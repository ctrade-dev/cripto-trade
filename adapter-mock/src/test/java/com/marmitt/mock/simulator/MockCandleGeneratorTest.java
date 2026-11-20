package com.marmitt.mock.simulator;

import com.marmitt.core.dto.strategy.CandleDto;
import com.marmitt.core.dto.strategy.CandleInterval;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T72: candles do mock sao deterministicos, continuos e so fechados. */
class MockCandleGeneratorTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:30:00Z");

    private final MockCandleGenerator generator =
            new MockCandleGenerator(42L, new BigDecimal("65000"), new BigDecimal("10"));

    @Test
    void sameQueryGivesTheSameClosedContinuousCandles() {
        List<CandleDto> first = generator.fetchClosed("BTCUSDT", CandleInterval.H1, NOW, 200);
        List<CandleDto> again = generator.fetchClosed("BTCUSDT", CandleInterval.H1, NOW, 200);

        assertEquals(first, again);
        assertEquals(200, first.size());
        assertEquals(Instant.parse("2026-11-20T09:00:00Z"), first.getLast().openTime(), "10:00 ainda esta em formacao");
        for (int i = 1; i < first.size(); i++) {
            assertEquals(Duration.ofHours(1), Duration.between(first.get(i - 1).openTime(), first.get(i).openTime()));
        }
        CandleDto candle = first.getLast();
        assertTrue(candle.high().compareTo(candle.open().max(candle.close())) >= 0);
        assertTrue(candle.low().compareTo(candle.open().min(candle.close())) <= 0);
        assertTrue(candle.volume().signum() > 0);
    }

    @Test
    void overlappingQueriesAgreeOnTheSameCandle() {
        CandleDto earlier = generator.fetchClosed("BTCUSDT", CandleInterval.M1, NOW, 5).getLast();
        List<CandleDto> later = generator.fetchClosed("BTCUSDT", CandleInterval.M1, NOW.plusSeconds(120), 5);

        assertEquals(earlier, later.get(2));
    }
}
