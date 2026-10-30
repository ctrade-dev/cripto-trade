package com.marmitt.core.application.usecase.runner.orderconciliation;

import com.marmitt.core.domain.runner.Position;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.domain.runner.TransactionMatch;
import com.marmitt.core.domain.shared.Fee;
import com.marmitt.core.dto.capital.ExecutionConfirmation;
import com.marmitt.core.dto.events.ExecutionConfirmedEvent;
import com.marmitt.core.dto.websocket.data.OrderDataDto;
import com.marmitt.core.enums.FeeType;
import com.marmitt.core.exceptions.MissingPositionOriginException;
import com.marmitt.core.ports.outbound.events.EventPublisherPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

/**
 * Aplica o efeito de um fill de venda: reduz a posicao, registra o resultado
 * da operacao e publica o evento de confirmacao de execucao para o sistema de P&L.
 *
 * <p>Um fill SELL aciona a seguinte sequencia:
 * <ol>
 *   <li><b>Resolucao da posicao alvo:</b> usa o {@code targetLotId} da transacao se
 *       disponivel (venda de lote especifico), ou localiza a posicao aberta do runner
 *       para o simbolo (politica implicita).</li>
 *   <li><b>Reducao da posicao:</b> decrementa a quantidade da posicao pelo
 *       {@code fillIncrement} e atualiza o preco medio. O valor da fee e subtraido
 *       do retorno liquido da venda.</li>
 *   <li><b>Unlock no fill final:</b> se o fill e terminal ({@code isFinal = true}),
 *       a posicao e desbloqueada — ela pode ter nova SELL emitida ou ser encerrada
 *       completamente.</li>
 *   <li><b>Criacao do {@link com.marmitt.core.domain.runner.TransactionMatch}:</b>
 *       registra o par BUY↔SELL com preco de compra, preco de venda, quantidade e fee,
 *       calculando o PnL realizado da operacao.</li>
 *   <li><b>Publicacao do {@link com.marmitt.core.dto.events.ExecutionConfirmedEvent}:</b>
 *       notifica o sistema de capital para liberar ou contabilizar o resultado no
 *       {@link com.marmitt.core.domain.portfolio.GlobalBalance}.</li>
 * </ol>
 *
 * <p><b>Fee e custo (T46):</b> a fee e classificada pelo ativo ({@link FeeResolver}). Fee em quote
 * (padrao da Binance para SELL) entra no PnL; sem ativo informado, aplica-se o comportamento legado
 * (assumida em quote); em ativo base ou terceiro ativo (ex.: BNB) nao e suportada: fica registrada
 * no match como pendente de conversao, fora do PnL, com log ERROR. O custo baixado de
 * {@code reserved} vem do {@code costBasis} da posicao, nao de {@code quantidade x preco medio}.
 *
 * <p><b>Residuo invendavel (T46):</b> no fill final, se sobrar na posicao uma quantidade que a
 * exchange nao aceita vender, ela e baixada como perda ({@link UnsellableRemainderWriteOff}) e a
 * posicao fecha.
 *
 * <p><b>Invariante estrito:</b> posicoes OPEN/CLOSING devem possuir
 * {@code openedByTransactionId}. Se esse vinculo nao existir, o fluxo falha
 * explicitamente com excecao para evitar perda silenciosa de contabilidade.
 */
@Slf4j
class SellFillHandler {

    private final StrategyRunnerRepositoryPort strategyRunnerRepository;
    private final EventPublisherPort eventPublisher;
    private final FeeResolver feeResolver;
    private final UnsellableRemainderWriteOff remainderWriteOff;

    public SellFillHandler(StrategyRunnerRepositoryPort strategyRunnerRepository,
                           EventPublisherPort eventPublisher,
                           FeeResolver feeResolver,
                           UnsellableRemainderWriteOff remainderWriteOff) {
        this.strategyRunnerRepository = strategyRunnerRepository;
        this.eventPublisher = eventPublisher;
        this.feeResolver = feeResolver;
        this.remainderWriteOff = remainderWriteOff;
    }

