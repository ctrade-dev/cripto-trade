package com.marmitt.core.application.usecase.portfolio;

import com.marmitt.core.domain.portfolio.GlobalBalance;
import com.marmitt.core.domain.portfolio.Portfolio;
import com.marmitt.core.domain.runner.Position;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.enums.RunnerStatus;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.ports.outbound.metrics.OperationalMetricsPort;
import com.marmitt.core.ports.outbound.repository.GlobalBalanceRepositoryPort;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T51: amostra do estado operacional publicada como metricas, sem regra nova.
 */
class ReportOperationalStateUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final UUID PORTFOLIO = UUID.randomUUID();

    private final PortfolioRepositoryPort portfolios = mock(PortfolioRepositoryPort.class);
    private final GlobalBalanceRepositoryPort balances = mock(GlobalBalanceRepositoryPort.class);
    private final StrategyRunnerRepositoryPort runners = mock(StrategyRunnerRepositoryPort.class);
    private final OperationalMetricsPort metrics = mock(OperationalMetricsPort.class);
    private final ReportOperationalStateUseCase useCase = new ReportOperationalStateUseCase(
            portfolios, balances, runners, metrics, Duration.ofMinutes(10), () -> NOW);

    @Test
    void portfolioStateCarriesSafeModeLevelTimeUtilizationAndHaltedRunners() {
        Portfolio portfolio = portfolio(SafeModeStatus.HALT, NOW.minusSeconds(90));
        StrategyRunner halted = runner(RunnerStatus.HALTED, NOW, false);
        GlobalBalance balance = balance("7500", "2500", "10000");
        when(portfolios.findAll()).thenReturn(List.of(portfolio));
        when(balances.findByPortfolioId(PORTFOLIO)).thenReturn(Optional.of(balance));
        when(runners.findAll()).thenReturn(List.of(halted));

        useCase.report();

        verify(metrics).recordPortfolioState(PORTFOLIO, SafeModeStatus.HALT, Duration.ofSeconds(90),
                new BigDecimal("0.25000000"), 1L);
    }

    @Test
    void normalLevelReportsZeroTimeInSafeMode() {
        Portfolio portfolio = portfolio(SafeModeStatus.NORMAL, NOW.minusSeconds(3600));
        when(portfolios.findAll()).thenReturn(List.of(portfolio));
        when(balances.findByPortfolioId(PORTFOLIO)).thenReturn(Optional.empty());
        when(runners.findAll()).thenReturn(List.of());

        useCase.report();

        verify(metrics).recordPortfolioState(PORTFOLIO, SafeModeStatus.NORMAL, Duration.ZERO, BigDecimal.ZERO, 0L);
    }

    @Test
    void operationalRunnerReportsUtilizationOfItsAllocationLimitAndOldestReservation() {
        StrategyRunner active = runner(RunnerStatus.ACTIVE, NOW, true);
        Portfolio portfolio = portfolio(SafeModeStatus.NORMAL, null);
        GlobalBalance balance = balance("9000", "1000", "10000");
        when(portfolios.findAll()).thenReturn(List.of(portfolio));
        when(balances.findByPortfolioId(PORTFOLIO)).thenReturn(Optional.of(balance));
        when(runners.findAll()).thenReturn(List.of(active));
        Transaction older = inFlight("600", NOW.minusSeconds(400));
        Transaction newer = inFlight("400", NOW.minusSeconds(10));
        when(runners.findByRunnerIdAndStatuses(active.getId(), TransactionStatus.IN_FLIGHT))
                .thenReturn(List.of(newer, older));
        Position lot = mock(Position.class);
        when(lot.getCostBasis()).thenReturn(new BigDecimal("500"));
        when(runners.findActivePositionByRunnerIdAndSymbol(active.getId(), "BTCUSDT")).thenReturn(Optional.of(lot));

        useCase.report();

        // limite = 0.25 x 10000 = 2500; exposicao = lote 500 + BUY em voo 1000 = 1500 -> 60% (T62)
        verify(metrics).recordRunnerState(active.getId(), new BigDecimal("0.60000000"), Duration.ofSeconds(400));
    }

    @Test
    void runnersAreCountedByStatusAndStuckTerminatingIsFlagged() {
        StrategyRunner stuck = runner(RunnerStatus.TERMINATING, NOW.minus(Duration.ofMinutes(11)), false);
        StrategyRunner recent = runner(RunnerStatus.TERMINATING, NOW.minus(Duration.ofMinutes(2)), false);
        StrategyRunner archived = runner(RunnerStatus.ARCHIVED, NOW, false);
        when(portfolios.findAll()).thenReturn(List.of());
        when(runners.findAll()).thenReturn(List.of(stuck, recent, archived));

        useCase.report();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<RunnerStatus, Long>> counts = ArgumentCaptor.forClass(Map.class);
        verify(metrics).recordRunnerCounts(counts.capture(), eq(1L));
        assertEquals(2L, counts.getValue().get(RunnerStatus.TERMINATING));
        assertEquals(1L, counts.getValue().get(RunnerStatus.ARCHIVED));
        assertEquals(0L, counts.getValue().get(RunnerStatus.ACTIVE), "Status sem runner aparece com zero");
        verify(metrics, never()).recordRunnerState(any(), any(), any());
    }

    @Test
    void safeModeByPortfolioReadsTheCurrentLevel() {
        Portfolio portfolio = portfolio(SafeModeStatus.PANIC_SELL, NOW);
        when(portfolios.findAll()).thenReturn(List.of(portfolio));

        assertEquals(Map.of(PORTFOLIO, SafeModeStatus.PANIC_SELL), useCase.safeModeByPortfolio());
        verify(metrics, never()).recordRunnerCounts(any(), anyLong());
    }

    private static Portfolio portfolio(SafeModeStatus level, Instant changedAt) {
        Portfolio portfolio = mock(Portfolio.class);
        when(portfolio.getId()).thenReturn(PORTFOLIO);
        when(portfolio.getSafeModeStatus()).thenReturn(level);
        when(portfolio.getSafeModeChangedAt()).thenReturn(changedAt);
        return portfolio;
    }

    private static GlobalBalance balance(String available, String reserved, String total) {
        GlobalBalance balance = mock(GlobalBalance.class);
        when(balance.getAvailableBalance()).thenReturn(new BigDecimal(available));
        when(balance.getReservedBalance()).thenReturn(new BigDecimal(reserved));
        when(balance.getTotalBalance()).thenReturn(new BigDecimal(total));
        return balance;
    }

    private static StrategyRunner runner(RunnerStatus status, Instant statusChangedAt, boolean operational) {
        StrategyRunner runner = mock(StrategyRunner.class);
        when(runner.getId()).thenReturn(UUID.randomUUID());
        when(runner.getPortfolioId()).thenReturn(PORTFOLIO);
        when(runner.getStatus()).thenReturn(status);
        when(runner.getStatusChangedAt()).thenReturn(statusChangedAt);
        when(runner.canAcceptSignals()).thenReturn(operational);
        when(runner.getMaxAllocationPercent()).thenReturn(new BigDecimal("0.25"));
        when(runner.getSymbol()).thenReturn("BTCUSDT");
        return runner;
    }

    private static Transaction inFlight(String total, Instant requestedAt) {
        Transaction transaction = mock(Transaction.class);
        when(transaction.isBuy()).thenReturn(true);
        when(transaction.getTotal()).thenReturn(new BigDecimal(total));
        when(transaction.getRequestedAt()).thenReturn(requestedAt);
        return transaction;
    }
}
