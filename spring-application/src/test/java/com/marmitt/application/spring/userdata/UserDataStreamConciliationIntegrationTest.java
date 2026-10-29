package com.marmitt.application.spring.userdata;

import com.marmitt.application.spring.testsupport.strategy.sma.SmaTestStrategyConfiguration;
import com.marmitt.core.domain.runner.RunnerStatusCause;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marmitt.application.spring.CTradeApplication;
import com.marmitt.application.spring.testsupport.DatabaseCleaner;
import com.marmitt.binance.processor.receive.BinanceUserDataProcessor;
import com.marmitt.core.domain.runner.ClientOrderId;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.portfolio.request.CreatePortfolioRequest;
import com.marmitt.core.dto.portfolio.response.CreatePortfolioResponse;
import com.marmitt.core.dto.runner.request.CreateRunnerRequest;
import com.marmitt.core.dto.runner.response.CreateRunnerResponse;
import com.marmitt.core.dto.websocket.MessageContext;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.enums.TransactionType;
import com.marmitt.core.domain.Symbol;
import com.marmitt.core.dto.websocket.data.OrderDataDto;
import com.marmitt.core.ports.inbound.handler.HandlerProcessUserMessagePort;
import com.marmitt.core.ports.inbound.portfolio.CreatePortfolioPort;
import com.marmitt.core.ports.inbound.runner.CreateRunnerPort;
import com.marmitt.core.ports.inbound.runner.OrderConciliationPort;
import com.marmitt.core.ports.outbound.exchange.adapter.ReceivedMessageProcessorPort;
import com.marmitt.core.ports.outbound.events.EventPublisherPort;
import com.marmitt.core.ports.outbound.exchange.streaming.ExchangeUserStreamPort;
import com.marmitt.core.ports.outbound.exchange.streaming.UserStreamSessionPort;
import com.marmitt.core.ports.outbound.repository.GlobalBalanceRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import com.marmitt.core.ports.outbound.websocket.WebSocketPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(classes = CTradeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({UserDataStreamConciliationIntegrationTest.MockUserStreamConfig.class, SmaTestStrategyConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class UserDataStreamConciliationIntegrationTest {

    static final UUID SMA_STRATEGY_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    static final String EXCHANGE = "MOCK";
    static final String SYMBOL = "BTCUSDT";
    static final BigDecimal INITIAL_CAPITAL = new BigDecimal("10000.00");
    static final BigDecimal QUANTITY = new BigDecimal("1.00000000");
    static final BigDecimal PRICE = new BigDecimal("100.00000000");
    static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ctrade").withUsername("ctrade").withPassword("ctrade123");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.enabled", () -> "true");
        r.add("runner.boot.orchestrator-enabled", () -> "false");
    }

    @Autowired HandlerProcessUserMessagePort processUserMessage;
    @Autowired OrderConciliationPort orderConciliation;
    @Autowired CreatePortfolioPort createPortfolioPort;
    @Autowired CreateRunnerPort createRunnerPort;
    @Autowired StrategyRunnerRepositoryPort strategyRunnerRepository;
    @Autowired GlobalBalanceRepositoryPort globalBalanceRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        DatabaseCleaner.reset(jdbcTemplate);
    }

    // ─── cenários de aceitação ────────────────────────────────────────────

    @Test
    void filledBuyOrder_shouldTransitionToFilledAndCreateOpenPosition() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        String clientOrderId = ClientOrderId.generate(runner.getShortCode(), TransactionType.BUY);
        BigDecimal cost = QUANTITY.multiply(PRICE);
        Transaction tx = seedBuyTransaction(runner, clientOrderId, cost);
        globalBalanceRepository.reserveAtomic(portfolioId, cost);

        processUserMessage.execute(
                executionReport(clientOrderId, "FILLED", "BUY", QUANTITY, QUANTITY, cost, null),
                MessageContext.createUserData(EXCHANGE, UUID.randomUUID()));

        awaitTransactionStatus(tx.getId(), TransactionStatus.FILLED);
        assertEquals(1, countOpenPositions(tx.getId()), "FILLED BUY must open exactly one position");
    }

    @Test
    void partiallyFilledBuyOrder_shouldUpdateExecutedQuantityAndOpenPartialPosition() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        String clientOrderId = ClientOrderId.generate(runner.getShortCode(), TransactionType.BUY);
        BigDecimal cost = QUANTITY.multiply(PRICE);
        BigDecimal partialQty = new BigDecimal("0.50000000");
        BigDecimal partialQuote = partialQty.multiply(PRICE);
        Transaction tx = seedBuyTransaction(runner, clientOrderId, cost);
        globalBalanceRepository.reserveAtomic(portfolioId, cost);

        processUserMessage.execute(
                executionReport(clientOrderId, "PARTIALLY_FILLED", "BUY", QUANTITY, partialQty, partialQuote, null),
                MessageContext.createUserData(EXCHANGE, UUID.randomUUID()));

        Transaction updated = awaitCondition(TIMEOUT, 100,
                () -> strategyRunnerRepository.findTransactionById(tx.getId()).orElse(null),
                t -> t != null
                        && t.getStatus() == TransactionStatus.PARTIAL
                        && partialQty.compareTo(t.getEffectiveExecutedQuantity()) == 0,
                "executed_qty must equal the cumulative partial qty " + partialQty);

        assertEquals(TransactionStatus.PARTIAL, updated.getStatus(),
                "PARTIALLY_FILLED BUY must transition to PARTIAL");
        assertEquals(1, countOpenPositions(tx.getId()),
                "PARTIALLY_FILLED BUY must open exactly one partial position");
        assertEquals(0, partialQty.compareTo(readOpenPositionQuantity(tx.getId())),
                "partial BUY must update the open position quantity by the executed delta");
        awaitCondition(TIMEOUT, 100,
                () -> readReservedBalance(portfolioId),
                reserved -> reserved.compareTo(BigDecimal.ZERO) > 0,
                "capital reservation must remain locked after PARTIALLY_FILLED");
    }

    @Test
    void canceledBuyOrder_shouldTransitionToCanceledAndReleaseCapitalReservation() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        String clientOrderId = ClientOrderId.generate(runner.getShortCode(), TransactionType.BUY);
        BigDecimal cost = QUANTITY.multiply(PRICE);
        Transaction tx = seedBuyTransaction(runner, clientOrderId, cost);
        globalBalanceRepository.reserveAtomic(portfolioId, cost);

        processUserMessage.execute(
                executionReport(clientOrderId, "CANCELED", "BUY", QUANTITY, BigDecimal.ZERO, BigDecimal.ZERO, null),
                MessageContext.createUserData(EXCHANGE, UUID.randomUUID()));

        awaitTransactionStatus(tx.getId(), TransactionStatus.CANCELED);
        assertEquals(0, countOpenPositions(tx.getId()), "CANCELED BUY must not leave any open position");
        awaitCondition(TIMEOUT, 100,
                () -> readReservedBalance(portfolioId),
                r -> r.compareTo(BigDecimal.ZERO) == 0,
                "capital reservation must be fully released after CANCELED");
    }

    @Test
    void rejectedBuyOrder_shouldTransitionToRejectedAndPreserveRejectReason() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        String clientOrderId = ClientOrderId.generate(runner.getShortCode(), TransactionType.BUY);
        BigDecimal cost = QUANTITY.multiply(PRICE);
        Transaction tx = seedBuyTransaction(runner, clientOrderId, cost);
        globalBalanceRepository.reserveAtomic(portfolioId, cost);

        processUserMessage.execute(
                executionReport(clientOrderId, "REJECTED", "BUY", QUANTITY, BigDecimal.ZERO, BigDecimal.ZERO, "INSUFFICIENT_BALANCE"),
                MessageContext.createUserData(EXCHANGE, UUID.randomUUID()));

        awaitTransactionStatus(tx.getId(), TransactionStatus.REJECTED);
        assertEquals(0, countOpenPositions(tx.getId()), "REJECTED BUY must not leave any open position");
        String rejectReason = jdbcTemplate.queryForObject(
                "SELECT reject_reason FROM transactions WHERE id = ?", String.class, tx.getId());
        assertEquals("INSUFFICIENT_BALANCE", rejectReason, "reject_reason must be persisted from the event");
        awaitCondition(TIMEOUT, 100,
                () -> readReservedBalance(portfolioId),
                r -> r.compareTo(BigDecimal.ZERO) == 0,
                "capital reservation must be released after REJECTED");
    }

    @Test
    void duplicateFilledEvent_shouldNotCreateDuplicatePositionOrMatch() {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        String clientOrderId = ClientOrderId.generate(runner.getShortCode(), TransactionType.BUY);
        BigDecimal cost = QUANTITY.multiply(PRICE);
        Transaction tx = seedBuyTransaction(runner, clientOrderId, cost);
        globalBalanceRepository.reserveAtomic(portfolioId, cost);

        String json = executionReport(clientOrderId, "FILLED", "BUY", QUANTITY, QUANTITY, cost, null);
        MessageContext ctx = MessageContext.createUserData(EXCHANGE, UUID.randomUUID());

        processUserMessage.execute(json, ctx);
        awaitTransactionStatus(tx.getId(), TransactionStatus.FILLED);

        processUserMessage.execute(json, ctx); // entrega duplicada
        sleep(300);

        assertEquals(1, countOpenPositions(tx.getId()),
                "duplicate FILLED must not open a second position");
        assertEquals(0, countTransactionMatches(tx.getId()),
                "BUY FILLED has no matches yet (match happens on SELL fill)");
    }

    @Test
    void concurrentNewPartiallyFilledFilled_shouldConvergeToFilledWithSinglePosition() throws Exception {
        UUID portfolioId = createPortfolio();
        StrategyRunner runner = createAndActivateRunner(portfolioId);
        String clientOrderId = ClientOrderId.generate(runner.getShortCode(), TransactionType.BUY);
        BigDecimal cost = QUANTITY.multiply(PRICE);
        BigDecimal partialQty = new BigDecimal("0.50000000");
        Transaction tx = seedBuyTransaction(runner, clientOrderId, cost);
        globalBalanceRepository.reserveAtomic(portfolioId, cost);

        Symbol symbol = Symbol.of(SYMBOL);
        Instant now = Instant.now();
        OrderDataDto newDto = new OrderDataDto("9876543", clientOrderId, symbol,
                OrderDataDto.OrderSide.BUY, OrderDataDto.OrderType.LIMIT,
                QUANTITY, BigDecimal.ZERO, PRICE, BigDecimal.ZERO, BigDecimal.ZERO,
                OrderDataDto.OrderStatus.NEW, null, now,
                null);
        OrderDataDto partialDto = new OrderDataDto("9876543", clientOrderId, symbol,
                OrderDataDto.OrderSide.BUY, OrderDataDto.OrderType.LIMIT,
                QUANTITY, partialQty, PRICE, PRICE, BigDecimal.ZERO,
                OrderDataDto.OrderStatus.PARTIALLY_FILLED, null, now,
                null);
        OrderDataDto filledDto = new OrderDataDto("9876543", clientOrderId, symbol,
                OrderDataDto.OrderSide.BUY, OrderDataDto.OrderType.LIMIT,
                QUANTITY, QUANTITY, PRICE, PRICE, BigDecimal.ZERO,
                OrderDataDto.OrderStatus.FILLED, null, now,
                null);

        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            List<Future<?>> futures = List.of(
                    executor.submit(() -> { ready.countDown(); start.await(); orderConciliation.execute(newDto);     return null; }),
                    executor.submit(() -> { ready.countDown(); start.await(); orderConciliation.execute(partialDto); return null; }),
                    executor.submit(() -> { ready.countDown(); start.await(); orderConciliation.execute(filledDto);  return null; })
            );
            assertTrue(ready.await(5, TimeUnit.SECONDS), "threads did not reach ready state");
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
        }

        awaitTransactionStatus(tx.getId(), TransactionStatus.FILLED);
        assertEquals(1, countOpenPositions(tx.getId()),
                "concurrent burst must produce exactly one open position");
        assertEquals(0, QUANTITY.compareTo(readOpenPositionQuantity(tx.getId())),
                "position quantity must equal the full executed quantity after concurrent burst");
    }

    @Test
    void unknownClientOrderId_shouldBeDiscardedWithoutException() {
        assertDoesNotThrow(() -> processUserMessage.execute(
                executionReport("UNKNOWN-ORDER-ID-9999", "FILLED", "BUY",
                        QUANTITY, QUANTITY, QUANTITY.multiply(PRICE), null),
                MessageContext.createUserData(EXCHANGE, UUID.randomUUID())),
                "unknown clientOrderId must be silently discarded");
    }

    // ─── suporte ─────────────────────────────────────────────────────────

    private UUID createPortfolio() {
        CreatePortfolioResponse r = createPortfolioPort.execute(
                CreatePortfolioRequest.builder()
                        .name("ud-test-" + UUID.randomUUID())
                        .initialCapitalAmount(INITIAL_CAPITAL)
                        .currency("USDT")
                        .build());
        assertNotNull(r.portfolioId(), "portfolio creation failed: " + r.message());
        return r.portfolioId();
    }

    private StrategyRunner createAndActivateRunner(UUID portfolioId) {
        CreateRunnerResponse r = createRunnerPort.execute(
                CreateRunnerRequest.builder()
                        .portfolioId(portfolioId)
                        .strategyId(SMA_STRATEGY_ID)
                        .symbol(SYMBOL)
                        .exchangeName(EXCHANGE)
                        .allowedMarketDataSources(Set.of("BINANCE"))
                        .build());
        assertNotNull(r.runnerId(), "runner creation failed: " + r.message());
        StrategyRunner runner = strategyRunnerRepository.findById(r.runnerId()).orElseThrow();
        runner.startInitializing(RunnerStatusCause.manual("test"));
        runner.activate(RunnerStatusCause.manual("test"));
        strategyRunnerRepository.save(runner);
        return runner;
    }

    private Transaction seedBuyTransaction(StrategyRunner runner, String clientOrderId, BigDecimal cost) {
        Transaction tx = new Transaction(
                runner.getId(), clientOrderId, TransactionType.BUY, OrderType.LIMIT, SYMBOL,
                QUANTITY, PRICE, cost, new BigDecimal("0.90"), "user-data-stream-test", null);
        strategyRunnerRepository.saveTransaction(tx);
        return tx;
    }

    private String executionReport(String clientOrderId, String status, String side,
                                    BigDecimal quantity, BigDecimal executedQty,
                                    BigDecimal cumulativeQuoteQty, String rejectReason) {
        return """
                {
                  "e": "executionReport",
                  "s": "%s",
                  "c": "%s",
                  "S": "%s",
                  "o": "LIMIT",
                  "X": "%s",
                  "q": "%s",
                  "z": "%s",
                  "Z": "%s",
                  "L": "0",
                  "n": "0.00000000",
                  "N": "BNB",
                  "r": "%s",
                  "i": "9876543",
                  "T": %d
                }
                """.formatted(
                SYMBOL, clientOrderId, side, status,
                quantity.toPlainString(), executedQty.toPlainString(),
                cumulativeQuoteQty.toPlainString(),
                rejectReason != null ? rejectReason : "NONE",
                Instant.now().toEpochMilli());
    }

    private void awaitTransactionStatus(UUID transactionId, TransactionStatus expected) {
        awaitCondition(TIMEOUT, 100,
                () -> strategyRunnerRepository.findTransactionById(transactionId).orElse(null),
                t -> t != null && t.getStatus() == expected,
                "transaction " + transactionId + " did not reach status " + expected);
    }

    private int countOpenPositions(UUID openedByTransactionId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM positions WHERE opened_by_transaction_id = ? AND status = 'OPEN'",
                Integer.class, openedByTransactionId);
        return n == null ? 0 : n;
    }

    private int countTransactionMatches(UUID transactionId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transaction_matches WHERE buy_transaction_id = ? OR sell_transaction_id = ?",
                Integer.class, transactionId, transactionId);
        return n == null ? 0 : n;
    }

    private BigDecimal readReservedBalance(UUID portfolioId) {
        BigDecimal v = jdbcTemplate.queryForObject(
                "SELECT reserved_balance FROM global_balances WHERE portfolio_id = ?",
                BigDecimal.class, portfolioId);
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal readOpenPositionQuantity(UUID openedByTransactionId) {
        BigDecimal v = jdbcTemplate.queryForObject(
                "SELECT quantity FROM positions WHERE opened_by_transaction_id = ? AND status = 'OPEN'",
                BigDecimal.class, openedByTransactionId);
        return v == null ? BigDecimal.ZERO : v;
    }

    private <T> T awaitCondition(Duration timeout, long pollMs, Supplier<T> supply,
                                  Predicate<T> predicate, String message) {
        Instant deadline = Instant.now().plus(timeout);
        T last = null;
        while (Instant.now().isBefore(deadline)) {
            last = supply.get();
            if (predicate.test(last)) return last;
            sleep(pollMs);
        }
        throw new AssertionError(message + " (last=" + last + ")");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting async processing", e);
        }
    }

    // ─── configuração de teste ────────────────────────────────────────────

    @TestConfiguration
    static class MockUserStreamConfig {

        @Bean
        ExchangeUserStreamPort mockExchangeUserStreamPort(ObjectMapper objectMapper,
                                                          EventPublisherPort eventPublisher) {
            BinanceUserDataProcessor processor = new BinanceUserDataProcessor(objectMapper, eventPublisher);
            return new ExchangeUserStreamPort() {
                @Override public String getExchangeName() { return "MOCK"; }
                @Override public com.marmitt.core.dto.processing.ProcessingResult<? extends com.marmitt.core.dto.websocket.data.ProcessorResponse> processMessage(String rawMessage, MessageContext context) {
                    return processor.processMessage(rawMessage, context);
                }
            };
        }

        @Bean
        UserStreamSessionPort mockUserStreamSession() {
            return new UserStreamSessionPort() {
                @Override public String getExchangeName() { return "MOCK"; }
                @Override public com.marmitt.core.ports.outbound.exchange.streaming.UserStreamSession createSession(java.util.UUID connectionId) {
                    return new com.marmitt.core.ports.outbound.exchange.streaming.UserStreamSession() {
                        @Override public String open() { return "mock://localhost"; }
                        @Override public void close() {}
                    };
                }
            };
        }
    }
}
