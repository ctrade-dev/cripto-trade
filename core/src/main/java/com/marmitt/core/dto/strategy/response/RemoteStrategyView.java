package com.marmitt.core.dto.strategy.response;

import com.marmitt.core.domain.strategy.RemoteStrategyStatus;
import com.marmitt.core.domain.strategy.RemoteStrategyVerification;
import com.marmitt.core.dto.strategy.StrategyRequirements;
import com.marmitt.core.enums.RunnerStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Estado de uma estrategia remota como o operador precisa ver para agir (T54). Nunca traz o token.
 *
 * @param usable    {@code true} quando os runners estao de fato consultando a estrategia neste
 *                  processo: habilitada e conferida desde o ultimo boot, cadastro, reabilitacao ou
 *                  troca de endereco
 * @param lastError motivo da ultima conferencia que falhou; nulo se a ultima passou
 * @param requirements historico declarado na primeira conferencia (ticks e candles); nulo antes dela
 * @param runners   runners que apontam para a estrategia (sem ela utilizavel, ficam em HOLD)
 */
public record RemoteStrategyView(UUID strategyRef,
                                 String name,
                                 String version,
                                 String baseUrl,
                                 boolean tokenConfigured,
                                 RemoteStrategyStatus status,
                                 RemoteStrategyVerification verification,
                                 boolean usable,
                                 String lastError,
                                 Instant lastVerificationAt,
                                 String servedContractVersion,
                                 StrategyRequirements requirements,
                                 Instant createdAt,
                                 Instant updatedAt,
                                 List<RunnerRef> runners) {

    public record RunnerRef(UUID runnerId, String shortCode, String symbol, RunnerStatus status) {
    }
}
