package com.marmitt.core.application.usecase.runner.orderconciliation;

import com.marmitt.core.domain.Symbol;
import com.marmitt.core.domain.runner.ClientOrderId;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.capital.MarginRelease;
import com.marmitt.core.dto.events.MarginReleaseEvent;
import com.marmitt.core.dto.websocket.data.OrderDataDto;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.ReleaseReason;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.enums.TransactionType;
import com.marmitt.core.ports.outbound.events.EventPublisherPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConciliationOrderUpdateIdempotencyTest {

    @Test
    void executeSkipsInvalidClientOrderId() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        EventPublisherPort publisher = mock(EventPublisherPort.class);
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of());

        OrderDataDto orderData = orderData(
                "invalid-client-order-id",
                OrderDataDto.OrderStatus.NEW,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );

        conciliation.execute(orderData, tx -> {}, (tx, od, finalFill) -> {}, tx -> {});

        verify(repository, never()).findTransactionByClientOrderId(orderData.clientOrderId());
    }

    @Test
    void executeNewTransitionsPendingTransactionAndCallsSubmitAction() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        EventPublisherPort publisher = mock(EventPublisherPort.class);
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of());

        Transaction transaction = newBuyTransaction();
        String clientOrderId = transaction.getClientOrderId();
        when(repository.findTransactionByClientOrderId(clientOrderId)).thenReturn(Optional.of(transaction));

        AtomicInteger submitCalls = new AtomicInteger();
        AtomicInteger fillCalls = new AtomicInteger();
        AtomicInteger releaseCalls = new AtomicInteger();

        conciliation.execute(
                orderData(clientOrderId, OrderDataDto.OrderStatus.NEW, BigDecimal.ZERO, BigDecimal.ZERO),
                tx -> submitCalls.incrementAndGet(),
                (tx, od, finalFill) -> fillCalls.incrementAndGet(),
                tx -> releaseCalls.incrementAndGet()
        );

        assertEquals(TransactionStatus.SUBMITTED, transaction.getStatus());
        assertEquals("EX_ORDER_1", transaction.getExchangeOrderId());
        assertEquals(1, submitCalls.get());
        assertEquals(0, fillCalls.get());
        assertEquals(0, releaseCalls.get());
    }

    @Test
    void executeNewIgnoresAlreadySubmittedTransaction() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        EventPublisherPort publisher = mock(EventPublisherPort.class);
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of());

        Transaction transaction = newBuyTransaction();
        transaction.submit("PREVIOUS_EX_ORDER");
        String clientOrderId = transaction.getClientOrderId();
        when(repository.findTransactionByClientOrderId(clientOrderId)).thenReturn(Optional.of(transaction));

        AtomicInteger submitCalls = new AtomicInteger();

        conciliation.execute(
                orderData(clientOrderId, OrderDataDto.OrderStatus.NEW, BigDecimal.ZERO, BigDecimal.ZERO),
                tx -> submitCalls.incrementAndGet(),
                (tx, od, finalFill) -> {
                },
                tx -> {
                }
        );

        assertEquals(TransactionStatus.SUBMITTED, transaction.getStatus());
        assertEquals("PREVIOUS_EX_ORDER", transaction.getExchangeOrderId());
        assertEquals(0, submitCalls.get());
    }

    @Test
    void executeFilledRoutesToFillActionWithFinalFlag() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        EventPublisherPort publisher = mock(EventPublisherPort.class);
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of());

        Transaction transaction = newBuyTransaction();
        String clientOrderId = transaction.getClientOrderId();
        when(repository.findTransactionByClientOrderId(clientOrderId)).thenReturn(Optional.of(transaction));

        AtomicInteger submitCalls = new AtomicInteger();
        AtomicInteger fillCalls = new AtomicInteger();
        AtomicInteger releaseCalls = new AtomicInteger();
        AtomicReference<Boolean> receivedFinalFlag = new AtomicReference<>(null);

        conciliation.execute(
                orderData(clientOrderId, OrderDataDto.OrderStatus.FILLED,
                        new BigDecimal("2.50000000"), new BigDecimal("10.20000000")),
                tx -> submitCalls.incrementAndGet(),
                (tx, od, finalFill) -> {
                    fillCalls.incrementAndGet();
                    assertEquals(transaction, tx);
                    assertEquals(OrderDataDto.OrderStatus.FILLED, od.status());
                    receivedFinalFlag.set(finalFill);
                },
                tx -> releaseCalls.incrementAndGet()
        );

        assertEquals(0, submitCalls.get());
        assertEquals(1, fillCalls.get());
        assertEquals(0, releaseCalls.get());
        assertEquals(Boolean.TRUE, receivedFinalFlag.get());
    }

    @Test
    void executePartiallyFilledRoutesToFillActionWithPartialFlag() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        EventPublisherPort publisher = mock(EventPublisherPort.class);
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of());

        Transaction transaction = newBuyTransaction();
        String clientOrderId = transaction.getClientOrderId();
        when(repository.findTransactionByClientOrderId(clientOrderId)).thenReturn(Optional.of(transaction));

        AtomicInteger fillCalls = new AtomicInteger();
        AtomicReference<Boolean> receivedFinalFlag = new AtomicReference<>(null);

        conciliation.execute(
                orderData(clientOrderId, OrderDataDto.OrderStatus.PARTIALLY_FILLED,
                        new BigDecimal("1.25000000"), new BigDecimal("10.10000000")),
                tx -> {
                },
                (tx, od, finalFill) -> {
                    fillCalls.incrementAndGet();
                    assertEquals(transaction, tx);
                    assertEquals(OrderDataDto.OrderStatus.PARTIALLY_FILLED, od.status());
                    receivedFinalFlag.set(finalFill);
                },
                tx -> {
                }
        );

        assertEquals(1, fillCalls.get());
        assertEquals(Boolean.FALSE, receivedFinalFlag.get());
    }

    @Test
    void executeSkipsWhenTransactionNotFoundForValidClientOrderId() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        EventPublisherPort publisher = mock(EventPublisherPort.class);
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of());

        String validClientOrderId = ClientOrderId.generate("x1", TransactionType.BUY);
        when(repository.findTransactionByClientOrderId(validClientOrderId)).thenReturn(Optional.empty());

        AtomicInteger submitCalls = new AtomicInteger();
        AtomicInteger fillCalls = new AtomicInteger();
        AtomicInteger releaseCalls = new AtomicInteger();

        conciliation.execute(
                orderData(validClientOrderId, OrderDataDto.OrderStatus.NEW, BigDecimal.ZERO, BigDecimal.ZERO),
                tx -> submitCalls.incrementAndGet(),
                (tx, od, finalFill) -> fillCalls.incrementAndGet(),
                tx -> releaseCalls.incrementAndGet()
        );

        verify(repository).findTransactionByClientOrderId(validClientOrderId);
        assertEquals(0, submitCalls.get());
        assertEquals(0, fillCalls.get());
        assertEquals(0, releaseCalls.get());
    }

    @Test
    void processFillIgnoresDuplicatePartiallyFilledWhenQuantityDidNotIncrease() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        EventPublisherPort publisher = mock(EventPublisherPort.class);
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of());

        Transaction transaction = newBuyTransaction();
        transaction.submit("EX_ORDER_1");
        transaction.partialFill(new BigDecimal("1.00000000"), new BigDecimal("10.00000000"));

        conciliation.processFill(
                transaction,
                orderData(
                        transaction.getClientOrderId(),
                        OrderDataDto.OrderStatus.PARTIALLY_FILLED,
                        new BigDecimal("1.00000000"),
                        new BigDecimal("10.50000000")
                ),
                false
        );

        assertEquals(TransactionStatus.PARTIAL, transaction.getStatus());
        assertAmount("1.00000000", transaction.getExecutedQuantity());
        assertAmount("10.00000000", transaction.getExecutedPrice());
        verifyNoInteractions(repository, publisher);
    }

    @Test
    void processFillIgnoresDuplicateFilledWhenAlreadyFinal() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        EventPublisherPort publisher = mock(EventPublisherPort.class);
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of());

        Transaction transaction = newBuyTransaction();
        transaction.submit("EX_ORDER_1");
        transaction.fill(new BigDecimal("2.50000000"), new BigDecimal("10.20000000"));

        conciliation.processFill(
                transaction,
                orderData(
                        transaction.getClientOrderId(),
                        OrderDataDto.OrderStatus.FILLED,
                        new BigDecimal("2.50000000"),
                        new BigDecimal("10.30000000")
                ),
                true
        );

        assertEquals(TransactionStatus.FILLED, transaction.getStatus());
        assertAmount("2.50000000", transaction.getExecutedQuantity());
        assertAmount("10.20000000", transaction.getExecutedPrice());
        verifyNoInteractions(repository, publisher);
    }

    @Test
    void processFillFinalBuyBelowLimitPublishesExcessReleaseOnce() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        EventPublisherPort publisher = mock(EventPublisherPort.class);
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of());
        when(repository.trySavePosition(any())).thenReturn(true);

        // Reserva 2.5 x 10.00 = 25.00; executada a 9.80 -> 24.50; excedente 0.50.
        Transaction transaction = newBuyTransaction();
        transaction.submit("EX_ORDER_1");
        OrderDataDto filled = orderData(transaction.getClientOrderId(), OrderDataDto.OrderStatus.FILLED,
                new BigDecimal("2.50000000"), new BigDecimal("9.80000000"));

        conciliation.processFill(transaction, filled, true);
        conciliation.processFill(transaction, filled, true); // FILLED duplicado

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher, times(1)).publishEvent(captor.capture());
        MarginRelease release = ((MarginReleaseEvent) captor.getValue()).release();
        assertEquals(ReleaseReason.FILLED_EXCESS, release.reason());
        assertAmount("0.50000000", release.releaseAmount());
        assertAmount("24.50000000", release.executedAmount());
    }

    @Test
    void processFillPartialBuyDoesNotReleaseExcess() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        EventPublisherPort publisher = mock(EventPublisherPort.class);
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of());
        when(repository.trySavePosition(any())).thenReturn(true);

        Transaction transaction = newBuyTransaction();
        transaction.submit("EX_ORDER_1");

        conciliation.processFill(transaction,
                orderData(transaction.getClientOrderId(), OrderDataDto.OrderStatus.PARTIALLY_FILLED,
                        new BigDecimal("1.00000000"), new BigDecimal("9.80000000")),
                false);

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void executeIgnoresCanceledForAlreadyFilledTransaction() {
        // T67: terminal nunca muda; um encerramento atrasado ou repetido nao gera erro nem nova liberacao.
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        EventPublisherPort publisher = mock(EventPublisherPort.class);
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of());

        Transaction transaction = newBuyTransaction();
        transaction.submit("EX_ORDER_1");
        transaction.fill(new BigDecimal("2.50000000"), new BigDecimal("10.20000000"));
        when(repository.findTransactionByClientOrderId(transaction.getClientOrderId()))
                .thenReturn(Optional.of(transaction));

        AtomicInteger releaseCalls = new AtomicInteger();
        conciliation.execute(
                orderData(transaction.getClientOrderId(), OrderDataDto.OrderStatus.CANCELED,
                        transaction.getExecutedQuantity(), transaction.getExecutedPrice()),
                tx -> {
                },
                (tx, od, finalFill) -> {
                },
                tx -> releaseCalls.incrementAndGet()
        );

        assertEquals(TransactionStatus.FILLED, transaction.getStatus());
        assertEquals(0, releaseCalls.get());
    }

    @Test
    void executeCanceledCallsReleaseActionAndTransitionsStatus() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        EventPublisherPort publisher = mock(EventPublisherPort.class);
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of());

        Transaction transaction = newBuyTransaction();
        when(repository.findTransactionByClientOrderId(transaction.getClientOrderId()))
                .thenReturn(Optional.of(transaction));

        AtomicInteger releaseCalls = new AtomicInteger();
        conciliation.execute(
                orderData(transaction.getClientOrderId(), OrderDataDto.OrderStatus.CANCELED,
                        BigDecimal.ZERO, BigDecimal.ZERO),
                tx -> {
                },
                (tx, od, finalFill) -> {
                },
                tx -> releaseCalls.incrementAndGet()
        );

        assertEquals(TransactionStatus.CANCELED, transaction.getStatus());
        assertEquals(1, releaseCalls.get());
    }

    @Test
    void executeExpiredCallsReleaseActionAndTransitionsStatus() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        EventPublisherPort publisher = mock(EventPublisherPort.class);
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of());

        Transaction transaction = newBuyTransaction();
        when(repository.findTransactionByClientOrderId(transaction.getClientOrderId()))
                .thenReturn(Optional.of(transaction));

        AtomicInteger releaseCalls = new AtomicInteger();
        conciliation.execute(
                orderData(transaction.getClientOrderId(), OrderDataDto.OrderStatus.EXPIRED,
                        BigDecimal.ZERO, BigDecimal.ZERO),
                tx -> {
                },
                (tx, od, finalFill) -> {
                },
                tx -> releaseCalls.incrementAndGet()
        );

        assertEquals(TransactionStatus.EXPIRED, transaction.getStatus());
        assertEquals(1, releaseCalls.get());
    }

    @Test
    void executeRejectedCallsReleaseActionAndTransitionsStatus() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        EventPublisherPort publisher = mock(EventPublisherPort.class);
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of());

        Transaction transaction = newBuyTransaction();
        when(repository.findTransactionByClientOrderId(transaction.getClientOrderId()))
                .thenReturn(Optional.of(transaction));

        AtomicInteger releaseCalls = new AtomicInteger();
        conciliation.execute(
                orderData(transaction.getClientOrderId(), OrderDataDto.OrderStatus.REJECTED,
                        BigDecimal.ZERO, BigDecimal.ZERO, "INSUFFICIENT_BALANCE"),
                tx -> {
                },
                (tx, od, finalFill) -> {
                },
                tx -> releaseCalls.incrementAndGet()
        );

        assertEquals(TransactionStatus.REJECTED, transaction.getStatus());
        assertEquals("INSUFFICIENT_BALANCE", transaction.getRejectReason());
        assertEquals(1, releaseCalls.get());
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

    private static OrderDataDto orderData(String clientOrderId,
                                          OrderDataDto.OrderStatus status,
                                          BigDecimal executedQty,
                                          BigDecimal executedPrice) {
        return orderData(clientOrderId, status, executedQty, executedPrice, null);
    }

    private static OrderDataDto orderData(String clientOrderId,
                                          OrderDataDto.OrderStatus status,
                                          BigDecimal executedQty,
                                          BigDecimal executedPrice,
                                          String rejectReason) {
        return new OrderDataDto(
                "EX_ORDER_1",
                clientOrderId,
                Symbol.of("BTCUSDT"),
                OrderDataDto.OrderSide.BUY,
                OrderDataDto.OrderType.LIMIT,
                new BigDecimal("2.50000000"),
                executedQty,
                new BigDecimal("10.00000000"),
                executedPrice,
                BigDecimal.ZERO,
                status,
                rejectReason,
                Instant.now(),
                null
        );
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
