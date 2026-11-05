package com.marmitt.application.spring.repository;

import com.marmitt.core.dto.strategy.PricePointDto;
import com.marmitt.core.ports.outbound.marketdata.PriceHistoryPort;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Buffer circular em memoria por exchange e simbolo (T69). A secao critica so copia referencias, entao a
 * thread de market data nao fica presa a leitura de um runner.
 */
@Repository
public class InMemoryPriceHistoryRepository implements PriceHistoryPort {

    private final Map<String, ArrayDeque<PricePointDto>> ticksByMarket = new ConcurrentHashMap<>();

    @Override
    public void record(String exchangeId, String symbol, BigDecimal price, Instant timestamp) {
        if (exchangeId == null || symbol == null || price == null || price.signum() <= 0 || timestamp == null) {
            return;
        }
        ArrayDeque<PricePointDto> ticks = ticksByMarket.computeIfAbsent(key(exchangeId, symbol),
                ignored -> new ArrayDeque<>(MAX_TICKS));
        synchronized (ticks) {
            PricePointDto last = ticks.peekLast();
            if (last != null && timestamp.isBefore(last.timestamp())) {
                return;
            }
            if (ticks.size() == MAX_TICKS) {
                ticks.removeFirst();
            }
            ticks.addLast(new PricePointDto(price, timestamp));
        }
    }

    @Override
    public List<PricePointDto> latest(String exchangeId, String symbol, int count, Instant upTo) {
        ArrayDeque<PricePointDto> ticks = ticksByMarket.get(key(exchangeId, symbol));
        if (ticks == null || count <= 0) {
            return List.of();
        }
        List<PricePointDto> newestFirst = new ArrayList<>(count);
        synchronized (ticks) {
            Iterator<PricePointDto> newest = ticks.descendingIterator();
            while (newest.hasNext() && newestFirst.size() < count) {
                PricePointDto tick = newest.next();
                if (!tick.timestamp().isAfter(upTo)) {
                    newestFirst.add(tick);
                }
            }
        }
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    private static String key(String exchangeId, String symbol) {
        return exchangeId.toUpperCase(Locale.ROOT) + ":" + symbol.toUpperCase(Locale.ROOT);
    }
}
