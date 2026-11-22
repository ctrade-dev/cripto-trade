package com.marmitt.core.domain.runner;

import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.enums.TransactionType;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Ordem de compra/venda gerenciada pelo StrategyRunner.
 * Migra do Portfolio para o StrategyRunner — a FK principal passa de
 * {@code portfolioId} para {@code runnerId}.
 * <p>
 * <b>Simplificação Asset → BigDecimal:</b> campos numéricos (quantity, price, total)
 * usam {@code BigDecimal} puro. A moeda é inferida do {@code symbol} (base/quote asset).
 * <p>
 * <b>Fee removida:</b> a fee deixa de ser campo da Transaction e passa a ser VO embutido
 * em cada {@code TransactionMatch} — fees existem apenas onde há execução real.
 * <p>
 * <b>Campos de auditoria:</b> {@code confidence} e {@code reasoning} registram a
 * motivação do sinal da estratégia para rastreabilidade (Blueprint 4.A).
 *
 * @see <a href="docs/IMPLEMENTATION_GUIDE.md">IG Seção 3.3.3, Blueprint 4, 6.A, 10.1</a>
 */
@Getter
public class Transaction {

    private final UUID id;
    private final UUID runnerId;

    /**
     * Chave de idempotência e roteamento. Formato: {@code v1r{shortCode}t{ts}s{seq}{type}_{uuid}}.
     * NOT NULL, UNIQUE.
     */
    private final String clientOrderId;

    /**
     * ID atribuído pela exchange após aceite da ordem. Null se crash antes do dispatch.
     */
    private String exchangeOrderId;

    private TransactionStatus status;
    private final TransactionType type;

    /**
     * Tipo da ordem enviada a exchange (T45). {@code LIMIT} para sinais da estrategia;
     * {@code MARKET} para a liquidacao do Safe Mode {@code PANIC_SELL}. Em MARKET, {@link #price}
     * e apenas o preco de referencia do tick; o valor real vem dos fills.
     */
    private final OrderType orderType;

    /** Par de trading (ex: "BTCUSDT"). */
    private final String symbol;

    /** Quantidade solicitada (em base asset). */
    private final BigDecimal quantity;

    /** Quantidade efetivamente executada acumulada (em base asset). Null enquanto PENDING/SUBMITTED. */
    private BigDecimal executedQuantity;

    /** Preço solicitado/estimado (em quote asset). */
    private final BigDecimal price;

    /** Preço médio ponderado de execução (em quote asset). Null enquanto PENDING/SUBMITTED. */
    private BigDecimal executedPrice;

    /**
     * Valor total estimado: {@code quantity × price} (em quote asset).
     * O caller é responsável pelo arredondamento antes de passar este valor
     * (convenção: {@code setScale(8, RoundingMode.HALF_UP)}).
     */
    private final BigDecimal total;

    /**
     * Confiança do sinal da estratégia (0.0–1.0). Nullable.
     * Registrado para auditoria e ajuste futuro de modelos.
     */
    private final BigDecimal confidence;

    /**
     * Motivação textual do sinal da estratégia. Nullable.
     * Registrado para auditoria (Blueprint 4.A).
     */
    private final String reasoning;

    /**
     * Para SELL: UUID da {@code Position} específica a fechar.
     * Null = AccountingPolicy decide (FIFO/LIFO).
     */
    private final UUID targetLotId;

    /**
     * {@code metadata} da decisao da estrategia que originou a ordem (T74), como veio no contrato: ex.
     * {@code exitKind} e {@code stop}. Vazio nas ordens do proprio ctrade (liquidacao). Registrado para auditoria e
     * para comparar a operacao com o backtest da estrategia; o ctrade nao decide nada por ele.
     */
    private final Map<String, Object> strategyMetadata;

    private final Instant requestedAt;
    private Instant updatedAt;
    private Instant executedAt;
    /**
     * Timestamp of the most recent partial fill. Set by {@link #partialFill} and NOT overwritten
     * by {@link #cancel()}, so it survives PARTIAL → CANCELED transitions intact.
     * Used as the time-filter anchor for CANCELED transactions in reconciliation queries.
     */
    private Instant lastPartialFillAt;
    private String rejectReason;
    private Long version;

