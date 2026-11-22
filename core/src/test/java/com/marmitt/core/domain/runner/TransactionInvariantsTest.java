package com.marmitt.core.domain.runner;

import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.enums.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionInvariantsTest {

    @Test
    void submitTransitionsFromPendingAndRejectsDuplicateSubmit() {
        Transaction transaction = newTransaction();

        transaction.submit("EX_ORDER_1");

        assertEquals(TransactionStatus.SUBMITTED, transaction.getStatus());
        assertEquals("EX_ORDER_1", transaction.getExchangeOrderId());
        assertThrows(IllegalStateException.class, () -> transaction.submit("EX_ORDER_2"));
    }

    @Test
    void fillTransitionsToFinalAndBlocksFurtherNonTerminalTransitions() {
        Transaction transaction = newTransaction();
        transaction.submit("EX_ORDER_1");

        transaction.fill(new BigDecimal("2.5"), new BigDecimal("10"));

        assertEquals(TransactionStatus.FILLED, transaction.getStatus());
        assertNotNull(transaction.getExecutedAt());
        assertThrows(IllegalStateException.class, () ->
                transaction.partialFill(new BigDecimal("2.5"), new BigDecimal("10")));
        assertThrows(IllegalStateException.class, transaction::cancel);
    }

    @Test
    void expireRejectsInvalidTransitionFromPartial() {
        Transaction transaction = newTransaction();
        transaction.submit("EX_ORDER_1");
        transaction.partialFill(new BigDecimal("1.0"), new BigDecimal("9.5"));

        assertThrows(IllegalStateException.class, transaction::expire);
    }

    @Test
    void executedValueIsZeroBeforeExecution() {
        Transaction transaction = newTransaction();

        assertAmount("0", transaction.getExecutedValue());
    }

    @Test
    void executedValueUsesAccumulatedExecution() {
        Transaction transaction = newTransaction();
        transaction.submit("EX_ORDER_1");
        transaction.partialFill(new BigDecimal("2.0"), new BigDecimal("11.5"));

        assertAmount("23.00000000", transaction.getExecutedValue());
    }

    @Test
    void executedValueUsesLatestCumulativePartialFillAcrossMultipleUpdates() {
        Transaction transaction = newTransaction();
        transaction.submit("EX_ORDER_1");

        transaction.partialFill(new BigDecimal("1.0"), new BigDecimal("10.0"));
        assertAmount("10.00000000", transaction.getExecutedValue());

        transaction.partialFill(new BigDecimal("2.5"), new BigDecimal("10.8"));
        assertAmount("27.00000000", transaction.getExecutedValue());
    }

    @Test
    void strategyMetadataIsAnImmutableCopyAndNullMeansEmpty() {
        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("exitKind", "trailing");
        metadata.put("stop", null);
        Transaction transaction = new Transaction(UUID.randomUUID(), "client-1", TransactionType.SELL, OrderType.LIMIT,
                "BTCUSDT", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, null, "t74", null, metadata);
        metadata.put("exitKind", "changed");

        assertEquals("trailing", transaction.getStrategyMetadata().get("exitKind"));
        assertEquals(true, transaction.getStrategyMetadata().containsKey("stop"));
        assertThrows(UnsupportedOperationException.class, () -> transaction.getStrategyMetadata().put("x", 1));
        assertEquals(Map.of(), new Transaction(UUID.randomUUID(), "client-2", TransactionType.BUY, OrderType.LIMIT,
                "BTCUSDT", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, null, null, null, null)
                .getStrategyMetadata());
    }

    private static Transaction newTransaction() {
        BigDecimal quantity = new BigDecimal("2.5");
        BigDecimal price = new BigDecimal("10");
        BigDecimal total = quantity.multiply(price);
        return new Transaction(
                UUID.randomUUID(),
                "client-order-id-1",
                TransactionType.BUY,
                OrderType.LIMIT,
                "BTCUSDT",
                quantity,
                price,
                total,
                new BigDecimal("0.8"),
                "test",
                null, Map.of()
        );
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
