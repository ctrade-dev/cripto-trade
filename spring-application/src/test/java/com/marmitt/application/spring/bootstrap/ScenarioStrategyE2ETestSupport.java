package com.marmitt.application.spring.bootstrap;

import com.marmitt.core.domain.runner.RunnerStatusCause;
import com.marmitt.application.spring.config.exchange.MockExchangeAdapter;
import com.marmitt.application.spring.testsupport.DatabaseCleaner;
import com.marmitt.core.domain.Symbol;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.dto.portfolio.request.CreatePortfolioRequest;
import com.marmitt.core.dto.portfolio.response.CreatePortfolioResponse;
import com.marmitt.core.dto.runner.request.CreateRunnerRequest;
import com.marmitt.core.dto.runner.response.CreateRunnerResponse;
import com.marmitt.core.dto.websocket.data.MarketDataDto;
import com.marmitt.core.ports.inbound.portfolio.CreatePortfolioPort;
import com.marmitt.core.ports.inbound.runner.CreateRunnerPort;
import com.marmitt.core.ports.inbound.runner.ProcessTradeSignalPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import com.marmitt.core.ports.outbound.strategy.TradingStrategy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Harness comum dos e2e das estrategias de cenario (T38).
 *
 * <p>Cada teste registra uma instancia propria da estrategia com teto de ciclos, ativa um runner
 * apontando para ela, dirige ticks e assere o estado final mais a ausencia de efeito extra.
 */
abstract class ScenarioStrategyE2ETestSupport extends AbstractIntegrationTest {

