package com.marmitt.application.spring.bootstrap;

import com.marmitt.core.domain.runner.RunnerStatusCause;
import com.marmitt.application.spring.config.exchange.MockExchangeAdapter;
import com.marmitt.application.spring.testsupport.DatabaseCleaner;
import com.marmitt.core.domain.runner.ClientOrderId;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.portfolio.request.CreatePortfolioRequest;
import com.marmitt.core.dto.portfolio.response.CreatePortfolioResponse;
import com.marmitt.core.dto.runner.request.CreateRunnerRequest;
import com.marmitt.core.dto.runner.response.CreateRunnerResponse;
import com.marmitt.core.dto.websocket.data.OrderDataDto;
import com.marmitt.core.dto.websocket.request.SendOrderRequest;
import com.marmitt.core.enums.OrderSide;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.enums.TransactionType;
import com.marmitt.core.ports.inbound.portfolio.CreatePortfolioPort;
import com.marmitt.core.ports.inbound.runner.CreateRunnerPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.GlobalBalanceRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import com.marmitt.mock.config.MockOrderScenarioOverride;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class MockOrderOverrideIntegrationTestSupport extends AbstractIntegrationTest {

    protected static final UUID SMA_STRATEGY_ID =
            UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    protected static final String SYMBOL = "BTCUSDT";
    protected static final String MOCK_EXCHANGE = "MOCK";
    protected static final BigDecimal INITIAL_CAPITAL = new BigDecimal("10000.00");
    protected static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);
    protected static final long DEFAULT_POLL_INTERVAL_MS = 80L;
    protected static final long FILLED_STABILITY_POLL_INTERVAL_MS = 50L;
    protected static final long INITIAL_CALLBACK_DELAY_MS = 120L;
    protected static final long NEAR_SIMULTANEOUS_CALLBACK_GAP_MS = 20L;
    protected static final long STABILITY_WINDOW_MS = 500L;
    protected static final String SCENARIO_DUPLICATE_PARTIAL = "phase1b deterministic override";
    protected static final String SCENARIO_PARTIAL_FILLED_CONVERGENCE = "phase1b partial+filled convergence";
    protected static final String SCENARIO_DUPLICATE_FILLED = "phase1b duplicate filled idempotency";
    protected static final String SCENARIO_DUPLICATE_SELL_FILLED = "phase2 duplicate sell filled idempotency";
    protected static final String SCENARIO_REORDERED_BUY_FILLED_BEFORE_PARTIAL =
            "phase2 reordered buy filled before partial";
    protected static final String SCENARIO_REORDERED_SELL_FILLED_BEFORE_PARTIAL =
            "phase2 reordered sell filled before partial";
    protected static final String SCENARIO_LATE_CANCELED_AFTER_BUY_FILLED =
            "phase2 late canceled after buy filled";
    protected static final String SCENARIO_LATE_CANCELED_AFTER_SELL_FILLED =
            "phase2 late canceled after sell filled";
    protected static final String SCENARIO_REJECTED_FINANCIAL = "phase1b rejected financial";
    protected static final String SCENARIO_EXPIRED_FINANCIAL = "phase1b expired financial";

    @Autowired
    protected CreatePortfolioPort createPortfolioPort;

    @Autowired
    protected CreateRunnerPort createRunnerPort;

    @Autowired
    protected StrategyRunnerRepositoryPort strategyRunnerRepository;

    @Autowired
    protected GlobalBalanceRepositoryPort globalBalanceRepository;

    @Autowired
    protected ExchangeAdapterRepositoryPort exchangeAdapterRepository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    protected void cleanDatabase() {
        getMockExchangeAdapter().reset();
        DatabaseCleaner.reset(jdbcTemplate);
    }

    protected MockExchangeAdapter getMockExchangeAdapter() {
        return exchangeAdapterRepository.findAdapter(MOCK_EXCHANGE)
                .map(d -> d.streaming())
                .filter(MockExchangeAdapter.class::isInstance)
                .map(MockExchangeAdapter.class::cast)
                .orElseThrow(() -> new IllegalStateException(
                        MOCK_EXCHANGE + " adapter not found or has invalid type"));
    }

    protected UUID createPortfolio() {
        CreatePortfolioResponse response = createPortfolioPort.execute(
                CreatePortfolioRequest.builder()
                        .name("phase1b-det-" + UUID.randomUUID())
                        .initialCapitalAmount(INITIAL_CAPITAL)
                        .currency("USDT")
                        .build()
        );
        assertNotNull(response.portfolioId(), "Portfolio creation failed: " + response.message());
        return response.portfolioId();
    }

    protected StrategyRunner createAndActivateRunner(UUID portfolioId) {
        CreateRunnerResponse response = createRunnerPort.execute(
                CreateRunnerRequest.builder()
                        .portfolioId(portfolioId)
                        .strategyId(SMA_STRATEGY_ID)
                        .symbol(SYMBOL)
                        .exchangeName(MOCK_EXCHANGE)
                        .allowedMarketDataSources(Set.of("BINANCE"))
                        .build()
        );
        assertNotNull(response.runnerId(), "Runner creation failed: " + response.message());

        StrategyRunner runner = strategyRunnerRepository.findById(response.runnerId())
                .orElseThrow(() -> new IllegalStateException("Runner not found: " + response.runnerId()));
        runner.startInitializing(RunnerStatusCause.manual("test"));
        runner.activate(RunnerStatusCause.manual("test"));
        strategyRunnerRepository.save(runner);

        return runner;
    }

    protected void submitOrderToMock(String clientOrderId,
                                     TransactionType type,
                                     BigDecimal quantity,
                                     BigDecimal price) {
        OrderSide side = type == TransactionType.BUY ? OrderSide.BUY : OrderSide.SELL;
        getMockExchangeAdapter().submitOrder(new SendOrderRequest(
                MOCK_EXCHANGE,
                SYMBOL,
                quantity,
                price,
                OrderType.LIMIT,
                side,
                clientOrderId
        ));
    }

    /**
     * Waits until the mock has delivered an event with {@code expectedStatus} for the order. Reads the
     * published history, not the REST snapshot: the snapshot never leaves a terminal (T52), so a late
     * PARTIAL or CANCELED after FILLED only shows up as a delivered event.
     */
    protected OrderDataDto awaitMockOrderStatus(String clientOrderId,
                                              OrderDataDto.OrderStatus expectedStatus,
                                              Duration timeout) {
        return awaitCondition(
                timeout,
                DEFAULT_POLL_INTERVAL_MS,
                () -> getMockExchangeAdapter().publishedOrderEvents(clientOrderId).stream()
                        .filter(order -> order.status() == expectedStatus)
                        .findFirst()
                        .orElse(null),
                order -> order != null,
                "Timeout waiting mock emitted order status " + expectedStatus
                        + " for clientOrderId=" + clientOrderId
        );
    }

    protected SellSetup createFilledBuyAndLockedSell(UUID portfolioId,
                                                   StrategyRunner runner,
                                                   BigDecimal quantity,
                                                   BigDecimal buyPrice,
                                                   BigDecimal sellPrice,
                                                   String reason) {
        BigDecimal buyCost = quantity.multiply(buyPrice);
        String buyClientOrderId = ClientOrderId.generate(runner.getShortCode(), TransactionType.BUY);
        Transaction pendingBuy = new Transaction(
                runner.getId(),
                buyClientOrderId,
                TransactionType.BUY,
                OrderType.LIMIT,
                SYMBOL,
                quantity,
                buyPrice,
                buyCost,
                new BigDecimal("0.90"),
                reason + " setup buy",
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
                runner.getId(),
                sellClientOrderId,
                TransactionType.SELL,
                OrderType.LIMIT,
                SYMBOL,
                quantity,
                sellPrice,
                quantity.multiply(sellPrice),
                new BigDecimal("0.90"),
                reason,
                openedPosition.id(), Map.of()
        );
        strategyRunnerRepository.saveTransaction(pendingSell);
        assertEquals(0, countMatchesByTransactionId(pendingSell.getId()));
        assertTrue(strategyRunnerRepository.tryLockPositionForSell(openedPosition.id(), pendingSell.getId(), quantity),
                "Seeded SELL must lock the target position like ProcessTradeSignalUseCase would");

        return new SellSetup(pendingBuy, pendingSell, openedPosition);
    }

    protected Transaction awaitTransactionStatus(UUID transactionId,
                                               TransactionStatus expectedStatus,
                                               Duration timeout) {
        return awaitCondition(
                timeout,
                DEFAULT_POLL_INTERVAL_MS,
                () -> strategyRunnerRepository.findTransactionById(transactionId).orElse(null),
                tx -> tx != null && tx.getStatus() == expectedStatus,
                "Timeout waiting transaction status " + expectedStatus + " for transactionId=" + transactionId
        );
    }

    protected PositionRow awaitOpenPositionByOpenedByTransactionId(UUID openedByTransactionId, Duration timeout) {
        return awaitCondition(
                timeout,
                DEFAULT_POLL_INTERVAL_MS,
                () -> findOpenPositionByOpenedByTransactionId(openedByTransactionId),
                position -> position != null,
                "Timeout waiting OPEN position for openedByTransactionId=" + openedByTransactionId
        );
    }

    protected PositionRow findOpenPositionByOpenedByTransactionId(UUID openedByTransactionId) {
        List<PositionRow> rows = jdbcTemplate.query(
                """
                        SELECT id, quantity
                          FROM positions
                         WHERE opened_by_transaction_id = ?
                           AND status = 'OPEN'
                         LIMIT 1
                        """,
                (rs, rowNum) -> new PositionRow(
                        UUID.fromString(rs.getString("id")),
                        rs.getBigDecimal("quantity")
                ),
                openedByTransactionId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    protected int countOpenPositionsByOpenedByTransactionId(UUID openedByTransactionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM positions WHERE opened_by_transaction_id = ? AND status = 'OPEN'",
                Integer.class,
                openedByTransactionId
        );
        return count == null ? 0 : count;
    }

    protected int countMatchesByTransactionId(UUID transactionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transaction_matches WHERE buy_transaction_id = ? OR sell_transaction_id = ?",
                Integer.class,
                transactionId,
                transactionId
        );
        return count == null ? 0 : count;
    }

    protected BalanceRow readBalance(UUID portfolioId) {
        List<BalanceRow> rows = jdbcTemplate.query(
                """
                        SELECT available_balance, reserved_balance
                              , realized_balance
                          FROM global_balances
                         WHERE portfolio_id = ?
                        """,
                (rs, rowNum) -> new BalanceRow(
                        rs.getBigDecimal("available_balance"),
                        rs.getBigDecimal("reserved_balance"),
                        rs.getBigDecimal("realized_balance")
                ),
                portfolioId
        );
        if (rows.isEmpty()) {
            throw new IllegalStateException("Balance not found for portfolioId=" + portfolioId);
        }
        return rows.getFirst();
    }

    protected void awaitBalanceState(UUID portfolioId,
                                   BigDecimal expectedAvailable,
                                   BigDecimal expectedReserved,
                                   Duration timeout) {
        awaitCondition(
                timeout,
                DEFAULT_POLL_INTERVAL_MS,
                () -> readBalance(portfolioId),
                balance -> balance.available().compareTo(expectedAvailable) == 0
                        && balance.reserved().compareTo(expectedReserved) == 0,
                "Timeout waiting balance state for portfolioId=" + portfolioId
                        + " expectedAvailable=" + expectedAvailable
                        + " expectedReserved=" + expectedReserved
        );
    }

    protected BuyStateSnapshot awaitStableFilledState(UUID openedByTransactionId,
                                                    Duration timeout,
                                                    BigDecimal expectedQuantity) {
        Instant deadline = Instant.now().plus(timeout);
        BuyStateSnapshot converged = null;
        Instant convergedAt = null;
        while (Instant.now().isBefore(deadline)) {
            BuyStateSnapshot current = readBuyState(openedByTransactionId);
            if (converged == null) {
                if (isExpectedFilledState(current, expectedQuantity)) {
                    converged = current;
                    convergedAt = Instant.now();
                }
            } else if (!isExpectedFilledState(current, expectedQuantity)) {
                throw new AssertionError("Filled buy state drift detected after convergence for openedByTransactionId="
                        + openedByTransactionId + " current=" + current);
            } else if (Duration.between(convergedAt, Instant.now()).toMillis() >= STABILITY_WINDOW_MS) {
                return converged;
            }
            sleep(FILLED_STABILITY_POLL_INTERVAL_MS);
        }
        if (converged == null) {
            throw new AssertionError("Timeout waiting FILLED buy convergence for openedByTransactionId="
                    + openedByTransactionId);
        }
        return converged;
    }

    protected SellStateSnapshot awaitStableSellState(UUID portfolioId,
                                                   UUID sellTransactionId,
                                                   UUID positionId,
                                                   Duration timeout,
                                                   BigDecimal expectedQuantity,
                                                   BigDecimal expectedPnl) {
        Instant deadline = Instant.now().plus(timeout);
        SellStateSnapshot converged = null;
        Instant convergedAt = null;
        while (Instant.now().isBefore(deadline)) {
            SellStateSnapshot current = readSellState(portfolioId, sellTransactionId, positionId);
            if (converged == null) {
                if (isExpectedSellFilledState(current, expectedQuantity, expectedPnl)) {
                    converged = current;
                    convergedAt = Instant.now();
                }
            } else if (!isExpectedSellFilledState(current, expectedQuantity, expectedPnl)) {
                throw new AssertionError("Filled sell state drift detected after convergence for sellTransactionId="
                        + sellTransactionId + " current=" + current);
            } else if (Duration.between(convergedAt, Instant.now()).toMillis() >= STABILITY_WINDOW_MS) {
                return converged;
            }
            sleep(FILLED_STABILITY_POLL_INTERVAL_MS);
        }
        if (converged == null) {
            throw new AssertionError("Timeout waiting FILLED sell convergence for sellTransactionId="
                    + sellTransactionId);
        }
        return converged;
    }

    protected BuyStateSnapshot readBuyState(UUID openedByTransactionId) {
        Transaction tx = strategyRunnerRepository.findTransactionById(openedByTransactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + openedByTransactionId));
        PositionRow position = findOpenPositionByOpenedByTransactionId(openedByTransactionId);
        Integer openRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM positions WHERE opened_by_transaction_id = ? AND status = 'OPEN'",
                Integer.class,
                openedByTransactionId
        );
        Integer matchCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transaction_matches WHERE buy_transaction_id = ? OR sell_transaction_id = ?",
                Integer.class,
                openedByTransactionId,
                openedByTransactionId
        );
        return new BuyStateSnapshot(
                tx.getStatus(),
                tx.getEffectiveExecutedQuantity(),
                tx.getVersion(),
                position == null ? null : position.quantity(),
                openRows == null ? 0 : openRows,
                matchCount == null ? 0 : matchCount
        );
    }

    protected SellStateSnapshot readSellState(UUID portfolioId, UUID sellTransactionId, UUID positionId) {
        Transaction tx = strategyRunnerRepository.findTransactionById(sellTransactionId)
                .orElseThrow(() -> new IllegalStateException("Transaction not found: " + sellTransactionId));
        PositionLifecycleRow position = findPositionById(positionId);
        MatchAggregateRow matches = readMatchAggregateBySellTransactionId(sellTransactionId);
        BalanceRow balance = readBalance(portfolioId);
        return new SellStateSnapshot(
                tx.getStatus(),
                tx.getEffectiveExecutedQuantity(),
                tx.getVersion(),
                position.status(),
                position.quantity(),
                position.lockedByTransactionId(),
                matches.matchCount(),
                matches.matchedQuantity(),
                matches.pnlRealized(),
                balance.available(),
                balance.reserved(),
                balance.realized()
        );
    }

    protected PositionLifecycleRow findPositionById(UUID positionId) {
        List<PositionLifecycleRow> rows = jdbcTemplate.query(
                """
                        SELECT status, quantity, locked_by_transaction_id
                          FROM positions
                         WHERE id = ?
                        """,
                (rs, rowNum) -> new PositionLifecycleRow(
                        rs.getString("status"),
                        rs.getBigDecimal("quantity"),
                        rs.getObject("locked_by_transaction_id", UUID.class)
                ),
                positionId
        );
        if (rows.isEmpty()) {
            throw new IllegalStateException("Position not found: " + positionId);
        }
        return rows.getFirst();
    }

    protected MatchAggregateRow readMatchAggregateBySellTransactionId(UUID sellTransactionId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*) AS match_count,
                               COALESCE(SUM(matched_quantity), 0) AS matched_quantity,
                               COALESCE(SUM(pnl_realized), 0) AS pnl_realized
                          FROM transaction_matches
                         WHERE sell_transaction_id = ?
                        """,
                (rs, rowNum) -> new MatchAggregateRow(
                        rs.getInt("match_count"),
                        rs.getBigDecimal("matched_quantity"),
                        rs.getBigDecimal("pnl_realized")
                ),
                sellTransactionId
        );
    }

    protected void assertNoInitialPositionOrMatch(UUID transactionId) {
        assertEquals(0, countOpenPositionsByOpenedByTransactionId(transactionId),
                "Initial state must not have OPEN position for transactionId=" + transactionId);
        assertEquals(0, countMatchesByTransactionId(transactionId),
                "Initial state must not have transaction_matches for transactionId=" + transactionId);
    }

    protected static boolean isExpectedFilledState(BuyStateSnapshot snapshot, BigDecimal expectedQuantity) {
        return snapshot.status() == TransactionStatus.FILLED
                && isSameValue(snapshot.executedQuantity(), expectedQuantity)
                && isSameValue(snapshot.positionQuantity(), expectedQuantity)
                && snapshot.openRows() == 1
                && snapshot.matchCount() == 0;
    }

    protected static boolean isExpectedSellFilledState(SellStateSnapshot snapshot,
                                                     BigDecimal expectedQuantity,
                                                     BigDecimal expectedPnl) {
        return snapshot.status() == TransactionStatus.FILLED
                && isSameValue(snapshot.executedQuantity(), expectedQuantity)
                && "CLOSED".equals(snapshot.positionStatus())
                && isSameValue(snapshot.positionQuantity(), BigDecimal.ZERO)
                && snapshot.lockedByTransactionId() == null
                && snapshot.matchCount() == 1
                && isSameValue(snapshot.matchedQuantity(), expectedQuantity)
                && isSameValue(snapshot.pnlRealized(), expectedPnl)
                && isSameValue(snapshot.reservedBalance(), BigDecimal.ZERO)
                && isSameValue(snapshot.realizedBalance(), expectedPnl)
                && isSameValue(snapshot.availableBalance(), INITIAL_CAPITAL.add(expectedPnl));
    }

    protected static boolean isSameValue(BigDecimal left, BigDecimal right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.compareTo(right) == 0;
    }

    protected <T> T awaitCondition(Duration timeout,
                                 long pollIntervalMs,
                                 Supplier<T> stateSupplier,
                                 Predicate<T> isSatisfied,
                                 String timeoutMessage) {
        Instant deadline = Instant.now().plus(timeout);
        T last = null;
        while (Instant.now().isBefore(deadline)) {
            last = stateSupplier.get();
            if (isSatisfied.test(last)) {
                return last;
            }
            sleep(pollIntervalMs);
        }
        throw new AssertionError(timeoutMessage + " last=" + last);
    }

    protected static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting async processing", e);
        }
    }

    protected record PositionRow(UUID id, BigDecimal quantity) {
    }

    protected record PositionLifecycleRow(String status,
                                        BigDecimal quantity,
                                        UUID lockedByTransactionId) {
    }

    protected record MatchAggregateRow(int matchCount,
                                     BigDecimal matchedQuantity,
                                     BigDecimal pnlRealized) {
    }

    protected record SellSetup(Transaction buyTransaction,
                             Transaction sellTransaction,
                             PositionRow position) {
    }

    protected record BuyStateSnapshot(TransactionStatus status,
                                    BigDecimal executedQuantity,
                                    Long version,
                                    BigDecimal positionQuantity,
                                    int openRows,
                                    int matchCount) {
    }

    protected record SellStateSnapshot(TransactionStatus status,
                                     BigDecimal executedQuantity,
                                     Long version,
                                     String positionStatus,
                                     BigDecimal positionQuantity,
                                     UUID lockedByTransactionId,
                                     int matchCount,
                                     BigDecimal matchedQuantity,
                                     BigDecimal pnlRealized,
                                     BigDecimal availableBalance,
                                     BigDecimal reservedBalance,
                                     BigDecimal realizedBalance) {
    }

    protected record BalanceRow(BigDecimal available, BigDecimal reserved, BigDecimal realized) {
    }
}


