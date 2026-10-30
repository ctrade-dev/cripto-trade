package com.marmitt.core.application.usecase.runner.orderconciliation;

import com.marmitt.core.domain.runner.ClientOrderId;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.websocket.data.OrderDataDto;
import com.marmitt.core.ports.outbound.events.EventPublisherPort;
import com.marmitt.core.ports.outbound.exchange.rest.OrderQuantityNormalizerPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.util.List;

/**
 * Nucleo de reconciliacao de atualizacao de ordem.
 *
 * <p>Centraliza o roteamento por status e a aplicacao das transicoes de estado
 * em transacao/posicao/margem. Pode ser usado com fronteira transacional externa
 * (callbacks) ou com persistencia direta (boot recovery).
 */
@Slf4j
public class ConciliationOrderUpdate {

    @FunctionalInterface
    public interface SubmitAction {
        void apply(Transaction transaction);
    }

    @FunctionalInterface
    public interface FillAction {
        void apply(Transaction transaction, OrderDataDto orderData, boolean isFinal);
    }

    @FunctionalInterface
    public interface ReleaseAction {
        void apply(Transaction transaction);
    }

    private final StrategyRunnerRepositoryPort strategyRunnerRepository;
    private final FillCalculator fillCalculator;
    private final BuyFillHandler buyFillHandler;
    private final SellFillHandler sellFillHandler;
    private final TerminationHandler terminationHandler;

    /**
     * @param orderNormalizers normalizadores de quantidade por exchange, usados para decidir se o
     *                         residuo de uma posicao ainda e vendavel (T46)
     */
    public ConciliationOrderUpdate(StrategyRunnerRepositoryPort strategyRunnerRepository,
                                   EventPublisherPort eventPublisher,
                                   List<OrderQuantityNormalizerPort> orderNormalizers) {
        this.strategyRunnerRepository = strategyRunnerRepository;
        MarginReleaseBuilder marginReleaseBuilder = new MarginReleaseBuilder();
        FeeResolver feeResolver = new FeeResolver();
        UnsellableRemainderWriteOff remainderWriteOff = new UnsellableRemainderWriteOff(
                strategyRunnerRepository, new SellableQuantityPolicy(orderNormalizers));
        this.fillCalculator = new FillCalculator();
        this.buyFillHandler = new BuyFillHandler(
                strategyRunnerRepository, eventPublisher, marginReleaseBuilder, feeResolver);
        this.sellFillHandler = new SellFillHandler(strategyRunnerRepository, eventPublisher, feeResolver,
                remainderWriteOff);
        this.terminationHandler = new TerminationHandler(
                strategyRunnerRepository, eventPublisher, marginReleaseBuilder, remainderWriteOff);
    }

    /**
     * Roteia um evento de ordem para as acoes de submit/fill/release fornecidas.
     * Ideal para uso com fronteiras transacionais externas.
     */
    public void execute(OrderDataDto orderData,
                        SubmitAction submitAction,
                        FillAction fillAction,
                        ReleaseAction releaseAction) {
        String clientOrderId = orderData.clientOrderId();

        if (!ClientOrderId.isValid(clientOrderId)) {
            log.warn("orderConciliation: clientOrderId={} not in v1 format - skipping", clientOrderId);
            return;
        }

        Transaction transaction = strategyRunnerRepository
                .findTransactionByClientOrderId(clientOrderId)
                .orElse(null);

        if (transaction == null) {
            log.warn("orderConciliation: transaction not found for clientOrderId={} - skipping", clientOrderId);
            return;
        }

        log.debug("orderConciliation: routing clientOrderId={} transactionId={} status={}",
                clientOrderId, transaction.getId(), orderData.status());

        MDC.put("transactionId", transaction.getId().toString());
        try {
            switch (orderData.status()) {
                case NEW -> {
                    if (!transaction.isPending()) {
                        log.debug("orderConciliation: duplicate NEW ignored transactionId={} status={}",
                                transaction.getId(), transaction.getStatus());
                        return;
                    }
                    transaction.submit(orderData.orderId());
                    submitAction.apply(transaction);
                }
                case FILLED -> fillAction.apply(transaction, orderData, true);
                case PARTIALLY_FILLED -> fillAction.apply(transaction, orderData, false);
                case CANCELED, EXPIRED, REJECTED -> terminate(transaction, orderData, fillAction, releaseAction);
                default -> log.warn("orderConciliation: unexpected status={} for clientOrderId={}",
                        orderData.status(), clientOrderId);
            }
        } catch (RuntimeException e) {
            log.error("orderConciliation: failed transactionId={} clientOrderId={} status={} - {}",
                    transaction.getId(), clientOrderId, orderData.status(), e.getMessage(), e);
            throw e;
        } finally {
            MDC.remove("transactionId");
        }
    }

