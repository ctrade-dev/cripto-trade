package com.marmitt.application.spring.testsupport.strategy.scenario;

import com.marmitt.core.dto.strategy.StrategyOutputDto;
import com.marmitt.core.enums.TradingAction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.marmitt.application.spring.testsupport.strategy.scenario.ScenarioTestFixtures.context;
import static com.marmitt.application.spring.testsupport.strategy.scenario.ScenarioTestFixtures.input;
import static com.marmitt.application.spring.testsupport.strategy.scenario.ScenarioTestFixtures.openLot;
import static com.marmitt.application.spring.testsupport.strategy.scenario.ScenarioTestFixtures.pending;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverAllocationRejectStrategyTest {

    private static final BigDecimal MARKET = new BigDecimal("65000");

    private final OverAllocationRejectStrategy strategy = new OverAllocationRejectStrategy();

    @Test
    void emitsBuyWhoseCostExceedsAvailableCapital() {
        BigDecimal available = new BigDecimal("1000");
        StrategyOutputDto out = strategy.executeStrategy(
                input(MARKET), context(available, List.of(), List.of()));

        assertEquals(TradingAction.SHOULD_BUY, out.decision());
        BigDecimal estimatedCost = out.quantity().multiply(MARKET);
        assertTrue(estimatedCost.compareTo(available) > 0,
                "custo estimado (" + estimatedCost + ") deve exceder o capital disponivel (" + available + ")");
    }

    @Test
    void flooredToFixedQuantityWhenBalanceTooSmall() {
        // available=10 com price=65000 daria (10/65000)*2 ≈ 0.0003077, abaixo do fixo 0.001 e
        // vulneravel ao floor de stepSize (viraria 0). Deve usar o fixo como piso e ainda over-alocar.
        BigDecimal available = new BigDecimal("10");
        StrategyOutputDto out = strategy.executeStrategy(
                input(MARKET), context(available, List.of(), List.of()));

        assertEquals(TradingAction.SHOULD_BUY, out.decision());
        assertTrue(out.quantity().compareTo(new BigDecimal("0.001")) >= 0,
                "quantidade (" + out.quantity() + ") nao pode ficar abaixo do fixo do config (0.001)");
        BigDecimal estimatedCost = out.quantity().multiply(MARKET);
        assertTrue(estimatedCost.compareTo(available) > 0,
                "custo estimado (" + estimatedCost + ") deve exceder o capital disponivel (" + available + ")");
    }

    @Test
    void fallsBackToPositiveQuantityWhenNoCapital() {
        StrategyOutputDto out = strategy.executeStrategy(
                input(MARKET), context(BigDecimal.ZERO, List.of(), List.of()));

        assertEquals(TradingAction.SHOULD_BUY, out.decision());
        assertTrue(out.quantity().compareTo(BigDecimal.ZERO) > 0, "quantidade deve ser positiva");
    }

    @Test
    void waitsForCleanStateWithoutSpendingCycle() {
        OverAllocationRejectStrategy bounded =
                new OverAllocationRejectStrategy(ScenarioStrategyConfig.boundedConfig(1));
        UUID runner = UUID.randomUUID();
        BigDecimal available = new BigDecimal("1000");

        // ordem em transito de um estado anterior: aguarda (HOLD) sem consumir o ciclo
        assertEquals(TradingAction.SHOULD_HOLD,
                bounded.executeStrategy(input(MARKET),
                        context(runner, available, List.of(), List.of(pending(TradingAction.SHOULD_BUY, UUID.randomUUID())))).decision());
        // posicao aberta de um estado anterior: idem
        assertEquals(TradingAction.SHOULD_HOLD,
                bounded.executeStrategy(input(MARKET),
                        context(runner, available, List.of(openLot(new BigDecimal("0.001"))), List.of())).decision());

        // estado limpo: o unico ciclo ainda esta disponivel -> BUY que forca REJECTED_CAPITAL
        assertEquals(TradingAction.SHOULD_BUY,
                bounded.executeStrategy(input(MARKET), context(runner, available, List.of(), List.of())).decision());
    }

    @Test
    void stopsAfterMaxCyclesReached() {
        OverAllocationRejectStrategy bounded = new OverAllocationRejectStrategy(ScenarioStrategyConfig.boundedConfig(2));
        UUID runner = UUID.randomUUID();
        BigDecimal available = new BigDecimal("1000");

        assertEquals(TradingAction.SHOULD_BUY,
                bounded.executeStrategy(input(MARKET), context(runner, available, List.of(), List.of())).decision());
        assertEquals(TradingAction.SHOULD_BUY,
                bounded.executeStrategy(input(MARKET), context(runner, available, List.of(), List.of())).decision());
        // teto de 2 atingido -> HOLD, sem mais tentativas de reserva
        assertEquals(TradingAction.SHOULD_HOLD,
                bounded.executeStrategy(input(MARKET), context(runner, available, List.of(), List.of())).decision());
    }

    @Test
    void budgetIsScopedPerRunner() {
        // o mesmo singleton e resolvido para todo runner; o teto nao pode vazar entre eles.
        OverAllocationRejectStrategy shared =
                new OverAllocationRejectStrategy(ScenarioStrategyConfig.boundedConfig(1));
        UUID runnerA = UUID.randomUUID();
        UUID runnerB = UUID.randomUUID();
        BigDecimal available = new BigDecimal("1000");

        // runner A gasta o unico ciclo dele
        assertEquals(TradingAction.SHOULD_BUY,
                shared.executeStrategy(input(MARKET), context(runnerA, available, List.of(), List.of())).decision());
        assertEquals(TradingAction.SHOULD_HOLD,
                shared.executeStrategy(input(MARKET), context(runnerA, available, List.of(), List.of())).decision());

        // runner B ainda tem o orcamento intacto — nao herda o teto ja consumido por A
        assertEquals(TradingAction.SHOULD_BUY,
                shared.executeStrategy(input(MARKET), context(runnerB, available, List.of(), List.of())).decision());
    }
}
