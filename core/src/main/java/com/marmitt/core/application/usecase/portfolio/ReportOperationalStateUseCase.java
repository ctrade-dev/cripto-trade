package com.marmitt.core.application.usecase.portfolio;

import com.marmitt.core.application.usecase.runner.processsignal.RunnerExposureService;
import com.marmitt.core.domain.portfolio.GlobalBalance;
import com.marmitt.core.domain.portfolio.Portfolio;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.enums.RunnerStatus;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.ports.inbound.portfolio.ReportOperationalStatePort;
import com.marmitt.core.ports.outbound.metrics.OperationalMetricsPort;
import com.marmitt.core.ports.outbound.repository.GlobalBalanceRepositoryPort;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Amostra o estado operacional e o publica como métricas (T51). Sem regra de negócio: as contas são as
 * mesmas que já decidem em outro lugar (exposição em voo da reserva, nível do Safe Mode, status do runner).
 *
 * <ul>
 *   <li>Por portfolio: nível e tempo no Safe Mode, utilização global (reservado ÷ total) e runners HALTED.</li>
 *   <li>Por runner operacional: utilização do limite de alocação e idade da ordem em voo mais antiga.</li>
 *   <li>Global: runners por status e runners em TERMINATING há mais que {@code terminationTimeout}
 *       (log WARN por runner).</li>
 * </ul>
 */
@Slf4j
public class ReportOperationalStateUseCase implements ReportOperationalStatePort {

    private final PortfolioRepositoryPort portfolioRepository;
    private final GlobalBalanceRepositoryPort globalBalanceRepository;
    private final StrategyRunnerRepositoryPort strategyRunnerRepository;
    private final RunnerExposureService exposureService;
    private final OperationalMetricsPort metrics;
    private final Duration terminationTimeout;
    private final Supplier<Instant> clock;

    public ReportOperationalStateUseCase(PortfolioRepositoryPort portfolioRepository,
                                         GlobalBalanceRepositoryPort globalBalanceRepository,
                                         StrategyRunnerRepositoryPort strategyRunnerRepository,
                                         OperationalMetricsPort metrics,
                                         Duration terminationTimeout,
                                         Supplier<Instant> clock) {
        this.portfolioRepository = portfolioRepository;
        this.globalBalanceRepository = globalBalanceRepository;
        this.strategyRunnerRepository = strategyRunnerRepository;
        this.exposureService = new RunnerExposureService(strategyRunnerRepository);
        this.metrics = metrics;
        this.terminationTimeout = terminationTimeout;
        this.clock = clock;
    }

    @Override
    public void report() {
        Instant now = clock.get();
        List<StrategyRunner> runners = strategyRunnerRepository.findAll();
        Map<UUID, GlobalBalance> balances = new HashMap<>();

        for (Portfolio portfolio : portfolioRepository.findAll()) {
            GlobalBalance balance = globalBalanceRepository.findByPortfolioId(portfolio.getId()).orElse(null);
            if (balance != null) {
                balances.put(portfolio.getId(), balance);
            }
            long halted = runners.stream()
                    .filter(runner -> runner.getPortfolioId().equals(portfolio.getId()))
                    .filter(runner -> runner.getStatus() == RunnerStatus.HALTED)
                    .count();
            metrics.recordPortfolioState(portfolio.getId(), portfolio.getSafeModeStatus(),
                    safeModeActiveFor(portfolio, now), globalUtilization(balance), halted);
        }

        for (StrategyRunner runner : runners) {
            if (runner.canAcceptSignals()) {
                reportRunner(runner, balances.get(runner.getPortfolioId()), now);
            }
        }

        Map<RunnerStatus, Long> byStatus = new EnumMap<>(RunnerStatus.class);
        for (RunnerStatus status : RunnerStatus.values()) {
            byStatus.put(status, 0L);
        }
        byStatus.putAll(runners.stream().collect(Collectors.groupingBy(StrategyRunner::getStatus, Collectors.counting())));
        metrics.recordRunnerCounts(byStatus, countStuckTerminating(runners, now));
    }

    @Override
    public Map<UUID, SafeModeStatus> safeModeByPortfolio() {
        return portfolioRepository.findAll().stream()
                .collect(Collectors.toMap(Portfolio::getId, Portfolio::getSafeModeStatus));
    }

    private void reportRunner(StrategyRunner runner, GlobalBalance balance, Instant now) {
        RunnerExposureService.ExposureSnapshot exposure = exposureService.loadSnapshot(runner);
        BigDecimal limit = balance == null ? BigDecimal.ZERO : RunnerExposureService.allocationLimit(runner, balance);
        BigDecimal utilization = limit.signum() > 0
                ? exposure.exposure().divide(limit, 8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        Duration oldest = exposure.oldestInFlightAt() == null ? Duration.ZERO
                : Duration.between(exposure.oldestInFlightAt(), now);
        metrics.recordRunnerState(runner.getId(), utilization, oldest);
    }

    private long countStuckTerminating(List<StrategyRunner> runners, Instant now) {
        long stuck = 0;
        for (StrategyRunner runner : runners) {
            if (runner.getStatus() == RunnerStatus.TERMINATING
                    && Duration.between(runner.getStatusChangedAt(), now).compareTo(terminationTimeout) > 0) {
                stuck++;
                log.warn("runnerLifecycle: runner stuck in TERMINATING runnerId={} since={} timeout={}",
                        runner.getId(), runner.getStatusChangedAt(), terminationTimeout);
            }
        }
        return stuck;
    }

    private static Duration safeModeActiveFor(Portfolio portfolio, Instant now) {
        if (!portfolio.getSafeModeStatus().isActive() || portfolio.getSafeModeChangedAt() == null) {
            return Duration.ZERO;
        }
        return Duration.between(portfolio.getSafeModeChangedAt(), now);
    }

    private static BigDecimal globalUtilization(GlobalBalance balance) {
        if (balance == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = balance.getAvailableBalance().add(balance.getReservedBalance());
        return total.signum() > 0
                ? balance.getReservedBalance().divide(total, 8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
    }
}
