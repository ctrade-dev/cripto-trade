package com.marmitt.core.dto.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento de erro terminal a ser notificado ativamente ao operador (ex.: Discord).
 *
 * <p>Contrato agnóstico de canal: descreve o que aconteceu e o contexto mínimo para o
 * operador agir, sem conhecer detalhes de transporte (webhook, embed, etc.). Os campos
 * de contexto são anuláveis porque nem todo disparo dispõe de todos eles.
 *
 * @param type          categoria do erro
 * @param title         título curto e legível
 * @param description   detalhe do erro (causa, fase, etc.)
 * @param correlationId id de correlação para rastrear nos logs (nullable)
 * @param runnerId      runner associado, quando aplicável (nullable)
 * @param dlqId         id da dead letter entry, quando aplicável (nullable)
 * @param occurredAt    momento da ocorrência
 */
public record ErrorNotificationEvent(
        ErrorNotificationType type,
        String title,
        String description,
        String correlationId,
        UUID runnerId,
        UUID dlqId,
        Instant occurredAt
) {

    public static ErrorNotificationEvent dlqPersisted(String title, String description,
                                                      String correlationId, UUID runnerId, UUID dlqId,
                                                      Instant occurredAt) {
        return new ErrorNotificationEvent(ErrorNotificationType.DLQ_PERSISTED, title, description,
                correlationId, runnerId, dlqId, occurredAt);
    }

    public static ErrorNotificationEvent circuitBreakerTriggered(String title, String description,
                                                                 Instant occurredAt) {
        return new ErrorNotificationEvent(ErrorNotificationType.CIRCUIT_BREAKER_TRIGGERED, title, description,
                null, null, null, occurredAt);
    }

    public static ErrorNotificationEvent protectiveExit(String title, String description, String correlationId,
                                                        UUID runnerId, Instant occurredAt) {
        return new ErrorNotificationEvent(ErrorNotificationType.PROTECTIVE_EXIT, title, description,
                correlationId, runnerId, null, occurredAt);
    }

    public static ErrorNotificationEvent runnerAutoHalted(String title, String description, UUID runnerId,
                                                          Instant occurredAt) {
        return new ErrorNotificationEvent(ErrorNotificationType.RUNNER_AUTO_HALTED, title, description,
                null, runnerId, null, occurredAt);
    }

    public static ErrorNotificationEvent strategyDisabled(String title, String description, Instant occurredAt) {
        return new ErrorNotificationEvent(ErrorNotificationType.STRATEGY_DISABLED, title, description,
                null, null, null, occurredAt);
    }

    public static ErrorNotificationEvent safeModeAutoResumed(String title, String description, Instant occurredAt) {
        return new ErrorNotificationEvent(ErrorNotificationType.SAFE_MODE_AUTO_RESUMED, title, description,
                null, null, null, occurredAt);
    }

    public static ErrorNotificationEvent bootFailFast(String title, String description,
                                                      String correlationId, Instant occurredAt) {
        return new ErrorNotificationEvent(ErrorNotificationType.BOOT_FAIL_FAST, title, description,
                correlationId, null, null, occurredAt);
    }
}
