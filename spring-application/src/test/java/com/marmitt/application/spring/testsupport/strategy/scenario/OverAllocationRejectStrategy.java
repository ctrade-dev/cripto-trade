package com.marmitt.application.spring.testsupport.strategy.scenario;

import com.marmitt.core.dto.strategy.StrategyContextDto;
import com.marmitt.core.dto.strategy.StrategyInputDto;
import com.marmitt.core.dto.strategy.StrategyOutputDto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Cenário 4 (T35): emite uma BUY cujo valor estimado ({@code quantity × price}) excede o capital
 * disponível, forçando o outcome {@code REJECTED_CAPITAL} na {@code CapitalReservationPolicy}.
 *
 * <p>Como a reserva é recusada antes do dispatch, nenhuma ordem vira {@code PENDING} nem lote —
 * a BUY não é barrada pela política SINGLE e o cenário se repete a cada tick. Usa preço de mercado
 * (sem {@code limitPrice}); só a quantidade importa aqui.
 */
public class OverAllocationRejectStrategy extends AbstractScenarioStrategy {

    private static final UUID STRATEGY_ID = UUID.fromString("b1000000-0000-4000-a000-000000000004");
    private static final String NAME = "OverAllocationRejectStrategy";
    private static final String VERSION = "1.0.0";

    public OverAllocationRejectStrategy() {
        this(ScenarioStrategyConfig.defaultConfig());
    }

    public OverAllocationRejectStrategy(ScenarioStrategyConfig config) {
        super(STRATEGY_ID, NAME, VERSION, config);
    }

    @Override
    public StrategyOutputDto executeStrategy(StrategyInputDto input, StrategyContextDto context) {
        // Estado sujo (posicao/ordem em transito de um estado anterior ou recuperado) faria a BUY ser
        // barrada pela RunnerSignalPolicy como REJECTED_POLICY em vez do REJECTED_CAPITAL pretendido, e
        // gastaria um ciclo do orcamento a toa (com maxCycles=1, HOLD para sempre depois que limpar).
        // Aguarda o estado limpar antes de consumir ciclo.
        if (context.hasPendingOrders() || context.hasOpenLots()) {
            return StrategyOutputDto.hold(NAME,
                    "cenario over-allocation-reject: aguardando estado limpo antes de forcar REJECTED_CAPITAL");
        }
        if (!tryStartCycle(context.runnerId())) {
            return StrategyOutputDto.hold(NAME, "cenario over-allocation-reject: teto de ciclos atingido");
        }
        BigDecimal quantity = overAllocatingQuantity(input.currentPrice(), context.availableCapital());
        return StrategyOutputDto.buy(NAME, CONFIDENCE, quantity,
                "cenario over-allocation-reject: BUY acima do capital para forcar REJECTED_CAPITAL");
    }

    /**
     * Quantidade cujo custo estimado supera o capital disponível: {@code (available / price) ×
     * factor}, com {@code factor > 1}. Nunca abaixo da quantidade fixa do config: com saldo muito
     * pequeno o valor calculado encolhe a ponto de o floor de {@code stepSize} da exchange zerá-lo,
     * o que desviaria o cenário do outcome {@code REJECTED_CAPITAL} para uma falha de filtro/dispatch
     * (reserva de valor 0). O piso ainda over-aloca no regime de saldo baixo — quando o calculado
     * fica abaixo do fixo, {@code fixo × price > available}, então a reserva segue sendo recusada.
     * Quando não há capital/preço utilizável, cai direto para a quantidade fixa (positiva).
     */
    private BigDecimal overAllocatingQuantity(BigDecimal price, BigDecimal availableCapital) {
        if (price != null && price.signum() > 0 && availableCapital != null && availableCapital.signum() > 0) {
            BigDecimal quantity = availableCapital
                    .divide(price, 8, RoundingMode.HALF_UP)
                    .multiply(config.overAllocationFactor());
            return quantity.max(config.quantity());
        }
        return config.quantity();
    }
}
