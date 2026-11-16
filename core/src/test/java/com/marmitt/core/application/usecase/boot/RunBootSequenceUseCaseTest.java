package com.marmitt.core.application.usecase.boot;

import com.marmitt.core.dto.boot.BootExecutionCommand;
import com.marmitt.core.enums.BootAccountQueryPolicy;
import com.marmitt.core.enums.BootFailureMode;
import com.marmitt.core.enums.BootRunStatus;
import com.marmitt.core.ports.outbound.boot.BootExecutionObserverPort;
import com.marmitt.core.ports.outbound.repository.BootRunRepositoryPort;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T70: a sequencia do boot so ordena as fases, liga/desliga, trata a falha e grava o historico; o corpo de cada
 * fase fica fora dela.
 */
class RunBootSequenceUseCaseTest {

    private static final BootExecutionCommand COMMAND = new BootExecutionCommand(true, true, true, true, true, true,
            BootFailureMode.FAIL_FAST, BootAccountQueryPolicy.FAIL);

    private final PortfolioRepositoryPort portfolios = mock(PortfolioRepositoryPort.class);
    private final StrategyRunnerRepositoryPort runners = mock(StrategyRunnerRepositoryPort.class);
    private final BootRunRepositoryPort bootRuns = mock(BootRunRepositoryPort.class);
    private final BootExecutionObserverPort observer = mock(BootExecutionObserverPort.class);
    private final List<String> executed = new ArrayList<>();

    @Test
    void runsEnabledPhasesInOrderAndSkipsDisabledOnes() {
        sequence(phase("a", true, null), phase("b", false, null), phase("c", true, null))
                .execute(COMMAND, observer);

        assertEquals(List.of("a", "c"), executed);
        InOrder order = inOrder(observer);
        order.verify(observer).onRunStarted(anyString(), eq("FAIL_FAST"));
        order.verify(observer).onPhaseStarted("a");
        order.verify(observer).onPhaseSucceeded(eq("a"), anyLong());
        order.verify(observer).onPhaseSkipped("b");
        order.verify(observer).onPhaseStarted("c");
        order.verify(observer).onPhaseSucceeded(eq("c"), anyLong());
        order.verify(observer).onRunCompleted(anyString());
        verify(bootRuns).save(argThat(run -> run.status() == BootRunStatus.SUCCESS && run.failurePhase() == null));
    }

    @Test
    void failingPhaseStopsTheSequenceAndReportsThePhaseOnce() {
        RuntimeException failure = new IllegalStateException("boom");
        RunBootSequenceUseCase sequence = sequence(phase("a", true, null), phase("b", true, failure),
                phase("c", true, null));

        assertThrows(IllegalStateException.class, () -> sequence.execute(COMMAND, observer));

        assertEquals(List.of("a", "b"), executed);
        verify(observer).onPhaseFailed(eq("b"), anyLong(), eq("boom"));
        verify(observer).onRunFailed(anyString(), eq("b"), eq("boom"));
        verify(observer, never()).onPhaseStarted("c");
        verify(observer, never()).onRunCompleted(any());
        verify(bootRuns).save(argThat(run -> run.status() == BootRunStatus.FAILED && "b".equals(run.failurePhase())
                && "boom".equals(run.failureMessage())));
    }

    @Test
    void failFastFromAPhaseNotifiesTheObserverAndFailsTheBoot() {
        BootPhase failFast = new BootPhase() {
            @Override public String name() { return "x"; }
            @Override public boolean enabled(BootExecutionCommand command) { return true; }
            @Override public void run(BootContext context) {
                throw context.failFast("x", "CODE", "not ready");
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> sequence(failFast).execute(COMMAND, observer));

        assertEquals("not ready code=CODE", error.getMessage());
        verify(observer).onFailFastRequested(anyString(), eq("x"), eq("CODE"), eq("not ready"));
        verify(observer).onRunFailed(anyString(), eq("x"), eq("not ready code=CODE"));
    }

    @Test
    void failureBeforeAnyPhaseHasNoPhase() {
        when(portfolios.findAll()).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> sequence(phase("a", true, null)).execute(COMMAND, observer));

        assertEquals(List.of(), executed);
        verify(observer).onRunFailed(anyString(), eq(null), eq("db down"));
        verify(bootRuns).save(argThat(run -> run.status() == BootRunStatus.FAILED && run.failurePhase() == null));
    }

    private RunBootSequenceUseCase sequence(BootPhase... phases) {
        return new RunBootSequenceUseCase(portfolios, runners, List.of(phases), bootRuns);
    }

    private BootPhase phase(String name, boolean enabled, RuntimeException failure) {
        return new BootPhase() {
            @Override public String name() { return name; }
            @Override public boolean enabled(BootExecutionCommand command) { return enabled; }
            @Override public void run(BootContext context) {
                executed.add(name);
                if (failure != null) {
                    throw failure;
                }
            }
        };
    }
}
