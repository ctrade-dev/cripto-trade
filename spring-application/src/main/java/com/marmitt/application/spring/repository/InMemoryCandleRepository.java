package com.marmitt.application.spring.repository;

import com.marmitt.core.dto.strategy.CandleDto;
import com.marmitt.core.dto.strategy.CandleSeriesKey;
import com.marmitt.core.ports.outbound.marketdata.CandleHistoryPort;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Series de candles em memoria (T72). Cada serie e uma lista imutavel trocada inteira: a leitura de um runner nunca
 * ve uma serie pela metade.
 */
@Repository
public class InMemoryCandleRepository implements CandleHistoryPort {

    private final Map<CandleSeriesKey, List<CandleDto>> seriesByKey = new ConcurrentHashMap<>();

    @Override
    public List<CandleDto> series(CandleSeriesKey key) {
        return seriesByKey.getOrDefault(key, List.of());
    }

    @Override
    public void replace(CandleSeriesKey key, List<CandleDto> candles) {
        seriesByKey.put(key, List.copyOf(candles));
    }

    @Override
    public void retainOnly(Set<CandleSeriesKey> keys) {
        seriesByKey.keySet().retainAll(keys);
    }
}
