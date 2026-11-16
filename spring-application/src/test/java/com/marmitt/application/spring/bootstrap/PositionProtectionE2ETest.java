package com.marmitt.application.spring.bootstrap;

import com.marmitt.core.dto.portfolio.request.ChangeSafeModeRequest;
import com.marmitt.core.dto.strategy.StrategyContextDto;
import com.marmitt.core.dto.strategy.StrategyInputDto;
import com.marmitt.core.dto.strategy.StrategyOutputDto;
import com.marmitt.core.enums.SafeModeOrigin;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.ports.inbound.portfolio.ManageSafeModePort;
import com.marmitt.core.ports.outbound.strategy.TradingStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * T44 — protecao do lote contra o MOCK: com a estrategia segurando a posicao (sempre HOLD), uma queda
 * alem do limite de perda do runner (default 5%) vende o lote a mercado pelo pipeline normal de SELL.
 */
class PositionProtectionE2ETest extends ScenarioStrategyE2ETestSupport {

    /** ~7.7% abaixo do preco de entrada (65000, ou ate 2% acima pela BUY marketable). */
    private static final BigDecimal CRASH_PRICE = new BigDecimal("60000.00");
    private static final BigDecimal QUANTITY = new BigDecimal("0.001");

    @Autowired
    private ManageSafeModePort manageSafeMode;

    @Test
    void alwaysHoldStrategyLotIsSoldAtMarketOnCrash() {
        UUID runnerId = activateScenario(new BuyOnceStrategy(false));
        UUID portfolioId = portfolioIdOf(runnerId);
        tickUntil(() -> positionCount(runnerId, "OPEN") == 1 && !hasInFlight(runnerId), MARKET_PRICE,
                "lote aberto", runnerId);

        driveTicks(3);
        assertEquals(0.0, signalEvaluatedCount(runnerId, "PROTECTIVE_EXIT"),
                "Dentro do limite a protecao nao age");

        tickUntil(() -> positionCount(runnerId, "CLOSED") == 1, CRASH_PRICE, "lote vendido pela protecao", runnerId);
        awaitCondition(() -> balance(portfolioId).reserved().signum() == 0, WAIT_TIMEOUT,
                "reserved zerado apos a venda", runnerId);
        awaitStable(runnerId, QUIET_WINDOW);

        assertEquals(List.of("MARKET:FILLED"), sellsWithOrderType(runnerId),
                "Uma unica venda MARKET, sem SELL da estrategia");
        assertTrue(signalEvaluatedCount(runnerId, "PROTECTIVE_EXIT") >= 1.0);
        assertEquals(0, positionCount(runnerId, "OPEN"));

        int transactionsAfter = transactionCount(runnerId);
        for (int i = 0; i < 4; i++) {
            publishTick(CRASH_PRICE);
            sleep(150);
        }
        sleep(QUIET_WINDOW.toMillis());
        assertEquals(transactionsAfter, transactionCount(runnerId), "Sem lote, a protecao nao gera novas ordens");
    }

    @Test
    void protectionStillSellsUnderSafeModeHalt() {
        UUID runnerId = activateScenario(new BuyOnceStrategy(false));
        UUID portfolioId = portfolioIdOf(runnerId);
        tickUntil(() -> positionCount(runnerId, "OPEN") == 1 && !hasInFlight(runnerId), MARKET_PRICE,
                "lote aberto", runnerId);

        manageSafeMode.change(portfolioId, SafeModeOrigin.MANUAL,
                ChangeSafeModeRequest.manual(SafeModeStatus.HALT, "t44 e2e", false));

        tickUntil(() -> positionCount(runnerId, "CLOSED") == 1, CRASH_PRICE, "lote vendido sob HALT", runnerId);
        awaitStable(runnerId, QUIET_WINDOW);
        assertEquals(List.of("MARKET:FILLED"), sellsWithOrderType(runnerId));
        assertTrue(signalEvaluatedCount(runnerId, "PROTECTIVE_EXIT") >= 1.0);
    }

