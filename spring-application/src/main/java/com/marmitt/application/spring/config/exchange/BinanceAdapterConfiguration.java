package com.marmitt.application.spring.config.exchange;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marmitt.application.spring.adapter.OkHttp3ListenerConverter;
import com.marmitt.application.spring.adapter.OkHttp3WebSocketAdapter;
import com.marmitt.application.spring.adapter.binance.OkHttpClientAdapter;
import com.marmitt.application.spring.repository.DefaultExchangeAdapterDescriptor;
import com.marmitt.binance.BinanceConnectionConfig;
import com.marmitt.binance.BinanceMarketStreamAdapter;
import com.marmitt.binance.BinanceOrderAdapter;
import com.marmitt.binance.BinanceUserStreamAdapter;
import com.marmitt.binance.BinanceUserStreamSessionAdapter;
import com.marmitt.binance.auth.BinanceCredentials;
import com.marmitt.binance.candle.BinanceCandleQueryAdapter;
import com.marmitt.binance.auth.BinanceRequestSigner;
import com.marmitt.binance.filters.BinanceOrderNormalizer;
import com.marmitt.binance.filters.SymbolFilterCache;
import com.marmitt.binance.rest.BinanceRestRequestBuilder;
import com.marmitt.binance.trade.BinanceTradeHistoryAdapter;
import com.marmitt.core.enums.StreamChannel;
import com.marmitt.core.ports.outbound.events.EventPublisherPort;
import com.marmitt.core.ports.outbound.exchange.ExchangeAdapterDescriptor;
import com.marmitt.core.ports.outbound.websocket.WebSocketPortRegistryPort;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(BinanceProperties.class)
@ConditionalOnExpression("!'${binance.api-key:}'.isBlank() && !'${binance.api-secret:}'.isBlank()")
public class BinanceAdapterConfiguration {

    @Bean
    public OkHttpClient binanceWebSocketClient() {
        return new OkHttpClient.Builder()
                .readTimeout(Duration.ZERO)
                .pingInterval(Duration.ofSeconds(20))
                .build();
    }

    @Bean
    public OkHttpClient binanceRestClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Bean
    public OkHttp3WebSocketAdapter binanceMarketWebSocketPort(OkHttpClient binanceWebSocketClient,
                                                               EventPublisherPort eventPublisher,
                                                               WebSocketPortRegistryPort webSocketRegistry) {
        OkHttp3WebSocketAdapter ws = new OkHttp3WebSocketAdapter(binanceWebSocketClient,
                new OkHttp3ListenerConverter(eventPublisher, StreamChannel.MARKET));
        webSocketRegistry.register("BINANCE", ws);
        return ws;
    }

    @Bean
    public OkHttp3WebSocketAdapter binanceUserStreamWebSocketPort(OkHttpClient binanceWebSocketClient,
                                                                   EventPublisherPort eventPublisher,
                                                                   WebSocketPortRegistryPort webSocketRegistry) {
        OkHttp3WebSocketAdapter ws = new OkHttp3WebSocketAdapter(binanceWebSocketClient,
                new OkHttp3ListenerConverter(eventPublisher, StreamChannel.USER_DATA));
        webSocketRegistry.registerUserStream("BINANCE", ws);
        return ws;
    }

    @Bean
    public SymbolFilterCache binanceSymbolFilterCache(ObjectMapper objectMapper,
                                                      BinanceProperties properties,
                                                      OkHttpClient binanceRestClient) {
        SymbolFilterCache cache = new SymbolFilterCache(
                properties.getRestBaseUrl(),
                new OkHttpClientAdapter(binanceRestClient),
                objectMapper);
        // Eager-load configured symbols at startup; SymbolFilterLoadException propagates and aborts boot
        properties.getSymbols().forEach(cache::loadAndCache);
        return cache;
    }

    @Bean
    public BinanceOrderNormalizer binanceOrderNormalizer(SymbolFilterCache binanceSymbolFilterCache) {
        return new BinanceOrderNormalizer(binanceSymbolFilterCache);
    }

    @Bean
    public BinanceMarketStreamAdapter binanceMarketStreamAdapter(ObjectMapper objectMapper,
                                                                 BinanceProperties properties,
                                                                 OkHttpClient binanceRestClient,
                                                                 SymbolFilterCache binanceSymbolFilterCache) {
        BinanceConnectionConfig config = new BinanceConnectionConfig(
                properties.getApiKey(),
                properties.getApiSecret(),
                properties.getWsBaseUrl(),
                properties.getRestBaseUrl());
        // Derived client shares binanceRestClient's connection pool; callTimeout caps the full
        // boot readiness check to 10s instead of the shared client's 30s read timeout.
        OkHttpClient bootReadinessClient = binanceRestClient.newBuilder()
                .callTimeout(Duration.ofSeconds(10))
                .build();
        return new BinanceMarketStreamAdapter(objectMapper, config,
                new OkHttpClientAdapter(bootReadinessClient), binanceSymbolFilterCache);
    }

