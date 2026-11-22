package com.marmitt.application.spring.bootstrap;

import com.marmitt.core.domain.runner.RunnerStatusCause;
import com.marmitt.application.spring.config.exchange.MockExchangeAdapter;
import com.marmitt.application.spring.testsupport.DatabaseCleaner;
import com.marmitt.core.application.usecase.runner.orderconciliation.ConciliationOrderUpdateExecutor;
import com.marmitt.core.domain.Symbol;
import com.marmitt.core.domain.portfolio.DeadLetterEntry;
import com.marmitt.core.domain.portfolio.GlobalBalance;
import com.marmitt.core.domain.runner.ClientOrderId;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.portfolio.request.CreatePortfolioRequest;
import com.marmitt.core.dto.portfolio.response.CreatePortfolioResponse;
import com.marmitt.core.dto.runner.response.RecoverStaleTransactionsResponse;
import com.marmitt.core.dto.runner.request.CreateRunnerRequest;
import com.marmitt.core.dto.runner.response.CreateRunnerResponse;
import com.marmitt.core.dto.websocket.data.OrderDataDto;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.AccountingPolicyType;
import com.marmitt.core.enums.ExecutionPolicy;
import com.marmitt.core.enums.RunnerStatus;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

class RunnerTransactionRecoveryWatchdogIntegrationTest extends AbstractIntegrationTest {

    private static final UUID SMA_STRATEGY_ID =
            UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final String SYMBOL = "BTCUSDT";
    private static final BigDecimal INITIAL_CAPITAL = new BigDecimal("1000.00000000");
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(8);

    @DynamicPropertySource
    static void registerWatchdogProperties(DynamicPropertyRegistry registry) {
        registry.add("runner.recovery.transaction.interval-ms", () -> 86_400_000L);
        registry.add("runner.recovery.transaction.stale-threshold-ms", () -> 30_000L);
        registry.add("runner.recovery.transaction.max-per-run", () -> 50);
    }

    @Autowired
    private CreatePortfolioPort createPortfolioPort;

    @Autowired
    private CreateRunnerPort createRunnerPort;

    @Autowired
    private RunnerTransactionRecoveryWatchdog watchdog;

    @Autowired
    private RunnerTransactionRecoveryProperties properties;

    @SpyBean
    private StrategyRunnerRepositoryPort strategyRunnerRepository;

    @Autowired
    private DeadLetterEntryRepositoryPort deadLetterEntryRepository;

    @Autowired
    private GlobalBalanceRepositoryPort globalBalanceRepository;

    @Autowired
    private ExchangeAdapterRepositoryPort exchangeAdapterRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConciliationOrderUpdateExecutor conciliationOrderUpdateExecutor;

    @BeforeEach
    void cleanDatabaseAndResetProperties() {
        reset(strategyRunnerRepository);
        getMockExchangeAdapter().reset();
        DatabaseCleaner.reset(jdbcTemplate);
        properties.setEnabled(true);
        properties.setIntervalMs(86_400_000L);
        properties.setStaleThresholdMs(30_000L);
        properties.setMaxPerRun(50);
    }

    @Test
    void watchdogShouldRecoverStaleSubmittedOrderFoundAsFilled() {
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
        submittedBuy.submit("EX_RUNTIME_FILLED");
        strategyRunnerRepository.saveTransaction(submittedBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, reservedAmount));
        markTransactionStale(submittedBuy.getId(), Duration.ofHours(2));

        getMockExchangeAdapter().seedQueriedOrderSnapshot(orderData(
                submittedBuy,
                OrderDataDto.OrderStatus.FILLED,
                quantity,
                fillPrice,
                BigDecimal.ZERO
        ));

        RecoverStaleTransactionsResponse response = watchdog.runRecoveryCycle();

