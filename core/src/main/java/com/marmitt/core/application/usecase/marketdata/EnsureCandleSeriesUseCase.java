package com.marmitt.core.application.usecase.marketdata;

import com.marmitt.core.application.usecase.websocket.StreamDemand;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.dto.strategy.CandleDto;
import com.marmitt.core.dto.strategy.CandleRequirement;
import com.marmitt.core.dto.strategy.CandleSeriesKey;
import com.marmitt.core.dto.strategy.StrategyRequirements;
import com.marmitt.core.ports.inbound.marketdata.EnsureCandleSeriesPort;
import com.marmitt.core.ports.outbound.exchange.ExchangeAdapterDescriptor;
import com.marmitt.core.ports.outbound.exchange.rest.ExchangeCandleQueryPort;
import com.marmitt.core.ports.outbound.marketdata.CandleHistoryPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import com.marmitt.core.ports.outbound.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Series de candles por demanda (T72).
 *
 * <p><b>Demanda:</b> runners que precisam dos streams ({@link StreamDemand}) cuja estrategia declara candles, em
 * cada fonte de {@code allowedMarketDataSources}: {@code (fonte, simbolo, intervalo)} com o maior {@code count}
 * pedido. Sem demanda, a serie e descartada.
 *
 * <p><b>Carga e manutencao pela REST:</b> serie vazia, curta ou atrasada pede a exchange os candles fechados que
 * faltam. A mesma chamada carrega, atualiza depois de cada fechamento e cobre buraco de uma queda. Chamado a cada
 * segundo: a serie em dia nao consulta nada ate o proximo fechamento. Falha da exchange espera
 * {@code retryDelay} antes de tentar de novo a mesma serie.
 */
@Slf4j
public class EnsureCandleSeriesUseCase implements EnsureCandleSeriesPort {

    private final StrategyRunnerRepositoryPort runnerRepository;
    private final StrategyRepositoryPort strategyRepository;
    private final ExchangeAdapterRepositoryPort exchangeAdapters;
    private final CandleHistoryPort store;
    private final CandleSeriesGaps gaps;
    private final Clock clock;
    private final Duration retryDelay;

    private final Map<CandleSeriesKey, Instant> retryAfter = new ConcurrentHashMap<>();
    private final Map<CandleSeriesKey, Instant> notReadySince = new ConcurrentHashMap<>();
    private final Set<CandleSeriesKey> unsupportedLogged = ConcurrentHashMap.newKeySet();

    public EnsureCandleSeriesUseCase(StrategyRunnerRepositoryPort runnerRepository,
                                     StrategyRepositoryPort strategyRepository,
                                     ExchangeAdapterRepositoryPort exchangeAdapters,
                                     CandleHistoryPort store,
                                     CandleSeriesGaps gaps,
                                     Clock clock,
                                     Duration retryDelay) {
        this.runnerRepository = runnerRepository;
        this.strategyRepository = strategyRepository;
        this.exchangeAdapters = exchangeAdapters;
        this.store = store;
        this.gaps = gaps;
        this.clock = clock;
        this.retryDelay = retryDelay;
    }

    @Override
    public synchronized void ensure() {
        Map<CandleSeriesKey, Integer> demand = demand();
        store.retainOnly(demand.keySet());
        retryAfter.keySet().retainAll(demand.keySet());
        notReadySince.keySet().retainAll(demand.keySet());
        unsupportedLogged.retainAll(demand.keySet());

        Instant now = clock.instant();
        List<CandleSeriesGaps.Gap> found = new ArrayList<>();
        demand.forEach((key, count) -> ensure(key, count, now).ifPresent(found::add));
        gaps.publish(found);
    }

