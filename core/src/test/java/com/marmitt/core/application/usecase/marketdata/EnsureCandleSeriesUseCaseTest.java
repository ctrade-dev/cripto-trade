package com.marmitt.core.application.usecase.marketdata;

import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.dto.strategy.CandleDto;
import com.marmitt.core.dto.strategy.CandleInterval;
import com.marmitt.core.dto.strategy.CandleRequirement;
import com.marmitt.core.dto.strategy.CandleSeriesKey;
import com.marmitt.core.dto.strategy.StrategyRequirements;
import com.marmitt.core.enums.RunnerStatus;
import com.marmitt.core.exceptions.ExchangeQueryException;
import com.marmitt.core.ports.outbound.exchange.ExchangeAdapterDescriptor;
import com.marmitt.core.ports.outbound.exchange.rest.ExchangeCandleQueryPort;
import com.marmitt.core.ports.outbound.marketdata.CandleHistoryPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import com.marmitt.core.ports.outbound.strategy.TradingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** T72: series por demanda, carregadas e mantidas pela REST, sem consultar a exchange quando em dia. */
class EnsureCandleSeriesUseCaseTest {

    private static final UUID STRATEGY = UUID.randomUUID();
    private static final Duration RETRY = Duration.ofSeconds(10);
    private static final CandleSeriesKey KEY = new CandleSeriesKey("BINANCE", "BTCUSDT", CandleInterval.M1);

    private final StrategyRunnerRepositoryPort runners = mock(StrategyRunnerRepositoryPort.class);
    private final StrategyRepositoryPort strategies = mock(StrategyRepositoryPort.class);
    private final ExchangeAdapterRepositoryPort adapters = mock(ExchangeAdapterRepositoryPort.class);
    private final TradingStrategy strategy = mock(TradingStrategy.class);
    private final FakeExchange exchange = new FakeExchange();
    private final MapStore store = new MapStore();
    private final CandleSeriesGaps gaps = new CandleSeriesGaps();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-20T10:00:30Z"));
    private final EnsureCandleSeriesUseCase useCase =
            new EnsureCandleSeriesUseCase(runners, strategies, adapters, store, gaps, clock, RETRY);

    @BeforeEach
    void binanceRunnerDeclaringMinutes() {
        ExchangeAdapterDescriptor descriptor = mock(ExchangeAdapterDescriptor.class);
        when(descriptor.hasCandleQuery()).thenReturn(true);
        when(descriptor.candleQuery()).thenReturn(exchange);
        when(adapters.findAdapter("BINANCE")).thenReturn(Optional.of(descriptor));
        when(strategies.findById(STRATEGY)).thenReturn(Optional.of(strategy));
        declare(new CandleRequirement(CandleInterval.M1, 3));
        List<StrategyRunner> active = List.of(runner(RunnerStatus.ACTIVE));
        when(runners.findAll()).thenReturn(active);
    }

    @Test
    void loadsTheSeriesAndDoesNotAskAgainUntilTheNextClose() {
        useCase.ensure();
        clock.advance(Duration.ofSeconds(20));
        useCase.ensure();

        assertEquals(List.of(3), exchange.limits);
        assertEquals(3, store.series(KEY).size());
        assertEquals(Instant.parse("2026-11-20T09:59:00Z"), store.series(KEY).getLast().openTime());
        assertTrue(gaps.current().isEmpty());

        clock.advance(Duration.ofSeconds(15)); // 10:01:05: o candle de 10:00 fechou
        useCase.ensure();

        assertEquals(List.of(3, 1), exchange.limits);
        assertEquals(Instant.parse("2026-11-20T10:00:00Z"), store.series(KEY).getLast().openTime());
        assertEquals(3, store.series(KEY).size());
    }

    @Test
    void longOutageReloadsTheWholeSeries() {
        useCase.ensure();
        clock.advance(Duration.ofMinutes(10));
        useCase.ensure();

        assertEquals(List.of(3, 3), exchange.limits);
        assertEquals(Instant.parse("2026-11-20T10:09:00Z"), store.series(KEY).getLast().openTime());
    }

