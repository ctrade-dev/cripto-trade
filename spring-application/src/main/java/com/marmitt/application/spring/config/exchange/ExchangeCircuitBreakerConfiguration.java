package com.marmitt.application.spring.config.exchange;

import com.marmitt.core.exceptions.ExchangeQueryException;
import com.marmitt.core.ports.outbound.exchange.rest.ExchangeOrderQueryPort;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Monta o circuit breaker por exchange das consultas REST de ordens (T24) e publica o estado no
 * Micrometer ({@code resilience4j_circuitbreaker_state{name="<exchange>-order-query"}}).
 */
@Configuration
public class ExchangeCircuitBreakerConfiguration {

    @Bean
    public CircuitBreakerRegistry exchangeCircuitBreakerRegistry(ExchangeCircuitBreakerProperties properties,
                                                                 MeterRegistry meterRegistry) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.getSlidingWindowSize())
                .minimumNumberOfCalls(properties.getMinimumNumberOfCalls())
                .failureRateThreshold(properties.getFailureRateThreshold())
                .waitDurationInOpenState(Duration.ofMillis(properties.getWaitDurationInOpenStateMs()))
                .permittedNumberOfCallsInHalfOpenState(properties.getPermittedNumberOfCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .recordException(ExchangeCircuitBreakerConfiguration::isInstability)
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return registry;
    }

    /**
     * Decorador por exchange. Com o circuit breaker desligado devolve o adapter sem mudança.
     */
    @Bean
    public ExchangeOrderQueryDecorator exchangeOrderQueryDecorator(CircuitBreakerRegistry exchangeCircuitBreakerRegistry,
                                                                   ExchangeCircuitBreakerProperties properties,
                                                                   MeterRegistry meterRegistry) {
        return (exchangeId, delegate) -> properties.isEnabled()
                ? new CircuitBreakerExchangeOrderQuery(exchangeId, delegate,
                        exchangeCircuitBreakerRegistry.circuitBreaker(exchangeId.toLowerCase() + "-order-query"),
                        meterRegistry)
                : delegate;
    }

    /** Falha que indica exchange instável; erro de requisição/autenticação não conta. */
    static boolean isInstability(Throwable error) {
        return error instanceof ExchangeQueryException query ? query.isRetryable() : true;
    }

    @FunctionalInterface
    public interface ExchangeOrderQueryDecorator {
        ExchangeOrderQueryPort decorate(String exchangeId, ExchangeOrderQueryPort delegate);
    }
}