    private Map<CandleSeriesKey, Integer> demand() {
        Map<CandleSeriesKey, Integer> demand = new TreeMap<>(Comparator.comparing(CandleSeriesKey::toString));
        for (StrategyRunner runner : runnerRepository.findAll()) {
            if (!StreamDemand.needsStreams(runner)) {
                continue;
            }
            List<CandleRequirement> candles = strategyRepository.findById(runner.getStrategyId())
                    .map(TradingStrategy::requirements)
                    .map(StrategyRequirements::candles)
                    .orElse(List.of());
            for (CandleRequirement candle : candles) {
                for (String source : runner.getAllowedMarketDataSources()) {
                    demand.merge(new CandleSeriesKey(source, runner.getSymbol(), candle.interval()), candle.count(),
                            Math::max);
                }
            }
        }
        return demand;
    }

    private Optional<CandleSeriesGaps.Gap> ensure(CandleSeriesKey key, int count, Instant now) {
        Optional<ExchangeCandleQueryPort> query = exchangeAdapters.findAdapter(key.exchangeId())
                .filter(ExchangeAdapterDescriptor::hasCandleQuery)
                .map(ExchangeAdapterDescriptor::candleQuery)
                .filter(candles -> candles.supportedIntervals().contains(key.interval()));
        if (query.isEmpty()) {
            if (unsupportedLogged.add(key)) {
                log.warn("candles: {} does not offer {} candles - runners declaring it get no decision",
                        key.exchangeId(), key.interval().code());
            }
            return Optional.of(new CandleSeriesGaps.Gap(key, CandleSeriesGaps.Reason.UNSUPPORTED,
                    notReadySince.computeIfAbsent(key, k -> now), "interval not offered by the exchange"));
        }

        List<CandleDto> stored = store.series(key);
        boolean complete = stored.size() >= count && CandleSeries.upToDate(stored, key.interval(), now);
        if (!complete && !now.isBefore(retryAfter.getOrDefault(key, Instant.MIN))) {
            stored = fetch(query.get(), key, stored, count, now);
            complete = stored.size() >= count && CandleSeries.upToDate(stored, key.interval(), now);
        }
        if (complete) {
            notReadySince.remove(key);
            return Optional.empty();
        }
        return Optional.of(new CandleSeriesGaps.Gap(key, CandleSeriesGaps.Reason.INCOMPLETE,
                notReadySince.computeIfAbsent(key, k -> now), "have " + stored.size() + " of " + count));
    }

    private List<CandleDto> fetch(ExchangeCandleQueryPort query, CandleSeriesKey key, List<CandleDto> stored,
                                  int count, Instant now) {
        int missing = missingSinceLast(stored, key, now);
        boolean reload = stored.size() < count || missing >= count;
        int limit = reload ? count : Math.max(1, missing);
        try {
            // So o que ja consolidou (CandleSeries.SETTLE): o candle recem-fechado ainda pode receber negocios.
            List<CandleDto> fetched = query.fetchClosed(key.symbol(), key.interval(), CandleSeries.settledAt(now),
                    limit);
            List<CandleDto> merged = CandleSeries.merge(reload ? List.of() : stored, fetched, key.interval(), count);
            store.replace(key, merged);
            if (merged.size() < count) {
                // Historico curto (simbolo novo): nao adianta perguntar de novo a cada segundo.
                retryAfter.put(key, now.plus(retryDelay));
            } else {
                // Em dia, ou a exchange ainda nao publicou o candle recem-fechado: o ciclo seguinte pergunta de novo.
                retryAfter.remove(key);
            }
            log.debug("candles: {} fetched={} stored={} of {}", key, fetched.size(), merged.size(), count);
            return merged;
        } catch (RuntimeException e) {
            retryAfter.put(key, now.plus(retryDelay));
            log.warn("candles: fetch failed {} - retry in {} - {}", key, retryDelay, e.getMessage());
            return stored;
        }
    }

    /** Candles que consolidaram depois do ultimo guardado, ate {@code now}. */
    private static int missingSinceLast(List<CandleDto> stored, CandleSeriesKey key, Instant now) {
        if (stored.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        Instant nextOpen = stored.getLast().openTime().plus(key.interval().duration());
        long missing = Duration.between(nextOpen, CandleSeries.settledAt(now)).dividedBy(key.interval().duration());
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, missing));
    }
}
