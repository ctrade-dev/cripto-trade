package com.marmitt.core.application.usecase.operations;

import com.marmitt.core.application.usecase.exchange.ExchangeBalanceReconciliation;
import com.marmitt.core.domain.portfolio.GlobalBalance;
import com.marmitt.core.domain.portfolio.Portfolio;
import com.marmitt.core.domain.runner.Position;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.operations.ExchangeComparison;
import com.marmitt.core.dto.operations.OperationalCheck;
import com.marmitt.core.dto.operations.OperationalSeverity;
import com.marmitt.core.dto.operations.OperationalStateReport;
import com.marmitt.core.dto.exchange.ExchangeBalanceReport;
import com.marmitt.core.enums.ExchangeBalanceStatus;
import com.marmitt.core.dto.reconciliation.ReconciliationReportDto;
import com.marmitt.core.dto.reconciliation.ReconciliationRequest;
import com.marmitt.core.dto.reconciliation.ReconciliationSummaryDto;
import com.marmitt.core.enums.BootRunStatus;
import com.marmitt.core.enums.RunnerStatus;
import com.marmitt.core.enums.SafeModeOrigin;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.enums.TransactionType;
import com.marmitt.core.ports.inbound.reconciliation.ReconcileTradesPort;
import com.marmitt.core.ports.outbound.exchange.ExchangeAdapterDescriptor;
import com.marmitt.core.ports.outbound.marketdata.LatestPricePort;
import com.marmitt.core.ports.outbound.operations.InfrastructureSignalsPort;
import com.marmitt.core.ports.outbound.operations.InfrastructureSignalsPort.BootState;
import com.marmitt.core.ports.outbound.repository.DeadLetterEntryRepositoryPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.GlobalBalanceRepositoryPort;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import com.marmitt.core.ports.outbound.repository.RemoteStrategyCatalogRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;
import com.marmitt.core.ports.outbound.strategy.TradingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T55: verificacoes do estado operacional consolidado — cada uma com o caso que dispara e o que nao dispara.
 */
class InspectOperationalStateUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private final PortfolioRepositoryPort portfolios = mock(PortfolioRepositoryPort.class);
    private final GlobalBalanceRepositoryPort balances = mock(GlobalBalanceRepositoryPort.class);
    private final StrategyRunnerRepositoryPort runners = mock(StrategyRunnerRepositoryPort.class);
    private final DeadLetterEntryRepositoryPort deadLetters = mock(DeadLetterEntryRepositoryPort.class);
    private final StrategyRepositoryPort strategies = mock(StrategyRepositoryPort.class);
    private final RemoteStrategyCatalogRepositoryPort catalog = mock(RemoteStrategyCatalogRepositoryPort.class);
    private final LatestPricePort prices = mock(LatestPricePort.class);
    private final WebSocketConnectionRepositoryPort connections = mock(WebSocketConnectionRepositoryPort.class);
    private final ExchangeAdapterRepositoryPort adapters = mock(ExchangeAdapterRepositoryPort.class);
    private final InfrastructureSignalsPort infrastructure = mock(InfrastructureSignalsPort.class);
    private final ExchangeBalanceReconciliation balanceSanity = mock(ExchangeBalanceReconciliation.class);
    private final ReconcileTradesPort tradeReconciliation = mock(ReconcileTradesPort.class);
    private final InspectOperationalStateUseCase.Thresholds thresholds = new InspectOperationalStateUseCase.Thresholds(
            Duration.ofSeconds(60), Duration.ofHours(24), Duration.ofSeconds(30), Duration.ofSeconds(60),
            Duration.ofMinutes(10));
    private final InspectOperationalStateUseCase useCase = new InspectOperationalStateUseCase(portfolios, balances,
            runners, deadLetters, strategies, catalog, prices, connections, adapters, infrastructure, balanceSanity,
            tradeReconciliation, thresholds, Clock.fixed(NOW, ZoneOffset.UTC));

    private Portfolio portfolio;
    private StrategyRunner runner;

    @BeforeEach
    void healthySystem() {
        portfolio = portfolio(SafeModeStatus.NORMAL);
        runner = runner(RunnerStatus.ACTIVE);
        when(portfolios.findAll()).thenReturn(List.of(portfolio));
        when(runners.findAll()).thenReturn(List.of(runner));
        when(runners.findByRunnerIdAndStatuses(any(), any())).thenReturn(List.of());
        when(runners.findActivePositionsByPortfolioId(any())).thenReturn(List.of());
        when(balances.findByPortfolioId(any())).thenReturn(Optional.empty());
        TradingStrategy strategy = mock(TradingStrategy.class);
        when(strategy.isEnabled()).thenReturn(true);
        UUID strategyId = runner.getStrategyId();
        when(strategies.findById(strategyId)).thenReturn(Optional.of(strategy));
        when(catalog.findAll()).thenReturn(List.of());
        when(prices.findUpdatedAt("BTCUSDT")).thenReturn(Optional.of(NOW.minusSeconds(5)));
        when(connections.getAllConnections()).thenReturn(Map.of());
        when(adapters.findAdapter("MOCK")).thenReturn(Optional.empty());
        when(infrastructure.bootState()).thenReturn(new BootState(BootRunStatus.SUCCESS, null));
        when(infrastructure.openRestCircuits()).thenReturn(List.of());
    }

    @Test
    void healthySystemIsOkAndListsEveryCheck() {
        OperationalStateReport report = useCase.inspect();

        assertEquals(OperationalSeverity.OK, report.status());
        assertEquals(16, report.checks().size());
        assertTrue(report.checks().stream().allMatch(c -> c.status() == OperationalSeverity.OK), report.toString());
        assertEquals("docs/OPERATIONS_RUNBOOK.md#dlq-unresolved", check(report, "DLQ_UNRESOLVED").runbook());
    }

    @Test
    void reservedCapitalWithoutABuyInFlightIsCritical() {
        GlobalBalance balance = mock(GlobalBalance.class);
        when(balance.getReservedBalance()).thenReturn(new BigDecimal("65.00"));
        UUID portfolioId = portfolio.getId();
        when(balances.findByPortfolioId(portfolioId)).thenReturn(Optional.of(balance));

        OperationalCheck orphan = check(useCase.inspect(), "CAPITAL_RESERVED_WITHOUT_ORDER");
        assertEquals(OperationalSeverity.CRITICAL, orphan.status());
        assertEquals("65.00", orphan.subjects().getFirst().get("reserved"));

        Transaction buy = tx(TransactionType.BUY, TransactionStatus.SUBMITTED, NOW.minusSeconds(10));
        UUID runnerId = runner.getId();
        when(runners.findByRunnerIdAndStatuses(runnerId, TransactionStatus.IN_FLIGHT)).thenReturn(List.of(buy));
        assertEquals(OperationalSeverity.OK, check(useCase.inspect(), "CAPITAL_RESERVED_WITHOUT_ORDER").status());
    }

    @Test
    void openLotJustifiesTheReservationOfItsCost() {
        GlobalBalance balance = mock(GlobalBalance.class);
        when(balance.getReservedBalance()).thenReturn(new BigDecimal("82.58801"));
        UUID portfolioId = portfolio.getId();
        when(balances.findByPortfolioId(portfolioId)).thenReturn(Optional.of(balance));
        Position openLot = mock(Position.class);
        when(runners.findActivePositionsByPortfolioId(portfolioId)).thenReturn(List.of(openLot));

        assertEquals(OperationalSeverity.OK, check(useCase.inspect(), "CAPITAL_RESERVED_WITHOUT_ORDER").status(),
                "o custo da BUY executada fica reservado ate a SELL do lote casar");
    }

    @Test
    void onlyUnacknowledgedOrdersAreStuckAndBeyondThreeWatchdogCyclesIsCritical() {
        Transaction resting = tx(TransactionType.BUY, TransactionStatus.SUBMITTED, NOW.minus(Duration.ofHours(2)));
        Transaction pending = tx(TransactionType.SELL, TransactionStatus.PENDING, NOW.minusSeconds(45));
        UUID runnerId = runner.getId();
        when(runners.findByRunnerIdAndStatuses(runnerId, TransactionStatus.IN_FLIGHT))
                .thenReturn(List.of(resting, pending));

        OperationalCheck stuck = check(useCase.inspect(), "TRANSACTION_STUCK");
        assertEquals(OperationalSeverity.WARN, stuck.status());
        assertEquals(1, stuck.subjects().size(), "ordem confirmada descansando no livro nao esta presa");

        when(pending.getUpdatedAt()).thenReturn(NOW.minusSeconds(30 + 3 * 60 + 1));
        assertEquals(OperationalSeverity.CRITICAL, check(useCase.inspect(), "TRANSACTION_STUCK").status());
    }

    @Test
    void lotLockedByAFinishedSellIsAnOrphan() {
        Position position = mock(Position.class);
        UUID lockId = UUID.randomUUID();
        when(position.isLocked()).thenReturn(true);
        when(position.getLockedByTransactionId()).thenReturn(lockId);
        when(position.getId()).thenReturn(UUID.randomUUID());
        when(runners.findActivePositionsByPortfolioId(any())).thenReturn(List.of(position));
        Transaction canceled = tx(TransactionType.SELL, TransactionStatus.CANCELED, NOW.minusSeconds(5));
        when(runners.findTransactionById(lockId)).thenReturn(Optional.of(canceled));

        OperationalCheck orphan = check(useCase.inspect(), "POSITION_LOCK_ORPHAN");
        assertEquals(OperationalSeverity.CRITICAL, orphan.status());
        assertEquals("CANCELED", orphan.subjects().getFirst().get("lockStatus"));
    }

    @Test
    void dlqIsWarnFromOneAndCriticalFromThree() {
        UUID portfolioId = portfolio.getId();
        when(deadLetters.countUnresolvedByPortfolioId(portfolioId)).thenReturn(1L);
        assertEquals(OperationalSeverity.WARN, check(useCase.inspect(), "DLQ_UNRESOLVED").status());

        when(deadLetters.countUnresolvedByPortfolioId(portfolioId)).thenReturn(3L);
        assertEquals(OperationalSeverity.CRITICAL, check(useCase.inspect(), "DLQ_UNRESOLVED").status());
    }

    @Test
    void safeModeHaltIsWarnAndMassActionLevelsAreCritical() {
        when(portfolio.getSafeModeStatus()).thenReturn(SafeModeStatus.HALT);
        assertEquals(OperationalSeverity.WARN, check(useCase.inspect(), "SAFE_MODE_ACTIVE").status());

        when(portfolio.getSafeModeStatus()).thenReturn(SafeModeStatus.PANIC_SELL);
        OperationalStateReport report = useCase.inspect();
        assertEquals(OperationalSeverity.CRITICAL, check(report, "SAFE_MODE_ACTIVE").status());
        assertEquals(OperationalSeverity.CRITICAL, report.status(), "o status geral e a pior severidade");
    }

    @Test
    void symbolWithoutARecentTickIsCritical() {
        when(prices.findUpdatedAt("BTCUSDT")).thenReturn(Optional.of(NOW.minusSeconds(61)));
        assertEquals(OperationalSeverity.CRITICAL, check(useCase.inspect(), "MARKET_DATA_STALE").status());

        when(prices.findUpdatedAt("BTCUSDT")).thenReturn(Optional.empty());
        assertEquals(OperationalSeverity.CRITICAL, check(useCase.inspect(), "MARKET_DATA_STALE").status(),
                "nenhum tick desde o boot");
    }

    @Test
    void openLotOfAStoppedRunnerWithoutTickIsWarnNotCritical() {
        // T58 (decisao 2026-10-21): ninguem opera, mas drawdown e PnL ficam sem preco.
        when(runner.getStatus()).thenReturn(RunnerStatus.HALTED);
        UUID runnerId = runner.getId();
        UUID portfolioId = portfolio.getId();
        Position lot = mock(Position.class);
        when(lot.getRunnerId()).thenReturn(runnerId);
        when(runners.findActivePositionsByPortfolioId(portfolioId)).thenReturn(List.of(lot));
        when(prices.findUpdatedAt("BTCUSDT")).thenReturn(Optional.empty());

        OperationalCheck stale = check(useCase.inspect(), "MARKET_DATA_STALE");

        assertEquals(OperationalSeverity.WARN, stale.status());
        assertEquals("OPEN_LOT", stale.subjects().getFirst().get("demand"));
    }

    @Test
    void stoppedRunnerWithoutLotNeedsNoTick() {
        when(runner.getStatus()).thenReturn(RunnerStatus.HALTED);
        when(prices.findUpdatedAt("BTCUSDT")).thenReturn(Optional.empty());

        assertEquals(OperationalSeverity.OK, check(useCase.inspect(), "MARKET_DATA_STALE").status());
    }

    @Test
    void userStreamIsRequiredOnlyForExchangesThatHaveOne() {
        assertEquals(OperationalSeverity.OK, check(useCase.inspect(), "STREAM_DISCONNECTED").status(),
                "exchange sem stream de usuario (MOCK) nao exige conexao");

        ExchangeAdapterDescriptor binance = mock(ExchangeAdapterDescriptor.class);
        when(binance.hasUserStream()).thenReturn(true);
        when(adapters.findAdapter("MOCK")).thenReturn(Optional.of(binance));
        OperationalCheck disconnected = check(useCase.inspect(), "STREAM_DISCONNECTED");
        assertEquals(OperationalSeverity.CRITICAL, disconnected.status());
        assertEquals("NOT_OPENED", disconnected.subjects().getFirst().get("status"));
    }

    /** T53 rodada 11: envio bloqueado com os streams ainda marcados como conectados. */
    @Test
    void blockedOrderDispatchIsCriticalWhateverTheStreamStatusSays() {
        assertEquals(OperationalSeverity.OK, check(useCase.inspect(), "ORDER_DISPATCH_BLOCKED").status());

        when(adapters.isDispatchBlocked("MOCK")).thenReturn(true);

        OperationalCheck blocked = check(useCase.inspect(), "ORDER_DISPATCH_BLOCKED");
        assertEquals(OperationalSeverity.CRITICAL, blocked.status());
        assertEquals("MOCK", blocked.subjects().getFirst().get("exchange"));
    }

    @Test
    void activeRunnerWithoutAUsableStrategyIsOnHold() {
        UUID strategyId = runner.getStrategyId();
        when(strategies.findById(strategyId)).thenReturn(Optional.empty());

        assertEquals(OperationalSeverity.WARN, check(useCase.inspect(), "RUNNER_ON_HOLD_STRATEGY_UNUSABLE").status());
    }

    @Test
    void infrastructureProblemsAreCritical() {
        when(infrastructure.bootState()).thenReturn(new BootState(BootRunStatus.FAILED, "phase2.sanity: FAIL_DEFICIT"));
        when(infrastructure.openRestCircuits()).thenReturn(List.of("BINANCE"));

        OperationalStateReport report = useCase.inspect();
        assertEquals(OperationalSeverity.CRITICAL, check(report, "BOOT_NOT_READY").status());
        assertEquals(OperationalSeverity.CRITICAL, check(report, "EXCHANGE_REST_CIRCUIT_OPEN").status());
    }

    @Test
    void exchangeComparisonOnlyRunsWhenRequested() {
        assertTrue(useCase.inspect().checks().stream().noneMatch(c -> c.id().startsWith("EXCHANGE_")
                && !c.id().equals("EXCHANGE_REST_CIRCUIT_OPEN")), "sem pedido, nada consulta a exchange");
        org.mockito.Mockito.verifyNoInteractions(balanceSanity, tradeReconciliation);
    }

    @Test
    void deficitIsCriticalSurplusIsUnallocatedAndFailureIsWarn() {
        reconciled(0, 0, 0);
        ExchangeBalanceReport deficitReport = balanceReport("1000", "900", ExchangeBalanceStatus.DEFICIT);
        when(balanceSanity.reconcileAll()).thenReturn(List.of(deficitReport));

        OperationalCheck deficit = check(useCase.inspect(ExchangeComparison.over(Duration.ofHours(24))),
                "EXCHANGE_BALANCE_DIVERGENCE");
        assertEquals(OperationalSeverity.CRITICAL, deficit.status());
        assertEquals("-100", deficit.subjects().getFirst().get("signedDelta"));
        assertEquals("USDT", deficit.subjects().getFirst().get("asset"));

        ExchangeBalanceReport surplusReport = balanceReport("900", "9900", ExchangeBalanceStatus.OK);
        when(balanceSanity.reconcileAll()).thenReturn(List.of(surplusReport));
        OperationalCheck surplus = check(useCase.inspect(ExchangeComparison.over(Duration.ofHours(24))),
                "EXCHANGE_BALANCE_DIVERGENCE");
        assertEquals(OperationalSeverity.OK, surplus.status(), "conta com mais que os portfolios e normal");
        assertEquals("9000", surplus.subjects().getFirst().get("unallocated"));

        UUID portfolioId = portfolio.getId();
        when(balanceSanity.reconcileAll()).thenReturn(List.of(ExchangeBalanceReport.notChecked("MOCK",
                ExchangeBalanceStatus.FAILED, "ACCOUNT_QUERY_FAILED", "timeout", List.of(portfolioId))));
        assertEquals(OperationalSeverity.WARN, check(useCase.inspect(ExchangeComparison.over(Duration.ofHours(24))),
                "EXCHANGE_BALANCE_DIVERGENCE").status());
    }

    private ExchangeBalanceReport balanceReport(String expected, String exchange, ExchangeBalanceStatus status) {
        BigDecimal delta = new BigDecimal(exchange).subtract(new BigDecimal(expected));
        return ExchangeBalanceReport.checked("MOCK", List.of(portfolio.getId()), List.of(
                new ExchangeBalanceReport.AssetBalance("USDT", new BigDecimal(expected), new BigDecimal(exchange),
                        delta, delta.max(BigDecimal.ZERO), BigDecimal.ZERO, status)));
    }

    @Test
    void fillsMissingOnEitherSideAreCritical() {
        ExchangeBalanceReport okReport = balanceReport("10", "10", ExchangeBalanceStatus.OK);
        when(balanceSanity.reconcileAll()).thenReturn(List.of(okReport));
        reconciled(5, 1, 0);

        OperationalStateReport report = useCase.inspect(ExchangeComparison.over(Duration.ofHours(6)));
        OperationalCheck trades = check(report, "EXCHANGE_TRADE_DIVERGENCE");
        assertEquals(OperationalSeverity.CRITICAL, trades.status());
        assertEquals(1, trades.subjects().getFirst().get("exchangeOnly"));
        assertEquals(OperationalSeverity.OK, check(report, "EXCHANGE_BALANCE_DIVERGENCE").status());
        org.mockito.ArgumentCaptor<ReconciliationRequest> request =
                org.mockito.ArgumentCaptor.forClass(ReconciliationRequest.class);
        org.mockito.Mockito.verify(tradeReconciliation).reconcile(request.capture());
        assertEquals(NOW.minus(Duration.ofHours(6)), request.getValue().from());
    }

    @Test
    void exchangeWithoutTradeHistoryIsReportedAsNotChecked() {
        UUID portfolioId = portfolio.getId();
        when(balanceSanity.reconcileAll()).thenReturn(List.of(ExchangeBalanceReport.notChecked("MOCK",
                ExchangeBalanceStatus.SKIPPED, "ACCOUNT_QUERY_NOT_AVAILABLE", "no account query",
                List.of(portfolioId))));
        when(tradeReconciliation.reconcile(any())).thenThrow(new IllegalArgumentException("not supported"));

        OperationalStateReport report = useCase.inspect(ExchangeComparison.over(Duration.ofHours(24)));
        assertEquals(OperationalSeverity.OK, check(report, "EXCHANGE_BALANCE_DIVERGENCE").status());
        OperationalCheck trades = check(report, "EXCHANGE_TRADE_DIVERGENCE");
        assertEquals(OperationalSeverity.OK, trades.status());
        assertEquals("NOT_CHECKED", trades.subjects().getFirst().get("result"));
    }

    // ---------------------------------------------------------------- helpers

    private void reconciled(int matched, int exchangeOnly, int localOnly) {
        when(tradeReconciliation.reconcile(any())).thenReturn(new ReconciliationReportDto("BTCUSDT", NOW, NOW,
                new ReconciliationSummaryDto(matched + exchangeOnly + localOnly, matched, 0, exchangeOnly, localOnly),
                List.of()));
    }

    private static OperationalCheck check(OperationalStateReport report, String id) {
        return report.checks().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    private static Portfolio portfolio(SafeModeStatus level) {
        Portfolio portfolio = mock(Portfolio.class);
        when(portfolio.getId()).thenReturn(UUID.randomUUID());
        when(portfolio.getSafeModeStatus()).thenReturn(level);
        when(portfolio.getSafeModeOrigin()).thenReturn(SafeModeOrigin.MANUAL);
        when(portfolio.getSafeModeChangedAt()).thenReturn(NOW.minusSeconds(120));
        return portfolio;
    }

    private StrategyRunner runner(RunnerStatus status) {
        StrategyRunner runner = mock(StrategyRunner.class);
        UUID portfolioId = portfolio.getId();
        when(runner.getId()).thenReturn(UUID.randomUUID());
        when(runner.getPortfolioId()).thenReturn(portfolioId);
        when(runner.getStrategyId()).thenReturn(UUID.randomUUID());
        when(runner.getStatus()).thenReturn(status);
        when(runner.getSymbol()).thenReturn("BTCUSDT");
        when(runner.getExchangeId()).thenReturn("MOCK");
        when(runner.getAllowedMarketDataSources()).thenReturn(Set.of("MOCK"));
        when(runner.getStatusChangedAt()).thenReturn(NOW.minusSeconds(600));
        return runner;
    }

    private Transaction tx(TransactionType type, TransactionStatus status, Instant at) {
        Transaction tx = mock(Transaction.class);
        UUID runnerId = runner.getId();
        when(tx.getId()).thenReturn(UUID.randomUUID());
        when(tx.getRunnerId()).thenReturn(runnerId);
        when(tx.getType()).thenReturn(type);
        when(tx.isBuy()).thenReturn(type == TransactionType.BUY);
        when(tx.getStatus()).thenReturn(status);
        when(tx.getRequestedAt()).thenReturn(at);
        when(tx.getUpdatedAt()).thenReturn(at);
        when(tx.getTotal()).thenReturn(new BigDecimal("65.00"));
        return tx;
    }
}
