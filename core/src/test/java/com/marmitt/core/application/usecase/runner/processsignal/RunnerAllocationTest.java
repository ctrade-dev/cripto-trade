package com.marmitt.core.application.usecase.runner.processsignal;

import com.marmitt.core.application.exception.CapitalReservationRejectedException;
import com.marmitt.core.domain.portfolio.GlobalBalance;
import com.marmitt.core.domain.portfolio.Portfolio;
import com.marmitt.core.domain.runner.Position;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.capital.CapitalRequest;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.PositionStatus;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.enums.TransactionType;
import com.marmitt.core.ports.outbound.repository.GlobalBalanceRepositoryPort;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Teto de alocacao do runner (T62, decisao B): exposicao = custo do lote ativo + parte nao executada
 * das BUY em voo; o espaco restante e o mesmo na reserva e no {@code maxOperationAmount} do contexto.
 */
class RunnerAllocationTest {

    private static final UUID PORTFOLIO = UUID.randomUUID();
    private static final UUID RUNNER = UUID.randomUUID();
    private static final String SYMBOL = "BTCUSDT";

    private final StrategyRunnerRepositoryPort runners = mock(StrategyRunnerRepositoryPort.class);
    private final GlobalBalanceRepositoryPort balances = mock(GlobalBalanceRepositoryPort.class);
    private final PortfolioRepositoryPort portfolios = mock(PortfolioRepositoryPort.class);
    private final RunnerExposureService exposureService = new RunnerExposureService(runners);
    private final StrategyRunner runner = runner();

    @BeforeEach
    void portfolioOf1000WithRunnerCapOf40Percent() {
        // 1000 USDT: 860 disponivel + 140 reservado; teto do runner = 0.4 x 1000 = 400.
        GlobalBalance balance = mock(GlobalBalance.class);
        when(balance.getAvailableBalance()).thenReturn(new BigDecimal("860"));
        when(balance.getTotalBalance()).thenReturn(new BigDecimal("1000"));
        when(balance.getRealizedBalance()).thenReturn(BigDecimal.ZERO);
        when(balances.findByPortfolioId(PORTFOLIO)).thenReturn(Optional.of(balance));
        when(balances.reserveAtomic(eq(PORTFOLIO), any())).thenReturn(true);
        Portfolio portfolio = mock(Portfolio.class);
        when(portfolio.getSafeModeStatus()).thenReturn(SafeModeStatus.NORMAL);
        when(portfolios.findById(PORTFOLIO)).thenReturn(Optional.of(portfolio));
    }

    @Test
    void exposureIsTheLotCostPlusTheUnexecutedPartOfBuysInFlight() {
        givenLot("140");
        givenInFlight(
                buy(TransactionStatus.SUBMITTED, "0.001", null, "60000"),      // 60 inteiros
                buy(TransactionStatus.PARTIAL, "0.002", "0.0015", "50000"),    // so 0.0005 x 50000 = 25
                sell("0.00174", "80000"));                                     // SELL nao soma

        RunnerExposureService.ExposureSnapshot snapshot = exposureService.loadSnapshot(runner);

        assertEquals(0, new BigDecimal("225").compareTo(snapshot.exposure()));
        assertEquals(3, snapshot.inFlightOrders());
    }

    @Test
    void anOpenLotTakesItsCostFromTheRoomUntilItIsSold() {
        // O caso da decisao B: com o lote preenchido, o runner nao volta a ter o teto inteiro.
        givenLot("400");
        givenInFlight();

        assertEquals(0, BigDecimal.ZERO.compareTo(contextMaxOperationAmount()));
        assertThrows(CapitalReservationRejectedException.class, () -> reserve("10"));
    }

    @Test
    void contextAndReservationAgreeOnTheRoom() {
        givenLot("140");
        givenInFlight(buy(TransactionStatus.SUBMITTED, "0.001", null, "60000"));

        BigDecimal room = contextMaxOperationAmount();

        assertEquals(0, new BigDecimal("200").compareTo(room));      // 400 - 140 - 60
        assertDoesNotThrow(() -> reserve("200"));
        assertThrows(CapitalReservationRejectedException.class, () -> reserve("200.01"));
    }

