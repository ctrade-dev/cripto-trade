package com.marmitt.application.spring.infrastructure.connection;

import com.marmitt.core.dto.connection.ConnectionKey;
import com.marmitt.core.dto.events.WebSocketFailedEvent;
import com.marmitt.core.dto.websocket.request.StreamSubscriptionRequest;
import com.marmitt.core.dto.websocket.response.WebSocketConnectionResponse;
import com.marmitt.core.dto.wrapper.WebSocketConnectionManager;
import com.marmitt.core.enums.ConnectionStatus;
import com.marmitt.core.enums.StreamChannel;
import com.marmitt.core.ports.inbound.websocket.ConnectMarketStreamPort;
import com.marmitt.core.ports.inbound.websocket.ConnectUserStreamPort;
import com.marmitt.core.ports.outbound.connection.ReconnectionStrategyPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;
import com.marmitt.core.ports.outbound.websocket.WebSocketPortRegistryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reconexao com espera linear ({@code base-delay-seconds} x tentativa), contada por canal (exchange + canal).
 *
 * <p>Cada falha conta uma tentativa e agenda a proxima, uma por vez: falhas que chegam com uma tentativa ja
 * agendada (de outro caminho que abriu o canal, como o coordenador de market data) nao abrem uma segunda fila
 * (T53 F20). A contagem so zera quando o canal conecta. Passado {@code max-attempts}, publica a falha critica uma
 * vez e para de agendar ate o canal voltar (T53 F19).
 */
@Slf4j
public class LinearBackoffReconnectionStrategy implements ReconnectionStrategyPort {

    /** Outro caminho (o coordenador de market data, T56) ja recuperou ou esta recuperando a conexao. */
    private static final Set<ConnectionStatus> RECOVERED_OR_RECOVERING = Set.of(
            ConnectionStatus.CONNECTED, ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING);

    private final WebSocketConnectionRepositoryPort connectionRepository;
    private final ExchangeAdapterRepositoryPort adapterRepository;
    private final WebSocketPortRegistryPort webSocketRegistry;
    private final ConnectMarketStreamPort connectMarketStreamPort;
    private final ConnectUserStreamPort connectUserStreamPort;
    private final ApplicationEventPublisher eventPublisher;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final long baseDelaySeconds;
    private final int maxAttempts;

    /** Falhas seguidas por canal; guardado por {@code this}. */
    private final Map<ConnectionKey, FailureStreak> streaks = new HashMap<>();

