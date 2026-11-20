package com.marmitt.core.dto.strategy;

import java.util.Objects;

/** Uma serie de candles fechados que a estrategia declara precisar (T72). */
public record CandleRequirement(CandleInterval interval, int count) {

    public CandleRequirement {
        Objects.requireNonNull(interval, "interval cannot be null");
    }
}
