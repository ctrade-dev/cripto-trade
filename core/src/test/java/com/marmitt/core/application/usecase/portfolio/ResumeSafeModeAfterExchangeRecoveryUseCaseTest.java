package com.marmitt.core.application.usecase.portfolio;

import com.marmitt.core.application.usecase.runner.WatchdogFailures;
import com.marmitt.core.domain.portfolio.Portfolio;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.dto.connection.ConnectionKey;
import com.marmitt.core.dto.notification.ErrorNotificationEvent;
import com.marmitt.core.dto.notification.ErrorNotificationType;
import com.marmitt.core.dto.portfolio.request.ChangeSafeModeRequest;
import com.marmitt.core.dto.wrapper.WebSocketConnectionManager;
import com.marmitt.core.enums.CapitalPoolingMode;
import com.marmitt.core.enums.ConnectionStatus;
import com.marmitt.core.enums.RunnerStatus;
import com.marmitt.core.enums.SafeModeOrigin;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.enums.SafeModeTrigger;
import com.marmitt.core.exceptions.SafeModeTransitionException;
import com.marmitt.core.ports.inbound.portfolio.ManageSafeModePort;
import com.marmitt.core.ports.outbound.metrics.CircuitBreakerMetricsPort;
import com.marmitt.core.ports.outbound.notification.ErrorNotificationPort;
import com.marmitt.core.ports.outbound.operations.InfrastructureSignalsPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T71: o HALT automatico por exchange fora do ar volta a NORMAL depois de N minutos seguidos de exchange
 * saudavel, no maximo M vezes em 24h.
 */
class ResumeSafeModeAfterExchangeRecoveryUseCaseTest {

    private static final UUID PORTFOLIO = UUID.randomUUID();
    private static final UUID RUNNER = UUID.randomUUID();
    private static final String EXCHANGE = "BINANCE";
    private static final Duration HEALTHY_FOR = Duration.ofMinutes(5);
    private static final Duration TICK = Duration.ofSeconds(30);

