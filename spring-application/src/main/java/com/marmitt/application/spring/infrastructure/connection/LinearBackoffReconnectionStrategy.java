package com.marmitt.application.spring.infrastructure.connection;

import com.marmitt.core.dto.connection.ConnectionKey;
import com.marmitt.core.dto.events.WebSocketFailedEvent;
import com.marmitt.core.dto.websocket.request.MessageRequest;
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

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
    public void scheduleReconnect(String exchangeName, StreamChannel channel, UUID sessionToClose, int attempt) {
        if (attempt > maxAttempts) {
            log.error("Max reconnect attempts ({}) reached - exchange={} channel={} — publishing critical failure",
                    maxAttempts, exchangeName, channel);
            eventPublisher.publishEvent(WebSocketFailedEvent.critical(
                    exchangeName, "Max reconnect attempts reached", channel));
            return;
        }

        long delay = baseDelaySeconds * attempt;
        log.info("Scheduling reconnect attempt {}/{} - exchange={} channel={} in {}s",
                attempt, maxAttempts, exchangeName, channel, delay);

        scheduler.schedule(() -> {
            try {
                ConnectionStatus status = connectionRepository
                        .getConnection(new ConnectionKey(exchangeName, channel)).getConnectionResult().status();
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
                scheduleReconnect(exchangeName, channel, null, attempt + 1);
            }
        }, delay, TimeUnit.SECONDS);
    }

    private void reconnectMarketStream(String exchangeName, int attempt) {
        ConnectionKey key = ConnectionKey.market(exchangeName);
        WebSocketConnectionManager manager = connectionRepository.getConnection(key);
        if (manager.getRequestHistory().isEmpty()) {
            log.warn("No request history for market reconnect - exchange={}", exchangeName);
            return;
        }
        MessageRequest lastRequest = manager.getLastRequestHistory();
        if (!(lastRequest instanceof StreamSubscriptionRequest subscriptionRequest)) {
            log.warn("Last request is not a StreamSubscriptionRequest - exchange={}", exchangeName);
            return;
        }
        log.info("Reconnecting market stream - exchange={} attempt={}", exchangeName, attempt);
        connectMarketStreamPort.execute(exchangeName, subscriptionRequest);
    }

    private void reconnectUserStream(String exchangeName, UUID sessionToClose, int attempt) {
        if (sessionToClose != null) {
            ConnectionKey key = ConnectionKey.userStream(exchangeName);
            WebSocketConnectionManager manager = connectionRepository.getConnection(key);
            UUID currentId = manager.getConnectionResult().connectionId();
            if (currentId != null && !currentId.equals(sessionToClose)) {
                log.debug("Skipping stale reconnect task for session={} — stream already moved to connection={}",
                        sessionToClose, currentId);
                return;
            }
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
}
