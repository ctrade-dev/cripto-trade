package com.marmitt.core.application.usecase.marketdata;

import com.marmitt.core.dto.strategy.CandleDto;
import com.marmitt.core.dto.strategy.CandleInterval;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T72: a serie entregue e completa, continua, so de fechados e termina no ultimo fechado antes do tick. */
class CandleSeriesTest {

    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");
    private static final CandleInterval M1 = CandleInterval.M1;

    @Test
    void latestClosedEndsOnTheLastCandleClosedAtTheTick() {
        List<CandleDto> stored = minutes(0, 10); // 10:00 .. 10:09, a ultima fecha 10:10:00

        List<CandleDto> series = CandleSeries.latestClosed(stored, M1, 3, at(10, 30));

        assertEquals(List.of(minute(7), minute(8), minute(9)), series);
    }

    @Test
    void candleStillFormingAtTheTickIsLeftOut() {
        List<CandleDto> stored = minutes(0, 10);

        // Tick as 10:09:30: o candle de 10:09 ainda esta em formacao; o ultimo fechado e o de 10:08.
        List<CandleDto> series = CandleSeries.latestClosed(stored, M1, 2, at(9, 30));

        assertEquals(List.of(minute(7), minute(8)), series);
    }

    @Test
    void seriesBehindTheTickIsNotDelivered() {
        List<CandleDto> stored = minutes(0, 10);

        // Tick as 10:11:05: o candle de 10:10 ja fechou e ainda nao chegou.
        assertTrue(CandleSeries.latestClosed(stored, M1, 3, at(11, 5)).isEmpty());
    }

    @Test
    void shortOrGappedSeriesIsNotDelivered() {
        assertTrue(CandleSeries.latestClosed(minutes(0, 2), M1, 3, at(2, 0)).isEmpty(), "curta");

        List<CandleDto> gapped = new ArrayList<>(minutes(0, 3));
        gapped.addAll(minutes(4, 2)); // falta 10:03
        assertTrue(CandleSeries.latestClosed(gapped, M1, 4, at(6, 0)).isEmpty(), "buraco");
        assertEquals(2, CandleSeries.latestClosed(gapped, M1, 2, at(6, 0)).size(), "buraco fora da janela pedida");
    }

    @Test
    void mergeAppendsNewCandlesIgnoresRepeatsAndKeepsCapacity() {
        List<CandleDto> merged = CandleSeries.merge(minutes(0, 5), minutes(3, 4), M1, 6);

        assertEquals(minutes(1, 6), merged);
    }

    @Test
    void mergeAfterAGapRestartsFromWhatCame() {
        List<CandleDto> merged = CandleSeries.merge(minutes(0, 3), minutes(10, 2), M1, 10);

        assertEquals(minutes(10, 2), merged);
    }

    @Test
    void upToDateUntilTheNextCandleCloses() {
        List<CandleDto> stored = minutes(0, 10);

        assertTrue(CandleSeries.upToDate(stored, M1, at(10, 59)));
        assertFalse(CandleSeries.upToDate(stored, M1, at(11, 0)));
        assertFalse(CandleSeries.upToDate(List.of(), M1, at(0, 0)));
    }

    private static Instant at(int minute, int second) {
        return T0.plusSeconds(minute * 60L + second);
    }

    private static List<CandleDto> minutes(int first, int count) {
        List<CandleDto> candles = new ArrayList<>();
        for (int i = first; i < first + count; i++) {
            candles.add(minute(i));
        }
        return candles;
    }

    private static CandleDto minute(int i) {
        BigDecimal price = BigDecimal.valueOf(100 + i);
        return new CandleDto(T0.plusSeconds(i * 60L), price, price, price, price, BigDecimal.ONE);
    }
}
