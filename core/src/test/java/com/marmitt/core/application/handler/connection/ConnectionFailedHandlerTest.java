package com.marmitt.core.application.handler.connection;

import com.marmitt.core.dto.connection.ConnectionKey;
import com.marmitt.core.dto.connection.ConnectionResultDto;
import com.marmitt.core.dto.events.WebSocketFailedEvent;
import com.marmitt.core.dto.wrapper.WebSocketConnectionManager;
import com.marmitt.core.enums.ConnectionStatus;
import com.marmitt.core.enums.StreamChannel;
import com.marmitt.core.ports.outbound.connection.ReconnectionStrategyPort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.EOFException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectionFailedHandlerTest {

    private static final String EXCHANGE = "BINANCE";

    private final WebSocketConnectionRepositoryPort connections = mock(WebSocketConnectionRepositoryPort.class);
    private final ReconnectionStrategyPort reconnection = mock(ReconnectionStrategyPort.class);
    private final ConnectionFailedHandler handler = new ConnectionFailedHandler(connections, reconnection);
    private final WebSocketConnectionManager manager = WebSocketConnectionManager.forExchange(EXCHANGE);
    private UUID connectionId;

    @BeforeEach
    void connected() {
        when(connections.getConnection(any(ConnectionKey.class))).thenReturn(manager);
        manager.setConnectionResult(ConnectionResultDto.connecting());
        connectionId = UUID.randomUUID();
        manager.setConnectionResult(ConnectionResultDto.connected("up", connectionId));
    }

    /** T53 rodada 11: a conexao cortada chega como EOFException sem mensagem. */
    @ParameterizedTest
    @EnumSource(value = StreamChannel.class, names = {"MARKET", "USER_DATA"})
    void abruptFailureWithoutMessageIsRecordedAndReconnects(StreamChannel channel) {
        handler.execute(WebSocketFailedEvent.of(EXCHANGE, "Connection failed: null", connectionId,
                new EOFException(), channel));

        assertEquals(ConnectionStatus.ERROR, manager.getConnectionResult().status());
        assertEquals("EOFException", manager.getConnectionResult().metadata().get("errorMessage"));
        verify(reconnection).scheduleReconnect(EXCHANGE, channel, connectionId);
    }

    @Test
    void lateFailureOfAReplacedConnectionChangesNothing() {
        handler.execute(WebSocketFailedEvent.of(EXCHANGE, "Connection failed: reset", UUID.randomUUID(),
                new EOFException("reset"), StreamChannel.MARKET));

        assertEquals(ConnectionStatus.CONNECTED, manager.getConnectionResult().status());
        verify(reconnection, never()).scheduleReconnect(anyString(), any(), any());
    }
}
