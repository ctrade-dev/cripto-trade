package com.marmitt.core.application.usecase.websocket;

import com.marmitt.core.dto.connection.ConnectionKey;
import com.marmitt.core.dto.connection.ConnectionResultDto;
import com.marmitt.core.dto.websocket.mapper.ConnectionResultMapper;
import com.marmitt.core.dto.websocket.response.WebSocketConnectionResponse;
import com.marmitt.core.dto.wrapper.WebSocketConnectionManager;
import com.marmitt.core.enums.ConnectionStatus;
import com.marmitt.core.ports.inbound.websocket.DisconnectWebSocketPort;
import com.marmitt.core.ports.outbound.exchange.ExchangeAdapterDescriptor;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;
import com.marmitt.core.ports.outbound.websocket.WebSocketPort;
import com.marmitt.core.ports.outbound.websocket.WebSocketPortRegistryPort;

import java.util.Optional;
import java.util.UUID;

public class DisconnectWebSocketUseCase implements DisconnectWebSocketPort {

    private final WebSocketConnectionRepositoryPort connectionRepository;
    private final ExchangeAdapterRepositoryPort adapterRepository;
    private final WebSocketPortRegistryPort webSocketRegistry;

    public DisconnectWebSocketUseCase(WebSocketConnectionRepositoryPort connectionRepository,
                                      ExchangeAdapterRepositoryPort adapterRepository,
                                      WebSocketPortRegistryPort webSocketRegistry) {
        this.connectionRepository = connectionRepository;
        this.adapterRepository = adapterRepository;
        this.webSocketRegistry = webSocketRegistry;
    }

    @Override
    public WebSocketConnectionResponse execute(final String exchangeName) {
        WebSocketConnectionManager manager = connectionRepository.getConnection(ConnectionKey.market(exchangeName));

        Optional<ExchangeAdapterDescriptor> adapterOpt = adapterRepository.findAdapter(exchangeName);
        if (adapterOpt.isEmpty()) {
            return ConnectionResultMapper.toResponse(
                    ConnectionResultDto.failure(this.getClass().getSimpleName(), "Exchange does not exist"),
                    exchangeName);
        }

        UUID connectionId = manager.getConnectionId();
        markDisconnecting(manager);
        WebSocketPort webSocket = webSocketRegistry.findByExchangeName(exchangeName)
                .orElseThrow(() -> new IllegalStateException("No WebSocket registered for exchange: " + exchangeName));
        webSocket.disconnect(exchangeName, connectionId);

        if (adapterOpt.get().hasUserStreamSession()) {
            WebSocketConnectionManager userManager = connectionRepository.getConnection(ConnectionKey.userStream(exchangeName));
            if (userManager != null) {
                UUID userConnectionId = userManager.getConnectionId();
                markDisconnecting(userManager);
                adapterRepository.findActiveSession(userConnectionId).ifPresent(s -> {
                    s.close();
                    adapterRepository.removeActiveSession(userConnectionId);
                });
                webSocketRegistry.findUserStreamByExchangeName(exchangeName)
                        .ifPresent(ws -> ws.disconnect(exchangeName, userConnectionId));
            }
        }

        return ConnectionResultMapper.toResponse(manager.getConnectionResult(), exchangeName);
    }

    /**
     * So canal conectado passa a {@code DISCONNECTING} (unica transicao valida). Canal em erro ou ja fechado
     * fica como esta, e o resto do disconnect (sessao, socket) segue: antes, o pedido manual respondia 500 com
     * o stream de usuario em {@code ERROR} e parava no meio (T53 F24).
     */
    private static void markDisconnecting(WebSocketConnectionManager manager) {
        if (manager.getConnectionResult().status() == ConnectionStatus.CONNECTED) {
            manager.setConnectionResult(ConnectionResultDto.disconnecting("Manual disconnection requested",
                    manager.getConnectionId()));
        }
    }
}
