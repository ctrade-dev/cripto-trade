package com.marmitt.core.domain.portfolio;

import com.marmitt.core.enums.CapitalPoolingMode;
import com.marmitt.core.enums.SafeModeOrigin;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.enums.SafeModeTrigger;
import com.marmitt.core.exceptions.SafeModeTransitionException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.marmitt.core.enums.SafeModeOrigin.AUTOMATIC;
import static com.marmitt.core.enums.SafeModeOrigin.MANUAL;
import static com.marmitt.core.enums.SafeModeStatus.CANCEL_ALL;
import static com.marmitt.core.enums.SafeModeStatus.HALT;
import static com.marmitt.core.enums.SafeModeStatus.NORMAL;
import static com.marmitt.core.enums.SafeModeStatus.PANIC_SELL;
import static com.marmitt.core.enums.SafeModeTrigger.DLQ_THRESHOLD;
import static com.marmitt.core.enums.SafeModeTrigger.EXCHANGE_UNAVAILABLE;
import static com.marmitt.core.enums.SafeModeTrigger.WATCHDOG_STUCK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T45: regras de transicao do Safe Mode (Blueprint 11.1.B.1 e 11.1.C).
 */
class PortfolioSafeModeTest {

    @Test
    void raisingRecordsAuditAndUpdatesState() {
        Portfolio portfolio = at(NORMAL);

        SafeModeChange change = portfolio.changeSafeMode(HALT, MANUAL, null, "drawdown manual", false).orElseThrow();

        assertEquals(NORMAL, change.from());
        assertEquals(HALT, change.to());
        assertEquals(MANUAL, change.origin());
        assertEquals(HALT, portfolio.getSafeModeStatus());
        assertEquals("drawdown manual", portfolio.getSafeModeReason());
        assertEquals(change.changedAt(), portfolio.getSafeModeChangedAt());
    }

    @Test
    void sameLevelIsIdempotent() {
        Portfolio portfolio = at(CANCEL_ALL);

        assertTrue(portfolio.changeSafeMode(CANCEL_ALL, MANUAL, null, "repeat", true).isEmpty());
        assertEquals(CANCEL_ALL, portfolio.getSafeModeStatus());
    }

    @Test
    void massActionLevelsRequireConfirmation() {
        assertThrows(SafeModeTransitionException.class,
                () -> at(NORMAL).changeSafeMode(CANCEL_ALL, MANUAL, null, "no confirm", false));
        assertThrows(SafeModeTransitionException.class,
                () -> at(HALT).changeSafeMode(PANIC_SELL, MANUAL, null, "no confirm", false));
        assertTrue(at(NORMAL).changeSafeMode(PANIC_SELL, MANUAL, null, "confirmed jump", true).isPresent());
    }

    @Test
    void loweringMustGoOneLevelAtATime() {
        assertThrows(SafeModeTransitionException.class,
                () -> at(PANIC_SELL).changeSafeMode(HALT, MANUAL, null, "skip", false));
        assertThrows(SafeModeTransitionException.class,
                () -> at(CANCEL_ALL).changeSafeMode(NORMAL, MANUAL, null, "skip", false));

        Portfolio portfolio = at(PANIC_SELL);
        portfolio.changeSafeMode(CANCEL_ALL, MANUAL, null, "step", false).orElseThrow();
        portfolio.changeSafeMode(HALT, MANUAL, null, "step", false).orElseThrow();
        portfolio.changeSafeMode(NORMAL, MANUAL, null, "step", false).orElseThrow();
        assertEquals(NORMAL, portfolio.getSafeModeStatus());
    }

    @Test
    void automaticTriggerOnlyRaisesToHalt() {
        assertThrows(SafeModeTransitionException.class,
                () -> at(NORMAL).changeSafeMode(CANCEL_ALL, AUTOMATIC, DLQ_THRESHOLD, "auto", true));
        assertThrows(SafeModeTransitionException.class,
                () -> at(HALT).changeSafeMode(NORMAL, AUTOMATIC, DLQ_THRESHOLD, "auto resume", false));

        Portfolio portfolio = at(NORMAL);
        assertTrue(portfolio.changeSafeMode(HALT, AUTOMATIC, DLQ_THRESHOLD, "daily loss", false).isPresent());
        assertEquals(AUTOMATIC, portfolio.getSafeModeOrigin());
        assertEquals(DLQ_THRESHOLD, portfolio.getSafeModeTrigger());
    }

    @Test
    void triggerIsRequiredForAutomaticAndAbsentForManual() {
        assertThrows(IllegalArgumentException.class,
                () -> at(NORMAL).changeSafeMode(HALT, AUTOMATIC, null, "auto", false));
        assertThrows(IllegalArgumentException.class,
                () -> at(NORMAL).changeSafeMode(HALT, MANUAL, DLQ_THRESHOLD, "manual", false));
    }

