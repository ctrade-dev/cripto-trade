package com.marmitt.core.application.usecase.runner.processsignal;

import com.marmitt.core.domain.portfolio.GlobalBalance;
import com.marmitt.core.domain.runner.Position;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Calcula a exposicao financeira atual do runner: o capital que ele tem investido e o que ainda
 * esta a caminho (T62, decisao B do mantenedor).
 *
 * <p><b>Exposicao</b> = custo pago do lote ativo ({@code costBasis}, OPEN ou CLOSING) + a parte ainda
 * nao executada das BUY em voo (PENDING, SUBMITTED ou PARTIAL). Um fill parcial ja entra no lote, por
 * isso a BUY em voo conta so o que falta executar. SELL em voo nao soma: so reduz exposicao, e o lote que
 * ela trava ja esta no custo.
 *
 * <p>{@code maxAllocationPercent × totalBalance} menos a exposicao e o espaco do runner
 * ({@link #allocationRoom}): a reserva de capital recusa BUY acima dele e o contexto da estrategia o
 * informa como {@code maxOperationAmount}. Uma conta so para os dois.
 *
 * <p>Usado por:
 * <ul>
 *   <li>{@link RunnerSignalPolicy}: politica SINGLE (posicao aberta ou ordem em voo).</li>
 *   <li>{@link PreTradeRiskPolicy}: limites {@code maxOpenPositions}/{@code maxPendingOrders} (T50).</li>
 *   <li>{@link CapitalReservationPolicy}: teto de alocacao do runner na reserva.</li>
 *   <li>{@link RunnerContextAssembler}: {@code maxOperationAmount} do contexto.</li>
 *   <li>Metrica de utilizacao de capital do runner (T51).</li>
 * </ul>
 */
public class RunnerExposureService {

    private final StrategyRunnerRepositoryPort strategyRunnerRepository;

    public RunnerExposureService(StrategyRunnerRepositoryPort strategyRunnerRepository) {
        this.strategyRunnerRepository = strategyRunnerRepository;
    }

    /**
     * Foto da exposicao do runner: posicoes abertas, ordens em voo e o capital investido.
     */
    public ExposureSnapshot loadSnapshot(StrategyRunner runner) {
        List<Position> openPositions = strategyRunnerRepository.findOpenPositionsByRunnerId(runner.getId());
        Optional<Position> activeLot =
                strategyRunnerRepository.findActivePositionByRunnerIdAndSymbol(runner.getId(), runner.getSymbol());
        List<Transaction> inFlight = strategyRunnerRepository.findByRunnerIdAndStatuses(runner.getId(), TransactionStatus.IN_FLIGHT);

        BigDecimal lotCost = activeLot.map(Position::getCostBasis).orElse(BigDecimal.ZERO);
        BigDecimal pendingBuys = inFlight.stream()
                .filter(Transaction::isBuy)
                .map(RunnerExposureService::unexecutedTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Instant oldestInFlightAt = inFlight.stream()
                .map(Transaction::getRequestedAt)
                .min(Instant::compareTo)
                .orElse(null);
        return new ExposureSnapshot(openPositions.size(), inFlight.size(), lotCost.add(pendingBuys), oldestInFlightAt);
    }

    /** Teto de alocacao do runner: {@code maxAllocationPercent × totalBalance} do portfolio. */
    public static BigDecimal allocationLimit(StrategyRunner runner, GlobalBalance balance) {
        return runner.getMaxAllocationPercent().multiply(balance.getTotalBalance());
    }

    /** Quanto o runner ainda pode investir: o teto menos a exposicao, nunca negativo. */
    public static BigDecimal allocationRoom(StrategyRunner runner, GlobalBalance balance, BigDecimal exposure) {
        return allocationLimit(runner, balance).subtract(exposure).max(BigDecimal.ZERO);
    }

    private static BigDecimal unexecutedTotal(Transaction buy) {
        BigDecimal executed = buy.getExecutedQuantity() == null ? BigDecimal.ZERO : buy.getExecutedQuantity();
        if (executed.signum() == 0) {
            return buy.getTotal();
        }
        return buy.getQuantity().subtract(executed).max(BigDecimal.ZERO)
                .multiply(buy.getPrice()).setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * Foto do estado de exposicao do runner no momento do tick.
     *
     * @param openPositions    lotes OPEN do runner — limite {@code maxOpenPositions} e politica SINGLE
     * @param inFlightOrders   ordens em voo (BUY e SELL) — limite {@code maxPendingOrders}
     * @param exposure         custo do lote ativo + parte nao executada das BUY em voo
     * @param oldestInFlightAt envio da ordem em voo mais antiga, {@code null} sem ordem
     */
    public record ExposureSnapshot(int openPositions, int inFlightOrders, BigDecimal exposure,
                                   Instant oldestInFlightAt) {

        /** Ao menos uma posicao aberta ou ordem em voo — usado pela politica SINGLE. */
        public boolean hasOpenOrInFlight() {
            return openPositions > 0 || inFlightOrders > 0;
        }
    }
}
