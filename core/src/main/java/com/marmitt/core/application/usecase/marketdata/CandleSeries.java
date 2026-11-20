package com.marmitt.core.application.usecase.marketdata;

import com.marmitt.core.dto.strategy.CandleDto;
import com.marmitt.core.dto.strategy.CandleInterval;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Regras da serie de candles (T72), sem estado: juntar o que veio da exchange e escolher o que vai para a
 * estrategia. Nunca entrega serie com buraco nem serie atrasada (decisao 4).
 */
public final class CandleSeries {

    private CandleSeries() {
    }

    /**
     * Junta candles fechados novos a serie guardada. Candle repetido ou mais velho que o ultimo e ignorado; se o
     * primeiro novo nao continua a serie, a serie passa a ser so o que veio (a consulta seguinte completa).
     *
     * @param capacity quantos candles guardar no maximo (o maior {@code count} da demanda)
     */
    public static List<CandleDto> merge(List<CandleDto> stored, List<CandleDto> fetched, CandleInterval interval,
                                        int capacity) {
        List<CandleDto> merged = new ArrayList<>(stored);
        for (CandleDto candle : fetched) {
            if (merged.isEmpty()) {
                merged.add(candle);
                continue;
            }
            Instant last = merged.getLast().openTime();
            if (!candle.openTime().isAfter(last)) {
                continue;
            }
            if (!candle.openTime().equals(last.plus(interval.duration()))) {
                merged.clear();
            }
            merged.add(candle);
        }
        int excess = merged.size() - capacity;
        return List.copyOf(excess > 0 ? merged.subList(excess, merged.size()) : merged);
    }

    /**
     * Os {@code count} candles fechados ate {@code upTo}, terminando no mais recente ja fechado nesse instante.
     *
     * @return a serie completa e continua, ou vazia: falta candle, ha buraco ou a serie esta atrasada (o ultimo
     *         candle fechado em {@code upTo} ainda nao chegou)
     */
    public static List<CandleDto> latestClosed(List<CandleDto> stored, CandleInterval interval, int count,
                                               Instant upTo) {
        Duration step = interval.duration();
        int end = stored.size() - 1;
        while (end >= 0 && stored.get(end).openTime().plus(step).isAfter(upTo)) {
            end--;
        }
        if (end < 0 || end + 1 < count) {
            return List.of();
        }
        if (!stored.get(end).openTime().plus(step.multipliedBy(2)).isAfter(upTo)) {
            return List.of();
        }
        List<CandleDto> selected = stored.subList(end + 1 - count, end + 1);
        for (int i = 1; i < selected.size(); i++) {
            if (!selected.get(i).openTime().equals(selected.get(i - 1).openTime().plus(step))) {
                return List.of();
            }
        }
        return List.copyOf(selected);
    }

    /** A serie esta em dia em {@code now}: o candle seguinte ao ultimo guardado ainda nao fechou. */
    public static boolean upToDate(List<CandleDto> stored, CandleInterval interval, Instant now) {
        return !stored.isEmpty()
                && stored.getLast().openTime().plus(interval.duration().multipliedBy(2)).isAfter(now);
    }
}
