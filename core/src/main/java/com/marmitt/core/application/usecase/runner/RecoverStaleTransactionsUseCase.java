package com.marmitt.core.application.usecase.runner;

import com.marmitt.core.application.usecase.portfolio.AutomaticSafeModeTrigger;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.runner.request.RecoverStaleTransactionsRequest;
import com.marmitt.core.dto.runner.request.RecoverTransactionStatusRequest;
import com.marmitt.core.dto.runner.response.RecoverStaleTransactionsResponse;
import com.marmitt.core.dto.runner.response.RecoverTransactionStatusResponse;
import com.marmitt.core.dto.runner.response.RecoverTransactionStatusResponse.FailureReason;
import com.marmitt.core.enums.SafeModeTrigger;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.ports.inbound.runner.RecoverStaleTransactionsPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
public class RecoverStaleTransactionsUseCase implements RecoverStaleTransactionsPort {

    // Confirmados (ja tem exchangeOrderId) usam o stale-threshold normal.
    private static final List<TransactionStatus> CONFIRMED_STATUSES = List.of(
            TransactionStatus.SUBMITTED,
            TransactionStatus.PARTIAL
    );
    // PENDING (reserva orfa) usa uma carencia maior (pendingUpdatedBefore): nao pode ser
    // selecionado enquanto o dispatch+ACK ainda pode estar em voo (persist-first).
    private static final List<TransactionStatus> PENDING_STATUSES = List.of(
            TransactionStatus.PENDING
    );

    private static final int LAST_FAILURE_MAX_LENGTH = 200;

    private final StrategyRunnerRepositoryPort strategyRunnerRepository;
    private final RecoverTransactionStatusUseCase recoverTransactionStatusUseCase;
    private final AutomaticSafeModeTrigger safeModeTrigger;
    private final WatchdogFailures watchdogFailures;
    private final int maxStuckCycles;
    /** Ciclos consecutivos em que cada transacao falhou (T25); so guarda as que falharam no ultimo ciclo. */
    private Map<UUID, StuckCount> consecutiveFailures = new HashMap<>();

    /**
     * Ciclos seguidos sem resolver uma transacao e se todos falharam por exchange fora do ar (T71).
     */
    private record StuckCount(int cycles, boolean onlyExchangeUnavailable) {

        StuckCount next(boolean exchangeUnavailable) {
            return new StuckCount(cycles + 1, onlyExchangeUnavailable && exchangeUnavailable);
        }

        SafeModeTrigger trigger() {
            return onlyExchangeUnavailable ? SafeModeTrigger.EXCHANGE_UNAVAILABLE : SafeModeTrigger.WATCHDOG_STUCK;
        }
    }

    private static final StuckCount NONE = new StuckCount(0, true);

    /**
     * @param maxStuckCycles ciclos consecutivos sem resolver a mesma transacao que acionam HALT do
     *                       portfolio (T25)
     * @param watchdogFailures recebe, ao fim de cada ciclo, os runners com transacao sem resolver (T71)
     */
    public RecoverStaleTransactionsUseCase(StrategyRunnerRepositoryPort strategyRunnerRepository,
                                           RecoverTransactionStatusUseCase recoverTransactionStatusUseCase,
                                           AutomaticSafeModeTrigger safeModeTrigger,
                                           WatchdogFailures watchdogFailures,
                                           int maxStuckCycles) {
        if (maxStuckCycles <= 0) {
            throw new IllegalArgumentException("maxStuckCycles must be positive: " + maxStuckCycles);
        }
        this.strategyRunnerRepository = strategyRunnerRepository;
        this.recoverTransactionStatusUseCase = recoverTransactionStatusUseCase;
        this.safeModeTrigger = safeModeTrigger;
        this.watchdogFailures = watchdogFailures;
        this.maxStuckCycles = maxStuckCycles;
    }

