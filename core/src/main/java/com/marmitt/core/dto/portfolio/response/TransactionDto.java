package com.marmitt.core.dto.portfolio.response;

import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.enums.TransactionType;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Builder
public record TransactionDto(
        UUID id,
        UUID runnerId,
        String clientOrderId,
        TransactionStatus status,
        TransactionType type,
        String symbol,

        // Valores solicitados
        BigDecimal quantity,
        String quantityCurrency,
        BigDecimal price,
        String priceCurrency,
        BigDecimal total,

        // Valores executados (pode ser diferente do solicitado)
        BigDecimal executedQuantity,
        BigDecimal executedPrice,
        BigDecimal fee,

        // Timestamps
        Instant requestedAt,
        Instant executedAt,

        // Rejeição
        String rejectReason,

        // Decisao da estrategia (T74): motivo, lote alvo da SELL e metadata do contrato (ex.: exitKind, stop)
        String reasoning,
        UUID targetLotId,
        Map<String, Object> strategyMetadata
) {

    public static TransactionDto fromDomain(Transaction transaction) {
        return TransactionDto.builder()
                .id(transaction.getId())
                .runnerId(transaction.getRunnerId())
                .clientOrderId(transaction.getClientOrderId())
                .status(transaction.getStatus())
                .type(transaction.getType())
                .symbol(transaction.getSymbol())
                .quantity(transaction.getQuantity())
                .price(transaction.getPrice())
                .total(transaction.getTotal())
                .executedQuantity(transaction.getExecutedQuantity())
                .executedPrice(transaction.getExecutedPrice())
                .requestedAt(transaction.getRequestedAt())
                .executedAt(transaction.getExecutedAt())
                .rejectReason(transaction.getRejectReason())
                .reasoning(transaction.getReasoning())
                .targetLotId(transaction.getTargetLotId())
                .strategyMetadata(transaction.getStrategyMetadata())
                .build();
    }
}

