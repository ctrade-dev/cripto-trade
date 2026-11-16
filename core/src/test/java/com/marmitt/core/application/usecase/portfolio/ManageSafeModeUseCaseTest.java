package com.marmitt.core.application.usecase.portfolio;

import com.marmitt.core.domain.portfolio.Portfolio;
import com.marmitt.core.domain.portfolio.SafeModeChange;
import com.marmitt.core.domain.runner.Position;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.portfolio.request.ChangeSafeModeRequest;
import com.marmitt.core.dto.portfolio.response.SafeModeStatusDto;
import com.marmitt.core.enums.CapitalPoolingMode;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.SafeModeOrigin;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.exceptions.PortfolioNotFoundException;
import com.marmitt.core.exceptions.SafeModeTransitionException;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManageSafeModeUseCaseTest {

    private final PortfolioRepositoryPort portfolioRepository = mock(PortfolioRepositoryPort.class);
    private final StrategyRunnerRepositoryPort runnerRepository = mock(StrategyRunnerRepositoryPort.class);
    private final ManageSafeModeUseCase useCase = new ManageSafeModeUseCase(portfolioRepository, runnerRepository);
    private final UUID portfolioId = UUID.randomUUID();

    @Test
    void changePersistsTheTransitionWithItsAudit() {
        Portfolio portfolio = portfolioAt(SafeModeStatus.NORMAL);
        when(portfolioRepository.findById(portfolioId)).thenReturn(Optional.of(portfolio));

        SafeModeStatusDto status = useCase.change(portfolioId, SafeModeOrigin.MANUAL,
                ChangeSafeModeRequest.manual(SafeModeStatus.CANCEL_ALL, "exchange instavel", true));

        ArgumentCaptor<SafeModeChange> change = ArgumentCaptor.forClass(SafeModeChange.class);
        verify(portfolioRepository).saveSafeModeChange(any(), change.capture());
        assertEquals(SafeModeStatus.NORMAL, change.getValue().from());
        assertEquals(SafeModeStatus.CANCEL_ALL, change.getValue().to());
        assertEquals("exchange instavel", change.getValue().reason());
        assertEquals(SafeModeStatus.CANCEL_ALL, status.level());
        assertEquals(SafeModeOrigin.MANUAL, status.origin());
    }

    @Test
    void repeatingTheCurrentLevelSavesNothing() {
        when(portfolioRepository.findById(portfolioId)).thenReturn(Optional.of(portfolioAt(SafeModeStatus.HALT)));

        useCase.change(portfolioId, SafeModeOrigin.MANUAL, ChangeSafeModeRequest.manual(SafeModeStatus.HALT, "again", false));

        verify(portfolioRepository, never()).saveSafeModeChange(any(), any());
    }

    @Test
    void rejectedTransitionSavesNothing() {
        when(portfolioRepository.findById(portfolioId)).thenReturn(Optional.of(portfolioAt(SafeModeStatus.NORMAL)));

        assertThrows(SafeModeTransitionException.class, () -> useCase.change(portfolioId, SafeModeOrigin.MANUAL,
                ChangeSafeModeRequest.manual(SafeModeStatus.PANIC_SELL, "sem confirmacao", false)));
        verify(portfolioRepository, never()).saveSafeModeChange(any(), any());
    }

    @Test
    void missingPortfolioIsNotFound() {
        when(portfolioRepository.findById(portfolioId)).thenReturn(Optional.empty());

        assertThrows(PortfolioNotFoundException.class, () -> useCase.status(portfolioId));
    }

    @Test
    void statusCountsWhatIsLeftOnOperationalRunnersAndIdleRunnersApart() {
        when(portfolioRepository.findById(portfolioId)).thenReturn(Optional.of(portfolioAt(SafeModeStatus.PANIC_SELL)));
        StrategyRunner active = runner(true);
        StrategyRunner halted = runner(false);
        when(runnerRepository.findByPortfolioId(portfolioId)).thenReturn(List.of(active, halted));
        List<Transaction> inFlight = List.of(tx(OrderType.LIMIT), tx(OrderType.LIMIT), tx(OrderType.MARKET));
        List<Position> openPositions = List.of(mock(Position.class));
        when(runnerRepository.findByRunnerIdAndStatuses(active.getId(), TransactionStatus.IN_FLIGHT)).thenReturn(inFlight);
        when(runnerRepository.findOpenPositionsByRunnerId(active.getId())).thenReturn(openPositions);

        SafeModeStatusDto status = useCase.status(portfolioId);

        assertEquals(2, status.restingOrders());
        assertEquals(1, status.liquidatingOrders());
        assertEquals(1, status.openPositions());
        assertEquals(1, status.idleRunners());
        verify(runnerRepository, never()).findOpenPositionsByRunnerId(halted.getId());
    }

    private Portfolio portfolioAt(SafeModeStatus status) {
        return new Portfolio(portfolioId, "p", true, Instant.now(), status, CapitalPoolingMode.SHARED,
                null, null, null, null, 0L);
    }

    private static StrategyRunner runner(boolean operational) {
        StrategyRunner runner = mock(StrategyRunner.class);
        when(runner.getId()).thenReturn(UUID.randomUUID());
        when(runner.canAcceptSignals()).thenReturn(operational);
        return runner;
    }

    private static Transaction tx(OrderType orderType) {
        Transaction transaction = mock(Transaction.class);
        when(transaction.getOrderType()).thenReturn(orderType);
        return transaction;
    }
}
