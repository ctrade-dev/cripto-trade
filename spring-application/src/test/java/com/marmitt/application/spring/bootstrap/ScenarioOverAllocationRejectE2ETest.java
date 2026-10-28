package com.marmitt.application.spring.bootstrap;

import com.marmitt.application.spring.testsupport.strategy.scenario.OverAllocationRejectStrategy;
import com.marmitt.application.spring.testsupport.strategy.scenario.ScenarioStrategyConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T38 — cenario 4: a BUY e recusada na reserva de capital.
 *
 * <p>Com {@code maxCycles = 1} a estrategia emite uma unica BUY cujo custo estimado excede o
 * capital disponivel. A reserva e recusada antes do dispatch, entao nada vira transacao e o saldo
 * fica intacto. Depois do teto, a estrategia holda para sempre.
 */
class ScenarioOverAllocationRejectE2ETest extends ScenarioStrategyE2ETestSupport {

    @Test
    void overAllocatingBuyShouldBeRejectedOnCapitalWithoutPersistingTransaction() {
        UUID runnerId = activateScenario(
                new OverAllocationRejectStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        UUID portfolioId = portfolioIdOf(runnerId);
        BalanceRow before = balance(portfolioId);

        driveTicks(3);

        awaitCondition(
                () -> signalEvaluatedCount(runnerId, "REJECTED_CAPITAL") >= 1.0,
                WAIT_TIMEOUT,
                "signal.evaluated.total{decision=REJECTED_CAPITAL} do runner " + runnerId
        );

        assertEquals(0, transactionCount(runnerId),
                "Reserva recusada nao pode persistir transacao");
        assertEquals(0, positionCount(runnerId, "OPEN"),
                "Reserva recusada nao pode abrir posicao");

        BalanceRow after = balance(portfolioId);
        assertEquals(0, before.available().compareTo(after.available()),
                "Saldo disponivel deve ficar intacto apos a recusa");
        assertEquals(0, after.reserved().compareTo(BigDecimal.ZERO),
                "Nada pode ficar reservado apos a recusa");
    }

    @Test
    void cycleCapShouldStopAtASingleRejection() {
        UUID runnerId = activateScenario(
                new OverAllocationRejectStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        UUID portfolioId = portfolioIdOf(runnerId);

        driveTicks(3);
        awaitCondition(
                () -> signalEvaluatedCount(runnerId, "REJECTED_CAPITAL") >= 1.0,
                WAIT_TIMEOUT,
                "primeira recusa de capital do runner " + runnerId
        );
        double rejectionsAfterCap = signalEvaluatedCount(runnerId, "REJECTED_CAPITAL");

        driveTicks(4);
        sleep(QUIET_WINDOW.toMillis());

        assertEquals(1.0, rejectionsAfterCap,
                "maxCycles=1 deve produzir exatamente uma recusa");
        assertEquals(rejectionsAfterCap, signalEvaluatedCount(runnerId, "REJECTED_CAPITAL"),
                "Ticks apos o teto nao podem gerar nova recusa");
        assertTrue(signalEvaluatedCount(runnerId, "HOLD") >= 1.0,
                "Depois do teto a estrategia deve holdar");
        assertEquals(0, transactionCount(runnerId));
        assertEquals(0, balance(portfolioId).reserved().compareTo(BigDecimal.ZERO));
    }
}
