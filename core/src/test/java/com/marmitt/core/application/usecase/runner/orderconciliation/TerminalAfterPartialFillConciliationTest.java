package com.marmitt.core.application.usecase.runner.orderconciliation;

import com.marmitt.core.domain.Symbol;
import com.marmitt.core.domain.runner.ClientOrderId;
import com.marmitt.core.domain.runner.Position;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.capital.ExecutionConfirmation;
import com.marmitt.core.dto.events.ExecutionConfirmedEvent;
import com.marmitt.core.dto.events.MarginReleaseEvent;
import com.marmitt.core.dto.websocket.data.OrderDataDto;
import com.marmitt.core.enums.OrderSide;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.PositionStatus;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.enums.TransactionType;
import com.marmitt.core.ports.outbound.events.EventPublisherPort;
import com.marmitt.core.ports.outbound.exchange.rest.OrderQuantityNormalizerPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T67: encerramento da exchange depois de execucao parcial, pelo caminho real da conciliacao
 * ({@link ConciliationOrderUpdate#execute(OrderDataDto)}).
 *
 * <p>Cenario da rodada 8 da T53: SELL a mercado de 0.00154 BTC preenche 0.00107 e a Binance expira o
 * resto por falta de liquidez.
 */
class TerminalAfterPartialFillConciliationTest {

    private final StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
    private final EventPublisherPort publisher = mock(EventPublisherPort.class);
    private final UUID runnerId = UUID.randomUUID();

    @Test
    void sellExpiredAfterPartialFillIsCanceledAndUnlocksTheRestOfTheLot() {
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of());
        Transaction sell = submitted(TransactionType.SELL, "0.00154000", "69810.17000000");
        Position lot = lockedLot(sell, "0.00154000", "119.23884280");
        conciliation.processFill(sell, event(sell, OrderDataDto.OrderSide.SELL,
                OrderDataDto.OrderStatus.PARTIALLY_FILLED, "0.00107000", "63998.18700935"), false);

        conciliation.execute(event(sell, OrderDataDto.OrderSide.SELL,
                OrderDataDto.OrderStatus.EXPIRED, "0.00107000", "63998.18700935"));

        assertEquals(TransactionStatus.CANCELED, sell.getStatus());
        assertAmount("0.00107000", sell.getExecutedQuantity());
        assertEquals(PositionStatus.OPEN, lot.getStatus());
        assertFalse(lot.isLocked());
        assertAmount("0.00047000", lot.getQuantity());
        verify(publisher, never()).publishEvent(any(MarginReleaseEvent.class));
    }

    @Test
    void sellCanceledAfterPartialFillWritesOffAnUnsellableRest() {
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher,
                List.of(new StepSizeNormalizer("BINANCE", new BigDecimal("0.001"))));
        Transaction sell = submitted(TransactionType.SELL, "0.00154000", "69810.17000000");
        Position lot = lockedLot(sell, "0.00154000", "119.23884280");
        conciliation.processFill(sell, event(sell, OrderDataDto.OrderSide.SELL,
                OrderDataDto.OrderStatus.PARTIALLY_FILLED, "0.00107000", "63998.18700935"), false);

        conciliation.execute(event(sell, OrderDataDto.OrderSide.SELL,
                OrderDataDto.OrderStatus.EXPIRED, "0.00107000", "63998.18700935"));

        assertEquals(TransactionStatus.CANCELED, sell.getStatus());
        assertEquals(PositionStatus.CLOSED, lot.getStatus());
        ExecutionConfirmation writeOff = lastConfirmation();
        assertAmount("0.00047000", writeOff.executedQuantity());
        assertAmount(writeOff.totalCost().negate().toPlainString(), writeOff.pnlRealized());
        assertTrue(writeOff.totalCost().signum() > 0);
    }

    @Test
    void buyExpiredAfterPartialFillReleasesOnlyTheUnexecutedReservation() {
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of());
        Transaction buy = submitted(TransactionType.BUY, "2.50000000", "10.00000000");
        buy.partialFill(new BigDecimal("1.00000000"), new BigDecimal("10.00000000"));
        when(repository.findTransactionByClientOrderId(buy.getClientOrderId())).thenReturn(Optional.of(buy));

        conciliation.execute(event(buy, OrderDataDto.OrderSide.BUY,
                OrderDataDto.OrderStatus.EXPIRED, "1.00000000", "10.00000000"));

        assertEquals(TransactionStatus.CANCELED, buy.getStatus());
        ArgumentCaptor<MarginReleaseEvent> captor = ArgumentCaptor.forClass(MarginReleaseEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertAmount("15.00000000", captor.getValue().release().releaseAmount());
    }

    @Test
    void executionReportedOnlyWithTheTerminalEventIsAppliedBeforeClosing() {
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of());
        Transaction sell = submitted(TransactionType.SELL, "0.00154000", "69810.17000000");
        Position lot = lockedLot(sell, "0.00154000", "119.23884280");

        conciliation.execute(event(sell, OrderDataDto.OrderSide.SELL,
                OrderDataDto.OrderStatus.CANCELED, "0.00107000", "63998.18700935"));

        assertEquals(TransactionStatus.CANCELED, sell.getStatus());
        assertAmount("0.00107000", sell.getExecutedQuantity());
        assertAmount("0.00107000", lastConfirmation().executedQuantity());
        assertAmount("0.00047000", lot.getQuantity());
        assertEquals(PositionStatus.OPEN, lot.getStatus());
    }

    @Test
    void expiredWithoutExecutionStaysExpired() {
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of());
        Transaction buy = submitted(TransactionType.BUY, "2.50000000", "10.00000000");
        when(repository.findTransactionByClientOrderId(buy.getClientOrderId())).thenReturn(Optional.of(buy));

        conciliation.execute(event(buy, OrderDataDto.OrderSide.BUY,
                OrderDataDto.OrderStatus.EXPIRED, "0", "0"));

        assertEquals(TransactionStatus.EXPIRED, buy.getStatus());
        ArgumentCaptor<MarginReleaseEvent> captor = ArgumentCaptor.forClass(MarginReleaseEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertAmount("25.00000000", captor.getValue().release().releaseAmount());
    }

    @Test
    void repeatedTerminalEventIsIgnored() {
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of());
        Transaction sell = submitted(TransactionType.SELL, "0.00259000", "69810.17000000");
        sell.expire();
        when(repository.findTransactionByClientOrderId(sell.getClientOrderId())).thenReturn(Optional.of(sell));

        conciliation.execute(event(sell, OrderDataDto.OrderSide.SELL,
                OrderDataDto.OrderStatus.EXPIRED, "0", "0"));

        assertEquals(TransactionStatus.EXPIRED, sell.getStatus());
        verify(repository, never()).saveTransaction(any());
        verify(publisher, never()).publishEvent(any());
    }

    private Position lockedLot(Transaction sell, String quantity, String cost) {
        Position lot = Position.open(runnerId, "BTCUSDT", new BigDecimal(quantity), new BigDecimal(cost));
        lot.associateBuyTransaction(UUID.randomUUID());
        lot.lock(sell.getId(), sell.getQuantity());
        when(repository.findTransactionByClientOrderId(sell.getClientOrderId())).thenReturn(Optional.of(sell));
        when(repository.findPositionLockedByTransactionIdForUpdate(sell.getId())).thenReturn(Optional.of(lot));
        when(repository.findActivePositionByRunnerIdAndSymbol(runnerId, "BTCUSDT")).thenReturn(Optional.of(lot));
        StrategyRunner runner = mock(StrategyRunner.class);
        when(runner.getExchangeId()).thenReturn("BINANCE");
        when(repository.findById(runnerId)).thenReturn(Optional.of(runner));
        return lot;
    }

    private ExecutionConfirmation lastConfirmation() {
        ArgumentCaptor<ExecutionConfirmedEvent> captor = ArgumentCaptor.forClass(ExecutionConfirmedEvent.class);
        verify(publisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
        List<ExecutionConfirmedEvent> events = captor.getAllValues();
        return events.get(events.size() - 1).confirmation();
    }

    private Transaction submitted(TransactionType type, String quantity, String price) {
        BigDecimal qty = new BigDecimal(quantity);
        BigDecimal px = new BigDecimal(price);
        Transaction tx = new Transaction(runnerId, ClientOrderId.generate("x1", type), type, OrderType.MARKET,
                "BTCUSDT", qty, px, qty.multiply(px).setScale(8, RoundingMode.HALF_UP), new BigDecimal("1.0"),
                "t67", null);
        tx.submit("EX_ORDER_1");
        return tx;
    }

    private static OrderDataDto event(Transaction tx, OrderDataDto.OrderSide side, OrderDataDto.OrderStatus status,
                                      String executedQty, String executedPrice) {
        return new OrderDataDto("EX_ORDER_1", tx.getClientOrderId(), Symbol.of("BTCUSDT"), side,
                OrderDataDto.OrderType.MARKET, tx.getQuantity(), new BigDecimal(executedQty), tx.getPrice(),
                new BigDecimal(executedPrice), BigDecimal.ZERO, status, null, Instant.now(), null);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    /** Normalizador com floor ao stepSize, como o da Binance. */
    private record StepSizeNormalizer(String exchangeName, BigDecimal stepSize) implements OrderQuantityNormalizerPort {
        @Override
        public String getExchangeName() {
            return exchangeName;
        }

        @Override
        public BigDecimal normalizeQuantity(String symbol, BigDecimal quantity) {
            return quantity.divide(stepSize, 0, RoundingMode.FLOOR).multiply(stepSize);
        }

        @Override
        public BigDecimal normalizePrice(String symbol, OrderSide side, BigDecimal price) {
            return price;
        }
    }
}
