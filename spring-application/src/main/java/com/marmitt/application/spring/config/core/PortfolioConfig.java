package com.marmitt.application.spring.config.core;

import com.marmitt.application.spring.bootstrap.AutoSafeModeProperties;
import com.marmitt.application.spring.bootstrap.CircuitBreakerProperties;
import com.marmitt.application.spring.bootstrap.OperationalStateProperties;
import com.marmitt.application.spring.service.TransactionalManageDeadLetterPort;
import com.marmitt.application.spring.bootstrap.RunnerTransactionRecoveryProperties;
import com.marmitt.core.application.usecase.exchange.ExchangeBalanceReconciliation;
import com.marmitt.core.application.usecase.operations.InspectOperationalStateUseCase;
import com.marmitt.core.application.usecase.operations.QueryEventTimelineUseCase;
import com.marmitt.core.application.usecase.portfolio.AutomaticSafeModeTrigger;
import com.marmitt.core.application.usecase.portfolio.CreatePortfolioUseCase;
import com.marmitt.core.application.usecase.portfolio.EvaluateDrawdownUseCase;
import com.marmitt.core.application.usecase.portfolio.EvaluateExchangeBalanceUseCase;
import com.marmitt.core.application.usecase.portfolio.EvaluateOperationalTriggersUseCase;
import com.marmitt.core.application.usecase.portfolio.ManageDeadLetterUseCase;
import com.marmitt.core.application.usecase.portfolio.ManageSafeModeUseCase;
import com.marmitt.core.application.usecase.portfolio.QueryPortfolioUseCase;
import com.marmitt.core.application.usecase.portfolio.ReportOperationalStateUseCase;
import com.marmitt.core.ports.inbound.operations.InspectOperationalStatePort;
import com.marmitt.core.ports.inbound.operations.QueryEventTimelinePort;
import com.marmitt.core.ports.inbound.portfolio.CreatePortfolioPort;
import com.marmitt.core.ports.inbound.reconciliation.ReconcileTradesPort;
import com.marmitt.core.ports.outbound.strategy.StrategyCallHealthPort;
import com.marmitt.core.ports.inbound.portfolio.EvaluateDrawdownPort;
import com.marmitt.core.ports.inbound.portfolio.EvaluateExchangeBalancePort;
import com.marmitt.core.ports.inbound.portfolio.EvaluateOperationalTriggersPort;
import com.marmitt.core.ports.inbound.portfolio.ManageDeadLetterPort;
import com.marmitt.core.ports.inbound.portfolio.ManageSafeModePort;
import com.marmitt.core.ports.inbound.portfolio.QueryPortfolioPort;
import com.marmitt.core.ports.inbound.portfolio.ReportOperationalStatePort;
import com.marmitt.core.ports.outbound.marketdata.LatestPricePort;
import com.marmitt.core.ports.outbound.metrics.CircuitBreakerMetricsPort;
import com.marmitt.core.ports.outbound.metrics.OperationalMetricsPort;
import com.marmitt.core.ports.outbound.notification.ErrorNotificationPort;
import com.marmitt.core.ports.outbound.operations.InfrastructureSignalsPort;
import com.marmitt.core.ports.outbound.repository.DeadLetterEntryRepositoryPort;
import com.marmitt.core.ports.outbound.repository.EventTimelineRepositoryPort;
import com.marmitt.core.ports.outbound.repository.DeadLetterReprocessingPort;
import com.marmitt.core.ports.outbound.repository.EquitySnapshotRepositoryPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.GlobalBalanceRepositoryPort;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import com.marmitt.core.ports.outbound.repository.RemoteStrategyCatalogRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Configuration
public class PortfolioConfig {

    @Bean
    public CreatePortfolioPort createPortfolio(
            PortfolioRepositoryPort portfolioRepository,
            GlobalBalanceRepositoryPort globalBalanceRepository
    ) {
        return new CreatePortfolioUseCase(
                portfolioRepository,
                globalBalanceRepository
        );
    }

    @Bean
    public QueryPortfolioPort queryPortfolio(
            PortfolioRepositoryPort portfolioRepository,
            StrategyRunnerRepositoryPort strategyRunnerRepository,
            GlobalBalanceRepositoryPort globalBalanceRepository,
            LatestPricePort latestPrices
    ) {
        return new QueryPortfolioUseCase(
                portfolioRepository,
                strategyRunnerRepository,
                globalBalanceRepository,
                latestPrices
        );
    }

    @Bean
    public ManageSafeModePort manageSafeMode(
            PortfolioRepositoryPort portfolioRepository,
            StrategyRunnerRepositoryPort strategyRunnerRepository
    ) {
        return new ManageSafeModeUseCase(portfolioRepository, strategyRunnerRepository);
    }

    @Bean
    public EvaluateDrawdownPort evaluateDrawdown(
            PortfolioRepositoryPort portfolioRepository,
            GlobalBalanceRepositoryPort globalBalanceRepository,
            StrategyRunnerRepositoryPort strategyRunnerRepository,
            LatestPricePort latestPrices,
            EquitySnapshotRepositoryPort equitySnapshots,
            AutomaticSafeModeTrigger automaticSafeModeTrigger,
            CircuitBreakerMetricsPort circuitBreakerMetrics,
            CircuitBreakerProperties circuitBreakerProperties
    ) {
        return new EvaluateDrawdownUseCase(portfolioRepository, globalBalanceRepository, strategyRunnerRepository,
                latestPrices, equitySnapshots, automaticSafeModeTrigger, circuitBreakerMetrics,
                circuitBreakerProperties.getMaxDrawdownPercent(), Clock.systemUTC());
    }

