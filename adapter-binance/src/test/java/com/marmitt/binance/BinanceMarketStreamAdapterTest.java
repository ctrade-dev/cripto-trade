package com.marmitt.binance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marmitt.binance.filters.OrderFilterViolationException;
import com.marmitt.binance.filters.SymbolFilterCache;
import com.marmitt.binance.rest.HttpClientPort;
import com.marmitt.core.dto.websocket.request.SendCancelOrderRequest;
import com.marmitt.core.dto.websocket.request.SendOrderRequest;
import com.marmitt.core.enums.OrderSide;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.exceptions.ExchangeQueryException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinanceMarketStreamAdapterTest {

    private static final String EXCHANGE_INFO_BTCUSDT = """
            {
              "symbols": [{
                "symbol": "BTCUSDT",
                "filters": [
                  {"filterType":"LOT_SIZE","minQty":"0.00001","maxQty":"9000","stepSize":"0.00001"},
                  {"filterType":"PRICE_FILTER","minPrice":"0.01","maxPrice":"1000000","tickSize":"0.01"},
                  {"filterType":"MIN_NOTIONAL","minNotional":"10.00"}
                ]
              }]
            }
            """;

    private BinanceMarketStreamAdapter adapter(HttpClientPort httpClient) {
        SymbolFilterCache cache = new SymbolFilterCache("http://stub", httpClient, new ObjectMapper());
        BinanceConnectionConfig config = new BinanceConnectionConfig(
                "test-key", "test-secret", "wss://stub", "http://stub");
        return new BinanceMarketStreamAdapter(new ObjectMapper(), config, httpClient, cache);
    }

    private SendOrderRequest order(String quantity, String price) {
        return new SendOrderRequest("BINANCE", "BTCUSDT",
                new BigDecimal(quantity), new BigDecimal(price),
                OrderType.LIMIT, OrderSide.BUY, "client-1");
    }

    @Test
    void formatMessage_normalizesUnalignedManualOrderInsteadOfRejecting() {
        // Regressão T11: ordem manual com qty/preço válidos mas desalinhados deve ser
        // normalizada (0.001234 -> 0.00123), não rejeitada.
        BinanceMarketStreamAdapter adapter = adapter(new StubHttpClient(EXCHANGE_INFO_BTCUSDT));

        String message = assertDoesNotThrow(() -> adapter.formatMessage(order("0.001234", "50000.00")));

        assertFalse(message.contains("0.001234"), "raw unaligned quantity must not survive");
        assertTrue(message.contains("0.00123"), "quantity must be floored to stepSize");
    }

    @Test
    void formatMessage_stillRejectsOrderBelowMinNotional() {
        // Normalização não mascara rejeição legítima: notional abaixo do mínimo segue rejeitado.
        BinanceMarketStreamAdapter adapter = adapter(new StubHttpClient(EXCHANGE_INFO_BTCUSDT));

        assertThrows(OrderFilterViolationException.class,
                () -> adapter.formatMessage(order("0.00001", "1.00")));
    }

    @Test
    void cancelOrder_unknownOrderIsOrderNotFound() {
        // T67: cancelamento de ordem que ja terminou na exchange (-2011 "Unknown order sent.").
        BinanceMarketStreamAdapter adapter = adapter(new StubHttpClient(EXCHANGE_INFO_BTCUSDT,
                new HttpClientPort.HttpResponse(400, "{\"code\":-2011,\"msg\":\"Unknown order sent.\"}")));

        ExchangeQueryException e = assertThrows(ExchangeQueryException.class,
                () -> adapter.cancelOrder(new SendCancelOrderRequest("BINANCE", "client-1", "BTCUSDT")));

        assertEquals(ExchangeQueryException.ErrorType.ORDER_NOT_FOUND, e.errorType());
    }

    @Test
    void cancelOrder_otherCancelRejectionKeepsHttpMapping() {
        BinanceMarketStreamAdapter adapter = adapter(new StubHttpClient(EXCHANGE_INFO_BTCUSDT,
                new HttpClientPort.HttpResponse(400,
                        "{\"code\":-2011,\"msg\":\"Order was not canceled due to cancel restrictions.\"}")));

        ExchangeQueryException e = assertThrows(ExchangeQueryException.class,
                () -> adapter.cancelOrder(new SendCancelOrderRequest("BINANCE", "client-1", "BTCUSDT")));

        assertEquals(ExchangeQueryException.ErrorType.INVALID_REQUEST, e.errorType());
    }

    private record StubHttpClient(String body, HttpResponse deleteResponse) implements HttpClientPort {
        StubHttpClient(String body) {
            this(body, null);
        }

        @Override
        public HttpResponse get(String url, Map<String, String> headers) {
            return new HttpResponse(200, body);
        }

        @Override
        public HttpResponse post(String url, Map<String, String> headers) throws IOException {
            throw new IOException("unused");
        }

        @Override
        public HttpResponse postForm(String url, Map<String, String> headers, String b) throws IOException {
            throw new IOException("unused");
        }

        @Override
        public HttpResponse put(String url, Map<String, String> headers) throws IOException {
            throw new IOException("unused");
        }

        @Override
        public HttpResponse delete(String url, Map<String, String> headers) throws IOException {
            if (deleteResponse != null) {
                return deleteResponse;
            }
            throw new IOException("unused");
        }
    }
}
