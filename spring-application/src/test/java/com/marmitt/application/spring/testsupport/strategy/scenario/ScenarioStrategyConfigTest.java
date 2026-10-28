package com.marmitt.application.spring.testsupport.strategy.scenario;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScenarioStrategyConfigTest {

    private static ScenarioStrategyConfig withFillOffset(BigDecimal fillOffset) {
        return new ScenarioStrategyConfig(
                new BigDecimal("0.50"),  // restingOffset
                fillOffset,
                new BigDecimal("0.001"), // quantity
                new BigDecimal("2"),     // overAllocationFactor
                0);                      // maxCycles
    }

    @Test
    void rejectsFillOffsetAtOrAboveOne() {
        // fillOffset >= 1 tornaria priceBelow(preco, fillOffset) zero/negativo no leg SELL do
        // ImmediateRoundTrip, degradando o sinal para HOLD; deve falhar na construcao.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> withFillOffset(new BigDecimal("1")));
        assertEquals("fillOffset must be between 0.0 and 1.0 (exclusive)", ex.getMessage());

        assertThrows(IllegalArgumentException.class, () -> withFillOffset(new BigDecimal("1.5")));
    }

    @Test
    void rejectsFillOffsetAtOrBelowZero() {
        assertThrows(IllegalArgumentException.class, () -> withFillOffset(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> withFillOffset(new BigDecimal("-0.1")));
    }

    @Test
    void acceptsFillOffsetAsFractionBelowOne() {
        assertDoesNotThrow(() -> withFillOffset(new BigDecimal("0.02")));
    }
}
