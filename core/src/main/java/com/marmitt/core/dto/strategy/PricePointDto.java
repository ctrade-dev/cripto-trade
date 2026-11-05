package com.marmitt.core.dto.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Um tick da serie de precos enviada a estrategia (T69).
 */
public record PricePointDto(BigDecimal price, Instant timestamp) {

    public PricePointDto {
        Objects.requireNonNull(price, "price cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
    }
}
