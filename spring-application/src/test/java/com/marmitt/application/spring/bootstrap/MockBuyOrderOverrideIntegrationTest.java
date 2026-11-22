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
class MockBuyOrderOverrideIntegrationTest extends MockOrderOverrideIntegrationTestSupport {

    @Test
    void shouldNotDoubleApplyQuantityWhenDuplicatePartialEventsOccur() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        UUID runnerId = runner.getId();

        BigDecimal quantity = new BigDecimal("0.00200000");
        BigDecimal price = new BigDecimal("65000.00000000");
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
                SCENARIO_DUPLICATE_PARTIAL,
                null, Map.of()
        );
        strategyRunnerRepository.saveTransaction(pendingBuy);
        assertNoInitialPositionOrMatch(pendingBuy.getId());

        MockExchangeAdapter mockExchangeAdapter = getMockExchangeAdapter();
        mockExchangeAdapter.registerOrderScenarioOverride(clientOrderId, new MockOrderScenarioOverride(
                List.of(
                        new MockOrderScenarioOverride.PlannedEvent(
                                com.marmitt.core.dto.websocket.data.OrderDataDto.OrderStatus.PARTIALLY_FILLED,
                                new BigDecimal("0.00100000"),
                                new BigDecimal("65010.00000000"),
                                BigDecimal.ZERO,
                                null,
                                INITIAL_CALLBACK_DELAY_MS,
                                1
                        ),
                        new MockOrderScenarioOverride.PlannedEvent(
                                com.marmitt.core.dto.websocket.data.OrderDataDto.OrderStatus.FILLED,
                                new BigDecimal("0.00200000"),
                                new BigDecimal("65020.00000000"),
                                BigDecimal.ZERO,
                                null,
                                INITIAL_CALLBACK_DELAY_MS + NEAR_SIMULTANEOUS_CALLBACK_GAP_MS,
                                0
                        )
                ),
                MockOrderScenarioOverride.EventOrdering.AS_IS
        ));

        submitOrderToMock(clientOrderId, TransactionType.BUY, quantity, price);

        Transaction filled = awaitTransactionStatus(pendingBuy.getId(), TransactionStatus.FILLED, WAIT_TIMEOUT);
        assertEquals(0, filled.getEffectiveExecutedQuantity().compareTo(quantity));

        PositionRow position = awaitOpenPositionByOpenedByTransactionId(pendingBuy.getId(), WAIT_TIMEOUT);
        assertNotNull(position);
        assertEquals(0, position.quantity().compareTo(quantity),
                "Duplicate PARTIAL must not double-apply quantity");

        Integer countMatches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transaction_matches WHERE buy_transaction_id = ? OR sell_transaction_id = ?",
                Integer.class,
                pendingBuy.getId(),
                pendingBuy.getId()
        );
        assertEquals(0, countMatches);
    }

    @Test
    void nearSimultaneousPartialAndFilledShouldConvergeWithoutQuantityDrift() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        UUID runnerId = runner.getId();

        BigDecimal quantity = new BigDecimal("0.00200000");
        BigDecimal price = new BigDecimal("65100.00000000");
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
                SCENARIO_PARTIAL_FILLED_CONVERGENCE,
                null, Map.of()
        );
        strategyRunnerRepository.saveTransaction(pendingBuy);
        assertNoInitialPositionOrMatch(pendingBuy.getId());

        MockExchangeAdapter mockExchangeAdapter = getMockExchangeAdapter();
        mockExchangeAdapter.registerOrderScenarioOverride(clientOrderId, new MockOrderScenarioOverride(
                List.of(
                        new MockOrderScenarioOverride.PlannedEvent(
                                com.marmitt.core.dto.websocket.data.OrderDataDto.OrderStatus.PARTIALLY_FILLED,
                                new BigDecimal("0.00100000"),
                                new BigDecimal("65110.00000000"),
                                BigDecimal.ZERO,
                                null,
                                INITIAL_CALLBACK_DELAY_MS,
                                0
                        ),
                        new MockOrderScenarioOverride.PlannedEvent(
                                com.marmitt.core.dto.websocket.data.OrderDataDto.OrderStatus.FILLED,
                                new BigDecimal("0.00200000"),
                                new BigDecimal("65120.00000000"),
                                BigDecimal.ZERO,
                                null,
                                INITIAL_CALLBACK_DELAY_MS + NEAR_SIMULTANEOUS_CALLBACK_GAP_MS,
                                0
                        )
                ),
                MockOrderScenarioOverride.EventOrdering.AS_IS
        ));

        submitOrderToMock(clientOrderId, TransactionType.BUY, quantity, price);

        Transaction filled = awaitTransactionStatus(pendingBuy.getId(), TransactionStatus.FILLED, WAIT_TIMEOUT);
        assertEquals(0, filled.getEffectiveExecutedQuantity().compareTo(quantity));

        PositionRow position = awaitOpenPositionByOpenedByTransactionId(pendingBuy.getId(), WAIT_TIMEOUT);
        assertNotNull(position);
        assertEquals(0, position.quantity().compareTo(quantity),
                "Near-simultaneous PARTIAL+FILLED must converge to exact filled quantity");

        Integer openRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM positions WHERE opened_by_transaction_id = ? AND status = 'OPEN'",
                Integer.class,
                pendingBuy.getId()
        );
        assertEquals(1, openRows);

        Integer countMatches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transaction_matches WHERE buy_transaction_id = ? OR sell_transaction_id = ?",
                Integer.class,
                pendingBuy.getId(),
                pendingBuy.getId()
        );
        assertEquals(0, countMatches);
    }

    @Test
    void duplicateFilledShouldNotDoubleApplyEconomicEffects() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        UUID runnerId = runner.getId();

        BigDecimal quantity = new BigDecimal("0.00200000");
        BigDecimal price = new BigDecimal("65200.00000000");
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
                SCENARIO_DUPLICATE_FILLED,
                null, Map.of()
        );
        strategyRunnerRepository.saveTransaction(pendingBuy);
        assertNoInitialPositionOrMatch(pendingBuy.getId());

        MockExchangeAdapter mockExchangeAdapter = getMockExchangeAdapter();
        mockExchangeAdapter.registerOrderScenarioOverride(clientOrderId, new MockOrderScenarioOverride(
                List.of(
                        new MockOrderScenarioOverride.PlannedEvent(
                                com.marmitt.core.dto.websocket.data.OrderDataDto.OrderStatus.FILLED,
                                new BigDecimal("0.00200000"),
                                new BigDecimal("65210.00000000"),
                                BigDecimal.ZERO,
                                null,
                                INITIAL_CALLBACK_DELAY_MS,
                                2
                        )
                ),
                MockOrderScenarioOverride.EventOrdering.AS_IS
        ));

        submitOrderToMock(clientOrderId, TransactionType.BUY, quantity, price);

        BuyStateSnapshot stable = awaitStableFilledState(
                pendingBuy.getId(),
                WAIT_TIMEOUT,
                quantity
        );

        assertEquals(0, stable.executedQuantity().compareTo(quantity),
                "Duplicate FILLED must not change executed quantity after first finalization");
        assertNotNull(stable.positionQuantity());
        assertEquals(0, stable.positionQuantity().compareTo(quantity),
                "Duplicate FILLED must not increase position quantity more than once");
        assertEquals(1, stable.openRows());
        assertEquals(0, stable.matchCount());
    }

    @Test
    void reorderedBuyFilledBeforePartialShouldIgnoreLatePartial() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        UUID runnerId = runner.getId();

        BigDecimal quantity = new BigDecimal("0.00200000");
        BigDecimal price = new BigDecimal("65500.00000000");
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
                SCENARIO_REORDERED_BUY_FILLED_BEFORE_PARTIAL,
                null, Map.of()
        );
        strategyRunnerRepository.saveTransaction(pendingBuy);
        assertNoInitialPositionOrMatch(pendingBuy.getId());

        MockExchangeAdapter mockExchangeAdapter = getMockExchangeAdapter();
        mockExchangeAdapter.registerOrderScenarioOverride(clientOrderId, new MockOrderScenarioOverride(
                List.of(
                        new MockOrderScenarioOverride.PlannedEvent(
                                com.marmitt.core.dto.websocket.data.OrderDataDto.OrderStatus.PARTIALLY_FILLED,
                                new BigDecimal("0.00100000"),
                                new BigDecimal("65505.00000000"),
                                BigDecimal.ZERO,
                                null,
                                INITIAL_CALLBACK_DELAY_MS,
                                0
                        ),
                        new MockOrderScenarioOverride.PlannedEvent(
                                com.marmitt.core.dto.websocket.data.OrderDataDto.OrderStatus.FILLED,
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
        OrderDataDto latePartial = awaitMockOrderStatus(
                clientOrderId,
                OrderDataDto.OrderStatus.PARTIALLY_FILLED,
                WAIT_TIMEOUT
        );

        assertEquals(OrderDataDto.OrderStatus.PARTIALLY_FILLED, latePartial.status(),
                "Mock must emit the delayed BUY PARTIAL after FILLED to prove reorder coverage");
        assertEquals(TransactionStatus.FILLED, stable.status());
        assertEquals(0, stable.executedQuantity().compareTo(quantity),
                "Late BUY PARTIAL after FILLED must not reduce executed quantity");
        assertEquals(1, stable.openRows(),
                "Late BUY PARTIAL after FILLED must not create another position");
        assertEquals(0, stable.positionQuantity().compareTo(quantity),
                "Late BUY PARTIAL after FILLED must not drift position quantity");
        assertEquals(0, stable.matchCount());
    }
}
