package com.marmitt.core.ports.outbound.strategy;

import com.marmitt.core.domain.strategy.StrategyEndpoint;
import com.marmitt.core.dto.strategy.StrategyContextDto;
import com.marmitt.core.dto.strategy.StrategyInputDto;
import com.marmitt.core.dto.strategy.StrategyOutputDto;

import java.util.UUID;

/**
 * Avaliação de uma estratégia que roda fora do processo (T41). Agnóstico de transporte: o adapter
 * decide REST, gRPC etc. Qualquer falha (indisponível, timeout, 5xx, resposta fora do contrato,
 * decisão velha) é sinalizada com {@link com.marmitt.core.exceptions.StrategyUnavailableException}.
 *
 * <p>O endereço vem do catálogo a cada chamada (T54): trocar endereço ou token vale na chamada seguinte,
 * sem reconstruir o cliente.
 */
public interface StrategyEvaluationPort {

    StrategyOutputDto evaluate(UUID strategyRef, StrategyEndpoint endpoint, StrategyInputDto input,
                               StrategyContextDto context);

    /**
     * Identidade e requisitos que o serviço declara para {@code strategyRef} ({@code /meta} no contrato REST).
     *
     * @throws com.marmitt.core.exceptions.StrategyUnavailableException se o serviço não responder ou
     *         não servir a versão
     */
    RemoteStrategyIdentity describe(UUID strategyRef, StrategyEndpoint endpoint);

    /**
     * @param contractVersion   versão do contrato de rede que o serviço implementa (ex.: {@code 2.0.0})
     * @param priceHistoryTicks ticks de histórico que a estratégia declarou precisar (T69)
     */
    record RemoteStrategyIdentity(UUID strategyId, String name, String version, String contractVersion,
                                  int priceHistoryTicks) {
    }
}
