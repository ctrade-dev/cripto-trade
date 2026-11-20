package com.marmitt.binance.candle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marmitt.binance.rest.BinanceHttpErrors;
import com.marmitt.binance.rest.BinanceRestRequestBuilder;
import com.marmitt.binance.rest.HttpClientPort;
import com.marmitt.binance.rest.RestRequest;
import com.marmitt.core.dto.strategy.CandleDto;
import com.marmitt.core.dto.strategy.CandleInterval;
import com.marmitt.core.exceptions.ExchangeQueryException;
import com.marmitt.core.ports.outbound.exchange.rest.ExchangeCandleQueryPort;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Candles fechados pela REST da Binance, {@code GET /api/v3/klines} (T72). Os codigos de intervalo do contrato sao os
 * mesmos da Binance. Fechado e decidido pelo {@code closeTime} que a Binance devolve: o candle em formacao nunca sai
 * daqui.
 */
public class BinanceCandleQueryAdapter implements ExchangeCandleQueryPort {

    /** Teto de {@code limit} da Binance por chamada. */
    static final int PAGE_LIMIT = 1000;

    private static final Set<CandleInterval> SUPPORTED = EnumSet.allOf(CandleInterval.class);

    private final BinanceRestRequestBuilder requestBuilder;
    private final HttpClientPort httpClient;
    private final ObjectMapper objectMapper;

    public BinanceCandleQueryAdapter(BinanceRestRequestBuilder requestBuilder, HttpClientPort httpClient,
                                     ObjectMapper objectMapper) {
        this.requestBuilder = requestBuilder;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<CandleInterval> supportedIntervals() {
        return SUPPORTED;
    }

    /**
     * Pede um candle a mais, porque o ultimo devolvido costuma ser o que ainda esta em formacao; se faltar candle
     * fechado e a pagina veio cheia, pede a pagina anterior.
     */
    @Override
    public List<CandleDto> fetchClosed(String symbol, CandleInterval interval, Instant endExclusive, int limit) {
        List<CandleDto> closed = new ArrayList<>();
        long endTime = endExclusive.toEpochMilli() - 1;
        while (closed.size() < limit) {
            int missing = limit - closed.size();
            int requested = Math.min(PAGE_LIMIT, missing + 1);
            List<Kline> page = fetchPage(requestBuilder.buildKlines(symbol, interval.code(), endTime, requested));
            List<CandleDto> pageClosed = page.stream()
                    .filter(kline -> kline.closeTime() < endExclusive.toEpochMilli())
                    .map(Kline::candle)
                    .toList();
            int from = Math.max(0, pageClosed.size() - missing);
            closed.addAll(0, pageClosed.subList(from, pageClosed.size()));
            if (page.size() < requested || pageClosed.isEmpty()) {
                break;
            }
            endTime = pageClosed.getFirst().openTime().toEpochMilli() - 1;
        }
        return List.copyOf(closed);
    }

    private List<Kline> fetchPage(RestRequest request) {
        HttpClientPort.HttpResponse response;
        try {
            response = httpClient.get(request.url(), request.headers());
        } catch (IOException e) {
            throw new ExchangeQueryException("BINANCE", ExchangeQueryException.ErrorType.TEMPORARY,
                    "Failed to fetch klines from Binance: " + e.getMessage(), e);
        }
        if (!response.isSuccessful()) {
            throw new ExchangeQueryException("BINANCE", BinanceHttpErrors.errorType(response.statusCode()),
                    "Binance klines returned HTTP " + response.statusCode() + ": " + response.body());
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            List<Kline> klines = new ArrayList<>(root.size());
            for (JsonNode node : root) {
                klines.add(new Kline(new CandleDto(Instant.ofEpochMilli(node.get(0).asLong()),
                        new BigDecimal(node.get(1).asText()), new BigDecimal(node.get(2).asText()),
                        new BigDecimal(node.get(3).asText()), new BigDecimal(node.get(4).asText()),
                        new BigDecimal(node.get(5).asText())), node.get(6).asLong()));
            }
            return klines;
        } catch (IOException | RuntimeException e) {
            throw new ExchangeQueryException("BINANCE", ExchangeQueryException.ErrorType.UNKNOWN,
                    "Failed to parse klines response: " + e.getMessage(), e);
        }
    }

    private record Kline(CandleDto candle, long closeTime) {
    }
}
