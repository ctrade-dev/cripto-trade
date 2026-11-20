package com.marmitt.core.dto.strategy;

import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Intervalos de candle do contrato de estrategias (T72, contrato 2.1). Lista fechada: a app declara um destes
 * codigos em {@code requirements.candles}.
 */
public enum CandleInterval {
    M1("1m", Duration.ofMinutes(1)),
    M5("5m", Duration.ofMinutes(5)),
    M15("15m", Duration.ofMinutes(15)),
    H1("1h", Duration.ofHours(1)),
    H4("4h", Duration.ofHours(4)),
    D1("1d", Duration.ofDays(1)),
    W1("1w", Duration.ofDays(7));

    private final String code;
    private final Duration duration;

    CandleInterval(String code, Duration duration) {
        this.code = code;
        this.duration = duration;
    }

    /** Codigo do contrato, por exemplo {@code 1h}; e tambem como o intervalo aparece na API do ctrade. */
    @JsonValue
    public String code() {
        return code;
    }

    public Duration duration() {
        return duration;
    }

    public static Optional<CandleInterval> fromCode(String code) {
        return Arrays.stream(values()).filter(interval -> interval.code.equals(code)).findFirst();
    }
}
