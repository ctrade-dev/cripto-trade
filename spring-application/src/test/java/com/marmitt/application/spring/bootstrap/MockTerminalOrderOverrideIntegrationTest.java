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
class MockTerminalOrderOverrideIntegrationTest extends MockOrderOverrideIntegrationTestSupport {

    @Test
    void lateCanceledAfterBuyFilledShouldNotRegressFilledState() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        UUID runnerId = runner.getId();

        BigDecimal quantity = new BigDecimal("0.00200000");
        BigDecimal price = new BigDecimal("65600.00000000");
        String clientOrderId = ClientOrderId.generate(runner.getShortCode(), TransactionType.BUY);

        Transaction pendingBuy = new Transaction(
                runnerId,
                clientOrderId,
                TransactionType.BUY,
                OrderType.LIMIT,
                SYMBOL,
                quantity,
                price,
                quantity.multiply(price),
                new BigDecimal("0.90"),
                SCENARIO_LATE_CANCELED_AFTER_BUY_FILLED,
                null, Map.of()
        );
        strategyRunnerRepository.saveTransaction(pendingBuy);
        assertNoInitialPositionOrMatch(pendingBuy.getId());

        MockExchangeAdapter mockExchangeAdapter = getMockExchangeAdapter();
        mockExchangeAdapter.registerOrderScenarioOverride(clientOrderId, new MockOrderScenarioOverride(
                List.of(
                        new MockOrderScenarioOverride.PlannedEvent(
                                com.marmitt.core.dto.websocket.data.OrderDataDto.OrderStatus.FILLED,
                                quantity,
                                price,
                                BigDecimal.ZERO,
                                null,
                                INITIAL_CALLBACK_DELAY_MS,
                                0
                        ),
                        new MockOrderScenarioOverride.PlannedEvent(
                                com.marmitt.core.dto.websocket.data.OrderDataDto.OrderStatus.CANCELED,
                                quantity,
                                price,
                                BigDecimal.ZERO,
                                null,
                                INITIAL_CALLBACK_DELAY_MS + NEAR_SIMULTANEOUS_CALLBACK_GAP_MS,
                                0
                        )
                ),
                MockOrderScenarioOverride.EventOrdering.AS_IS
        ));

        submitOrderToMock(clientOrderId, TransactionType.BUY, quantity, price);

        BuyStateSnapshot stable = awaitStableFilledState(pendingBuy.getId(), WAIT_TIMEOUT, quantity);
        OrderDataDto lateCanceled = awaitMockOrderStatus(
                clientOrderId,
                OrderDataDto.OrderStatus.CANCELED,
                WAIT_TIMEOUT
        );
        BuyStateSnapshot afterLateCancel = awaitCondition(
                WAIT_TIMEOUT,
                DEFAULT_POLL_INTERVAL_MS,
                () -> readBuyState(pendingBuy.getId()),
                snapshot -> isExpectedFilledState(snapshot, quantity),
                "Timeout waiting BUY state to remain FILLED after delayed CANCELED"
        );

