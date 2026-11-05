package com.marmitt.core.application.handler.connection;

import com.marmitt.core.dto.connection.ConnectionKey;
import com.marmitt.core.dto.connection.ConnectionResultDto;
import com.marmitt.core.dto.events.WebSocketClosedEvent;
import com.marmitt.core.dto.events.WebSocketConnectedEvent;
import com.marmitt.core.dto.events.WebSocketFailedEvent;
import com.marmitt.core.dto.wrapper.WebSocketConnectionManager;
import com.marmitt.core.enums.StreamChannel;
import com.marmitt.core.ports.outbound.exchange.ExchangeAdapterDescriptor;
import com.marmitt.core.ports.outbound.exchange.streaming.UserStreamSession;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConnectionLostOrderDispatchGuardTest {

    private TrackingAdapterRepository adapterRepository;
    private ConnectionLostOrderDispatchGuard guard;
    private final WebSocketConnectionRepositoryPort connections = mock(WebSocketConnectionRepositoryPort.class);

    @BeforeEach
    void setup() {
        adapterRepository = new TrackingAdapterRepository();
        guard = new ConnectionLostOrderDispatchGuard(adapterRepository, connections);
    }

    @Test
    void unexpectedMarketClose_blocksDispatch() {
        WebSocketClosedEvent event = WebSocketClosedEvent.unexpected(
                "BINANCE", 1006, "Connection reset", UUID.randomUUID(), StreamChannel.MARKET);

        guard.onConnectionClosed(event);

        assertTrue(adapterRepository.isDispatchBlocked("BINANCE"));
    }

    @Test
    void unexpectedUserDataClose_blocksDispatch() {
        WebSocketClosedEvent event = WebSocketClosedEvent.unexpected(
                "BINANCE", 1006, "Connection reset", UUID.randomUUID(), StreamChannel.USER_DATA);

        guard.onConnectionClosed(event);

        assertTrue(adapterRepository.isDispatchBlocked("BINANCE"));
    }

    @Test
    void expectedClose_doesNotBlockDispatch() {
        WebSocketClosedEvent event = WebSocketClosedEvent.of(
                "BINANCE", 1000, "Normal closure", UUID.randomUUID(), StreamChannel.MARKET);

        guard.onConnectionClosed(event);

        assertFalse(adapterRepository.isDispatchBlocked("BINANCE"));
    }

    @Test
    void reconnect_unblocks_whenSameChannelRecovers() {
        WebSocketClosedEvent close = WebSocketClosedEvent.unexpected(
                "BINANCE", 1006, "Reset", UUID.randomUUID(), StreamChannel.MARKET);
        guard.onConnectionClosed(close);
        assertTrue(adapterRepository.isDispatchBlocked("BINANCE"));

        WebSocketConnectedEvent reconnect = WebSocketConnectedEvent.of(
                "BINANCE", "Connected", UUID.randomUUID(), StreamChannel.MARKET);
        guard.onConnectionReestablished(reconnect);

        assertFalse(adapterRepository.isDispatchBlocked("BINANCE"));
    }

    @Test
    void reconnect_keepBlocked_whenOtherChannelStillDown() {
        WebSocketClosedEvent marketClose = WebSocketClosedEvent.unexpected(
                "BINANCE", 1006, "Reset", UUID.randomUUID(), StreamChannel.MARKET);
        WebSocketClosedEvent userDataClose = WebSocketClosedEvent.unexpected(
                "BINANCE", 1006, "Reset", UUID.randomUUID(), StreamChannel.USER_DATA);
        guard.onConnectionClosed(marketClose);
        guard.onConnectionClosed(userDataClose);

        // Only MARKET reconnects
        WebSocketConnectedEvent marketReconnect = WebSocketConnectedEvent.of(
                "BINANCE", "Connected", UUID.randomUUID(), StreamChannel.MARKET);
        guard.onConnectionReestablished(marketReconnect);

        assertTrue(adapterRepository.isDispatchBlocked("BINANCE"),
                "dispatch must stay blocked while USER_DATA is still down");
    }

    @Test
    void reconnect_unblocks_whenBothChannelsRecover() {
        guard.onConnectionClosed(WebSocketClosedEvent.unexpected(
                "BINANCE", 1006, "Reset", UUID.randomUUID(), StreamChannel.MARKET));
        guard.onConnectionClosed(WebSocketClosedEvent.unexpected(
                "BINANCE", 1006, "Reset", UUID.randomUUID(), StreamChannel.USER_DATA));

        guard.onConnectionReestablished(WebSocketConnectedEvent.of(
                "BINANCE", "Connected", UUID.randomUUID(), StreamChannel.MARKET));
        assertTrue(adapterRepository.isDispatchBlocked("BINANCE"));

        guard.onConnectionReestablished(WebSocketConnectedEvent.of(
                "BINANCE", "Connected", UUID.randomUUID(), StreamChannel.USER_DATA));
        assertFalse(adapterRepository.isDispatchBlocked("BINANCE"));
    }

    @Test
    void connection_whenDispatchNotBlocked_isNoOp() {
        assertFalse(adapterRepository.isDispatchBlocked("BINANCE"));

        WebSocketConnectedEvent event = WebSocketConnectedEvent.of(
                "BINANCE", "Connected", UUID.randomUUID(), StreamChannel.MARKET);
        guard.onConnectionReestablished(event);

        assertFalse(adapterRepository.isDispatchBlocked("BINANCE"));
    }

    /** O socket novo segue de pe: nenhuma reconexao viria liberar o envio (T68). */
    @Test
    void lateEventsOfAReplacedConnection_doNotBlockDispatch() {
        WebSocketConnectionManager manager = WebSocketConnectionManager.forExchange("BINANCE");
        manager.setConnectionResult(ConnectionResultDto.connecting());
        manager.setConnectionResult(ConnectionResultDto.connected("up", UUID.randomUUID()));
        ConnectionKey key = ConnectionKey.market("BINANCE");
        when(connections.hasConnection(key)).thenReturn(true);
        when(connections.getConnection(key)).thenReturn(manager);

        guard.onConnectionFailed(WebSocketFailedEvent.of(
                "BINANCE", "Connection reset", UUID.randomUUID(), null, StreamChannel.MARKET));
        guard.onConnectionClosed(WebSocketClosedEvent.unexpected(
                "BINANCE", 1006, "Connection reset", UUID.randomUUID(), StreamChannel.MARKET));

        assertFalse(adapterRepository.isDispatchBlocked("BINANCE"));
    }

    @Test
    void socketFailure_onMarket_blocksDispatch() {
        WebSocketFailedEvent event = WebSocketFailedEvent.of(
                "BINANCE", "Connection reset", UUID.randomUUID(), null, StreamChannel.MARKET);

        guard.onConnectionFailed(event);

        assertTrue(adapterRepository.isDispatchBlocked("BINANCE"));
    }

    @Test
    void socketFailure_onUserData_blocksDispatch() {
        WebSocketFailedEvent event = WebSocketFailedEvent.of(
                "BINANCE", "Connection reset", UUID.randomUUID(), null, StreamChannel.USER_DATA);

        guard.onConnectionFailed(event);

        assertTrue(adapterRepository.isDispatchBlocked("BINANCE"));
    }

    @Test
    void socketFailure_thenReconnect_unblocks() {
        guard.onConnectionFailed(WebSocketFailedEvent.of(
                "BINANCE", "Connection reset", UUID.randomUUID(), null, StreamChannel.MARKET));
        assertTrue(adapterRepository.isDispatchBlocked("BINANCE"));

        guard.onConnectionReestablished(WebSocketConnectedEvent.of(
                "BINANCE", "Reconnected", UUID.randomUUID(), StreamChannel.MARKET));

        assertFalse(adapterRepository.isDispatchBlocked("BINANCE"));
    }

    static class TrackingAdapterRepository implements ExchangeAdapterRepositoryPort {
        private final Set<String> blocked = ConcurrentHashMap.newKeySet();

        @Override public void blockDispatch(String exchangeName) { blocked.add(exchangeName.toUpperCase()); }
        @Override public void unblockDispatch(String exchangeName) { blocked.remove(exchangeName.toUpperCase()); }
        @Override public boolean isDispatchBlocked(String exchangeName) { return blocked.contains(exchangeName.toUpperCase()); }

        @Override public Optional<ExchangeAdapterDescriptor> findAdapter(String n) { return Optional.empty(); }
        @Override public boolean hasAdapter(String n) { return false; }
        @Override public Set<String> getAllExchangeNames() { return Set.of(); }
        @Override public void storeActiveSession(UUID id, UserStreamSession s) {}
        @Override public Optional<UserStreamSession> findActiveSession(UUID id) { return Optional.empty(); }
        @Override public void removeActiveSession(UUID id) {}
        @Override public void registerPortfolioByAdapter(String n, UUID id) {}
    }
}
