package com.marmitt.application.spring.bootstrap;

import com.marmitt.core.dto.portfolio.request.ChangeSafeModeRequest;
import com.marmitt.core.enums.SafeModeOrigin;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.ports.inbound.portfolio.EvaluateDrawdownPort;
import com.marmitt.core.ports.inbound.portfolio.ManageSafeModePort;
import com.marmitt.core.ports.outbound.marketdata.LatestPricePort;
import com.marmitt.application.spring.testsupport.strategy.scenario.FilledBuyRestingSellCancelStrategy;
import com.marmitt.application.spring.testsupport.strategy.scenario.ScenarioStrategyConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T43 — monitor de drawdown contra o Postgres real: amostras persistidas, HALT automatico com
 * auditoria, reset manual abrindo nova janela e equity com lote travado marcado a mercado.
 */
class DrawdownMonitorIntegrationTest extends ScenarioStrategyE2ETestSupport {

    @Autowired
    private EvaluateDrawdownPort evaluateDrawdown;

    @Autowired
    private ManageSafeModePort manageSafeMode;

    @Autowired
    private LatestPricePort latestPrices;

    @Test
    void drawdownBeyondTenPercentPutsThePortfolioInAutomaticHaltWithAudit() {
        UUID portfolioId = createPortfolio();
        evaluateDrawdown.evaluate();
        assertTrue(snapshotCount(portfolioId) >= 1, "A avaliacao deve persistir a amostra de equity");

        setAvailable(portfolioId, "8900.00");   // perda realizada de 11%
        evaluateDrawdown.evaluate();

        assertEquals("HALT", safeModeStatus(portfolioId));
        String audit = jdbcTemplate.queryForObject(
                "SELECT origin || ':' || to_status || ':' || reason FROM safe_mode_changes WHERE portfolio_id = ?",
                String.class, portfolioId);
        assertTrue(audit.startsWith("AUTOMATIC:HALT:MAX_DRAWDOWN_EXCEEDED"), audit);
    }

    @Test
    void manualResetOpensANewWindowAndDoesNotRetrip() {
        UUID portfolioId = createPortfolio();
        evaluateDrawdown.evaluate();
        setAvailable(portfolioId, "8900.00");
        evaluateDrawdown.evaluate();
        assertEquals("HALT", safeModeStatus(portfolioId));

        manageSafeMode.change(portfolioId, SafeModeOrigin.MANUAL,
                new ChangeSafeModeRequest(SafeModeStatus.NORMAL, "perda analisada, retomando", false));
        evaluateDrawdown.evaluate();

        assertEquals("NORMAL", safeModeStatus(portfolioId),
                "O pico anterior ao reset nao pode reacionar o HALT");
    }

    @Test
    void lockedLotStillCountsInTheMarkedEquity() {
        UUID runnerId = activateScenario(
                new FilledBuyRestingSellCancelStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        UUID portfolioId = portfolioIdOf(runnerId);
        awaitLockedLot(runnerId);

        assertEquals(1, strategyRunnerRepository.findActivePositionsByPortfolioId(portfolioId).size(),
                "Lote travado pela SELL (CLOSING) continua sendo do portfolio");

        latestPrices.record(SYMBOL, MARKET_PRICE);
        evaluateDrawdown.evaluate();
        assertEquals("NORMAL", safeModeStatus(portfolioId));

        latestPrices.record(SYMBOL, new BigDecimal("1.00"));   // lote de ~65 USDT vira quase zero: < 1% do capital
        evaluateDrawdown.evaluate();
        assertEquals("NORMAL", safeModeStatus(portfolioId), "Perda flutuante abaixo do limite nao aciona");
        assertTrue(snapshotCount(portfolioId) >= 2);
    }

    // ---------------------------------------------------------------- helpers

    private void awaitLockedLot(UUID runnerId) {
        for (int i = 0; i < 40 && !hasLockedPosition(runnerId); i++) {
            publishTick(MARKET_PRICE);
            sleep(400);
        }
        assertTrue(hasLockedPosition(runnerId), "Esperado lote travado pela SELL" + System.lineSeparator()
                + stateDump(runnerId));
    }

    private void setAvailable(UUID portfolioId, String available) {
        jdbcTemplate.update("UPDATE global_balances SET available_balance = ? WHERE portfolio_id = ?",
                new BigDecimal(available), portfolioId);
    }

    private String safeModeStatus(UUID portfolioId) {
        return jdbcTemplate.queryForObject("SELECT safe_mode_status FROM portfolios WHERE id = ?",
                String.class, portfolioId);
    }

    private int snapshotCount(UUID portfolioId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM equity_snapshots WHERE portfolio_id = ?", Integer.class, portfolioId);
        return count == null ? 0 : count;
    }
}
