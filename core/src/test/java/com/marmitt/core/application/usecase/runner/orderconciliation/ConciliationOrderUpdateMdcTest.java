package com.marmitt.core.application.usecase.runner.orderconciliation;

import com.marmitt.core.domain.Symbol;
import com.marmitt.core.domain.runner.ClientOrderId;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.websocket.data.OrderDataDto;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.TransactionType;
import com.marmitt.core.ports.outbound.events.EventPublisherPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import com.marmitt.core.ports.outbound.marketdata.LatestPricePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T26: a conciliacao deve ancorar o {@code transactionId} no MDC enquanto roteia o evento,
 * restaurando o valor anterior ao final (seguro contra aninhamento, ex.: watchdog -> recover).
 */
class ConciliationOrderUpdateMdcTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void putsTransactionIdInMdcDuringRoutingAndClearsAfter() {
        StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
        EventPublisherPort publisher = mock(EventPublisherPort.class);
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of(), mock(LatestPricePort.class));

        Transaction transaction = newBuyTransaction();
        when(repository.findTransactionByClientOrderId(transaction.getClientOrderId()))
                .thenReturn(Optional.of(transaction));

        AtomicReference<String> mdcDuringAction = new AtomicReference<>();
        conciliation.execute(
                orderData(transaction.getClientOrderId(), OrderDataDto.OrderStatus.NEW),
                tx -> mdcDuringAction.set(MDC.get("transactionId")),
                (tx, od, finalFill) -> { },
                tx -> { }
        );

        assertEquals(transaction.getId().toString(), mdcDuringAction.get(),
                "transactionId deve estar no MDC durante o roteamento da conciliacao");
        assertNull(MDC.get("transactionId"), "MDC deve ser limpo quando nao havia valor anterior");
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

    private static OrderDataDto orderData(String clientOrderId, OrderDataDto.OrderStatus status) {
        return new OrderDataDto(
                "EX_ORDER_1",
                clientOrderId,
                Symbol.of("BTCUSDT"),
                OrderDataDto.OrderSide.BUY,
                OrderDataDto.OrderType.LIMIT,
                new BigDecimal("2.50000000"),
                BigDecimal.ZERO,
                new BigDecimal("10.00000000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                status,
                null,
                Instant.now(),
                null
        );
    }
}
