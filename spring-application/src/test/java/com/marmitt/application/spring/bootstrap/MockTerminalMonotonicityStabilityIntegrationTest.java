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
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;

import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MockTerminalMonotonicityStabilityIntegrationTest extends MockOrderOverrideIntegrationTestSupport {

    @RepeatedTest(value = 3, name = "reordered filled-before-partial remains stable [{currentRepetition}/{totalRepetitions}]")
    void reorderedFilledBeforePartialShouldRemainStableAcrossRepeats(RepetitionInfo repetitionInfo) {
        assertRepeatedBuyFilledBeforePartial(repetitionInfo.getCurrentRepetition());
        assertRepeatedSellFilledBeforePartial(repetitionInfo.getCurrentRepetition());
    }

    @RepeatedTest(value = 3, name = "late canceled-after-filled remains monotonic [{currentRepetition}/{totalRepetitions}]")
    void lateCanceledAfterFilledShouldRemainMonotonicAcrossRepeats(RepetitionInfo repetitionInfo) {
        assertRepeatedBuyLateCanceledAfterFilled(repetitionInfo.getCurrentRepetition());
        assertRepeatedSellLateCanceledAfterFilled(repetitionInfo.getCurrentRepetition());
    }

    private void assertRepeatedBuyFilledBeforePartial(int repetition) {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);

        BigDecimal quantity = new BigDecimal("0.00200000");
        BigDecimal price = new BigDecimal("65500.00000000");
        String clientOrderId = ClientOrderId.generate(runner.getShortCode(), TransactionType.BUY);

        Transaction pendingBuy = new Transaction(
                runner.getId(),
                clientOrderId,
                TransactionType.BUY,
                OrderType.LIMIT,
                SYMBOL,
                quantity,
                price,
                quantity.multiply(price),
                new BigDecimal("0.90"),
                SCENARIO_REORDERED_BUY_FILLED_BEFORE_PARTIAL + " repeat " + repetition,
                null, Map.of()
        );
        strategyRunnerRepository.saveTransaction(pendingBuy);
        assertNoInitialPositionOrMatch(pendingBuy.getId());

        getMockExchangeAdapter().registerOrderScenarioOverride(clientOrderId, new MockOrderScenarioOverride(
                List.of(
                        new MockOrderScenarioOverride.PlannedEvent(
                                OrderDataDto.OrderStatus.PARTIALLY_FILLED,
                                new BigDecimal("0.00100000"),
                                new BigDecimal("65505.00000000"),
                                BigDecimal.ZERO,
                                null,
                                INITIAL_CALLBACK_DELAY_MS,
                                0
                        ),
                        new MockOrderScenarioOverride.PlannedEvent(
                                OrderDataDto.OrderStatus.FILLED,
                                quantity,
                                new BigDecimal("65510.00000000"),
                                BigDecimal.ZERO,
                                null,
                                INITIAL_CALLBACK_DELAY_MS + NEAR_SIMULTANEOUS_CALLBACK_GAP_MS,
                                0
                        )
                ),
                MockOrderScenarioOverride.EventOrdering.REVERSE
        ));

        submitOrderToMock(clientOrderId, TransactionType.BUY, quantity, price);

        BuyStateSnapshot stable = awaitStableFilledState(pendingBuy.getId(), WAIT_TIMEOUT, quantity);
        OrderDataDto latePartial = awaitMockOrderStatus(clientOrderId, OrderDataDto.OrderStatus.PARTIALLY_FILLED, WAIT_TIMEOUT);

        assertEquals(OrderDataDto.OrderStatus.PARTIALLY_FILLED, latePartial.status());
        assertEquals(TransactionStatus.FILLED, stable.status());
        assertEquals(0, stable.executedQuantity().compareTo(quantity));
        assertEquals(1, stable.openRows());
        assertEquals(0, stable.positionQuantity().compareTo(quantity));
        assertEquals(0, stable.matchCount());
    }

    private void assertRepeatedSellFilledBeforePartial(int repetition) {
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
                SCENARIO_REORDERED_SELL_FILLED_BEFORE_PARTIAL + " repeat " + repetition
        );

        getMockExchangeAdapter().registerOrderScenarioOverride(
                sellSetup.sellTransaction().getClientOrderId(),
                new MockOrderScenarioOverride(
                        List.of(
                                new MockOrderScenarioOverride.PlannedEvent(
                                        OrderDataDto.OrderStatus.PARTIALLY_FILLED,
                                        new BigDecimal("0.00100000"),
                                        new BigDecimal("66090.00000000"),
                                        BigDecimal.ZERO,
                                        null,
                                        INITIAL_CALLBACK_DELAY_MS,
                                        0
                                ),
                                new MockOrderScenarioOverride.PlannedEvent(
                                        OrderDataDto.OrderStatus.FILLED,
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

        assertEquals(OrderDataDto.OrderStatus.PARTIALLY_FILLED, latePartial.status());
        assertEquals(TransactionStatus.FILLED, stable.status());
        assertEquals(1, stable.matchCount());
        assertEquals(0, stable.matchedQuantity().compareTo(quantity));
        assertEquals(0, stable.pnlRealized().compareTo(expectedPnl));
        assertEquals("CLOSED", stable.positionStatus());
        assertEquals(0, stable.positionQuantity().compareTo(BigDecimal.ZERO));
        assertEquals(0, stable.availableBalance().compareTo(INITIAL_CAPITAL.add(expectedPnl)));
        assertEquals(0, stable.reservedBalance().compareTo(BigDecimal.ZERO));
    }

    private void assertRepeatedBuyLateCanceledAfterFilled(int repetition) {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);

        BigDecimal quantity = new BigDecimal("0.00200000");
        BigDecimal price = new BigDecimal("65600.00000000");
        String clientOrderId = ClientOrderId.generate(runner.getShortCode(), TransactionType.BUY);

        Transaction pendingBuy = new Transaction(
                runner.getId(),
                clientOrderId,
                TransactionType.BUY,
                OrderType.LIMIT,
                SYMBOL,
                quantity,
                price,
                quantity.multiply(price),
                new BigDecimal("0.90"),
                SCENARIO_LATE_CANCELED_AFTER_BUY_FILLED + " repeat " + repetition,
                null, Map.of()
        );
        strategyRunnerRepository.saveTransaction(pendingBuy);
        assertNoInitialPositionOrMatch(pendingBuy.getId());

        getMockExchangeAdapter().registerOrderScenarioOverride(clientOrderId, new MockOrderScenarioOverride(
                List.of(
                        new MockOrderScenarioOverride.PlannedEvent(
                                OrderDataDto.OrderStatus.FILLED,
                                quantity,
                                price,
                                BigDecimal.ZERO,
                                null,
                                INITIAL_CALLBACK_DELAY_MS,
                                0
                        ),
                        new MockOrderScenarioOverride.PlannedEvent(
                                OrderDataDto.OrderStatus.CANCELED,
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
        OrderDataDto lateCanceled = awaitMockOrderStatus(clientOrderId, OrderDataDto.OrderStatus.CANCELED, WAIT_TIMEOUT);
        BuyStateSnapshot afterLateCancel = awaitCondition(
                WAIT_TIMEOUT,
                DEFAULT_POLL_INTERVAL_MS,
                () -> readBuyState(pendingBuy.getId()),
                snapshot -> isExpectedFilledState(snapshot, quantity),
                "Timeout waiting BUY state to remain FILLED after delayed CANCELED"
        );

        assertEquals(OrderDataDto.OrderStatus.CANCELED, lateCanceled.status());
        assertEquals(TransactionStatus.FILLED, stable.status());
        assertEquals(TransactionStatus.FILLED, afterLateCancel.status());
        assertEquals(0, afterLateCancel.executedQuantity().compareTo(quantity));
        assertEquals(0, afterLateCancel.positionQuantity().compareTo(quantity));
        assertEquals(1, afterLateCancel.openRows());
        assertEquals(0, afterLateCancel.matchCount());
    }

    private void assertRepeatedSellLateCanceledAfterFilled(int repetition) {
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
                SCENARIO_LATE_CANCELED_AFTER_SELL_FILLED + " repeat " + repetition
        );

        getMockExchangeAdapter().registerOrderScenarioOverride(
                sellSetup.sellTransaction().getClientOrderId(),
                new MockOrderScenarioOverride(
                        List.of(
                                new MockOrderScenarioOverride.PlannedEvent(
                                        OrderDataDto.OrderStatus.FILLED,
                                        quantity,
                                        sellPrice,
                                        BigDecimal.ZERO,
                                        null,
                                        INITIAL_CALLBACK_DELAY_MS,
                                        0
                                ),
                                new MockOrderScenarioOverride.PlannedEvent(
                                        OrderDataDto.OrderStatus.CANCELED,
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

        submitOrderToMock(sellSetup.sellTransaction().getClientOrderId(), TransactionType.SELL, quantity, sellPrice);

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

        assertEquals(OrderDataDto.OrderStatus.CANCELED, lateCanceled.status());
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
