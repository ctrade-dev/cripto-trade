package com.marmitt.core.dto.events;

import com.marmitt.core.enums.StreamChannel;

import java.time.Instant;
import java.util.UUID;

/**
 * Falha de uma conexao de WebSocket. {@code attemptCount} so tem valor na falha critica: quantas tentativas de
 * reconexao seguidas falharam. A falha comum leva 0; quem conta as tentativas e a estrategia de reconexao.
 */
public record WebSocketFailedEvent(
        String exchange,
        String reason,
        UUID connectionId,
        Throwable cause,
        Instant timestamp,
        boolean isCritical,
        int attemptCount,
        StreamChannel channel
) {

    public static WebSocketFailedEvent of(String exchange, String reason, UUID connectionId, Throwable cause, StreamChannel channel) {
        return new WebSocketFailedEvent(exchange, reason, connectionId, cause, Instant.now(), false, 0, channel);
    }

    public static WebSocketFailedEvent critical(String exchange, String reason, StreamChannel channel, int attempts) {
        return new WebSocketFailedEvent(exchange, reason, null, null, Instant.now(), true, attempts, channel);
    }
}
