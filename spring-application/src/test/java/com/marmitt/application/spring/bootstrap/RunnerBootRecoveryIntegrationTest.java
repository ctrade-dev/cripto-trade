package com.marmitt.application.spring.bootstrap;

import com.marmitt.core.domain.runner.RunnerStatusCause;
import com.marmitt.application.spring.config.exchange.MockExchangeAdapter;
import com.marmitt.application.spring.testsupport.DatabaseCleaner;
import com.marmitt.core.domain.Symbol;
import com.marmitt.core.application.usecase.runner.RunnerBootRecoveryUseCase;
import com.marmitt.core.domain.portfolio.GlobalBalance;
import com.marmitt.core.domain.runner.Position;
import com.marmitt.core.domain.runner.ClientOrderId;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.portfolio.request.CreatePortfolioRequest;
import com.marmitt.core.dto.portfolio.response.CreatePortfolioResponse;
import com.marmitt.core.dto.runner.request.CreateRunnerRequest;
import com.marmitt.core.dto.runner.response.CreateRunnerResponse;
import com.marmitt.core.dto.websocket.data.OrderDataDto;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.DlqReason;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.enums.TransactionType;
import com.marmitt.core.exceptions.ExchangeQueryException;
import com.marmitt.core.ports.inbound.portfolio.CreatePortfolioPort;
import com.marmitt.core.ports.inbound.runner.CreateRunnerPort;
import com.marmitt.core.ports.outbound.repository.DeadLetterEntryRepositoryPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.GlobalBalanceRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerBootRecoveryIntegrationTest extends AbstractIntegrationTest {

    private static final UUID SMA_STRATEGY_ID =
            UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final String SYMBOL = "BTCUSDT";
    private static final BigDecimal INITIAL_CAPITAL = new BigDecimal("1000.00000000");
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(8);

    @DynamicPropertySource
    static void registerRecoveryProperties(DynamicPropertyRegistry registry) {
        registry.add("runner.boot.phase3.exchange-query-timeout-ms", () -> 500L);
        registry.add("runner.boot.phase3.exchange-query-max-attempts", () -> 3);
        registry.add("runner.boot.phase3.exchange-query-initial-backoff-ms", () -> 10L);
        registry.add("runner.boot.phase3.exchange-query-backoff-multiplier", () -> 1.0d);
        registry.add("runner.boot.phase3.exchange-query-max-backoff-ms", () -> 10L);
    }

    @Autowired
    private CreatePortfolioPort createPortfolioPort;

    @Autowired
    private CreateRunnerPort createRunnerPort;

    @Autowired
    private RunnerBootRecoveryUseCase runnerBootRecoveryUseCase;

    @Autowired
    private StrategyRunnerRepositoryPort strategyRunnerRepository;

    @Autowired
    private GlobalBalanceRepositoryPort globalBalanceRepository;

    @Autowired
    private DeadLetterEntryRepositoryPort deadLetterEntryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExchangeAdapterRepositoryPort exchangeAdapterRepository;

    @BeforeEach
    void cleanDatabase() {
        getMockExchangeAdapter().reset();
        DatabaseCleaner.reset(jdbcTemplate);
    }

    @Test
    void recoveryShouldQueryVerifyAndExpirePendingBuyNotFoundOnExchange() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        BigDecimal reservedAmount = new BigDecimal("150.00000000");

        Transaction pendingBuy = newTransaction(
                runner,
                TransactionType.BUY,
                new BigDecimal("0.00300000"),
                new BigDecimal("50000.00000000"),
                reservedAmount
        );
        strategyRunnerRepository.saveTransaction(pendingBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, reservedAmount));

        // Age is no longer required (boot query-verifies regardless of age); kept for realism.
        jdbcTemplate.update(
                "UPDATE transactions SET requested_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(2))),
                pendingBuy.getId()
        );

        RunnerBootRecoveryUseCase.RecoverySummary summary = runnerBootRecoveryUseCase.recoverRunner(runner, RunnerStatusCause.boot("test"));

        // PENDING (sem exchangeOrderId) agora e consultado na exchange (nao encontrado) e
        // expirado pelo mesmo caminho do limbo — sem expiracao local cega.
        Transaction expired = awaitTransactionStatus(pendingBuy.getId(), TransactionStatus.EXPIRED, WAIT_TIMEOUT);
        assertNotNull(expired);
        assertEquals(1, summary.inFlightCount());
        assertEquals(1, summary.limboCount());

        awaitBalance(portfolioId, INITIAL_CAPITAL, BigDecimal.ZERO, WAIT_TIMEOUT);

        StrategyRunner latestRunner = strategyRunnerRepository.findById(runner.getId())
                .orElseThrow(() -> new IllegalStateException("Runner not found after recovery"));
        assertEquals(com.marmitt.core.enums.RunnerStatus.ACTIVE, latestRunner.getStatus());
        assertFalse(latestRunner.isReconciling());
    }

    @Test
    void recoveryShouldReconcilePendingBuyFoundAliveOnExchangeAndKeepReserve() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        BigDecimal quantity = new BigDecimal("0.00150000");
        BigDecimal price = new BigDecimal("60000.00000000");
        BigDecimal reservedAmount = quantity.multiply(price);

        // PENDING sem exchangeOrderId (ACK perdido), porem a ordem esta VIVA na exchange.
        Transaction pendingBuy = newTransaction(
                runner,
                TransactionType.BUY,
                quantity,
                price,
                reservedAmount
        );
        strategyRunnerRepository.saveTransaction(pendingBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, reservedAmount));

        // Velho o suficiente para passar da carencia de reconciliacao de PENDING no boot.
        jdbcTemplate.update(
                "UPDATE transactions SET requested_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(2))),
                pendingBuy.getId()
        );

        getMockExchangeAdapter().seedQueriedOrderSnapshot(new OrderDataDto(
                "EX_PENDING_ALIVE",
                pendingBuy.getClientOrderId(),
                Symbol.of(SYMBOL),
                OrderDataDto.OrderSide.BUY,
                OrderDataDto.OrderType.LIMIT,
                quantity,
                BigDecimal.ZERO,
                price,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                OrderDataDto.OrderStatus.NEW,
                null,
                Instant.now(),
                null
        ));

        RunnerBootRecoveryUseCase.RecoverySummary summary = runnerBootRecoveryUseCase.recoverRunner(runner, RunnerStatusCause.boot("test"));

        // Query-before-expire: ordem viva e reconciliada (PENDING->SUBMITTED); capital NAO liberado.
        Transaction submitted = awaitTransactionStatus(pendingBuy.getId(), TransactionStatus.SUBMITTED, WAIT_TIMEOUT);
        assertNotNull(submitted);
        assertEquals(1, summary.inFlightCount());
        assertEquals(1, summary.limboCount());

        awaitBalance(portfolioId, INITIAL_CAPITAL.subtract(reservedAmount), reservedAmount, WAIT_TIMEOUT);

        StrategyRunner latestRunner = strategyRunnerRepository.findById(runner.getId())
                .orElseThrow(() -> new IllegalStateException("Runner not found after recovery"));
        assertEquals(com.marmitt.core.enums.RunnerStatus.ACTIVE, latestRunner.getStatus());
        assertFalse(latestRunner.isReconciling());
    }

    @Test
    void recoveryShouldDeferYoungPendingWithoutExpiringIt() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        BigDecimal quantity = new BigDecimal("0.00150000");
        BigDecimal price = new BigDecimal("60000.00000000");
        BigDecimal reservedAmount = quantity.multiply(price);

        // PENDING recem-criado (jovem): a ordem pode ter sido enviada logo antes do crash e ainda
        // nao estar visivel na query da exchange — nao deve ser expirada no boot.
        Transaction youngPending = newTransaction(runner, TransactionType.BUY, quantity, price, reservedAmount);
        strategyRunnerRepository.saveTransaction(youngPending);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, reservedAmount));

        RunnerBootRecoveryUseCase.RecoverySummary summary = runnerBootRecoveryUseCase.recoverRunner(runner, RunnerStatusCause.boot("test"));

        // Deferido: continua PENDING, fora do limbo; capital reservado preservado; runner ativa.
        Transaction stillPending = strategyRunnerRepository.findTransactionById(youngPending.getId())
                .orElseThrow(() -> new IllegalStateException("Transaction not found"));
        assertEquals(TransactionStatus.PENDING, stillPending.getStatus());
        assertEquals(1, summary.inFlightCount());
        assertEquals(0, summary.limboCount());
        awaitBalance(portfolioId, INITIAL_CAPITAL.subtract(reservedAmount), reservedAmount, WAIT_TIMEOUT);

        StrategyRunner latestRunner = strategyRunnerRepository.findById(runner.getId())
                .orElseThrow(() -> new IllegalStateException("Runner not found after recovery"));
        assertEquals(com.marmitt.core.enums.RunnerStatus.ACTIVE, latestRunner.getStatus());
        assertFalse(latestRunner.isReconciling());
    }

    @Test
    void recoveryShouldBeIdempotentWhenExpiringTheSamePendingTwice() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        BigDecimal reservedAmount = new BigDecimal("140.00000000");

        Transaction pendingBuy = newTransaction(
                runner,
                TransactionType.BUY,
                new BigDecimal("0.00280000"),
                new BigDecimal("50000.00000000"),
                reservedAmount
        );
        strategyRunnerRepository.saveTransaction(pendingBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, reservedAmount));

        jdbcTemplate.update(
                "UPDATE transactions SET requested_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(2))),
                pendingBuy.getId()
        );

        RunnerBootRecoveryUseCase.RecoverySummary first = runnerBootRecoveryUseCase.recoverRunner(runner, RunnerStatusCause.boot("test"));
        Transaction expired = awaitTransactionStatus(pendingBuy.getId(), TransactionStatus.EXPIRED, WAIT_TIMEOUT);
        assertNotNull(expired);
        awaitBalance(portfolioId, INITIAL_CAPITAL, BigDecimal.ZERO, WAIT_TIMEOUT);

        RunnerBootRecoveryUseCase.RecoverySummary second = runnerBootRecoveryUseCase.recoverRunner(runner, RunnerStatusCause.boot("test"));

        Transaction expiredAgain = awaitTransactionStatus(pendingBuy.getId(), TransactionStatus.EXPIRED, WAIT_TIMEOUT);
        assertNotNull(expiredAgain);
        assertEquals(1, first.inFlightCount());
        assertEquals(1, first.limboCount());
        assertEquals(0, second.inFlightCount());
        assertEquals(0, second.limboCount());
        awaitBalance(portfolioId, INITIAL_CAPITAL, BigDecimal.ZERO, WAIT_TIMEOUT);

        StrategyRunner latestRunner = strategyRunnerRepository.findById(runner.getId())
                .orElseThrow(() -> new IllegalStateException("Runner not found after repeated recovery"));
        assertEquals(com.marmitt.core.enums.RunnerStatus.ACTIVE, latestRunner.getStatus());
        assertFalse(latestRunner.isReconciling());
    }

    @Test
    void recoveryShouldExpireSubmittedOrderNotFoundOnExchangeAndReleaseBalance() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        BigDecimal reservedAmount = new BigDecimal("90.00000000");

        Transaction submittedBuy = newTransaction(
                runner,
                TransactionType.BUY,
                new BigDecimal("0.00150000"),
                new BigDecimal("60000.00000000"),
                reservedAmount
        );
        submittedBuy.submit("EX_MISSING_ON_EXCHANGE");
        strategyRunnerRepository.saveTransaction(submittedBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, reservedAmount));

        RunnerBootRecoveryUseCase.RecoverySummary summary = runnerBootRecoveryUseCase.recoverRunner(runner, RunnerStatusCause.boot("test"));

        Transaction expired = awaitTransactionStatus(submittedBuy.getId(), TransactionStatus.EXPIRED, WAIT_TIMEOUT);
        assertNotNull(expired);
        assertEquals(1, summary.inFlightCount());
        assertEquals(1, summary.limboCount());

        awaitBalance(portfolioId, INITIAL_CAPITAL, BigDecimal.ZERO, WAIT_TIMEOUT);

        RunnerBootRecoveryUseCase.RecoverySummary second = runnerBootRecoveryUseCase.recoverRunner(runner, RunnerStatusCause.boot("test"));

        assertEquals(0, second.inFlightCount());
        assertEquals(0, second.limboCount());

        Transaction expiredAgain = strategyRunnerRepository.findTransactionById(submittedBuy.getId())
                .orElseThrow(() -> new IllegalStateException("Transaction not found after repeated recovery"));
        assertEquals(TransactionStatus.EXPIRED, expiredAgain.getStatus());

        awaitBalance(portfolioId, INITIAL_CAPITAL, BigDecimal.ZERO, WAIT_TIMEOUT);
    }

    @Test
    void recoveryShouldCancelPartialBuyNotFoundOnExchangeAndReleaseOutstandingReserveOnly() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        BigDecimal totalQuantity = new BigDecimal("1.00000000");
        BigDecimal totalReserved = new BigDecimal("112.00000000");
        BigDecimal partialQuantity = new BigDecimal("0.40000000");
        BigDecimal partialPrice = new BigDecimal("100.00000000");
        BigDecimal partialExecutedCost = partialQuantity.multiply(partialPrice);

        Transaction partialBuy = newTransaction(
                runner,
                TransactionType.BUY,
                totalQuantity,
                new BigDecimal("112.00000000"),
                totalReserved
        );
        partialBuy.submit("EX_PARTIAL_MISSING_ON_EXCHANGE");
        partialBuy.partialFill(partialQuantity, partialPrice);
        strategyRunnerRepository.saveTransaction(partialBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, totalReserved));

        Position partialPosition = Position.open(runner.getId(), SYMBOL, partialQuantity, partialQuantity.multiply(partialPrice));
        partialPosition.associateBuyTransaction(partialBuy.getId());
        strategyRunnerRepository.savePosition(partialPosition);

        GlobalBalance balanceAfterPartial = globalBalanceRepository.findByPortfolioId(portfolioId)
                .orElseThrow(() -> new IllegalStateException("GlobalBalance not found"));
        balanceAfterPartial.confirmExecution(partialExecutedCost, BigDecimal.ZERO, BigDecimal.ZERO);
        globalBalanceRepository.save(balanceAfterPartial);

        RunnerBootRecoveryUseCase.RecoverySummary summary = runnerBootRecoveryUseCase.recoverRunner(runner, RunnerStatusCause.boot("test"));

        Transaction canceled = awaitTransactionStatus(partialBuy.getId(), TransactionStatus.CANCELED, WAIT_TIMEOUT);
        assertNotNull(canceled);
        assertEquals(1, summary.inFlightCount());
        assertEquals(1, summary.limboCount());

        Position position = strategyRunnerRepository.findPositionByOpenedByTransactionId(partialBuy.getId())
                .orElseThrow(() -> new IllegalStateException("Position not found for openedByTransactionId="
                        + partialBuy.getId()));
        assertEquals(0, position.getQuantity().compareTo(partialQuantity));
        assertEquals(0, position.getAveragePrice().compareTo(partialPrice));

        awaitBalance(portfolioId, INITIAL_CAPITAL, BigDecimal.ZERO, WAIT_TIMEOUT);

        RunnerBootRecoveryUseCase.RecoverySummary second = runnerBootRecoveryUseCase.recoverRunner(runner, RunnerStatusCause.boot("test"));

        assertEquals(0, second.inFlightCount());
        assertEquals(0, second.limboCount());

        Transaction canceledAgain = strategyRunnerRepository.findTransactionById(partialBuy.getId())
                .orElseThrow(() -> new IllegalStateException("Transaction not found after repeated recovery"));
        assertEquals(TransactionStatus.CANCELED, canceledAgain.getStatus());

        Position positionAgain = strategyRunnerRepository.findPositionByOpenedByTransactionId(partialBuy.getId())
                .orElseThrow(() -> new IllegalStateException("Position not found after repeated recovery"));
        assertEquals(0, positionAgain.getQuantity().compareTo(partialQuantity));
        assertEquals(0, positionAgain.getAveragePrice().compareTo(partialPrice));

        awaitBalance(portfolioId, INITIAL_CAPITAL, BigDecimal.ZERO, WAIT_TIMEOUT);
    }

    @Test
    void recoveryShouldHaltRunnerWhenLimboExistsButQueryCapabilityIsUnavailable() {
        UUID portfolioId = createPortfolio();
        StrategyRunner created = createAndActivateRunner(portfolioId);
        BigDecimal reservedAmount = new BigDecimal("90.00000000");

        jdbcTemplate.update(
                "UPDATE strategy_runners SET exchange_id = ? WHERE id = ?",
                "MOCK_NO_QUERY",
                created.getId()
        );
        StrategyRunner runner = strategyRunnerRepository.findById(created.getId())
                .orElseThrow(() -> new IllegalStateException("Runner not found after exchange override"));

        Transaction submittedBuy = newTransaction(
                runner,
                TransactionType.BUY,
                new BigDecimal("0.00150000"),
                new BigDecimal("60000.00000000"),
                reservedAmount
        );
        submittedBuy.submit("EX_UNSUPPORTED_QUERY");
        strategyRunnerRepository.saveTransaction(submittedBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, reservedAmount));

        RunnerBootRecoveryUseCase.RecoverySummary summary = runnerBootRecoveryUseCase.recoverRunner(runner, RunnerStatusCause.boot("test"));

        Transaction stillSubmitted = awaitTransactionStatus(submittedBuy.getId(), TransactionStatus.SUBMITTED, WAIT_TIMEOUT);
        assertNotNull(stillSubmitted);
        assertEquals(1, summary.inFlightCount());
        assertEquals(1, summary.limboCount());
        assertTrue(summary.notes().stream().anyMatch(note -> note.contains("Step 2 ERROR")),
                "Recovery summary should record missing query capability");
        assertTrue(summary.notes().stream().anyMatch(note -> note.contains("Step 4 ERROR")),
                "Recovery summary should record limbo without query capability");

        StrategyRunner halted = strategyRunnerRepository.findById(runner.getId())
                .orElseThrow(() -> new IllegalStateException("Runner not found after unsupported query recovery"));
        assertEquals(com.marmitt.core.enums.RunnerStatus.HALTED, halted.getStatus());
        assertTrue(halted.isReconciling());

        awaitBalance(
                portfolioId,
                INITIAL_CAPITAL.subtract(reservedAmount),
                reservedAmount,
                WAIT_TIMEOUT
        );
    }

    @Test
    void recoveryShouldRetryTransientQueryFailureAndReconcileOnNextAttempt() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        BigDecimal quantity = new BigDecimal("0.00150000");
        BigDecimal fillPrice = new BigDecimal("60000.00000000");
        BigDecimal reservedAmount = quantity.multiply(fillPrice);

        Transaction submittedBuy = newTransaction(
                runner,
                TransactionType.BUY,
                quantity,
                fillPrice,
                reservedAmount
        );
        submittedBuy.submit("EX_QUERY_RETRY_FILLED");
        strategyRunnerRepository.saveTransaction(submittedBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, reservedAmount));

        MockExchangeAdapter mock = getMockExchangeAdapter();
        mock.seedQueriedOrderSnapshot(orderData(
                submittedBuy,
                OrderDataDto.OrderStatus.FILLED,
                quantity,
                fillPrice,
                BigDecimal.ZERO
        ));
        mock.registerQueryFailurePlan(
                submittedBuy.getClientOrderId(),
                1,
                ExchangeQueryException.ErrorType.TEMPORARY,
                "Planned transient query failure"
        );

        RunnerBootRecoveryUseCase.RecoverySummary summary = runnerBootRecoveryUseCase.recoverRunner(runner, RunnerStatusCause.boot("test"));

        Transaction filled = awaitTransactionStatus(submittedBuy.getId(), TransactionStatus.FILLED, WAIT_TIMEOUT);
        assertNotNull(filled);
        assertEquals(1, summary.inFlightCount());
        assertEquals(1, summary.limboCount());
        assertTrue(summary.notes().stream().anyMatch(note -> note.contains("transient query failure")),
                "Recovery summary should record the transient query failure");
        assertTrue(summary.notes().stream().anyMatch(note -> note.contains("exchange query recovered")),
                "Recovery summary should record recovery after retry");

        Position position = strategyRunnerRepository.findPositionByOpenedByTransactionId(submittedBuy.getId())
                .orElseThrow(() -> new IllegalStateException("Position not found for openedByTransactionId="
                        + submittedBuy.getId()));
        assertEquals(0, position.getQuantity().compareTo(quantity));
        assertEquals(0, position.getAveragePrice().compareTo(fillPrice));

        awaitBalance(
                portfolioId,
                INITIAL_CAPITAL.subtract(reservedAmount),
                reservedAmount,
                WAIT_TIMEOUT
        );
    }

    @Test
    void recoveryShouldHaltRunnerWhenTransientQueryFailuresExhaustRetries() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        BigDecimal quantity = new BigDecimal("0.00150000");
        BigDecimal price = new BigDecimal("60000.00000000");
        BigDecimal reservedAmount = quantity.multiply(price);

        Transaction submittedBuy = newTransaction(
                runner,
                TransactionType.BUY,
                quantity,
                price,
                reservedAmount
        );
        submittedBuy.submit("EX_QUERY_RETRY_EXHAUSTED");
        strategyRunnerRepository.saveTransaction(submittedBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, reservedAmount));

        MockExchangeAdapter mock = getMockExchangeAdapter();
        mock.seedQueriedOrderSnapshot(orderData(
                submittedBuy,
                OrderDataDto.OrderStatus.FILLED,
                quantity,
                price,
                BigDecimal.ZERO
        ));
        mock.registerQueryFailurePlan(
                submittedBuy.getClientOrderId(),
                3,
                ExchangeQueryException.ErrorType.TEMPORARY,
                "Planned transient query failure exhausted"
        );

        RunnerBootRecoveryUseCase.RecoverySummary summary = runnerBootRecoveryUseCase.recoverRunner(runner, RunnerStatusCause.boot("test"));

        Transaction stillSubmitted = awaitTransactionStatus(submittedBuy.getId(), TransactionStatus.SUBMITTED, WAIT_TIMEOUT);
        assertNotNull(stillSubmitted);
        assertEquals(1, summary.inFlightCount());
        assertEquals(1, summary.limboCount());
        assertTrue(summary.notes().stream().anyMatch(note -> note.contains("transient query failure")),
                "Recovery summary should record transient query failures");
        assertTrue(summary.notes().stream().anyMatch(note -> note.contains("Step 4 ERROR")),
                "Recovery summary should record the exhausted query failure");

        StrategyRunner halted = strategyRunnerRepository.findById(runner.getId())
                .orElseThrow(() -> new IllegalStateException("Runner not found after exhausted query recovery"));
        assertEquals(com.marmitt.core.enums.RunnerStatus.HALTED, halted.getStatus());
        assertTrue(halted.isReconciling());

        assertTrue(strategyRunnerRepository.findPositionByOpenedByTransactionId(submittedBuy.getId()).isEmpty(),
                "Exhausted query retries must not create a position");

        awaitBalance(
                portfolioId,
                INITIAL_CAPITAL.subtract(reservedAmount),
                reservedAmount,
                WAIT_TIMEOUT
        );
    }

    @Test
    void recoveryShouldReconcileSubmittedBuyFoundAsFilledOnExchange() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        BigDecimal quantity = new BigDecimal("0.00150000");
        BigDecimal fillPrice = new BigDecimal("60000.00000000");
        BigDecimal reservedAmount = quantity.multiply(fillPrice);

        Transaction submittedBuy = newTransaction(
                runner,
                TransactionType.BUY,
                quantity,
                fillPrice,
                reservedAmount
        );
        submittedBuy.submit("EX_FOUND_FILLED");
        strategyRunnerRepository.saveTransaction(submittedBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, reservedAmount));

        getMockExchangeAdapter().seedQueriedOrderSnapshot(orderData(
                submittedBuy,
                OrderDataDto.OrderStatus.FILLED,
                quantity,
                fillPrice,
                BigDecimal.ZERO
        ));

        RunnerBootRecoveryUseCase.RecoverySummary summary = runnerBootRecoveryUseCase.recoverRunner(runner, RunnerStatusCause.boot("test"));

        Transaction filled = awaitTransactionStatus(submittedBuy.getId(), TransactionStatus.FILLED, WAIT_TIMEOUT);
        assertNotNull(filled);
        assertEquals(1, summary.inFlightCount());
        assertEquals(1, summary.limboCount());

        Position position = strategyRunnerRepository.findPositionByOpenedByTransactionId(submittedBuy.getId())
                .orElseThrow(() -> new IllegalStateException("Position not found for openedByTransactionId="
                        + submittedBuy.getId()));
        assertEquals(0, position.getQuantity().compareTo(quantity));
        assertEquals(0, position.getAveragePrice().compareTo(fillPrice));

        awaitBalance(
                portfolioId,
                INITIAL_CAPITAL.subtract(reservedAmount),
                reservedAmount,
                WAIT_TIMEOUT
        );
    }

    @Test
    void recoveryShouldReconcilePartialBuyFoundAsFilledOnExchangeWithoutQuantityDrift() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        BigDecimal totalQuantity = new BigDecimal("1.00000000");
        BigDecimal totalReserved = new BigDecimal("112.00000000");
        BigDecimal partialQuantity = new BigDecimal("0.40000000");
        BigDecimal partialPrice = new BigDecimal("100.00000000");
        BigDecimal finalExecutedPrice = new BigDecimal("112.00000000");
        BigDecimal partialExecutedCost = partialQuantity.multiply(partialPrice);

        Transaction partialBuy = newTransaction(
                runner,
                TransactionType.BUY,
                totalQuantity,
                finalExecutedPrice,
                totalReserved
        );
        partialBuy.submit("EX_PARTIAL_THEN_FILLED");
        partialBuy.partialFill(partialQuantity, partialPrice);
        strategyRunnerRepository.saveTransaction(partialBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, totalReserved));

        Position partialPosition = Position.open(runner.getId(), SYMBOL, partialQuantity, partialQuantity.multiply(partialPrice));
        partialPosition.associateBuyTransaction(partialBuy.getId());
        strategyRunnerRepository.savePosition(partialPosition);

        GlobalBalance balanceAfterPartial = globalBalanceRepository.findByPortfolioId(portfolioId)
                .orElseThrow(() -> new IllegalStateException("GlobalBalance not found"));
        balanceAfterPartial.confirmExecution(partialExecutedCost, BigDecimal.ZERO, BigDecimal.ZERO);
        globalBalanceRepository.save(balanceAfterPartial);

        getMockExchangeAdapter().seedQueriedOrderSnapshot(orderData(
                partialBuy,
                OrderDataDto.OrderStatus.FILLED,
                totalQuantity,
                finalExecutedPrice,
                BigDecimal.ZERO
        ));

        RunnerBootRecoveryUseCase.RecoverySummary summary = runnerBootRecoveryUseCase.recoverRunner(runner, RunnerStatusCause.boot("test"));

        Transaction filled = awaitTransactionStatus(partialBuy.getId(), TransactionStatus.FILLED, WAIT_TIMEOUT);
        assertNotNull(filled);
        assertEquals(1, summary.inFlightCount());
        assertEquals(1, summary.limboCount());
        assertEquals(0, filled.getEffectiveExecutedQuantity().compareTo(totalQuantity));
        assertEquals(0, filled.getEffectiveExecutedPrice().compareTo(finalExecutedPrice));

        Position position = strategyRunnerRepository.findPositionByOpenedByTransactionId(partialBuy.getId())
                .orElseThrow(() -> new IllegalStateException("Position not found for openedByTransactionId="
                        + partialBuy.getId()));
        assertEquals(0, position.getQuantity().compareTo(totalQuantity));
        assertEquals(0, position.getAveragePrice().compareTo(finalExecutedPrice));

        awaitBalance(
                portfolioId,
                INITIAL_CAPITAL.subtract(totalReserved).add(partialExecutedCost),
                totalReserved.subtract(partialExecutedCost),
                WAIT_TIMEOUT
        );

        RunnerBootRecoveryUseCase.RecoverySummary second = runnerBootRecoveryUseCase.recoverRunner(runner, RunnerStatusCause.boot("test"));

        assertEquals(0, second.inFlightCount());
        assertEquals(0, second.limboCount());

        Transaction filledAgain = strategyRunnerRepository.findTransactionById(partialBuy.getId())
                .orElseThrow(() -> new IllegalStateException("Transaction not found after repeated recovery"));
        assertEquals(0, filledAgain.getEffectiveExecutedQuantity().compareTo(totalQuantity));

        Position positionAgain = strategyRunnerRepository.findPositionByOpenedByTransactionId(partialBuy.getId())
                .orElseThrow(() -> new IllegalStateException("Position not found after repeated recovery"));
        assertEquals(0, positionAgain.getQuantity().compareTo(totalQuantity));
        assertEquals(0, positionAgain.getAveragePrice().compareTo(finalExecutedPrice));

        awaitBalance(
                portfolioId,
                INITIAL_CAPITAL.subtract(totalReserved).add(partialExecutedCost),
                totalReserved.subtract(partialExecutedCost),
                WAIT_TIMEOUT
        );
    }

    @Test
    void recoveryShouldHaltActiveRunnerWhenUnresolvedDlqExists() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);

        insertUnresolvedDlq(
                portfolioId,
                runner.getId(),
                ClientOrderId.generate(runner.getShortCode(), TransactionType.BUY),
                "EX_DANGLING",
                DlqReason.RETRY_EXHAUSTED
        );
        assertTrue(deadLetterEntryRepository.existsUnresolvedByRunnerId(runner.getId()));

        RunnerBootRecoveryUseCase.RecoverySummary summary = runnerBootRecoveryUseCase.recoverRunner(runner, RunnerStatusCause.boot("test"));

        assertEquals(0, summary.inFlightCount());
        assertEquals(0, summary.limboCount());

        StrategyRunner latestRunner = strategyRunnerRepository.findById(runner.getId())
                .orElseThrow(() -> new IllegalStateException("Runner not found after recovery"));
        assertEquals(com.marmitt.core.enums.RunnerStatus.HALTED, latestRunner.getStatus());
        assertTrue(latestRunner.isReconciling());
        assertTrue(deadLetterEntryRepository.existsUnresolvedByRunnerId(runner.getId()));
    }

    private UUID createPortfolio() {
        CreatePortfolioResponse response = createPortfolioPort.execute(
                CreatePortfolioRequest.builder()
                        .name("phase1a-boot-" + UUID.randomUUID())
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
                        .exchangeName("MOCK")
                        .allowedMarketDataSources(Set.of("BINANCE"))
                        .build()
        );
        assertNotNull(response.runnerId(), "Runner creation failed: " + response.message());

        StrategyRunner runner = strategyRunnerRepository.findById(response.runnerId())
                .orElseThrow(() -> new IllegalStateException("Runner not found: " + response.runnerId()));
        runner.startInitializing(RunnerStatusCause.manual("test"));
        runner.activate(RunnerStatusCause.manual("test"));
        strategyRunnerRepository.save(runner);
        return strategyRunnerRepository.findById(response.runnerId())
                .orElseThrow(() -> new IllegalStateException("Runner not found after activation"));
    }

    private Transaction newTransaction(StrategyRunner runner,
                                       TransactionType type,
                                       BigDecimal quantity,
                                       BigDecimal price,
                                       BigDecimal total) {
        return new Transaction(
                runner.getId(),
                ClientOrderId.generate(runner.getShortCode(), type),
                type,
                OrderType.LIMIT,
                SYMBOL,
                quantity,
                price,
                total,
                new BigDecimal("0.80"),
                "phase1a boot recovery test",
                null, Map.of()
        );
    }

    private MockExchangeAdapter getMockExchangeAdapter() {
        return exchangeAdapterRepository.findAdapter("MOCK")
                .map(d -> d.streaming())
                .filter(MockExchangeAdapter.class::isInstance)
                .map(MockExchangeAdapter.class::cast)
                .orElseThrow(() -> new IllegalStateException("MOCK adapter not found or has invalid type"));
    }

    private OrderDataDto orderData(Transaction transaction,
                                   OrderDataDto.OrderStatus status,
                                   BigDecimal executedQuantity,
                                   BigDecimal executedPrice,
                                   BigDecimal fee) {
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
                fee,
                status,
                null,
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
                + " transactionId=" + transactionId
                + " lastStatus=" + (last == null ? "null" : last.getStatus()));
    }

    private void insertUnresolvedDlq(UUID portfolioId,
                                     UUID runnerId,
                                     String clientOrderId,
                                     String exchangeOrderId,
                                     DlqReason reason) {
        jdbcTemplate.update(
                """
                INSERT INTO dead_letter_entries (
                    id,
                    portfolio_id,
                    runner_id,
                    client_order_id,
                    exchange_order_id,
                    raw_payload,
                    reason,
                    is_resolved,
                    created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, ?)
                """,
                UUID.randomUUID(),
                portfolioId,
                runnerId,
                clientOrderId,
                exchangeOrderId,
                "source=test unresolved dlq",
                reason.name(),
                Timestamp.from(Instant.now())
        );
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
        throw new AssertionError("Timeout waiting global balance"
                + " portfolioId=" + portfolioId
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


