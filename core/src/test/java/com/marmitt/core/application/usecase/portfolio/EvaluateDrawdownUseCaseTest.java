package com.marmitt.core.application.usecase.portfolio;

import com.marmitt.core.domain.portfolio.GlobalBalance;
import com.marmitt.core.domain.portfolio.Portfolio;
import com.marmitt.core.domain.runner.Position;
import com.marmitt.core.dto.notification.ErrorNotificationEvent;
import com.marmitt.core.dto.notification.ErrorNotificationType;
import com.marmitt.core.dto.portfolio.request.ChangeSafeModeRequest;
import com.marmitt.core.enums.CapitalPoolingMode;
import com.marmitt.core.enums.SafeModeOrigin;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.enums.SafeModeTrigger;
import com.marmitt.core.ports.inbound.portfolio.ManageSafeModePort;
import com.marmitt.core.ports.outbound.marketdata.LatestPricePort;
import com.marmitt.core.ports.outbound.metrics.CircuitBreakerMetricsPort;
import com.marmitt.core.ports.outbound.notification.ErrorNotificationPort;
import com.marmitt.core.ports.outbound.repository.EquitySnapshotRepositoryPort;
import com.marmitt.core.ports.outbound.repository.GlobalBalanceRepositoryPort;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T43: drawdown de 24h sobre equity marcado a mercado aciona HALT automatico.
 */
class EvaluateDrawdownUseCaseTest {

    private static final BigDecimal LIMIT = new BigDecimal("0.10");

    private final PortfolioRepositoryPort portfolioRepository = mock(PortfolioRepositoryPort.class);
    private final GlobalBalanceRepositoryPort balanceRepository = mock(GlobalBalanceRepositoryPort.class);
    private final StrategyRunnerRepositoryPort runnerRepository = mock(StrategyRunnerRepositoryPort.class);
    private final ManageSafeModePort manageSafeMode = mock(ManageSafeModePort.class);
    private final ErrorNotificationPort notification = mock(ErrorNotificationPort.class);
    private final CircuitBreakerMetricsPort metrics = mock(CircuitBreakerMetricsPort.class);
    private final FakePrices prices = new FakePrices();
    private final FakeSnapshots snapshots = new FakeSnapshots();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-16T12:00:00Z"));
    private final UUID portfolioId = UUID.randomUUID();

    private Portfolio portfolio;

    @BeforeEach
    void setUp() {
        portfolioAt(SafeModeStatus.NORMAL, null);
        when(runnerRepository.findActivePositionsByPortfolioId(portfolioId)).thenReturn(List.of());
    }

    @Test
    void realizedLossBeyondLimitTripsHalt() {
        balance("10000", "0");
        useCase().evaluate();
        clock.advance(Duration.ofMinutes(10));
        balance("8900", "0");

        useCase().evaluate();

        ArgumentCaptor<ChangeSafeModeRequest> request = ArgumentCaptor.forClass(ChangeSafeModeRequest.class);
        verify(manageSafeMode).change(eq(portfolioId), eq(SafeModeOrigin.AUTOMATIC), request.capture());
        assertEquals(SafeModeStatus.HALT, request.getValue().level());
        assertTrue(request.getValue().reason().startsWith("MAX_DRAWDOWN_EXCEEDED"));
        verify(metrics).recordActivation(portfolioId, "MAX_DRAWDOWN_EXCEEDED");
        ArgumentCaptor<ErrorNotificationEvent> event = ArgumentCaptor.forClass(ErrorNotificationEvent.class);
        verify(notification).notifyError(event.capture());
        assertEquals(ErrorNotificationType.CIRCUIT_BREAKER_TRIGGERED, event.getValue().type());
    }

    @Test
    void lossWithinLimitDoesNotTrip() {
        balance("10000", "0");
        useCase().evaluate();
        balance("9100", "0");

        useCase().evaluate();

        verify(manageSafeMode, never()).change(any(), any(), any());
    }

    @Test
    void floatingLossCountsThroughTheLatestPrice() {
        // Lote de 1 BTC custando 5000 (em reserved); available 5000. Equity inicial = 10000.
        Position lot = Position.open(UUID.randomUUID(), "BTCUSDT", BigDecimal.ONE, new BigDecimal("5000"));
        when(runnerRepository.findActivePositionsByPortfolioId(portfolioId)).thenReturn(List.of(lot));
        balance("5000", "5000");
        prices.record("BTCUSDT", new BigDecimal("5000"));
        useCase().evaluate();
        verify(manageSafeMode, never()).change(any(), any(), any());

        prices.record("BTCUSDT", new BigDecimal("3900"));   // flutuante -1100 => drawdown 11%
        useCase().evaluate();

        verify(manageSafeMode).change(eq(portfolioId), eq(SafeModeOrigin.AUTOMATIC), any());
    }

