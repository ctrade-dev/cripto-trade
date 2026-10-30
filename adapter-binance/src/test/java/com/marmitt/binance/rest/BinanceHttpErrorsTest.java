package com.marmitt.binance.rest;

import com.marmitt.core.exceptions.ExchangeQueryException.ErrorType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T66: tipo de falha por status HTTP da Binance.
 */
class BinanceHttpErrorsTest {

    @ParameterizedTest
    @CsvSource({
            "400, INVALID_REQUEST",
            "401, AUTH",
            "403, AUTH",
            "418, RATE_LIMIT",
            "429, RATE_LIMIT",
            "500, TEMPORARY",
            "502, TEMPORARY",
            "503, TEMPORARY"
    })
    void mapsStatusToErrorType(int status, ErrorType expected) {
        assertEquals(expected, BinanceHttpErrors.errorType(status));
    }
}
