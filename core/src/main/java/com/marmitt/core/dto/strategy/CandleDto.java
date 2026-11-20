package com.marmitt.core.dto.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Candle fechado (T72). {@code volume} na moeda base. O periodo vai de {@code openTime} ate
 * {@code openTime + intervalo}, exclusivo.
 */
public record CandleDto(Instant openTime, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                        BigDecimal volume) {

    public CandleDto {
        Objects.requireNonNull(openTime, "openTime cannot be null");
        Objects.requireNonNull(open, "open cannot be null");
        Objects.requireNonNull(high, "high cannot be null");
        Objects.requireNonNull(low, "low cannot be null");
        Objects.requireNonNull(close, "close cannot be null");
        Objects.requireNonNull(volume, "volume cannot be null");
    }
}
