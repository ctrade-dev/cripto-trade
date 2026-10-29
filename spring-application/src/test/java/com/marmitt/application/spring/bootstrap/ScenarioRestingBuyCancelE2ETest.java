package com.marmitt.application.spring.bootstrap;

import com.marmitt.application.spring.testsupport.strategy.scenario.RestingBuyCancelStrategy;
import com.marmitt.application.spring.testsupport.strategy.scenario.ScenarioStrategyConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T38 — cenario 1: BUY que descansa no book e e cancelada.
 *
 * <p>Com {@code maxCycles = 1} a estrategia coloca uma unica BUY longe do mercado — que o mock
 * mantem aberta em vez de preencher — e a puxa no tick seguinte. O teste assere que a transacao
 * termina em {@code CANCELED}, que o capital reservado volta integralmente e que nenhum lote foi
 * aberto.
 */
class ScenarioRestingBuyCancelE2ETest extends ScenarioStrategyE2ETestSupport {

    @Test
    void restingBuyShouldBeCanceledAndReleaseReservedCapital() {
        UUID runnerId = activateScenario(
                new RestingBuyCancelStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        UUID portfolioId = portfolioIdOf(runnerId);

        driveTicks(12);
        awaitStable(runnerId, QUIET_WINDOW);

        List<TransactionRow> buys = transactionsOfType(runnerId, "BUY");
        assertEquals(1, buys.size(), "maxCycles=1 deve produzir exatamente uma BUY");
        assertEquals("CANCELED", buys.getFirst().status(),
                "A BUY que descansa deve terminar cancelada." + System.lineSeparator() + stateDump(runnerId));
        assertEquals(0, buys.getFirst().price().compareTo(new BigDecimal("61750.00000000")),
                "A BUY deve descansar 5% abaixo do mercado (restingOffset)");

        assertEquals(0, positionCount(runnerId, "OPEN"),
                "Uma BUY cancelada nao pode abrir lote");

        BalanceRow balance = balance(portfolioId);
        assertEquals(0, balance.reserved().compareTo(BigDecimal.ZERO),
                "O cancelamento deve liberar toda a reserva. Saldo=" + balance);
        assertEquals(0, balance.available().compareTo(INITIAL_CAPITAL),
                "Capital disponivel deve voltar ao inicial apos o cancelamento. Saldo=" + balance);

        assertTrue(signalEvaluatedCount(runnerId, "CANCEL") >= 1.0,
                "Esperado signal.evaluated.total{decision=CANCEL} >= 1");
    }

    @Test
    void afterCycleCapNoFurtherOrderShouldBePlaced() {
        UUID runnerId = activateScenario(
                new RestingBuyCancelStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        UUID portfolioId = portfolioIdOf(runnerId);

        driveTicks(12);
        awaitStable(runnerId, QUIET_WINDOW);

        assertEquals(1, transactionCount(runnerId),
                "O ciclo deve deixar exatamente uma transacao");

        assertNoFurtherEffect(runnerId, portfolioId, 6);

        assertFalse(hasLockedPosition(runnerId), "Nenhuma posicao pode ficar travada");
        assertTrue(signalEvaluatedCount(runnerId, "HOLD") >= 1.0,
                "Depois do teto a estrategia deve holdar");
    }
}
