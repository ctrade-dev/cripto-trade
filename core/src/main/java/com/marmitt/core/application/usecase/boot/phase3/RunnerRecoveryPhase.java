package com.marmitt.core.application.usecase.boot.phase3;

import com.marmitt.core.application.usecase.boot.BootContext;
import com.marmitt.core.application.usecase.boot.BootPhase;
import com.marmitt.core.application.usecase.runner.RunnerBootRecoveryUseCase;
import com.marmitt.core.domain.runner.RunnerStatusCause;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.dto.boot.BootExecutionCommand;
import lombok.extern.slf4j.Slf4j;

/** Fase 3: recupera cada runner elegivel (ordens em voo e limbo) com a causa {@code BOOT} (T59). */
@Slf4j
public class RunnerRecoveryPhase implements BootPhase {

    public static final String NAME = "phase3.runner_recovery";

    /** Causa das transicoes que a reconciliacao do boot faz no runner (T59). */
    private static final RunnerStatusCause BOOT_RECOVERY = RunnerStatusCause.boot("boot runner recovery");

    private final RunnerBootRecoveryUseCase runnerBootRecoveryUseCase;

    public RunnerRecoveryPhase(RunnerBootRecoveryUseCase runnerBootRecoveryUseCase) {
        this.runnerBootRecoveryUseCase = runnerBootRecoveryUseCase;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean enabled(BootExecutionCommand command) {
        return command.phase3Enabled();
    }

    @Override
    public void run(BootContext context) {
        context.eligibleRunners().forEach(this::recover);
        log.info("bootSequence.phase3: completed runId={} runners={}", context.runId(),
                context.eligibleRunners().size());
    }

    private void recover(StrategyRunner runner) {
        RunnerBootRecoveryUseCase.RecoverySummary summary =
                runnerBootRecoveryUseCase.recoverRunner(runner, BOOT_RECOVERY);

        log.info("bootSequence: runner={} inFlight={} limbo={}",
                summary.runnerId(), summary.inFlightCount(), summary.limboCount());

        if (log.isDebugEnabled()) {
            summary.notes().forEach(note ->
                    log.debug("bootSequence: runner={} plan={}", summary.runnerId(), note));
        }
    }
}