        assertEquals(OrderDataDto.OrderStatus.CANCELED, lateCanceled.status(),
                "Mock must emit delayed BUY CANCELED after FILLED to prove terminal reorder coverage");
        assertEquals(TransactionStatus.FILLED, stable.status());
        assertEquals(TransactionStatus.FILLED, afterLateCancel.status());
        assertEquals(0, afterLateCancel.executedQuantity().compareTo(quantity));
        assertEquals(0, afterLateCancel.positionQuantity().compareTo(quantity));
        assertEquals(1, afterLateCancel.openRows());
        assertEquals(0, afterLateCancel.matchCount());
    }

    @Test
    void rejectedBuyShouldReleaseReservedBalanceWithoutCreatingPosition() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        UUID runnerId = runner.getId();

        BigDecimal quantity = new BigDecimal("0.00200000");
        BigDecimal price = new BigDecimal("65300.00000000");
        String clientOrderId = ClientOrderId.generate(runner.getShortCode(), TransactionType.BUY);

        Transaction pendingBuy = new Transaction(
                runnerId,
                clientOrderId,
                TransactionType.BUY,
                OrderType.LIMIT,
                SYMBOL,
                quantity,
                price,
                quantity.multiply(price),
                new BigDecimal("0.90"),
                SCENARIO_REJECTED_FINANCIAL,
                null, Map.of()
        );
        strategyRunnerRepository.saveTransaction(pendingBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, pendingBuy.getTotal()));
        assertNoInitialPositionOrMatch(pendingBuy.getId());

        MockExchangeAdapter mockExchangeAdapter = getMockExchangeAdapter();
        mockExchangeAdapter.registerOrderScenarioOverride(clientOrderId, new MockOrderScenarioOverride(
                List.of(
                        new MockOrderScenarioOverride.PlannedEvent(
                                com.marmitt.core.dto.websocket.data.OrderDataDto.OrderStatus.REJECTED,
                                BigDecimal.ZERO,
                                price,
                                BigDecimal.ZERO,
                                "MOCK_REJECT_TEST",
                                INITIAL_CALLBACK_DELAY_MS,
                                0
                        )
                ),
                MockOrderScenarioOverride.EventOrdering.AS_IS
        ));

        submitOrderToMock(clientOrderId, TransactionType.BUY, quantity, price);

        Transaction rejected = awaitTransactionStatus(pendingBuy.getId(), TransactionStatus.REJECTED, WAIT_TIMEOUT);
        assertEquals("MOCK_REJECT_TEST", rejected.getRejectReason());

        awaitBalanceState(portfolioId, INITIAL_CAPITAL, BigDecimal.ZERO, WAIT_TIMEOUT);
        assertEquals(0, countOpenPositionsByOpenedByTransactionId(pendingBuy.getId()));
        assertEquals(0, countMatchesByTransactionId(pendingBuy.getId()));
    }

    @Test
    void expiredBuyShouldReleaseReservedBalanceWithoutCreatingPosition() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        UUID runnerId = runner.getId();

        BigDecimal quantity = new BigDecimal("0.00200000");
        BigDecimal price = new BigDecimal("65400.00000000");
        String clientOrderId = ClientOrderId.generate(runner.getShortCode(), TransactionType.BUY);

        Transaction pendingBuy = new Transaction(
                runnerId,
                clientOrderId,
                TransactionType.BUY,
                OrderType.LIMIT,
                SYMBOL,
                quantity,
                price,
                quantity.multiply(price),
                new BigDecimal("0.90"),
                SCENARIO_EXPIRED_FINANCIAL,
                null, Map.of()
        );
        strategyRunnerRepository.saveTransaction(pendingBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, pendingBuy.getTotal()));
        assertNoInitialPositionOrMatch(pendingBuy.getId());

        MockExchangeAdapter mockExchangeAdapter = getMockExchangeAdapter();
        mockExchangeAdapter.registerOrderScenarioOverride(clientOrderId, new MockOrderScenarioOverride(
                List.of(
                        new MockOrderScenarioOverride.PlannedEvent(
                                com.marmitt.core.dto.websocket.data.OrderDataDto.OrderStatus.EXPIRED,
                                BigDecimal.ZERO,
                                price,
                                BigDecimal.ZERO,
                                null,
                                INITIAL_CALLBACK_DELAY_MS,
                                0
                        )
                ),
                MockOrderScenarioOverride.EventOrdering.AS_IS
        ));

        submitOrderToMock(clientOrderId, TransactionType.BUY, quantity, price);

        Transaction expired = awaitTransactionStatus(pendingBuy.getId(), TransactionStatus.EXPIRED, WAIT_TIMEOUT);
        assertEquals(0, expired.getEffectiveExecutedQuantity().compareTo(BigDecimal.ZERO));

        awaitBalanceState(portfolioId, INITIAL_CAPITAL, BigDecimal.ZERO, WAIT_TIMEOUT);
        assertEquals(0, countOpenPositionsByOpenedByTransactionId(pendingBuy.getId()));
        assertEquals(0, countMatchesByTransactionId(pendingBuy.getId()));
    }

    @Test
    void lateCanceledAfterSellFilledShouldNotRegressClosedState() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);

        BigDecimal quantity = new BigDecimal("0.00200000");
        BigDecimal buyPrice = new BigDecimal("65000.00000000");
        BigDecimal sellPrice = new BigDecimal("66200.00000000");
        BigDecimal expectedPnl = quantity.multiply(sellPrice.subtract(buyPrice));

        SellSetup sellSetup = createFilledBuyAndLockedSell(
                portfolioId,
                runner,
                quantity,
                buyPrice,
                sellPrice,
                SCENARIO_LATE_CANCELED_AFTER_SELL_FILLED
        );

        MockExchangeAdapter mockExchangeAdapter = getMockExchangeAdapter();
        mockExchangeAdapter.registerOrderScenarioOverride(
                sellSetup.sellTransaction().getClientOrderId(),
                new MockOrderScenarioOverride(
                        List.of(
                                new MockOrderScenarioOverride.PlannedEvent(
                                        com.marmitt.core.dto.websocket.data.OrderDataDto.OrderStatus.FILLED,
                                        quantity,
                                        sellPrice,
                                        BigDecimal.ZERO,
                                        null,
                                        INITIAL_CALLBACK_DELAY_MS,
                                        0
                                ),
                                new MockOrderScenarioOverride.PlannedEvent(
                                        com.marmitt.core.dto.websocket.data.OrderDataDto.OrderStatus.CANCELED,
                                        quantity,
                                        sellPrice,
                                        BigDecimal.ZERO,
                                        null,
                                        INITIAL_CALLBACK_DELAY_MS + NEAR_SIMULTANEOUS_CALLBACK_GAP_MS,
                                        0
                                )
                        ),
                        MockOrderScenarioOverride.EventOrdering.AS_IS
                )
        );

        submitOrderToMock(
                sellSetup.sellTransaction().getClientOrderId(),
                TransactionType.SELL,
                quantity,
                sellPrice
        );

        SellStateSnapshot stable = awaitStableSellState(
                portfolioId,
                sellSetup.sellTransaction().getId(),
                sellSetup.position().id(),
                WAIT_TIMEOUT,
                quantity,
                expectedPnl
        );
        OrderDataDto lateCanceled = awaitMockOrderStatus(
                sellSetup.sellTransaction().getClientOrderId(),
                OrderDataDto.OrderStatus.CANCELED,
                WAIT_TIMEOUT
        );
        SellStateSnapshot afterLateCancel = awaitCondition(
                WAIT_TIMEOUT,
                DEFAULT_POLL_INTERVAL_MS,
                () -> readSellState(portfolioId, sellSetup.sellTransaction().getId(), sellSetup.position().id()),
                snapshot -> isExpectedSellFilledState(snapshot, quantity, expectedPnl),
                "Timeout waiting SELL state to remain FILLED after delayed CANCELED"
        );

        assertEquals(OrderDataDto.OrderStatus.CANCELED, lateCanceled.status(),
                "Mock must emit delayed SELL CANCELED after FILLED to prove terminal reorder coverage");
        assertEquals(TransactionStatus.FILLED, stable.status());
        assertEquals(TransactionStatus.FILLED, afterLateCancel.status());
        assertEquals(1, afterLateCancel.matchCount());
        assertEquals(0, afterLateCancel.matchedQuantity().compareTo(quantity));
        assertEquals(0, afterLateCancel.pnlRealized().compareTo(expectedPnl));
        assertEquals("CLOSED", afterLateCancel.positionStatus());
        assertEquals(0, afterLateCancel.positionQuantity().compareTo(BigDecimal.ZERO));
        assertEquals(0, afterLateCancel.availableBalance().compareTo(INITIAL_CAPITAL.add(expectedPnl)));
        assertEquals(0, afterLateCancel.reservedBalance().compareTo(BigDecimal.ZERO));
        assertEquals(0, afterLateCancel.realizedBalance().compareTo(expectedPnl));
    }
}
