package com.marmitt.application.spring.config.exchange;

import com.marmitt.mock.config.MockMarketDataFeedConfig;
import com.marmitt.mock.config.MockScenarioConfig;
import com.marmitt.mock.simulator.MockCandleGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marmitt.application.spring.repository.DefaultExchangeAdapterDescriptor;
import com.marmitt.core.dto.exchange.OrderSubmissionResult;
import com.marmitt.core.dto.websocket.data.OrderDataDto;
import com.marmitt.core.dto.websocket.request.SendOrderRequest;
import com.marmitt.core.ports.outbound.events.EventPublisherPort;
import com.marmitt.core.ports.outbound.exchange.ExchangeAdapterDescriptor;
import com.marmitt.core.ports.outbound.exchange.ExchangeOrderPort;
import com.marmitt.core.ports.outbound.exchange.streaming.ExchangeUserStreamPort;
import com.marmitt.core.ports.outbound.exchange.streaming.UserStreamSessionPort;
import com.marmitt.core.ports.outbound.websocket.WebSocketPortRegistryPort;
import com.marmitt.mock.adapter.LocalEventWebSocketAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class MockAdapterConfiguration {

    @Bean
    public LocalEventWebSocketAdapter mockWebSocketPort(EventPublisherPort eventPublisher,
                                                        WebSocketPortRegistryPort webSocketRegistry) {
        LocalEventWebSocketAdapter ws = new LocalEventWebSocketAdapter(eventPublisher);
        webSocketRegistry.register("MOCK", ws);
        return ws;
    }

    @Bean
    public MockExchangeAdapter mockExchangeAdapter(ObjectMapper objectMapper,
                                                   EventPublisherPort eventPublisher) {
        return new MockExchangeAdapter(objectMapper, eventPublisher);
    }

    @Bean
    public ExchangeOrderPort mockOrderPort(MockExchangeAdapter mockExchangeAdapter,
                                           LocalEventWebSocketAdapter mockWebSocketPort) {
        return new ExchangeOrderPort() {
            @Override
            public String getExchangeName() { return "MOCK"; }

            @Override
            public OrderSubmissionResult submitOrder(SendOrderRequest request) {
                try {
                    OrderDataDto dto = mockExchangeAdapter.submitOrder(request);
                    if (dto == null || dto.status() == null) {
                        mockWebSocketPort.sendMessage(mockExchangeAdapter.formatMessage(request));
                        return OrderSubmissionResult.dispatched();
                    }
                    return OrderSubmissionResult.completed(dto);
                } catch (UnsupportedOperationException ex) {
                    mockWebSocketPort.sendMessage(mockExchangeAdapter.formatMessage(request));
                    return OrderSubmissionResult.dispatched();
                }
            }
        };
    }

    @Bean
    public ExchangeAdapterDescriptor mockAdapterDescriptor(MockExchangeAdapter mockExchangeAdapter,
                                                            ExchangeOrderPort mockOrderPort,
                                                            List<ExchangeUserStreamPort> userStreamPorts,
                                                            List<UserStreamSessionPort> userStreamSessionPorts) {
        var builder = DefaultExchangeAdapterDescriptor.builder("MOCK")
                .streaming(mockExchangeAdapter)
                .orderPort(mockOrderPort)
                .orderExecution(mockExchangeAdapter)
                .orderQuery(mockExchangeAdapter)
                .accountQuery(mockExchangeAdapter)
                .bootReadiness(mockExchangeAdapter)
                .candleQuery(new MockCandleGenerator(MockScenarioConfig.defaultConfig().randomSeed(),
                        MockMarketDataFeedConfig.defaultConfig().defaultStartPrice(),
                        MockMarketDataFeedConfig.defaultConfig().defaultVolume()));

        userStreamPorts.stream()
                .filter(p -> "MOCK".equals(p.getExchangeName()))
                .findFirst()
                .ifPresent(builder::userStream);

        userStreamSessionPorts.stream()
                .filter(p -> "MOCK".equals(p.getExchangeName()))
                .findFirst()
                .ifPresent(builder::userStreamSession);

        return builder.build();
    }
}
