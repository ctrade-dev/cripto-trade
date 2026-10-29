package com.marmitt.application.spring.testsupport.strategy.scenario;

import com.marmitt.core.dto.strategy.PendingOrderDto;
import com.marmitt.core.dto.strategy.StrategyContextDto;
import com.marmitt.core.dto.strategy.StrategyInputDto;
import com.marmitt.core.dto.strategy.StrategyOutputDto;
import com.marmitt.core.enums.TradingAction;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Cenário 1 (T35): abre uma BUY que descansa longe do mercado e a cancela. Exercita o ramo
 * BUY → CANCEL e a liberação da reserva de capital na conciliação do {@code CANCELED}.
 *
 * <p>Ciclo determinístico:
 * <ol>
 *   <li>sem pendência/lote → BUY com {@code limitPrice} abaixo do mercado (não preenche);</li>
 *   <li>tick seguinte vê a pending BUY → {@code SHOULD_CANCEL} dela;</li>
 *   <li>reemite o cancel a cada tick até o {@code CANCELED} assíncrono limpar a pendência
 *       (de-dup no dispatch), então recomeça.</li>
 * </ol>
 */
public class RestingBuyCancelStrategy extends AbstractScenarioStrategy {

    private static final UUID STRATEGY_ID = UUID.fromString("b1000000-0000-4000-a000-000000000001");
    private static final String NAME = "RestingBuyCancelStrategy";
    private static final String VERSION = "1.0.0";

    public RestingBuyCancelStrategy() {
        this(ScenarioStrategyConfig.defaultConfig());
    }

    public RestingBuyCancelStrategy(ScenarioStrategyConfig config) {
        super(STRATEGY_ID, NAME, VERSION, config);
    }

    @Override
    public StrategyOutputDto executeStrategy(StrategyInputDto input, StrategyContextDto context) {
        Optional<PendingOrderDto> pendingBuy = firstPendingOfType(context, TradingAction.SHOULD_BUY);
        if (pendingBuy.isPresent()) {
            return StrategyOutputDto.cancel(NAME, pendingBuy.get().transactionId(),
                    "cenario resting-buy-cancel: puxando a BUY que descansa");
        }

        if (!context.hasOpenLots() && !context.hasPendingOrders()) {
            if (!tryStartCycle(context.runnerId())) {
                return StrategyOutputDto.hold(NAME, "cenario resting-buy-cancel: teto de ciclos atingido");
            }
            BigDecimal limitPrice = priceBelow(input.currentPrice(), config.restingOffset());
            return StrategyOutputDto.buyAt(NAME, CONFIDENCE, config.quantity(), limitPrice,
                    "cenario resting-buy-cancel: BUY descansando abaixo do mercado");
        }

        return StrategyOutputDto.hold(NAME, "cenario resting-buy-cancel: aguardando estado limpo");
    }
}
