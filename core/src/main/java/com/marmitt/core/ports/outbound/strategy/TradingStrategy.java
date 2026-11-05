package com.marmitt.core.ports.outbound.strategy;

import com.marmitt.core.dto.strategy.StrategyContextDto;
import com.marmitt.core.dto.strategy.StrategyInputDto;
import com.marmitt.core.dto.strategy.StrategyOutputDto;

import java.util.UUID;

public interface TradingStrategy {

    UUID getStrategyId();

    /**
     * Executa a estrategia com contexto operacional consolidado do runner.
     *
     * @param inputData dados de mercado (precos, volume, etc)
     * @param strategyContext contexto do runner (capital, lotes, ordens em transito)
     * @return decisao da estrategia com quantity absoluta calculada
     */
    StrategyOutputDto executeStrategy(StrategyInputDto inputData, StrategyContextDto strategyContext);

    String getStrategyName();

    String getStrategyVersion();

    /**
     * Ticks que {@link StrategyInputDto#priceHistory()} precisa trazer; sem eles a estrategia nao e consultada.
     */
    default int requiredPriceHistoryTicks() {
        return 0;
    }

    boolean isEnabled();

    void setEnabled(boolean enabled);
}
