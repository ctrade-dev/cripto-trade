package com.marmitt.application.spring.config.core;

import com.marmitt.application.spring.infrastructure.connection.LinearBackoffReconnectionStrategy;
import com.marmitt.core.application.handler.ProcessMessageHandler;
import com.marmitt.core.application.handler.ProcessUserMessageHandler;
import com.marmitt.core.application.handler.connection.*;
import com.marmitt.core.ports.inbound.handler.*;
import com.marmitt.core.ports.inbound.runner.HaltRunnerPort;
import com.marmitt.core.ports.inbound.websocket.ConnectMarketStreamPort;
import com.marmitt.core.ports.inbound.websocket.ConnectUserStreamPort;
import com.marmitt.core.ports.inbound.websocket.PostConnectionEstablishedPort;
import com.marmitt.core.ports.outbound.connection.ReconnectionStrategyPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.ListenerRepositoryPort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;
import com.marmitt.core.ports.outbound.websocket.WebSocketPortRegistryPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HandleConnectionConfig {

    @Bean
    public HandlerProcessMessagePort processMessage(WebSocketConnectionRepositoryPort connectionManager,
                                                    ExchangeAdapterRepositoryPort adapterRepository,
                                                    ListenerRepositoryPort listenerRepository) {
        return new ProcessMessageHandler(connectionManager, adapterRepository, listenerRepository);
    }

    @Bean
    public HandlerProcessUserMessagePort processUserMessage(WebSocketConnectionRepositoryPort connectionManager,
                                                             ExchangeAdapterRepositoryPort adapterRepository,
                                                             ListenerRepositoryPort listenerRepository) {
        return new ProcessUserMessageHandler(connectionManager, adapterRepository, listenerRepository);
    }

    @Bean
    public ConnectionEstablishedPort handleConnectionEstablished(WebSocketConnectionRepositoryPort connectionManager) {
        return new ConnectionEstablishedHandler(connectionManager);
    }

    @Bean
    public PostConnectionEstablishedPort handlePostConnectionEstablished(WebSocketConnectionRepositoryPort connectionManager,
                                                                         ExchangeAdapterRepositoryPort adapterRepository,
                                                                         WebSocketPortRegistryPort webSocketRegistry) {
        return new PostConnectionEstablishHandler(connectionManager, adapterRepository, webSocketRegistry);
    }

    @Bean
    public ConnectionClosingPort handleConnectionClosing(WebSocketConnectionRepositoryPort connectionManager) {
        return new ConnectionClosingHandler(connectionManager);
    }

    @Bean
    public ConnectionClosedPort handleConnectionClosed(WebSocketConnectionRepositoryPort connectionManager,
                                                       ExchangeAdapterRepositoryPort adapterRepository) {
        return new ConnectionClosedHandler(connectionManager, adapterRepository);
    }

    @Bean
    public ConnectionDisconnectedPort handleConnectionDisconnected(WebSocketConnectionRepositoryPort connectionManager) {
        return new ConnectionDisconnectedHandler(connectionManager);
    }

    @Bean
    public ReconnectionStrategyPort reconnectionStrategy(
            WebSocketConnectionRepositoryPort connectionRepository,
            ExchangeAdapterRepositoryPort adapterRepository,
            WebSocketPortRegistryPort webSocketRegistry,
            ConnectMarketStreamPort connectMarketStreamPort,
            ConnectUserStreamPort connectUserStreamPort,
            ApplicationEventPublisher eventPublisher,
            @Value("${reconnect.base-delay-seconds:5}") long baseDelaySeconds,
            @Value("${reconnect.max-attempts:10}") int maxAttempts) {
        return new LinearBackoffReconnectionStrategy(
                connectionRepository, adapterRepository, webSocketRegistry,
                connectMarketStreamPort, connectUserStreamPort, eventPublisher,
                baseDelaySeconds, maxAttempts);
    }

    @Bean
    public ConnectionFailedPort handleConnectionFailed(WebSocketConnectionRepositoryPort connectionManager,
                                                       ReconnectionStrategyPort reconnectionStrategy) {
        return new ConnectionFailedHandler(connectionManager, reconnectionStrategy);
    }

    @Bean
    public CriticalConnectionFailedPort handleCriticalConnectionFailed(HaltRunnerPort haltRunnerPort) {
        return new CriticalConnectionFailureHandler(haltRunnerPort);
    }

    @Bean
    public ConnectionLostOrderDispatchGuard connectionLostOrderDispatchGuard(
            ExchangeAdapterRepositoryPort adapterRepository,
            WebSocketConnectionRepositoryPort connectionRepository) {
        return new ConnectionLostOrderDispatchGuard(adapterRepository, connectionRepository);
    }
}
