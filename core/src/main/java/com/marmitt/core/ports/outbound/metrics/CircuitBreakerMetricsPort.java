package com.marmitt.core.ports.outbound.metrics;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Métricas do monitor de drawdown (T43, IG §11.7) e do HALT automático (T25, T71).
 */
public interface CircuitBreakerMetricsPort {

    /** Estado do portfolio na última avaliação: equity, pico da janela e drawdown (fração). */
    void recordDrawdown(UUID portfolioId, BigDecimal equity, BigDecimal peak, BigDecimal drawdown);

    /** Ativação automática do Safe Mode pelo monitor. */
    void recordActivation(UUID portfolioId, String reason);

    /** Retomada automática do HALT, com o gatilho que o causou (T71). */
    void recordAutoResume(UUID portfolioId, String trigger);
}
