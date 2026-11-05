package com.marmitt.core.application.usecase.runner.processsignal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LotPeakTrackerTest {

    private final LotPeakTracker tracker = new LotPeakTracker();
    private final UUID runner = UUID.randomUUID();
    private final UUID lot = UUID.randomUUID();

    private static BigDecimal price(String value) {
        return new BigDecimal(value);
    }

    @Test
    void startsAtTheHigherOfEntryAndCurrentPrice() {
        assertEquals(price("100"), tracker.update(runner, lot, price("100"), price("95")));

        UUID other = UUID.randomUUID();
        assertEquals(price("105"), tracker.update(other, lot, price("100"), price("105")));
    }

    @Test
    void keepsTheHighestPriceSeen() {
        tracker.update(runner, lot, price("100"), price("110"));

        assertEquals(price("110"), tracker.update(runner, lot, price("100"), price("105")));
        assertEquals(price("120"), tracker.update(runner, lot, price("100"), price("120")));
    }

    @Test
    void aNewLotStartsOver() {
        tracker.update(runner, lot, price("100"), price("150"));

        assertEquals(price("101"), tracker.update(runner, UUID.randomUUID(), price("100"), price("101")));
    }

    @Test
    void withoutACurrentPriceKeepsWhatIsKnown() {
        assertEquals(price("100"), tracker.update(runner, lot, price("100"), null));
    }
}
