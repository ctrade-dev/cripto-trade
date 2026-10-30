package com.marmitt.core.dto.runner.request;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T67: o runner nasce com fonte de market data (T53 rodada 8).
 */
class CreateRunnerRequestTest {

    @Test
    void rejectsMissingMarketDataSources() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> request(null));
        assertTrue(e.getMessage().contains("allowedMarketDataSources"));
    }

    @Test
    void rejectsEmptyMarketDataSources() {
        assertThrows(IllegalArgumentException.class, () -> request(Set.of()));
    }

    @Test
    void acceptsRunnerWithMarketDataSource() {
        assertDoesNotThrow(() -> request(Set.of("BINANCE")));
    }

    private static CreateRunnerRequest request(Set<String> sources) {
        return CreateRunnerRequest.builder()
                .portfolioId(UUID.randomUUID())
                .strategyId(UUID.randomUUID())
                .symbol("BTCUSDT")
                .exchangeName("BINANCE")
                .allowedMarketDataSources(sources)
                .build();
    }
}
