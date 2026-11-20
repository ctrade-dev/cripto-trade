package com.marmitt.application.spring.bootstrap;

import com.marmitt.application.spring.config.core.CandleSeriesProperties;
import com.marmitt.core.ports.inbound.marketdata.EnsureCandleSeriesPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Agenda a manutencao das series de candles (T72). So agenda; a regra e do core. */
@Slf4j
@Component
public class CandleSeriesScheduler {

    private final EnsureCandleSeriesPort ensureCandleSeries;
    private final CandleSeriesProperties properties;

    public CandleSeriesScheduler(EnsureCandleSeriesPort ensureCandleSeries, CandleSeriesProperties properties) {
        this.ensureCandleSeries = ensureCandleSeries;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${ctrade.market-data.candles.initial-delay-ms:5000}",
            fixedDelayString = "${ctrade.market-data.candles.interval-ms:1000}")
    public void scheduledTick() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            ensureCandleSeries.ensure();
        } catch (RuntimeException e) {
            log.error("candles: cycle failed - {}", e.getMessage(), e);
        }
    }
}
