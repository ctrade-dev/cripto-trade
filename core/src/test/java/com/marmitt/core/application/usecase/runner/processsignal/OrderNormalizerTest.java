package com.marmitt.core.application.usecase.runner.processsignal;

import com.marmitt.core.enums.OrderSide;
import com.marmitt.core.ports.outbound.exchange.rest.OrderQuantityNormalizerPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderNormalizerTest {

    private static final String SYMBOL = "BTCUSDT";
    private static final BigDecimal RAW_QTY = new BigDecimal("0.001234");
    private static final BigDecimal RAW_PRICE = new BigDecimal("43521.755");

    @Test
    void normalize_appliesRegisteredNormalizerForExchange() {
        OrderNormalizer normalizer = new OrderNormalizer(List.of(new FixedNormalizer("BINANCE")));

        OrderNormalizer.NormalizedOrder result = normalizer.normalize("BINANCE", SYMBOL, OrderSide.BUY, RAW_QTY, RAW_PRICE);

        assertSameValue("0.00123", result.quantity());
        assertSameValue("43521.75", result.price());
    }

    @Test
    void normalize_propagatesSideToPriceNormalization() {
        // T37: o lado chega ao normalizador da exchange; o fake arredonda SELL para cima.
        OrderNormalizer normalizer = new OrderNormalizer(List.of(new FixedNormalizer("BINANCE")));

        OrderNormalizer.NormalizedOrder sell = normalizer.normalize("BINANCE", SYMBOL, OrderSide.SELL, RAW_QTY, RAW_PRICE);

        assertSameValue("43521.76", sell.price());
        assertSameValue("0.00123", sell.quantity());
    }

    @Test
    void normalize_resolvesExchangeNameCaseInsensitively() {
        OrderNormalizer normalizer = new OrderNormalizer(List.of(new FixedNormalizer("BINANCE")));

        OrderNormalizer.NormalizedOrder result = normalizer.normalize("binance", SYMBOL, OrderSide.BUY, RAW_QTY, RAW_PRICE);

        assertSameValue("0.00123", result.quantity());
    }

    @Test
    void normalize_returnsOriginalWhenNoNormalizerForExchange() {
        OrderNormalizer normalizer = new OrderNormalizer(List.of(new FixedNormalizer("BINANCE")));

        OrderNormalizer.NormalizedOrder result = normalizer.normalize("MOCK", SYMBOL, OrderSide.BUY, RAW_QTY, RAW_PRICE);

        assertEquals(RAW_QTY, result.quantity());
        assertEquals(RAW_PRICE, result.price());
    }

    @Test
    void normalize_returnsOriginalWhenNoNormalizersRegistered() {
        OrderNormalizer normalizer = new OrderNormalizer(List.of());

        OrderNormalizer.NormalizedOrder result = normalizer.normalize("BINANCE", SYMBOL, OrderSide.BUY, RAW_QTY, RAW_PRICE);

        assertEquals(RAW_QTY, result.quantity());
        assertEquals(RAW_PRICE, result.price());
    }

    @Test
    void normalize_returnsOriginalWhenExchangeNameIsNull() {
        OrderNormalizer normalizer = new OrderNormalizer(List.of(new FixedNormalizer("BINANCE")));

        OrderNormalizer.NormalizedOrder result = normalizer.normalize(null, SYMBOL, OrderSide.BUY, RAW_QTY, RAW_PRICE);

        assertEquals(RAW_QTY, result.quantity());
    }

    private static void assertSameValue(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    /**
     * Normalizador fake que faz floor da quantidade para 5 casas e arredonda o preço para 2 casas
     * por lado (BUY floor, SELL ceiling), suficiente para verificar a delegação sem depender de
     * regras reais de exchange.
     */
    private record FixedNormalizer(String exchangeName) implements OrderQuantityNormalizerPort {
        @Override
        public String getExchangeName() {
            return exchangeName;
        }

        @Override
        public BigDecimal normalizeQuantity(String symbol, BigDecimal quantity) {
            return quantity.setScale(5, java.math.RoundingMode.FLOOR);
        }

        @Override
        public BigDecimal minNotional(String symbol) {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal normalizePrice(String symbol, OrderSide side, BigDecimal price) {
            if (price == null) {
                return null;
            }
            return price.setScale(2, side == OrderSide.BUY
                    ? java.math.RoundingMode.FLOOR
                    : java.math.RoundingMode.CEILING);
        }
    }
}
