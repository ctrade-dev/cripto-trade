package com.marmitt.application.spring.bootstrap;

import com.marmitt.application.spring.testsupport.strategy.scenario.FilledBuyRestingSellCancelStrategy;
import com.marmitt.application.spring.testsupport.strategy.scenario.ScenarioStrategyConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T38 — cenario 2: BUY preenchida, SELL que descansa e e cancelada.
 *
 * <p>Com {@code maxCycles = 1} a estrategia preenche uma BUY marketable, coloca uma SELL longe do
 * mercado sobre o lote — que o mock mantem aberta — e a puxa no tick seguinte. O lote **permanece
 * aberto por design**: fechar posicao e objetivo do cenario 3. O teste assere o cancelamento da
 * SELL e, sobretudo, a liberacao do lock da posicao.
 */
class ScenarioFilledBuyRestingSellCancelE2ETest extends ScenarioStrategyE2ETestSupport {

    @Test
    void restingSellShouldBeCanceledAndReleasePositionLock() {
        UUID runnerId = activateScenario(
                new FilledBuyRestingSellCancelStrategy(ScenarioStrategyConfig.boundedConfig(1)));

        driveTicks(14);
        awaitStable(runnerId, QUIET_WINDOW);

        List<TransactionRow> buys = transactionsOfType(runnerId, "BUY");
        List<TransactionRow> sells = transactionsOfType(runnerId, "SELL");
        assertEquals(1, buys.size(), "Deve haver exatamente uma BUY");
        assertEquals(1, sells.size(), "maxCycles=1 deve produzir exatamente uma SELL");
        assertEquals("FILLED", buys.getFirst().status(),
                "A BUY marketable deve preencher." + System.lineSeparator() + stateDump(runnerId));
        assertEquals("CANCELED", sells.getFirst().status(),
                "A SELL que descansa deve terminar cancelada." + System.lineSeparator() + stateDump(runnerId));
        assertEquals(0, sells.getFirst().price().compareTo(new BigDecimal("68250.00000000")),
                "A SELL deve descansar 5% acima do mercado (restingOffset)");

        assertFalse(hasLockedPosition(runnerId),
                "O cancelamento da SELL deve liberar o lock da posicao."
                        + System.lineSeparator() + stateDump(runnerId));
        assertEquals(1, positionCount(runnerId, "OPEN"),
                "O lote permanece aberto por design — fechar posicao e do cenario 3");
        assertEquals(0, matchCount(runnerId),
                "SELL cancelada nao pode gerar match");

        assertTrue(signalEvaluatedCount(runnerId, "CANCEL") >= 1.0,
                "Esperado signal.evaluated.total{decision=CANCEL} >= 1");
        assertTrue(signalEvaluatedCount(runnerId, "SELL") >= 1.0,
                "Esperado signal.evaluated.total{decision=SELL} >= 1");
    }

    @Test
    void afterCycleCapNoFurtherSellShouldBePlaced() {
        UUID runnerId = activateScenario(
                new FilledBuyRestingSellCancelStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        UUID portfolioId = portfolioIdOf(runnerId);

        driveTicks(14);
        awaitStable(runnerId, QUIET_WINDOW);

        assertEquals(2, transactionCount(runnerId),
                "O ciclo deve deixar exatamente BUY + SELL");

        assertNoFurtherEffect(runnerId, portfolioId, 6);

        assertFalse(hasLockedPosition(runnerId), "Nenhuma posicao pode ficar travada apos o teto");
        assertTrue(signalEvaluatedCount(runnerId, "HOLD") >= 1.0,
                "Depois do teto a estrategia deve holdar");
    }

    @Test
    void openLotShouldKeepOnlyTheExecutedBuyCostReserved() {
        // T36: com o lote aberto, reserved deve ser exatamente o custo executado da BUY — o mesmo
        // valor que a venda futura baixara em confirmExecution. Excedente do limite volta ao disponivel.
        UUID runnerId = activateScenario(
                new FilledBuyRestingSellCancelStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        UUID portfolioId = portfolioIdOf(runnerId);

        driveTicks(14);
        awaitStable(runnerId, QUIET_WINDOW);

        TransactionRow buy = transactionsOfType(runnerId, "BUY").getFirst();
        BigDecimal executedCost = buy.executedQuantity().multiply(buy.executedPrice())
                .setScale(8, RoundingMode.HALF_UP);
        BalanceRow balance = balance(portfolioId);
        assertEquals(0, balance.reserved().compareTo(executedCost),
                "reserved deve igualar o custo executado da BUY. buy=" + buy + " saldo=" + balance);
        assertEquals(0, balance.available().add(balance.reserved()).compareTo(INITIAL_CAPITAL),
                "Sem PnL realizado, available + reserved deve ser o capital inicial. Saldo=" + balance);
    }
}
