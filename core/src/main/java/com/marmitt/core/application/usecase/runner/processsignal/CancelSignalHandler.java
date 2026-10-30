package com.marmitt.core.application.usecase.runner.processsignal;

import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.exceptions.ExchangeQueryException;
import com.marmitt.core.ports.outbound.exchange.OrderDispatchPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * Executa o ramo {@code SHOULD_CANCEL} do pipeline de sinais: valida o alvo e despacha o
 * cancelamento (fire-and-forget) via {@link OrderDispatchPort#cancel}.
 *
 * <p><b>Rejeicao silenciosa:</b> o contexto da estrategia e montado no inicio do tick e pode
 * estar levemente defasado (a ordem pode ter enchido ou sumido nesse meio-tempo). Alvo
 * inexistente, de outro runner ou ja terminal e tratado como no-op logado — nunca excecao que
 * derrube o tick.
 *
 * <p><b>Ordem que a exchange ja nao conhece (T67):</b> o cancelamento pode chegar depois que a ordem
 * terminou na exchange (preencheu ou expirou). E o mesmo contexto defasado, visto pela exchange: no-op
 * logado, sem derrubar o tick. O status final chega pelo stream ou pelo watchdog.
 *
 * <p><b>Sem marcacao terminal otimista:</b> o handler nao altera o estado local. O {@code CANCELED}
 * real chega via stream e e conciliado pelo caminho idempotente, liberando capital quando aplicavel.
 */
@Slf4j
class CancelSignalHandler {

    private final StrategyRunnerRepositoryPort strategyRunnerRepository;
    private final TradeIntentFactory intentFactory;
    private final OrderDispatchPort orderDispatch;

    CancelSignalHandler(StrategyRunnerRepositoryPort strategyRunnerRepository, TradeIntentFactory intentFactory,
                        OrderDispatchPort orderDispatch) {
        this.strategyRunnerRepository = strategyRunnerRepository;
        this.intentFactory = intentFactory;
        this.orderDispatch = orderDispatch;
    }

    /**
     * Valida o alvo e despacha o cancelamento. Usado pelo {@code SHOULD_CANCEL} da estrategia (que
     * ignora o desfecho) e pelo comando admin de cancelar ordem (T49), que o traduz em resposta.
     */
    CancelOutcome handle(StrategyRunner runner, UUID targetTransactionId, String reason) {
        if (targetTransactionId == null) {
            log.warn("processCancelSignal: cancel without targetTransactionId runnerId={} - ignored", runner.getId());
            return CancelOutcome.NOT_FOUND;
        }

        Transaction transaction = strategyRunnerRepository.findTransactionById(targetTransactionId).orElse(null);
        if (transaction == null) {
            log.warn("processCancelSignal: target transaction not found transactionId={} runnerId={} - ignored",
                    targetTransactionId, runner.getId());
            return CancelOutcome.NOT_FOUND;
        }

        if (!transaction.getRunnerId().equals(runner.getId())) {
            log.warn("processCancelSignal: target belongs to another runner transactionId={} owner={} requester={} - ignored",
                    targetTransactionId, transaction.getRunnerId(), runner.getId());
            return CancelOutcome.NOT_FOUND;
        }

        if (!transaction.getStatus().isInFlight()) {
            log.info("processCancelSignal: target not cancelable transactionId={} status={} runnerId={} - no-op",
                    targetTransactionId, transaction.getStatus(), runner.getId());
            return CancelOutcome.NOT_CANCELABLE;
        }

        // De-dup de reenvios (estrategia deterministica reemite SHOULD_CANCEL a cada tick enquanto o
        // CANCELED async nao chega) e tratado no OrderDispatchAdapter, que marca o cooldown apenas
        // apos um envio REAL (blocked/unsupported nao consomem a janela).
        if (!sendCancel(runner, transaction)) {
            return CancelOutcome.NOT_CANCELABLE;
        }

        log.info("processCancelSignal: cancel dispatched transactionId={} clientOrderId={} runnerId={} reason={}",
                targetTransactionId, transaction.getClientOrderId(), runner.getId(), reason);
        return CancelOutcome.DISPATCHED;
    }

    /**
     * Cancela a ordem se ela for LIMIT e estiver em voo; MARKET nao e cancelada (executa de imediato e
     * costuma ser a propria saida). Fonte unica do cancelamento decidido pelo core: Safe Mode (T45) e
     * protecao do lote (T44). Falha de envio e logada e nao propaga: o proximo tick tenta de novo.
     *
     * @return {@code true} quando o cancelamento foi enviado
     */
    boolean cancelResting(StrategyRunner runner, Transaction transaction, String reason) {
        if (transaction.getOrderType() != OrderType.LIMIT || !transaction.getStatus().isInFlight()) {
            return false;
        }
        try {
            if (!sendCancel(runner, transaction)) {
                return false;
            }
            log.info("cancelResting: cancel dispatched runnerId={} transactionId={} clientOrderId={} reason={}",
                    runner.getId(), transaction.getId(), transaction.getClientOrderId(), reason);
            return true;
        } catch (RuntimeException e) {
            log.warn("cancelResting: cancel failed runnerId={} transactionId={} - retried next tick: {}",
                    runner.getId(), transaction.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Envia o cancelamento.
     *
     * @return {@code false} quando a exchange ja nao conhece a ordem ({@code ORDER_NOT_FOUND})
     */
    private boolean sendCancel(StrategyRunner runner, Transaction transaction) {
        try {
            orderDispatch.cancel(intentFactory.buildCancelCommand(runner, transaction));
            return true;
        } catch (ExchangeQueryException e) {
            if (e.errorType() != ExchangeQueryException.ErrorType.ORDER_NOT_FOUND) {
                throw e;
            }
            log.warn("cancel: order unknown to the exchange runnerId={} transactionId={} status={} - awaiting its final status",
                    runner.getId(), transaction.getId(), transaction.getStatus());
            return false;
        }
    }

    /**
     * Desfecho da validacao do alvo. Alvo de outro runner conta como inexistente: o runner nao
     * enxerga ordens alheias.
     */
    enum CancelOutcome {
        DISPATCHED,
        NOT_FOUND,
        NOT_CANCELABLE
    }
}
