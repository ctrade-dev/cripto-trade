package com.marmitt.core.application.usecase.portfolio;

import com.marmitt.core.domain.portfolio.Portfolio;
import com.marmitt.core.dto.notification.ErrorNotificationEvent;
import com.marmitt.core.dto.notification.ErrorNotificationType;
import com.marmitt.core.dto.portfolio.request.ChangeSafeModeRequest;
import com.marmitt.core.enums.SafeModeOrigin;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.enums.SafeModeTrigger;
import com.marmitt.core.ports.inbound.portfolio.ManageSafeModePort;
import com.marmitt.core.ports.outbound.metrics.CircuitBreakerMetricsPort;
import com.marmitt.core.ports.outbound.notification.ErrorNotificationPort;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T25: caminho unico do HALT automatico.
 */
class AutomaticSafeModeTriggerTest {

    private static final UUID PORTFOLIO = UUID.randomUUID();

    private final PortfolioRepositoryPort portfolios = mock(PortfolioRepositoryPort.class);
    private final ManageSafeModePort manageSafeMode = mock(ManageSafeModePort.class);
    private final CircuitBreakerMetricsPort metrics = mock(CircuitBreakerMetricsPort.class);
    private final ErrorNotificationPort notification = mock(ErrorNotificationPort.class);

    @Test
    void normalPortfolioGoesToHaltWithAuditMetricAndAlert() {
        portfolioAt(SafeModeStatus.NORMAL);

        assertTrue(trigger(EnumSet.allOf(SafeModeTrigger.class))
                .activate(PORTFOLIO, SafeModeTrigger.DLQ_THRESHOLD, "unresolved=3 threshold=3"));

        ArgumentCaptor<ChangeSafeModeRequest> request = ArgumentCaptor.forClass(ChangeSafeModeRequest.class);
        verify(manageSafeMode).change(eq(PORTFOLIO), eq(SafeModeOrigin.AUTOMATIC), request.capture());
        assertEquals(SafeModeStatus.HALT, request.getValue().level());
        assertEquals("DLQ_THRESHOLD unresolved=3 threshold=3", request.getValue().reason());
        assertEquals(SafeModeTrigger.DLQ_THRESHOLD, request.getValue().trigger());
        verify(metrics).recordActivation(PORTFOLIO, "DLQ_THRESHOLD");
        ArgumentCaptor<ErrorNotificationEvent> event = ArgumentCaptor.forClass(ErrorNotificationEvent.class);
        verify(notification).notifyError(event.capture());
        assertEquals(ErrorNotificationType.CIRCUIT_BREAKER_TRIGGERED, event.getValue().type());
        assertTrue(event.getValue().title().startsWith("DLQ_THRESHOLD"));
    }

    @Test
    void portfolioAlreadyInSafeModeIsNotTouchedAgain() {
        for (SafeModeStatus active : new SafeModeStatus[]{SafeModeStatus.HALT, SafeModeStatus.PANIC_SELL}) {
            portfolioAt(active);
            assertFalse(trigger(EnumSet.allOf(SafeModeTrigger.class))
                    .activate(PORTFOLIO, SafeModeTrigger.WATCHDOG_STUCK, "cycles=3"));
        }
        verify(manageSafeMode, never()).change(any(), any(), any());
        verifyNoInteractions(metrics, notification);
    }

    @Test
    void disabledTriggerIsIgnored() {
        portfolioAt(SafeModeStatus.NORMAL);

        assertFalse(trigger(EnumSet.of(SafeModeTrigger.MAX_DRAWDOWN_EXCEEDED))
                .activate(PORTFOLIO, SafeModeTrigger.BALANCE_DIVERGENCE, "delta=-10"));
        verifyNoInteractions(manageSafeMode, metrics, notification);
    }

    private AutomaticSafeModeTrigger trigger(EnumSet<SafeModeTrigger> enabled) {
        return new AutomaticSafeModeTrigger(portfolios, manageSafeMode, metrics, notification,
                Clock.systemUTC(), enabled);
    }

    private void portfolioAt(SafeModeStatus level) {
        Portfolio portfolio = mock(Portfolio.class);
        when(portfolio.getName()).thenReturn("p");
        when(portfolio.getSafeModeStatus()).thenReturn(level);
        when(portfolios.findById(PORTFOLIO)).thenReturn(Optional.of(portfolio));
    }
}
