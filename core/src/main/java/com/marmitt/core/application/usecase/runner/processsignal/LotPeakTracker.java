package com.marmitt.core.application.usecase.runner.processsignal;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maior preco visto desde a abertura do lote de cada runner (T69), enviado a estrategia em
 * {@code OpenLot.peakPrice}. Um runner avalia um tick por vez e tem um lote por vez, entao guarda so o lote
 * atual dele. Volatil: apos restart o pico recomeca em {@code max(entrada, preco atual)}.
 */
class LotPeakTracker {

    private record LotPeak(UUID lotId, BigDecimal peak) {
    }

    private final Map<UUID, LotPeak> peakByRunner = new ConcurrentHashMap<>();

    BigDecimal update(UUID runnerId, UUID lotId, BigDecimal entryPrice, BigDecimal price) {
        LotPeak current = peakByRunner.get(runnerId);
        BigDecimal known = current != null && current.lotId().equals(lotId) ? current.peak() : entryPrice;
        BigDecimal peak = price == null ? known : known.max(price);
        peakByRunner.put(runnerId, new LotPeak(lotId, peak));
        return peak;
    }
}