    @Test
    void demandIsTheLargestCountAndUnusedSeriesAreDropped() {
        StrategyRunner other = runner(RunnerStatus.HALTED);
        List<StrategyRunner> both = List.of(runner(RunnerStatus.ACTIVE), other);
        when(runners.findAll()).thenReturn(both);
        TradingStrategy bigger = mock(TradingStrategy.class);
        when(bigger.requirements()).thenReturn(new StrategyRequirements(0,
                List.of(new CandleRequirement(CandleInterval.M1, 5))));
        when(strategies.findById(other.getStrategyId())).thenReturn(Optional.of(bigger));

        useCase.ensure();
        assertEquals(5, store.series(KEY).size());

        List<StrategyRunner> archived = List.of(runner(RunnerStatus.ARCHIVED));
        when(runners.findAll()).thenReturn(archived);
        useCase.ensure();
        assertTrue(store.series(KEY).isEmpty());
        assertTrue(gaps.current().isEmpty());
    }

    @Test
    void intervalTheExchangeDoesNotOfferIsReportedWithoutAsking() {
        exchange.supported = EnumSet.of(CandleInterval.H1);

        useCase.ensure();

        assertTrue(exchange.limits.isEmpty());
        assertEquals(CandleSeriesGaps.Reason.UNSUPPORTED, gaps.current().getFirst().reason());
    }

    @Test
    void exchangeFailureWaitsBeforeAskingAgain() {
        exchange.failing = true;
        useCase.ensure();
        clock.advance(Duration.ofSeconds(5));
        useCase.ensure();

        assertEquals(1, exchange.limits.size());
        CandleSeriesGaps.Gap gap = gaps.current().getFirst();
        assertEquals(CandleSeriesGaps.Reason.INCOMPLETE, gap.reason());
        assertEquals(Instant.parse("2026-11-20T10:00:30Z"), gap.since());

        exchange.failing = false;
        clock.advance(RETRY);
        useCase.ensure();

        assertEquals(3, store.series(KEY).size());
        assertTrue(gaps.current().isEmpty());
    }

    private void declare(CandleRequirement... candles) {
        when(strategy.requirements()).thenReturn(new StrategyRequirements(0, List.of(candles)));
    }

    private static StrategyRunner runner(RunnerStatus status) {
        StrategyRunner runner = mock(StrategyRunner.class);
        when(runner.getStatus()).thenReturn(status);
        when(runner.getStrategyId()).thenReturn(status == RunnerStatus.HALTED ? UUID.randomUUID() : STRATEGY);
        when(runner.getSymbol()).thenReturn("BTCUSDT");
        when(runner.getAllowedMarketDataSources()).thenReturn(Set.of("binance"));
        return runner;
    }

    /** Exchange com candles de minuto ate o ultimo fechado. */
    private static final class FakeExchange implements ExchangeCandleQueryPort {
        private final List<Integer> limits = new ArrayList<>();
        private Set<CandleInterval> supported = EnumSet.allOf(CandleInterval.class);
        private boolean failing;

        @Override
        public Set<CandleInterval> supportedIntervals() {
            return supported;
        }

        @Override
        public List<CandleDto> fetchClosed(String symbol, CandleInterval interval, Instant endExclusive, int limit) {
            limits.add(limit);
            if (failing) {
                throw new ExchangeQueryException("BINANCE", ExchangeQueryException.ErrorType.TEMPORARY, "down");
            }
            long step = interval.duration().toMillis();
            long lastOpen = Math.floorDiv(endExclusive.toEpochMilli(), step) * step - step;
            List<CandleDto> candles = new ArrayList<>();
            for (int i = limit - 1; i >= 0; i--) {
                candles.add(new CandleDto(Instant.ofEpochMilli(lastOpen - i * step), BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
            }
            return candles;
        }
    }

    private static final class MapStore implements CandleHistoryPort {
        private final Map<CandleSeriesKey, List<CandleDto>> series = new HashMap<>();

        @Override
        public List<CandleDto> series(CandleSeriesKey key) {
            return series.getOrDefault(key, List.of());
        }

        @Override
        public void replace(CandleSeriesKey key, List<CandleDto> candles) {
            series.put(key, List.copyOf(candles));
        }

        @Override
        public void retainOnly(Set<CandleSeriesKey> keys) {
            series.keySet().retainAll(keys);
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