    protected static final String SYMBOL = "BTCUSDT";
    protected static final String MOCK_EXCHANGE = "MOCK";
    protected static final String MARKET_DATA_EXCHANGE = "BINANCE";
    protected static final BigDecimal INITIAL_CAPITAL = new BigDecimal("10000.00");
    protected static final BigDecimal MARKET_PRICE = new BigDecimal("65000.00");
    protected static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);
    /** Janela usada para provar ausencia de efeito extra depois que a estrategia holda. */
    protected static final Duration QUIET_WINDOW = Duration.ofSeconds(2);

    @Autowired
    protected CreatePortfolioPort createPortfolioPort;

    @Autowired
    protected CreateRunnerPort createRunnerPort;

    @Autowired
    protected ProcessTradeSignalPort processTradeSignalPort;

    @Autowired
    protected StrategyRunnerRepositoryPort strategyRunnerRepository;

    @Autowired
    protected StrategyRepositoryPort strategyRepository;

    @Autowired
    protected ExchangeAdapterRepositoryPort exchangeAdapterRepository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected MeterRegistry meterRegistry;

    protected MockExchangeAdapter mockAdapter;

    @BeforeEach
    void resetMockAndDatabase() {
        mockAdapter = exchangeAdapterRepository
                .findAdapter(MOCK_EXCHANGE)
                .map(descriptor -> descriptor.streaming())
                .filter(MockExchangeAdapter.class::isInstance)
                .map(MockExchangeAdapter.class::cast)
                .orElseThrow(() -> new IllegalStateException("MOCK adapter not registered"));
        mockAdapter.reset();
        DatabaseCleaner.reset(jdbcTemplate);
    }

    // ---------------------------------------------------------------- setup

    protected UUID createPortfolio() {
        CreatePortfolioResponse response = createPortfolioPort.execute(
                CreatePortfolioRequest.builder()
                        .name("t38-" + UUID.randomUUID())
                        .initialCapitalAmount(INITIAL_CAPITAL)
                        .currency("USDT")
                        .build()
        );
        assertNotNull(response.portfolioId(), "Portfolio creation failed: " + response.message());
        return response.portfolioId();
    }

    protected UUID createAndActivateRunner(UUID portfolioId, UUID strategyId) {
        CreateRunnerResponse response = createRunnerPort.execute(
                CreateRunnerRequest.builder()
                        .portfolioId(portfolioId)
                        .strategyId(strategyId)
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
        return runner.getId();
    }

    /** Registra a instancia da estrategia e devolve o runner ja ativo apontando para ela. */
    protected UUID activateScenario(TradingStrategy strategy) {
        strategyRepository.registerStrategy(strategy);
        return createAndActivateRunner(createPortfolio(), strategy.getStrategyId());
    }

    // ---------------------------------------------------------------- ticks

    /**
     * Publica um tick no pipeline de sinal e alinha o preco de referencia do mock ao mesmo valor.
     *
     * <p>As duas coisas juntas por design: se a estrategia enxergasse um preco e a exchange outro, a
     * decisao de marketable-vs-resting do mock deixaria de corresponder a intencao da estrategia.
     */
    protected void publishTick(BigDecimal price) {
        publishTick(price, price);
    }

    /**
     * Tick com a exchange num preco diferente do visto pelo pipeline (mercado que ja andou alem do
     * tick), para provar ordens que descansam porque o livro saiu do lugar.
     */
    protected void publishTick(BigDecimal price, BigDecimal exchangePrice) {
        mockAdapter.seedReferencePrice(SYMBOL, exchangePrice);
        processTradeSignalPort.execute(new MarketDataDto(
                MARKET_DATA_EXCHANGE,
                Symbol.of(SYMBOL),
                price,
                price,
                price,
                new BigDecimal("1000"),
                price,
                price,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Instant.now()
        ));
    }

    /** Dirige {@code count} ticks ao preco de mercado padrao, espacados para o pipeline reagir. */
    protected void driveTicks(int count) {
        for (int i = 0; i < count; i++) {
            publishTick(MARKET_PRICE);
            sleep(150);
        }
    }

    // ---------------------------------------------------------------- estado

    protected int transactionCount(UUID runnerId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE runner_id = ?", Integer.class, runnerId);
        return count == null ? 0 : count;
    }

    protected List<TransactionRow> transactions(UUID runnerId) {
        return jdbcTemplate.query(
                """
                        SELECT id, type, status, quantity, executed_quantity, price, executed_price
                          FROM transactions
                         WHERE runner_id = ?
                         ORDER BY requested_at ASC
                        """,
                (rs, rowNum) -> new TransactionRow(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("type"),
                        rs.getString("status"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("executed_quantity"),
                        rs.getBigDecimal("price"),
                        rs.getBigDecimal("executed_price")
                ),
                runnerId
        );
    }

    protected List<TransactionRow> transactionsOfType(UUID runnerId, String type) {
        return transactions(runnerId).stream().filter(tx -> type.equals(tx.type())).toList();
    }

    protected int positionCount(UUID runnerId, String status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM positions WHERE runner_id = ? AND status = ?",
                Integer.class, runnerId, status);
        return count == null ? 0 : count;
    }

    /** {@code true} se existe posicao do runner ainda travada por alguma transacao de venda. */
    protected boolean hasLockedPosition(UUID runnerId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM positions WHERE runner_id = ? AND locked_by_transaction_id IS NOT NULL",
                Integer.class, runnerId);
        return count != null && count > 0;
    }

    protected int matchCount(UUID runnerId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transaction_matches WHERE runner_id = ?", Integer.class, runnerId);
        return count == null ? 0 : count;
    }

    protected BalanceRow balance(UUID portfolioId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT available_balance, reserved_balance, realized_balance
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
    }

    protected UUID portfolioIdOf(UUID runnerId) {
        return jdbcTemplate.queryForObject(
                "SELECT portfolio_id FROM strategy_runners WHERE id = ?",
                (rs, rowNum) -> UUID.fromString(rs.getString("portfolio_id")),
                runnerId
        );
    }

    protected double signalEvaluatedCount(UUID runnerId, String decision) {
        try {
            return meterRegistry.get("signal.evaluated.total")
                    .tags("runnerId", runnerId.toString(), "decision", decision)
                    .counter()
                    .count();
        } catch (MeterNotFoundException e) {
            return 0.0;
        }
    }

    // ---------------------------------------------------------------- espera

    protected void awaitCondition(BooleanSupplier condition, Duration timeout, String what) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(80);
        }
        fail("Timeout esperando " + what);
    }

    /** Espera com o estado do runner anexado ao erro, para o timeout dizer o que de fato aconteceu. */
    protected void awaitCondition(BooleanSupplier condition, Duration timeout, String what, UUID runnerId) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(80);
        }
        fail("Timeout esperando " + what + System.lineSeparator() + stateDump(runnerId));
    }

    protected String stateDump(UUID runnerId) {
        StringBuilder dump = new StringBuilder("--- estado do runner ").append(runnerId).append(System.lineSeparator());
        dump.append("transacoes:").append(System.lineSeparator());
        for (TransactionRow tx : transactions(runnerId)) {
            dump.append("  ").append(tx.type()).append(' ').append(tx.status())
                    .append(" qty=").append(tx.quantity())
                    .append(" exec=").append(tx.executedQuantity())
                    .append(" price=").append(tx.price())
                    .append(" execPrice=").append(tx.executedPrice())
                    .append(System.lineSeparator());
        }
        dump.append("posicoes:").append(System.lineSeparator());
        List<String> positions = jdbcTemplate.query(
                """
                        SELECT status, quantity, locked_quantity, locked_by_transaction_id
                          FROM positions
                         WHERE runner_id = ?
                        """,
                (rs, rowNum) -> "  " + rs.getString("status")
                        + " qty=" + rs.getBigDecimal("quantity")
                        + " lockedQty=" + rs.getBigDecimal("locked_quantity")
                        + " lockedBy=" + rs.getString("locked_by_transaction_id"),
                runnerId
        );
        positions.forEach(row -> dump.append(row).append(System.lineSeparator()));
        dump.append("matches=").append(matchCount(runnerId)).append(System.lineSeparator());
        Integer deadLetters = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dead_letter_entries WHERE runner_id = ?", Integer.class, runnerId);
        dump.append("deadLetters=").append(deadLetters).append(System.lineSeparator());
        dump.append("outcomes:");
        for (String decision : List.of("HOLD", "BUY", "SELL", "CANCEL",
                "REJECTED_CAPITAL", "REJECTED_LOCK", "REJECTED_NO_POSITION", "REJECTED_POLICY",
                "REJECTED_PRICE_DEVIATION", "REJECTED_MAX_POSITIONS", "REJECTED_MAX_PENDING", "DISPATCH_BLOCKED",
                "SAFE_MODE", "STRATEGY_UNAVAILABLE", "PROTECTIVE_EXIT")) {
            double count = signalEvaluatedCount(runnerId, decision);
            if (count > 0) {
                dump.append(' ').append(decision).append('=').append(count);
            }
        }
        return dump.toString();
    }

    /**
     * Espera o pipeline parar de se mover: mesma contagem de transacoes, matches e posicoes por
     * uma janela inteira.
     *
     * <p>Necessario porque uma ordem produz varios eventos de fill (parciais + final) e o mock
     * ainda emite duplicatas e eventos fora de ordem. Capturar estado logo apos o primeiro sinal
     * de conclusao leria um instante intermediario.
     */
    protected void awaitStable(UUID runnerId, Duration window) {
        Instant deadline = Instant.now().plus(WAIT_TIMEOUT);
        String lastSnapshot = null;
        Instant stableSince = null;
        while (Instant.now().isBefore(deadline)) {
            String snapshot = transactionCount(runnerId) + "/" + matchCount(runnerId)
                    + "/" + positionCount(runnerId, "OPEN") + "/" + positionCount(runnerId, "CLOSED")
                    + "/" + positionCount(runnerId, "CLOSING");
            if (snapshot.equals(lastSnapshot)) {
                if (stableSince != null && Duration.between(stableSince, Instant.now()).compareTo(window) >= 0) {
                    return;
                }
            } else {
                lastSnapshot = snapshot;
                stableSince = Instant.now();
            }
            sleep(100);
        }
        fail("Timeout esperando o estado estabilizar" + System.lineSeparator() + stateDump(runnerId));
    }

    protected BigDecimal totalMatchedQuantity(UUID runnerId) {
        BigDecimal total = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(matched_quantity), 0) FROM transaction_matches WHERE runner_id = ?",
                BigDecimal.class, runnerId);
        return total == null ? BigDecimal.ZERO : total;
    }

    /**
     * Prova a ausencia de excesso: dirige mais ticks depois do teto e assere que transacoes,
     * posicoes e saldo nao se moveram.
     */
    protected void assertNoFurtherEffect(UUID runnerId, UUID portfolioId, int extraTicks) {
        int transactionsBefore = transactionCount(runnerId);
        int openPositionsBefore = positionCount(runnerId, "OPEN");
        int matchesBefore = matchCount(runnerId);
        BalanceRow balanceBefore = balance(portfolioId);

        driveTicks(extraTicks);
        sleep(QUIET_WINDOW.toMillis());

        assertEquals(transactionsBefore, transactionCount(runnerId),
                "Ticks apos o teto de ciclos nao podem criar novas transacoes");
        assertEquals(openPositionsBefore, positionCount(runnerId, "OPEN"),
                "Ticks apos o teto de ciclos nao podem abrir posicao");
        assertEquals(matchesBefore, matchCount(runnerId),
                "Ticks apos o teto de ciclos nao podem gerar novo match");
        BalanceRow balanceAfter = balance(portfolioId);
        assertEquals(0, balanceBefore.available().compareTo(balanceAfter.available()),
                "Saldo disponivel nao pode se mover apos o teto de ciclos");
        assertEquals(0, balanceBefore.reserved().compareTo(balanceAfter.reserved()),
                "Saldo reservado nao pode se mover apos o teto de ciclos");
    }

    protected static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrompido aguardando processamento assincrono", e);
        }
    }

    protected record TransactionRow(UUID id,
                                    String type,
                                    String status,
                                    BigDecimal quantity,
                                    BigDecimal executedQuantity,
                                    BigDecimal price,
                                    BigDecimal executedPrice) {
    }

    protected record BalanceRow(BigDecimal available,
                                BigDecimal reserved,
                                BigDecimal realized) {
    }
}
