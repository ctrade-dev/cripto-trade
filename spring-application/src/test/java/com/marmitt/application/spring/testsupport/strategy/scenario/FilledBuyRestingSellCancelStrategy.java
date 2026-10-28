package com.marmitt.application.spring.testsupport.strategy.scenario;

import com.marmitt.core.dto.strategy.OpenLotDto;
import com.marmitt.core.dto.strategy.PendingOrderDto;
import com.marmitt.core.dto.strategy.StrategyContextDto;
import com.marmitt.core.dto.strategy.StrategyInputDto;
import com.marmitt.core.dto.strategy.StrategyOutputDto;
import com.marmitt.core.enums.TradingAction;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Cenário 2 (T35): compra (fill garantido) e então coloca uma SELL que descansa longe do mercado
 * e a cancela. Exercita fill → SELL → CANCEL e a liberação do <b>lock de posição</b>.
 *
 * <p>Ciclo determinístico:
 * <ol>
 *   <li>pending SELL presente → {@code SHOULD_CANCEL} dela;</li>
 *   <li>qualquer outra pendência (ex.: BUY marketable ainda não preencheu) → aguarda;</li>
 *   <li>sem lote → BUY marketable (preço acima do mercado) para preencher como taker;</li>
 *   <li>lote aberto e sem pendência → SELL com {@code limitPrice} acima do mercado (descansa).</li>
 * </ol>
 *
 * <p><b>Escopo:</b> o lote permanece aberto; a estratégia cicla place-SELL/cancel-SELL sobre ele.
 * Fechar o lote é objetivo do cenário 3 ({@link ImmediateRoundTripStrategy}), não deste.
 */
public class FilledBuyRestingSellCancelStrategy extends AbstractScenarioStrategy {

    private static final UUID STRATEGY_ID = UUID.fromString("b1000000-0000-4000-a000-000000000002");
    private static final String NAME = "FilledBuyRestingSellCancelStrategy";
    private static final String VERSION = "1.0.0";

    public FilledBuyRestingSellCancelStrategy() {
        this(ScenarioStrategyConfig.defaultConfig());
    }

    public FilledBuyRestingSellCancelStrategy(ScenarioStrategyConfig config) {
        super(STRATEGY_ID, NAME, VERSION, config);
    }

    @Override
    public StrategyOutputDto executeStrategy(StrategyInputDto input, StrategyContextDto context) {
        Optional<PendingOrderDto> pendingSell = firstPendingOfType(context, TradingAction.SHOULD_SELL);
        if (pendingSell.isPresent()) {
            return StrategyOutputDto.cancel(NAME, pendingSell.get().transactionId(),
                    "cenario filled-buy-resting-sell-cancel: puxando a SELL que descansa");
        }

        // BUY marketable ainda em trânsito (ou qualquer pendência): aguarda o fill antes de vender.
        if (context.hasPendingOrders()) {
            return StrategyOutputDto.hold(NAME,
                    "cenario filled-buy-resting-sell-cancel: aguardando fill da BUY");
        }

        if (!context.hasOpenLots()) {
            // Reabertura de posição também respeita o teto: se a SELL que descansa preencheu e fechou
            // o lote após o cap, o estado fica limpo — sem este gate o cenário emitiria um novo BUY
            // (e novo ciclo) violando o contrato e2e de nao produzir transacoes extras apos o teto.
            if (budgetExhausted(context.runnerId())) {
                return StrategyOutputDto.hold(NAME,
                        "cenario filled-buy-resting-sell-cancel: teto de ciclos atingido, nao reabre posicao");
            }
            BigDecimal limitPrice = priceAbove(input.currentPrice(), config.fillOffset());
            return StrategyOutputDto.buyAt(NAME, CONFIDENCE, config.quantity(), limitPrice,
                    "cenario filled-buy-resting-sell-cancel: BUY marketable para preencher");
        }

        if (!tryStartCycle(context.runnerId())) {
            return StrategyOutputDto.hold(NAME, "cenario filled-buy-resting-sell-cancel: teto de ciclos atingido");
        }
        OpenLotDto lot = context.openLots().getFirst();
        BigDecimal limitPrice = priceAbove(input.currentPrice(), config.restingOffset());
        return StrategyOutputDto.sellAt(NAME, CONFIDENCE, lot.availableQuantity(), limitPrice,
                "cenario filled-buy-resting-sell-cancel: SELL descansando acima do mercado (lote segue aberto)");
    }
}