    private final PortfolioRepositoryPort portfolios = mock(PortfolioRepositoryPort.class);
    private final StrategyRunnerRepositoryPort runners = mock(StrategyRunnerRepositoryPort.class);
    private final ManageSafeModePort manageSafeMode = mock(ManageSafeModePort.class);
    private final InfrastructureSignalsPort infrastructure = mock(InfrastructureSignalsPort.class);
    private final ExchangeAdapterRepositoryPort exchangeAdapters = mock(ExchangeAdapterRepositoryPort.class);
    private final WebSocketConnectionRepositoryPort connections = mock(WebSocketConnectionRepositoryPort.class);
    private final WatchdogFailuresStub watchdogFailures = new WatchdogFailuresStub();
    private final CircuitBreakerMetricsPort metrics = mock(CircuitBreakerMetricsPort.class);
    private final ErrorNotificationPort notification = mock(ErrorNotificationPort.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T12:00:00Z"));

    private final ResumeSafeModeAfterExchangeRecoveryUseCase useCase = new ResumeSafeModeAfterExchangeRecoveryUseCase(
            portfolios, runners, manageSafeMode, infrastructure, exchangeAdapters, connections, watchdogFailures,
            metrics, notification, clock, HEALTHY_FOR, 3);

    @BeforeEach
    void healthyExchange() {
        StrategyRunner runner = mock(StrategyRunner.class);
        when(runner.getId()).thenReturn(RUNNER);
        when(runner.getStatus()).thenReturn(RunnerStatus.ACTIVE);
        when(runner.getExchangeId()).thenReturn(EXCHANGE);
        when(runner.getAllowedMarketDataSources()).thenReturn(Set.of(EXCHANGE));
        when(runners.findByPortfolioId(PORTFOLIO)).thenReturn(List.of(runner));
        when(infrastructure.openRestCircuits()).thenReturn(List.of());
        when(exchangeAdapters.isDispatchBlocked(EXCHANGE)).thenReturn(false);
        when(exchangeAdapters.findAdapter(EXCHANGE)).thenReturn(Optional.empty());
        when(connections.getAllConnections()).thenReturn(Map.of());
        portfolioIn(SafeModeOrigin.AUTOMATIC, SafeModeTrigger.EXCHANGE_UNAVAILABLE);
    }

    @Test
    void resumesAfterHealthyMinutesWithAuditMetricAndAlert() {
        runFor(HEALTHY_FOR);
        verify(manageSafeMode, never()).change(any(), any(), any());

        tick();

        ArgumentCaptor<ChangeSafeModeRequest> request = ArgumentCaptor.forClass(ChangeSafeModeRequest.class);
        verify(manageSafeMode).change(eq(PORTFOLIO), eq(SafeModeOrigin.AUTOMATIC), request.capture());
        assertEquals(SafeModeStatus.NORMAL, request.getValue().level());
        assertEquals(SafeModeTrigger.EXCHANGE_UNAVAILABLE, request.getValue().trigger());
        assertEquals("AUTO_RESUME EXCHANGE_UNAVAILABLE: exchange healthy for 5m", request.getValue().reason());
        verify(metrics).recordAutoResume(PORTFOLIO, "EXCHANGE_UNAVAILABLE");
        ArgumentCaptor<ErrorNotificationEvent> event = ArgumentCaptor.forClass(ErrorNotificationEvent.class);
        verify(notification).notifyError(event.capture());
        assertEquals(ErrorNotificationType.SAFE_MODE_AUTO_RESUMED, event.getValue().type());
    }

    @Test
    void unhealthyTickResetsTheCount() {
        runFor(Duration.ofMinutes(4));
        when(infrastructure.openRestCircuits()).thenReturn(List.of(EXCHANGE));
        tick();
        when(infrastructure.openRestCircuits()).thenReturn(List.of());

        runFor(HEALTHY_FOR);
        verify(manageSafeMode, never()).change(any(), any(), any());

        tick();
        verify(manageSafeMode).change(eq(PORTFOLIO), eq(SafeModeOrigin.AUTOMATIC), any());
    }

    @Test
    void eachHealthConditionBlocksTheResume() {
        when(exchangeAdapters.isDispatchBlocked(EXCHANGE)).thenReturn(true);
        runFor(Duration.ofMinutes(10));
        when(exchangeAdapters.isDispatchBlocked(EXCHANGE)).thenReturn(false);

        WebSocketConnectionManager disconnected = mock(WebSocketConnectionManager.class);
        when(disconnected.getConnectionResult()).thenReturn(null);
        when(connections.getAllConnections()).thenReturn(Map.of(ConnectionKey.market(EXCHANGE), disconnected));
        runFor(Duration.ofMinutes(10));
        when(connections.getAllConnections()).thenReturn(Map.of());

        watchdogFailures.failing = true;
        runFor(Duration.ofMinutes(10));

        verify(manageSafeMode, never()).change(any(), any(), any());
    }

    @Test
    void limitPerDayKeepsTheHaltAndAlertsOnce() {
        when(portfolios.countSafeModeChanges(eq(PORTFOLIO), eq(SafeModeOrigin.AUTOMATIC), eq(SafeModeStatus.NORMAL),
                any())).thenReturn(3L);

        runFor(HEALTHY_FOR.plus(TICK));
        runFor(Duration.ofMinutes(10));

        verify(manageSafeMode, never()).change(any(), any(), any());
        verify(metrics, never()).recordAutoResume(any(), any());
        ArgumentCaptor<ErrorNotificationEvent> event = ArgumentCaptor.forClass(ErrorNotificationEvent.class);
        verify(notification, times(1)).notifyError(event.capture());
        assertEquals(ErrorNotificationType.CIRCUIT_BREAKER_TRIGGERED, event.getValue().type());
    }

    @Test
    void haltsThatAreNotAutoResumableAreIgnored() {
        portfolioIn(SafeModeOrigin.AUTOMATIC, SafeModeTrigger.MAX_DRAWDOWN_EXCEEDED);
        runFor(Duration.ofMinutes(10));
        portfolioIn(SafeModeOrigin.MANUAL, null);
        runFor(Duration.ofMinutes(10));

        verifyNoInteractions(manageSafeMode, metrics, notification);
    }

    @Test
    void operatorChangeBetweenReadAndResumeIsRespected() {
        when(manageSafeMode.change(any(), any(), any())).thenThrow(
                new SafeModeTransitionException("AUTO_RESUME_NOT_ALLOWED", "operator changed it"));

        runFor(HEALTHY_FOR.plus(TICK));

        verify(metrics, never()).recordAutoResume(any(), any());
        verifyNoInteractions(notification);
    }

    private void portfolioIn(SafeModeOrigin origin, SafeModeTrigger trigger) {
        Portfolio portfolio = new Portfolio(PORTFOLIO, "p", true, Instant.parse("2026-01-01T00:00:00Z"),
                SafeModeStatus.HALT, CapitalPoolingMode.SHARED, origin, trigger, "setup", clock.instant(), 0L);
        when(portfolios.findAll()).thenReturn(List.of(portfolio));
    }

    /** Ticks a cada 30 s ate cobrir a duracao, comecando pelo instante atual. */
    private void runFor(Duration duration) {
        for (long elapsed = 0; elapsed < duration.toSeconds(); elapsed += TICK.toSeconds()) {
            tick();
        }
    }

    private void tick() {
        useCase.evaluate();
        clock.advance(TICK);
    }

    private static final class WatchdogFailuresStub extends WatchdogFailures {
        private boolean failing;

        @Override
        public boolean anyFailing(java.util.Collection<UUID> runnerIds) {
            return failing && runnerIds.contains(RUNNER);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