    public LinearBackoffReconnectionStrategy(
            WebSocketConnectionRepositoryPort connectionRepository,
            ExchangeAdapterRepositoryPort adapterRepository,
            WebSocketPortRegistryPort webSocketRegistry,
            ConnectMarketStreamPort connectMarketStreamPort,
            ConnectUserStreamPort connectUserStreamPort,
            ApplicationEventPublisher eventPublisher,
            @Value("${reconnect.base-delay-seconds:5}") long baseDelaySeconds,
            @Value("${reconnect.max-attempts:10}") int maxAttempts) {
        this.connectionRepository = connectionRepository;
        this.adapterRepository = adapterRepository;
        this.webSocketRegistry = webSocketRegistry;
        this.connectMarketStreamPort = connectMarketStreamPort;
        this.connectUserStreamPort = connectUserStreamPort;
        this.eventPublisher = eventPublisher;
        this.baseDelaySeconds = baseDelaySeconds;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public void scheduleReconnect(String exchangeName, StreamChannel channel, UUID sessionToClose) {
        ConnectionKey key = new ConnectionKey(exchangeName, channel);
        synchronized (this) {
            FailureStreak streak = streaks.computeIfAbsent(key, k -> new FailureStreak());
            if (streak.exhausted) {
                log.debug("Reconnect attempts exhausted - exchange={} channel={}: waiting for the connection to come back",
                        exchangeName, channel);
                return;
            }
            if (streak.pending != null) {
                log.debug("Reconnect already scheduled - exchange={} channel={}: failure not counted again",
                        exchangeName, channel);
                return;
            }
            streak.failures++;
            int attempt = streak.failures;
            if (attempt <= maxAttempts) {
                long delay = baseDelaySeconds * attempt;
                log.info("Scheduling reconnect attempt {}/{} - exchange={} channel={} in {}s",
                        attempt, maxAttempts, exchangeName, channel, delay);
                streak.pending = scheduler.schedule(
                        () -> attempt(key, sessionToClose, attempt), delay, TimeUnit.SECONDS);
                return;
            }
            streak.exhausted = true;
        }
        log.error("Max reconnect attempts ({}) reached - exchange={} channel={} — publishing critical failure",
                maxAttempts, exchangeName, channel);
        eventPublisher.publishEvent(WebSocketFailedEvent.critical(
                exchangeName, "Max reconnect attempts reached", channel, maxAttempts));
    }

    @Override
    public void connectionEstablished(String exchangeName, StreamChannel channel) {
        FailureStreak streak;
        synchronized (this) {
            streak = streaks.remove(new ConnectionKey(exchangeName, channel));
            if (streak != null && streak.pending != null) {
                streak.pending.cancel(false);
            }
        }
        if (streak != null && streak.failures > 0) {
            log.info("Reconnected after {} failed attempt(s) - exchange={} channel={}",
                    streak.failures, exchangeName, channel);
        }
    }

    private void attempt(ConnectionKey key, UUID sessionToClose, int attempt) {
        synchronized (this) {
            FailureStreak streak = streaks.get(key);
            if (streak != null) {
                streak.pending = null;
            }
        }
        String exchangeName = key.exchangeName();
        StreamChannel channel = key.channel();
        try {
            ConnectionStatus status = connectionRepository.getConnection(key).getConnectionResult().status();
            if (RECOVERED_OR_RECOVERING.contains(status)) {
                log.debug("Skipping reconnect attempt {} - exchange={} channel={} already {}",
                        attempt, exchangeName, channel, status);
                return;
            }
            if (channel == StreamChannel.MARKET) {
                reconnectMarketStream(exchangeName, attempt);
            } else {
                reconnectUserStream(exchangeName, sessionToClose, attempt);
            }
        } catch (Exception e) {
            log.error("Reconnect attempt {} failed - exchange={} channel={}: {}",
                    attempt, exchangeName, channel, e.getMessage());
            scheduleReconnect(exchangeName, channel, null);
        }
    }

    private void reconnectMarketStream(String exchangeName, int attempt) {
        ConnectionKey key = ConnectionKey.market(exchangeName);
        WebSocketConnectionManager manager = connectionRepository.getConnection(key);
        Optional<StreamSubscriptionRequest> subscription = manager.accumulatedSubscription();
        if (subscription.isEmpty()) {
            log.warn("No subscription in request history for market reconnect - exchange={}", exchangeName);
            return;
        }
        log.info("Reconnecting market stream - exchange={} attempt={} pairs={}", exchangeName, attempt,
                subscription.get().getCurrencyPairs().size());
        connectMarketStreamPort.execute(exchangeName, subscription.get());
    }

    private void reconnectUserStream(String exchangeName, UUID sessionToClose, int attempt) {
        if (sessionToClose != null && isCurrentUserStreamSession(exchangeName, sessionToClose)) {
            adapterRepository.findActiveSession(sessionToClose).ifPresent(s -> {
                s.close();
                adapterRepository.removeActiveSession(sessionToClose);
            });
            webSocketRegistry.findUserStreamByExchangeName(exchangeName)
                    .ifPresent(ws -> ws.disconnect(exchangeName, sessionToClose));
        }
        log.info("Reconnecting user data stream - exchange={} attempt={}", exchangeName, attempt);
        WebSocketConnectionResponse response = connectUserStreamPort.execute(exchangeName)
                .orElseThrow(() -> new RuntimeException("No user stream adapter for exchange: " + exchangeName));
        if (response.isFailed()) {
            throw new RuntimeException("User stream reconnect failed: " + response.message());
        }
    }

    /**
     * A sessao que falhou ainda e a do stream? Se outra tentativa (o coordenador) ja trocou a conexao, a
     * sessao antiga nao e fechada aqui, mas a reconexao segue: {@link #attempt} so chega aqui com o canal
     * fora, entao a conexao nova tambem caiu. Pular a tentativa encerrava a fila do canal (T53 F23).
     */
    private boolean isCurrentUserStreamSession(String exchangeName, UUID sessionToClose) {
        UUID currentId = connectionRepository.getConnection(ConnectionKey.userStream(exchangeName))
                .getConnectionResult().connectionId();
        if (currentId != null && !currentId.equals(sessionToClose)) {
            log.debug("Reconnect task for session={} — stream already moved to connection={}: not closing the old session",
                    sessionToClose, currentId);
            return false;
        }
        return true;
    }

    /** Falhas seguidas de um canal desde a ultima conexao. */
    private static final class FailureStreak {
        int failures;
        ScheduledFuture<?> pending;
        boolean exhausted;
    }
}
