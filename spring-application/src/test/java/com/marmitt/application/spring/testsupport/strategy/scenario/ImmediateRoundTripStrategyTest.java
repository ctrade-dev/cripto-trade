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

class ImmediateRoundTripStrategyTest {

    private static final BigDecimal MARKET = new BigDecimal("65000");

    private final ImmediateRoundTripStrategy strategy = new ImmediateRoundTripStrategy();

    @Test
    void placesMarketableBuyWhenClean() {
        StrategyOutputDto out = strategy.executeStrategy(
                input(MARKET), context(new BigDecimal("10000"), List.of(), List.of()));

        assertEquals(TradingAction.SHOULD_BUY, out.decision());
        assertTrue(out.limitPrice().compareTo(MARKET) > 0, "BUY marketable deve estar acima do mercado");
    }

    @Test
    void placesMarketableSellToCloseLot() {
        BigDecimal lotQty = new BigDecimal("0.001");
        StrategyOutputDto out = strategy.executeStrategy(
                input(MARKET), context(new BigDecimal("10000"), List.of(openLot(lotQty)), List.of()));

        assertEquals(TradingAction.SHOULD_SELL, out.decision());
        assertTrue(out.limitPrice().compareTo(MARKET) < 0, "SELL marketable deve estar abaixo do mercado");
        assertEquals(0, out.quantity().compareTo(lotQty));
    }

    @Test
    void holdsWhilePending() {
        StrategyOutputDto out = strategy.executeStrategy(
                input(MARKET),
                context(new BigDecimal("10000"), List.of(), List.of(pending(TradingAction.SHOULD_BUY, UUID.randomUUID()))));

        assertEquals(TradingAction.SHOULD_HOLD, out.decision());
    }

    @Test
    void stopsAfterMaxCyclesReached() {
        ImmediateRoundTripStrategy bounded = new ImmediateRoundTripStrategy(ScenarioStrategyConfig.boundedConfig(1));
        UUID runner = UUID.randomUUID();
        BigDecimal capital = new BigDecimal("10000");

        // ciclo 1: abre (BUY marketable)
        assertEquals(TradingAction.SHOULD_BUY,
                bounded.executeStrategy(input(MARKET), context(runner, capital, List.of(), List.of())).decision());
        // lote aberto -> fecha (SELL marketable), completando o round trip
        assertEquals(TradingAction.SHOULD_SELL,
                bounded.executeStrategy(input(MARKET), context(runner, capital, List.of(openLot(new BigDecimal("0.001"))), List.of())).decision());
        // estado limpo, teto atingido -> HOLD (nao reabre)
        assertEquals(TradingAction.SHOULD_HOLD,
                bounded.executeStrategy(input(MARKET), context(runner, capital, List.of(), List.of())).decision());
    }
}
