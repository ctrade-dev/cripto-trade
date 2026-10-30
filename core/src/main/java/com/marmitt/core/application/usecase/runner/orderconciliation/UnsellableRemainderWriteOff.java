package com.marmitt.core.application.usecase.runner.orderconciliation;

import com.marmitt.core.domain.runner.Position;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.domain.shared.Fee;
import com.marmitt.core.dto.capital.ExecutionConfirmation;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Baixa como perda a quantidade que sobrou num lote destravado e que a exchange nao aceita vender (T46).
 *
 * <p>Vale para os dois jeitos de uma SELL terminar com resto no lote: o fill final de uma venda menor
 * que o lote ({@link SellFillHandler}) e a venda interrompida depois de executar em parte
 * ({@link TerminationHandler}, T67). Sem a baixa, o lote voltaria a {@code OPEN} com uma quantidade
 * que nenhuma ordem consegue vender.
 *
 * <p>O custo do residuo sai de {@code reserved} por um {@link ExecutionConfirmation} com PnL igual a
 * menos o custo, com {@code matchId} deterministico por posicao para idempotencia.
 */
@Slf4j
class UnsellableRemainderWriteOff {

    private final StrategyRunnerRepositoryPort strategyRunnerRepository;
    private final SellableQuantityPolicy sellableQuantityPolicy;

    UnsellableRemainderWriteOff(StrategyRunnerRepositoryPort strategyRunnerRepository,
                                SellableQuantityPolicy sellableQuantityPolicy) {
        this.strategyRunnerRepository = strategyRunnerRepository;
        this.sellableQuantityPolicy = sellableQuantityPolicy;
    }

    /**
     * @return confirmacao a publicar para tirar o custo do residuo de {@code reserved}, ou vazio
     *         quando a posicao fechou, segue travada ou o restante ainda e vendavel
     */
    Optional<ExecutionConfirmation> apply(Transaction transaction, Position position, String quoteAsset) {
        BigDecimal remaining = position.getQuantity();
        if (remaining.signum() <= 0 || position.isLocked()) {
            return Optional.empty();
        }
        String exchangeId = strategyRunnerRepository.findById(transaction.getRunnerId())
                .map(runner -> runner.getExchangeId())
                .orElse(null);
        if (sellableQuantityPolicy.isSellable(exchangeId, position.getSymbol(), remaining)) {
            return Optional.empty();
        }
        BigDecimal averagePrice = position.getAveragePrice();
        BigDecimal dustCost = position.writeOffDust().setScale(8, RoundingMode.HALF_UP);
        log.info("orderConciliation: unsellable remainder written off positionId={} transactionId={} quantity={} cost={}",
                position.getId(), transaction.getId(), remaining, dustCost);
        if (dustCost.signum() <= 0) {
            return Optional.empty();
        }
        UUID writeOffId = UUID.nameUUIDFromBytes(
                ("dust-write-off:" + position.getId()).getBytes(StandardCharsets.UTF_8));
        return Optional.of(new ExecutionConfirmation(
                transaction.getId(),
                transaction.getRunnerId(),
                writeOffId,
                remaining,
                averagePrice,
                Fee.zero(quoteAsset != null ? quoteAsset : "UNKNOWN"),
                dustCost,
                dustCost.negate(),
                true
        ));
    }
}
