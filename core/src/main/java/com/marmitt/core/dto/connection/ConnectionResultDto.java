package com.marmitt.core.dto.connection;

import com.marmitt.core.enums.ConnectionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ConnectionResultDto(
        ConnectionStatus status,
        String message,
        Instant timestamp,
        UUID connectionId,
        Map<String, Object> metadata) {

    public static ConnectionResultDto idle() {
        return new ConnectionResultDto(
                ConnectionStatus.IDLE,
                "WebSocket adapter initialized, ready to connect",
                Instant.now(),
                null,
                Map.of("initialized", true)
        );
    }

    public static ConnectionResultDto connecting() {
        return new ConnectionResultDto(
                ConnectionStatus.CONNECTING,
                "Establishing connection...",
                Instant.now(),
                UUID.randomUUID(),
                Map.of()
        );
    }

    public static ConnectionResultDto reconnecting(int attempt, int maxAttempts) {
        return new ConnectionResultDto(
                ConnectionStatus.RECONNECTING,
                String.format("Reconnecting... (attempt %d/%d)", attempt, maxAttempts),
                Instant.now(),
                UUID.randomUUID(),
                Map.of("reconnectAttempt", attempt, "maxAttempts", maxAttempts)
        );
    }

    public static ConnectionResultDto connected(String message, UUID connectionId) {
        return new ConnectionResultDto(
                ConnectionStatus.CONNECTED,
                message,
                Instant.now(),
                connectionId,
                Map.of("connectionAt", Instant.now())
        );
    }

    public static ConnectionResultDto disconnecting(String reason, UUID connectionId) {
        return new ConnectionResultDto(
                ConnectionStatus.DISCONNECTING,
                reason,
                Instant.now(),
                connectionId,
                Map.of()
        );
    }

    public static ConnectionResultDto disconnected(String reason, UUID connectionId) {
        Map<String, Object> metadata = Map.of(
                "disconnectionAt", Instant.now(),
                "disconnectReason", reason
        );

        return new ConnectionResultDto(
                ConnectionStatus.DISCONNECTED,
                reason,
                Instant.now(),
                connectionId,
                metadata
        );
    }

    public static ConnectionResultDto failure(String origin, String reason) {
        return new ConnectionResultDto(
                ConnectionStatus.ERROR,
                "Origin class: " + origin + " - " + reason,
                Instant.now(),
                null,
                Map.of("error", reason)
        );
    }

    public static ConnectionResultDto failure(String reason, Throwable cause) {
        return failure(reason, null, cause);
    }

    /** {@code cause} pode vir sem mensagem (ex.: {@code EOFException} de conexao cortada, T68). */
    public static ConnectionResultDto failure(String reason, UUID connectionId, Throwable cause) {
        String errorType = cause.getClass().getSimpleName();
        Map<String, Object> metadata = Map.of(
                "error", reason,
                "errorMessage", cause.getMessage() != null ? cause.getMessage() : errorType,
                "errorType", errorType
        );

        return new ConnectionResultDto(
                ConnectionStatus.ERROR,
                reason,
                Instant.now(),
                connectionId,
                metadata
        );
    }

    public static ConnectionResultDto closing(int code, String reason) {
        Map<String, Object> metadata = Map.of(
                "closeCode", code,
                "closeReason", reason
        );
        
        return new ConnectionResultDto(
                ConnectionStatus.CLOSING,
                reason,
                Instant.now(),
                null,
                metadata
        );
    }

    public static ConnectionResultDto closing(int code, String reason, UUID connectionId) {
        Map<String, Object> metadata = Map.of(
                "closeCode", code,
                "closeReason", reason
        );
        
        return new ConnectionResultDto(
                ConnectionStatus.CLOSING,
                reason,
                Instant.now(),
                connectionId,
                metadata
        );
    }

    public static ConnectionResultDto closed(int code, String reason) {
        Map<String, Object> metadata = Map.of(
                "closedAt", Instant.now(),
                "closeCode", code,
                "closeReason", reason
        );
        
        return new ConnectionResultDto(
                ConnectionStatus.CLOSED,
                reason,
                Instant.now(),
                null,
                metadata
        );
    }

    public static ConnectionResultDto closed(int code, String reason, UUID connectionId) {
        Map<String, Object> metadata = Map.of(
                "closedAt", Instant.now(),
                "closeCode", code,
                "closeReason", reason
        );
        
        return new ConnectionResultDto(
                ConnectionStatus.CLOSED,
                reason,
                Instant.now(),
                connectionId,
                metadata
        );
    }

    public static ConnectionResultDto withMetadata(ConnectionStatus status, String message, Map<String, Object> metadata) {
        return new ConnectionResultDto(
                status,
                message,
                Instant.now(),
                null,
                Map.copyOf(metadata)
        );
    }

    public static ConnectionResultDto withMetadata(ConnectionStatus status, String message, UUID connectionId, Map<String, Object> metadata) {
        return new ConnectionResultDto(
                status,
                message,
                Instant.now(),
                connectionId,
                Map.copyOf(metadata)
        );
    }

    public boolean isDisconnecting() {
        return status == ConnectionStatus.DISCONNECTING;
    }
    public boolean isConnected() {
        return status == ConnectionStatus.CONNECTED;
    }

    public boolean isFailed() {
        return status == ConnectionStatus.ERROR;
    }

    public boolean isInProgress() {
        return status == ConnectionStatus.CONNECTING || status == ConnectionStatus.RECONNECTING;
    }

    public boolean isSuccess() {
        return status == ConnectionStatus.CONNECTED || status == ConnectionStatus.IDLE;
    }

    public Duration getConnectionDuration() {
        Instant startTime = (Instant) metadata.get("connectionAt");
        if (startTime == null) {
            return Duration.ZERO;
        }
        return Duration.between(startTime, Instant.now());
    }
}