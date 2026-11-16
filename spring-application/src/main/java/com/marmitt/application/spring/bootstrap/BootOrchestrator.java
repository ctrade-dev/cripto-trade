package com.marmitt.application.spring.bootstrap;

import com.marmitt.core.dto.boot.BootExecutionCommand;
import com.marmitt.core.enums.BootPhaseStatus;
import com.marmitt.core.enums.BootRunStatus;
import com.marmitt.core.ports.inbound.boot.RunBootSequencePort;
import com.marmitt.core.ports.outbound.boot.BootExecutionObserverPort;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "runner.boot.orchestrator-enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class BootOrchestrator {

    private final RunnerBootPhase1Properties phase1Properties;
    private final RunnerBootPhase2Properties phase2Properties;
    private final RunnerBootPhase3Properties phase3Properties;
    private final PortfolioSanityCheckProperties portfolioSanityCheckProperties;
    private final PortfolioZombieDetectionProperties portfolioZombieDetectionProperties;
    private final PortfolioCutoffProperties portfolioCutoffProperties;
    private final RunBootSequencePort runBootSequence;
    private final BootStatusTracker bootStatusTracker;
    private final BootMetricsRecorder bootMetricsRecorder;
    private final ApplicationEventPublisher applicationEventPublisher;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        BootExecutionCommand command = new BootExecutionCommand(
                phase1Properties.isEnabled(),
                phase2Properties.isEnabled(),
                phase3Properties.isEnabled(),
                portfolioSanityCheckProperties.isEnabled(),
                portfolioZombieDetectionProperties.isEnabled(),
                portfolioCutoffProperties.isEnabled(),
                phase2Properties.getMode(),
                phase2Properties.getAccountQueryPolicy()
        );

        runBootSequence.execute(command, buildObserver());
    }

    /**
     * Traduz os eventos do boot para o progresso ao vivo ({@code GET} do boot), as metricas e o evento de
     * fail-fast. O log fica no core (T70).
     */

    private BootExecutionObserverPort buildObserver() {
        return new BootExecutionObserverPort() {
            @Override
            public void onRunStarted(String runId, String mode) {
                bootStatusTracker.startRun(runId, mode);
            }

            @Override
            public void onPhaseSkipped(String phase) {
                bootStatusTracker.completePhase(phase, BootPhaseStatus.SKIPPED, "disabled_by_configuration");
                bootMetricsRecorder.recordPhase(phase, BootPhaseStatus.SKIPPED, 0L);
            }

            @Override
            public void onPhaseStarted(String phase) {
                bootStatusTracker.startPhase(phase);
            }

            @Override
            public void onPhaseSucceeded(String phase, long durationMs) {
                bootStatusTracker.completePhase(phase, BootPhaseStatus.SUCCESS, "ok");
                bootMetricsRecorder.recordPhase(phase, BootPhaseStatus.SUCCESS, durationMs);
            }

            @Override
            public void onPhaseFailed(String phase, long durationMs, String message) {
                bootStatusTracker.completePhase(phase, BootPhaseStatus.FAILED, message);
                bootMetricsRecorder.recordPhase(phase, BootPhaseStatus.FAILED, durationMs);
            }

            @Override
            public void onPortfolioPhaseEvaluated(String phase, String status, String exchange, String mode, long durationMs) {
                bootMetricsRecorder.recordPortfolioPhaseEvaluation(phase, status, exchange, mode, durationMs);
            }

            @Override
            public void onFailFastRequested(String runId, String phase, String code, String message) {
                bootMetricsRecorder.recordFailFast(phase, code);
                applicationEventPublisher.publishEvent(new BootFailFastEvent(runId, phase, code, message, Instant.now()));
            }

            @Override
            public void onRunCompleted(String runId) {
                bootStatusTracker.completeRun();
                bootMetricsRecorder.recordRun(BootRunStatus.SUCCESS);
            }

            @Override
            public void onRunFailed(String runId, String failurePhase, String failureMessage) {
                // Falha fora de uma fase (carga de portfolios e runners) aparece como "boot".
                bootStatusTracker.failRun(failurePhase != null ? failurePhase : "boot", failureMessage);
                bootMetricsRecorder.recordRun(BootRunStatus.FAILED);
            }
        };
    }
}