        Transaction filled = awaitTransactionStatus(submittedBuy.getId(), TransactionStatus.FILLED, WAIT_TIMEOUT);
        assertNotNull(filled);
        assertEquals(1, response.scanned());
        assertEquals(1, response.recovered());
        assertEquals(0, response.routedToDlq());
        assertEquals(0, response.failed());
    }

    @Test
    void watchdogShouldExpirePendingZombieNotFoundOnExchangeWithoutDlq() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        BigDecimal quantity = new BigDecimal("0.00150000");
        BigDecimal price = new BigDecimal("60000.00000000");
        BigDecimal reservedAmount = quantity.multiply(price);

        // PENDING sem exchangeOrderId (zombie) — nunca confirmado pela exchange.
        Transaction pendingBuy = newTransaction(runner, TransactionType.BUY, quantity, price, reservedAmount);
        strategyRunnerRepository.saveTransaction(pendingBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, reservedAmount));
        markTransactionStale(pendingBuy.getId(), Duration.ofHours(2));

        RecoverStaleTransactionsResponse response = watchdog.runRecoveryCycle();

        // query-before-expire: nao encontrado -> EXPIRED via terminal fallback, sem DLQ.
        Transaction expired = awaitTransactionStatus(pendingBuy.getId(), TransactionStatus.EXPIRED, WAIT_TIMEOUT);
        assertNotNull(expired);
        assertEquals(1, response.scanned());
        assertEquals(1, response.recovered());
        assertEquals(0, response.routedToDlq());
        assertEquals(0, response.failed());
        assertTrue(deadLetterEntryRepository.findUnresolved(portfolioId, runner.getId(), 10).isEmpty(),
                "Zombie not-found must not create a DLQ entry");
    }

    @Test
    void watchdogShouldNotLetConfirmedBacklogStarvePendingCleanup() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        properties.setMaxPerRun(1);

        // Confirmado faltante consome todo o budget de confirmados (=1) e PERMANECE SUBMITTED
        // (vai para DLQ a cada ciclo) — simula um backlog persistente.
        Transaction submittedMissing = newSubmittedTransaction(runner, "EX_BACKLOG_MISSING");
        strategyRunnerRepository.saveTransaction(submittedMissing);
        markTransactionStale(submittedMissing.getId(), Duration.ofHours(2));

        // Reserva orfa PENDING que precisa ser limpa mesmo com o backlog de confirmados.
        BigDecimal quantity = new BigDecimal("0.00150000");
        BigDecimal price = new BigDecimal("60000.00000000");
        BigDecimal reservedAmount = quantity.multiply(price);
        Transaction pendingZombie = newTransaction(runner, TransactionType.BUY, quantity, price, reservedAmount);
        strategyRunnerRepository.saveTransaction(pendingZombie);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, reservedAmount));
        markTransactionStale(pendingZombie.getId(), Duration.ofHours(2));

        RecoverStaleTransactionsResponse response = watchdog.runRecoveryCycle();

        // Budget independente: o PENDING e expirado apesar de o confirmado ter consumido o seu.
        Transaction expired = awaitTransactionStatus(pendingZombie.getId(), TransactionStatus.EXPIRED, WAIT_TIMEOUT);
        assertNotNull(expired);
        Transaction stillSubmitted = strategyRunnerRepository.findTransactionById(submittedMissing.getId())
                .orElseThrow(() -> new IllegalStateException("Submitted not found"));
        assertEquals(TransactionStatus.SUBMITTED, stillSubmitted.getStatus());
        assertEquals(2, response.scanned());
        assertEquals(1, response.routedToDlq());
        assertEquals(1, response.recovered());
    }

    @Test
    void watchdogShouldExpirePendingSellWithoutReleasingUnrelatedReservedCapital() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);

        // Reserva de um BUY nao relacionado (capital legitimamente comprometido).
        BigDecimal unrelatedReserved = new BigDecimal("100.00000000");
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, unrelatedReserved));

        // PENDING SELL orfa: SELLs nao reservam quote (bloqueiam uma Position).
        Transaction pendingSell = newTransaction(runner, TransactionType.SELL,
                new BigDecimal("0.00150000"), new BigDecimal("60000.00000000"), new BigDecimal("90.00000000"));
        strategyRunnerRepository.saveTransaction(pendingSell);
        markTransactionStale(pendingSell.getId(), Duration.ofHours(2));

        RecoverStaleTransactionsResponse response = watchdog.runRecoveryCycle();

        // A SELL orfa e saneada (query-verify -> EXPIRED), mas o release type-aware NAO libera quote:
        // o reservado do BUY alheio fica intacto.
        Transaction expired = awaitTransactionStatus(pendingSell.getId(), TransactionStatus.EXPIRED, WAIT_TIMEOUT);
        assertNotNull(expired);
        assertEquals(1, response.scanned());
        assertEquals(1, response.recovered());
        GlobalBalance balance = globalBalanceRepository.findByPortfolioId(portfolioId)
                .orElseThrow(() -> new IllegalStateException("Balance not found"));
        assertEquals(0, balance.getReservedBalance().compareTo(unrelatedReserved));
    }

    @Test
    void watchdogShouldNotExpirePendingWithinDispatchGrace() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        BigDecimal quantity = new BigDecimal("0.00150000");
        BigDecimal price = new BigDecimal("60000.00000000");
        BigDecimal reservedAmount = quantity.multiply(price);

        Transaction pendingBuy = newTransaction(runner, TransactionType.BUY, quantity, price, reservedAmount);
        strategyRunnerRepository.saveTransaction(pendingBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, reservedAmount));
        // Passa do stale-threshold (30s) mas esta DENTRO da carencia de PENDING (default 10min):
        // o dispatch ainda poderia estar em voo, entao nao pode ser expirado.
        markTransactionStale(pendingBuy.getId(), Duration.ofMinutes(1));

        RecoverStaleTransactionsResponse response = watchdog.runRecoveryCycle();

        Transaction stillPending = strategyRunnerRepository.findTransactionById(pendingBuy.getId())
                .orElseThrow(() -> new IllegalStateException("Transaction not found"));
        assertEquals(TransactionStatus.PENDING, stillPending.getStatus());
        assertEquals(0, response.scanned());
    }

    @Test
    void watchdogShouldReconcilePendingZombieFoundAliveOnExchange() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        BigDecimal quantity = new BigDecimal("0.00150000");
        BigDecimal price = new BigDecimal("60000.00000000");
        BigDecimal reservedAmount = quantity.multiply(price);

        Transaction pendingBuy = newTransaction(runner, TransactionType.BUY, quantity, price, reservedAmount);
        strategyRunnerRepository.saveTransaction(pendingBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, reservedAmount));
        markTransactionStale(pendingBuy.getId(), Duration.ofHours(2));

        getMockExchangeAdapter().seedQueriedOrderSnapshot(new OrderDataDto(
                "EX_PENDING_ALIVE_RUNTIME",
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

        RecoverStaleTransactionsResponse response = watchdog.runRecoveryCycle();

        // ordem viva (ACK perdido) -> reconciliada PENDING->SUBMITTED, sem DLQ, capital comprometido.
        Transaction submitted = awaitTransactionStatus(pendingBuy.getId(), TransactionStatus.SUBMITTED, WAIT_TIMEOUT);
        assertNotNull(submitted);
        assertEquals(1, response.scanned());
        assertEquals(1, response.recovered());
        assertEquals(0, response.routedToDlq());
        assertEquals(0, response.failed());
        assertTrue(deadLetterEntryRepository.findUnresolved(portfolioId, runner.getId(), 10).isEmpty());
    }

    @Test
    void watchdogShouldRecoverStalePartialOrderFoundAsFilled() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        BigDecimal quantity = new BigDecimal("0.00200000");
        BigDecimal requestedPrice = new BigDecimal("50000.00000000");
        BigDecimal reservedAmount = quantity.multiply(requestedPrice);
        BigDecimal partialQuantity = new BigDecimal("0.00080000");
        BigDecimal partialPrice = new BigDecimal("49950.00000000");
        BigDecimal finalPrice = new BigDecimal("50020.00000000");

        Transaction submittedBuy = newTransaction(
                runner,
                TransactionType.BUY,
                quantity,
                requestedPrice,
                reservedAmount
        );
        submittedBuy.submit("EX_RUNTIME_PARTIAL_FILLED");
        strategyRunnerRepository.saveTransaction(submittedBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, reservedAmount));

        conciliationOrderUpdateExecutor.execute(orderData(
                submittedBuy,
                OrderDataDto.OrderStatus.PARTIALLY_FILLED,
                partialQuantity,
                partialPrice,
                BigDecimal.ZERO
        ));
        Transaction partial = awaitTransactionStatus(submittedBuy.getId(), TransactionStatus.PARTIAL, WAIT_TIMEOUT);
        markTransactionStale(partial.getId(), Duration.ofHours(2));

        getMockExchangeAdapter().seedQueriedOrderSnapshot(orderData(
                partial,
                OrderDataDto.OrderStatus.FILLED,
                quantity,
                finalPrice,
                BigDecimal.ZERO
        ));

        RecoverStaleTransactionsResponse response = watchdog.runRecoveryCycle();

        Transaction filled = awaitTransactionStatus(partial.getId(), TransactionStatus.FILLED, WAIT_TIMEOUT);
        assertNotNull(filled);
        assertEquals(1, response.scanned());
        assertEquals(1, response.recovered());
        assertEquals(0, response.routedToDlq());
        assertEquals(0, response.failed());
    }

    @Test
    void watchdogShouldRecoverStaleSubmittedOrderFoundAsCanceled() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        BigDecimal quantity = new BigDecimal("0.00150000");
        BigDecimal requestedPrice = new BigDecimal("60000.00000000");
        BigDecimal reservedAmount = quantity.multiply(requestedPrice);

        Transaction submittedBuy = newTransaction(
                runner,
                TransactionType.BUY,
                quantity,
                requestedPrice,
                reservedAmount
        );
        submittedBuy.submit("EX_RUNTIME_CANCELED");
        strategyRunnerRepository.saveTransaction(submittedBuy);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, reservedAmount));
        markTransactionStale(submittedBuy.getId(), Duration.ofHours(2));

        getMockExchangeAdapter().seedQueriedOrderSnapshot(orderData(
                submittedBuy,
                OrderDataDto.OrderStatus.CANCELED,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ));

        RecoverStaleTransactionsResponse response = watchdog.runRecoveryCycle();

        Transaction canceled = awaitTransactionStatus(submittedBuy.getId(), TransactionStatus.CANCELED, WAIT_TIMEOUT);
        assertNotNull(canceled);
        assertEquals(1, response.scanned());
        assertEquals(1, response.recovered());
        assertEquals(0, response.routedToDlq());
        assertEquals(0, response.failed());
        assertTrue(deadLetterEntryRepository.findUnresolved(portfolioId, runner.getId(), 10).isEmpty());
    }

    @Test
    void watchdogShouldRouteMissingOrderToSingleDlqEntryAcrossRepeatedCycles() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);

        Transaction submittedBuy = newTransaction(
                runner,
                TransactionType.BUY,
                new BigDecimal("0.00200000"),
                new BigDecimal("50000.00000000"),
                new BigDecimal("100.00000000")
        );
        submittedBuy.submit("EX_RUNTIME_MISSING");
        strategyRunnerRepository.saveTransaction(submittedBuy);
        markTransactionStale(submittedBuy.getId(), Duration.ofHours(2));

        RecoverStaleTransactionsResponse first = watchdog.runRecoveryCycle();
        List<DeadLetterEntry> firstEntries = deadLetterEntryRepository.findUnresolved(portfolioId, runner.getId(), 10);

        RecoverStaleTransactionsResponse second = watchdog.runRecoveryCycle();
        List<DeadLetterEntry> secondEntries = deadLetterEntryRepository.findUnresolved(portfolioId, runner.getId(), 10);

        Transaction stillSubmitted = strategyRunnerRepository.findTransactionById(submittedBuy.getId())
                .orElseThrow(() -> new IllegalStateException("Transaction not found after watchdog"));

        assertEquals(TransactionStatus.SUBMITTED, stillSubmitted.getStatus());
        assertEquals(1, first.scanned());
        assertEquals(1, first.routedToDlq());
        assertEquals(1, second.scanned());
        assertEquals(1, second.routedToDlq());
        assertEquals(1, firstEntries.size());
        assertEquals(1, secondEntries.size());
    }

    @Test
    void watchdogShouldSkipCandidateThatBecomesTerminalAfterSelection() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);

        Transaction submitted = newSubmittedTransaction(runner, "EX_RUNTIME_SKIPPED");
        strategyRunnerRepository.saveTransaction(submitted);
        markTransactionStale(submitted.getId(), Duration.ofHours(2));

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<Transaction> candidates = (List<Transaction>) invocation.callRealMethod();
            // O lote consulta confirmados e PENDING em selecoes separadas; so simula a corrida
            // (terminal apos a selecao) quando o candidato confirmado foi de fato selecionado.
            if (!candidates.isEmpty()) {
                Transaction reloaded = strategyRunnerRepository.findTransactionById(submitted.getId())
                        .orElseThrow(() -> new IllegalStateException("Transaction not found before skip simulation"));
                if (reloaded.getStatus() == TransactionStatus.SUBMITTED) {
                    reloaded.expire();
                    strategyRunnerRepository.saveTransaction(reloaded);
                }
            }
            return candidates;
        }).when(strategyRunnerRepository).findByStatusesUpdatedBefore(any(), any(), anyInt());

        RecoverStaleTransactionsResponse response = watchdog.runRecoveryCycle();

        Transaction expired = awaitTransactionStatus(submitted.getId(), TransactionStatus.EXPIRED, WAIT_TIMEOUT);
        assertNotNull(expired);
        assertEquals(1, response.scanned());
        assertEquals(0, response.recovered());
        assertEquals(0, response.routedToDlq());
        assertEquals(1, response.skipped());
        assertEquals(0, response.failed());
    }

    @Test
    void watchdogShouldContinueAfterTransientQueryFailureAndRecoverNextCandidate() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);

        Transaction first = newSubmittedTransaction(runner, "EX_RUNTIME_TRANSIENT_1");
        Transaction second = newSubmittedTransaction(runner, "EX_RUNTIME_TRANSIENT_2");
        strategyRunnerRepository.saveTransaction(first);
        strategyRunnerRepository.saveTransaction(second);
        markTransactionStale(first.getId(), Duration.ofHours(3));
        markTransactionStale(second.getId(), Duration.ofHours(2));

        MockExchangeAdapter mock = getMockExchangeAdapter();
        mock.seedQueriedOrderSnapshot(orderData(
                first,
                OrderDataDto.OrderStatus.FILLED,
                first.getQuantity(),
                first.getPrice(),
                BigDecimal.ZERO
        ));
        mock.seedQueriedOrderSnapshot(orderData(
                second,
                OrderDataDto.OrderStatus.FILLED,
                second.getQuantity(),
                second.getPrice(),
                BigDecimal.ZERO
        ));
        mock.registerQueryFailurePlan(
                first.getClientOrderId(),
                1,
                ExchangeQueryException.ErrorType.TEMPORARY,
                "simulated temporary timeout"
        );

        RecoverStaleTransactionsResponse firstCycle = watchdog.runRecoveryCycle();

        Transaction firstAfterCycle = strategyRunnerRepository.findTransactionById(first.getId())
                .orElseThrow(() -> new IllegalStateException("First transaction not found"));
        Transaction secondAfterCycle = awaitTransactionStatus(second.getId(), TransactionStatus.FILLED, WAIT_TIMEOUT);

        assertEquals(TransactionStatus.SUBMITTED, firstAfterCycle.getStatus());
        assertEquals(TransactionStatus.FILLED, secondAfterCycle.getStatus());
        assertEquals(2, firstCycle.scanned());
        assertEquals(1, firstCycle.recovered());
        assertEquals(1, firstCycle.failed());

        RecoverStaleTransactionsResponse secondCycle = watchdog.runRecoveryCycle();
        Transaction firstRecovered = awaitTransactionStatus(first.getId(), TransactionStatus.FILLED, WAIT_TIMEOUT);

        assertEquals(TransactionStatus.FILLED, firstRecovered.getStatus());
        assertEquals(1, secondCycle.scanned());
        assertEquals(1, secondCycle.recovered());
        assertEquals(0, secondCycle.failed());
    }

    @Test
    void watchdogShouldRespectMaxPerRun() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        properties.setMaxPerRun(1);

        Transaction first = newSubmittedTransaction(runner, "EX_RUNTIME_LIMIT_1");
        Transaction second = newSubmittedTransaction(runner, "EX_RUNTIME_LIMIT_2");
        strategyRunnerRepository.saveTransaction(first);
        strategyRunnerRepository.saveTransaction(second);
        markTransactionStale(first.getId(), Duration.ofHours(3));
        markTransactionStale(second.getId(), Duration.ofHours(2));

        MockExchangeAdapter mock = getMockExchangeAdapter();
        mock.seedQueriedOrderSnapshot(orderData(first, OrderDataDto.OrderStatus.FILLED, first.getQuantity(), first.getPrice(), BigDecimal.ZERO));
        mock.seedQueriedOrderSnapshot(orderData(second, OrderDataDto.OrderStatus.FILLED, second.getQuantity(), second.getPrice(), BigDecimal.ZERO));

        RecoverStaleTransactionsResponse response = watchdog.runRecoveryCycle();

        Transaction firstAfterCycle = awaitTransactionStatus(first.getId(), TransactionStatus.FILLED, WAIT_TIMEOUT);
        Transaction secondAfterCycle = strategyRunnerRepository.findTransactionById(second.getId())
                .orElseThrow(() -> new IllegalStateException("Second transaction not found"));

        assertEquals(1, response.scanned());
        assertEquals(1, response.recovered());
        assertEquals(TransactionStatus.FILLED, firstAfterCycle.getStatus());
        assertEquals(TransactionStatus.SUBMITTED, secondAfterCycle.getStatus());
    }

    @Test
    void watchdogShouldDoNothingWhenDisabled() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        properties.setEnabled(false);

        Transaction submitted = newSubmittedTransaction(runner, "EX_RUNTIME_DISABLED");
        strategyRunnerRepository.saveTransaction(submitted);
        markTransactionStale(submitted.getId(), Duration.ofHours(2));

        getMockExchangeAdapter().seedQueriedOrderSnapshot(orderData(
                submitted,
                OrderDataDto.OrderStatus.FILLED,
                submitted.getQuantity(),
                submitted.getPrice(),
                BigDecimal.ZERO
        ));

        RecoverStaleTransactionsResponse response = watchdog.runRecoveryCycle();
        Transaction afterCycle = strategyRunnerRepository.findTransactionById(submitted.getId())
                .orElseThrow(() -> new IllegalStateException("Transaction not found after disabled watchdog"));

        assertEquals(0, response.scanned());
        assertEquals(0, response.recovered());
        assertEquals(TransactionStatus.SUBMITTED, afterCycle.getStatus());
        assertTrue(deadLetterEntryRepository.findUnresolved(portfolioId, runner.getId(), 10).isEmpty());
    }

    private UUID createPortfolio() {
        CreatePortfolioResponse response = createPortfolioPort.execute(
                CreatePortfolioRequest.builder()
                        .name("runtime-recovery-" + UUID.randomUUID())
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

    private Transaction newSubmittedTransaction(StrategyRunner runner, String exchangeOrderId) {
        Transaction tx = newTransaction(
                runner,
                TransactionType.BUY,
                new BigDecimal("0.00100000"),
                new BigDecimal("50000.00000000"),
                new BigDecimal("50.00000000")
        );
        tx.submit(exchangeOrderId);
        return tx;
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
                "runtime recovery watchdog test",
                null, Map.of()
        );
    }

    private void markTransactionStale(UUID transactionId, Duration age) {
        Instant staleAt = Instant.now().minus(age);
        jdbcTemplate.update(
                "UPDATE transactions SET updated_at = ? WHERE id = ?",
                Timestamp.from(staleAt),
                transactionId
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting async processing", e);
        }
    }
}
