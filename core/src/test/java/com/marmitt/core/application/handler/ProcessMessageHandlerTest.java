package com.marmitt.core.application.handler;

import com.marmitt.core.dto.connection.ConnectionKey;
import com.marmitt.core.dto.processing.ProcessingResult;
import com.marmitt.core.dto.websocket.MessageContext;
import com.marmitt.core.dto.wrapper.WebSocketConnectionManager;
import com.marmitt.core.ports.outbound.exchange.ExchangeAdapterDescriptor;
import com.marmitt.core.ports.outbound.exchange.streaming.ExchangeStreamingPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.ListenerRepositoryPort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Mensagem do socket de market data: so erro de processamento conta como erro da conexao. */
class ProcessMessageHandlerTest {

    private final WebSocketConnectionRepositoryPort connections = mock(WebSocketConnectionRepositoryPort.class);
    private final ExchangeAdapterRepositoryPort adapters = mock(ExchangeAdapterRepositoryPort.class);
    private final ExchangeStreamingPort streaming = mock(ExchangeStreamingPort.class);
    private final WebSocketConnectionManager market = mock(WebSocketConnectionManager.class);
    private final ProcessMessageHandler handler =
            new ProcessMessageHandler(connections, adapters, mock(ListenerRepositoryPort.class));
    private final MessageContext context = MessageContext.create("BINANCE", UUID.randomUUID());

    @BeforeEach
    void binance() {
        ExchangeAdapterDescriptor binance = mock(ExchangeAdapterDescriptor.class);
        when(binance.streaming()).thenReturn(streaming);
        when(adapters.findAdapter("BINANCE")).thenReturn(Optional.of(binance));
        when(connections.getConnection(ConnectionKey.market("BINANCE"))).thenReturn(market);
    }

    /** T53 F26: a confirmacao de uma assinatura e ignorada, sem contar erro na conexao. */
    @Test
    void ignoredMessageIsNotCountedAsAConnectionError() {
        doReturn(ProcessingResult.ignored("c", "stream-request-accepted")).when(streaming).processMessage(anyString(), any());

        handler.execute("{\"result\":null,\"id\":1}", context);

        verify(market).onMessageReceived();
        verify(market, never()).onMessageError(anyString());
    }

    @Test
    void processingErrorIsCountedAsAConnectionError() {
        doReturn(ProcessingResult.error("c", "Unrecognized message", "{}")).when(streaming).processMessage(anyString(), any());

        handler.execute("{}", context);

        verify(market).onMessageError("Unrecognized message");
    }
}