    /**
     * Construtor para criação de nova Transaction (status inicial = PENDING).
     */
    public Transaction(
            UUID runnerId,
            String clientOrderId,
            TransactionType type,
            OrderType orderType,
            String symbol,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal total,
            BigDecimal confidence,
            String reasoning,
            UUID targetLotId,
            Map<String, Object> strategyMetadata
    ) {
        this.id = UUID.randomUUID();
        this.runnerId = Objects.requireNonNull(runnerId, "runnerId cannot be null");
        this.clientOrderId = Objects.requireNonNull(clientOrderId, "clientOrderId cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.orderType = Objects.requireNonNull(orderType, "orderType cannot be null");
        this.symbol = requireNonBlank(symbol, "symbol");
        this.quantity = requirePositive(quantity, "quantity");
        this.price = requirePositive(price, "price");
        this.total = requirePositive(total, "total");
        this.confidence = validateConfidence(confidence);
        this.reasoning = reasoning;
        this.targetLotId = targetLotId;
        this.strategyMetadata = copyOf(strategyMetadata);
        this.status = TransactionStatus.PENDING;
        this.requestedAt = Instant.now();
        this.updatedAt = this.requestedAt;
        this.version = null;
    }

    /**
     * Construtor completo para reconstituição a partir do banco de dados.
     * Use {@code Transaction.reconstitute().id(...).build()} via Builder gerado.
     */
    @Builder(builderMethodName = "reconstitute")
    public Transaction(
            UUID id,
            UUID runnerId,
            String clientOrderId,
            String exchangeOrderId,
            TransactionStatus status,
            TransactionType type,
            OrderType orderType,
            String symbol,
            BigDecimal quantity,
            BigDecimal executedQuantity,
            BigDecimal price,
            BigDecimal executedPrice,
            BigDecimal total,
            BigDecimal confidence,
            String reasoning,
            UUID targetLotId,
            Map<String, Object> strategyMetadata,
            Instant requestedAt,
            Instant updatedAt,
            Instant executedAt,
            Instant lastPartialFillAt,
            String rejectReason,
            Long version
    ) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.runnerId = Objects.requireNonNull(runnerId, "runnerId cannot be null");
        this.clientOrderId = Objects.requireNonNull(clientOrderId, "clientOrderId cannot be null");
        this.exchangeOrderId = exchangeOrderId;
        this.status = Objects.requireNonNull(status, "status cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.orderType = Objects.requireNonNull(orderType, "orderType cannot be null");
        this.symbol = Objects.requireNonNull(symbol, "symbol cannot be null");
        this.quantity = Objects.requireNonNull(quantity, "quantity cannot be null");
        this.executedQuantity = executedQuantity;
        this.price = Objects.requireNonNull(price, "price cannot be null");
        this.executedPrice = executedPrice;
        this.total = Objects.requireNonNull(total, "total cannot be null");
        this.confidence = confidence;
        this.reasoning = reasoning;
        this.targetLotId = targetLotId;
        this.strategyMetadata = copyOf(strategyMetadata);
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt cannot be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt cannot be null");
        this.executedAt = executedAt;
        this.lastPartialFillAt = lastPartialFillAt;
        this.rejectReason = rejectReason;
        this.version = version;
    }

    // ============================================================
    // Status transitions
    // ============================================================

    /**
     * Exchange aceitou a ordem — registra o {@code exchangeOrderId}.
     * Transição: PENDING → SUBMITTED.
     */
    public void submit(String exchangeOrderId) {
        requireStatus(TransactionStatus.PENDING, "submit");
        this.exchangeOrderId = Objects.requireNonNull(exchangeOrderId, "exchangeOrderId cannot be null");
        this.status = TransactionStatus.SUBMITTED;
        this.updatedAt = Instant.now();
    }

    /**
     * Execução parcial recebida. Atualiza quantidade e preço médio acumulados.
     * Transição: SUBMITTED | PARTIAL → PARTIAL.
     *
     * @param cumulativeQty   quantidade total executada até agora (acumulada, em base asset)
     * @param avgExecutedPrice preço médio ponderado acumulado (em quote asset)
     */
    public void partialFill(BigDecimal cumulativeQty, BigDecimal avgExecutedPrice) {
        partialFill(cumulativeQty, avgExecutedPrice, Instant.now());
    }

    public void partialFill(BigDecimal cumulativeQty, BigDecimal avgExecutedPrice, Instant fillAt) {
        if (this.status != TransactionStatus.SUBMITTED && this.status != TransactionStatus.PARTIAL) {
            throw new IllegalStateException(
                    "Cannot apply partial fill from status: " + this.status);
        }
        requirePositive(cumulativeQty, "cumulativeQty");
        requirePositive(avgExecutedPrice, "avgExecutedPrice");

        this.executedQuantity = cumulativeQty;
        this.executedPrice = avgExecutedPrice;
        this.status = TransactionStatus.PARTIAL;
        this.updatedAt = Instant.now();
        this.lastPartialFillAt = fillAt;
    }

    /**
     * Execução total concluída.
     * Transição: SUBMITTED | PARTIAL → FILLED.
     *
     * @param cumulativeQty   quantidade total executada (deve igualar {@code quantity})
     * @param avgExecutedPrice preço médio ponderado final (em quote asset)
     */
    public void fill(BigDecimal cumulativeQty, BigDecimal avgExecutedPrice) {
        fill(cumulativeQty, avgExecutedPrice, Instant.now());
    }

    public void fill(BigDecimal cumulativeQty, BigDecimal avgExecutedPrice, Instant fillAt) {
        if (this.status != TransactionStatus.SUBMITTED && this.status != TransactionStatus.PARTIAL) {
            throw new IllegalStateException(
                    "Cannot apply fill from status: " + this.status);
        }
        requirePositive(cumulativeQty, "cumulativeQty");
        requirePositive(avgExecutedPrice, "avgExecutedPrice");

        this.executedQuantity = cumulativeQty;
        this.executedPrice = avgExecutedPrice;
        this.executedAt = fillAt;
        this.status = TransactionStatus.FILLED;
        this.updatedAt = Instant.now();
    }

    /**
     * Ordem cancelada (pelo Runner, operador ou exchange).
     * Transição: PENDING | SUBMITTED | PARTIAL → CANCELED.
     */
    public void cancel() {
        if (this.status == TransactionStatus.FILLED || this.status.isFinal()) {
            throw new IllegalStateException(
                    "Cannot cancel from status: " + this.status);
        }
        this.status = TransactionStatus.CANCELED;
        this.updatedAt = Instant.now();
    }

    /**
     * Ordem expirou (timeout do Watchdog ou intenção não materializada após crash).
     * Transição: PENDING | SUBMITTED → EXPIRED.
     */
    public void expire() {
        if (this.status != TransactionStatus.PENDING && this.status != TransactionStatus.SUBMITTED) {
            throw new IllegalStateException(
                    "Cannot expire from status: " + this.status);
        }
        this.status = TransactionStatus.EXPIRED;
        this.updatedAt = Instant.now();
    }

    /**
     * Ordem rejeitada (Portfolio negou Capital Request ou exchange rejeitou).
     * Transição: PENDING | SUBMITTED → REJECTED.
     *
     * @param reason motivo da rejeição (não pode ser null)
     */
    public void reject(String reason) {
        if (this.status != TransactionStatus.PENDING && this.status != TransactionStatus.SUBMITTED) {
            throw new IllegalStateException(
                    "Cannot reject from status: " + this.status);
        }
        this.rejectReason = Objects.requireNonNull(reason, "reason cannot be null");
        this.status = TransactionStatus.REJECTED;
        this.updatedAt = Instant.now();
    }

    // ============================================================
    // Query methods
    // ============================================================

    public boolean isBuy()  { return type == TransactionType.BUY; }
    public boolean isSell() { return type == TransactionType.SELL; }
    public boolean isPending()   { return status == TransactionStatus.PENDING; }
    public boolean isSubmitted() { return status == TransactionStatus.SUBMITTED; }
    public boolean isFinal()     { return status.isFinal(); }
    public boolean isFailed()    { return status.isFailed(); }

    /**
     * Quantidade executada ou ZERO se ainda não houve execução.
     */
    public BigDecimal getEffectiveExecutedQuantity() {
        return executedQuantity != null ? executedQuantity : BigDecimal.ZERO;
    }

    /**
     * Preço efetivo de execução ou preço solicitado como fallback.
     */
    public BigDecimal getEffectiveExecutedPrice() {
        return executedPrice != null ? executedPrice : price;
    }

    /**
     * Valor executado acumulado: {@code executedQuantity × executedPrice}.
     * Retorna ZERO se ainda não houve execução.
     */
    public BigDecimal getExecutedValue() {
        if (executedQuantity == null || executedPrice == null) {
            return BigDecimal.ZERO;
        }
        return executedQuantity.multiply(executedPrice).setScale(8, RoundingMode.HALF_UP);
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // ============================================================
    // Helpers
    // ============================================================

    private void requireStatus(TransactionStatus expected, String operation) {
        if (this.status != expected) {
            throw new IllegalStateException(
                    "Cannot " + operation + " from status: " + this.status + " (expected: " + expected + ")");
        }
    }

    private static BigDecimal requirePositive(BigDecimal value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " cannot be null");
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }

    private static BigDecimal validateConfidence(BigDecimal confidence) {
        if (confidence == null) return null;
        if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
        }
        return confidence;
    }

    /** Copia imutavel; aceita valores nulos (JSON {@code null}) e mantem a ordem das chaves. */
    private static Map<String, Object> copyOf(Map<String, Object> metadata) {
        return metadata == null || metadata.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
