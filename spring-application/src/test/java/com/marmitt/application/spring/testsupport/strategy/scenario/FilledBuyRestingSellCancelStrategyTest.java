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

class FilledBuyRestingSellCancelStrategyTest {

    private static final BigDecimal MARKET = new BigDecimal("65000");

    private final FilledBuyRestingSellCancelStrategy strategy = new FilledBuyRestingSellCancelStrategy();

    @Test
    void placesMarketableBuyWhenClean() {
        StrategyOutputDto out = strategy.executeStrategy(
                input(MARKET), context(new BigDecimal("10000"), List.of(), List.of()));

        assertEquals(TradingAction.SHOULD_BUY, out.decision());
        assertTrue(out.limitPrice().compareTo(MARKET) > 0, "BUY marketable deve estar acima do mercado");
    }

    @Test
    void holdsWhileBuyPending() {
        StrategyOutputDto out = strategy.executeStrategy(
                input(MARKET),
                context(new BigDecimal("10000"), List.of(), List.of(pending(TradingAction.SHOULD_BUY, UUID.randomUUID()))));

        assertEquals(TradingAction.SHOULD_HOLD, out.decision());
    }

    @Test
    void placesRestingSellAboveMarketWhenLotOpen() {
        BigDecimal lotQty = new BigDecimal("0.001");
        StrategyOutputDto out = strategy.executeStrategy(
                input(MARKET), context(new BigDecimal("10000"), List.of(openLot(lotQty)), List.of()));

        assertEquals(TradingAction.SHOULD_SELL, out.decision());
        assertTrue(out.limitPrice().compareTo(MARKET) > 0, "SELL deve descansar acima do mercado");
        assertEquals(0, out.quantity().compareTo(lotQty));
    }

    @Test
    void cancelsPendingSell() {
        UUID txId = UUID.randomUUID();
        StrategyOutputDto out = strategy.executeStrategy(
                input(MARKET),
                context(new BigDecimal("10000"), List.of(openLot(new BigDecimal("0.001"))),
                        List.of(pending(TradingAction.SHOULD_SELL, txId))));

        assertEquals(TradingAction.SHOULD_CANCEL, out.decision());
        assertEquals(txId, out.targetTransactionId());
    }

    @Test
    void stopsAfterMaxCyclesReached() {
        FilledBuyRestingSellCancelStrategy bounded =
                new FilledBuyRestingSellCancelStrategy(ScenarioStrategyConfig.boundedConfig(1));
        UUID runner = UUID.randomUUID();
        BigDecimal capital = new BigDecimal("10000");
        BigDecimal lotQty = new BigDecimal("0.001");

        // BUY marketable (setup, nao consome orcamento)
        assertEquals(TradingAction.SHOULD_BUY,
                bounded.executeStrategy(input(MARKET), context(runner, capital, List.of(), List.of())).decision());
        // lote aberto -> coloca SELL descansando (ciclo 1)
        assertEquals(TradingAction.SHOULD_SELL,
                bounded.executeStrategy(input(MARKET), context(runner, capital, List.of(openLot(lotQty)), List.of())).decision());
        // pending SELL -> cancela (limpeza)
        assertEquals(TradingAction.SHOULD_CANCEL,
                bounded.executeStrategy(input(MARKET),
                        context(runner, capital, List.of(openLot(lotQty)), List.of(pending(TradingAction.SHOULD_SELL, UUID.randomUUID())))).decision());
        // lote aberto, sem pending, teto atingido -> HOLD (nao recoloca SELL)
        assertEquals(TradingAction.SHOULD_HOLD,
                bounded.executeStrategy(input(MARKET), context(runner, capital, List.of(openLot(lotQty)), List.of())).decision());
    }

    @Test
    void doesNotReopenPositionAfterCapWhenRestingSellFills() {
        FilledBuyRestingSellCancelStrategy bounded =
                new FilledBuyRestingSellCancelStrategy(ScenarioStrategyConfig.boundedConfig(1));
        UUID runner = UUID.randomUUID();
        BigDecimal capital = new BigDecimal("10000");
        BigDecimal lotQty = new BigDecimal("0.001");

        // setup BUY (nao consome) + SELL descansando (consome o unico ciclo)
        assertEquals(TradingAction.SHOULD_BUY,
                bounded.executeStrategy(input(MARKET), context(runner, capital, List.of(), List.of())).decision());
        assertEquals(TradingAction.SHOULD_SELL,
                bounded.executeStrategy(input(MARKET), context(runner, capital, List.of(openLot(lotQty)), List.of())).decision());

        // a SELL que descansa preencheu e fechou o lote: estado limpo, mas teto ja atingido ->
        // HOLD (nao reabre posicao), preservando o contrato de nenhuma transacao extra apos o teto
        assertEquals(TradingAction.SHOULD_HOLD,
                bounded.executeStrategy(input(MARKET), context(runner, capital, List.of(), List.of())).decision());
    }
}
