package com.marmitt.core.dto.strategy;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * O que uma estrategia precisa receber em cada interacao (T69, T72): ticks de historico e series de candles
 * fechados. Sem eles completos o ctrade nao a consulta ({@code WAITING_HISTORY}).
 */
public record StrategyRequirements(int priceHistoryTicks, List<CandleRequirement> candles) {

    /** Teto de ticks, o mesmo que o historico guarda por exchange e simbolo. */
    public static final int MAX_PRICE_HISTORY_TICKS = 1000;
    /** Teto de candles por serie: uma consulta de klines da Binance carrega a serie inteira (T72, decisao 6). */
    public static final int MAX_CANDLES_PER_SERIES = 1000;
    /** Teto de series por estrategia (T72, decisao 6). */
    public static final int MAX_CANDLE_SERIES = 3;

    public static final StrategyRequirements NONE = new StrategyRequirements(0, List.of());

    public StrategyRequirements {
        candles = candles == null ? List.of() : List.copyOf(candles);
    }

    public boolean declaresCandles() {
        return !candles.isEmpty();
    }

    /**
     * A regra unica dos tetos e da repeticao, usada na conferencia do catalogo.
     *
     * @return o motivo da violacao, ou vazio se os requisitos cabem no que o ctrade entrega
     */
    public Optional<String> violation() {
        if (priceHistoryTicks < 0 || priceHistoryTicks > MAX_PRICE_HISTORY_TICKS) {
            return Optional.of("service requires " + priceHistoryTicks + " price history ticks, supported 0 to "
                    + MAX_PRICE_HISTORY_TICKS);
        }
        if (candles.size() > MAX_CANDLE_SERIES) {
            return Optional.of("service requires " + candles.size() + " candle series, supported up to "
                    + MAX_CANDLE_SERIES);
        }
        Set<CandleInterval> seen = new HashSet<>();
        for (CandleRequirement candle : candles) {
            if (candle.count() < 1 || candle.count() > MAX_CANDLES_PER_SERIES) {
                return Optional.of("service requires " + candle.count() + " candles of " + candle.interval().code()
                        + ", supported 1 to " + MAX_CANDLES_PER_SERIES);
            }
            if (!seen.add(candle.interval())) {
                return Optional.of("service declares candle interval " + candle.interval().code() + " twice");
            }
        }
        return Optional.empty();
    }

    /** Texto curto para log e mensagem de conferencia, por exemplo {@code ticks=20 candles=[1h x 200]}. */
    public String describe() {
        return "ticks=" + priceHistoryTicks + " candles=" + candles.stream()
                .map(candle -> candle.interval().code() + " x " + candle.count()).toList();
    }
}
