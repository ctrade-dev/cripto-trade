package com.marmitt.application.spring.config.exchange;

import com.marmitt.core.dto.websocket.data.OrderDataDto;
import com.marmitt.core.exceptions.ExchangeQueryException;
import com.marmitt.core.ports.outbound.exchange.rest.ExchangeOrderQueryPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T24: circuit breaker nas consultas REST de ordens.
 */
class CircuitBreakerExchangeOrderQueryTest {

    private final ExchangeOrderQueryPort delegate = mock(ExchangeOrderQueryPort.class);
    private final ExchangeCircuitBreakerProperties properties = properties(200);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ExchangeCircuitBreakerConfiguration configuration = new ExchangeCircuitBreakerConfiguration();
    private final CircuitBreakerRegistry registry = configuration.exchangeCircuitBreakerRegistry(properties, meterRegistry);
    private final ExchangeOrderQueryPort query =
            configuration.exchangeOrderQueryDecorator(registry, properties, meterRegistry).decorate("BINANCE", delegate);

    @Test
    void closedCircuitBehavesLikeTheAdapter() {
        OrderDataDto order = mock(OrderDataDto.class);
        when(delegate.queryOrderByClientOrderId("BTCUSDT", "c1")).thenReturn(Optional.of(order));
        when(delegate.listAllOpenOrders()).thenReturn(List.of(order));

        assertEquals(Optional.of(order), query.queryOrderByClientOrderId("BTCUSDT", "c1"));
        assertEquals(List.of(order), query.listAllOpenOrders());
        assertEquals(CircuitBreaker.State.CLOSED, circuit().getState());
    }

    @Test
    void fiveConsecutiveInstabilityFailuresOpenTheCircuitAndNextCallsFailFast() {
        when(delegate.queryOrderByClientOrderId("BTCUSDT", "c1")).thenThrow(temporary());
        for (int i = 0; i < 5; i++) {
            assertThrows(ExchangeQueryException.class, () -> query.queryOrderByClientOrderId("BTCUSDT", "c1"));
        }
        assertEquals(CircuitBreaker.State.OPEN, circuit().getState());

        ExchangeQueryException fastFail = assertThrows(ExchangeQueryException.class,
                () -> query.queryOrderByClientOrderId("BTCUSDT", "c1"));
        assertEquals(ExchangeQueryException.ErrorType.TEMPORARY, fastFail.errorType(),
                "Circuito aberto vira falha transitoria: retry do boot e watchdog ja tratam");
        verify(delegate, times(5)).queryOrderByClientOrderId("BTCUSDT", "c1");
        assertEquals(1.0, meterRegistry.get("resilience4j.circuitbreaker.state")
                .tags("name", "binance-order-query", "state", "open").gauge().value());
    }

    @Test
    void requestOrAuthErrorsDoNotOpenTheCircuit() {
        when(delegate.queryOrderByClientOrderId("BTCUSDT", "bad"))
                .thenThrow(new ExchangeQueryException("BINANCE", ExchangeQueryException.ErrorType.INVALID_REQUEST, "400"));
        for (int i = 0; i < 6; i++) {
            assertThrows(ExchangeQueryException.class, () -> query.queryOrderByClientOrderId("BTCUSDT", "bad"));
        }
        assertEquals(CircuitBreaker.State.CLOSED, circuit().getState());
    }

    @Test
    void afterTheWaitTheCircuitTestsAgainAndClosesOnSuccess() throws InterruptedException {
        when(delegate.listOpenOrdersBySymbol("BTCUSDT")).thenThrow(temporary());
        for (int i = 0; i < 5; i++) {
            assertThrows(ExchangeQueryException.class, () -> query.listOpenOrdersBySymbol("BTCUSDT"));
        }
        assertEquals(CircuitBreaker.State.OPEN, circuit().getState());

        Thread.sleep(250);
        reset(delegate);
        when(delegate.listOpenOrdersBySymbol("BTCUSDT")).thenReturn(List.of());
        for (int i = 0; i < 3; i++) {
            assertEquals(List.of(), query.listOpenOrdersBySymbol("BTCUSDT"));
        }
        assertEquals(CircuitBreaker.State.CLOSED, circuit().getState());
    }

    @Test
    void countsEachFailureByErrorTypeAndCircuitOpen() {
        when(delegate.queryOrderByClientOrderId("BTCUSDT", "c1")).thenThrow(temporary());
        when(delegate.queryOrderByClientOrderId("BTCUSDT", "banned"))
                .thenThrow(new ExchangeQueryException("BINANCE", ExchangeQueryException.ErrorType.RATE_LIMIT, "418"));
        when(delegate.listAllOpenOrders()).thenThrow(new IllegalStateException("boom"));

        assertThrows(ExchangeQueryException.class, () -> query.queryOrderByClientOrderId("BTCUSDT", "banned"));
        assertThrows(IllegalStateException.class, query::listAllOpenOrders);
        for (int i = 0; i < 3; i++) {
            assertThrows(ExchangeQueryException.class, () -> query.queryOrderByClientOrderId("BTCUSDT", "c1"));
        }
        assertEquals(CircuitBreaker.State.OPEN, circuit().getState());
        assertThrows(ExchangeQueryException.class, () -> query.queryOrderByClientOrderId("BTCUSDT", "c1"));

        assertEquals(1.0, failures("RATE_LIMIT"));
        assertEquals(1.0, failures("UNEXPECTED"));
        assertEquals(3.0, failures("TEMPORARY"));
        assertEquals(1.0, failures("CIRCUIT_OPEN"), "Chamada barrada pelo circuito tambem conta");
    }

    @Test
    void disabledCircuitBreakerReturnsTheAdapterItself() {
        ExchangeCircuitBreakerProperties disabled = properties(200);
        disabled.setEnabled(false);

        assertSame(delegate, configuration.exchangeOrderQueryDecorator(registry, disabled, meterRegistry).decorate("BINANCE", delegate));
        verify(delegate, never()).listAllOpenOrders();
    }

    private double failures(String errorType) {
        return meterRegistry.get("exchange.order.query.failures")
                .tags("exchange", "BINANCE", "errorType", errorType).counter().count();
    }

    private CircuitBreaker circuit() {
        return registry.circuitBreaker("binance-order-query");
    }

    private static ExchangeQueryException temporary() {
        return new ExchangeQueryException("BINANCE", ExchangeQueryException.ErrorType.TEMPORARY, "timeout");
    }

    private static ExchangeCircuitBreakerProperties properties(long waitMs) {
        ExchangeCircuitBreakerProperties properties = new ExchangeCircuitBreakerProperties();
        properties.setWaitDurationInOpenStateMs(waitMs);
        return properties;
    }
}
