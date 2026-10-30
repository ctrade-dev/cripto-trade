package com.marmitt.core.exceptions;

import java.util.Objects;

/**
 * Excecao tipada para falhas em consulta REST de ordens na exchange.
 *
 * <p>Usada principalmente no boot recovery para decidir retry de forma
 * robusta, sem depender de parsing de mensagem textual.
 */
public class ExchangeQueryException extends RuntimeException implements CodedError {

    public enum ErrorType {
        TEMPORARY,
        RATE_LIMIT,
        AUTH,
        INVALID_REQUEST,
        NOT_SUPPORTED,
        /** A exchange nao conhece a ordem (ex.: cancelamento de ordem que ja terminou, T67). */
        ORDER_NOT_FOUND,
        UNKNOWN
    }

    private final String exchangeId;
    private final ErrorType errorType;

    public ExchangeQueryException(String exchangeId, ErrorType errorType, String message) {
        super(message);
        this.exchangeId = exchangeId;
        this.errorType = Objects.requireNonNull(errorType, "errorType cannot be null");
    }

    public ExchangeQueryException(String exchangeId, ErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.exchangeId = exchangeId;
        this.errorType = Objects.requireNonNull(errorType, "errorType cannot be null");
    }

    public String exchangeId() {
        return exchangeId;
    }

    public ErrorType errorType() {
        return errorType;
    }

    public boolean isRetryable() {
        return errorType == ErrorType.TEMPORARY || errorType == ErrorType.RATE_LIMIT;
    }

    @Override
    public String code() {
        return "EXCHANGE_QUERY_FAILED";
    }
}
