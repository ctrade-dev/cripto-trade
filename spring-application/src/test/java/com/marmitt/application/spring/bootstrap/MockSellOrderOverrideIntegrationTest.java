package com.marmitt.application.spring.bootstrap;

import com.marmitt.application.spring.config.exchange.MockExchangeAdapter;
import com.marmitt.core.domain.runner.ClientOrderId;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.websocket.data.OrderDataDto;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.enums.TransactionType;
import com.marmitt.mock.config.MockOrderScenarioOverride;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
class MockSellOrderOverrideIntegrationTest extends MockOrderOverrideIntegrationTestSupport {

    @Test
    void duplicateSellFilledShouldNotDoubleApplyEconomicEffects() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        UUID runnerId = runner.getId();

        BigDecimal quantity = new BigDecimal("0.00200000");
        BigDecimal buyPrice = new BigDecimal("65000.00000000");
        BigDecimal sellPrice = new BigDecimal("66000.00000000");
        BigDecimal buyCost = quantity.multiply(buyPrice);
        BigDecimal expectedPnl = quantity.multiply(sellPrice.subtract(buyPrice));

        String buyClientOrderId = ClientOrderId.generate(runner.getShortCode(), TransactionType.BUY);
        Transaction pendingBuy = new Transaction(
                runnerId,
                buyClientOrderId,
                TransactionType.BUY,
                OrderType.LIMIT,
                SYMBOL,
                quantity,
                buyPrice,
                buyCost,
                new BigDecimal("0.90"),
                SCENARIO_DUPLICATE_SELL_FILLED + " setup buy",
                null, Map.of()
        );
        strategyRunnerRepository.saveTransaction(pendingBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, buyCost),
                "Seeded BUY must reserve capital like ProcessTradeSignalUseCase would");
        assertNoInitialPositionOrMatch(pendingBuy.getId());

        MockExchangeAdapter mockExchangeAdapter = getMockExchangeAdapter();
        mockExchangeAdapter.registerOrderScenarioOverride(buyClientOrderId, new MockOrderScenarioOverride(
                List.of(
                        new MockOrderScenarioOverride.PlannedEvent(
                                com.marmitt.core.dto.websocket.data.OrderDataDto.OrderStatus.FILLED,
                                quantity,
                                buyPrice,
                                BigDecimal.ZERO,
                                null,
                                INITIAL_CALLBACK_DELAY_MS,
                                0
                        )
                ),
                MockOrderScenarioOverride.EventOrdering.AS_IS
        ));

        submitOrderToMock(buyClientOrderId, TransactionType.BUY, quantity, buyPrice);

        BuyStateSnapshot buyState = awaitStableFilledState(pendingBuy.getId(), WAIT_TIMEOUT, quantity);
        assertEquals(0, buyState.positionQuantity().compareTo(quantity));

        PositionRow openedPosition = awaitOpenPositionByOpenedByTransactionId(pendingBuy.getId(), WAIT_TIMEOUT);
        String sellClientOrderId = ClientOrderId.generate(runner.getShortCode(), TransactionType.SELL);
        Transaction pendingSell = new Transaction(
                runnerId,
                sellClientOrderId,
                TransactionType.SELL,
                OrderType.LIMIT,
                SYMBOL,
                quantity,
                sellPrice,
                quantity.multiply(sellPrice),
                new BigDecimal("0.90"),
                SCENARIO_DUPLICATE_SELL_FILLED,
                openedPosition.id(), Map.of()
        );
        strategyRunnerRepository.saveTransaction(pendingSell);
        assertEquals(0, countMatchesByTransactionId(pendingSell.getId()));
        assertTrue(strategyRunnerRepository.tryLockPositionForSell(openedPosition.id(), pendingSell.getId(), quantity),
                "Seeded SELL must lock the target position like ProcessTradeSignalUseCase would");

        mockExchangeAdapter.registerOrderScenarioOverride(sellClientOrderId, new MockOrderScenarioOverride(
                List.of(
                        new MockOrderScenarioOverride.PlannedEvent(
                                com.marmitt.core.dto.websocket.data.OrderDataDto.OrderStatus.FILLED,
                                quantity,
                                sellPrice,
                                BigDecimal.ZERO,
                                null,
                                INITIAL_CALLBACK_DELAY_MS,
                                2
                        )
                ),
                MockOrderScenarioOverride.EventOrdering.AS_IS
        ));

        submitOrderToMock(sellClientOrderId, TransactionType.SELL, quantity, sellPrice);

        SellStateSnapshot stable = awaitStableSellState(
                portfolioId,
                pendingSell.getId(),
                openedPosition.id(),
                WAIT_TIMEOUT,
                quantity,
                expectedPnl
        );

        assertEquals(TransactionStatus.FILLED, stable.status());
        assertEquals(0, stable.executedQuantity().compareTo(quantity),
                "Duplicate SELL FILLED must not change executed quantity after first finalization");
        assertEquals(1, stable.matchCount(),
                "Duplicate SELL FILLED must persist exactly one transaction_match");
        assertEquals(0, stable.matchedQuantity().compareTo(quantity),
                "Duplicate SELL FILLED must not duplicate matched quantity");
        assertEquals(0, stable.pnlRealized().compareTo(expectedPnl),
                "Duplicate SELL FILLED must not duplicate realized PnL");
        assertEquals("CLOSED", stable.positionStatus());
        assertEquals(0, stable.positionQuantity().compareTo(BigDecimal.ZERO),
                "Duplicate SELL FILLED must not reduce position more than once");
        assertEquals(0, stable.realizedBalance().compareTo(expectedPnl),
                "Duplicate SELL FILLED must release capital and apply PnL exactly once");
        assertEquals(0, stable.availableBalance().compareTo(INITIAL_CAPITAL.add(expectedPnl)),
                "Duplicate SELL FILLED must not credit available balance twice");
        assertEquals(0, stable.reservedBalance().compareTo(BigDecimal.ZERO),
                "Duplicate SELL FILLED must release the reserved buy cost exactly once");
    }

    @Test
    void reorderedSellFilledBeforePartialShouldIgnoreLatePartial() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);

        BigDecimal quantity = new BigDecimal("0.00200000");
        BigDecimal buyPrice = new BigDecimal("65000.00000000");
        BigDecimal sellPrice = new BigDecimal("66100.00000000");
        BigDecimal expectedPnl = quantity.multiply(sellPrice.subtract(buyPrice));

        SellSetup sellSetup = createFilledBuyAndLockedSell(
                portfolioId,
                runner,
                quantity,
                buyPrice,
                sellPrice,
                SCENARIO_REORDERED_SELL_FILLED_BEFORE_PARTIAL
        );

        MockExchangeAdapter mockExchangeAdapter = getMockExchangeAdapter();
        mockExchangeAdapter.registerOrderScenarioOverride(
                sellSetup.sellTransaction().getClientOrderId(),
                new MockOrderScenarioOverride(
                        List.of(
                                new MockOrderScenarioOverride.PlannedEvent(
                                        com.marmitt.core.dto.websocket.data.OrderDataDto.OrderStatus.PARTIALLY_FILLED,
                                        new BigDecimal("0.00100000"),
                                        new BigDecimal("66090.00000000"),
                                        BigDecimal.ZERO,
                                        null,
                                        INITIAL_CALLBACK_DELAY_MS,
                                        0
                                ),
                                new MockOrderScenarioOverride.PlannedEvent(
                                        com.marmitt.core.dto.websocket.data.OrderDataDto.OrderStatus.FILLED,
                                        quantity,
                                        sellPrice,
                                        BigDecimal.ZERO,
                                        null,
                                        INITIAL_CALLBACK_DELAY_MS + NEAR_SIMULTANEOUS_CALLBACK_GAP_MS,
                                        0
                                )
                        ),
                        MockOrderScenarioOverride.EventOrdering.REVERSE
                )
        );

        submitOrderToMock(sellSetup.sellTransaction().getClientOrderId(), TransactionType.SELL, quantity, sellPrice);

        SellStateSnapshot stable = awaitStableSellState(
                portfolioId,
                sellSetup.sellTransaction().getId(),
                sellSetup.position().id(),
                WAIT_TIMEOUT,
                quantity,
                expectedPnl
        );
        OrderDataDto latePartial = awaitMockOrderStatus(
                sellSetup.sellTransaction().getClientOrderId(),
                OrderDataDto.OrderStatus.PARTIALLY_FILLED,
                WAIT_TIMEOUT
        );

        assertEquals(OrderDataDto.OrderStatus.PARTIALLY_FILLED, latePartial.status(),
                "Mock must emit the delayed SELL PARTIAL after FILLED to prove reorder coverage");
        assertEquals(TransactionStatus.FILLED, stable.status());
        assertEquals(0, stable.executedQuantity().compareTo(quantity),
                "Late SELL PARTIAL after FILLED must not reduce executed quantity");
        assertEquals(1, stable.matchCount(),
                "Late SELL PARTIAL after FILLED must not create another transaction_match");
        assertEquals(0, stable.matchedQuantity().compareTo(quantity),
                "Late SELL PARTIAL after FILLED must not drift matched quantity");
        assertEquals(0, stable.pnlRealized().compareTo(expectedPnl),
                "Late SELL PARTIAL after FILLED must not change realized PnL");
        assertEquals("CLOSED", stable.positionStatus());
        assertEquals(0, stable.positionQuantity().compareTo(BigDecimal.ZERO));
        assertEquals(0, stable.realizedBalance().compareTo(expectedPnl));
        assertEquals(0, stable.availableBalance().compareTo(INITIAL_CAPITAL.add(expectedPnl)));
        assertEquals(0, stable.reservedBalance().compareTo(BigDecimal.ZERO));
    }
}
