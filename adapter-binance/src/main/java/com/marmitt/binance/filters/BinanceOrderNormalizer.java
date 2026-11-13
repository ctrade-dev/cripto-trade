package com.marmitt.binance.filters;

import com.marmitt.core.enums.OrderSide;
import com.marmitt.core.ports.outbound.exchange.rest.OrderQuantityNormalizerPort;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Normaliza quantidade e preço de ordens Binance às regras de filtro do símbolo
 * ({@code LOT_SIZE.stepSize} e {@code PRICE_FILTER.tickSize}), usando o
 * {@link SymbolFilterCache}.
 *
 * <p>É a fonte única de alinhamento de ordem para a Binance: aplicada no core antes do
 * persist/reserva via {@link OrderQuantityNormalizerPort}. O {@code OrderFilterValidator}
 * no dispatch apenas valida (rejeição/alinhamento), sem voltar a ajustar valores.
 */
@Slf4j
public class BinanceOrderNormalizer implements OrderQuantityNormalizerPort {

    private final SymbolFilterCache filterCache;

    public BinanceOrderNormalizer(SymbolFilterCache filterCache) {
        this.filterCache = filterCache;
    }

    @Override
    public String getExchangeName() {
        return "BINANCE";
    }

    @Override
    public BigDecimal normalizeQuantity(String symbol, BigDecimal quantity) {
        BigDecimal stepSize = filterCache.getFilters(symbol).stepSize();
        if (stepSize.compareTo(BigDecimal.ZERO) == 0) {
            return quantity;
        }
        BigDecimal aligned = quantity.divide(stepSize, 0, RoundingMode.FLOOR)
                .multiply(stepSize)
                .stripTrailingZeros();
        if (aligned.compareTo(quantity) != 0) {
            log.debug("normalizeQuantity {}: {} -> {} (stepSize={})", symbol, quantity, aligned, stepSize);
        }
        return aligned;
    }

    @Override
    public BigDecimal normalizePrice(String symbol, OrderSide side, BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) == 0) {
            return price;
        }
        Objects.requireNonNull(side, "side cannot be null");
        BigDecimal tickSize = filterCache.getFilters(symbol).tickSize();
        if (tickSize.compareTo(BigDecimal.ZERO) == 0) {
            return price;
        }
        // Arredondamento a favor do trader (T37): BUY nunca acima, SELL nunca abaixo do pretendido.
        RoundingMode mode = side == OrderSide.BUY ? RoundingMode.FLOOR : RoundingMode.CEILING;
        BigDecimal aligned = price.divide(tickSize, 0, mode)
                .multiply(tickSize)
                .stripTrailingZeros();
        if (aligned.compareTo(price) != 0) {
            log.debug("normalizePrice {} {}: {} -> {} (tickSize={})", symbol, side, price, aligned, tickSize);
        }
        return aligned;
    }

    @Override
    public BigDecimal minNotional(String symbol) {
        return filterCache.getFilters(symbol).minNotional();
    }
}
