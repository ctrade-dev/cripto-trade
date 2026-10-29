package com.marmitt.application.spring.bootstrap;

import com.marmitt.core.dto.portfolio.request.ChangeSafeModeRequest;
import com.marmitt.core.dto.portfolio.response.SafeModeStatusDto;
import com.marmitt.core.dto.runner.request.UpdateRunnerLimitsRequest;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.SafeModeOrigin;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.ports.inbound.portfolio.ManageSafeModePort;
import com.marmitt.core.ports.inbound.runner.ManageRunnerLifecyclePort;
import com.marmitt.application.spring.testsupport.strategy.scenario.FilledBuyRestingSellCancelStrategy;
import com.marmitt.application.spring.testsupport.strategy.scenario.ImmediateRoundTripStrategy;
import com.marmitt.application.spring.testsupport.strategy.scenario.RestingBuyCancelStrategy;
import com.marmitt.application.spring.testsupport.strategy.scenario.ScenarioStrategyConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * T45 — niveis do Safe Mode contra o MOCK, usando as estrategias de cenario da T38; T48 — politica de
 * liquidacao LIMIT do PANIC_SELL, com preenchimento e com escalada para MARKET.
 *
 * <p>Cada teste dirige ticks ate o estado de interesse (ordem descansando, lote aberto), muda o nivel
 * pelo port de entrada e prova o efeito pelo pipeline real: cancelamento conciliado, venda MARKET,
 * capital devolvido e nenhuma acao da estrategia nos niveis de acao em massa.
 */
class SafeModeE2ETest extends ScenarioStrategyE2ETestSupport {

    @Autowired
    private ManageSafeModePort manageSafeMode;

    @Autowired
    private ManageRunnerLifecyclePort runnerLifecycle;

