package com.marmitt.core.ports.outbound.exchange.rest;

import com.marmitt.core.enums.OrderSide;

import java.math.BigDecimal;

/**
 * Normaliza quantidade e preço de uma ordem para as regras de filtro da exchange
 * (ex.: {@code stepSize} de lote e {@code tickSize} de preço na Binance).
 *
 * <p>A normalização acontece <em>antes</em> do persist da {@code Transaction} e da reserva
 * de capital, de modo que o valor reservado, o valor persistido e o valor enviado para a
 * exchange sejam sempre idênticos. Sem isso, o floor aplicado no momento do dispatch deixaria
 * a reserva super-dimensionada pela diferença {@code quantity % stepSize} até a próxima
 * reconciliação de saldo.
 *
 * <p>Cada exchange registra a sua implementação; exchanges sem normalizador (ex.: MOCK)
 * simplesmente não participam e a quantidade/preço originais são usados sem modificação.
 */
public interface OrderQuantityNormalizerPort {

    /**
     * Nome da exchange dona deste normalizador (ex.: {@code "BINANCE"}). Usado para
     * resolução por {@code exchangeName} em runtime.
     */
    String getExchangeName();

    /**
     * Alinha a quantidade ao {@code stepSize} do símbolo (floor). Retorna a própria
     * quantidade quando não houver regra aplicável.
     */
    BigDecimal normalizeQuantity(String symbol, BigDecimal quantity);

    /**
     * Alinha o preço ao {@code tickSize} do símbolo, sempre a favor do trader (T37):
     * BUY arredonda para baixo (nunca paga acima do preço pretendido) e SELL arredonda para
     * cima (nunca vende abaixo do preço pretendido). Retorna o próprio preço quando ele for
     * {@code null}/zero (ex.: ordem a mercado) ou não houver regra aplicável.
     *
     * @param side lado da ordem; obrigatório, pois define a direção do arredondamento
     */
    BigDecimal normalizePrice(String symbol, OrderSide side, BigDecimal price);

    /**
     * Menor valor (quantidade x preco, na moeda de cotacao) que a exchange aceita numa ordem do simbolo
     * (ex.: {@code NOTIONAL.minNotional} na Binance). Retorna zero quando nao houver regra aplicavel.
     */
    BigDecimal minNotional(String symbol);
}