    /**
     * Encerra a transacao com o status terminal da exchange (T67).
     *
     * <ul>
     *   <li><b>Repetido:</b> transacao ja final ignora o evento, como o FILLED repetido. O mesmo
     *       encerramento chega pelo stream e pela consulta do watchdog.</li>
     *   <li><b>Execucao que so chega no encerramento:</b> se a exchange informa mais quantidade executada
     *       do que a transacao conhece (o watchdog leu por REST um fill que o stream perdeu), o incremento
     *       e aplicado como fill parcial antes de encerrar. Sem isso o fill sumiria da contabilidade.</li>
     *   <li><b>EXPIRED depois de execucao parcial:</b> a Binance expira o resto de uma ordem a mercado que
     *       esgota o livro. Pela maquina de estados (IG 6.1.2), ordem interrompida depois de executar e
     *       {@code CANCELED}: devolve so a reserva nao executada e destrava o resto do lote.
     *       {@code EXPIRED} fica para ordem sem nenhuma execucao.</li>
     * </ul>
     */
    private void terminate(Transaction transaction,
                           OrderDataDto orderData,
                           FillAction fillAction,
                           ReleaseAction releaseAction) {
        if (transaction.isFinal()) {
            log.debug("orderConciliation: duplicate {} ignored transactionId={} status={}",
                    orderData.status(), transaction.getId(), transaction.getStatus());
            return;
        }
        BigDecimal incoming = orderData.executedQuantity();
        BigDecimal current = transaction.getEffectiveExecutedQuantity();
        if (incoming != null && incoming.compareTo(current) > 0) {
            log.info("orderConciliation: execution reported with {} applied transactionId={} currentQty={} incomingQty={}",
                    orderData.status(), transaction.getId(), current, incoming);
            fillAction.apply(transaction, orderData, false);
        }
        switch (orderData.status()) {
            case CANCELED -> transaction.cancel();
            case EXPIRED -> {
                if (transaction.getEffectiveExecutedQuantity().signum() > 0) {
                    transaction.cancel();
                } else {
                    transaction.expire();
                }
            }
            case REJECTED -> transaction.reject(orderData.rejectReason());
            default -> throw new IllegalArgumentException("Not a terminal status: " + orderData.status());
        }
        releaseAction.apply(transaction);
    }

    /**
     * Atalho para reconciliacao com persistencia direta.
     * Usado no boot recovery para evitar acoplamento com outro use case.
     */
    public void execute(OrderDataDto orderData) {
        execute(orderData, this::submitTransaction, this::processFill, this::releaseMargin);
    }

    public void submitTransaction(Transaction transaction) {
        strategyRunnerRepository.saveTransaction(transaction);
        log.info("orderConciliation: PENDING->SUBMITTED transactionId={} exchangeOrderId={}",
                transaction.getId(), transaction.getExchangeOrderId());
    }

    public void processFill(Transaction transaction, OrderDataDto orderData, boolean isFinal) {
        if (transaction.isPending() && orderData.orderId() != null) {
            transaction.submit(orderData.orderId());
            log.debug("orderConciliation: PENDING->SUBMITTED via fill transactionId={} exchangeOrderId={}",
                    transaction.getId(), orderData.orderId());
        }
        if (isFinal) {
            BigDecimal incoming = orderData.executedQuantity();
            BigDecimal current = transaction.getEffectiveExecutedQuantity();
            if (incoming != null && incoming.compareTo(current) <= 0) {
                log.debug("orderConciliation: duplicate FILLED ignored transactionId={} currentQty={} incomingQty={}",
                        transaction.getId(), current, incoming);
                return;
            }
            if (transaction.isFinal()) {
                log.debug("orderConciliation: duplicate FILLED ignored transactionId={} status={}",
                        transaction.getId(), transaction.getStatus());
                return;
            }
        } else {
            BigDecimal incoming = orderData.executedQuantity();
            BigDecimal current = transaction.getEffectiveExecutedQuantity();
            if (incoming == null || incoming.compareTo(current) <= 0) {
                log.debug("orderConciliation: duplicate PARTIALLY_FILLED ignored transactionId={} currentQty={} incomingQty={}",
                        transaction.getId(), current, incoming);
                return;
            }
        }
        FillCalculator.FillComputation computed = fillCalculator.compute(transaction, orderData, isFinal);
        if (transaction.isBuy()) {
            buyFillHandler.handle(transaction, orderData, computed.fillIncrement(), computed.costIncrement());
        } else {
            sellFillHandler.handle(transaction, orderData,
                    computed.fillIncrement(), computed.fillPrice(), isFinal);
        }
    }

    public void releaseMargin(Transaction transaction) {
        terminationHandler.handle(transaction);
    }
}
