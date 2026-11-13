package com.marmitt.binance.filters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marmitt.binance.rest.HttpClientPort;
import com.marmitt.core.dto.websocket.request.SendOrderRequest;
import com.marmitt.core.enums.OrderSide;
import com.marmitt.core.enums.OrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinanceOrderNormalizerTest {

    private static final String SYMBOL = "BTCUSDT";

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

    private BinanceOrderNormalizer normalizer;
    private OrderFilterValidator validator;

    @BeforeEach
    void setUp() {
        SymbolFilterCache cache = new SymbolFilterCache("http://stub", new StubHttpClient(EXCHANGE_INFO_BTCUSDT),
                new ObjectMapper());
        normalizer = new BinanceOrderNormalizer(cache);
        validator = new OrderFilterValidator(cache);
    }

    @Test
    void getExchangeName_returnsBinance() {
        assertEquals("BINANCE", normalizer.getExchangeName());
    }

    @Test
    void minNotional_comesFromTheSymbolFilter() {
        assertSameValue("10.00", normalizer.minNotional(SYMBOL));
    }

    @Test
    void normalizeQuantity_floorsToStepSize() {
        // stepSize=0.00001 → 0.001234 / 0.00001 = 123.4 → floor=123 → 0.00123
        assertSameValue("0.00123", normalizer.normalizeQuantity(SYMBOL, new BigDecimal("0.001234")));
    }

    @Test
    void normalizeQuantity_keepsAlignedQuantityUnchanged() {
        assertSameValue("0.001", normalizer.normalizeQuantity(SYMBOL, new BigDecimal("0.001")));
    }

    @Test
    void normalizePrice_buyFloorsToTickSize() {
        // T37: BUY nunca paga acima do pretendido. HALF_UP daria 43521.76.
        assertSameValue("43521.75", normalizer.normalizePrice(SYMBOL, OrderSide.BUY, new BigDecimal("43521.755")));
        assertSameValue("43521.75", normalizer.normalizePrice(SYMBOL, OrderSide.BUY, new BigDecimal("43521.759")));
    }

    @Test
    void normalizePrice_sellCeilsToTickSize() {
        // T37: SELL nunca vende abaixo do pretendido. HALF_UP daria 43521.75.
        assertSameValue("43521.76", normalizer.normalizePrice(SYMBOL, OrderSide.SELL, new BigDecimal("43521.751")));
        assertSameValue("43521.76", normalizer.normalizePrice(SYMBOL, OrderSide.SELL, new BigDecimal("43521.755")));
    }

    @Test
    void normalizePrice_neverMovesAgainstTheTrader() {
        BigDecimal[] prices = {
                new BigDecimal("43521.751"), new BigDecimal("43521.755"),
                new BigDecimal("43521.759"), new BigDecimal("0.019"), new BigDecimal("100.001")
        };
        for (BigDecimal intended : prices) {
            BigDecimal buy = normalizer.normalizePrice(SYMBOL, OrderSide.BUY, intended);
            BigDecimal sell = normalizer.normalizePrice(SYMBOL, OrderSide.SELL, intended);
            assertTrue(buy.compareTo(intended) <= 0, () -> "BUY acima do pretendido: " + intended + " -> " + buy);
            assertTrue(sell.compareTo(intended) >= 0, () -> "SELL abaixo do pretendido: " + intended + " -> " + sell);
            assertTrue(intended.subtract(buy).compareTo(new BigDecimal("0.01")) < 0, "BUY desloca menos de um tick");
            assertTrue(sell.subtract(intended).compareTo(new BigDecimal("0.01")) < 0, "SELL desloca menos de um tick");
        }
    }

    @Test
    void normalizePrice_keepsAlignedPriceUnchangedOnBothSides() {
        // Idempotente: o reenvio no dispatch (BinanceMarketStreamAdapter) nao altera preco ja alinhado.
        assertSameValue("43521.75", normalizer.normalizePrice(SYMBOL, OrderSide.BUY, new BigDecimal("43521.75")));
        assertSameValue("43521.75", normalizer.normalizePrice(SYMBOL, OrderSide.SELL, new BigDecimal("43521.75")));
    }

    @Test
    void sideNormalizedPricesPassDispatchValidationWithoutAdjustment() {
        // O OrderFilterValidator do dispatch so valida; precos FLOOR/CEILING precisam estar alinhados.
        for (OrderSide side : OrderSide.values()) {
            BigDecimal price = normalizer.normalizePrice(SYMBOL, side, new BigDecimal("43521.755"));
            BigDecimal quantity = normalizer.normalizeQuantity(SYMBOL, new BigDecimal("0.001234"));
            SendOrderRequest request = new SendOrderRequest("BINANCE", SYMBOL, quantity, price,
                    OrderType.LIMIT, side, "client-order-id");

            SendOrderRequest validated = validator.validate(request);

            assertSameValue(price.toPlainString(), validated.getPrice());
            assertSameValue(quantity.toPlainString(), validated.getQuantity());
        }
    }

    @Test
    void normalizePrice_returnsNullForMarketOrder() {
        assertNull(normalizer.normalizePrice(SYMBOL, OrderSide.BUY, null));
    }

    @Test
    void normalizePrice_returnsZeroUntouched() {
        assertSameValue("0", normalizer.normalizePrice(SYMBOL, OrderSide.SELL, BigDecimal.ZERO));
    }

    @Test
    void normalizePrice_requiresSideForPricedOrder() {
        assertThrows(NullPointerException.class,
                () -> normalizer.normalizePrice(SYMBOL, null, new BigDecimal("43521.755")));
    }

    private static void assertSameValue(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    private record StubHttpClient(String body) implements HttpClientPort {
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
            throw new IOException("unused");
        }
    }
}
