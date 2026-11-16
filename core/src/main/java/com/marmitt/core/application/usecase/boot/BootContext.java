package com.marmitt.core.application.usecase.boot;

import com.marmitt.core.domain.portfolio.Portfolio;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.dto.boot.BootExecutionCommand;
import com.marmitt.core.ports.outbound.boot.BootExecutionObserverPort;

import java.util.List;

/**
 * O que as fases do boot compartilham, carregado uma vez pela sequencia: portfolios e runners elegiveis
 * (nem {@code ARCHIVED} nem {@code TERMINATING}).
 */
public record BootContext(
        String runId,
        BootExecutionCommand command,
        BootExecutionObserverPort observer,
        List<Portfolio> portfolios,
        List<StrategyRunner> eligibleRunners
) {

    /**
     * Pede o fail-fast ao observer e devolve a excecao que a fase lanca para falhar o boot.
     */
    public IllegalStateException failFast(String phase, String code, String message) {
        observer.onFailFastRequested(runId, phase, code, message);
        return new IllegalStateException(message + " code=" + code);
    }
}