    @Test
    void restingLimitSellIsCanceledBeforeTheProtectiveExitWithoutDuplicateSell() {
        UUID runnerId = activateScenario(new BuyOnceStrategy(true));
        UUID portfolioId = portfolioIdOf(runnerId);
        tickUntil(() -> transactionsOfType(runnerId, "SELL").stream().anyMatch(tx -> "SUBMITTED".equals(tx.status())),
                MARKET_PRICE, "SELL LIMIT descansando sobre o lote", runnerId);

        tickUntil(() -> positionCount(runnerId, "CLOSED") == 1, CRASH_PRICE, "lote vendido pela protecao", runnerId);
        awaitCondition(() -> balance(portfolioId).reserved().signum() == 0, WAIT_TIMEOUT,
                "reserved zerado apos a venda", runnerId);
        awaitStable(runnerId, QUIET_WINDOW);

        List<String> sells = sellsWithOrderType(runnerId);
        assertEquals(List.of("LIMIT:CANCELED", "MARKET:FILLED"), sells,
                "A SELL LIMIT da estrategia e cancelada e o lote sai numa unica venda MARKET: " + sells);
        assertEquals(0, marketSellExecutedQuantity(runnerId).compareTo(totalMatchedQuantity(runnerId)),
                "O lote inteiro e casado pela venda protetiva");
    }

    // ---------------------------------------------------------------- helpers

    /** Publica um tick por vez ao preco dado ate a condicao valer. */
    private void tickUntil(BooleanSupplier condition, BigDecimal price, String what, UUID runnerId) {
        Instant deadline = Instant.now().plus(WAIT_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            publishTick(price);
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

    private List<String> sellsWithOrderType(UUID runnerId) {
        return jdbcTemplate.query(
                "SELECT order_type, status FROM transactions WHERE runner_id = ? AND type = 'SELL' ORDER BY requested_at",
                (rs, rowNum) -> rs.getString("order_type") + ":" + rs.getString("status"),
                runnerId);
    }

    /**
     * Compra uma vez (BUY marketable) e depois so segura o lote. Com {@code restingSell}, coloca uma
     * SELL LIMIT bem acima do mercado sempre que o lote estiver livre e sem ordem pendente — uma
     * estrategia que insiste em vender, para provar a precedencia da protecao sem SELL duplicada.
     */
    private static final class BuyOnceStrategy implements TradingStrategy {

        private final UUID id = UUID.randomUUID();
        private final boolean restingSell;
        private final AtomicBoolean bought = new AtomicBoolean();
        private boolean enabled = true;

        private BuyOnceStrategy(boolean restingSell) {
            this.restingSell = restingSell;
        }

        @Override
        public StrategyOutputDto executeStrategy(StrategyInputDto input, StrategyContextDto context) {
            if (context.hasPendingOrders()) {
                return StrategyOutputDto.hold(getStrategyName(), "aguardando ordem pendente");
            }
            if (!context.hasOpenLots()) {
                if (bought.compareAndSet(false, true)) {
                    return StrategyOutputDto.buyAt(getStrategyName(), BigDecimal.ONE, QUANTITY,
                            input.currentPrice().multiply(new BigDecimal("1.02")).setScale(2, RoundingMode.HALF_UP),
                            "t44: BUY marketable unica");
                }
                return StrategyOutputDto.hold(getStrategyName(), "t44: ja comprou");
            }
            if (restingSell) {
                return StrategyOutputDto.sellAt(getStrategyName(), BigDecimal.ONE,
                        context.openLots().getFirst().availableQuantity(),
                        input.currentPrice().multiply(new BigDecimal("1.05")).setScale(2, RoundingMode.HALF_UP),
                        "t44: SELL descansando acima do mercado");
            }
            return StrategyOutputDto.hold(getStrategyName(), "t44: sempre HOLD com lote aberto");
        }

        @Override
        public UUID getStrategyId() {
            return id;
        }

        @Override
        public String getStrategyName() {
            return "T44BuyOnce";
        }

        @Override
        public String getStrategyVersion() {
            return "1.0.0";
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
