package com.marmitt.core.application.usecase.runner.processsignal;

import com.marmitt.core.domain.runner.ClientOrderId;
import com.marmitt.core.domain.runner.Position;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.capital.BuyExecutionContext;
import com.marmitt.core.dto.capital.CapitalRequest;
import com.marmitt.core.dto.runner.OrderCancelCommand;
import com.marmitt.core.dto.runner.OrderDispatchCommand;
import com.marmitt.core.dto.strategy.StrategyInputDto;
import com.marmitt.core.dto.strategy.StrategyOutputDto;
import com.marmitt.core.dto.websocket.data.MarketDataDto;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.OrderSide;
import com.marmitt.core.enums.TradingAction;
import com.marmitt.core.enums.TransactionType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;

/**
 * Fabrica responsavel por construir todos os objetos que representam a "intencao de trade"
 * ao longo do pipeline: do input da estrategia ate o comando de dispatch para a exchange.
 *
 * <p>Centraliza a construcao em um unico lugar para manter o orquestrador e os handlers
 * livres de logica de montagem. Cada metodo produz um objeto especifico para uma etapa:
 * <ol>
 *   <li>{@link #buildStrategyInput} — adaptacao do tick para o contrato da estrategia.</li>
 *   <li>{@link #buildTransaction} — materializacao da intencao como registro local PENDING,
 *       com clientOrderId unico gerado para roteamento de callbacks da exchange.</li>
 *   <li>{@link #buildCapitalRequest} — pedido de reserva de capital para ordens BUY.</li>
 *   <li>{@link #buildBuyExecutionContext} — agrupamento de todos os artefatos do ramo BUY
 *       em um unico objeto para facilitar passagem entre camadas.</li>
 *   <li>{@link #buildDispatchCommand} — comando de envio para o adapter de exchange.</li>
 * </ol>
 */
class TradeIntentFactory {

    /**
     * Adapta o tick de market data para o contrato {@link StrategyInputDto} esperado
     * pela estrategia. Permite que a estrategia opere sem dependencia direta do DTO
     * de transporte WebSocket.
     */
    public StrategyInputDto buildStrategyInput(MarketDataDto marketData) {
        return StrategyInputDto.builder()
                .symbol(marketData.symbol())
                .currentPrice(marketData.price())
                .bidPrice(marketData.bidPrice())
                .askPrice(marketData.askPrice())
                .volume(marketData.volume())
                .high24h(marketData.high24h())
                .low24h(marketData.low24h())
                .timestamp(marketData.timestamp())
                .build();
    }

    /**
     * Cria a {@link Transaction} local representando a intencao de trade com status PENDING.
     *
     * <p>O {@code clientOrderId} gerado aqui e a chave de roteamento que permite ao sistema
     * reconciliar os callbacks assincronos da exchange com a transacao correta no banco.
     * O total e calculado como {@code quantity × price} e usado como valor de reserva de
     * capital — o valor executado real sera atualizado ao receber os fills.
     *
     * <p>A {@code quantity} e o {@code price} recebidos ja devem estar normalizados as regras
     * de filtro da exchange (ver {@link OrderNormalizer}), garantindo que o valor persistido e
     * reservado seja identico ao enviado para a exchange.
     */
    /**
     * Lado da ordem derivado da decisao da estrategia. Usado antes de existir a transacao
     * (ex.: normalizacao de preco por lado, T37).
     */
    public OrderSide orderSideOf(StrategyOutputDto signal) {
        return signal.decision() == TradingAction.SHOULD_BUY ? OrderSide.BUY : OrderSide.SELL;
    }

    public Transaction buildTransaction(StrategyRunner runner, StrategyOutputDto signal, OrderType orderType,
                                        BigDecimal quantity, BigDecimal price) {
        TransactionType type = signal.decision() == TradingAction.SHOULD_BUY
                ? TransactionType.BUY : TransactionType.SELL;
        String clientOrderId = ClientOrderId.generate(runner.getShortCode(), type);
        BigDecimal total = quantity.multiply(price).setScale(8, RoundingMode.HALF_UP);

        return new Transaction(
                runner.getId(),
                clientOrderId,
                type,
                orderType,
                runner.getSymbol(),
                quantity,
                price,
                total,
                signal.confidence(),
                signal.reasoning(),
                signal.targetLotId(),
                signal.metadata()
        );
    }

