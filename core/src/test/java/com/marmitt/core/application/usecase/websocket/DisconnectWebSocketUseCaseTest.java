package com.marmitt.core.application.usecase.websocket;

import com.marmitt.core.dto.connection.ConnectionKey;
import com.marmitt.core.dto.connection.ConnectionResultDto;
import com.marmitt.core.dto.wrapper.WebSocketConnectionManager;
import com.marmitt.core.enums.ConnectionStatus;
import com.marmitt.core.ports.outbound.exchange.ExchangeAdapterDescriptor;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;
import com.marmitt.core.ports.outbound.websocket.WebSocketPort;
import com.marmitt.core.ports.outbound.websocket.WebSocketPortRegistryPort;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DisconnectWebSocketUseCaseTest {

    private static final String EXCHANGE = "BINANCE";

    /** T53 F24: com o stream de usuario em ERROR o disconnect respondia 500 e parava no meio. */
    @Test
    void userStreamInErrorDoesNotStopTheDisconnect() {
        WebSocketConnectionRepositoryPort connections = mock(WebSocketConnectionRepositoryPort.class);
        ExchangeAdapterRepositoryPort adapters = mock(ExchangeAdapterRepositoryPort.class);
        WebSocketPortRegistryPort registry = mock(WebSocketPortRegistryPort.class);
        ExchangeAdapterDescriptor binance = mock(ExchangeAdapterDescriptor.class);
        WebSocketPort marketSocket = mock(WebSocketPort.class);
        WebSocketPort userSocket = mock(WebSocketPort.class);

        UUID marketId = UUID.randomUUID();
        WebSocketConnectionManager market = WebSocketConnectionManager.forExchange(EXCHANGE);
        market.setConnectionResult(ConnectionResultDto.connecting());
        market.setConnectionResult(ConnectionResultDto.connected("up", marketId));
        UUID userId = UUID.randomUUID();
        WebSocketConnectionManager user = WebSocketConnectionManager.forExchange(EXCHANGE);
        user.setConnectionResult(ConnectionResultDto.failure("refused", userId, new IllegalStateException()));

        when(adapters.findAdapter(EXCHANGE)).thenReturn(Optional.of(binance));
        when(binance.hasUserStreamSession()).thenReturn(true);
        when(connections.getConnection(ConnectionKey.market(EXCHANGE))).thenReturn(market);
        when(connections.getConnection(ConnectionKey.userStream(EXCHANGE))).thenReturn(user);
        when(registry.findByExchangeName(EXCHANGE)).thenReturn(Optional.of(marketSocket));
        when(registry.findUserStreamByExchangeName(EXCHANGE)).thenReturn(Optional.of(userSocket));

        assertDoesNotThrow(() -> new DisconnectWebSocketUseCase(connections, adapters, registry).execute(EXCHANGE));

        assertEquals(ConnectionStatus.DISCONNECTING, market.getConnectionResult().status());
        assertEquals(ConnectionStatus.ERROR, user.getConnectionResult().status());
        verify(marketSocket).disconnect(EXCHANGE, marketId);
        verify(userSocket).disconnect(EXCHANGE, userId);
    }
}