    @Override
    public RecoverStaleTransactionsResponse execute(RecoverStaleTransactionsRequest request) {
        if (request.maxPerRun() == 0) {
            watchdogFailures.cycleCompleted(Set.of());
            return new RecoverStaleTransactionsResponse(0, 0, 0, 0, 0);
        }

        List<Transaction> confirmed = strategyRunnerRepository.findByStatusesUpdatedBefore(
                CONFIRMED_STATUSES,
                request.updatedBefore(),
                request.maxPerRun()
        );
        List<Transaction> pending = strategyRunnerRepository.findByStatusesUpdatedBefore(
                PENDING_STATUSES,
                request.pendingUpdatedBefore(),
                request.maxPerRun()
        );

        List<Transaction> candidates = new ArrayList<>(confirmed.size() + pending.size());
        candidates.addAll(confirmed);
        candidates.addAll(pending);

        int recovered = 0;
        int routedToDlq = 0;
        int skipped = 0;
        int failed = 0;
        Map<UUID, StuckCount> failuresThisCycle = new HashMap<>();
        Set<UUID> failingRunners = new HashSet<>();

        for (Transaction candidate : candidates) {
            try {
                RecoverTransactionStatusResponse response = recoverTransactionStatusUseCase.execute(
                        RecoverTransactionStatusRequest.forRuntimeWatchdog(candidate.getId()));

                if (response.outcome() == RecoverTransactionStatusResponse.RecoveryOutcome.RECOVERED) {
                    if (response.action() == RecoverTransactionStatusResponse.RecoveryAction.ROUTED_TO_DLQ) {
                        routedToDlq++;
                    } else {
                        recovered++;
                    }
                    continue;
                }

                if (response.outcome() == RecoverTransactionStatusResponse.RecoveryOutcome.SKIPPED) {
                    skipped++;
                    continue;
                }

                failed++;
                failingRunners.add(candidate.getRunnerId());
                recordFailure(candidate, failuresThisCycle,
                        response.failureReason() == FailureReason.EXCHANGE_QUERY_RETRYABLE_FAILURE,
                        response.failureReason() + ": " + response.message());
                log.warn("runtimeRecoveryBatch: failed transactionId={} reason={} failureReason={}",
                        response.transactionId(), response.message(), response.failureReason());
            } catch (RuntimeException e) {
                failed++;
                failingRunners.add(candidate.getRunnerId());
                recordFailure(candidate, failuresThisCycle, false,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                log.error("runtimeRecoveryBatch: unexpected failure transactionId={}", candidate.getId(), e);
            }
        }
        // Transacao resolvida, pulada ou fora dos candidatos zera a contagem: so conta ciclo seguido.
        consecutiveFailures = failuresThisCycle;
        watchdogFailures.cycleCompleted(failingRunners);

        return new RecoverStaleTransactionsResponse(
                candidates.size(),
                recovered,
                routedToDlq,
                skipped,
                failed
        );
    }

    /**
     * Conta mais um ciclo sem resolver a transacao; no limite, aciona HALT do portfolio do runner (T25).
     * Transacao presa que nem a exchange esclarece deixa capital e posicao em estado incerto.
     *
     * <p>O gatilho diz se a exchange estava fora do ar (T71): {@code EXCHANGE_UNAVAILABLE} quando todos os
     * ciclos contados falharam com {@code EXCHANGE_QUERY_RETRYABLE_FAILURE} (timeout, 5xx, limite de peso), e o
     * HALT volta sozinho com a exchange saudavel; qualquer outra falha no meio mantem {@code WATCHDOG_STUCK},
     * de retomada manual. O motivo leva a ultima falha (T66).
     */
    private void recordFailure(Transaction candidate, Map<UUID, StuckCount> failuresThisCycle,
                               boolean exchangeUnavailable, String lastFailure) {
        StuckCount count = consecutiveFailures.getOrDefault(candidate.getId(), NONE).next(exchangeUnavailable);
        failuresThisCycle.put(candidate.getId(), count);
        if (count.cycles() < maxStuckCycles) {
            return;
        }
        strategyRunnerRepository.findById(candidate.getRunnerId()).ifPresent(runner ->
                safeModeTrigger.activate(runner.getPortfolioId(), count.trigger(),
                        "transactionId=" + candidate.getId() + " runnerId=" + runner.getId()
                                + " status=" + candidate.getStatus() + " cycles=" + count.cycles()
                                + " lastFailure=" + truncate(lastFailure)));
    }

    private static String truncate(String text) {
        return text.length() <= LAST_FAILURE_MAX_LENGTH ? text : text.substring(0, LAST_FAILURE_MAX_LENGTH) + "...";
    }
}
