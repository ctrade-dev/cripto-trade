package com.marmitt.application.spring.bootstrap;

import com.marmitt.core.domain.runner.RunnerStatusCause;
import com.marmitt.application.spring.testsupport.DatabaseCleaner;
import com.marmitt.core.domain.runner.ClientOrderId;
import com.marmitt.core.domain.runner.Position;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.portfolio.request.CreatePortfolioRequest;
import com.marmitt.core.dto.portfolio.response.CreatePortfolioResponse;
import com.marmitt.core.dto.runner.request.CreateRunnerRequest;
import com.marmitt.core.dto.runner.response.CreateRunnerResponse;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.PositionStatus;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.enums.TransactionType;
import com.marmitt.core.ports.inbound.portfolio.CreatePortfolioPort;
import com.marmitt.core.ports.inbound.runner.CreateRunnerPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentSellLockIntegrationTest extends AbstractIntegrationTest {

    private static final UUID SMA_STRATEGY_ID =
            UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final String SYMBOL = "BTCUSDT";
    private static final String MOCK_EXCHANGE = "MOCK";
    private static final String MARKET_DATA_EXCHANGE = "BINANCE";
    private static final BigDecimal INITIAL_CAPITAL = new BigDecimal("10000.00");
    private static final BigDecimal POSITION_QUANTITY = new BigDecimal("0.00200000");
    private static final BigDecimal HIGH_PRECISION_LOCK_QUANTITY = new BigDecimal("0.002000004");
    private static final BigDecimal NORMALIZED_HIGH_PRECISION_LOCK_QUANTITY = new BigDecimal("0.00200000");
    private static final BigDecimal POSITION_PRICE = new BigDecimal("65000.00000000");
    private static final Duration LOCK_ATTEMPT_TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private CreatePortfolioPort createPortfolioPort;

    @Autowired
    private CreateRunnerPort createRunnerPort;

    @Autowired
    private StrategyRunnerRepositoryPort strategyRunnerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        DatabaseCleaner.reset(jdbcTemplate);
    }

    @Test
    void concurrentSellLocksShouldAllowOnlyOneWinnerForSamePosition() throws Exception {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);

        Position position = createOpenPosition(runner);

        // This test isolates the database lock primitive, so both competing SELL intents
        // are pre-created before the race. The full signal pipeline is covered elsewhere.
        Transaction firstSell = newSellTransaction(runner, position.getId());
        Transaction secondSell = newSellTransaction(runner, position.getId());
        strategyRunnerRepository.saveTransaction(firstSell);
        strategyRunnerRepository.saveTransaction(secondSell);

        LockResult firstResult;
        LockResult secondResult;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch readySignal = new CountDownLatch(2);
            CountDownLatch startSignal = new CountDownLatch(1);
            Future<LockResult> firstAttempt = executor.submit(() -> attemptLockConcurrently(
                    readySignal,
                    startSignal,
                    position.getId(),
                    firstSell.getId()
            ));
            Future<LockResult> secondAttempt = executor.submit(() -> attemptLockConcurrently(
                    readySignal,
                    startSignal,
                    position.getId(),
                    secondSell.getId()
            ));

            awaitLatch(readySignal, LOCK_ATTEMPT_TIMEOUT);
            startSignal.countDown();

            firstResult = firstAttempt.get(LOCK_ATTEMPT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            secondResult = secondAttempt.get(LOCK_ATTEMPT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } finally {
            shutdownExecutor(executor);
        }

        long winners = List.of(firstResult, secondResult).stream()
                .filter(LockResult::locked)
                .count();
        assertEquals(1, winners, "Only one concurrent SELL lock can win for the same position");

        LockResult winner = firstResult.locked() ? firstResult : secondResult;
        Position lockedPosition = strategyRunnerRepository.findPositionById(position.getId())
                .orElseThrow(() -> new AssertionError("Position not found after lock: " + position.getId()));

        assertEquals(PositionStatus.CLOSING, lockedPosition.getStatus());
        assertEquals(winner.transactionId(), lockedPosition.getLockedByTransactionId());
        assertEquals(0, lockedPosition.getLockedQuantity().compareTo(POSITION_QUANTITY));
        assertNotNull(lockedPosition.getLockedAt());
        assertEquals(1, countLockedPositions(position.getId()));

        Transaction winnerTransaction = strategyRunnerRepository.findTransactionById(winner.transactionId())
                .orElseThrow(() -> new AssertionError("Winner transaction not found: " + winner.transactionId()));
        assertEquals(TransactionType.SELL, winnerTransaction.getType());
        assertEquals(TransactionStatus.PENDING, winnerTransaction.getStatus());
        assertEquals(position.getId(), winnerTransaction.getTargetLotId());

        LockResult loser = firstResult.locked() ? secondResult : firstResult;
        Transaction loserTransaction = strategyRunnerRepository.findTransactionById(loser.transactionId())
                .orElseThrow(() -> new AssertionError("Loser transaction not found: " + loser.transactionId()));
        assertEquals(TransactionStatus.PENDING, loserTransaction.getStatus());
        assertNull(loserTransaction.getRejectReason());
    }

    @Test
    void sellLockRetryForSameTransactionShouldBeIdempotent() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        Position position = createOpenPosition(runner, HIGH_PRECISION_LOCK_QUANTITY);

        Transaction sell = newSellTransaction(runner, position.getId(), HIGH_PRECISION_LOCK_QUANTITY);
        Transaction competingSell = newSellTransaction(runner, position.getId(), HIGH_PRECISION_LOCK_QUANTITY);
        strategyRunnerRepository.saveTransaction(sell);
        strategyRunnerRepository.saveTransaction(competingSell);

        boolean firstAttempt = strategyRunnerRepository.tryLockPositionForSell(
                position.getId(),
                sell.getId(),
                sell.getQuantity()
        );
        boolean retryAttempt = strategyRunnerRepository.tryLockPositionForSell(
                position.getId(),
                sell.getId(),
                sell.getQuantity()
        );
        boolean competingAttempt = strategyRunnerRepository.tryLockPositionForSell(
                position.getId(),
                competingSell.getId(),
                competingSell.getQuantity()
        );

        assertTrue(firstAttempt, "Initial SELL lock should succeed");
        assertTrue(retryAttempt, "Retry by the same SELL transaction should be idempotent");
        assertFalse(competingAttempt, "Different SELL transaction must not take an existing lock");

        Position lockedPosition = strategyRunnerRepository.findPositionById(position.getId())
                .orElseThrow(() -> new AssertionError("Position not found after lock: " + position.getId()));
        assertEquals(PositionStatus.CLOSING, lockedPosition.getStatus());
        assertEquals(sell.getId(), lockedPosition.getLockedByTransactionId());
        assertEquals(0, lockedPosition.getLockedQuantity().compareTo(NORMALIZED_HIGH_PRECISION_LOCK_QUANTITY));
        assertEquals(1, countLockedPositions(position.getId()));
    }

    @Test
    void sellLockShouldRefuseQuantityAboveTheLot() {
        // T61: defesa na persistencia — o lock nao trava mais que o lote, mesmo que a regra do core seja pulada.
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        Position position = createOpenPosition(runner, POSITION_QUANTITY);

        BigDecimal aboveTheLot = POSITION_QUANTITY.multiply(BigDecimal.TWO);
        Transaction oversell = newSellTransaction(runner, position.getId(), aboveTheLot);
        Transaction wholeLot = newSellTransaction(runner, position.getId(), POSITION_QUANTITY);
        strategyRunnerRepository.saveTransaction(oversell);
        strategyRunnerRepository.saveTransaction(wholeLot);

        assertFalse(strategyRunnerRepository.tryLockPositionForSell(position.getId(), oversell.getId(), aboveTheLot),
                "SELL above the lot must not lock it");
        assertEquals(0, countLockedPositions(position.getId()));
        assertTrue(strategyRunnerRepository.tryLockPositionForSell(position.getId(), wholeLot.getId(), POSITION_QUANTITY),
                "SELL of the whole lot still locks it");
    }

    private LockResult attemptLockConcurrently(CountDownLatch readySignal,
                                               CountDownLatch startSignal,
                                               UUID positionId,
                                               UUID transactionId) throws InterruptedException {
        readySignal.countDown();
        awaitLatch(startSignal, LOCK_ATTEMPT_TIMEOUT);
        boolean locked = strategyRunnerRepository.tryLockPositionForSell(
                positionId,
                transactionId,
                POSITION_QUANTITY
        );
        return new LockResult(transactionId, locked);
    }

    private static void awaitLatch(CountDownLatch latch, Duration timeout) throws InterruptedException {
        assertTrue(latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS),
                "Timed out waiting latch after " + timeout);
    }

    private static void shutdownExecutor(ExecutorService executor) throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(LOCK_ATTEMPT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            executor.shutdownNow();
        }
    }

    private UUID createPortfolio() {
        CreatePortfolioResponse response = createPortfolioPort.execute(
                CreatePortfolioRequest.builder()
                        .name("phase2-lock-" + UUID.randomUUID())
                        .initialCapitalAmount(INITIAL_CAPITAL)
                        .currency("USDT")
                        .build()
        );
        assertNotNull(response.portfolioId(), "Portfolio creation failed: " + response.message());
        return response.portfolioId();
    }

    private StrategyRunner createAndActivateRunner(UUID portfolioId) {
        CreateRunnerResponse response = createRunnerPort.execute(
                CreateRunnerRequest.builder()
                        .portfolioId(portfolioId)
                        .strategyId(SMA_STRATEGY_ID)
                        .symbol(SYMBOL)
                        .exchangeName(MOCK_EXCHANGE)
                        .allowedMarketDataSources(Set.of(MARKET_DATA_EXCHANGE))
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

    private Position createOpenPosition(StrategyRunner runner) {
        return createOpenPosition(runner, POSITION_QUANTITY);
    }

    private Position createOpenPosition(StrategyRunner runner, BigDecimal quantity) {
        Transaction buy = newBuyTransaction(runner);
        strategyRunnerRepository.saveTransaction(buy);

        Position position = Position.open(runner.getId(), SYMBOL, quantity, quantity.multiply(POSITION_PRICE));
        position.associateBuyTransaction(buy.getId());
        strategyRunnerRepository.savePosition(position);
        return position;
    }

    private Transaction newBuyTransaction(StrategyRunner runner) {
        return new Transaction(
                runner.getId(),
                ClientOrderId.generate(runner.getShortCode(), TransactionType.BUY),
                TransactionType.BUY,
                OrderType.LIMIT,
                SYMBOL,
                POSITION_QUANTITY,
                POSITION_PRICE,
                POSITION_QUANTITY.multiply(POSITION_PRICE),
                new BigDecimal("0.90"),
                "phase2 seed buy position",
                null
        );
    }

    private Transaction newSellTransaction(StrategyRunner runner, UUID targetLotId) {
        return newSellTransaction(runner, targetLotId, POSITION_QUANTITY);
    }

    private Transaction newSellTransaction(StrategyRunner runner, UUID targetLotId, BigDecimal quantity) {
        return new Transaction(
                runner.getId(),
                ClientOrderId.generate(runner.getShortCode(), TransactionType.SELL),
                TransactionType.SELL,
                OrderType.LIMIT,
                SYMBOL,
                quantity,
                POSITION_PRICE,
                quantity.multiply(POSITION_PRICE),
                new BigDecimal("0.90"),
                "phase2 concurrent sell lock",
                targetLotId
        );
    }

    private int countLockedPositions(UUID positionId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                          FROM positions
                         WHERE id = ?
                           AND status = 'CLOSING'
                           AND locked_by_transaction_id IS NOT NULL
                        """,
                Integer.class,
                positionId
        );
        return count == null ? 0 : count;
    }

    private record LockResult(UUID transactionId, boolean locked) {
    }
}


