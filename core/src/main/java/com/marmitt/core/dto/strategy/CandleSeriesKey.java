package com.marmitt.core.dto.strategy;

import java.util.Locale;
import java.util.Objects;

/** Uma serie de candles: exchange de market data, simbolo e intervalo (T72). */
public record CandleSeriesKey(String exchangeId, String symbol, CandleInterval interval) {

    public CandleSeriesKey {
        exchangeId = Objects.requireNonNull(exchangeId, "exchangeId cannot be null").toUpperCase(Locale.ROOT);
        symbol = Objects.requireNonNull(symbol, "symbol cannot be null").toUpperCase(Locale.ROOT);
        Objects.requireNonNull(interval, "interval cannot be null");
    }

    @Override
    public String toString() {
        return exchangeId + ":" + symbol + ":" + interval.code();
    }
}
