package com.marmitt.core.application.usecase.marketdata;

import com.marmitt.core.dto.strategy.CandleDto;
import com.marmitt.core.dto.strategy.CandleRequirement;
import com.marmitt.core.dto.strategy.CandleSeriesDto;
import com.marmitt.core.dto.strategy.CandleSeriesKey;
import com.marmitt.core.ports.outbound.marketdata.CandleHistoryPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Leitura das series de candles para a avaliacao de um tick (T72). */
public class CandleHistory {

    private final CandleHistoryPort store;

    public CandleHistory(CandleHistoryPort store) {
        this.store = store;
    }

    /**
     * Uma serie por requisito, na ordem declarada, da fonte do tick.
     *
     * @return vazio se alguma serie nao estiver completa, continua e em dia no instante do tick
     */
    public Optional<List<CandleSeriesDto>> seriesFor(String source, String symbol, List<CandleRequirement> required,
                                                     Instant tick) {
        List<CandleSeriesDto> series = new ArrayList<>(required.size());
        for (CandleRequirement requirement : required) {
            List<CandleDto> candles = CandleSeries.latestClosed(
                    store.series(new CandleSeriesKey(source, symbol, requirement.interval())),
                    requirement.interval(), requirement.count(), tick);
            if (candles.isEmpty()) {
                return Optional.empty();
            }
            series.add(new CandleSeriesDto(requirement.interval(), candles));
        }
        return Optional.of(series);
    }
}
