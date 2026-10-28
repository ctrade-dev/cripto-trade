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

class RestingBuyCancelStrategyTest {

    private static final BigDecimal MARKET = new BigDecimal("65000");

    private final RestingBuyCancelStrategy strategy = new RestingBuyCancelStrategy();

    @Test
    void placesRestingBuyBelowMarketWhenClean() {
        StrategyOutputDto out = strategy.executeStrategy(
                input(MARKET), context(new BigDecimal("10000"), List.of(), List.of()));

        assertEquals(TradingAction.SHOULD_BUY, out.decision());
        assertTrue(out.limitPrice().compareTo(MARKET) < 0, "BUY deve descansar abaixo do mercado");
        assertEquals(0, out.quantity().compareTo(new BigDecimal("0.001")));
    }

    @Test
    void cancelsPendingBuy() {
        UUID txId = UUID.randomUUID();
        StrategyOutputDto out = strategy.executeStrategy(
                input(MARKET),
                context(new BigDecimal("10000"), List.of(), List.of(pending(TradingAction.SHOULD_BUY, txId))));

        assertEquals(TradingAction.SHOULD_CANCEL, out.decision());
        assertEquals(txId, out.targetTransactionId());
    }

    @Test
    void holdsWhenLotAlreadyOpen() {
        StrategyOutputDto out = strategy.executeStrategy(
                input(MARKET),
                context(new BigDecimal("10000"), List.of(openLot(new BigDecimal("0.001"))), List.of()));

        assertEquals(TradingAction.SHOULD_HOLD, out.decision());
    }

    @Test
    void stopsAfterMaxCyclesReached() {
        RestingBuyCancelStrategy bounded = new RestingBuyCancelStrategy(ScenarioStrategyConfig.boundedConfig(1));
        UUID runner = UUID.randomUUID();
        BigDecimal capital = new BigDecimal("10000");

        // ciclo 1: estado limpo -> abre a BUY que descansa
        assertEquals(TradingAction.SHOULD_BUY,
                bounded.executeStrategy(input(MARKET), context(runner, capital, List.of(), List.of())).decision());
        // pending BUY -> cancela (limpeza, nao consome orcamento)
        assertEquals(TradingAction.SHOULD_CANCEL,
                bounded.executeStrategy(input(MARKET),
                        context(runner, capital, List.of(), List.of(pending(TradingAction.SHOULD_BUY, UUID.randomUUID())))).decision());
        // estado limpo de novo, mas teto atingido -> HOLD (nao abre nova ordem)
        assertEquals(TradingAction.SHOULD_HOLD,
                bounded.executeStrategy(input(MARKET), context(runner, capital, List.of(), List.of())).decision());
    }
}
