package com.marmitt.application.spring.testsupport.strategy.scenario;

import java.math.BigDecimal;

/**
 * Parâmetros compartilhados das estratégias de cenário para testnet (T35).
 *
 * <p>Offsets são <b>frações sobre o preço de mercado corrente</b> (ex.: {@code 0.05} = 5%),
 * nunca valores absolutos, para adaptarem-se a qualquer símbolo/preço. Os defaults devem caber
 * na banda do filtro {@code PERCENT_PRICE}/{@code PERCENT_PRICE_BY_SIDE} do símbolo (ver T35/T6):
 * o offset de "descansar" precisa afastar o suficiente para a ordem não preencher, mas dentro da
 * banda; o offset marketable precisa cruzar o spread. Os dois ficam abaixo do desvio maximo de 10%
 * entre o preco da ordem e o do tick, acima do qual o core descarta o sinal (T50).
 */
public record ScenarioStrategyConfig(
        BigDecimal restingOffset,        // afastamento p/ a ordem descansar sem preencher (0 < x < 1)
        BigDecimal fillOffset,           // afastamento p/ tornar a ordem marketable (0 < x < 1)
        BigDecimal quantity,             // quantidade absoluta por ordem (> 0)
        BigDecimal overAllocationFactor, // multiplicador (> 1) p/ estourar o capital disponível
        int maxCycles                    // nº máx. de ciclos antes de a estratégia ficar inerte (HOLD); 0 = ilimitado
) {

    public ScenarioStrategyConfig {
        requireFraction(restingOffset, "restingOffset");
        // fillOffset também é fração < 1: no leg SELL, priceBelow(preço, fillOffset) = preço*(1-fillOffset),
        // que ficaria zero/negativo com fillOffset >= 1 e faria o sellAt rejeitar o sinal (vira HOLD),
        // deixando o BUY preenchido em aberto. Falhar na construção do config em vez de degradar em runtime.
        requireFraction(fillOffset, "fillOffset");
        requirePositive(quantity, "quantity");
        if (overAllocationFactor == null || overAllocationFactor.compareTo(BigDecimal.ONE) <= 0) {
            throw new IllegalArgumentException("overAllocationFactor must be > 1");
        }
        if (maxCycles < 0) {
            throw new IllegalArgumentException("maxCycles must be >= 0 (0 = unlimited)");
        }
    }

    /** {@code true} se o número de ciclos é limitado (útil para e2e determinístico). */
    public boolean hasCycleLimit() {
        return maxCycles > 0;
    }

    /**
     * Defaults sensatos para testnet, <b>ilimitados</b> ({@code maxCycles = 0}) — preserva o loop
     * contínuo. Ajuste os offsets aos filtros do símbolo antes de operar.
     */
    public static ScenarioStrategyConfig defaultConfig() {
        return new ScenarioStrategyConfig(
                new BigDecimal("0.05"),   // 5% longe do mercado — descansa sem preencher (T50: < 10%)
                new BigDecimal("0.02"),   // 2% além do mercado — marketable (cruza o spread)
                new BigDecimal("0.001"),  // 0.001 unidade por ordem
                new BigDecimal("2"),      // 2x o capital disponível
                0                         // ilimitado
        );
    }

    /**
     * Variante com teto de ciclos, para cenários e2e com início e fim determinísticos.
     */
    public static ScenarioStrategyConfig boundedConfig(int maxCycles) {
        ScenarioStrategyConfig base = defaultConfig();
        return new ScenarioStrategyConfig(base.restingOffset(), base.fillOffset(),
                base.quantity(), base.overAllocationFactor(), maxCycles);
    }

    private static void requireFraction(BigDecimal value, String name) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0 || value.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException(name + " must be between 0.0 and 1.0 (exclusive)");
        }
    }

    private static void requirePositive(BigDecimal value, String name) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
