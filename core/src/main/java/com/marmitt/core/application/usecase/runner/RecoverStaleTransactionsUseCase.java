package com.marmitt.core.application.usecase.runner;

import com.marmitt.core.application.usecase.portfolio.AutomaticSafeModeTrigger;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.runner.request.RecoverStaleTransactionsRequest;
import com.marmitt.core.dto.runner.request.RecoverTransactionStatusRequest;
import com.marmitt.core.dto.runner.response.RecoverStaleTransactionsResponse;
import com.marmitt.core.dto.runner.response.RecoverTransactionStatusResponse;
import com.marmitt.core.enums.SafeModeTrigger;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.ports.inbound.runner.RecoverStaleTransactionsPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final int maxStuckCycles;
    /** Ciclos consecutivos em que cada transacao falhou (T25); so guarda as que falharam no ultimo ciclo. */
    private Map<UUID, Integer> consecutiveFailures = new HashMap<>();

    /**
     * @param maxStuckCycles ciclos consecutivos sem resolver a mesma transacao que acionam HALT do
     *                       portfolio (T25)
     */
    public RecoverStaleTransactionsUseCase(StrategyRunnerRepositoryPort strategyRunnerRepository,
                                           RecoverTransactionStatusUseCase recoverTransactionStatusUseCase,
                                           AutomaticSafeModeTrigger safeModeTrigger,
                                           int maxStuckCycles) {
        if (maxStuckCycles <= 0) {
            throw new IllegalArgumentException("maxStuckCycles must be positive: " + maxStuckCycles);
        }
        this.strategyRunnerRepository = strategyRunnerRepository;
        this.recoverTransactionStatusUseCase = recoverTransactionStatusUseCase;
        this.safeModeTrigger = safeModeTrigger;
        this.maxStuckCycles = maxStuckCycles;
    }

    @Override
    public RecoverStaleTransactionsResponse execute(RecoverStaleTransactionsRequest request) {
        if (request.maxPerRun() == 0) {
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
        Map<UUID, Integer> failuresThisCycle = new HashMap<>();

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
                recordFailure(candidate, failuresThisCycle, response.failureReason() + ": " + response.message());
                log.warn("runtimeRecoveryBatch: failed transactionId={} reason={} failureReason={}",
                        response.transactionId(), response.message(), response.failureReason());
            } catch (RuntimeException e) {
                failed++;
                recordFailure(candidate, failuresThisCycle, e.getClass().getSimpleName() + ": " + e.getMessage());
                log.error("runtimeRecoveryBatch: unexpected failure transactionId={}", candidate.getId(), e);
            }
        }
        // Transacao resolvida, pulada ou fora dos candidatos zera a contagem: so conta ciclo seguido.
        consecutiveFailures = failuresThisCycle;

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
     * <p>O motivo leva a ultima falha (T66): o evento do Safe Mode diz sozinho se foi exchange fora do
     * ar, limite de peso ou outra coisa, sem depender do log.
     */
    private void recordFailure(Transaction candidate, Map<UUID, Integer> failuresThisCycle, String lastFailure) {
        int cycles = consecutiveFailures.getOrDefault(candidate.getId(), 0) + 1;
        failuresThisCycle.put(candidate.getId(), cycles);
        if (cycles < maxStuckCycles) {
            return;
        }
        strategyRunnerRepository.findById(candidate.getRunnerId()).ifPresent(runner ->
                safeModeTrigger.activate(runner.getPortfolioId(), SafeModeTrigger.WATCHDOG_STUCK,
                        "transactionId=" + candidate.getId() + " runnerId=" + runner.getId()
                                + " status=" + candidate.getStatus() + " cycles=" + cycles
                                + " lastFailure=" + truncate(lastFailure)));
    }

    private static String truncate(String text) {
        return text.length() <= LAST_FAILURE_MAX_LENGTH ? text : text.substring(0, LAST_FAILURE_MAX_LENGTH) + "...";
    }
}
