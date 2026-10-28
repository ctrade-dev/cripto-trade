package com.marmitt.application.spring.testsupport.strategy.scenario;

import com.marmitt.core.dto.strategy.OpenLotDto;
import com.marmitt.core.dto.strategy.StrategyContextDto;
import com.marmitt.core.dto.strategy.StrategyInputDto;
import com.marmitt.core.dto.strategy.StrategyOutputDto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Cenário 3 (T35): caminho feliz completo. Compra (fill garantido) e fecha a posição o mais rápido
 * possível, reabrindo em seguida. Exercita {@code transaction_match}, PnL (≈0/negativo por
 * spread+fees), fechamento de lote e liberação de capital a cada ciclo.
 *
 * <p>Ciclo determinístico:
 * <ol>
 *   <li>pendência em trânsito → aguarda o fill;</li>
 *   <li>lote aberto → SELL marketable (preço abaixo do mercado) para fechar como taker;</li>
 *   <li>sem lote/pendência → BUY marketable (preço acima do mercado) para abrir.</li>
 * </ol>
 */
public class ImmediateRoundTripStrategy extends AbstractScenarioStrategy {

    private static final UUID STRATEGY_ID = UUID.fromString("b1000000-0000-4000-a000-000000000003");
    private static final String NAME = "ImmediateRoundTripStrategy";
    private static final String VERSION = "1.0.0";

    public ImmediateRoundTripStrategy() {
        this(ScenarioStrategyConfig.defaultConfig());
    }

    public ImmediateRoundTripStrategy(ScenarioStrategyConfig config) {
        super(STRATEGY_ID, NAME, VERSION, config);
    }

    @Override
    public StrategyOutputDto executeStrategy(StrategyInputDto input, StrategyContextDto context) {
        if (context.hasPendingOrders()) {
            return StrategyOutputDto.hold(NAME, "cenario immediate-round-trip: aguardando fill");
        }

        if (context.hasOpenLots()) {
            OpenLotDto lot = context.openLots().getFirst();
            BigDecimal limitPrice = priceBelow(input.currentPrice(), config.fillOffset());
            return StrategyOutputDto.sellAt(NAME, CONFIDENCE, lot.availableQuantity(), limitPrice,
                    "cenario immediate-round-trip: SELL marketable para fechar o lote");
        }

        if (!tryStartCycle(context.runnerId())) {
            return StrategyOutputDto.hold(NAME, "cenario immediate-round-trip: teto de ciclos atingido");
        }
        BigDecimal limitPrice = priceAbove(input.currentPrice(), config.fillOffset());
        return StrategyOutputDto.buyAt(NAME, CONFIDENCE, config.quantity(), limitPrice,
                "cenario immediate-round-trip: BUY marketable para abrir");
    }
}
