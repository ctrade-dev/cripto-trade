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
import com.marmitt.core.enums.BootFailureMode;
import com.marmitt.core.enums.BootPhaseStatus;
import com.marmitt.core.enums.BootRunStatus;
import com.marmitt.core.enums.RunnerStatus;
import com.marmitt.core.ports.outbound.repository.DeadLetterEntryRepositoryPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BootOrchestratorObservabilityTest {

    @Test
    void warnOnlyShouldRecordBootAndPortfolioMetricsWithoutPublishingFailFastEvent() {
        Portfolio portfolio = new Portfolio(UUID.randomUUID(), "p1");
        StrategyRunner runner = activeRunner(portfolio.getId(), "MOCK");
        PortfolioZombieDetectionResult detected = PortfolioZombieDetectionResult.detected(
                portfolio.getId(), "MOCK", 1, 1, 0, 0, 0, 0, List.of()
        );

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CapturingEventPublisher eventPublisher = new CapturingEventPublisher();
        BootOrchestrator orchestrator = newOrchestrator(
                BootFailureMode.WARN_ONLY,
                portfolio,
                runner,
                detected,
                eventPublisher,
                new BootMetricsRecorder(meterRegistry)
        );

        orchestrator.onApplicationReady();

        assertEquals(1.0d, meterRegistry.get("boot.run.total")
                .tag("status", BootRunStatus.SUCCESS.name())
                .counter()
                .count());
        assertEquals(1.0d, meterRegistry.get("boot.phase.total")
                .tag("phase", "phase2.zombie")
                .tag("status", BootPhaseStatus.SUCCESS.name())
                .counter()
                .count());
        assertEquals(1L, meterRegistry.get("boot.phase.duration")
                .tag("phase", "phase2.zombie")
                .tag("status", BootPhaseStatus.SUCCESS.name())
                .timer()
                .count());
        assertEquals(1.0d, meterRegistry.get("boot.phase.portfolio.total")
                .tag("phase", "phase2.zombie")
                .tag("status", "DETECTED")
                .tag("exchange", "MOCK")
                .tag("mode", BootFailureMode.WARN_ONLY.name())
                .counter()
                .count());
        assertEquals(1L, meterRegistry.get("boot.phase.portfolio.duration")
                .tag("phase", "phase2.zombie")
                .tag("status", "DETECTED")
                .tag("exchange", "MOCK")
                .tag("mode", BootFailureMode.WARN_ONLY.name())
                .timer()
                .count());
        assertEquals(0, eventPublisher.events().size());
    }

    @Test
    void failFastShouldPublishEventAndRecordFailureMetrics() {
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

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CapturingEventPublisher eventPublisher = new CapturingEventPublisher();
        BootOrchestrator orchestrator = newOrchestrator(
                BootFailureMode.FAIL_FAST,
                portfolio,
                runner,
                detected,
                eventPublisher,
                new BootMetricsRecorder(meterRegistry)
        );

        assertThrows(IllegalStateException.class, orchestrator::onApplicationReady);

        assertEquals(1.0d, meterRegistry.get("boot.run.total")
                .tag("status", BootRunStatus.FAILED.name())
                .counter()
                .count());
        assertEquals(1.0d, meterRegistry.get("boot.phase.total")
                .tag("phase", "phase2.zombie")
                .tag("status", BootPhaseStatus.FAILED.name())
                .counter()
                .count());
        assertEquals(1L, meterRegistry.get("boot.phase.duration")
                .tag("phase", "phase2.zombie")
                .tag("status", BootPhaseStatus.FAILED.name())
                .timer()
                .count());
        assertEquals(1.0d, meterRegistry.get("boot.phase.portfolio.total")
                .tag("phase", "phase2.zombie")
                .tag("status", "DETECTED")
                .tag("exchange", "MOCK")
                .tag("mode", BootFailureMode.FAIL_FAST.name())
                .counter()
                .count());
        assertEquals(1.0d, meterRegistry.get("boot.failfast.total")
                .tag("phase", "phase2.zombie")
                .tag("code", "ZOMBIE_DETECTED")
                .counter()
                .count());

        assertEquals(1, eventPublisher.events().size());
        Object published = eventPublisher.events().getFirst();
        BootFailFastEvent event = assertInstanceOf(BootFailFastEvent.class, published);
        assertEquals("phase2.zombie", event.phase());
        assertEquals("ZOMBIE_DETECTED", event.code());
        assertNotNull(event.runId());
        assertNotNull(event.timestamp());
    }

    private static BootOrchestrator newOrchestrator(BootFailureMode mode,
                                                    Portfolio portfolio,
                                                    StrategyRunner runner,
                                                    PortfolioZombieDetectionResult zombieResult,
                                                    ApplicationEventPublisher eventPublisher,
                                                    BootMetricsRecorder bootMetricsRecorder) {
        PortfolioRepositoryPort portfolioRepository = mock(PortfolioRepositoryPort.class);
        StrategyRunnerRepositoryPort strategyRunnerRepository = mock(StrategyRunnerRepositoryPort.class);
        ExchangeAdapterRepositoryPort exchangeAdapterRepository = mock(ExchangeAdapterRepositoryPort.class);
        ExchangeBalanceReconciliation portfolioBootSanityUseCase = mock(ExchangeBalanceReconciliation.class);
        PortfolioZombieDetectionUseCase portfolioZombieDetectionUseCase = mock(PortfolioZombieDetectionUseCase.class);
        DeadLetterEntryRepositoryPort deadLetterRepository = mock(DeadLetterEntryRepositoryPort.class);
        RunnerBootRecoveryUseCase runnerBootRecoveryUseCase = mock(RunnerBootRecoveryUseCase.class);
        BootStatusTracker tracker = new BootStatusTracker();

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

    private static final class CapturingEventPublisher implements ApplicationEventPublisher {
        private final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }

        @Override
        public void publishEvent(ApplicationEvent event) {
            events.add(event);
        }

        public List<Object> events() {
            return events;
        }
    }
}
