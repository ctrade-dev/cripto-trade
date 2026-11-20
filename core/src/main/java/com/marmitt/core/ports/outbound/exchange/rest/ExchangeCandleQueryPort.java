package com.marmitt.core.ports.outbound.exchange.rest;

import com.marmitt.core.dto.strategy.CandleDto;
import com.marmitt.core.dto.strategy.CandleInterval;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Candles fechados da exchange (T72), dado publico de mercado consultado pela REST.
 */
public interface ExchangeCandleQueryPort {

    /** Intervalos do contrato que a exchange oferece; runner que declara outro fica sem decisao. */
    Set<CandleInterval> supportedIntervals();

    /**
     * Os ultimos candles <b>fechados</b> antes de {@code endExclusive}, do mais antigo para o mais novo. O candle
     * ainda em formacao nunca vem.
     *
     * @param limit ate 1000 candles por chamada
     * @throws com.marmitt.core.exceptions.ExchangeQueryException se a exchange falhar
     */
    List<CandleDto> fetchClosed(String symbol, CandleInterval interval, Instant endExclusive, int limit);
}
