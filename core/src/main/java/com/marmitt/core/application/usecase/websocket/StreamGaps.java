package com.marmitt.core.application.usecase.websocket;

import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.dto.connection.ConnectionKey;
import com.marmitt.core.enums.ConnectionStatus;
import com.marmitt.core.enums.RunnerStatus;
import com.marmitt.core.enums.StreamChannel;
import com.marmitt.core.ports.outbound.exchange.ExchangeAdapterDescriptor;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Streams que runners precisam ({@link StreamDemand}) e que nao estao conectados: o stream de usuario de cada
 * exchange que o tem e o market data de cada fonte registrada. Usado pelo estado consolidado
 * ({@code STREAM_DISCONNECTED}) e pela retomada automatica do Safe Mode (T71), para os dois enxergarem a mesma
 * falta.
 */
public final class StreamGaps {

    /**
     * @param status status da conexao; nulo quando o stream de usuario nunca foi aberto
     * @param neededByActive se um runner {@code ACTIVE} precisa do stream (opera sem ele)
     */
    public record StreamGap(String exchange, StreamChannel channel, ConnectionStatus status, boolean neededByActive) {
    }

    private final WebSocketConnectionRepositoryPort connections;
    private final ExchangeAdapterRepositoryPort exchangeAdapters;

    public StreamGaps(WebSocketConnectionRepositoryPort connections, ExchangeAdapterRepositoryPort exchangeAdapters) {
        this.connections = connections;
        this.exchangeAdapters = exchangeAdapters;
    }

    public List<StreamGap> of(Collection<StrategyRunner> runners) {
        Map<ConnectionKey, ConnectionStatus> statuses = new LinkedHashMap<>();
        connections.getAllConnections().forEach((key, manager) -> statuses.put(key,
                manager.getConnectionResult() == null ? null : manager.getConnectionResult().status()));

        Set<String> exchanges = new TreeSet<>();
        Set<String> marketSources = new TreeSet<>();
        Set<String> activeExchanges = new TreeSet<>();
        Set<String> activeSources = new TreeSet<>();
        for (StrategyRunner runner : runners) {
            if (!StreamDemand.needsStreams(runner)) {
                continue;
            }
            exchanges.add(runner.getExchangeId());
            marketSources.addAll(runner.getAllowedMarketDataSources());
            if (runner.getStatus() == RunnerStatus.ACTIVE) {
                activeExchanges.add(runner.getExchangeId());
                activeSources.addAll(runner.getAllowedMarketDataSources());
            }
        }
        List<StreamGap> gaps = new ArrayList<>();
        for (String exchange : exchanges) {
            boolean needsUserStream = exchangeAdapters.findAdapter(exchange)
                    .map(ExchangeAdapterDescriptor::hasUserStream).orElse(false);
            ConnectionStatus status = statuses.get(new ConnectionKey(exchange, StreamChannel.USER_DATA));
            if (needsUserStream && status != ConnectionStatus.CONNECTED) {
                gaps.add(new StreamGap(exchange, StreamChannel.USER_DATA, status, activeExchanges.contains(exchange)));
            }
        }
        for (String source : marketSources) {
            ConnectionKey key = ConnectionKey.market(source);
            if (statuses.containsKey(key) && statuses.get(key) != ConnectionStatus.CONNECTED) {
                gaps.add(new StreamGap(source, StreamChannel.MARKET, statuses.get(key), activeSources.contains(source)));
            }
        }
        return gaps;
    }
}