    @Test
    void maxOperationAmountIsCappedByThePortfolioAvailableBalance() {
        when(runner.getMaxAllocationPercent()).thenReturn(BigDecimal.ONE);   // teto 1000 > 860 disponivel
        givenInFlight();

        assertEquals(0, new BigDecimal("860").compareTo(contextMaxOperationAmount()));
    }

    @Test
    void roomIsNeverNegative() {
        GlobalBalance balance = balances.findByPortfolioId(PORTFOLIO).orElseThrow();

        assertEquals(0, BigDecimal.ZERO.compareTo(
                RunnerExposureService.allocationRoom(runner, balance, new BigDecimal("500"))));
    }

    private BigDecimal contextMaxOperationAmount() {
        RunnerContextAssembler assembler =
                new RunnerContextAssembler(balances, runners, exposureService, new BigDecimal("5"));
        return assembler.assemble(runner, new BigDecimal("80000")).maxOperationAmount();
    }

    private void reserve(String amount) {
        CapitalReservationPolicy policy = new CapitalReservationPolicy(portfolios, balances, exposureService);
        policy.validateAndReserve(new CapitalRequest(UUID.randomUUID(), RUNNER, "r1", SYMBOL,
                new BigDecimal(amount), TransactionType.BUY), runner, null);
    }

    private void givenLot(String cost) {
        Instant now = Instant.now();
        Position lot = Position.reconstitute()
                .id(UUID.randomUUID()).runnerId(RUNNER).symbol(SYMBOL).status(PositionStatus.OPEN)
                .quantity(new BigDecimal("0.00174")).averagePrice(new BigDecimal(cost).divide(new BigDecimal("0.00174"), 8, RoundingMode.HALF_UP))
                .costBasis(new BigDecimal(cost))
                .realizedPnl(BigDecimal.ZERO).openedAt(now).updatedAt(now)
                .build();
        when(runners.findActivePositionByRunnerIdAndSymbol(RUNNER, SYMBOL)).thenReturn(Optional.of(lot));
        when(runners.findOpenPositionsByRunnerId(RUNNER)).thenReturn(List.of(lot));
    }

    private void givenInFlight(Transaction... transactions) {
        when(runners.findByRunnerIdAndStatuses(RUNNER, TransactionStatus.IN_FLIGHT)).thenReturn(List.of(transactions));
    }

    private static Transaction buy(TransactionStatus status, String quantity, String executed, String price) {
        return transaction(TransactionType.BUY, status, quantity, executed, price);
    }

    private static Transaction sell(String quantity, String price) {
        return transaction(TransactionType.SELL, TransactionStatus.SUBMITTED, quantity, null, price);
    }

    private static Transaction transaction(TransactionType type, TransactionStatus status, String quantity,
                                           String executed, String price) {
        BigDecimal qty = new BigDecimal(quantity);
        BigDecimal px = new BigDecimal(price);
        return Transaction.reconstitute()
                .id(UUID.randomUUID())
                .runnerId(RUNNER)
                .clientOrderId("client-" + UUID.randomUUID())
                .exchangeOrderId("EX")
                .status(status)
                .type(type)
                .orderType(OrderType.LIMIT)
                .symbol(SYMBOL)
                .quantity(qty)
                .executedQuantity(executed == null ? null : new BigDecimal(executed))
                .price(px)
                .total(qty.multiply(px))
                .requestedAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private static StrategyRunner runner() {
        StrategyRunner runner = mock(StrategyRunner.class);
        when(runner.getId()).thenReturn(RUNNER);
        when(runner.getPortfolioId()).thenReturn(PORTFOLIO);
        when(runner.getSymbol()).thenReturn(SYMBOL);
        when(runner.getMaxAllocationPercent()).thenReturn(new BigDecimal("0.4"));
        when(runner.getMaxOpenPositions()).thenReturn(1);
        return runner;
    }
}
