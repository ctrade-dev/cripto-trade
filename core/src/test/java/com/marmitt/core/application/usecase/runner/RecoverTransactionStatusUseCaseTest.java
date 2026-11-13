package com.marmitt.core.application.usecase.runner;

import com.marmitt.core.application.usecase.runner.orderconciliation.ConciliationOrderUpdate;
import com.marmitt.core.application.usecase.runner.orderconciliation.ConciliationOrderUpdateExecutor;
import com.marmitt.core.domain.Symbol;
import com.marmitt.core.domain.portfolio.DeadLetterEntry;
import com.marmitt.core.domain.runner.ClientOrderId;
import com.marmitt.core.domain.runner.LiquidationPolicy;
import com.marmitt.core.domain.runner.PositionProtection;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.runner.request.RecoverTransactionStatusRequest;
import com.marmitt.core.dto.runner.response.RecoverTransactionStatusResponse;
import com.marmitt.core.dto.websocket.data.OrderDataDto;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.AccountingPolicyType;
import com.marmitt.core.enums.DlqReason;
import com.marmitt.core.enums.ExecutionPolicy;
import com.marmitt.core.enums.RunnerStatus;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.enums.TransactionType;
import com.marmitt.core.exceptions.ExchangeQueryException;
import com.marmitt.core.ports.outbound.events.EventPublisherPort;
import com.marmitt.core.ports.outbound.exchange.ExchangeAdapterDescriptor;
import com.marmitt.core.ports.outbound.exchange.rest.ExchangeOrderQueryPort;
import com.marmitt.core.ports.outbound.repository.DeadLetterEntryRepositoryPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import com.marmitt.core.ports.outbound.marketdata.LatestPricePort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecoverTransactionStatusUseCaseTest {

    @Test
    void executeRecoversSubmittedTransactionFromPositiveExchangeQuery() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        ExchangeAdapterRepositoryPort exchangeRepository = mock(ExchangeAdapterRepositoryPort.class);
        ExchangeOrderQueryPort orderQueryPort = mock(ExchangeOrderQueryPort.class);

        Transaction transaction = newBuyTransaction();
        transaction.submit("EX_ORDER_LOCAL");
        StrategyRunner runner = newRunner(transaction.getRunnerId(), "BINANCE");

        when(repository.findTransactionById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(repository.findById(transaction.getRunnerId())).thenReturn(Optional.of(runner));
        when(repository.findTransactionByClientOrderId(transaction.getClientOrderId())).thenReturn(Optional.of(transaction));
        when(repository.findPositionByOpenedByTransactionIdForUpdate(transaction.getId())).thenReturn(Optional.empty());
        when(repository.findOpenPositionByRunnerIdAndSymbolForUpdate(transaction.getRunnerId(), transaction.getSymbol()))
                .thenReturn(Optional.empty());
        when(repository.trySavePosition(any())).thenReturn(true);
        stubOrderQuery(exchangeRepository, orderQueryPort);
        when(orderQueryPort.queryOrderByClientOrderId(transaction.getSymbol(), transaction.getClientOrderId()))
                .thenReturn(Optional.of(orderData(
                        transaction,
                        OrderDataDto.OrderStatus.FILLED,
                        new BigDecimal("2.50000000"),
                        new BigDecimal("10.20000000"),
                        "EX_ORDER_REMOTE"
                )));

        RecoverTransactionStatusUseCase useCase = newUseCase(repository, exchangeRepository);

        RecoverTransactionStatusResponse response = useCase.execute(
                new RecoverTransactionStatusRequest(transaction.getId()));

        assertEquals(RecoverTransactionStatusResponse.RecoveryOutcome.RECOVERED, response.outcome());
        assertEquals(RecoverTransactionStatusResponse.RecoveryAction.RECONCILED_FROM_EXCHANGE, response.action());
        assertEquals(TransactionStatus.SUBMITTED, response.statusBefore());
        assertEquals(TransactionStatus.FILLED, response.statusAfter());
        assertEquals(TransactionStatus.FILLED, transaction.getStatus());
        verify(orderQueryPort).queryOrderByClientOrderId(transaction.getSymbol(), transaction.getClientOrderId());
    }

    @Test
    void executeMarksSubmittedTransactionExpiredWhenExchangeDoesNotFindOrderInBootMode() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        ExchangeAdapterRepositoryPort exchangeRepository = mock(ExchangeAdapterRepositoryPort.class);
        ExchangeOrderQueryPort orderQueryPort = mock(ExchangeOrderQueryPort.class);

        Transaction transaction = newBuyTransaction();
        transaction.submit("EX_ORDER_LOCAL");
        StrategyRunner runner = newRunner(transaction.getRunnerId(), "BINANCE");

        when(repository.findTransactionById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(repository.findById(transaction.getRunnerId())).thenReturn(Optional.of(runner));
        when(repository.findTransactionByClientOrderId(transaction.getClientOrderId())).thenReturn(Optional.of(transaction));
        stubOrderQuery(exchangeRepository, orderQueryPort);
        when(orderQueryPort.queryOrderByClientOrderId(transaction.getSymbol(), transaction.getClientOrderId()))
                .thenReturn(Optional.empty());

        RecoverTransactionStatusUseCase useCase = newUseCase(repository, exchangeRepository);

        RecoverTransactionStatusResponse response = useCase.execute(
                RecoverTransactionStatusRequest.forBoot(transaction.getId()));

        assertEquals(RecoverTransactionStatusResponse.RecoveryOutcome.RECOVERED, response.outcome());
        assertEquals(RecoverTransactionStatusResponse.RecoveryAction.MARKED_EXPIRED, response.action());
        assertEquals(TransactionStatus.EXPIRED, response.statusAfter());
        assertEquals(TransactionStatus.EXPIRED, transaction.getStatus());
    }

    @Test
    void executeMarksPartialTransactionCanceledWhenExchangeDoesNotFindOrderInBootMode() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        ExchangeAdapterRepositoryPort exchangeRepository = mock(ExchangeAdapterRepositoryPort.class);
        ExchangeOrderQueryPort orderQueryPort = mock(ExchangeOrderQueryPort.class);

        Transaction transaction = newBuyTransaction();
        transaction.submit("EX_ORDER_LOCAL");
        transaction.partialFill(new BigDecimal("1.00000000"), new BigDecimal("10.10000000"));
        StrategyRunner runner = newRunner(transaction.getRunnerId(), "BINANCE");

        when(repository.findTransactionById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(repository.findById(transaction.getRunnerId())).thenReturn(Optional.of(runner));
        when(repository.findTransactionByClientOrderId(transaction.getClientOrderId())).thenReturn(Optional.of(transaction));
        stubOrderQuery(exchangeRepository, orderQueryPort);
        when(orderQueryPort.queryOrderByClientOrderId(transaction.getSymbol(), transaction.getClientOrderId()))
                .thenReturn(Optional.empty());

        RecoverTransactionStatusUseCase useCase = newUseCase(repository, exchangeRepository);

        RecoverTransactionStatusResponse response = useCase.execute(
                RecoverTransactionStatusRequest.forBoot(transaction.getId()));

        assertEquals(RecoverTransactionStatusResponse.RecoveryOutcome.RECOVERED, response.outcome());
        assertEquals(RecoverTransactionStatusResponse.RecoveryAction.MARKED_CANCELED, response.action());
        assertEquals(TransactionStatus.CANCELED, response.statusAfter());
        assertEquals(TransactionStatus.CANCELED, transaction.getStatus());
    }

    @Test
    void executeRoutesSubmittedTransactionToDlqWhenExchangeDoesNotFindOrderInRuntimeMode() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        ExchangeAdapterRepositoryPort exchangeRepository = mock(ExchangeAdapterRepositoryPort.class);
        DeadLetterEntryRepositoryPort deadLetterRepository = mock(DeadLetterEntryRepositoryPort.class);
        ExchangeOrderQueryPort orderQueryPort = mock(ExchangeOrderQueryPort.class);

        Transaction transaction = newBuyTransaction();
        transaction.submit("EX_ORDER_LOCAL");
        StrategyRunner runner = newRunner(transaction.getRunnerId(), "BINANCE");

        when(repository.findTransactionById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(repository.findById(transaction.getRunnerId())).thenReturn(Optional.of(runner));
        stubOrderQuery(exchangeRepository, orderQueryPort);
        when(orderQueryPort.queryOrderByClientOrderId(transaction.getSymbol(), transaction.getClientOrderId()))
                .thenReturn(Optional.empty());
        when(deadLetterRepository.existsUnresolvedByIdentity(
                runner.getPortfolioId(),
                transaction.getRunnerId(),
                transaction.getClientOrderId(),
                transaction.getExchangeOrderId(),
                DlqReason.RECONCILIATION_CONFLICT
        )).thenReturn(false);

        RecoverTransactionStatusUseCase useCase = new RecoverTransactionStatusUseCase(
                repository,
                exchangeRepository,
                deadLetterRepository,
                newExecutor(repository)
        );

        RecoverTransactionStatusResponse response = useCase.execute(
                RecoverTransactionStatusRequest.forRuntimeWatchdog(transaction.getId()));

        assertEquals(RecoverTransactionStatusResponse.RecoveryOutcome.RECOVERED, response.outcome());
        assertEquals(RecoverTransactionStatusResponse.RecoveryAction.ROUTED_TO_DLQ, response.action());
        assertEquals(TransactionStatus.SUBMITTED, response.statusAfter());
        assertEquals(TransactionStatus.SUBMITTED, transaction.getStatus());
        verify(deadLetterRepository).save(any());
    }

    @Test
    void executeMarksPendingTransactionExpiredWhenExchangeDoesNotFindOrderInRuntimeMode() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        ExchangeAdapterRepositoryPort exchangeRepository = mock(ExchangeAdapterRepositoryPort.class);
        ExchangeOrderQueryPort orderQueryPort = mock(ExchangeOrderQueryPort.class);

        // PENDING (zombie) — nunca submetido, sem exchangeOrderId.
        Transaction transaction = newBuyTransaction();
        StrategyRunner runner = newRunner(transaction.getRunnerId(), "BINANCE");

        when(repository.findTransactionById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(repository.findById(transaction.getRunnerId())).thenReturn(Optional.of(runner));
        when(repository.findTransactionByClientOrderId(transaction.getClientOrderId())).thenReturn(Optional.of(transaction));
        stubOrderQuery(exchangeRepository, orderQueryPort);
        when(orderQueryPort.queryOrderByClientOrderId(transaction.getSymbol(), transaction.getClientOrderId()))
                .thenReturn(Optional.empty());

        RecoverTransactionStatusUseCase useCase = newUseCase(repository, exchangeRepository);

        // forRuntimeWatchdog -> DERIVE_FROM_STATUS: PENDING resolve para terminal fallback (EXPIRED), sem DLQ.
        RecoverTransactionStatusResponse response = useCase.execute(
                RecoverTransactionStatusRequest.forRuntimeWatchdog(transaction.getId()));

        assertEquals(RecoverTransactionStatusResponse.RecoveryOutcome.RECOVERED, response.outcome());
        assertEquals(RecoverTransactionStatusResponse.RecoveryAction.MARKED_EXPIRED, response.action());
        assertEquals(TransactionStatus.EXPIRED, response.statusAfter());
        assertEquals(TransactionStatus.EXPIRED, transaction.getStatus());
    }

    @Test
    void executeRoutesToDlqWhenPendingGetsConfirmedDuringQueryInRuntimeMode() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        ExchangeAdapterRepositoryPort exchangeRepository = mock(ExchangeAdapterRepositoryPort.class);
        DeadLetterEntryRepositoryPort deadLetterRepository = mock(DeadLetterEntryRepositoryPort.class);
        ExchangeOrderQueryPort orderQueryPort = mock(ExchangeOrderQueryPort.class);

        Transaction transaction = newBuyTransaction(); // PENDING, exchangeOrderId == null
        StrategyRunner runner = newRunner(transaction.getRunnerId(), "BINANCE");

        // Twin recarregado: mesma id/clientOrderId, ja promovida a SUBMITTED com o exchangeOrderId
        // real — simula um evento USER_DATA que confirmou a ordem durante a query.
        Transaction promoted = Transaction.reconstitute()
                .id(transaction.getId())
                .runnerId(transaction.getRunnerId())
                .clientOrderId(transaction.getClientOrderId())
                .exchangeOrderId("EX_PROMOTED_DURING_QUERY")
                .status(TransactionStatus.SUBMITTED)
                .type(transaction.getType())
                .orderType(OrderType.LIMIT)
                .symbol(transaction.getSymbol())
                .quantity(transaction.getQuantity())
                .price(transaction.getPrice())
                .total(transaction.getTotal())
                .requestedAt(transaction.getRequestedAt())
                .updatedAt(Instant.now())
                .build();

        // 1a chamada (inicio do execute) PENDING; 2a chamada (re-read antes do fallback) o twin promovido.
        when(repository.findTransactionById(transaction.getId()))
                .thenReturn(Optional.of(transaction))
                .thenReturn(Optional.of(promoted));
        when(repository.findById(transaction.getRunnerId())).thenReturn(Optional.of(runner));
        stubOrderQuery(exchangeRepository, orderQueryPort);
        when(orderQueryPort.queryOrderByClientOrderId(transaction.getSymbol(), transaction.getClientOrderId()))
                .thenReturn(Optional.empty());

        RecoverTransactionStatusUseCase useCase = new RecoverTransactionStatusUseCase(
                repository,
                exchangeRepository,
                deadLetterRepository,
                newExecutor(repository)
        );

        RecoverTransactionStatusResponse response = useCase.execute(
                RecoverTransactionStatusRequest.forRuntimeWatchdog(transaction.getId()));

        // Promovida durante a query -> not-found vira CONFLITO (DLQ), nunca expiracao da ordem confirmada.
        assertEquals(RecoverTransactionStatusResponse.RecoveryOutcome.RECOVERED, response.outcome());
        assertEquals(RecoverTransactionStatusResponse.RecoveryAction.ROUTED_TO_DLQ, response.action());

        // A DLQ deve carregar o exchangeOrderId RECARREGADO (nao o objeto pre-query com id nulo).
        ArgumentCaptor<DeadLetterEntry> captor = ArgumentCaptor.forClass(DeadLetterEntry.class);
        verify(deadLetterRepository).save(captor.capture());
        assertEquals("EX_PROMOTED_DURING_QUERY", captor.getValue().getExchangeOrderId());
    }

    @Test
    void executeReturnsFailureWhenOrderQueryIsUnsupported() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        ExchangeAdapterRepositoryPort exchangeRepository = mock(ExchangeAdapterRepositoryPort.class);
        ExchangeOrderQueryPort orderQueryPort = mock(ExchangeOrderQueryPort.class);

        Transaction transaction = newBuyTransaction();
        transaction.submit("EX_ORDER_LOCAL");
        StrategyRunner runner = newRunner(transaction.getRunnerId(), "BINANCE");

        when(repository.findTransactionById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(repository.findById(transaction.getRunnerId())).thenReturn(Optional.of(runner));
        stubOrderQuery(exchangeRepository, orderQueryPort);
        when(orderQueryPort.queryOrderByClientOrderId(transaction.getSymbol(), transaction.getClientOrderId()))
                .thenThrow(new UnsupportedOperationException("not supported"));

        RecoverTransactionStatusUseCase useCase = newUseCase(repository, exchangeRepository);

        RecoverTransactionStatusResponse response = useCase.execute(
                new RecoverTransactionStatusRequest(transaction.getId()));

        assertEquals(RecoverTransactionStatusResponse.RecoveryOutcome.FAILED, response.outcome());
        assertEquals(RecoverTransactionStatusResponse.FailureReason.ORDER_QUERY_UNSUPPORTED, response.failureReason());
        assertEquals(TransactionStatus.SUBMITTED, response.statusAfter());
        assertEquals(TransactionStatus.SUBMITTED, transaction.getStatus());
    }

    @Test
    void executeReturnsRetryableFailureWhenExchangeQueryFailsTemporarily() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        ExchangeAdapterRepositoryPort exchangeRepository = mock(ExchangeAdapterRepositoryPort.class);
        ExchangeOrderQueryPort orderQueryPort = mock(ExchangeOrderQueryPort.class);

        Transaction transaction = newBuyTransaction();
        transaction.submit("EX_ORDER_LOCAL");
        StrategyRunner runner = newRunner(transaction.getRunnerId(), "BINANCE");

        when(repository.findTransactionById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(repository.findById(transaction.getRunnerId())).thenReturn(Optional.of(runner));
        stubOrderQuery(exchangeRepository, orderQueryPort);
        when(orderQueryPort.queryOrderByClientOrderId(transaction.getSymbol(), transaction.getClientOrderId()))
                .thenThrow(new ExchangeQueryException(
                        "BINANCE",
                        ExchangeQueryException.ErrorType.TEMPORARY,
                        "Temporary upstream timeout"
                ));

        RecoverTransactionStatusUseCase useCase = newUseCase(repository, exchangeRepository);

        RecoverTransactionStatusResponse response = useCase.execute(
                new RecoverTransactionStatusRequest(transaction.getId()));

        assertEquals(RecoverTransactionStatusResponse.RecoveryOutcome.FAILED, response.outcome());
        assertEquals(RecoverTransactionStatusResponse.FailureReason.EXCHANGE_QUERY_RETRYABLE_FAILURE,
                response.failureReason());
        assertEquals(TransactionStatus.SUBMITTED, transaction.getStatus());
    }

    @Test
    void executeTreatsGenericRuntimeQueryFailureAsTerminalEvenWhenMessageLooksTransient() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        ExchangeAdapterRepositoryPort exchangeRepository = mock(ExchangeAdapterRepositoryPort.class);
        ExchangeOrderQueryPort orderQueryPort = mock(ExchangeOrderQueryPort.class);

        Transaction transaction = newBuyTransaction();
        transaction.submit("EX_ORDER_LOCAL");
        StrategyRunner runner = newRunner(transaction.getRunnerId(), "BINANCE");

        when(repository.findTransactionById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(repository.findById(transaction.getRunnerId())).thenReturn(Optional.of(runner));
        stubOrderQuery(exchangeRepository, orderQueryPort);
        when(orderQueryPort.queryOrderByClientOrderId(transaction.getSymbol(), transaction.getClientOrderId()))
                .thenThrow(new RuntimeException("temporary upstream timeout"));

        RecoverTransactionStatusUseCase useCase = newUseCase(repository, exchangeRepository);

        RecoverTransactionStatusResponse response = useCase.execute(
                new RecoverTransactionStatusRequest(transaction.getId()));

        assertEquals(RecoverTransactionStatusResponse.RecoveryOutcome.FAILED, response.outcome());
        assertEquals(RecoverTransactionStatusResponse.FailureReason.EXCHANGE_QUERY_TERMINAL_FAILURE,
                response.failureReason());
        assertEquals(TransactionStatus.SUBMITTED, transaction.getStatus());
    }

    @Test
    void executeReturnsInvalidExchangeResponseWhenClientOrderIdDoesNotMatchRequestedTransaction() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        ExchangeAdapterRepositoryPort exchangeRepository = mock(ExchangeAdapterRepositoryPort.class);
        ExchangeOrderQueryPort orderQueryPort = mock(ExchangeOrderQueryPort.class);

        Transaction transaction = newBuyTransaction();
        transaction.submit("EX_ORDER_LOCAL");
        StrategyRunner runner = newRunner(transaction.getRunnerId(), "BINANCE");

        when(repository.findTransactionById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(repository.findById(transaction.getRunnerId())).thenReturn(Optional.of(runner));
        stubOrderQuery(exchangeRepository, orderQueryPort);
        when(orderQueryPort.queryOrderByClientOrderId(transaction.getSymbol(), transaction.getClientOrderId()))
                .thenReturn(Optional.of(orderData(
                        "another-client-order-id",
                        transaction,
                        OrderDataDto.OrderStatus.FILLED,
                        new BigDecimal("2.50000000"),
                        new BigDecimal("10.20000000"),
                        "EX_ORDER_REMOTE",
                        null
                )));

        RecoverTransactionStatusUseCase useCase = newUseCase(repository, exchangeRepository);

        RecoverTransactionStatusResponse response = useCase.execute(
                new RecoverTransactionStatusRequest(transaction.getId()));

        assertEquals(RecoverTransactionStatusResponse.RecoveryOutcome.FAILED, response.outcome());
        assertEquals(RecoverTransactionStatusResponse.FailureReason.INVALID_EXCHANGE_RESPONSE,
                response.failureReason());
        assertEquals(TransactionStatus.SUBMITTED, transaction.getStatus());
    }

    @Test
    void executeReturnsInvalidExchangeResponseWhenFillPayloadIsIncomplete() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        ExchangeAdapterRepositoryPort exchangeRepository = mock(ExchangeAdapterRepositoryPort.class);
        ExchangeOrderQueryPort orderQueryPort = mock(ExchangeOrderQueryPort.class);

        Transaction transaction = newBuyTransaction();
        transaction.submit("EX_ORDER_LOCAL");
        StrategyRunner runner = newRunner(transaction.getRunnerId(), "BINANCE");

        when(repository.findTransactionById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(repository.findById(transaction.getRunnerId())).thenReturn(Optional.of(runner));
        stubOrderQuery(exchangeRepository, orderQueryPort);
        when(orderQueryPort.queryOrderByClientOrderId(transaction.getSymbol(), transaction.getClientOrderId()))
                .thenReturn(Optional.of(orderData(
                        transaction.getClientOrderId(),
                        transaction,
                        OrderDataDto.OrderStatus.FILLED,
                        null,
                        new BigDecimal("10.20000000"),
                        "EX_ORDER_REMOTE",
                        null
                )));

        RecoverTransactionStatusUseCase useCase = newUseCase(repository, exchangeRepository);

        RecoverTransactionStatusResponse response = useCase.execute(
                new RecoverTransactionStatusRequest(transaction.getId()));

        assertEquals(RecoverTransactionStatusResponse.RecoveryOutcome.FAILED, response.outcome());
        assertEquals(RecoverTransactionStatusResponse.FailureReason.INVALID_EXCHANGE_RESPONSE,
                response.failureReason());
        assertEquals(TransactionStatus.SUBMITTED, transaction.getStatus());
    }

    @Test
    void executeSkipsTerminalTransactions() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        ExchangeAdapterRepositoryPort exchangeRepository = mock(ExchangeAdapterRepositoryPort.class);

        Transaction transaction = newBuyTransaction();
        transaction.submit("EX_ORDER_LOCAL");
        transaction.fill(new BigDecimal("2.50000000"), new BigDecimal("10.20000000"));

        when(repository.findTransactionById(transaction.getId())).thenReturn(Optional.of(transaction));

        RecoverTransactionStatusUseCase useCase = newUseCase(repository, exchangeRepository);

        RecoverTransactionStatusResponse response = useCase.execute(
                new RecoverTransactionStatusRequest(transaction.getId()));

        assertEquals(RecoverTransactionStatusResponse.RecoveryOutcome.SKIPPED, response.outcome());
        assertEquals(RecoverTransactionStatusResponse.FailureReason.TRANSACTION_NOT_ELIGIBLE, response.failureReason());
        assertEquals(TransactionStatus.FILLED, response.statusBefore());
        assertEquals(TransactionStatus.FILLED, response.statusAfter());
        assertNull(response.exchangeId());
    }

    private static void stubOrderQuery(ExchangeAdapterRepositoryPort exchangeRepository,
                                       ExchangeOrderQueryPort orderQueryPort) {
        ExchangeAdapterDescriptor descriptor = mock(ExchangeAdapterDescriptor.class);
        when(descriptor.hasOrderQuery()).thenReturn(true);
        when(descriptor.orderQuery()).thenReturn(orderQueryPort);
        when(exchangeRepository.findAdapter("BINANCE")).thenReturn(Optional.of(descriptor));
    }

    private static RecoverTransactionStatusUseCase newUseCase(StrategyRunnerRepositoryPort repository,
                                                              ExchangeAdapterRepositoryPort exchangeRepository) {
        return new RecoverTransactionStatusUseCase(
                repository,
                exchangeRepository,
                mock(DeadLetterEntryRepositoryPort.class),
                newExecutor(repository)
        );
    }

    private static ConciliationOrderUpdateExecutor newExecutor(StrategyRunnerRepositoryPort repository) {
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, mock(EventPublisherPort.class), List.of(), mock(LatestPricePort.class));
        return new ConciliationOrderUpdateExecutor() {
            @Override
            public void execute(OrderDataDto orderData) {
                conciliation.execute(orderData, this::submitTransaction, this::processFill, this::releaseMargin);
            }

            @Override
            public void submitTransaction(Transaction transaction) {
                conciliation.submitTransaction(transaction);
            }

            @Override
            public void processFill(Transaction transaction, OrderDataDto orderData, boolean isFinal) {
                conciliation.processFill(transaction, orderData, isFinal);
            }

            @Override
            public void releaseMargin(Transaction transaction) {
                conciliation.releaseMargin(transaction);
            }
        };
    }

    private static Transaction newBuyTransaction() {
        BigDecimal quantity = new BigDecimal("2.50000000");
        BigDecimal price = new BigDecimal("10.00000000");
        return new Transaction(
                UUID.randomUUID(),
                ClientOrderId.generate("x1", TransactionType.BUY),
                TransactionType.BUY,
                OrderType.LIMIT,
                "BTCUSDT",
                quantity,
                price,
                quantity.multiply(price),
                new BigDecimal("0.8"),
                "test",
                null
        );
    }

    private static StrategyRunner newRunner(UUID runnerId, String exchangeId) {
        return new StrategyRunner(
                runnerId,
                UUID.randomUUID(),
                "x1",
                UUID.randomUUID(),
                "strategy",
                "BTCUSDT",
                exchangeId,
                Set.of("BINANCE"),
                RunnerStatus.ACTIVE,
                ExecutionPolicy.SINGLE,
                AccountingPolicyType.FIFO,
                new BigDecimal("0.2"),
                1,
                1,
                null,
                PositionProtection.defaults(),
                LiquidationPolicy.defaults(),
                false,
                Instant.now(),
                Instant.now(),
                null,
                null,
                0L
        );
    }

    private static OrderDataDto orderData(Transaction transaction,
                                          OrderDataDto.OrderStatus status,
                                          BigDecimal executedQty,
                                          BigDecimal executedPrice,
                                          String exchangeOrderId) {
        return orderData(
                transaction.getClientOrderId(),
                transaction,
                status,
                executedQty,
                executedPrice,
                exchangeOrderId,
                null
        );
    }

    private static OrderDataDto orderData(String clientOrderId,
                                          Transaction transaction,
                                          OrderDataDto.OrderStatus status,
                                          BigDecimal executedQty,
                                          BigDecimal executedPrice,
                                          String exchangeOrderId,
                                          String rejectReason) {
        return new OrderDataDto(
                exchangeOrderId,
                clientOrderId,
                Symbol.of(transaction.getSymbol()),
                OrderDataDto.OrderSide.BUY,
                OrderDataDto.OrderType.LIMIT,
                transaction.getQuantity(),
                executedQty,
                transaction.getPrice(),
                executedPrice,
                BigDecimal.ZERO,
                status,
                rejectReason,
                Instant.now(),
                null
        );
    }
}