    @Bean
    public BinanceUserStreamSessionAdapter binanceUserStreamSessionAdapter(ObjectMapper objectMapper,
                                                                            BinanceProperties properties) {
        var credentials = new BinanceCredentials(properties.getApiKey(), properties.getApiSecret());
        return new BinanceUserStreamSessionAdapter(properties.getWsApiBaseUrl(), credentials, objectMapper);
    }

    @Bean
    public BinanceUserStreamAdapter binanceUserStreamAdapter(ObjectMapper objectMapper,
                                                             EventPublisherPort eventPublisher) {
        return new BinanceUserStreamAdapter(objectMapper, eventPublisher);
    }

    @Bean
    public BinanceOrderAdapter binanceOrderAdapter(OkHttp3WebSocketAdapter binanceUserStreamWebSocketPort,
                                                   OkHttp3WebSocketAdapter binanceMarketWebSocketPort,
                                                   BinanceMarketStreamAdapter binanceMarketStreamAdapter) {
        return new BinanceOrderAdapter(
                binanceUserStreamWebSocketPort,
                binanceMarketWebSocketPort,
                binanceMarketStreamAdapter,
                binanceMarketStreamAdapter
        );
    }

    @Bean
    public BinanceTradeHistoryAdapter binanceTradeHistoryAdapter(BinanceProperties properties,
                                                                  OkHttpClient binanceRestClient,
                                                                  ObjectMapper objectMapper) {
        BinanceCredentials credentials = new BinanceCredentials(properties.getApiKey(), properties.getApiSecret());
        BinanceRequestSigner signer = new BinanceRequestSigner(credentials);
        BinanceRestRequestBuilder requestBuilder = new BinanceRestRequestBuilder(properties.getRestBaseUrl(), signer);
        return new BinanceTradeHistoryAdapter(requestBuilder, new OkHttpClientAdapter(binanceRestClient), objectMapper);
    }

    /** Candles fechados para as estrategias que os declaram (T72): dado publico, sem assinatura. */
    @Bean
    public BinanceCandleQueryAdapter binanceCandleQueryAdapter(BinanceProperties properties,
                                                               OkHttpClient binanceRestClient,
                                                               ObjectMapper objectMapper) {
        BinanceCredentials credentials = new BinanceCredentials(properties.getApiKey(), properties.getApiSecret());
        BinanceRestRequestBuilder requestBuilder = new BinanceRestRequestBuilder(properties.getRestBaseUrl(),
                new BinanceRequestSigner(credentials));
        return new BinanceCandleQueryAdapter(requestBuilder, new OkHttpClientAdapter(binanceRestClient), objectMapper);
    }

    @Bean
    public ExchangeAdapterDescriptor binanceAdapterDescriptor(BinanceMarketStreamAdapter binanceMarketStreamAdapter,
                                                               BinanceUserStreamAdapter binanceUserStreamAdapter,
                                                               BinanceUserStreamSessionAdapter binanceUserStreamSessionAdapter,
                                                               BinanceOrderAdapter binanceOrderAdapter,
                                                               BinanceTradeHistoryAdapter binanceTradeHistoryAdapter,
                                                               BinanceCandleQueryAdapter binanceCandleQueryAdapter,
                                                               ExchangeCircuitBreakerConfiguration.ExchangeOrderQueryDecorator
                                                                       orderQueryDecorator) {
        return DefaultExchangeAdapterDescriptor.builder("BINANCE")
                .streaming(binanceMarketStreamAdapter)
                .orderPort(binanceOrderAdapter)
                .userStream(binanceUserStreamAdapter)
                .userStreamSession(binanceUserStreamSessionAdapter)
                .orderExecution(binanceMarketStreamAdapter)
                .orderQuery(orderQueryDecorator.decorate("BINANCE", binanceMarketStreamAdapter))
                .accountQuery(binanceMarketStreamAdapter)
                .tradeHistory(binanceTradeHistoryAdapter)
                .bootReadiness(binanceMarketStreamAdapter)
                .candleQuery(binanceCandleQueryAdapter)
                .build();
    }
}
