package com.marmitt.core.application.usecase.runner.processsignal;

import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.dto.capital.CapitalRequest;
import com.marmitt.core.dto.runner.OrderDispatchCommand;
import com.marmitt.core.dto.strategy.StrategyOutputDto;
import com.marmitt.core.enums.OrderSide;
import com.marmitt.core.ports.outbound.exchange.rest.OrderQuantityNormalizerPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T37: o preco normalizado por lado e o mesmo valor que e persistido, reservado e enviado — a
 * normalizacao continua acontecendo num ponto unico, antes do persist.
 */
class SideAwarePriceNormalizationTest {

    private static final BigDecimal QTY = new BigDecimal("0.00150000");
    private static final BigDecimal UNALIGNED_PRICE = new BigDecimal("43521.755");

    private final TradeIntentFactory factory = new TradeIntentFactory();
    private final OrderNormalizer normalizer = new OrderNormalizer(List.of(new TickNormalizer()));
    private final StrategyRunner runner = runner();

    @Test
    void buySignalMapsToBuySideAndFloorsPriceAcrossPersistReserveAndDispatch() {
        StrategyOutputDto signal = StrategyOutputDto.buyAt("s", new BigDecimal("0.8"), QTY, UNALIGNED_PRICE, "t37");

        Transaction tx = materialize(signal);

        assertEquals(OrderSide.BUY, factory.orderSideOf(signal));
        assertSame("43521.75", tx.getPrice());
        CapitalRequest reserve = factory.buildCapitalRequest(runner, tx);
        OrderDispatchCommand dispatch = factory.buildDispatchCommand(runner, tx);
        assertSame(tx.getTotal().toPlainString(), reserve.amount());
        assertSame(QTY.multiply(new BigDecimal("43521.75")).setScale(8, RoundingMode.HALF_UP).toPlainString(),
                reserve.amount());
        assertSame("43521.75", dispatch.price());
        assertSame(tx.getQuantity().toPlainString(), dispatch.quantity());
    }

    @Test
    void sellSignalMapsToSellSideAndCeilsPriceAcrossPersistAndDispatch() {
        StrategyOutputDto signal = StrategyOutputDto.sellAt("s", new BigDecimal("0.8"), QTY, new BigDecimal("43521.751"), "t37");

        Transaction tx = materialize(signal);

        assertEquals(OrderSide.SELL, factory.orderSideOf(signal));
        assertSame("43521.76", tx.getPrice());
        assertSame("43521.76", factory.buildDispatchCommand(runner, tx).price());
    }

    private Transaction materialize(StrategyOutputDto signal) {
        OrderNormalizer.NormalizedOrder normalized = normalizer.normalize(
                "BINANCE", "BTCUSDT", factory.orderSideOf(signal), signal.quantity(), signal.limitPrice());
        return factory.buildTransaction(runner, signal, OrderType.LIMIT, normalized.quantity(), normalized.price());
    }

    private static StrategyRunner runner() {
        StrategyRunner runner = mock(StrategyRunner.class);
        when(runner.getId()).thenReturn(UUID.randomUUID());
        when(runner.getShortCode()).thenReturn("x1");
        when(runner.getSymbol()).thenReturn("BTCUSDT");
        when(runner.getExchangeId()).thenReturn("BINANCE");
        return runner;
    }

    private static void assertSame(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    /** Normalizador com tickSize 0.01 e arredondamento por lado, como o contrato do port exige. */
    private static final class TickNormalizer implements OrderQuantityNormalizerPort {
        @Override
        public String getExchangeName() {
            return "BINANCE";
        }

        @Override
        public BigDecimal normalizeQuantity(String symbol, BigDecimal quantity) {
            return quantity;
        }

        @Override
        public BigDecimal minNotional(String symbol) {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal normalizePrice(String symbol, OrderSide side, BigDecimal price) {
            return price.setScale(2, side == OrderSide.BUY ? RoundingMode.FLOOR : RoundingMode.CEILING);
        }
    }
}
