package com.marmitt.core.application.usecase.websocket;

import com.marmitt.core.dto.connection.ConnectionKey;
import com.marmitt.core.dto.connection.ConnectionResultDto;
import com.marmitt.core.dto.websocket.request.StreamSubscriptionRequest;
import com.marmitt.core.dto.websocket.response.WebSocketConnectionResponse;
import com.marmitt.core.dto.wrapper.WebSocketConnectionManager;
import com.marmitt.core.enums.ConnectionStatus;
import com.marmitt.core.enums.StreamAction;
import com.marmitt.core.ports.outbound.exchange.ExchangeAdapterDescriptor;
import com.marmitt.core.ports.outbound.exchange.streaming.ExchangeStreamingPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;
import com.marmitt.core.ports.outbound.websocket.WebSocketPort;
import com.marmitt.core.ports.outbound.websocket.WebSocketPortRegistryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectMarketStreamUseCaseTest {

    private static final String EXCHANGE = "BINANCE";
    private static final StreamSubscriptionRequest REQUEST =
            new StreamSubscriptionRequest(EXCHANGE, List.of(), StreamAction.SUBSCRIBE);

    private final WebSocketConnectionRepositoryPort connections = mock(WebSocketConnectionRepositoryPort.class);
    private final ExchangeAdapterRepositoryPort adapters = mock(ExchangeAdapterRepositoryPort.class);
    private final WebSocketPortRegistryPort registry = mock(WebSocketPortRegistryPort.class);
    private final ExchangeAdapterDescriptor binance = mock(ExchangeAdapterDescriptor.class);
    private final ExchangeStreamingPort streaming = mock(ExchangeStreamingPort.class);
    private final WebSocketPort socket = mock(WebSocketPort.class);
    private final WebSocketConnectionManager manager = WebSocketConnectionManager.forExchange(EXCHANGE);
    private final ConnectMarketStreamUseCase useCase = new ConnectMarketStreamUseCase(connections, adapters, registry);

    @BeforeEach
    void binance() {
        when(adapters.findAdapter(EXCHANGE)).thenReturn(Optional.of(binance));
        when(binance.streaming()).thenReturn(streaming);
        when(streaming.buildConnectionUrl(any(), anyString())).thenReturn("wss://stream");
        when(registry.findByExchangeName(EXCHANGE)).thenReturn(Optional.of(socket));
        when(connections.getConnection(ConnectionKey.market(EXCHANGE))).thenReturn(manager);
    }

    /** T53 F24: o connect manual com o market de pe respondia 500 (transicao CONNECTED -> CONNECTING). */
    @Test
    void connectedChannelIsLeftAloneAndItsStatusReturned() {
        manager.setConnectionResult(ConnectionResultDto.connecting());
        manager.setConnectionResult(ConnectionResultDto.connected("up", UUID.randomUUID()));

        WebSocketConnectionResponse response = useCase.execute(EXCHANGE, REQUEST);

        assertEquals(ConnectionStatus.CONNECTED, response.status());
        verify(socket, never()).connect(anyString(), anyString(), any());
    }

    @Test
    void droppedChannelIsOpenedAgain() {
        manager.setConnectionResult(ConnectionResultDto.failure("down", UUID.randomUUID(), new IllegalStateException()));

        useCase.execute(EXCHANGE, REQUEST);

        assertEquals(ConnectionStatus.RECONNECTING, manager.getConnectionResult().status());
        verify(socket).connect(eq("wss://stream"), eq(EXCHANGE), any());
    }
}
