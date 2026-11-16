package com.marmitt.application.spring.config.core;

import com.marmitt.application.spring.bootstrap.PortfolioSanityCheckProperties;
import com.marmitt.core.application.usecase.boot.BootPhase;
import com.marmitt.core.application.usecase.boot.RunBootSequenceUseCase;
import com.marmitt.core.application.usecase.boot.phase1.InfrastructureReadinessPhase;
import com.marmitt.core.application.usecase.boot.phase2.BalanceSanityPhase;
import com.marmitt.core.application.usecase.boot.phase2.ZombieDetectionPhase;
import com.marmitt.core.application.usecase.boot.phase3.RunnerRecoveryPhase;
import com.marmitt.core.application.usecase.boot.phase2.PortfolioZombieDetectionUseCase;
import com.marmitt.core.application.usecase.exchange.ExchangeBalanceReconciliation;
import com.marmitt.core.application.usecase.portfolio.AutomaticSafeModeTrigger;
import com.marmitt.core.application.usecase.runner.RunnerBootRecoveryUseCase;
import com.marmitt.core.ports.inbound.boot.RunBootSequencePort;
import com.marmitt.core.ports.outbound.repository.BootRunRepositoryPort;
import com.marmitt.core.ports.outbound.repository.DeadLetterEntryRepositoryPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.GlobalBalanceRepositoryPort;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class BootConfig {

    /** Conferencia de saldo por exchange (T57): boot fase 2, estado operacional e avaliacao periodica. */
    @Bean
    public ExchangeBalanceReconciliation exchangeBalanceReconciliation(
            GlobalBalanceRepositoryPort globalBalanceRepository,
            StrategyRunnerRepositoryPort strategyRunnerRepository,
            ExchangeAdapterRepositoryPort exchangeAdapterRepository,
            PortfolioSanityCheckProperties portfolioSanityCheckProperties
    ) {
        return new ExchangeBalanceReconciliation(globalBalanceRepository, strategyRunnerRepository,
                exchangeAdapterRepository, portfolioSanityCheckProperties.getThreshold());
    }

    @Bean
    public PortfolioZombieDetectionUseCase portfolioZombieDetectionUseCase(
            StrategyRunnerRepositoryPort strategyRunnerRepository,
            ExchangeAdapterRepositoryPort exchangeAdapterRepository
    ) {
        return new PortfolioZombieDetectionUseCase(strategyRunnerRepository, exchangeAdapterRepository);
    }

    /** Fases do boot na ordem de execucao (T70); a sequencia so ordena, liga/desliga e trata a falha. */
    @Bean
    public RunBootSequencePort runBootSequence(
            PortfolioRepositoryPort portfolioRepository,
            StrategyRunnerRepositoryPort strategyRunnerRepository,
            ExchangeAdapterRepositoryPort exchangeAdapterRepository,
            ExchangeBalanceReconciliation exchangeBalanceReconciliation,
            PortfolioZombieDetectionUseCase portfolioZombieDetectionUseCase,
            DeadLetterEntryRepositoryPort deadLetterEntryRepository,
            RunnerBootRecoveryUseCase runnerBootRecoveryUseCase,
            AutomaticSafeModeTrigger automaticSafeModeTrigger,
            BootRunRepositoryPort bootRunRepository
    ) {
        List<BootPhase> phases = List.of(
                new InfrastructureReadinessPhase(exchangeAdapterRepository),
                new BalanceSanityPhase(exchangeBalanceReconciliation, automaticSafeModeTrigger),
                new ZombieDetectionPhase(portfolioZombieDetectionUseCase, deadLetterEntryRepository,
                        strategyRunnerRepository),
                new RunnerRecoveryPhase(runnerBootRecoveryUseCase));
        return new RunBootSequenceUseCase(portfolioRepository, strategyRunnerRepository, phases, bootRunRepository);
    }
}
