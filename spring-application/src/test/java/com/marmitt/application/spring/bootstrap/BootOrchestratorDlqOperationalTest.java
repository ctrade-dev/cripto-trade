package com.marmitt.application.spring.bootstrap;

import com.marmitt.core.ports.outbound.repository.BootRunRepositoryPort;
import com.marmitt.core.application.usecase.portfolio.AutomaticSafeModeTrigger;
import com.marmitt.application.spring.config.core.BootConfig;
import com.marmitt.core.ports.inbound.boot.RunBootSequencePort;
import com.marmitt.core.application.usecase.exchange.ExchangeBalanceReconciliation;
import com.marmitt.core.application.usecase.boot.phase2.PortfolioZombieDetectionUseCase;
import com.marmitt.core.application.usecase.runner.RunnerBootRecoveryUseCase;
import com.marmitt.core.domain.portfolio.DeadLetterEntry;
import com.marmitt.core.domain.portfolio.Portfolio;
import com.marmitt.core.domain.runner.LiquidationPolicy;
import com.marmitt.core.domain.runner.PositionProtection;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.dto.portfolio.PortfolioZombieCandidate;
import com.marmitt.core.dto.portfolio.PortfolioZombieDetectionResult;
import com.marmitt.core.enums.AccountingPolicyType;
import com.marmitt.core.enums.DlqReason;
import com.marmitt.core.enums.ExecutionPolicy;
import com.marmitt.core.enums.BootFailureMode;
import com.marmitt.core.enums.RunnerStatus;
import com.marmitt.core.ports.outbound.repository.DeadLetterEntryRepositoryPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BootOrchestratorDlqOperationalTest {

    @Test
    void failFastZombieDetectionShouldPersistNewDlqEntryWithResolvedRunnerIdentity() {
        Portfolio portfolio = new Portfolio(UUID.randomUUID(), "p1");
        StrategyRunner runner = activeRunner(portfolio.getId(), "MOCK");
        String clientOrderId = "v1rx1t1234567890s001B_deadbeefcafe";
        PortfolioZombieDetectionResult detected = PortfolioZombieDetectionResult.detected(
                portfolio.getId(), "MOCK", 1, 1, 0, 0, 0, 0, List.of(
                        new PortfolioZombieCandidate(
                                clientOrderId,
                                "EX_ORDER_123",
                                "BTCUSDT",
                                DlqReason.RECONCILIATION_CONFLICT,
                                "NO_LOCAL_MATCH"
                        )
                )
        );

        DeadLetterEntryRepositoryPort deadLetterRepository = mock(DeadLetterEntryRepositoryPort.class);
        when(deadLetterRepository.existsUnresolvedByIdentity(
                portfolio.getId(),
                runner.getId(),
                clientOrderId,
                "EX_ORDER_123",
                DlqReason.RECONCILIATION_CONFLICT
        )).thenReturn(false);

        BootOrchestrator orchestrator = newOrchestrator(
                portfolio,
                runner,
                detected,
                deadLetterRepository
        );

        assertThrows(IllegalStateException.class, orchestrator::onApplicationReady);

        ArgumentCaptor<DeadLetterEntry> entryCaptor = ArgumentCaptor.forClass(DeadLetterEntry.class);
        verify(deadLetterRepository).save(entryCaptor.capture());

        DeadLetterEntry saved = entryCaptor.getValue();
        assertEquals(portfolio.getId(), saved.getPortfolioId());
        assertEquals(runner.getId(), saved.getRunnerId());
        assertEquals(clientOrderId, saved.getClientOrderId());
        assertEquals("EX_ORDER_123", saved.getExchangeOrderId());
        assertEquals(DlqReason.RECONCILIATION_CONFLICT, saved.getReason());
        assertTrue(saved.getRawPayload().contains("source=boot.phase2.zombie"));
        assertTrue(saved.getRawPayload().contains("exchange=MOCK"));
        assertTrue(saved.getRawPayload().contains("runnerId=" + runner.getId()));
        assertTrue(saved.getRawPayload().contains("code=NO_LOCAL_MATCH"));
        assertTrue(saved.getRawPayload().contains("symbol=BTCUSDT"));

        verify(deadLetterRepository).existsUnresolvedByIdentity(
                portfolio.getId(),
                runner.getId(),
                clientOrderId,
                "EX_ORDER_123",
                DlqReason.RECONCILIATION_CONFLICT
        );
    }

    @Test
    void failFastZombieDetectionShouldNotDuplicateDlqEntryWhenIdentityIsAlreadyOpen() {
        Portfolio portfolio = new Portfolio(UUID.randomUUID(), "p1");
        StrategyRunner runner = activeRunner(portfolio.getId(), "MOCK");
        String clientOrderId = "v1rx1t1234567890s001B_deadbeefcafe";
        PortfolioZombieDetectionResult detected = PortfolioZombieDetectionResult.detected(
                portfolio.getId(), "MOCK", 1, 1, 0, 0, 0, 0, List.of(
                        new PortfolioZombieCandidate(
                                clientOrderId,
                                "EX_ORDER_123",
                                "BTCUSDT",
                                DlqReason.RECONCILIATION_CONFLICT,
                                "NO_LOCAL_MATCH"
                        )
                )
        );

        DeadLetterEntryRepositoryPort deadLetterRepository = mock(DeadLetterEntryRepositoryPort.class);
        when(deadLetterRepository.existsUnresolvedByIdentity(
                portfolio.getId(),
                runner.getId(),
                clientOrderId,
                "EX_ORDER_123",
                DlqReason.RECONCILIATION_CONFLICT
        )).thenReturn(true);

        BootOrchestrator orchestrator = newOrchestrator(
                portfolio,
                runner,
                detected,
                deadLetterRepository
        );

        assertThrows(IllegalStateException.class, orchestrator::onApplicationReady);

        verify(deadLetterRepository, never()).save(any(DeadLetterEntry.class));
        verify(deadLetterRepository, times(1)).existsUnresolvedByIdentity(
                portfolio.getId(),
                runner.getId(),
                clientOrderId,
                "EX_ORDER_123",
                DlqReason.RECONCILIATION_CONFLICT
        );
    }

    private static BootOrchestrator newOrchestrator(Portfolio portfolio,
                                                    StrategyRunner runner,
                                                    PortfolioZombieDetectionResult zombieResult,
                                                    DeadLetterEntryRepositoryPort deadLetterRepository) {
        PortfolioRepositoryPort portfolioRepository = mock(PortfolioRepositoryPort.class);
        StrategyRunnerRepositoryPort strategyRunnerRepository = mock(StrategyRunnerRepositoryPort.class);
        ExchangeAdapterRepositoryPort exchangeAdapterRepository = mock(ExchangeAdapterRepositoryPort.class);
        ExchangeBalanceReconciliation portfolioBootSanityUseCase = mock(ExchangeBalanceReconciliation.class);
        PortfolioZombieDetectionUseCase portfolioZombieDetectionUseCase = mock(PortfolioZombieDetectionUseCase.class);
        RunnerBootRecoveryUseCase runnerBootRecoveryUseCase = mock(RunnerBootRecoveryUseCase.class);
        BootMetricsRecorder bootMetricsRecorder = mock(BootMetricsRecorder.class);
        BootStatusTracker tracker = new BootStatusTracker();
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        when(portfolioRepository.findAll()).thenReturn(List.of(portfolio));
        when(strategyRunnerRepository.findByPortfolioId(portfolio.getId())).thenReturn(List.of(runner));
        when(strategyRunnerRepository.findTransactionByClientOrderId("v1rx1t1234567890s001B_deadbeefcafe"))
                .thenReturn(Optional.empty());
        when(strategyRunnerRepository.findByShortCodeAndPortfolioId("x1", portfolio.getId()))
                .thenReturn(Optional.of(runner));
        when(portfolioZombieDetectionUseCase.execute(portfolio.getId(), "MOCK", true)).thenReturn(zombieResult);

        RunnerBootPhase1Properties phase1Properties = new RunnerBootPhase1Properties();
        phase1Properties.setEnabled(false);

        RunnerBootPhase2Properties phase2Properties = new RunnerBootPhase2Properties();
        phase2Properties.setEnabled(true);
        phase2Properties.setMode(BootFailureMode.FAIL_FAST);

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
                mock(BootRunRepositoryPort.class)
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
