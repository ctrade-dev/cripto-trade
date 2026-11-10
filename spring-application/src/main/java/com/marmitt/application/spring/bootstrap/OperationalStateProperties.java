package com.marmitt.application.spring.bootstrap;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Amostragem do estado operacional para as métricas (T51) e limiares do estado consolidado (T55).
 * Defaults do IG §13.8 e das decisões da T55.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ctrade.observability")
public class OperationalStateProperties {

    private boolean enabled = true;
    /** Intervalo entre amostras de Safe Mode, capital e lifecycle. */
    private long reportIntervalMs = 15_000L;
    /** Runner em TERMINATING há mais que isso conta como travado (IG: 10 min). */
    private long terminationTimeoutMs = 600_000L;
    /** T55: sem tick há mais que isso, com runner ACTIVE no símbolo, é CRITICAL. */
    private long marketDataStaleMs = 60_000L;
    /** T53 F21: estrategia de runner ACTIVE sem decisao (so falhas) ha mais que isso e WARN. */
    private long strategyUnavailableMs = 60_000L;
    /** T55: BUY em voo segurando capital há mais que isso é WARN. */
    private long reservationStaleMs = 86_400_000L;
}
