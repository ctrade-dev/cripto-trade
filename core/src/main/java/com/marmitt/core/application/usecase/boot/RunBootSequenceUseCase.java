package com.marmitt.core.application.usecase.boot;

import com.marmitt.core.domain.portfolio.Portfolio;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.dto.boot.BootExecutionCommand;
import com.marmitt.core.dto.boot.BootRun;
import com.marmitt.core.enums.BootRunStatus;
import com.marmitt.core.enums.RunnerStatus;
import com.marmitt.core.ports.inbound.boot.RunBootSequencePort;
import com.marmitt.core.ports.outbound.boot.BootExecutionObserverPort;
import com.marmitt.core.ports.outbound.repository.BootRunRepositoryPort;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sequencia do boot (T70): roda as fases na ordem recebida, liga/desliga cada uma pelo comando, cronometra,
 * loga o status, trata a falha e grava o historico em {@code boot_runs}. O corpo de cada fase fica na sua
 * classe ({@link BootPhase}).
 */
@Slf4j
public class RunBootSequenceUseCase implements RunBootSequencePort {

    private final PortfolioRepositoryPort portfolioRepository;
    private final StrategyRunnerRepositoryPort strategyRunnerRepository;
    private final List<BootPhase> phases;
    private final BootRunRepositoryPort bootRunRepository;

    public RunBootSequenceUseCase(PortfolioRepositoryPort portfolioRepository,
                                  StrategyRunnerRepositoryPort strategyRunnerRepository,
                                  List<BootPhase> phases,
                                  BootRunRepositoryPort bootRunRepository) {
        this.portfolioRepository = portfolioRepository;
        this.strategyRunnerRepository = strategyRunnerRepository;
        this.phases = List.copyOf(phases);
        this.bootRunRepository = bootRunRepository;
    }

    @Override
    public void execute(BootExecutionCommand command, BootExecutionObserverPort observer) {
        String runId = UUID.randomUUID().toString();
        String mode = command.failureMode().name();
        Instant startedAt = Instant.now();
        String currentPhase = null;
        observer.onRunStarted(runId, mode);
        log.info("bootSequence: start runId={} mode={} phase1Enabled={} phase2Enabled={} phase3Enabled={}",
                runId, command.failureMode(), command.phase1Enabled(), command.phase2Enabled(), command.phase3Enabled());

        try {
            List<Portfolio> portfolios = portfolioRepository.findAll();
            List<StrategyRunner> eligibleRunners = portfolios.stream()
                    .flatMap(portfolio -> strategyRunnerRepository.findByPortfolioId(portfolio.getId()).stream())
                    .filter(RunBootSequenceUseCase::isEligibleForRecovery)
                    .toList();
            BootContext context = new BootContext(runId, command, observer, portfolios, eligibleRunners);

            for (BootPhase phase : phases) {
                currentPhase = phase.name();
                executePhase(phase, context);
            }
            currentPhase = null;

            recordRun(new BootRun(runId, mode, BootRunStatus.SUCCESS, startedAt, Instant.now(), null, null));
            observer.onRunCompleted(runId);
            log.info("bootSequence: completed runId={} portfolios={} runners={}",
                    runId, portfolios.size(), eligibleRunners.size());
        } catch (RuntimeException e) {
            recordRun(new BootRun(runId, mode, BootRunStatus.FAILED, startedAt, Instant.now(), currentPhase,
                    e.getMessage()));
            observer.onRunFailed(runId, currentPhase, e.getMessage());
            log.error("bootSequence: failed runId={} phase={} reason={}", runId, currentPhase, e.getMessage(), e);
            throw e;
        }
    }

    private void executePhase(BootPhase phase, BootContext context) {
        String name = phase.name();
        BootExecutionObserverPort observer = context.observer();
        if (!phase.enabled(context.command())) {
            observer.onPhaseSkipped(name);
            log.info("bootSequence: phase={} status=SKIPPED reason=disabled_by_configuration runId={}",
                    name, context.runId());
            return;
        }

        long startedNs = System.nanoTime();
        observer.onPhaseStarted(name);
        try {
            phase.run(context);
        } catch (RuntimeException e) {
            long durationMs = elapsedMs(startedNs);
            observer.onPhaseFailed(name, durationMs, e.getMessage());
            log.warn("bootSequence: phase={} status=FAILED durationMs={} runId={}", name, durationMs, context.runId());
            throw e;
        }
        long durationMs = elapsedMs(startedNs);
        observer.onPhaseSucceeded(name, durationMs);
        log.info("bootSequence: phase={} status=SUCCESS durationMs={} runId={}", name, durationMs, context.runId());
    }

    /**
     * Historico do boot (T59). Falha ao gravar nao derruba o boot: um boot que falhou por falta de
     * banco nao tem onde registrar, e o log continua sendo a fonte nesse caso.
     */
    private void recordRun(BootRun run) {
        try {
            bootRunRepository.save(run);
        } catch (RuntimeException e) {
            log.warn("bootSequence: boot run not recorded runId={} status={} - {}", run.runId(), run.status(),
                    e.getMessage());
        }
    }

    private static long elapsedMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000L;
    }

    private static boolean isEligibleForRecovery(StrategyRunner runner) {
        return runner.getStatus() != RunnerStatus.ARCHIVED
                && runner.getStatus() != RunnerStatus.TERMINATING;
    }
}
