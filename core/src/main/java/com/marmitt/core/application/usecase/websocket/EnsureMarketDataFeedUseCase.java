package com.marmitt.core.application.usecase.websocket;

import com.marmitt.core.domain.Symbol;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.dto.common.CurrencyPair;
import com.marmitt.core.dto.connection.ConnectionKey;
import com.marmitt.core.dto.connection.ConnectionResultDto;
import com.marmitt.core.dto.websocket.request.StreamSubscriptionRequest;
import com.marmitt.core.dto.wrapper.WebSocketConnectionManager;
import com.marmitt.core.enums.ConnectionStatus;
import com.marmitt.core.enums.StreamAction;
import com.marmitt.core.enums.StreamChannel;
import com.marmitt.core.enums.StreamType;
import com.marmitt.core.ports.inbound.websocket.ConnectMarketStreamPort;
import com.marmitt.core.ports.inbound.websocket.ConnectUserStreamPort;
import com.marmitt.core.ports.inbound.websocket.EnsureMarketDataFeedPort;
import com.marmitt.core.ports.inbound.websocket.SendMessageWebSocketPort;
import com.marmitt.core.ports.outbound.exchange.ExchangeAdapterDescriptor;
import com.marmitt.core.ports.outbound.marketdata.LatestPricePort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Coordenador de demanda de market data (T56). O feed (conexoes, assinaturas) nao sabe de runners e os
 * runners nao sabem do feed; este use case conhece os dois pelos ports e alinha um ao outro.
 *
 * <p><b>Demanda</b> (regra de negocio, {@link StreamDemand}): runners {@code ACTIVE}, {@code TERMINATING} e
 * {@code HALTED} precisam de tick do seu simbolo em cada fonte de {@code allowedMarketDataSources}, e do
 * stream de usuario da sua exchange quando o adapter tem um. Runner de outro status com lote aberto tambem
 * precisa do tick, so para marcar o lote a mercado (drawdown, PnL; T58): o roteamento do tick continua
 * filtrando por status, entao runner parado nao negocia.
 *
 * <p><b>Evidencia, nao registro:</b> o estado das assinaturas nao e guardado e uma reconexao pode
 * perde-lo, entao a decisao olha o que de fato acontece:
 * <ul>
 *   <li>conexao de market data da fonte ausente ou caida: abre com todos os simbolos demandados;</li>
 *   <li>conexao conectada e simbolo demandado que ela nunca recebeu como pedido (o historico de pedidos da
 *       propria conexao): assina na hora. A conexao abre com os simbolos dos runners ja ativos; os que
 *       entram depois, como numa largada com varios simbolos, nao esperam o limiar (T53 F27);</li>
 *   <li>conexao conectada ha mais que o limiar e simbolo sem tick ha mais que o limiar: assina de novo
 *       o simbolo (assinar de novo e inofensivo no MOCK e na Binance);</li>
 *   <li>assinatura recusada pelo socket: a conexao nao esta de pe, seja o que for que o estado diga. Fica
 *       {@code ERROR} e o ciclo seguinte reabre (T68);</li>
 *   <li>stream de usuario demandado ausente ou caido: abre.</li>
 * </ul>
 * Conexao abrindo ou reconectando e deixada em paz: a estrategia de reconexao esta trabalhando.
 * Nada e cancelado quando a demanda some (decisao da T56).
 */
@Slf4j
public class EnsureMarketDataFeedUseCase implements EnsureMarketDataFeedPort {

    /** Estados em que a conexao precisa ser aberta de novo. */
    private static final Set<ConnectionStatus> DOWN = Set.of(ConnectionStatus.IDLE, ConnectionStatus.DISCONNECTED,
            ConnectionStatus.CLOSED, ConnectionStatus.ERROR);

    private final StrategyRunnerRepositoryPort runnerRepository;
    private final ExchangeAdapterRepositoryPort exchangeAdapters;
    private final WebSocketConnectionRepositoryPort connections;
    private final LatestPricePort latestPrices;
    private final ConnectMarketStreamPort connectMarketStream;
    private final ConnectUserStreamPort connectUserStream;
    private final SendMessageWebSocketPort sendMessage;
    private final Function<String, StreamType> streamTypeByExchange;
    private final Duration staleThreshold;
    private final Clock clock;
    private final OpenLotDemand openLotDemand;

    public EnsureMarketDataFeedUseCase(StrategyRunnerRepositoryPort runnerRepository,
                                       ExchangeAdapterRepositoryPort exchangeAdapters,
                                       WebSocketConnectionRepositoryPort connections,
                                       LatestPricePort latestPrices,
                                       ConnectMarketStreamPort connectMarketStream,
                                       ConnectUserStreamPort connectUserStream,
                                       SendMessageWebSocketPort sendMessage,
                                       Function<String, StreamType> streamTypeByExchange,
                                       Duration staleThreshold,
                                       Clock clock) {
        this.runnerRepository = runnerRepository;
        this.exchangeAdapters = exchangeAdapters;
        this.connections = connections;
        this.latestPrices = latestPrices;
        this.connectMarketStream = connectMarketStream;
        this.connectUserStream = connectUserStream;
        this.sendMessage = sendMessage;
        this.streamTypeByExchange = streamTypeByExchange;
        this.staleThreshold = staleThreshold;
        this.clock = clock;
        this.openLotDemand = new OpenLotDemand(runnerRepository);
    }

