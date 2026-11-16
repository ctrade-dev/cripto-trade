package com.marmitt.application.spring.bootstrap;

import com.marmitt.core.ports.inbound.portfolio.EvaluateExchangeBalancePort;
import com.marmitt.core.ports.inbound.portfolio.EvaluateOperationalTriggersPort;
import com.marmitt.core.ports.inbound.portfolio.ResumeSafeModeAfterExchangeRecoveryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara a avaliação periódica dos gatilhos operacionais de Safe Mode (T25), da retomada automática do HALT por
 * exchange fora do ar (T71, mesmo tick) e da conferência de saldo por exchange (T57, intervalo próprio: uma
 * consulta de conta por exchange). Só agenda; a regra fica no core.
 */
@Slf4j
@Component
public class OperationalTriggerScheduler {

    private final EvaluateOperationalTriggersPort evaluateOperationalTriggers;
    private final ResumeSafeModeAfterExchangeRecoveryPort resumeAfterExchangeRecovery;
    private final EvaluateExchangeBalancePort evaluateExchangeBalance;
    private final AutoSafeModeProperties properties;

    public OperationalTriggerScheduler(EvaluateOperationalTriggersPort evaluateOperationalTriggers,
                                       ResumeSafeModeAfterExchangeRecoveryPort resumeAfterExchangeRecovery,
                                       EvaluateExchangeBalancePort evaluateExchangeBalance,
                                       AutoSafeModeProperties properties) {
        this.evaluateOperationalTriggers = evaluateOperationalTriggers;
        this.resumeAfterExchangeRecovery = resumeAfterExchangeRecovery;
        this.evaluateExchangeBalance = evaluateExchangeBalance;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${safe-mode.auto.evaluation-interval-ms:30000}",
            initialDelayString = "${safe-mode.auto.evaluation-interval-ms:30000}"
    )
    public void scheduledTick() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            evaluateOperationalTriggers.evaluate();
        } catch (RuntimeException e) {
            log.error("autoSafeMode: evaluation cycle failed - {}", e.getMessage(), e);
        }
        if (!properties.getResume().isEnabled()) {
            return;
        }
        try {
            resumeAfterExchangeRecovery.evaluate();
        } catch (RuntimeException e) {
            log.error("autoResume: evaluation cycle failed - {}", e.getMessage(), e);
        }
    }

    @Scheduled(
            fixedDelayString = "${safe-mode.auto.balance-divergence.evaluation-interval-ms:300000}",
            initialDelayString = "${safe-mode.auto.balance-divergence.evaluation-interval-ms:300000}"
    )
    public void exchangeBalanceTick() {
        if (!properties.isEnabled() || !properties.getBalanceDivergence().isEnabled()) {
            return;
        }
        try {
            evaluateExchangeBalance.evaluate();
        } catch (RuntimeException e) {
            log.error("exchangeBalance: evaluation cycle failed - {}", e.getMessage(), e);
        }
    }
}
