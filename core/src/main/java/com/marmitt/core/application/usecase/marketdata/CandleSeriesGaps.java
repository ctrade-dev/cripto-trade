package com.marmitt.core.application.usecase.marketdata;

import com.marmitt.core.dto.strategy.CandleSeriesKey;

import java.time.Instant;
import java.util.List;

/**
 * Series de candles demandadas que nao estao prontas, publicadas a cada ciclo do {@link EnsureCandleSeriesUseCase}
 * para o estado operacional (T72). Runner que depende delas fica sem decisao ({@code WAITING_HISTORY}).
 */
public class CandleSeriesGaps {

    public enum Reason {
        /** A exchange de market data nao oferece o intervalo (ou nao tem consulta de candles). */
        UNSUPPORTED,
        /** A serie ainda nao esta completa e em dia (carga, falha da exchange ou historico curto do simbolo). */
        INCOMPLETE
    }

    public record Gap(CandleSeriesKey key, Reason reason, Instant since, String detail) {
    }

    private volatile List<Gap> current = List.of();

    void publish(List<Gap> gaps) {
        current = List.copyOf(gaps);
    }

    public List<Gap> current() {
        return current;
    }
}
