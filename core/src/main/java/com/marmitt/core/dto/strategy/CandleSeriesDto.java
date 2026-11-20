package com.marmitt.core.dto.strategy;

import java.util.List;
import java.util.Objects;

/** Serie entregue a estrategia (T72): candles fechados, do mais antigo para o mais novo, continuos. */
public record CandleSeriesDto(CandleInterval interval, List<CandleDto> candles) {

    public CandleSeriesDto {
        Objects.requireNonNull(interval, "interval cannot be null");
        candles = List.copyOf(candles);
    }
}
