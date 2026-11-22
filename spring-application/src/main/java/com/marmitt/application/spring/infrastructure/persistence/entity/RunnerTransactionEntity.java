package com.marmitt.application.spring.infrastructure.persistence.entity;

import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.enums.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de persistência para runner.Transaction.
 * Tabela separada de {@code transactions} (modelo legado de Portfolio).
 * Usa BigDecimal puro — sem AssetType, sem portfolioId.
 */
@Table("transactions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunnerTransactionEntity {

    @Id
    private UUID id;

    private UUID runnerId;
    private String clientOrderId;
    private String exchangeOrderId;

    private TransactionStatus status;
    private TransactionType type;
    private OrderType orderType;
    private String symbol;

    private BigDecimal quantity;
    private BigDecimal executedQuantity;
    private BigDecimal price;
    private BigDecimal executedPrice;
    private BigDecimal total;

    private BigDecimal confidence;
    private String reasoning;

    private UUID targetLotId;

    /** JSON do metadata da decisao da estrategia (T74); null quando vazio. */
    private String strategyMetadata;

    private Instant requestedAt;
    private Instant updatedAt;
    private Instant executedAt;
    private Instant lastPartialFillAt;
    private String rejectReason;

    @Version
    private Long version;
}
