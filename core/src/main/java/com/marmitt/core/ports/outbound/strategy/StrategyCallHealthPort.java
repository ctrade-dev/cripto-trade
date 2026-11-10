package com.marmitt.core.ports.outbound.strategy;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Saude recente das chamadas a estrategias que rodam fora do processo (T53 F21): desde quando as chamadas de
 * avaliacao de uma estrategia falham sem nenhum sucesso no meio. Um sucesso zera. O estado consolidado usa
 * isto para mostrar uma app de estrategia fora do ar, que antes so aparecia nas metricas e no log.
 */
public interface StrategyCallHealthPort {

    /**
     * @return vazio se a ultima chamada deu certo ou se nao houve chamada desde o boot
     */
    Optional<FailingSince> failingSince(UUID strategyRef);

    /**
     * @param since      primeira falha da sequencia atual
     * @param lastReason motivo da falha mais recente
     */
    record FailingSince(Instant since, String lastReason) {
    }
}
