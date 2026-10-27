package com.marmitt.core.application.usecase.runner.processsignal;

import com.marmitt.core.domain.runner.Position;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.strategy.StrategyOutputDto;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.PositionStatus;
import com.marmitt.core.enums.TradingAction;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.enums.TransactionType;
import com.marmitt.core.ports.outbound.exchange.OrderDispatchPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Teto da venda e dono do lote alvo (T61). */
class SellSignalHandlerTest {

    private static final BigDecimal LOT_QUANTITY = new BigDecimal("0.00174000");

    private final StrategyRunnerRepositoryPort repository = mock(StrategyRunnerRepositoryPort.class);
    private final OrderDispatchPort orderDispatch = mock(OrderDispatchPort.class);
    private final SellSignalHandler handler = new SellSignalHandler(repository, new TradeIntentFactory(), orderDispatch);
    private final SellSignalHandler.SellPersistenceAction persistence = mock(SellSignalHandler.SellPersistenceAction.class);

    private final UUID runnerId = UUID.randomUUID();
    private final StrategyRunner runner = runner(runnerId);

    @Test
    void rejectsSellAboveTheLotWithoutPersistingOrDispatching() {
        // O fault OVERSELL do SoakChaos na rodada da T60: o dobro do lote.
        Position lot = lot(runnerId);
        when(repository.findPositionById(lot.getId())).thenReturn(Optional.of(lot));

        SellSignalHandler.SellOutcome outcome = handle(sell(lot.getId()), new BigDecimal("0.00348000"));

        assertEquals(SellSignalHandler.SellOutcome.REJECTED_QUANTITY, outcome);
        verify(persistence, never()).persist(any(), any());
        verify(orderDispatch, never()).dispatch(any());
    }

    @Test
    void rejectsSellAboveTheLotWithoutTargetLot() {
        Position lot = lot(runnerId);
        when(repository.findOpenPositionByRunnerIdAndSymbol(runnerId, "BTCUSDT")).thenReturn(Optional.of(lot));

        assertEquals(SellSignalHandler.SellOutcome.REJECTED_QUANTITY,
                handle(sell(null), new BigDecimal("0.00174001")));
        verify(orderDispatch, never()).dispatch(any());
    }

    @Test
    void discardsSellOfAnotherRunnersLot() {
        Position lot = lot(UUID.randomUUID());
        when(repository.findPositionById(lot.getId())).thenReturn(Optional.of(lot));

        assertEquals(SellSignalHandler.SellOutcome.NO_OPEN_POSITION, handle(sell(lot.getId()), LOT_QUANTITY));
        verify(persistence, never()).persist(any(), any());
        verify(orderDispatch, never()).dispatch(any());
    }

    @Test
    void dispatchesSellOfTheWholeLot() {
        Position lot = lot(runnerId);
        when(repository.findPositionById(lot.getId())).thenReturn(Optional.of(lot));

        assertEquals(SellSignalHandler.SellOutcome.DISPATCHED, handle(sell(lot.getId()), LOT_QUANTITY));
        verify(persistence).persist(any(), any());
        verify(orderDispatch).dispatch(any());
    }

    @Test
    void dispatchesPartialSell() {
        Position lot = lot(runnerId);
        when(repository.findPositionById(lot.getId())).thenReturn(Optional.of(lot));

        assertEquals(SellSignalHandler.SellOutcome.DISPATCHED,
                handle(sell(lot.getId()), new BigDecimal("0.00100000")));
        verify(orderDispatch).dispatch(any());
    }

    private SellSignalHandler.SellOutcome handle(StrategyOutputDto signal, BigDecimal quantity) {
        return handler.handle(runner, signal, transaction(quantity), persistence, id -> { }, tx -> { });
    }

    private static StrategyOutputDto sell(UUID targetLotId) {
        return new StrategyOutputDto("SoakChaos", TradingAction.SHOULD_SELL, BigDecimal.ONE, LOT_QUANTITY, null,
                targetLotId, null, "test", Instant.now(), Map.of());
    }

    private static StrategyRunner runner(UUID id) {
        StrategyRunner runner = mock(StrategyRunner.class);
        when(runner.getId()).thenReturn(id);
        when(runner.getSymbol()).thenReturn("BTCUSDT");
        when(runner.getExchangeId()).thenReturn("BINANCE");
        return runner;
    }

    private static Position lot(UUID ownerRunnerId) {
        Instant now = Instant.now();
        return Position.reconstitute()
                .id(UUID.randomUUID()).runnerId(ownerRunnerId).symbol("BTCUSDT").status(PositionStatus.OPEN)
                .quantity(LOT_QUANTITY).averagePrice(new BigDecimal("80183.15")).costBasis(new BigDecimal("139.52"))
                .realizedPnl(BigDecimal.ZERO).openedAt(now).updatedAt(now)
                .build();
    }

    private Transaction transaction(BigDecimal quantity) {
        return Transaction.reconstitute()
                .id(UUID.randomUUID())
                .runnerId(runnerId)
                .clientOrderId("client-order-id")
                .status(TransactionStatus.PENDING)
                .type(TransactionType.SELL)
                .orderType(OrderType.MARKET)
                .symbol("BTCUSDT")
                .quantity(quantity)
                .price(new BigDecimal("80178.52"))
                .total(quantity.multiply(new BigDecimal("80178.52")))
                .requestedAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