    @Override
    public synchronized void ensure() {
        Map<String, Set<String>> symbolsBySource = new TreeMap<>();
        Set<String> userStreamExchanges = new TreeSet<>();
        List<StrategyRunner> runners = runnerRepository.findAll();
        for (StrategyRunner runner : runners) {
            if (!StreamDemand.needsStreams(runner)) {
                continue;
            }
            demandSymbol(symbolsBySource, runner);
            userStreamExchanges.add(runner.getExchangeId());
        }
        openLotDemand.runnersWithOpenLots(runners).forEach(runner -> demandSymbol(symbolsBySource, runner));

        Instant now = clock.instant();
        symbolsBySource.forEach((source, symbols) -> ensureMarket(source, symbols, now));
        userStreamExchanges.forEach(this::ensureUserStream);
    }

    private static void demandSymbol(Map<String, Set<String>> symbolsBySource, StrategyRunner runner) {
        for (String source : runner.getAllowedMarketDataSources()) {
            symbolsBySource.computeIfAbsent(source.toUpperCase(), s -> new TreeSet<>()).add(runner.getSymbol());
        }
    }

    private void ensureMarket(String source, Set<String> symbols, Instant now) {
        if (exchangeAdapters.findAdapter(source).isEmpty()) {
            log.warn("marketData: no adapter for market data source={} demanded by runners {}", source, symbols);
            return;
        }
        Optional<ConnectionResultDto> connection = connectionResult(ConnectionKey.market(source));
        ConnectionStatus status = connection.map(ConnectionResultDto::status).orElse(null);
        if (status == null || DOWN.contains(status)) {
            try {
                log.info("marketData: connecting {} for {} - connection {}", source, symbols,
                        status == null ? "not opened" : status);
                connectMarketStream.execute(source, request(source, symbols));
            } catch (RuntimeException e) {
                log.error("marketData: connect failed source={} - {}", source, e.getMessage(), e);
            }
            return;
        }
        if (status != ConnectionStatus.CONNECTED) {
            return;
        }
        WebSocketConnectionManager manager = connections.getConnection(ConnectionKey.market(source));
        Set<String> requested = requestedSymbols(manager);
        boolean settled = olderThan(connection.get().timestamp(), now);
        List<String> missing = symbols.stream()
                .filter(symbol -> latestPrices.findUpdatedAt(symbol).map(at -> olderThan(at, now)).orElse(true))
                .filter(symbol -> settled || !requested.contains(symbol))
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        try {
            log.info("marketData: subscribing {} on {} - not requested on this connection or no tick for more than {}",
                    missing, source, staleThreshold);
            sendMessage.execute(request(source, missing));
        } catch (RuntimeException e) {
            log.error("marketData: subscribe failed source={} symbols={} - {}", source, missing, e.getMessage(), e);
            markDown(ConnectionKey.market(source), e);
        }
    }

    /** Simbolos ja pedidos a conexao (abertura e assinaturas), pela assinatura acumulada que ela mesma guarda. */
    private static Set<String> requestedSymbols(WebSocketConnectionManager manager) {
        Set<String> requested = new TreeSet<>();
        manager.accumulatedSubscription().ifPresent(subscription -> subscription.getCurrencyPairs()
                .forEach(pair -> requested.add(pair.baseCurrency() + pair.quoteCurrency())));
        return requested;
    }

    private void markDown(ConnectionKey key, RuntimeException cause) {
        WebSocketConnectionManager manager = connections.getConnection(key);
        if (manager.getConnectionResult().isConnected()) {
            manager.setConnectionResult(ConnectionResultDto.failure("Subscribe rejected", manager.getConnectionId(), cause));
        }
    }

    private void ensureUserStream(String exchange) {
        boolean hasUserStream = exchangeAdapters.findAdapter(exchange)
                .map(ExchangeAdapterDescriptor::hasUserStream).orElse(false);
        if (!hasUserStream) {
            return;
        }
        ConnectionStatus status = connectionResult(new ConnectionKey(exchange, StreamChannel.USER_DATA))
                .map(ConnectionResultDto::status).orElse(null);
        if (status != null && !DOWN.contains(status)) {
            return;
        }
        try {
            log.info("marketData: connecting user stream of {} - connection {}", exchange,
                    status == null ? "not opened" : status);
            connectUserStream.execute(exchange);
        } catch (RuntimeException e) {
            log.error("marketData: user stream connect failed exchange={} - {}", exchange, e.getMessage(), e);
        }
    }

    private Optional<ConnectionResultDto> connectionResult(ConnectionKey key) {
        if (!connections.hasConnection(key)) {
            return Optional.empty();
        }
        return Optional.ofNullable(connections.getConnection(key)).map(WebSocketConnectionManager::getConnectionResult);
    }

    private StreamSubscriptionRequest request(String source, java.util.Collection<String> symbols) {
        StreamType type = streamTypeByExchange.apply(source);
        List<CurrencyPair> pairs = symbols.stream()
                .map(Symbol::of)
                .map(symbol -> new CurrencyPair(symbol.getBaseAsset(), symbol.getQuoteAsset(), type))
                .toList();
        return new StreamSubscriptionRequest(source, pairs, StreamAction.SUBSCRIBE);
    }

    private boolean olderThan(Instant instant, Instant now) {
        return instant == null || Duration.between(instant, now).compareTo(staleThreshold) > 0;
    }
}