    /** Caminho unico do HALT automatico do portfolio (T43, T25). */
    @Bean
    public AutomaticSafeModeTrigger automaticSafeModeTrigger(
            PortfolioRepositoryPort portfolioRepository,
            ManageSafeModePort manageSafeMode,
            CircuitBreakerMetricsPort circuitBreakerMetrics,
            ErrorNotificationPort errorNotification,
            AutoSafeModeProperties autoSafeModeProperties
    ) {
        return new AutomaticSafeModeTrigger(portfolioRepository, manageSafeMode, circuitBreakerMetrics,
                errorNotification, Clock.systemUTC(), autoSafeModeProperties.enabledTriggers());
    }

    /** Divergencia de saldo durante a operacao (T57): HALT na segunda leitura seguida em DEFICIT. */
    @Bean
    public EvaluateExchangeBalancePort evaluateExchangeBalance(
            ExchangeBalanceReconciliation exchangeBalanceReconciliation,
            AutomaticSafeModeTrigger automaticSafeModeTrigger
    ) {
        return new EvaluateExchangeBalanceUseCase(exchangeBalanceReconciliation, automaticSafeModeTrigger);
    }

    @Bean
    public EvaluateOperationalTriggersPort evaluateOperationalTriggers(
            PortfolioRepositoryPort portfolioRepository,
            DeadLetterEntryRepositoryPort deadLetterEntryRepository,
            AutomaticSafeModeTrigger automaticSafeModeTrigger,
            AutoSafeModeProperties autoSafeModeProperties
    ) {
        return new EvaluateOperationalTriggersUseCase(portfolioRepository, deadLetterEntryRepository,
                automaticSafeModeTrigger, autoSafeModeProperties.getDlq().getThreshold());
    }

    @Bean
    public ReportOperationalStatePort reportOperationalState(
            PortfolioRepositoryPort portfolioRepository,
            GlobalBalanceRepositoryPort globalBalanceRepository,
            StrategyRunnerRepositoryPort strategyRunnerRepository,
            OperationalMetricsPort operationalMetrics,
            OperationalStateProperties operationalStateProperties
    ) {
        return new ReportOperationalStateUseCase(portfolioRepository, globalBalanceRepository,
                strategyRunnerRepository, operationalMetrics,
                Duration.ofMillis(operationalStateProperties.getTerminationTimeoutMs()), Instant::now);
    }

    @Bean
    public InspectOperationalStatePort inspectOperationalState(
            PortfolioRepositoryPort portfolioRepository,
            GlobalBalanceRepositoryPort globalBalanceRepository,
            StrategyRunnerRepositoryPort strategyRunnerRepository,
            DeadLetterEntryRepositoryPort deadLetterEntryRepository,
            StrategyRepositoryPort strategyRepository,
            RemoteStrategyCatalogRepositoryPort remoteStrategyCatalog,
            LatestPricePort latestPrices,
            WebSocketConnectionRepositoryPort webSocketConnections,
            ExchangeAdapterRepositoryPort exchangeAdapters,
            InfrastructureSignalsPort infrastructureSignals,
            ExchangeBalanceReconciliation exchangeBalanceReconciliation,
            ObjectProvider<ReconcileTradesPort> reconcileTrades,
            StrategyCallHealthPort strategyCallHealth,
            OperationalStateProperties operationalStateProperties,
            RunnerTransactionRecoveryProperties transactionRecoveryProperties
    ) {
        // Reconciliacao de trades so existe com exchange de historico configurada (Binance): sem ela, a
        // comparacao de trades responde "nao conferido".
        return new InspectOperationalStateUseCase(portfolioRepository, globalBalanceRepository,
                strategyRunnerRepository, deadLetterEntryRepository, strategyRepository, remoteStrategyCatalog,
                latestPrices, webSocketConnections, exchangeAdapters, infrastructureSignals,
                exchangeBalanceReconciliation, reconcileTrades.getIfAvailable(), strategyCallHealth,
                new InspectOperationalStateUseCase.Thresholds(
                        Duration.ofMillis(operationalStateProperties.getMarketDataStaleMs()),
                        Duration.ofMillis(operationalStateProperties.getReservationStaleMs()),
                        Duration.ofMillis(transactionRecoveryProperties.getStaleThresholdMs()),
                        Duration.ofMillis(transactionRecoveryProperties.getIntervalMs()),
                        Duration.ofMillis(operationalStateProperties.getTerminationTimeoutMs()),
                        Duration.ofMillis(operationalStateProperties.getStrategyUnavailableMs())),
                Clock.systemUTC());
    }

    @Bean
    public QueryEventTimelinePort queryEventTimeline(EventTimelineRepositoryPort eventTimelineRepository) {
        return new QueryEventTimelineUseCase(eventTimelineRepository, Clock.systemUTC());
    }

    @Bean
    public ManageDeadLetterUseCase manageDeadLetterUseCase(
            DeadLetterEntryRepositoryPort deadLetterEntryRepository,
            DeadLetterReprocessingPort deadLetterReprocessingPort
    ) {
        return new ManageDeadLetterUseCase(deadLetterEntryRepository, deadLetterReprocessingPort);
    }

    @Bean
    public ManageDeadLetterPort manageDeadLetter(
            ManageDeadLetterUseCase manageDeadLetterUseCase
    ) {
        return new TransactionalManageDeadLetterPort(manageDeadLetterUseCase);
    }
}