    /**
     * Aplica o fill SELL na posicao, cria o match e publica o evento de confirmacao.
     *
     * @param transaction   transacao SELL no estado SUBMITTED ou PARTIAL
     * @param orderData     evento original da exchange com fee e simbolo
     * @param fillIncrement quantidade executada neste evento (marginal, nao cumulativa)
     * @param fillPrice     preco medio ponderado do incremento calculado por {@link FillCalculator}
     * @param isFinal       {@code true} se este e o ultimo fill da ordem (FILLED),
     *                      {@code false} se e uma execucao parcial (PARTIALLY_FILLED)
     * @throws IllegalStateException se nenhuma posicao aberta for encontrada para a venda
     */
    public void handle(Transaction transaction, OrderDataDto orderData,
                       BigDecimal fillIncrement, BigDecimal fillPrice, boolean isFinal) {
        Position position = resolvePosition(transaction)
                .orElseThrow(() -> new IllegalStateException(
                        "No position found for SELL fill transactionId=" + transaction.getId()));

        BigDecimal buyPrice = position.getAveragePrice();
        UUID buyTransactionId = position.getOpenedByTransactionId();

        String quoteAsset = orderData.symbol().getQuoteAsset();
        Fee fee = buildFee(transaction, orderData, quoteAsset);

        BigDecimal totalCost = position.reduceQuantity(fillIncrement, fillPrice, fee.getConvertedAmountOrZero())
                .setScale(8, RoundingMode.HALF_UP);

        if (isFinal) {
            position.unlockAfterFill();
            log.debug("orderConciliation: position unlocked after final SELL fill positionId={} transactionId={}",
                    position.getId(), transaction.getId());
        }

        if (buyTransactionId == null) {
            throw new MissingPositionOriginException(position.getId(), transaction.getId(), transaction.getRunnerId());
        }

        TransactionMatch match = TransactionMatch.create(
                transaction.getRunnerId(),
                buyTransactionId,
                transaction.getId(),
                fillIncrement,
                buyPrice,
                fillPrice,
                fee
        );

        ExecutionConfirmation confirmation = new ExecutionConfirmation(
                transaction.getId(),
                transaction.getRunnerId(),
                match.id(),
                fillIncrement,
                fillPrice,
                fee,
                totalCost,
                match.pnlRealized(),
                isFinal
        );

        strategyRunnerRepository.saveAtomicTransactionAndMatch(transaction, match);
        Optional<ExecutionConfirmation> dustWriteOff = isFinal
                ? remainderWriteOff.apply(transaction, position, quoteAsset)
                : Optional.empty();
        strategyRunnerRepository.savePosition(position);
        eventPublisher.publishEvent(new ExecutionConfirmedEvent(confirmation));
        dustWriteOff.ifPresent(writeOff -> eventPublisher.publishEvent(new ExecutionConfirmedEvent(writeOff)));
        log.info("orderConciliation: SELL match persisted matchId={} transactionId={} status={} executedQty={}",
                match.id(), transaction.getId(), isFinal ? "FILLED" : "PARTIAL", fillIncrement);
        log.info("orderConciliation: SELL fill matchId={} runnerId={} increment={} pnl={}",
                match.id(), transaction.getRunnerId(), fillIncrement, match.pnlRealized());
    }

    /**
     * Resolve a posicao alvo da venda: lote especifico via {@code targetLotId} tem precedencia;
     * senao, a posicao que esta transacao travou; por ultimo, a posicao aberta do runner no
     * simbolo (politica FIFO implicita).
     *
     * <p>A busca pela posicao travada por esta transacao e o que sustenta a SELL FIFO. Nesse caso
     * o sinal nao informa {@code targetLotId}, e o lock movimenta a posicao de OPEN para CLOSING —
     * entao a busca por posicao aberta nao a encontraria e o fill morreria com "No position found"
     * deixando SELL e lote em limbo. O {@code locked_by_transaction_id} e o vinculo autoritativo:
     * liquida exatamente o lote que esta venda reservou, nunca outro. Mesma razao pela qual
     * {@code TerminationHandler} ja considera posicoes em CLOSING ao desfazer o lock.
     */
    private Optional<Position> resolvePosition(Transaction transaction) {
        return Optional.ofNullable(transaction.getTargetLotId())
                .flatMap(strategyRunnerRepository::findPositionByIdForUpdate)
                .or(() -> strategyRunnerRepository
                        .findPositionLockedByTransactionIdForUpdate(transaction.getId()))
                .or(() -> strategyRunnerRepository.findOpenPositionByRunnerIdAndSymbolForUpdate(
                        transaction.getRunnerId(), transaction.getSymbol()));
    }

    private Fee buildFee(Transaction transaction, OrderDataDto orderData, String quoteAsset) {
        String quote = quoteAsset != null ? quoteAsset : "UNKNOWN";
        FeeResolver.ResolvedFee fee = feeResolver.resolve(orderData);
        return switch (fee.kind()) {
            case NONE -> Fee.zero(quote);
            // UNKNOWN: comportamento legado (fee assumida em quote) para produtores sem ativo.
            case QUOTE, UNKNOWN -> Fee.sameBaseCurrency(fee.amount(), quote, FeeType.UNKNOWN);
            case BASE, UNSUPPORTED -> {
                log.error("orderConciliation: unsupported SELL fee asset transactionId={} fee={} asset={} - recorded pending conversion, not in PnL (T46)",
                        transaction.getId(), fee.amount(), fee.asset());
                yield Fee.pendingConversion(fee.amount(), fee.asset(), FeeType.UNKNOWN);
            }
        };
    }
}
