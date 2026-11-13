package com.marmitt.core.application.usecase.runner.orderconciliation;

import com.marmitt.core.ports.outbound.exchange.rest.OrderQuantityNormalizerPort;
import com.marmitt.core.ports.outbound.marketdata.LatestPricePort;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decide se uma quantidade residual de posicao ainda pode ser vendida na exchange (T46, T53 F30).
 *
 * <p>Uma SELL que termina com resto no lote pode deixar uma quantidade que nenhuma ordem consegue
 * vender, por dois motivos:
 * <ul>
 *   <li>o resto e menor que um {@code stepSize} (fee de BUY cobrada no ativo base): normaliza para zero;</li>
 *   <li>o resto, ao ultimo preco conhecido, vale menos que o valor minimo de ordem da exchange
 *       ({@code minNotional}), como depois de uma venda parcial interrompida (F30).</li>
 * </ul>
 * A regra de quando baixar o resto e do core; o {@code stepSize} e o valor minimo sao do adapter,
 * consultados via {@link OrderQuantityNormalizerPort}. Exchange sem normalizador (ex.: MOCK) aceita
 * qualquer quantidade. Sem preco conhecido (ex.: logo apos um restart), so a regra do passo vale.
 */
class SellableQuantityPolicy {

    private final Map<String, OrderQuantityNormalizerPort> byExchange;
    private final LatestPricePort latestPrices;

    SellableQuantityPolicy(List<OrderQuantityNormalizerPort> normalizers, LatestPricePort latestPrices) {
        Map<String, OrderQuantityNormalizerPort> map = new HashMap<>();
        for (OrderQuantityNormalizerPort normalizer : normalizers) {
            map.put(key(normalizer.getExchangeName()), normalizer);
        }
        this.byExchange = map;
        this.latestPrices = latestPrices;
    }

    boolean isSellable(String exchangeName, String symbol, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            return false;
        }
        OrderQuantityNormalizerPort normalizer = exchangeName == null ? null : byExchange.get(key(exchangeName));
        if (normalizer == null) {
            return true;
        }
        BigDecimal sellable = normalizer.normalizeQuantity(symbol, quantity);
        if (sellable.signum() <= 0) {
            return false;
        }
        return latestPrices.find(symbol)
                .map(price -> sellable.multiply(price).compareTo(normalizer.minNotional(symbol)) >= 0)
                .orElse(true);
    }

    private static String key(String exchangeName) {
        return exchangeName.toUpperCase(Locale.ROOT);
    }
}
