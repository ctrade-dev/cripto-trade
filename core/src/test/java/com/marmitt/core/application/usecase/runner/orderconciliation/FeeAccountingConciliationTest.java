package com.marmitt.core.application.usecase.runner.orderconciliation;

import com.marmitt.core.domain.Symbol;
import com.marmitt.core.domain.runner.ClientOrderId;
import com.marmitt.core.domain.runner.Position;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.domain.runner.TransactionMatch;
import com.marmitt.core.dto.capital.ExecutionConfirmation;
import com.marmitt.core.dto.events.ExecutionConfirmedEvent;
import com.marmitt.core.dto.websocket.data.OrderDataDto;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.OrderSide;
import com.marmitt.core.enums.PositionStatus;
import com.marmitt.core.enums.TransactionType;
import com.marmitt.core.ports.outbound.events.EventPublisherPort;
import com.marmitt.core.ports.outbound.exchange.rest.OrderQuantityNormalizerPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import com.marmitt.core.ports.outbound.marketdata.LatestPricePort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T46: contabilizacao da fee por ativo e baixa do residuo invendavel, pelo caminho real da
 * conciliacao ({@link ConciliationOrderUpdate#processFill}).
 */
class FeeAccountingConciliationTest {

    private final StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
    private final EventPublisherPort publisher = mock(EventPublisherPort.class);
    private final UUID runnerId = UUID.randomUUID();

    @Test
    void buyFeeInBaseAssetOpensLotWithNetQuantityAtPaidCost() {
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of(), mock(LatestPricePort.class));
        when(repository.trySavePosition(any())).thenReturn(true);
        Transaction buy = submitted(TransactionType.BUY, "0.00100000", "66300.00000000");

        conciliation.processFill(buy, fill(buy, OrderDataDto.OrderSide.BUY, "0.00100000", "66233.70000000",
                "0.00000100", "BTC"), true);

        Position opened = capturedNewPosition();
        assertAmount("0.000999", opened.getQuantity());
        assertAmount("66.23370000", opened.getCostBasis());
        assertAmount("0.00100000", buy.getExecutedQuantity());
    }

    @Test
    void buyFeeInThirdAssetIsNotAccountedAndKeepsExecutedQuantity() {
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of(), mock(LatestPricePort.class));
        when(repository.trySavePosition(any())).thenReturn(true);
        Transaction buy = submitted(TransactionType.BUY, "0.00100000", "66300.00000000");

        conciliation.processFill(buy, fill(buy, OrderDataDto.OrderSide.BUY, "0.00100000", "66233.70000000",
                "0.00010000", "BNB"), true);

        assertAmount("0.001", capturedNewPosition().getQuantity());
    }

    @Test
    void finalSellLeavingUnsellableRemainderWritesItOffAndReleasesItsCost() {
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher,
                List.of(new StepSizeNormalizer("BINANCE", new BigDecimal("0.00001"))), mock(LatestPricePort.class));
        Transaction sell = submitted(TransactionType.SELL, "0.00099000", "66400.00000000");
        Position lot = Position.open(runnerId, "BTCUSDT", new BigDecimal("0.000999"), new BigDecimal("66.23370000"));
        lot.associateBuyTransaction(UUID.randomUUID());
        lot.lock(sell.getId(), new BigDecimal("0.00099"));
        when(repository.findPositionLockedByTransactionIdForUpdate(sell.getId())).thenReturn(Optional.of(lot));
        StrategyRunner runner = runnerOn("BINANCE");
        when(repository.findById(runnerId)).thenReturn(Optional.of(runner));

        conciliation.processFill(sell, fill(sell, OrderDataDto.OrderSide.SELL, "0.00099000", "66400.00000000",
                "0.06573600", "USDT"), true);

        List<ExecutionConfirmation> confirmations = publishedConfirmations(2);
        assertAmount("65.63700000", confirmations.get(0).totalCost());
        ExecutionConfirmation writeOff = confirmations.get(1);
        assertAmount("0.59670000", writeOff.totalCost());
        assertAmount("-0.59670000", writeOff.pnlRealized());
        assertAmount("66.23370000", confirmations.get(0).totalCost().add(writeOff.totalCost()));
        assertEquals(UUID.nameUUIDFromBytes(("dust-write-off:" + lot.getId()).getBytes(StandardCharsets.UTF_8)),
                writeOff.matchId());
        assertEquals(PositionStatus.CLOSED, lot.getStatus());
    }

    @Test
    void finalSellLeavingSellableRemainderKeepsLotOpen() {
        // Sem normalizador (ex.: MOCK) qualquer quantidade e vendavel: nada e baixado.
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of(), mock(LatestPricePort.class));
        Transaction sell = submitted(TransactionType.SELL, "0.00099000", "66400.00000000");
        Position lot = Position.open(runnerId, "BTCUSDT", new BigDecimal("0.000999"), new BigDecimal("66.23370000"));
        lot.associateBuyTransaction(UUID.randomUUID());
        lot.lock(sell.getId(), new BigDecimal("0.00099"));
        when(repository.findPositionLockedByTransactionIdForUpdate(sell.getId())).thenReturn(Optional.of(lot));
        StrategyRunner runner = runnerOn("MOCK");
        when(repository.findById(runnerId)).thenReturn(Optional.of(runner));

        conciliation.processFill(sell, fill(sell, OrderDataDto.OrderSide.SELL, "0.00099000", "66400.00000000",
                "0", null), true);

        publishedConfirmations(1);
        assertEquals(PositionStatus.OPEN, lot.getStatus());
        assertAmount("0.000009", lot.getQuantity());
    }

    @Test
    void sellFeeInThirdAssetIsRecordedPendingConversionAndKeptOutOfPnl() {
        ConciliationOrderUpdate conciliation = new ConciliationOrderUpdate(repository, publisher, List.of(), mock(LatestPricePort.class));
        Transaction sell = submitted(TransactionType.SELL, "0.00100000", "66400.00000000");
        Position lot = Position.open(runnerId, "BTCUSDT", new BigDecimal("0.001"), new BigDecimal("66.30000000"));
        lot.associateBuyTransaction(UUID.randomUUID());
        lot.lock(sell.getId(), new BigDecimal("0.001"));
        when(repository.findPositionLockedByTransactionIdForUpdate(sell.getId())).thenReturn(Optional.of(lot));

        conciliation.processFill(sell, fill(sell, OrderDataDto.OrderSide.SELL, "0.00100000", "66400.00000000",
                "0.00020000", "BNB"), true);

        ArgumentCaptor<TransactionMatch> match = ArgumentCaptor.forClass(TransactionMatch.class);
        verify(repository).saveAtomicTransactionAndMatch(any(), match.capture());
        assertTrue(match.getValue().fee().isPendingConversion());
        assertEquals("BNB", match.getValue().fee().asset());
        // PnL bruto (66.40 - 66.30), sem descontar a fee em BNB.
        assertAmount("0.10000000", match.getValue().pnlRealized());
    }

    private Position capturedNewPosition() {
        ArgumentCaptor<Position> captor = ArgumentCaptor.forClass(Position.class);
        verify(repository).trySavePosition(captor.capture());
        return captor.getValue();
    }

    private List<ExecutionConfirmation> publishedConfirmations(int expected) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher, times(expected)).publishEvent(captor.capture());
        return captor.getAllValues().stream()
                .map(event -> ((ExecutionConfirmedEvent) event).confirmation())
                .toList();
    }

    private Transaction submitted(TransactionType type, String quantity, String price) {
        BigDecimal qty = new BigDecimal(quantity);
        BigDecimal px = new BigDecimal(price);
        Transaction tx = new Transaction(runnerId, ClientOrderId.generate("x1", type), type, OrderType.LIMIT, "BTCUSDT",
                qty, px, qty.multiply(px).setScale(8, RoundingMode.HALF_UP), new BigDecimal("0.8"), "t46", null);
        tx.submit("EX_ORDER_1");
        return tx;
    }

    private static OrderDataDto fill(Transaction tx, OrderDataDto.OrderSide side, String executedQty,
                                     String executedPrice, String fee, String feeAsset) {
        return new OrderDataDto("EX_ORDER_1", tx.getClientOrderId(), Symbol.of("BTCUSDT"), side,
                OrderDataDto.OrderType.LIMIT, tx.getQuantity(), new BigDecimal(executedQty), tx.getPrice(),
                new BigDecimal(executedPrice), new BigDecimal(fee), OrderDataDto.OrderStatus.FILLED, null,
                Instant.now(), feeAsset);
    }

    private static StrategyRunner runnerOn(String exchangeId) {
        StrategyRunner runner = mock(StrategyRunner.class);
        when(runner.getExchangeId()).thenReturn(exchangeId);
        return runner;
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
        public BigDecimal minNotional(String symbol) {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal normalizePrice(String symbol, OrderSide side, BigDecimal price) {
            return price;
        }
    }
}
