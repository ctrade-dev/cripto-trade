package com.marmitt.core.application.usecase.runner.processsignal;

import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.dto.strategy.StrategyOutputDto;
import com.marmitt.core.ports.outbound.metrics.SignalDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T50: limites de risco antes de materializar a ordem.
 */
class PreTradeRiskPolicyTest {

    private static final BigDecimal TICK = new BigDecimal("65000");
    private static final BigDecimal QTY = new BigDecimal("0.001");
    private static final RunnerExposureService.ExposureSnapshot FLAT =
            new RunnerExposureService.ExposureSnapshot(0, 0, BigDecimal.ZERO, null);

    private final PreTradeRiskPolicy policy = new PreTradeRiskPolicy();
    private final StrategyRunner runner = runner(1, 1);

    @Test
    void priceWithinTenPercentPassesOnBothSides() {
        assertEquals(Optional.empty(), policy.rejection(runner, buyAt("58500"), TICK, FLAT), "-10% exatos");
        assertEquals(Optional.empty(), policy.rejection(runner, sellAt("71500"), TICK, null), "+10% exatos");
        assertEquals(Optional.empty(), policy.rejection(runner, buy(), TICK, FLAT), "sem limitPrice usa o tick");
    }

    @Test
    void priceBeyondTenPercentIsRejectedOnBothSides() {
        assertEquals(Optional.of(SignalDecision.REJECTED_PRICE_DEVIATION),
                policy.rejection(runner, buyAt("6500"), TICK, FLAT), "casa decimal trocada");
        assertEquals(Optional.of(SignalDecision.REJECTED_PRICE_DEVIATION),
                policy.rejection(runner, sellAt("71500.01"), TICK, null));
        assertEquals(Optional.of(SignalDecision.REJECTED_PRICE_DEVIATION),
                policy.rejection(runner, sellAt("58499.99"), TICK, null), "SELL barata demais tambem");
    }

    @Test
    void buyIsRejectedAtTheRunnerPositionAndPendingLimits() {
        StrategyRunner roomy = runner(2, 2);

        assertEquals(Optional.of(SignalDecision.REJECTED_MAX_POSITIONS), policy.rejection(roomy, buy(), TICK,
                new RunnerExposureService.ExposureSnapshot(2, 0, BigDecimal.ZERO, null)));
        assertEquals(Optional.of(SignalDecision.REJECTED_MAX_PENDING), policy.rejection(roomy, buy(), TICK,
                new RunnerExposureService.ExposureSnapshot(1, 2, new BigDecimal("130"), null)));
        assertEquals(Optional.empty(), policy.rejection(roomy, buy(), TICK,
                new RunnerExposureService.ExposureSnapshot(1, 1, new BigDecimal("65"), null)));
    }

    @Test
    void sellIsNeverBlockedByPositionOrPendingLimits() {
        assertEquals(Optional.empty(), policy.rejection(runner, sell(), TICK, null));
    }

    private static StrategyRunner runner(int maxOpenPositions, int maxPendingOrders) {
        StrategyRunner runner = mock(StrategyRunner.class);
        when(runner.getId()).thenReturn(UUID.randomUUID());
        when(runner.getMaxOpenPositions()).thenReturn(maxOpenPositions);
        when(runner.getMaxPendingOrders()).thenReturn(maxPendingOrders);
        return runner;
    }

    private static StrategyOutputDto buy() {
        return StrategyOutputDto.buy("S", BigDecimal.ONE, QTY, "t");
    }

    private static StrategyOutputDto buyAt(String price) {
        return StrategyOutputDto.buyAt("S", BigDecimal.ONE, QTY, new BigDecimal(price), "t");
    }

    private static StrategyOutputDto sell() {
        return StrategyOutputDto.sell("S", BigDecimal.ONE, QTY, "t");
    }

    private static StrategyOutputDto sellAt(String price) {
        return StrategyOutputDto.sellAt("S", BigDecimal.ONE, QTY, new BigDecimal(price), "t");
    }
}