    @Test
    void positionWithoutPriceSkipsTheEvaluation() {
        Position lot = Position.open(UUID.randomUUID(), "ETHUSDT", BigDecimal.ONE, new BigDecimal("3000"));
        when(runnerRepository.findActivePositionsByPortfolioId(portfolioId)).thenReturn(List.of(lot));
        balance("7000", "3000");

        useCase().evaluate();

        assertTrue(snapshots.samples.isEmpty(), "Equity incompleto nao pode virar amostra");
        verify(metrics, never()).recordDrawdown(any(), any(), any(), any());
    }

    @Test
    void peakOlderThan24hLeavesTheWindow() {
        balance("10000", "0");
        useCase().evaluate();
        clock.advance(Duration.ofHours(25));
        balance("8900", "0");

        useCase().evaluate();

        verify(manageSafeMode, never()).change(any(), any(), any());
    }

    @Test
    void peakSurvivesRestartInsideTheWindow() {
        balance("10000", "0");
        useCase().evaluate();

        // "Restart": nova instancia, mesma persistencia de amostras.
        clock.advance(Duration.ofHours(3));
        balance("8900", "0");
        useCase().evaluate();

        verify(manageSafeMode).change(eq(portfolioId), eq(SafeModeOrigin.AUTOMATIC), any());
    }

    @Test
    void manualResetStartsANewWindow() {
        balance("10000", "0");
        useCase().evaluate();
        clock.advance(Duration.ofHours(1));
        portfolioAt(SafeModeStatus.NORMAL, clock.instant());   // operador desceu HALT -> NORMAL
        clock.advance(Duration.ofMinutes(1));
        balance("8900", "0");

        useCase().evaluate();

        verify(manageSafeMode, never()).change(any(), any(), any());
    }

    @Test
    void portfolioAlreadyInSafeModeIsMeasuredButNotTrippedAgain() {
        portfolioAt(SafeModeStatus.HALT, clock.instant());
        balance("10000", "0");
        useCase().evaluate();
        balance("5000", "0");

        useCase().evaluate();

        verify(manageSafeMode, never()).change(any(), any(), any());
        assertEquals(2, snapshots.samples.size());
    }

    @Test
    void samplesOlderThanTheWindowArePruned() {
        balance("10000", "0");
        useCase().evaluate();
        clock.advance(Duration.ofHours(25));

        useCase().evaluate();

        assertEquals(1, snapshots.samples.size());
    }

    // ---------------------------------------------------------------- setup

    private EvaluateDrawdownUseCase useCase() {
        AutomaticSafeModeTrigger trigger = new AutomaticSafeModeTrigger(portfolioRepository, manageSafeMode, metrics,
                notification, clock, EnumSet.allOf(SafeModeTrigger.class));
        return new EvaluateDrawdownUseCase(portfolioRepository, balanceRepository, runnerRepository, prices,
                snapshots, trigger, metrics, LIMIT, clock);
    }

    private void portfolioAt(SafeModeStatus status, Instant changedAt) {
        portfolio = new Portfolio(portfolioId, "p", true, Instant.parse("2026-01-01T00:00:00Z"), status,
                CapitalPoolingMode.SHARED, changedAt == null ? null : SafeModeOrigin.MANUAL, null,
                changedAt == null ? null : "setup", changedAt, 0L);
        when(portfolioRepository.findAll()).thenReturn(List.of(portfolio));
        when(portfolioRepository.findById(portfolioId)).thenReturn(Optional.of(portfolio));
    }

    private void balance(String available, String reserved) {
        when(balanceRepository.findByPortfolioId(portfolioId)).thenReturn(Optional.of(new GlobalBalance(
                portfolioId, new BigDecimal(available), new BigDecimal(reserved), BigDecimal.ZERO,
                new BigDecimal("10000"), "USDT", BigDecimal.ZERO, null, Instant.now(), 0L)));
    }

    private static final class FakePrices implements LatestPricePort {
        private final Map<String, BigDecimal> prices = new HashMap<>();

        @Override
        public void record(String symbol, BigDecimal price) {
            prices.put(symbol, price);
        }

        @Override
        public Optional<BigDecimal> find(String symbol) {
            return Optional.ofNullable(prices.get(symbol));
        }

        @Override
        public Optional<Instant> findUpdatedAt(String symbol) {
            return Optional.empty();
        }
    }

    private static final class FakeSnapshots implements EquitySnapshotRepositoryPort {
        private final List<Sample> samples = new ArrayList<>();

        @Override
        public void record(UUID portfolioId, BigDecimal equity, Instant sampledAt) {
            samples.add(new Sample(portfolioId, equity, sampledAt));
        }

        @Override
        public Optional<BigDecimal> peakSince(UUID portfolioId, Instant since) {
            return samples.stream()
                    .filter(s -> s.portfolioId().equals(portfolioId) && !s.sampledAt().isBefore(since))
                    .map(Sample::equity)
                    .max(BigDecimal::compareTo);
        }

        @Override
        public int deleteBefore(Instant cutoff) {
            int before = samples.size();
            samples.removeIf(s -> s.sampledAt().isBefore(cutoff));
            return before - samples.size();
        }

        private record Sample(UUID portfolioId, BigDecimal equity, Instant sampledAt) {
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
