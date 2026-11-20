package com.marmitt.core.ports.outbound.marketdata;

import com.marmitt.core.dto.strategy.CandleDto;
import com.marmitt.core.dto.strategy.CandleSeriesKey;

import java.util.List;
import java.util.Set;

/**
 * Guarda as series de candles fechados (T72). So memoria: depois de um restart a serie e recarregada da exchange
 * (decisao 3). A regra de juntar, cortar e escolher a serie fica no core ({@code CandleSeries}); aqui so se guarda.
 */
public interface CandleHistoryPort {

    /** A serie guardada, do mais antigo para o mais novo; vazia se nao houver. Lista imutavel. */
    List<CandleDto> series(CandleSeriesKey key);

    /** Troca a serie inteira. */
    void replace(CandleSeriesKey key, List<CandleDto> candles);

    /** Descarta as series fora da demanda. */
    void retainOnly(Set<CandleSeriesKey> keys);
}