    /**
     * Constroi o pedido de reserva de capital para uma ordem BUY.
     * O valor reservado e o total estimado da transacao; eventuais diferenca para o
     * valor executado real serao devolvidas ao saldo pelo fluxo de conciliacao.
     */
    public CapitalRequest buildCapitalRequest(StrategyRunner runner, Transaction transaction) {
        return new CapitalRequest(
                transaction.getId(),
                runner.getId(),
                runner.getShortCode(),
                runner.getSymbol(),
                transaction.getTotal(),
                transaction.getType()
        );
    }

    /**
     * Agrupa transacao, pedido de capital e runner em um {@link BuyExecutionContext}
     * para passagem entre o orquestrador e os handlers sem multiplos parametros avulsos.
     * O {@code precomputedExposure} evita nova consulta ao banco quando o snapshot ja
     * foi carregado pela policy SINGLE.
     */
    public BuyExecutionContext buildBuyExecutionContext(StrategyRunner runner,
                                                        Transaction transaction,
                                                        BigDecimal precomputedExposure) {
        return new BuyExecutionContext(
                transaction,
                buildCapitalRequest(runner, transaction),
                runner,
                precomputedExposure
        );
    }

    /**
     * Sinal de venda do lote inteiro, executado a {@link #liquidationOrderType()}. Fonte unica das saidas
     * que o core decide sem a estrategia: Safe Mode PANIC_SELL (T45), fechar posicao pelo admin (T49) e
     * protecao do lote (T44). Segue o pipeline normal de SELL (normalizacao, lock, persistencia, dispatch).
     *
     * @param source nome registrado como estrategia de origem da transacao
     */
    public StrategyOutputDto liquidationSignal(String source, Position position, String reasoning) {
        return liquidationSignal(source, position, null, reasoning);
    }

    /**
     * Sinal de venda do lote inteiro com preco-limite: a SELL LIMIT do PANIC_SELL com politica de
     * liquidacao LIMIT (T48). {@code limitPrice} nulo usa o preco do tick, como nas saidas a mercado.
     */
    public StrategyOutputDto liquidationSignal(String source, Position position, BigDecimal limitPrice,
                                               String reasoning) {
        return new StrategyOutputDto(source, TradingAction.SHOULD_SELL, BigDecimal.ONE,
                position.getQuantity(), limitPrice, position.getId(), null, reasoning, Instant.now(), Map.of());
    }

    /** Saida decidida pelo core vende a mercado: certeza de execucao acima de preco (T45, T44). */
    public OrderType liquidationOrderType() {
        return OrderType.MARKET;
    }

    /**
     * Comando de cancelamento de uma ordem em voo do runner. Fonte unica para o SHOULD_CANCEL da
     * estrategia e para o Safe Mode (T45).
     */
    public OrderCancelCommand buildCancelCommand(StrategyRunner runner, Transaction transaction) {
        return new OrderCancelCommand(
                transaction.getClientOrderId(), runner.getId(), runner.getSymbol(), runner.getExchangeId());
    }

    /**
     * Constroi o comando de envio para o adapter de exchange.
     * Contem apenas os dados necessarios para o dispatch: nao expoe internos do dominio
     * ao adapter, mantendo o desacoplamento entre nucleo e infraestrutura.
     */
    public OrderDispatchCommand buildDispatchCommand(StrategyRunner runner, Transaction transaction) {
        return new OrderDispatchCommand(
                transaction.getClientOrderId(),
                runner.getId(),
                runner.getSymbol(),
                runner.getExchangeId(),
                transaction.getType(),
                transaction.getOrderType(),
                transaction.getQuantity(),
                transaction.getPrice()
        );
    }
}
