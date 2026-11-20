package com.marmitt.core.ports.outbound.strategy;

import com.marmitt.core.dto.strategy.StrategyContextDto;
import com.marmitt.core.dto.strategy.StrategyInputDto;
import com.marmitt.core.dto.strategy.StrategyOutputDto;
import com.marmitt.core.dto.strategy.StrategyRequirements;

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
     * Historico que {@link StrategyInputDto} precisa trazer (ticks e candles, T69 e T72); sem ele completo a
     * estrategia nao e consultada.
     */
    default StrategyRequirements requirements() {
        return StrategyRequirements.NONE;
    }

    boolean isEnabled();

    void setEnabled(boolean enabled);
}
