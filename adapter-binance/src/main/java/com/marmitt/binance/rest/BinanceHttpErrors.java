package com.marmitt.binance.rest;

import com.marmitt.core.exceptions.ExchangeQueryException;

/**
 * Tipo de falha de uma resposta HTTP de erro da Binance (T66). Um mapeamento só para todas as
 * chamadas REST do adapter.
 *
 * <p>429 é o aviso de limite de peso; 418 é o IP banido depois de 429 repetidos. Os dois são
 * {@code RATE_LIMIT}: a causa é o volume de chamadas, não a exchange fora do ar.
 */
public final class BinanceHttpErrors {

    private BinanceHttpErrors() {
    }

    public static ExchangeQueryException.ErrorType errorType(int statusCode) {
        return switch (statusCode) {
            case 400 -> ExchangeQueryException.ErrorType.INVALID_REQUEST;
            case 401, 403 -> ExchangeQueryException.ErrorType.AUTH;
            case 418, 429 -> ExchangeQueryException.ErrorType.RATE_LIMIT;
            default -> ExchangeQueryException.ErrorType.TEMPORARY;
        };
    }
}
