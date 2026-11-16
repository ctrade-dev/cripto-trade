package com.marmitt.application.spring.bootstrap;

import com.marmitt.core.ports.outbound.repository.BootRunRepositoryPort;
import com.marmitt.core.application.usecase.portfolio.AutomaticSafeModeTrigger;
import com.marmitt.application.spring.config.core.BootConfig;
import com.marmitt.core.ports.inbound.boot.RunBootSequencePort;
import com.marmitt.core.application.usecase.exchange.ExchangeBalanceReconciliation;
import com.marmitt.core.application.usecase.boot.phase2.PortfolioZombieDetectionUseCase;
import com.marmitt.core.application.usecase.runner.RunnerBootRecoveryUseCase;
import com.marmitt.core.domain.portfolio.Portfolio;
import com.marmitt.core.domain.runner.LiquidationPolicy;
import com.marmitt.core.domain.runner.PositionProtection;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.dto.portfolio.PortfolioZombieCandidate;
import com.marmitt.core.dto.portfolio.PortfolioZombieDetectionResult;
import com.marmitt.core.enums.AccountingPolicyType;
import com.marmitt.core.enums.DlqReason;
import com.marmitt.core.enums.ExecutionPolicy;
import com.marmitt.core.dto.boot.BootRunSnapshot;
import com.marmitt.core.enums.BootFailureMode;
import com.marmitt.core.enums.BootRunStatus;
import com.marmitt.core.enums.RunnerStatus;
import com.marmitt.core.ports.outbound.repository.DeadLetterEntryRepositoryPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BootOrchestratorBootMinimumTest {

    private final BootRunRepositoryPort bootRuns = mock(BootRunRepositoryPort.class);

    @Test
    void warnOnlyZombieDetectionDoesNotPersistDlqAndRunCompletes() {
        Portfolio portfolio = new Portfolio(UUID.randomUUID(), "p1");
        StrategyRunner runner = activeRunner(portfolio.getId(), "MOCK");
        PortfolioZombieDetectionResult detected = PortfolioZombieDetectionResult.detected(
                portfolio.getId(), "MOCK", 1, 1, 0, 0, 0, 0, List.of()
        );

        BootStatusTracker tracker = new BootStatusTracker();
        DeadLetterEntryRepositoryPort deadLetterRepository = mock(DeadLetterEntryRepositoryPort.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        BootOrchestrator orchestrator = newOrchestrator(
                BootFailureMode.WARN_ONLY,
                portfolio,
                runner,
                detected,
                tracker,
                deadLetterRepository,
                eventPublisher
        );

        orchestrator.onApplicationReady();

        BootRunSnapshot snapshot = tracker.snapshot();
        assertEquals(BootRunStatus.SUCCESS, snapshot.status());
        assertNull(snapshot.failurePhase());
        // T59: o boot fica no historico, que sobrevive ao restart.
        verify(bootRuns).save(argThat(run -> run.status() == BootRunStatus.SUCCESS && run.failurePhase() == null
                && run.runId().equals(snapshot.runId()) && !run.finishedAt().isBefore(run.startedAt())));
        verifyNoInteractions(deadLetterRepository);
        verify(eventPublisher, never()).publishEvent(any(BootFailFastEvent.class));
    }

    @Test
    void failFastDetectedPersistsDlqSamplesAndPreservesPhaseSpecificFailureMetadata() {
        Portfolio portfolio = new Portfolio(UUID.randomUUID(), "p1");
        StrategyRunner runner = activeRunner(portfolio.getId(), "MOCK");
        PortfolioZombieDetectionResult detected = PortfolioZombieDetectionResult.detected(
                portfolio.getId(), "MOCK", 1, 1, 0, 0, 0, 0, List.of(
                        new PortfolioZombieCandidate(
                                "",
                                "EX_ORDER_123",
                                "BTCUSDT",
                                DlqReason.INVALID_FORMAT,
                                "INVALID_FORMAT"
                        )
                )
        );

        BootStatusTracker tracker = new BootStatusTracker();
        DeadLetterEntryRepositoryPort deadLetterRepository = mock(DeadLetterEntryRepositoryPort.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        BootOrchestrator orchestrator = newOrchestrator(
                BootFailureMode.FAIL_FAST,
                portfolio,
                runner,
                detected,
                tracker,
                deadLetterRepository,
                eventPublisher
        );

        assertThrows(IllegalStateException.class, orchestrator::onApplicationReady);

        BootRunSnapshot snapshot = tracker.snapshot();
        assertEquals(BootRunStatus.FAILED, snapshot.status());
        assertEquals("phase2.zombie", snapshot.failurePhase());
        verify(bootRuns).save(argThat(run -> run.status() == BootRunStatus.FAILED
                && "phase2.zombie".equals(run.failurePhase()) && run.failureMessage() != null));
        verify(deadLetterRepository, times(1)).save(any());
        verify(eventPublisher).publishEvent(any(BootFailFastEvent.class));
    }

    @Test
    void failFastFailedStatusPreservesPhaseSpecificFailureMetadata() {
        Portfolio portfolio = new Portfolio(UUID.randomUUID(), "p1");
        StrategyRunner runner = activeRunner(portfolio.getId(), "MOCK");
        PortfolioZombieDetectionResult failed = PortfolioZombieDetectionResult.failed(
                portfolio.getId(),
                "MOCK",
                "QUERY_FAILED",
                "Simulated exchange query failure"
        );

        BootStatusTracker tracker = new BootStatusTracker();
        DeadLetterEntryRepositoryPort deadLetterRepository = mock(DeadLetterEntryRepositoryPort.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        BootOrchestrator orchestrator = newOrchestrator(
                BootFailureMode.FAIL_FAST,
                portfolio,
                runner,
                failed,
                tracker,
                deadLetterRepository,
                eventPublisher
        );

        assertThrows(IllegalStateException.class, orchestrator::onApplicationReady);

        BootRunSnapshot snapshot = tracker.snapshot();
        assertEquals(BootRunStatus.FAILED, snapshot.status());
        assertEquals("phase2.zombie", snapshot.failurePhase());
        verifyNoInteractions(deadLetterRepository);
        verify(eventPublisher).publishEvent(any(BootFailFastEvent.class));
    }

    private BootOrchestrator newOrchestrator(BootFailureMode mode,
                                                    Portfolio portfolio,
                                                    StrategyRunner runner,
                                                    PortfolioZombieDetectionResult zombieResult,
                                                    BootStatusTracker tracker,
                                                    DeadLetterEntryRepositoryPort deadLetterRepository,
                                                    ApplicationEventPublisher eventPublisher) {
        PortfolioRepositoryPort portfolioRepository = mock(PortfolioRepositoryPort.class);
        StrategyRunnerRepositoryPort strategyRunnerRepository = mock(StrategyRunnerRepositoryPort.class);
        ExchangeAdapterRepositoryPort exchangeAdapterRepository = mock(ExchangeAdapterRepositoryPort.class);
        ExchangeBalanceReconciliation portfolioBootSanityUseCase = mock(ExchangeBalanceReconciliation.class);
        PortfolioZombieDetectionUseCase portfolioZombieDetectionUseCase = mock(PortfolioZombieDetectionUseCase.class);
        RunnerBootRecoveryUseCase runnerBootRecoveryUseCase = mock(RunnerBootRecoveryUseCase.class);
        BootMetricsRecorder bootMetricsRecorder = mock(BootMetricsRecorder.class);

        when(portfolioRepository.findAll()).thenReturn(List.of(portfolio));
        when(strategyRunnerRepository.findByPortfolioId(portfolio.getId())).thenReturn(List.of(runner));
        when(portfolioZombieDetectionUseCase.execute(portfolio.getId(), "MOCK", true)).thenReturn(zombieResult);

        RunnerBootPhase1Properties phase1Properties = new RunnerBootPhase1Properties();
        phase1Properties.setEnabled(false);

        RunnerBootPhase2Properties phase2Properties = new RunnerBootPhase2Properties();
        phase2Properties.setEnabled(true);
        phase2Properties.setMode(mode);

        RunnerBootPhase3Properties phase3Properties = new RunnerBootPhase3Properties();
        phase3Properties.setEnabled(false);

        PortfolioSanityCheckProperties sanityProperties = new PortfolioSanityCheckProperties();
        sanityProperties.setEnabled(false);

        PortfolioZombieDetectionProperties zombieProperties = new PortfolioZombieDetectionProperties();
        zombieProperties.setEnabled(true);

        PortfolioCutoffProperties cutoffProperties = new PortfolioCutoffProperties();
        cutoffProperties.setEnabled(true);

        RunBootSequencePort runBootSequence = new BootConfig().runBootSequence(
                portfolioRepository,
                strategyRunnerRepository,
                exchangeAdapterRepository,
                portfolioBootSanityUseCase,
                portfolioZombieDetectionUseCase,
                deadLetterRepository,
                runnerBootRecoveryUseCase,
                mock(AutomaticSafeModeTrigger.class),
                bootRuns
        );

        return new BootOrchestrator(
                phase1Properties,
                phase2Properties,
                phase3Properties,
                sanityProperties,
                zombieProperties,
                cutoffProperties,
                runBootSequence,
                tracker,
                bootMetricsRecorder,
                eventPublisher
        );
    }

    private static StrategyRunner activeRunner(UUID portfolioId, String exchangeId) {
        return new StrategyRunner(
                UUID.randomUUID(),
                portfolioId,
                "x1",
                UUID.randomUUID(),
                "SimpleMovingAverage",
                "BTCUSDT",
                exchangeId,
                Set.of(exchangeId),
                RunnerStatus.ACTIVE,
                ExecutionPolicy.SINGLE,
                AccountingPolicyType.FIFO,
                new BigDecimal("0.25"),
                1,
                1,
                null,
                PositionProtection.defaults(),
                LiquidationPolicy.defaults(),
                false,
                Instant.now(),
                Instant.now(),
                null,
                null,
                0L
        );
    }
}
