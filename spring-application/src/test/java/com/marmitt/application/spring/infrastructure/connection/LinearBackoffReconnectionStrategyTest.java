package com.marmitt.application.spring.infrastructure.connection;

import com.marmitt.core.dto.connection.ConnectionKey;
import com.marmitt.core.dto.connection.ConnectionResultDto;
import com.marmitt.core.dto.events.WebSocketFailedEvent;
import com.marmitt.core.dto.websocket.request.StreamSubscriptionRequest;
import com.marmitt.core.dto.websocket.response.WebSocketConnectionResponse;
import com.marmitt.core.dto.wrapper.WebSocketConnectionManager;
import com.marmitt.core.enums.StreamAction;
import com.marmitt.core.enums.StreamChannel;
import com.marmitt.core.ports.inbound.websocket.ConnectMarketStreamPort;
import com.marmitt.core.ports.inbound.websocket.ConnectUserStreamPort;
import com.marmitt.core.ports.outbound.exchange.ExchangeAdapterDescriptor;
import com.marmitt.core.ports.outbound.exchange.streaming.UserStreamSession;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;
import com.marmitt.core.ports.outbound.websocket.WebSocketPort;
import com.marmitt.core.ports.outbound.websocket.WebSocketPortRegistryPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinearBackoffReconnectionStrategyTest {

    private static final String EXCHANGE = "BINANCE";
    private static final long NO_DELAY = 0;
    private static final long NEVER_RUNS = 3600;

    private final List<Object> published = new CopyOnWriteArrayList<>();

    @Test
    void failedAttemptIsReconnectedOnTheNextOne() throws InterruptedException {
        CountDownLatch connected = new CountDownLatch(1);
        LinearBackoffReconnectionStrategy strategy = strategy((name, req) -> {
            connected.countDown();
            return ok(name);
        }, NO_DELAY, 10);

        strategy.scheduleReconnect(EXCHANGE, StreamChannel.MARKET, null);

        assertTrue(connected.await(3, TimeUnit.SECONDS));
        assertTrue(published.isEmpty());
    }

    /**
     * T53 F19: a falha de cada tentativa chega depois, como uma falha nova da conexao aberta pela tentativa. A
     * contagem sobe a cada uma e, esgotadas as tentativas, a falha critica sai uma vez so.
     */
    @Test
    void asynchronousFailuresCountUpToTheCriticalFailure() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<LinearBackoffReconnectionStrategy> self = new AtomicReference<>();
        CountDownLatch critical = new CountDownLatch(1);
        LinearBackoffReconnectionStrategy strategy = new LinearBackoffReconnectionStrategy(
                new FailedConnections(), new StubAdapterRepo(), new StubWebSocketRegistry(),
                (name, req) -> {
                    attempts.incrementAndGet();
                    // a conexao aberta pela tentativa falha logo depois
                    self.get().scheduleReconnect(EXCHANGE, StreamChannel.MARKET, UUID.randomUUID());
                    return ok(name);
                },
                name -> Optional.of(ok(name)),
                event -> {
                    published.add(event);
                    critical.countDown();
                }, NO_DELAY, 3);
        self.set(strategy);

        strategy.scheduleReconnect(EXCHANGE, StreamChannel.MARKET, UUID.randomUUID());

        assertTrue(critical.await(3, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertEquals(3, attempts.get());
        assertEquals(1, published.size());
        WebSocketFailedEvent event = (WebSocketFailedEvent) published.get(0);
        assertTrue(event.isCritical());
        assertEquals(StreamChannel.MARKET, event.channel());
        assertEquals(3, event.attemptCount());
    }

    /** T53 F20: falha que chega com uma tentativa ja agendada nao abre uma segunda fila nem conta de novo. */
    @Test
    void failuresWhileAnAttemptIsScheduledDoNotOpenAnotherQueue() {
        LinearBackoffReconnectionStrategy strategy = strategy((name, req) -> ok(name), NEVER_RUNS, 1);

        for (int i = 0; i < 5; i++) {
            strategy.scheduleReconnect(EXCHANGE, StreamChannel.MARKET, UUID.randomUUID());
        }

        assertTrue(published.isEmpty(), "only one attempt was counted");
    }

    @Test
    void channelsAreCountedApart() {
        LinearBackoffReconnectionStrategy strategy = strategy((name, req) -> ok(name), NEVER_RUNS, 1);

        strategy.scheduleReconnect(EXCHANGE, StreamChannel.MARKET, UUID.randomUUID());
        strategy.scheduleReconnect(EXCHANGE, StreamChannel.USER_DATA, UUID.randomUUID());
        strategy.scheduleReconnect("COINBASE", StreamChannel.MARKET, UUID.randomUUID());

        assertTrue(published.isEmpty());
    }

    @Test
    void connectingAgainStartsTheCountOver() throws InterruptedException {
        LinearBackoffReconnectionStrategy strategy = strategy((name, req) -> ok(name), NEVER_RUNS, 1);

        strategy.scheduleReconnect(EXCHANGE, StreamChannel.MARKET, UUID.randomUUID());
        strategy.connectionEstablished(EXCHANGE, StreamChannel.MARKET);
        strategy.scheduleReconnect(EXCHANGE, StreamChannel.MARKET, UUID.randomUUID());

        assertTrue(published.isEmpty(), "the second failure is attempt 1 of a new streak");
    }

    @Test
    void afterTheCriticalFailureNothingIsScheduledUntilTheChannelConnects() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger();
        LinearBackoffReconnectionStrategy strategy = strategy((name, req) -> {
            attempts.incrementAndGet();
            return ok(name);
        }, NO_DELAY, 0);

        strategy.scheduleReconnect(EXCHANGE, StreamChannel.MARKET, null);
        strategy.scheduleReconnect(EXCHANGE, StreamChannel.MARKET, null);
        strategy.scheduleReconnect(EXCHANGE, StreamChannel.MARKET, null);
        Thread.sleep(100);

        assertEquals(1, published.size(), "the critical failure is published once");
        assertEquals(0, attempts.get());

        strategy.connectionEstablished(EXCHANGE, StreamChannel.MARKET);
        strategy.scheduleReconnect(EXCHANGE, StreamChannel.MARKET, null);

        assertEquals(2, published.size(), "a new streak after the connection came back");
    }

    /** Reconexao sincrona que falha conta como a proxima tentativa. */
    @Test
    void synchronousFailureSchedulesTheNextAttempt() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch critical = new CountDownLatch(1);
        LinearBackoffReconnectionStrategy strategy = new LinearBackoffReconnectionStrategy(
                new FailedConnections(), new StubAdapterRepo(), new StubWebSocketRegistry(),
                (name, req) -> ok(name),
                name -> {
                    attempts.incrementAndGet();
                    return Optional.of(new WebSocketConnectionResponse(null, "down", null, name, null, null,
                            false, false, false, true, null));
                },
                event -> {
                    published.add(event);
                    critical.countDown();
                }, NO_DELAY, 2);

        strategy.scheduleReconnect(EXCHANGE, StreamChannel.USER_DATA, null);

        assertTrue(critical.await(3, TimeUnit.SECONDS));
        assertEquals(2, attempts.get());
        assertEquals(1, published.size());
    }

    /** O coordenador de market data chegou antes: a tarefa nao abre outro socket nem escala para falha critica (T68). */
    @Test
    void scheduledAttemptDoesNothingWhenTheConnectionIsAlreadyBack() throws InterruptedException {
        List<String> reconnected = new CopyOnWriteArrayList<>();
        WebSocketConnectionManager manager = WebSocketConnectionManager.forExchange(EXCHANGE);
        manager.addRequestToHistory(new StreamSubscriptionRequest(EXCHANGE, List.of(), StreamAction.SUBSCRIBE));
        manager.setConnectionResult(ConnectionResultDto.connecting());
        manager.setConnectionResult(ConnectionResultDto.connected("up", UUID.randomUUID()));
        FailedConnections connections = new FailedConnections() {
            @Override
            public WebSocketConnectionManager getConnection(ConnectionKey key) {
                return manager;
            }
        };
        LinearBackoffReconnectionStrategy strategy = new LinearBackoffReconnectionStrategy(
                connections, new StubAdapterRepo(), new StubWebSocketRegistry(),
                (name, req) -> {
                    reconnected.add(name);
                    return null;
                },
                name -> {
                    reconnected.add(name);
                    return Optional.empty();
                },
                published::add, NO_DELAY, 10);

        strategy.scheduleReconnect(EXCHANGE, StreamChannel.MARKET, UUID.randomUUID());
        strategy.scheduleReconnect(EXCHANGE, StreamChannel.USER_DATA, UUID.randomUUID());
        Thread.sleep(200);

        assertTrue(reconnected.isEmpty());
        assertTrue(published.isEmpty());
    }

    /**
     * T53 F23: o coordenador trocou a conexao do stream de usuario e ela tambem caiu. A tarefa da sessao
     * antiga nao fecha nada, mas reconecta: antes ela era pulada e a fila do canal acabava.
     */
    @Test
    void attemptForASupersededSessionStillReconnectsTheDownChannel() throws InterruptedException {
        UUID failedSession = UUID.randomUUID();
        UUID coordinatorConnection = UUID.randomUUID();
        FailedConnections connections = new FailedConnections() {
            @Override
            public WebSocketConnectionManager getConnection(ConnectionKey key) {
                WebSocketConnectionManager mgr = WebSocketConnectionManager.forExchange(key.exchangeName());
                mgr.setConnectionResult(ConnectionResultDto.failure("Connection refused", coordinatorConnection,
                        new IllegalStateException("refused")));
                return mgr;
            }
        };
        CountDownLatch reconnected = new CountDownLatch(1);
        LinearBackoffReconnectionStrategy strategy = new LinearBackoffReconnectionStrategy(
                connections, new StubAdapterRepo(), new StubWebSocketRegistry(),
                (name, req) -> ok(name),
                name -> {
                    reconnected.countDown();
                    return Optional.of(ok(name));
                },
                published::add, NO_DELAY, 10);

        strategy.scheduleReconnect(EXCHANGE, StreamChannel.USER_DATA, failedSession);

        assertTrue(reconnected.await(3, TimeUnit.SECONDS), "the down channel must be reconnected");
    }

    private LinearBackoffReconnectionStrategy strategy(ConnectMarketStreamPort market, long delay, int maxAttempts) {
        ConnectUserStreamPort user = name -> Optional.of(ok(name));
        return new LinearBackoffReconnectionStrategy(new FailedConnections(), new StubAdapterRepo(),
                new StubWebSocketRegistry(), market, user, published::add, delay, maxAttempts);
    }

    private static WebSocketConnectionResponse ok(String name) {
        return new WebSocketConnectionResponse(null, "ok", null, name, null, null, true, true, false, false, null);
    }

    // ---- stubs ----

    /** Toda conexao consultada esta em erro, com a assinatura de market data no historico. */
    static class FailedConnections implements WebSocketConnectionRepositoryPort {
        @Override
        public WebSocketConnectionManager getConnection(ConnectionKey key) {
            WebSocketConnectionManager mgr = WebSocketConnectionManager.forExchange(key.exchangeName());
            mgr.addRequestToHistory(new StreamSubscriptionRequest(key.exchangeName(), List.of(), StreamAction.SUBSCRIBE));
            mgr.setConnectionResult(ConnectionResultDto.failure("test", "test"));
            return mgr;
        }
        @Override public void registerConnection(ConnectionKey key) {}
        @Override public boolean hasConnection(ConnectionKey key) { return true; }
        @Override public Set<String> getAllExchangeNames() { return Set.of(); }
        @Override public java.util.Map<ConnectionKey, WebSocketConnectionManager> getAllConnections() { return java.util.Map.of(); }
    }

    static class StubAdapterRepo implements ExchangeAdapterRepositoryPort {
        @Override public Optional<ExchangeAdapterDescriptor> findAdapter(String n) { return Optional.empty(); }
        @Override public boolean hasAdapter(String n) { return false; }
        @Override public Set<String> getAllExchangeNames() { return Set.of(); }
        @Override public void storeActiveSession(UUID id, UserStreamSession s) {}
        @Override public Optional<UserStreamSession> findActiveSession(UUID id) { return Optional.empty(); }
        @Override public void removeActiveSession(UUID id) {}
        @Override public void blockDispatch(String n) {}
        @Override public void unblockDispatch(String n) {}
        @Override public boolean isDispatchBlocked(String n) { return false; }
        @Override public void registerPortfolioByAdapter(String n, UUID id) {}
    }

    static class StubWebSocketRegistry implements WebSocketPortRegistryPort {
        @Override public void register(String n, WebSocketPort p) {}
        @Override public Optional<WebSocketPort> findByExchangeName(String n) { return Optional.empty(); }
        @Override public void registerUserStream(String n, WebSocketPort p) {}
        @Override public Optional<WebSocketPort> findUserStreamByExchangeName(String n) { return Optional.empty(); }
    }
}
