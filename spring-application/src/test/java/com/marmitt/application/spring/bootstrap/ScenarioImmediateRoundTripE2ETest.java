package com.marmitt.application.spring.bootstrap;

import com.marmitt.application.spring.testsupport.strategy.scenario.ImmediateRoundTripStrategy;
import com.marmitt.application.spring.testsupport.strategy.scenario.ScenarioStrategyConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T38 — cenario 3: round trip feliz completo.
 *
 * <p>Com {@code maxCycles = 1} a estrategia abre uma BUY marketable, espera o fill, fecha o lote
 * com uma SELL marketable e depois holda. O teste assere o ciclo inteiro (BUY FILLED, SELL FILLED,
 * posicao fechada, match materializado) e que ticks adicionais nao produzem efeito.
 */
class ScenarioImmediateRoundTripE2ETest extends ScenarioStrategyE2ETestSupport {

    @Test
    void roundTripShouldFillBuyThenSellAndClosePosition() {
        UUID runnerId = activateScenario(
                new ImmediateRoundTripStrategy(ScenarioStrategyConfig.boundedConfig(1)));

        driveTicks(10);

        awaitCondition(
                () -> transactionsOfType(runnerId, "SELL").stream()
                        .anyMatch(tx -> "FILLED".equals(tx.status())),
                WAIT_TIMEOUT,
                "SELL FILLED fechando o round trip",
                runnerId
        );

        List<TransactionRow> buys = transactionsOfType(runnerId, "BUY");
        List<TransactionRow> sells = transactionsOfType(runnerId, "SELL");
        assertEquals(1, buys.size(), "maxCycles=1 deve produzir exatamente uma BUY");
        assertEquals(1, sells.size(), "maxCycles=1 deve produzir exatamente uma SELL");
        assertEquals("FILLED", buys.getFirst().status());
        assertEquals("FILLED", sells.getFirst().status());
        assertEquals(0, buys.getFirst().quantity().compareTo(buys.getFirst().executedQuantity()),
                "BUY deve executar a quantidade integral");
        assertEquals(0, sells.getFirst().quantity().compareTo(sells.getFirst().executedQuantity()),
                "SELL deve executar a quantidade integral");

        awaitCondition(
                () -> positionCount(runnerId, "OPEN") == 0,
                WAIT_TIMEOUT,
                "fechamento da posicao do runner " + runnerId
        );
        assertEquals(1, positionCount(runnerId, "CLOSED"),
                "O round trip deve deixar exatamente uma posicao fechada");

        awaitStable(runnerId, QUIET_WINDOW);
        // Um fill parcial gera um match por incremento, entao o que importa e a quantidade casada
        // somar exatamente o que foi vendido — nao o numero de linhas.
        assertTrue(matchCount(runnerId) >= 1, "O round trip deve materializar ao menos um match");
        assertEquals(0, totalMatchedQuantity(runnerId).compareTo(sells.getFirst().executedQuantity()),
                "A quantidade casada deve somar exatamente a quantidade vendida");

        assertTrue(signalEvaluatedCount(runnerId, "BUY") >= 1.0,
                "Esperado signal.evaluated.total{decision=BUY} >= 1");
        assertTrue(signalEvaluatedCount(runnerId, "SELL") >= 1.0,
                "Esperado signal.evaluated.total{decision=SELL} >= 1");
    }

