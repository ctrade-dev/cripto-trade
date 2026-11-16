package com.marmitt.core.application.usecase.runner;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Runners com transação contando ciclos de falha no watchdog no último ciclo (T71). O watchdog
 * ({@link RecoverStaleTransactionsUseCase}) publica o conjunto ao fim de cada ciclo; a retomada automática do Safe
 * Mode o lê para não considerar saudável uma exchange que ainda não responde às consultas de ordem.
 *
 * <p>Estado só em memória: depois de um restart começa vazio, e o watchdog volta a contar do zero.
 */
public class WatchdogFailures {

    private volatile Set<UUID> failingRunners = Set.of();

    void cycleCompleted(Set<UUID> runnerIds) {
        this.failingRunners = Set.copyOf(runnerIds);
    }

    /** Se algum dos runners teve transação sem resolver no último ciclo do watchdog. */
    public boolean anyFailing(Collection<UUID> runnerIds) {
        Set<UUID> snapshot = failingRunners;
        return runnerIds.stream().anyMatch(snapshot::contains);
    }
}
