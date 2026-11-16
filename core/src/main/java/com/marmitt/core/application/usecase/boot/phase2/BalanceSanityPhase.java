package com.marmitt.core.application.usecase.boot.phase2;

import com.marmitt.core.application.usecase.boot.BootContext;
import com.marmitt.core.application.usecase.boot.BootPhase;
import com.marmitt.core.application.usecase.exchange.ExchangeBalanceReconciliation;
import com.marmitt.core.application.usecase.portfolio.AutomaticSafeModeTrigger;
import com.marmitt.core.dto.boot.BootExecutionCommand;
import com.marmitt.core.dto.exchange.ExchangeBalanceReport;
import com.marmitt.core.enums.BootAccountQueryPolicy;
import com.marmitt.core.enums.BootFailureMode;
import com.marmitt.core.enums.ExchangeBalanceStatus;
import com.marmitt.core.enums.SafeModeTrigger;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Fase 2, conferencia de saldo por exchange (T57): soma dos portfolios contra a conta. {@code DEFICIT} poe em
 * HALT todos os portfolios da exchange (nao da para atribuir a falta a um so) antes de qualquer BUY;
 * persistido, vale tambem se o fail-fast abortar o boot a seguir. {@code PENDING} (falta coberta por
 * ordens em voo, que a fase 3 concilia) so registra.
 */
@Slf4j
public class BalanceSanityPhase implements BootPhase {

    public static final String NAME = "phase2.sanity";

    private final ExchangeBalanceReconciliation exchangeBalanceReconciliation;
    private final AutomaticSafeModeTrigger safeModeTrigger;

    public BalanceSanityPhase(ExchangeBalanceReconciliation exchangeBalanceReconciliation,
                              AutomaticSafeModeTrigger safeModeTrigger) {
        this.exchangeBalanceReconciliation = exchangeBalanceReconciliation;
        this.safeModeTrigger = safeModeTrigger;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean enabled(BootExecutionCommand command) {
        return command.phase2Enabled() && command.sanityEnabled();
    }

    @Override
    public void run(BootContext context) {
        BootExecutionCommand command = context.command();
        BootFailureMode mode = command.failureMode();
        log.info("bootSequence.phase2.sanity: start mode={} accountQueryPolicy={}", mode, command.accountQueryPolicy());

        ExchangeBalanceStatus aggregateResult = null;
        long startedNs = System.nanoTime();
        List<ExchangeBalanceReport> reports = exchangeBalanceReconciliation.reconcileAll();
        long durationMs = (System.nanoTime() - startedNs) / 1_000_000L;
        for (ExchangeBalanceReport report : reports) {
            context.observer().onPortfolioPhaseEvaluated(NAME, report.status().name(), report.exchange(),
                    mode.name(), durationMs);
            aggregateResult = aggregateResult == null || report.status().compareTo(aggregateResult) > 0
                    ? report.status() : aggregateResult;

            switch (report.status()) {
                case OK -> log.info("bootSequence.phase2.sanity: portfolios={} {}",
                        report.portfolios().size(), report.details());
                case PENDING, SKIPPED, FAILED -> log.warn("bootSequence.phase2.sanity: portfolios={} {}",
                        report.portfolios().size(), report.details());
                case DEFICIT -> {
                    log.error("bootSequence.phase2.sanity: portfolios={} {}",
                            report.portfolios().size(), report.details());
                    report.portfolios().forEach(portfolioId -> safeModeTrigger.activate(portfolioId,
                            SafeModeTrigger.BALANCE_DIVERGENCE, report.details()));
                }
            }

            boolean skippedWithFailPolicy = report.status() == ExchangeBalanceStatus.SKIPPED
                    && command.accountQueryPolicy() == BootAccountQueryPolicy.FAIL;
            boolean criticalFailure = report.status() == ExchangeBalanceStatus.DEFICIT
                    || report.status() == ExchangeBalanceStatus.FAILED;
            if ((skippedWithFailPolicy || criticalFailure) && mode == BootFailureMode.FAIL_FAST) {
                throw context.failFast(NAME, report.code(), "Phase2 sanity failed " + report.details());
            }
        }

        log.info("bootSequence.phase2.sanity: completed runId={} result={} exchanges={}",
                context.runId(), aggregateResult != null ? aggregateResult.name() : "NONE", reports.size());
    }
}
