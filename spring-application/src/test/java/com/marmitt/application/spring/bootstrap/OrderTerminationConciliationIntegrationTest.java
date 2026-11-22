package com.marmitt.application.spring.bootstrap;

import com.marmitt.application.spring.testsupport.DatabaseCleaner;
import com.marmitt.core.domain.Symbol;
import com.marmitt.core.domain.portfolio.GlobalBalance;
import com.marmitt.core.domain.runner.ClientOrderId;
import com.marmitt.core.domain.runner.Position;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.portfolio.request.CreatePortfolioRequest;
import com.marmitt.core.dto.portfolio.response.CreatePortfolioResponse;
import com.marmitt.core.dto.runner.request.CreateRunnerRequest;
import com.marmitt.core.dto.runner.response.CreateRunnerResponse;
import com.marmitt.core.dto.websocket.data.OrderDataDto;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.PositionStatus;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.enums.TransactionType;
import com.marmitt.core.ports.inbound.portfolio.CreatePortfolioPort;
import com.marmitt.core.ports.inbound.runner.CreateRunnerPort;
import com.marmitt.core.ports.inbound.runner.OrderConciliationPort;
import com.marmitt.core.ports.outbound.repository.GlobalBalanceRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderTerminationConciliationIntegrationTest extends AbstractIntegrationTest {

    private static final UUID SMA_STRATEGY_ID =
            UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final String SYMBOL = "BTCUSDT";
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(8);
    private static final BigDecimal INITIAL_CAPITAL = new BigDecimal("1000.00000000");

    @Autowired
    private CreatePortfolioPort createPortfolioPort;

    @Autowired
    private CreateRunnerPort createRunnerPort;

    @Autowired
    private OrderConciliationPort orderConciliationPort;

    @Autowired
    private StrategyRunnerRepositoryPort strategyRunnerRepository;

    @Autowired
    private GlobalBalanceRepositoryPort globalBalanceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        DatabaseCleaner.reset(jdbcTemplate);
    }

    @Test
    void rejectedBuyShouldReleaseFullReservedBalance() {
        UUID portfolioId = createPortfolio();
        UUID runnerId = createRunner(portfolioId);
        BigDecimal reserveAmount = new BigDecimal("120.00000000");

        Transaction pendingBuy = newTransaction(
                runnerId,
                TransactionType.BUY,
                new BigDecimal("0.00200000"),
                new BigDecimal("60000.00000000"),
                reserveAmount,
                null
        );
        strategyRunnerRepository.saveTransaction(pendingBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, reserveAmount));

        orderConciliationPort.execute(orderData(
                pendingBuy,
                OrderDataDto.OrderStatus.REJECTED,
                "MOCK_REJECTED",
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ));

        Transaction rejected = awaitTransactionStatus(pendingBuy.getId(), TransactionStatus.REJECTED, WAIT_TIMEOUT);
        assertEquals("MOCK_REJECTED", rejected.getRejectReason());

        GlobalBalance balance = awaitBalance(portfolioId, INITIAL_CAPITAL, BigDecimal.ZERO, WAIT_TIMEOUT);
        assertNotNull(balance);
    }

    @Test
    void expiredSubmittedBuyShouldReleaseFullReservedBalance() {
        UUID portfolioId = createPortfolio();
        UUID runnerId = createRunner(portfolioId);
        BigDecimal reserveAmount = new BigDecimal("80.00000000");

        Transaction submittedBuy = newTransaction(
                runnerId,
                TransactionType.BUY,
                new BigDecimal("0.00100000"),
                new BigDecimal("80000.00000000"),
                reserveAmount,
                null
        );
        submittedBuy.submit("EX_SUBMITTED_BUY");
        strategyRunnerRepository.saveTransaction(submittedBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, reserveAmount));

        orderConciliationPort.execute(orderData(
                submittedBuy,
                OrderDataDto.OrderStatus.EXPIRED,
                "MOCK_EXPIRED",
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ));

        Transaction expired = awaitTransactionStatus(submittedBuy.getId(), TransactionStatus.EXPIRED, WAIT_TIMEOUT);
        assertEquals("EX_SUBMITTED_BUY", expired.getExchangeOrderId());

        GlobalBalance balance = awaitBalance(portfolioId, INITIAL_CAPITAL, BigDecimal.ZERO, WAIT_TIMEOUT);
        assertNotNull(balance);
    }

    @Test
    void canceledPartialBuyShouldReleaseOnlyOutstandingReservedAmount() {
        UUID portfolioId = createPortfolio();
        UUID runnerId = createRunner(portfolioId);
        BigDecimal totalReserved = new BigDecimal("100.00000000");
        BigDecimal executedCost = new BigDecimal("40.00000000");
        BigDecimal expectedRemainingReserved = totalReserved.subtract(executedCost);

        Transaction partialBuy = newTransaction(
                runnerId,
                TransactionType.BUY,
                new BigDecimal("1.00000000"),
                new BigDecimal("100.00000000"),
                totalReserved,
                null
        );
        partialBuy.submit("EX_PARTIAL_BUY");
        partialBuy.partialFill(new BigDecimal("0.40000000"), new BigDecimal("100.00000000"));
        strategyRunnerRepository.saveTransaction(partialBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, totalReserved));

        GlobalBalance balanceAfterPartial = globalBalanceRepository.findByPortfolioId(portfolioId)
                .orElseThrow(() -> new IllegalStateException("GlobalBalance not found"));
        balanceAfterPartial.confirmExecution(executedCost, BigDecimal.ZERO, BigDecimal.ZERO);
        globalBalanceRepository.save(balanceAfterPartial);

        GlobalBalance preCancelBalance = globalBalanceRepository.findByPortfolioId(portfolioId)
                .orElseThrow(() -> new IllegalStateException("GlobalBalance not found"));
        assertEquals(0, preCancelBalance.getReservedBalance().compareTo(expectedRemainingReserved));

        orderConciliationPort.execute(orderData(
                partialBuy,
                OrderDataDto.OrderStatus.CANCELED,
                "MOCK_CANCELED",
                partialBuy.getEffectiveExecutedQuantity(),
                partialBuy.getEffectiveExecutedPrice()
        ));

        awaitTransactionStatus(partialBuy.getId(), TransactionStatus.CANCELED, WAIT_TIMEOUT);
        awaitBalance(portfolioId, INITIAL_CAPITAL, BigDecimal.ZERO, WAIT_TIMEOUT);
    }

    @Test
    void canceledSellShouldUnlockTargetPositionAndPreserveBalance() {
        UUID portfolioId = createPortfolio();
        UUID runnerId = createRunner(portfolioId);
        BigDecimal quantity = new BigDecimal("0.01000000");
        BigDecimal buyPrice = new BigDecimal("50000.00000000");
        BigDecimal sellPrice = new BigDecimal("52000.00000000");

        Transaction openedByBuy = newTransaction(
                runnerId,
                TransactionType.BUY,
                quantity,
                buyPrice,
                quantity.multiply(buyPrice),
                null
        );
        openedByBuy.submit("EX_OPEN_BUY");
        openedByBuy.fill(quantity, buyPrice);
        strategyRunnerRepository.saveTransaction(openedByBuy);

        Position position = Position.open(runnerId, SYMBOL, quantity, quantity.multiply(buyPrice));
        position.associateBuyTransaction(openedByBuy.getId());

        Transaction sellPending = newTransaction(
                runnerId,
                TransactionType.SELL,
                quantity,
                sellPrice,
                quantity.multiply(sellPrice),
                null
        );
        strategyRunnerRepository.saveTransaction(sellPending);

        position.startClosing();
        position.lock(sellPending.getId(), quantity);
        strategyRunnerRepository.savePosition(position);
        jdbcTemplate.update(
                "UPDATE transactions SET target_lot_id = ? WHERE id = ?",
                position.getId(),
                sellPending.getId()
        );

        orderConciliationPort.execute(orderData(
                sellPending,
                OrderDataDto.OrderStatus.CANCELED,
                "MOCK_CANCELED",
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ));

        awaitTransactionStatus(sellPending.getId(), TransactionStatus.CANCELED, WAIT_TIMEOUT);

        Position unlocked = awaitPositionUnlocked(position.getId(), WAIT_TIMEOUT);
        assertEquals(PositionStatus.OPEN, unlocked.getStatus());
        assertNull(unlocked.getLockedByTransactionId());
        assertNull(unlocked.getLockedQuantity());

        awaitBalance(portfolioId, INITIAL_CAPITAL, BigDecimal.ZERO, WAIT_TIMEOUT);
    }

    /**
     * T73 (T53 F32): EXPIRED que traz execucao que a transacao nao conhecia. O fill e aplicado e gravado numa
     * transacao de banco; o encerramento grava de novo e nao pode usar a versao lida antes do fill.
     */
    @Test
    void expiredBuyWithUnseenExecutionIsCanceledKeepingTheFill() {
        UUID portfolioId = createPortfolio();
        UUID runnerId = createRunner(portfolioId);
        BigDecimal reserveAmount = new BigDecimal("100.00000000");

        Transaction submittedBuy = newTransaction(
                runnerId,
                TransactionType.BUY,
                new BigDecimal("1.00000000"),
                new BigDecimal("100.00000000"),
                reserveAmount,
                null
        );
        submittedBuy.submit("EX_EXPIRED_WITH_FILL_BUY");
        strategyRunnerRepository.saveTransaction(submittedBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, reserveAmount));

        orderConciliationPort.execute(orderData(
                submittedBuy,
                OrderDataDto.OrderStatus.EXPIRED,
                null,
                new BigDecimal("0.40000000"),
                new BigDecimal("100.00000000")
        ));

        Transaction canceled = awaitTransactionStatus(submittedBuy.getId(), TransactionStatus.CANCELED, WAIT_TIMEOUT);
        assertEquals(0, canceled.getEffectiveExecutedQuantity().compareTo(new BigDecimal("0.40000000")));
        // Volta a reserva nao executada (60); o custo executado (40) segue reservado no lote aberto.
        awaitBalance(portfolioId, new BigDecimal("960.00000000"), new BigDecimal("40.00000000"), WAIT_TIMEOUT);
    }

    /** T73 (T53 F32): CANCELED de SELL com execucao nao vista encerra, grava o match e destrava o resto do lote. */
    @Test
    void canceledSellWithUnseenExecutionIsCanceledAndUnlocksTheRest() {
        UUID portfolioId = createPortfolio();
        UUID runnerId = createRunner(portfolioId);
        BigDecimal quantity = new BigDecimal("0.01000000");
        BigDecimal buyPrice = new BigDecimal("50000.00000000");
        BigDecimal sellPrice = new BigDecimal("52000.00000000");

        Transaction openedByBuy = newTransaction(runnerId, TransactionType.BUY, quantity, buyPrice,
                quantity.multiply(buyPrice), null);
        openedByBuy.submit("EX_OPEN_BUY_F32");
        openedByBuy.fill(quantity, buyPrice);
        strategyRunnerRepository.saveTransaction(openedByBuy);

        Position position = Position.open(runnerId, SYMBOL, quantity, quantity.multiply(buyPrice));
        position.associateBuyTransaction(openedByBuy.getId());

        Transaction sellSubmitted = newTransaction(runnerId, TransactionType.SELL, quantity, sellPrice,
                quantity.multiply(sellPrice), null);
        sellSubmitted.submit("EX_SELL_F32");
        strategyRunnerRepository.saveTransaction(sellSubmitted);

        position.startClosing();
        position.lock(sellSubmitted.getId(), quantity);
        strategyRunnerRepository.savePosition(position);
        jdbcTemplate.update("UPDATE transactions SET target_lot_id = ? WHERE id = ?",
                position.getId(), sellSubmitted.getId());

        orderConciliationPort.execute(orderData(
                sellSubmitted,
                OrderDataDto.OrderStatus.CANCELED,
                null,
                new BigDecimal("0.00400000"),
                sellPrice
        ));

        Transaction canceled = awaitTransactionStatus(sellSubmitted.getId(), TransactionStatus.CANCELED, WAIT_TIMEOUT);
        assertEquals(0, canceled.getEffectiveExecutedQuantity().compareTo(new BigDecimal("0.00400000")));
        Position unlocked = awaitPositionUnlocked(position.getId(), WAIT_TIMEOUT);
        assertEquals(0, unlocked.getQuantity().compareTo(new BigDecimal("0.00600000")));
        Integer matches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transaction_matches WHERE sell_transaction_id = ?", Integer.class,
                sellSubmitted.getId());
        assertEquals(1, matches);
    }

    private UUID createPortfolio() {
        CreatePortfolioResponse response = createPortfolioPort.execute(
                CreatePortfolioRequest.builder()
                        .name("phase1b-term-" + UUID.randomUUID())
                        .initialCapitalAmount(INITIAL_CAPITAL)
                        .currency("USDT")
                        .build()
        );
        assertNotNull(response.portfolioId(), "Portfolio creation failed: " + response.message());
        return response.portfolioId();
    }

    private UUID createRunner(UUID portfolioId) {
        CreateRunnerResponse response = createRunnerPort.execute(
                CreateRunnerRequest.builder()
                        .portfolioId(portfolioId)
                        .strategyId(SMA_STRATEGY_ID)
                        .symbol(SYMBOL)
                        .exchangeName("MOCK")
                        .allowedMarketDataSources(Set.of("BINANCE"))
                        .build()
        );
        assertNotNull(response.runnerId(), "Runner creation failed: " + response.message());
        return response.runnerId();
    }

    private Transaction newTransaction(UUID runnerId,
                                       TransactionType type,
                                       BigDecimal quantity,
                                       BigDecimal price,
                                       BigDecimal total,
                                       UUID targetLotId) {
        return new Transaction(
                runnerId,
                ClientOrderId.generate("x1", type),
                type,
                OrderType.LIMIT,
                SYMBOL,
                quantity,
                price,
                total,
                new BigDecimal("0.80"),
                "phase1b termination test",
                targetLotId, Map.of()
        );
    }

    private OrderDataDto orderData(Transaction transaction,
                                   OrderDataDto.OrderStatus status,
                                   String rejectReason,
                                   BigDecimal executedQuantity,
                                   BigDecimal executedPrice) {
        return new OrderDataDto(
                transaction.getExchangeOrderId(),
                transaction.getClientOrderId(),
                Symbol.of(SYMBOL),
                transaction.isBuy() ? OrderDataDto.OrderSide.BUY : OrderDataDto.OrderSide.SELL,
                OrderDataDto.OrderType.LIMIT,
                transaction.getQuantity(),
                executedQuantity,
                transaction.getPrice(),
                executedPrice,
                BigDecimal.ZERO,
                status,
                rejectReason,
                Instant.now(),
                null
        );
    }

    private Transaction awaitTransactionStatus(UUID transactionId,
                                               TransactionStatus expectedStatus,
                                               Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        Transaction last = null;
        while (Instant.now().isBefore(deadline)) {
            last = strategyRunnerRepository.findTransactionById(transactionId).orElse(null);
            if (last != null && last.getStatus() == expectedStatus) {
                return last;
            }
            sleep(80);
        }
        throw new AssertionError("Timeout waiting transaction status " + expectedStatus
                + " for transactionId=" + transactionId + " last=" + (last == null ? "null" : last.getStatus()));
    }

    private Position awaitPositionUnlocked(UUID positionId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        Position last = null;
        while (Instant.now().isBefore(deadline)) {
            last = strategyRunnerRepository.findPositionById(positionId).orElse(null);
            if (last != null && last.getLockedByTransactionId() == null) {
                return last;
            }
            sleep(80);
        }
        throw new AssertionError("Timeout waiting unlocked positionId=" + positionId
                + " lastLockTx=" + (last == null ? "null" : last.getLockedByTransactionId()));
    }

    private GlobalBalance awaitBalance(UUID portfolioId,
                                       BigDecimal expectedAvailable,
                                       BigDecimal expectedReserved,
                                       Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        GlobalBalance last = null;
        while (Instant.now().isBefore(deadline)) {
            last = globalBalanceRepository.findByPortfolioId(portfolioId).orElse(null);
            if (last != null
                    && last.getAvailableBalance().compareTo(expectedAvailable) == 0
                    && last.getReservedBalance().compareTo(expectedReserved) == 0) {
                return last;
            }
            sleep(80);
        }
        throw new AssertionError("Timeout waiting balance portfolioId=" + portfolioId
                + " expectedAvailable=" + expectedAvailable
                + " expectedReserved=" + expectedReserved
                + " lastAvailable=" + (last == null ? "null" : last.getAvailableBalance())
                + " lastReserved=" + (last == null ? "null" : last.getReservedBalance()));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting async processing", e);
        }
    }
}


