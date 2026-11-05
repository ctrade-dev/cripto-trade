package com.marmitt.core.application.handler.connection;

import com.marmitt.core.dto.connection.ConnectionKey;
import com.marmitt.core.dto.events.WebSocketClosedEvent;
import com.marmitt.core.dto.events.WebSocketConnectedEvent;
import com.marmitt.core.dto.events.WebSocketFailedEvent;
import com.marmitt.core.enums.StreamChannel;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ConnectionLostOrderDispatchGuard {

    private final ExchangeAdapterRepositoryPort adapterRepository;
    private final WebSocketConnectionRepositoryPort connections;
    private final ConcurrentHashMap<String, Set<StreamChannel>> blockedChannels = new ConcurrentHashMap<>();

    public ConnectionLostOrderDispatchGuard(ExchangeAdapterRepositoryPort adapterRepository,
                                            WebSocketConnectionRepositoryPort connections) {
        this.adapterRepository = adapterRepository;
        this.connections = connections;
    }

    public void onConnectionClosed(WebSocketClosedEvent event) {
        if (event.wasExpected()) {
            return;
        }
        StreamChannel channel = event.channel();
        if (channel != StreamChannel.MARKET && channel != StreamChannel.USER_DATA) {
            return;
        }
        String exchange = event.exchange();
        if (isSuperseded(exchange, channel, event.connectionId())) {
            return;
        }
        blockedChannels.computeIfAbsent(exchange, k -> ConcurrentHashMap.newKeySet()).add(channel);
        log.warn("Unexpected connection loss on exchange={} channel={} — blocking order dispatch",
                exchange, channel);
        adapterRepository.blockDispatch(exchange);
    }

    public void onConnectionFailed(WebSocketFailedEvent event) {
        StreamChannel channel = event.channel();
        if (channel != StreamChannel.MARKET && channel != StreamChannel.USER_DATA) {
            return;
        }
        String exchange = event.exchange();
        if (isSuperseded(exchange, channel, event.connectionId())) {
            return;
        }
        blockedChannels.computeIfAbsent(exchange, k -> ConcurrentHashMap.newKeySet()).add(channel);
        log.warn("Connection failure on exchange={} channel={} — blocking order dispatch",
                exchange, channel);
        adapterRepository.blockDispatch(exchange);
    }

    /** Evento do socket antigo: a conexao atual segue de pe e nenhuma reconexao viria liberar o envio. */
    private boolean isSuperseded(String exchange, StreamChannel channel, UUID connectionId) {
        ConnectionKey key = new ConnectionKey(exchange, channel);
        return connections.hasConnection(key) && connections.getConnection(key).isSupersededBy(connectionId);
    }

    public void onConnectionReestablished(WebSocketConnectedEvent event) {
        String exchange = event.exchange();
        Set<StreamChannel> pending = blockedChannels.get(exchange);
        if (pending == null || !pending.remove(event.channel())) {
            return;
        }
        if (pending.isEmpty()) {
            blockedChannels.remove(exchange);
            log.info("All lost channels reconnected on exchange={} — unblocking order dispatch", exchange);
            adapterRepository.unblockDispatch(exchange);
        } else {
            log.info("Channel {} reconnected but {} still recovering on exchange={} — dispatch remains blocked",
                    event.channel(), pending, exchange);
        }
    }
}
