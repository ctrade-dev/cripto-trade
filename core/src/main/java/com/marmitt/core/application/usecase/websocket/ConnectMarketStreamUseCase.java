package com.marmitt.core.application.usecase.websocket;

import com.marmitt.core.dto.connection.ConnectionKey;
import com.marmitt.core.dto.connection.ConnectionResultDto;
import com.marmitt.core.dto.websocket.mapper.ConnectionResultMapper;
import com.marmitt.core.dto.websocket.request.StreamSubscriptionRequest;
import com.marmitt.core.dto.websocket.response.WebSocketConnectionResponse;
import com.marmitt.core.dto.wrapper.WebSocketConnectionManager;
import com.marmitt.core.enums.ConnectionStatus;
import com.marmitt.core.ports.inbound.websocket.ConnectMarketStreamPort;
import com.marmitt.core.ports.outbound.exchange.ExchangeAdapterDescriptor;
import com.marmitt.core.ports.outbound.exchange.streaming.ExchangeStreamingPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;
import com.marmitt.core.ports.outbound.websocket.WebSocketPort;
import com.marmitt.core.ports.outbound.websocket.WebSocketPortRegistryPort;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.Set;

@Slf4j
public class ConnectMarketStreamUseCase implements ConnectMarketStreamPort {

    /** Canal de pe ou a caminho: nao ha o que abrir (o mesmo criterio do stream de usuario). */
    private static final Set<ConnectionStatus> UP_OR_GOING = Set.of(ConnectionStatus.CONNECTED,
            ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING, ConnectionStatus.DISCONNECTING,
            ConnectionStatus.CLOSING);

    private final WebSocketConnectionRepositoryPort connectionRepository;
    private final ExchangeAdapterRepositoryPort adapterRepository;
    private final WebSocketPortRegistryPort webSocketRegistry;

    public ConnectMarketStreamUseCase(WebSocketConnectionRepositoryPort connectionRepository,
                                      ExchangeAdapterRepositoryPort adapterRepository,
                                      WebSocketPortRegistryPort webSocketRegistry) {
        this.connectionRepository = connectionRepository;
        this.adapterRepository = adapterRepository;
        this.webSocketRegistry = webSocketRegistry;
    }

    @Override
    public WebSocketConnectionResponse execute(final String exchangeName, final StreamSubscriptionRequest parameters) {
        Optional<ExchangeAdapterDescriptor> adapterOpt = adapterRepository.findAdapter(exchangeName);
        if (adapterOpt.isEmpty()) {
            return ConnectionResultMapper.toResponse(
                    ConnectionResultDto.failure(this.getClass().getSimpleName(), "Exchange does not exist"),
                    exchangeName);
        }

        ConnectionKey marketKey = ConnectionKey.market(exchangeName);
        connectionRepository.registerConnection(marketKey);
        WebSocketConnectionManager current = connectionRepository.getConnection(marketKey);
        ConnectionStatus status = current.getConnectionResult().status();
        if (UP_OR_GOING.contains(status)) {
            // Conectar de novo um canal de pe e transicao invalida (T53 F24): o pedido manual ou de outro
            // caminho devolve o estado atual, como no stream de usuario.
            log.debug("Market stream already {} - skipping connect - exchange={}", status, exchangeName);
            return ConnectionResultMapper.toResponse(current.getConnectionResult(), exchangeName);
        }

        WebSocketConnectionManager manager = prepareMarketManager(current, parameters);
        ExchangeStreamingPort streaming = adapterOpt.get().streaming();
        String url = streaming.buildConnectionUrl(parameters, exchangeName);
        WebSocketPort webSocket = webSocketRegistry.findByExchangeName(exchangeName)
                .orElseThrow(() -> new IllegalStateException("No WebSocket registered for exchange: " + exchangeName));
        webSocket.connect(url, exchangeName, manager.getConnectionId());

        return ConnectionResultMapper.toResponse(manager.getConnectionResult(), exchangeName);
    }

    private WebSocketConnectionManager prepareMarketManager(WebSocketConnectionManager manager,
                                                            StreamSubscriptionRequest parameters) {
        ConnectionStatus currentStatus = manager.getConnectionResult().status();
        boolean isReconnect = currentStatus == ConnectionStatus.ERROR || currentStatus == ConnectionStatus.CLOSED;
        manager.setConnectionResult(isReconnect
                ? ConnectionResultDto.reconnecting(1, 1)
                : ConnectionResultDto.connecting());
        manager.addRequestToHistory(parameters);

        return manager;
    }
}