    @Test
    void afterCycleCapNoFurtherTransactionShouldBeCreated() {
        UUID runnerId = activateScenario(
                new ImmediateRoundTripStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        UUID portfolioId = portfolioIdOf(runnerId);

        driveTicks(10);
        awaitStable(runnerId, QUIET_WINDOW);

        assertEquals(2, transactionCount(runnerId),
                "O ciclo fechado deve deixar exatamente BUY + SELL");

        assertNoFurtherEffect(runnerId, portfolioId, 5);
    }

    @Test
    void closedRoundTripShouldMaterializeRealizedPnl() {
        UUID runnerId = activateScenario(
                new ImmediateRoundTripStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        UUID portfolioId = portfolioIdOf(runnerId);

        driveTicks(10);
        awaitStable(runnerId, QUIET_WINDOW);

        BalanceRow balance = balance(portfolioId);
        assertTrue(balance.realized().compareTo(BigDecimal.ZERO) != 0,
                "O round trip deve materializar PnL realizado. Saldo=" + balance);
        // Comprar acima do mercado e vender abaixo, somado a fees, so pode dar prejuizo.
        assertTrue(balance.realized().compareTo(BigDecimal.ZERO) < 0,
                "Round trip marketable com fees deve realizar prejuizo. Saldo=" + balance);
        // T46: o MOCK cobra a fee da BUY no ativo base (0,1% por fill), como a Binance; o lote recebe
        // a quantidade liquida e e ela que a venda fecha.
        assertNetOfBuyFee(totalMatchedQuantity(runnerId), "quantidade casada");
    }

    @Test
    void closedRoundTripShouldLeaveNoCapitalReserved() {
        // T36: a BUY marketable executa abaixo do limite; o excedente da reserva precisa voltar ao
        // disponivel. Antes da T36 sobrava quantity x (limite - executado) preso em reserved.
        UUID runnerId = activateScenario(
                new ImmediateRoundTripStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        UUID portfolioId = portfolioIdOf(runnerId);

        driveTicks(10);
        awaitStable(runnerId, QUIET_WINDOW);

        BalanceRow balance = balance(portfolioId);
        assertEquals(0, balance.reserved().compareTo(BigDecimal.ZERO),
                "Sem ordem viva nem lote aberto, nada pode ficar reservado. Saldo=" + balance);
        assertEquals(0, balance.available().subtract(INITIAL_CAPITAL).compareTo(balance.realized()),
                "Com tudo fechado, available - capital inicial deve ser o PnL realizado. Saldo=" + balance);
    }

    @Test
    void buyFeeInBaseAssetShouldOpenLotWithNetQuantityAndSellIt() {
        // T46: BUY de 0.001 BTC com fee de 0.000001 BTC credita 0.000999. A venda do lote inteiro
        // usa a quantidade liquida e e aceita; antes, a SELL pedia 0.001 e a exchange recusaria.
        UUID runnerId = activateScenario(
                new ImmediateRoundTripStrategy(ScenarioStrategyConfig.boundedConfig(1)));

        driveTicks(10);
        awaitStable(runnerId, QUIET_WINDOW);

        TransactionRow buy = transactionsOfType(runnerId, "BUY").getFirst();
        TransactionRow sell = transactionsOfType(runnerId, "SELL").getFirst();
        assertEquals(0, buy.executedQuantity().compareTo(new BigDecimal("0.00100000")),
                "A transacao BUY reflete o executado bruto informado pela exchange");
        assertEquals("FILLED", sell.status());
        assertNetOfBuyFee(sell.executedQuantity(), "SELL");
        assertEquals(0, sell.executedQuantity().compareTo(totalMatchedQuantity(runnerId)),
                "A SELL vende exatamente a quantidade do lote");
        assertEquals(1, positionCount(runnerId, "CLOSED"), "O lote liquido deve fechar por inteiro");
    }

    /**
     * A quantidade liquida fica abaixo do executado bruto (0.001) e no maximo uma taxa (0,1%) abaixo
     * dele. O valor exato depende do arredondamento da fee em cada fill parcial do MOCK.
     */
    private static void assertNetOfBuyFee(BigDecimal quantity, String what) {
        BigDecimal gross = new BigDecimal("0.00100000");
        assertTrue(quantity.compareTo(gross) < 0, what + " deve ser liquida da fee de BUY: " + quantity);
        assertTrue(quantity.compareTo(new BigDecimal("0.00099900")) >= 0,
                what + " nao pode descontar mais que a taxa de 0,1%: " + quantity);
    }
}