    @Test
    void haltBlocksNewBuysButKeepsTheStrategyRunning() {
        UUID runnerId = activateScenario(new ImmediateRoundTripStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        UUID portfolioId = portfolioIdOf(runnerId);
        change(portfolioId, SafeModeStatus.HALT, false);

        driveTicks(6);
        sleep(QUIET_WINDOW.toMillis());

        assertTrue(transactionsOfType(runnerId, "BUY").stream()
                        .noneMatch(tx -> List.of("SUBMITTED", "PARTIAL", "FILLED").contains(tx.status())),
                "HALT nao pode deixar BUY chegar a exchange" + System.lineSeparator() + stateDump(runnerId));
        assertEquals(0, positionCount(runnerId, "OPEN"));
        assertTrue(signalEvaluatedCount(runnerId, "REJECTED_CAPITAL") >= 1.0,
                "A estrategia continua avaliando; o BUY e recusado na reserva de capital");
        assertEquals(0, balance(portfolioId).reserved().signum());
    }

    @Test
    void haltStillLetsTheStrategySell() {
        UUID runnerId = activateScenario(
                new FilledBuyRestingSellCancelStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        UUID portfolioId = portfolioIdOf(runnerId);
        tickUntil(() -> positionCount(runnerId, "OPEN") == 1 && transactionsOfType(runnerId, "SELL").isEmpty()
                && !hasInFlight(runnerId), "lote aberto sem SELL", runnerId);

        change(portfolioId, SafeModeStatus.HALT, false);
        tickUntil(() -> !transactionsOfType(runnerId, "SELL").isEmpty(), "SELL da estrategia sob HALT", runnerId);

        assertTrue(signalEvaluatedCount(runnerId, "SELL") >= 1.0,
                "Opcao A da T45: no HALT a estrategia ainda pode vender");
    }

    @Test
    void cancelAllCancelsRestingOrdersAndReturnsTheCapital() {
        UUID runnerId = activateScenario(new RestingBuyCancelStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        UUID portfolioId = portfolioIdOf(runnerId);
        tickUntil(() -> transactionsOfType(runnerId, "BUY").stream().anyMatch(tx -> "SUBMITTED".equals(tx.status())),
                "BUY descansando no livro", runnerId);
        assertTrue(balance(portfolioId).reserved().signum() > 0, "A BUY que descansa deve ter capital reservado");

        SafeModeStatusDto status = change(portfolioId, SafeModeStatus.CANCEL_ALL, true);
        assertEquals(1, status.restingOrders(), "O status mostra a ordem que falta cancelar");

        driveTicks(6);
        awaitCondition(() -> "CANCELED".equals(transactionsOfType(runnerId, "BUY").getFirst().status()),
                WAIT_TIMEOUT, "BUY cancelada pelo Safe Mode", runnerId);
        awaitCondition(() -> balance(portfolioId).reserved().signum() == 0, WAIT_TIMEOUT,
                "capital devolvido pela conciliacao", runnerId);

        assertEquals(0, INITIAL_CAPITAL.compareTo(balance(portfolioId).available()));
        assertEquals(0.0, signalEvaluatedCount(runnerId, "CANCEL"),
                "Em CANCEL_ALL a estrategia nao e consultada: o cancelamento e do Safe Mode");
        assertTrue(signalEvaluatedCount(runnerId, "SAFE_MODE") >= 1.0);
        assertEquals(0, manageSafeMode.status(portfolioId).restingOrders());
    }

    @Test
    void panicSellCancelsTheRestingSellAndLiquidatesTheLotAtMarket() {
        UUID runnerId = activateScenario(
                new FilledBuyRestingSellCancelStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        UUID portfolioId = portfolioIdOf(runnerId);
        tickUntil(() -> transactionsOfType(runnerId, "SELL").stream().anyMatch(tx -> "SUBMITTED".equals(tx.status())),
                "SELL LIMIT descansando sobre o lote", runnerId);

        change(portfolioId, SafeModeStatus.PANIC_SELL, true);

        driveTicks(10);
        awaitCondition(() -> positionCount(runnerId, "CLOSED") == 1 && positionCount(runnerId, "OPEN") == 0,
                WAIT_TIMEOUT, "lote liquidado", runnerId);
        awaitCondition(() -> balance(portfolioId).reserved().signum() == 0, WAIT_TIMEOUT,
                "reserved zerado apos a liquidacao", runnerId);
        awaitStable(runnerId, QUIET_WINDOW);

        List<String> sells = sellsWithOrderType(runnerId);
        assertTrue(sells.contains("LIMIT:CANCELED"), "A SELL LIMIT deve ser cancelada: " + sells);
        assertTrue(sells.contains("MARKET:FILLED"), "A liquidacao deve ser uma SELL MARKET preenchida: " + sells);
        assertEquals(2, sells.size(), "Uma SELL LIMIT cancelada e uma unica liquidacao, sem SELL duplicada: " + sells);
        // Um match por fill (o mock preenche em parciais): o casado soma exatamente o executado da liquidacao.
        assertEquals(0, marketSellExecutedQuantity(runnerId).compareTo(totalMatchedQuantity(runnerId)),
                "O lote inteiro deve ser casado pela venda MARKET");
        assertEquals(0.0, signalEvaluatedCount(runnerId, "CANCEL"),
                "Em PANIC_SELL a estrategia nao e consultada");

        SafeModeStatusDto status = manageSafeMode.status(portfolioId);
        assertEquals(0, status.restingOrders());
        assertEquals(0, status.liquidatingOrders());
        assertEquals(0, status.openPositions());

        int transactionsAfter = transactionCount(runnerId);
        driveTicks(4);
        sleep(QUIET_WINDOW.toMillis());
        assertEquals(transactionsAfter, transactionCount(runnerId),
                "Convergido, o PANIC_SELL nao gera novas ordens nos ticks seguintes");
    }

    @Test
    void panicSellWithLimitPolicyLiquidatesWithTheLimitWhenItFills() {
        UUID runnerId = activateScenario(
                new FilledBuyRestingSellCancelStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        UUID portfolioId = portfolioIdOf(runnerId);
        // O MOCK preenche a LIMIT marketable em parciais ao longo de ~1s: prazo largo para provar o
        // caminho em que a LIMIT preenche inteira. A escalada no meio do preenchimento e o teste abaixo.
        useLimitLiquidation(runnerId, 30);
        tickUntil(() -> transactionsOfType(runnerId, "SELL").stream().anyMatch(tx -> "SUBMITTED".equals(tx.status())),
                "SELL LIMIT descansando sobre o lote", runnerId);

        change(portfolioId, SafeModeStatus.PANIC_SELL, true);

        driveTicks(10);
        awaitCondition(() -> positionCount(runnerId, "CLOSED") == 1 && positionCount(runnerId, "OPEN") == 0,
                WAIT_TIMEOUT, "lote liquidado pela LIMIT", runnerId);
        awaitStable(runnerId, QUIET_WINDOW);

        List<String> sells = sellsWithOrderType(runnerId);
        assertEquals(List.of("LIMIT:CANCELED", "LIMIT:FILLED"), sells,
                "SELL da estrategia cancelada e liquidacao LIMIT preenchida, sem MARKET: " + sells);
        BigDecimal liquidationPrice = sellPrices(runnerId).getLast();
        assertEquals(0, new BigDecimal("64350").compareTo(liquidationPrice),
                "SELL LIMIT a 1% abaixo do tick: " + liquidationPrice);
    }

    /**
     * T52: com o prazo padrao de 3 ticks a escalada costuma cancelar a LIMIT no meio dos parciais do
     * MOCK. O MOCK agora para de publicar depois do CANCELED e so debita o executado, entao a MARKET
     * vende o restante sem ser recusada. Se a LIMIT terminar antes do prazo, o desfecho valido e ela
     * preenchida; os dois caminhos fecham o lote inteiro.
     */
    @Test
    void panicSellWithLimitPolicyEscalatingMidFillSellsOnlyTheRestAtMarket() {
        UUID runnerId = activateScenario(
                new FilledBuyRestingSellCancelStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        UUID portfolioId = portfolioIdOf(runnerId);
        useLimitLiquidation(runnerId, 3);
        tickUntil(() -> transactionsOfType(runnerId, "SELL").stream().anyMatch(tx -> "SUBMITTED".equals(tx.status())),
                "SELL LIMIT descansando sobre o lote", runnerId);

        change(portfolioId, SafeModeStatus.PANIC_SELL, true);

        driveTicks(10);
        awaitCondition(() -> positionCount(runnerId, "CLOSED") == 1 && positionCount(runnerId, "OPEN") == 0,
                WAIT_TIMEOUT, "lote liquidado", runnerId);
        awaitStable(runnerId, QUIET_WINDOW);

        List<String> sells = sellsWithOrderType(runnerId);
        assertTrue(sells.equals(List.of("LIMIT:CANCELED", "LIMIT:CANCELED", "MARKET:FILLED"))
                        || sells.equals(List.of("LIMIT:CANCELED", "LIMIT:FILLED")),
                "Escalada (LIMIT cancelada + MARKET do restante) ou LIMIT preenchida, nunca recusada: " + sells);
        assertEquals(0, sellExecutedQuantity(runnerId).compareTo(totalMatchedQuantity(runnerId)),
                "O executado das vendas soma exatamente o casado com o lote");
        assertEquals(0, balance(portfolioId).reserved().signum());
    }

    @Test
    void panicSellWithLimitPolicyEscalatesToMarketWhenTheLimitDoesNotFill() {
        UUID runnerId = activateScenario(
                new FilledBuyRestingSellCancelStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        UUID portfolioId = portfolioIdOf(runnerId);
        useLimitLiquidation(runnerId, 3);
        tickUntil(() -> transactionsOfType(runnerId, "SELL").stream().anyMatch(tx -> "SUBMITTED".equals(tx.status())),
                "SELL LIMIT descansando sobre o lote", runnerId);

        change(portfolioId, SafeModeStatus.PANIC_SELL, true);

        // A exchange ja caiu 2% abaixo do tick: a SELL LIMIT a -1% descansa e nao preenche.
        BigDecimal fallenMarket = new BigDecimal("63700.00");
        Instant deadline = Instant.now().plus(WAIT_TIMEOUT);
        while (Instant.now().isBefore(deadline) && positionCount(runnerId, "CLOSED") == 0) {
            publishTick(MARKET_PRICE, fallenMarket);
            sleep(150);
        }
        awaitCondition(() -> positionCount(runnerId, "CLOSED") == 1 && positionCount(runnerId, "OPEN") == 0,
                WAIT_TIMEOUT, "lote liquidado pela escalada", runnerId);
        awaitStable(runnerId, QUIET_WINDOW);

        List<String> sells = sellsWithOrderType(runnerId);
        assertEquals(List.of("LIMIT:CANCELED", "LIMIT:CANCELED", "MARKET:FILLED"), sells,
                "SELL da estrategia e LIMIT de liquidacao canceladas, uma unica MARKET: " + sells);
        assertEquals(0, marketSellExecutedQuantity(runnerId).compareTo(totalMatchedQuantity(runnerId)),
                "O lote inteiro deve ser casado pela venda MARKET");
        assertEquals(0, balance(portfolioId).reserved().signum());
    }

    // ---------------------------------------------------------------- helpers

    private void useLimitLiquidation(UUID runnerId, int escalationTicks) {
        runnerLifecycle.updateLimits(runnerId, new UpdateRunnerLimitsRequest(null, null, null, null, null,
                OrderType.LIMIT, null, escalationTicks));
    }

    private List<BigDecimal> sellPrices(UUID runnerId) {
        return jdbcTemplate.queryForList(
                "SELECT price FROM transactions WHERE runner_id = ? AND type = 'SELL' ORDER BY requested_at",
                BigDecimal.class, runnerId);
    }

    private SafeModeStatusDto change(UUID portfolioId, SafeModeStatus level, boolean confirm) {
        return manageSafeMode.change(portfolioId, SafeModeOrigin.MANUAL,
                new ChangeSafeModeRequest(level, "t45 e2e", confirm));
    }

    /** Publica um tick por vez ate a condicao valer, sem ultrapassar o estado de interesse. */
    private void tickUntil(BooleanSupplier condition, String what, UUID runnerId) {
        Instant deadline = Instant.now().plus(WAIT_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            publishTick(MARKET_PRICE);
            Instant tickDeadline = Instant.now().plus(Duration.ofMillis(600));
            while (Instant.now().isBefore(tickDeadline) && !condition.getAsBoolean()) {
                sleep(50);
            }
        }
        fail("Timeout esperando " + what + System.lineSeparator() + stateDump(runnerId));
    }

    private boolean hasInFlight(UUID runnerId) {
        return transactions(runnerId).stream()
                .anyMatch(tx -> List.of("PENDING", "SUBMITTED", "PARTIAL").contains(tx.status()));
    }

    private BigDecimal marketSellExecutedQuantity(UUID runnerId) {
        return jdbcTemplate.queryForObject(
                "SELECT executed_quantity FROM transactions WHERE runner_id = ? AND type = 'SELL' AND order_type = 'MARKET'",
                BigDecimal.class, runnerId);
    }

    private BigDecimal sellExecutedQuantity(UUID runnerId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(executed_quantity), 0) FROM transactions WHERE runner_id = ? AND type = 'SELL'",
                BigDecimal.class, runnerId);
    }

    private List<String> sellsWithOrderType(UUID runnerId) {
        return jdbcTemplate.query(
                "SELECT order_type, status FROM transactions WHERE runner_id = ? AND type = 'SELL' ORDER BY requested_at",
                (rs, rowNum) -> rs.getString("order_type") + ":" + rs.getString("status"),
                runnerId);
    }
}
