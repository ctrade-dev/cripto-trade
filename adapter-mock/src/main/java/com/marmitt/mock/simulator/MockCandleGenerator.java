package com.marmitt.mock.simulator;

import com.marmitt.core.dto.strategy.CandleDto;
import com.marmitt.core.dto.strategy.CandleInterval;
import com.marmitt.core.ports.outbound.exchange.rest.ExchangeCandleQueryPort;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Candles do mock (T72): uma funcao pura de semente, simbolo, intervalo e abertura. A mesma consulta devolve sempre os
 * mesmos candles, ha historico para qualquer {@code count} e a serie e continua. O preco oscila em torno do mesmo
 * preco inicial do feed de ticks do mock; nao e a mesma caminhada aleatoria, que depende do relogio.
 */
public class MockCandleGenerator implements ExchangeCandleQueryPort {

    private static final Set<CandleInterval> SUPPORTED = EnumSet.allOf(CandleInterval.class);
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");
    /** Amplitude da oscilacao em torno do preco base: 5%. */
    private static final double AMPLITUDE = 0.05;

    private final long seed;
    private final BigDecimal defaultStartPrice;
    private final BigDecimal defaultVolume;

    public MockCandleGenerator(long seed, BigDecimal defaultStartPrice, BigDecimal defaultVolume) {
        this.seed = seed;
        this.defaultStartPrice = defaultStartPrice;
        this.defaultVolume = defaultVolume;
    }

    @Override
    public Set<CandleInterval> supportedIntervals() {
        return SUPPORTED;
    }

    /** Abertura alinhada a epoca no multiplo do intervalo. */
    @Override
    public List<CandleDto> fetchClosed(String symbol, CandleInterval interval, Instant endExclusive, int limit) {
        long step = interval.duration().toMillis();
        long lastOpen = Math.floorDiv(endExclusive.toEpochMilli(), step) * step - step;
        List<CandleDto> candles = new ArrayList<>(limit);
        for (int i = limit - 1; i >= 0; i--) {
            candles.add(candle(symbol, interval, Instant.ofEpochMilli(lastOpen - i * step)));
        }
        return candles;
    }

    private CandleDto candle(String symbol, CandleInterval interval, Instant openTime) {
        Duration step = interval.duration();
        BigDecimal open = price(symbol, openTime);
        BigDecimal close = price(symbol, openTime.plus(step));
        SplittableRandom random = random(symbol, interval, openTime);
        BigDecimal wick = BigDecimal.ONE.add(BigDecimal.valueOf(random.nextDouble() * 0.002));
        BigDecimal high = open.max(close).multiply(wick).setScale(8, RoundingMode.HALF_UP);
        BigDecimal low = open.min(close).divide(wick, 8, RoundingMode.HALF_UP);
        BigDecimal volume = defaultVolume.multiply(BigDecimal.valueOf(0.5 + random.nextDouble()))
                .setScale(8, RoundingMode.HALF_UP);
        return new CandleDto(openTime, open, high, low, close, volume);
    }

    /** Preco num instante: base do simbolo vezes uma oscilacao lenta e um ruido fixo por instante. */
    private BigDecimal price(String symbol, Instant at) {
        double hours = at.toEpochMilli() / 3_600_000d;
        double wave = Math.sin(hours / 24d * 2 * Math.PI) * AMPLITUDE;
        double noise = (new SplittableRandom(seed ^ symbol.hashCode() ^ at.toEpochMilli()).nextDouble() - 0.5) * 0.004;
        return base(symbol).multiply(BigDecimal.valueOf(1 + wave + noise)).setScale(8, RoundingMode.HALF_UP);
    }

    /** O mesmo preco inicial por simbolo do feed de ticks do mock. */
    private BigDecimal base(String symbol) {
        int hash = Math.abs(symbol.hashCode() % 5000);
        BigDecimal offset = BigDecimal.valueOf(hash).divide(TEN_THOUSAND, 8, RoundingMode.HALF_UP);
        return defaultStartPrice.multiply(BigDecimal.ONE.add(offset));
    }

    private SplittableRandom random(String symbol, CandleInterval interval, Instant openTime) {
        return new SplittableRandom(seed ^ symbol.hashCode() ^ ((long) interval.ordinal() << 48) ^ openTime.toEpochMilli());
    }
}
