package com.marmitt.application.spring.repository;

import com.marmitt.core.dto.strategy.PricePointDto;
import com.marmitt.core.ports.outbound.marketdata.PriceHistoryPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryPriceHistoryRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-11-05T12:00:00Z");

    private final InMemoryPriceHistoryRepository history = new InMemoryPriceHistoryRepository();

    private void tick(int second, String price) {
        history.record("BINANCE", "BTCUSDT", new BigDecimal(price), T0.plusSeconds(second));
    }

    private static List<String> prices(List<PricePointDto> points) {
        return points.stream().map(point -> point.price().toPlainString()).toList();
    }

    @Test
    void latestReturnsTheNewestTicksFromOldestToNewest() {
        tick(0, "1");
        tick(1, "2");
        tick(2, "3");

        assertEquals(List.of("2", "3"), prices(history.latest("BINANCE", "BTCUSDT", 2, T0.plusSeconds(2))));
    }

    @Test
    void latestStopsAtTheTickBeingEvaluated() {
        tick(0, "1");
        tick(1, "2");
        tick(2, "3");

        assertEquals(List.of("1", "2"), prices(history.latest("BINANCE", "BTCUSDT", 5, T0.plusSeconds(1))));
    }

    @Test
    void marketsDoNotMixAndNamesIgnoreCase() {
        tick(0, "1");
        history.record("COINBASE", "BTCUSDT", new BigDecimal("9"), T0);

        assertEquals(List.of("1"), prices(history.latest("binance", "btcusdt", 5, T0)));
        assertTrue(history.latest("BINANCE", "ETHUSDT", 5, T0).isEmpty());
    }

    @Test
    void olderTickAndInvalidPriceAreIgnored() {
        tick(5, "1");
        tick(4, "2");
        tick(6, "0");

        assertEquals(List.of("1"), prices(history.latest("BINANCE", "BTCUSDT", 5, T0.plusSeconds(9))));
    }

    @Test
    void keepsAtMostTheCapDroppingTheOldest() {
        for (int second = 0; second <= PriceHistoryPort.MAX_TICKS; second++) {
            tick(second, String.valueOf(second + 1));
        }

        List<PricePointDto> all = history.latest("BINANCE", "BTCUSDT", PriceHistoryPort.MAX_TICKS + 5,
                T0.plusSeconds(PriceHistoryPort.MAX_TICKS));

        assertEquals(PriceHistoryPort.MAX_TICKS, all.size());
        assertEquals(new BigDecimal("2"), all.getFirst().price());
    }

    @Test
    void zeroTicksNeedNoLookup() {
        tick(0, "1");

        assertTrue(history.latest("BINANCE", "BTCUSDT", 0, T0).isEmpty());
    }
}