    @Test
    void automaticHaltByAutoResumableTriggerResumesAutomatically() {
        Portfolio portfolio = at(NORMAL);
        portfolio.changeSafeMode(HALT, AUTOMATIC, EXCHANGE_UNAVAILABLE, "watchdog", false).orElseThrow();
        assertTrue(portfolio.isInAutoResumableHalt());

        SafeModeChange change = portfolio.changeSafeMode(NORMAL, AUTOMATIC, EXCHANGE_UNAVAILABLE, "auto resume", false)
                .orElseThrow();

        assertEquals(HALT, change.from());
        assertEquals(NORMAL, change.to());
        assertEquals(AUTOMATIC, change.origin());
        assertEquals(EXCHANGE_UNAVAILABLE, change.trigger());
        assertEquals(NORMAL, portfolio.getSafeModeStatus());
        assertTrue(!portfolio.isInAutoResumableHalt());
    }

    @Test
    void automaticResumeRefusedForOtherTriggersManualHaltsAndHigherLevels() {
        Portfolio drawdown = at(NORMAL);
        drawdown.changeSafeMode(HALT, AUTOMATIC, SafeModeTrigger.MAX_DRAWDOWN_EXCEEDED, "drawdown", false).orElseThrow();
        assertThrows(SafeModeTransitionException.class,
                () -> drawdown.changeSafeMode(NORMAL, AUTOMATIC, SafeModeTrigger.MAX_DRAWDOWN_EXCEEDED, "x", false));

        Portfolio stuck = at(NORMAL);
        stuck.changeSafeMode(HALT, AUTOMATIC, WATCHDOG_STUCK, "stuck", false).orElseThrow();
        assertThrows(SafeModeTransitionException.class,
                () -> stuck.changeSafeMode(NORMAL, AUTOMATIC, WATCHDOG_STUCK, "x", false));
        assertThrows(SafeModeTransitionException.class,
                () -> stuck.changeSafeMode(NORMAL, AUTOMATIC, EXCHANGE_UNAVAILABLE, "other trigger", false));

        assertThrows(SafeModeTransitionException.class,
                () -> at(HALT).changeSafeMode(NORMAL, AUTOMATIC, EXCHANGE_UNAVAILABLE, "manual halt", false));
        assertThrows(SafeModeTransitionException.class,
                () -> at(CANCEL_ALL).changeSafeMode(NORMAL, AUTOMATIC, EXCHANGE_UNAVAILABLE, "higher", false));
    }

    @Test
    void operatorTouchAfterAutomaticHaltDisablesAutomaticResume() {
        Portfolio portfolio = at(NORMAL);
        portfolio.changeSafeMode(HALT, AUTOMATIC, EXCHANGE_UNAVAILABLE, "watchdog", false).orElseThrow();
        portfolio.changeSafeMode(CANCEL_ALL, MANUAL, null, "operator", true).orElseThrow();
        portfolio.changeSafeMode(HALT, MANUAL, null, "operator", false).orElseThrow();

        assertTrue(!portfolio.isInAutoResumableHalt());
        assertEquals(null, portfolio.getSafeModeTrigger());
        assertThrows(SafeModeTransitionException.class,
                () -> portfolio.changeSafeMode(NORMAL, AUTOMATIC, EXCHANGE_UNAVAILABLE, "x", false));
    }

    @Test
    void automaticHaltDoesNotLowerAHigherManualLevel() {
        Portfolio portfolio = at(PANIC_SELL);

        Optional<SafeModeChange> change = portfolio.changeSafeMode(HALT, AUTOMATIC, DLQ_THRESHOLD, "daily loss", false);

        assertTrue(change.isEmpty());
        assertEquals(PANIC_SELL, portfolio.getSafeModeStatus());
    }

    @Test
    void reasonIsMandatory() {
        assertThrows(SafeModeTransitionException.class,
                () -> at(NORMAL).changeSafeMode(HALT, MANUAL, null, " ", false));
        assertThrows(SafeModeTransitionException.class,
                () -> at(NORMAL).changeSafeMode(HALT, MANUAL, null, null, false));
    }

    private static Portfolio at(SafeModeStatus status) {
        return new Portfolio(UUID.randomUUID(), "p", true, Instant.now(), status, CapitalPoolingMode.SHARED,
                status == NORMAL ? null : SafeModeOrigin.MANUAL, null, status == NORMAL ? null : "setup",
                status == NORMAL ? null : Instant.now(), 0L);
    }
}
