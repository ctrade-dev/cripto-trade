package com.marmitt.core.application.usecase.runner;

import com.marmitt.core.application.usecase.portfolio.AutomaticSafeModeTrigger;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.runner.request.RecoverStaleTransactionsRequest;
import com.marmitt.core.dto.runner.response.RecoverTransactionStatusResponse;
import com.marmitt.core.enums.SafeModeTrigger;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T25: mesma transacao sem resolver em ciclos seguidos do watchdog aciona HALT do portfolio.
 */
class RecoverStaleTransactionsStuckTriggerTest {

    private static final UUID RUNNER = UUID.randomUUID();
    private static final UUID PORTFOLIO = UUID.randomUUID();
    private static final UUID STUCK_ID = UUID.randomUUID();
    private static final RecoverStaleTransactionsRequest REQUEST =
            new RecoverStaleTransactionsRequest(Instant.now(), Instant.now(), 50);

    private final StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
    private final RecoverTransactionStatusUseCase recoverStatus = mock(RecoverTransactionStatusUseCase.class);
    private final AutomaticSafeModeTrigger trigger = mock(AutomaticSafeModeTrigger.class);
    private final RecoverStaleTransactionsUseCase useCase =
            new RecoverStaleTransactionsUseCase(repository, recoverStatus, trigger, 3);
    private final Transaction stuck = transaction();

    RecoverStaleTransactionsStuckTriggerTest() {
        StrategyRunner runner = mock(StrategyRunner.class);
        when(runner.getId()).thenReturn(RUNNER);
        when(runner.getPortfolioId()).thenReturn(PORTFOLIO);
        when(repository.findById(RUNNER)).thenReturn(Optional.of(runner));
        when(repository.findByStatusesUpdatedBefore(eq(List.of(TransactionStatus.SUBMITTED, TransactionStatus.PARTIAL)),
                any(), anyInt())).thenReturn(List.of(stuck));
        when(repository.findByStatusesUpdatedBefore(eq(List.of(TransactionStatus.PENDING)), any(), anyInt()))
                .thenReturn(List.of());
    }

    @Test
    void thirdConsecutiveFailureTriggersHalt() {
        failing();
        useCase.execute(REQUEST);
        useCase.execute(REQUEST);
        verify(trigger, never()).activate(any(), any(), any());

        useCase.execute(REQUEST);

        verify(trigger).activate(eq(PORTFOLIO), eq(SafeModeTrigger.WATCHDOG_STUCK), any());
    }

    @Test
    void haltReasonCarriesTheLastFailure() {
        failing();
        useCase.execute(REQUEST);
        useCase.execute(REQUEST);
        useCase.execute(REQUEST);

        verify(trigger).activate(PORTFOLIO, SafeModeTrigger.WATCHDOG_STUCK,
                "transactionId=" + STUCK_ID + " runnerId=" + RUNNER + " status=SUBMITTED cycles=3"
                        + " lastFailure=EXCHANGE_QUERY_RETRYABLE_FAILURE: circuit open");
    }

    @Test
    void unexpectedExceptionIsTheLastFailureTooAndLongMessagesAreCut() {
        when(recoverStatus.execute(any())).thenThrow(new IllegalStateException("x".repeat(500)));
        useCase.execute(REQUEST);
        useCase.execute(REQUEST);
        useCase.execute(REQUEST);

        String expected = "IllegalStateException: " + "x".repeat(500);
        verify(trigger).activate(PORTFOLIO, SafeModeTrigger.WATCHDOG_STUCK,
                "transactionId=" + STUCK_ID + " runnerId=" + RUNNER + " status=SUBMITTED cycles=3"
                        + " lastFailure=" + expected.substring(0, 200) + "...");
    }

    @Test
    void cycleThatResolvesTheTransactionResetsTheCount() {
        failing();
        useCase.execute(REQUEST);
        useCase.execute(REQUEST);
        when(recoverStatus.execute(any())).thenReturn(RecoverTransactionStatusResponse.skipped(
                STUCK_ID, RUNNER, "BINANCE", TransactionStatus.SUBMITTED, "already terminal"));
        useCase.execute(REQUEST);
        failing();
        useCase.execute(REQUEST);
        useCase.execute(REQUEST);

        verify(trigger, never()).activate(any(), any(), any());
    }

    private void failing() {
        when(recoverStatus.execute(any())).thenReturn(RecoverTransactionStatusResponse.failed(
                STUCK_ID, RUNNER, "BINANCE", TransactionStatus.SUBMITTED,
                RecoverTransactionStatusResponse.FailureReason.EXCHANGE_QUERY_RETRYABLE_FAILURE, "circuit open"));
    }

    private static Transaction transaction() {
        Transaction transaction = mock(Transaction.class);
        when(transaction.getId()).thenReturn(STUCK_ID);
        when(transaction.getRunnerId()).thenReturn(RUNNER);
        when(transaction.getStatus()).thenReturn(TransactionStatus.SUBMITTED);
        return transaction;
    }
}
