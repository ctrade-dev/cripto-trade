package com.marmitt.binance.candle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marmitt.binance.auth.BinanceCredentials;
import com.marmitt.binance.auth.BinanceRequestSigner;
import com.marmitt.binance.rest.BinanceRestRequestBuilder;
import com.marmitt.binance.rest.HttpClientPort;
import com.marmitt.core.dto.strategy.CandleDto;
import com.marmitt.core.dto.strategy.CandleInterval;
import com.marmitt.core.exceptions.ExchangeQueryException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T72: klines da Binance viram candles fechados; o candle em formacao nunca sai do adapter. */
class BinanceCandleQueryAdapterTest {

    private static final long MINUTE = 60_000L;
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:30Z");
    private static final Pattern PARAM = Pattern.compile("[?&](endTime|limit)=([0-9]+)");

    private final FakeBinance binance = new FakeBinance();
    private final BinanceCandleQueryAdapter adapter = new BinanceCandleQueryAdapter(
            new BinanceRestRequestBuilder("https://testnet.binance.vision",
                    new BinanceRequestSigner(new BinanceCredentials("key", "secret"))),
            binance, new ObjectMapper());

    @Test
    void returnsTheClosedCandlesAndDropsTheOneStillForming() {
        List<CandleDto> candles = adapter.fetchClosed("BTCUSDT", CandleInterval.M1, NOW, 3);

        assertEquals(List.of("09:57", "09:58", "09:59"), candles.stream()
                .map(c -> c.openTime().toString().substring(11, 16)).toList());
        CandleDto last = candles.getLast();
        assertEquals(new BigDecimal("100.5"), last.open());
        assertEquals(new BigDecimal("101.5"), last.high());
        assertEquals(new BigDecimal("99.5"), last.low());
        assertEquals(new BigDecimal("100.75"), last.close());
        assertEquals(new BigDecimal("12.5"), last.volume());
        String url = binance.urls.getFirst();
        assertTrue(url.startsWith("https://testnet.binance.vision/api/v3/klines?symbol=BTCUSDT&interval=1m"), url);
        assertFalse(url.contains("signature"), "klines e dado publico, sem assinatura");
        assertEquals("4", param(url, "limit"), "um a mais, porque o ultimo costuma estar em formacao");
    }

    @Test
    void pagesBackwardsWhenOnePageIsNotEnough() {
        List<CandleDto> candles = adapter.fetchClosed("BTCUSDT", CandleInterval.M1, NOW, 1500);

        assertEquals(1500, candles.size());
        assertEquals(2, binance.urls.size());
        for (int i = 1; i < candles.size(); i++) {
            assertEquals(MINUTE, candles.get(i).openTime().toEpochMilli() - candles.get(i - 1).openTime().toEpochMilli());
        }
        assertEquals(Instant.parse("2026-11-20T09:59:00Z"), candles.getLast().openTime());
    }

    @Test
    void shortHistoryReturnsWhatExists() {
        binance.firstOpen = Instant.parse("2026-11-20T09:56:00Z").toEpochMilli();

        assertEquals(4, adapter.fetchClosed("NEWUSDT", CandleInterval.M1, NOW, 200).size());
        assertEquals(1, binance.urls.size());
    }

    @Test
    void httpFailureIsAnExchangeQueryException() {
        binance.status = 503;

        assertThrows(ExchangeQueryException.class, () -> adapter.fetchClosed("BTCUSDT", CandleInterval.M1, NOW, 3));
    }

    private static String param(String url, String name) {
        Matcher matcher = PARAM.matcher(url);
        while (matcher.find()) {
            if (matcher.group(1).equals(name)) {
                return matcher.group(2);
            }
        }
        throw new AssertionError(name + " missing in " + url);
    }

    /**
     * Klines de minuto como a Binance devolve: abertura ate {@code endTime}, os mais novos, ate {@code limit}; o ultimo
     * ainda em formacao em {@link #NOW}.
     */
    private static final class FakeBinance implements HttpClientPort {
        private final List<String> urls = new ArrayList<>();
        private long firstOpen = 0;
        private int status = 200;

        @Override
        public HttpResponse get(String url, Map<String, String> headers) {
            urls.add(url);
            if (status != 200) {
                return new HttpResponse(status, "{\"code\":-1}");
            }
            long endTime = Long.parseLong(param(url, "endTime"));
            int limit = Integer.parseInt(param(url, "limit"));
            long lastOpen = Math.floorDiv(endTime, MINUTE) * MINUTE;
            StringJoiner body = new StringJoiner(",", "[", "]");
            for (long open = Math.max(firstOpen, lastOpen - (limit - 1) * MINUTE); open <= lastOpen; open += MINUTE) {
                body.add("[" + open + ",\"100.5\",\"101.5\",\"99.5\",\"100.75\",\"12.5\"," + (open + MINUTE - 1)
                        + ",\"1250.0\",10,\"6.0\",\"600.0\",\"0\"]");
            }
            return new HttpResponse(200, body.toString());
        }

        @Override public HttpResponse post(String url, Map<String, String> headers) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse postForm(String url, Map<String, String> headers, String body) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse put(String url, Map<String, String> headers) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse delete(String url, Map<String, String> headers) { throw new UnsupportedOperationException(); }
    }
}
