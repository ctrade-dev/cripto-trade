package com.marmitt.application.spring.infrastructure.persistence.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marmitt.application.spring.infrastructure.persistence.entity.RunnerMarketDataSourceEntity;
import com.marmitt.application.spring.infrastructure.persistence.entity.RunnerPositionEntity;
import com.marmitt.application.spring.infrastructure.persistence.entity.RunnerTransactionEntity;
import com.marmitt.application.spring.infrastructure.persistence.entity.RunnerTransactionMatchEntity;
import com.marmitt.application.spring.infrastructure.persistence.entity.StrategyRunnerEntity;
import com.marmitt.core.domain.runner.LiquidationPolicy;
import com.marmitt.core.domain.runner.Position;
import com.marmitt.core.domain.runner.PositionProtection;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.domain.runner.TransactionMatch;
import com.marmitt.core.domain.shared.Fee;
import com.marmitt.core.enums.FeeType;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class StrategyRunnerEntityMapper {

    /** So para o metadata da decisao (T74): mapa JSON livre, sem tipos do dominio. */
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> METADATA = new TypeReference<>() {
    };

    // ── StrategyRunner ──────────────────────────────────────────────────────

    public static StrategyRunnerEntity toEntity(StrategyRunner domain) {
        return StrategyRunnerEntity.builder()
                .id(domain.getId())
                .portfolioId(domain.getPortfolioId())
                .shortCode(domain.getShortCode())
                .strategyId(domain.getStrategyId())
                .strategyName(domain.getStrategyName())
                .symbol(domain.getSymbol())
                .exchangeId(domain.getExchangeId())
                .allowedMarketDataSources(toSourceEntities(domain.getAllowedMarketDataSources()))
                .status(domain.getStatus())
                .executionPolicy(domain.getExecutionPolicy())
                .accountingPolicyType(domain.getAccountingPolicyType())
                .maxAllocationPercent(domain.getMaxAllocationPercent())
                .maxOpenPositions(domain.getMaxOpenPositions())
                .maxPendingOrders(domain.getMaxPendingOrders())
                .maxLossPercentPerLot(domain.getPositionProtection().maxLossPercentPerLot())
                .maxHoldingSeconds(domain.getPositionProtection().maxHoldingDuration() != null
                        ? domain.getPositionProtection().maxHoldingDuration().toSeconds() : null)
                .liquidationOrderType(domain.getLiquidationPolicy().orderType())
                .liquidationMaxSlippagePercent(domain.getLiquidationPolicy().maxSlippagePercent())
                .liquidationEscalationTicks(domain.getLiquidationPolicy().escalationTicks())
                .dedicatedBudget(domain.getDedicatedBudget())
                .isReconciling(domain.isReconciling())
                .createdAt(domain.getCreatedAt())
                .statusChangedAt(domain.getStatusChangedAt())
                .lastReconciliationAt(domain.getLastReconciliationAt())
                .archivedAt(domain.getArchivedAt())
                .version(domain.getVersion())
                .build();
    }

    public static StrategyRunner toDomain(StrategyRunnerEntity entity) {
        return new StrategyRunner(
                entity.getId(),
                entity.getPortfolioId(),
                entity.getShortCode(),
                entity.getStrategyId(),
                entity.getStrategyName(),
                entity.getSymbol(),
                entity.getExchangeId(),
                fromSourceEntities(entity.getAllowedMarketDataSources()),
                entity.getStatus(),
                entity.getExecutionPolicy(),
                entity.getAccountingPolicyType(),
                entity.getMaxAllocationPercent(),
                entity.getMaxOpenPositions(),
                entity.getMaxPendingOrders(),
                entity.getDedicatedBudget(),
                new PositionProtection(entity.getMaxLossPercentPerLot(),
                        entity.getMaxHoldingSeconds() != null ? Duration.ofSeconds(entity.getMaxHoldingSeconds()) : null),
                new LiquidationPolicy(entity.getLiquidationOrderType(), entity.getLiquidationMaxSlippagePercent(),
                        entity.getLiquidationEscalationTicks()),
                entity.isReconciling(),
                entity.getCreatedAt(),
                entity.getStatusChangedAt(),
                entity.getLastReconciliationAt(),
                entity.getArchivedAt(),
                entity.getVersion()
        );
    }

    // ── Position ────────────────────────────────────────────────────────────

    public static RunnerPositionEntity toEntity(Position domain) {
        return RunnerPositionEntity.builder()
                .id(domain.getId())
                .runnerId(domain.getRunnerId())
                .symbol(domain.getSymbol())
                .status(domain.getStatus())
                .quantity(domain.getQuantity())
                .averagePrice(domain.getAveragePrice())
                .costBasis(domain.getCostBasis())
                .currentPrice(domain.getCurrentPrice())
                .realizedPnl(domain.getRealizedPnl())
                .openedAt(domain.getOpenedAt())
                .closedAt(domain.getClosedAt())
                .updatedAt(domain.getUpdatedAt())
                .openedByTransactionId(domain.getOpenedByTransactionId())
                .lockedByTransactionId(domain.getLockedByTransactionId())
                .lockedQuantity(domain.getLockedQuantity())
                .lockedAt(domain.getLockedAt())
                .version(domain.getVersion())
                .build();
    }

    public static Position toDomain(RunnerPositionEntity entity) {
        return Position.reconstitute()
                .id(entity.getId())
                .runnerId(entity.getRunnerId())
                .symbol(entity.getSymbol())
                .status(entity.getStatus())
                .quantity(entity.getQuantity())
                .averagePrice(entity.getAveragePrice())
                .costBasis(entity.getCostBasis())
                .currentPrice(entity.getCurrentPrice())
                .realizedPnl(entity.getRealizedPnl())
                .openedAt(entity.getOpenedAt())
                .closedAt(entity.getClosedAt())
                .updatedAt(entity.getUpdatedAt())
                .lockedByTransactionId(entity.getLockedByTransactionId())
                .lockedQuantity(entity.getLockedQuantity())
                .lockedAt(entity.getLockedAt())
                .openedByTransactionId(entity.getOpenedByTransactionId())
                .version(entity.getVersion())
                .build();
    }

    // ── Transaction ─────────────────────────────────────────────────────────

    public static RunnerTransactionEntity toEntity(Transaction domain) {
        return RunnerTransactionEntity.builder()
                .id(domain.getId())
                .runnerId(domain.getRunnerId())
                .clientOrderId(domain.getClientOrderId())
                .exchangeOrderId(domain.getExchangeOrderId())
                .status(domain.getStatus())
                .type(domain.getType())
                .orderType(domain.getOrderType())
                .symbol(domain.getSymbol())
                .quantity(domain.getQuantity())
                .executedQuantity(domain.getExecutedQuantity())
                .price(domain.getPrice())
                .executedPrice(domain.getExecutedPrice())
                .total(domain.getTotal())
                .confidence(domain.getConfidence())
                .reasoning(domain.getReasoning())
                .targetLotId(domain.getTargetLotId())
                .strategyMetadata(metadataJson(domain.getStrategyMetadata()))
                .requestedAt(domain.getRequestedAt())
                .updatedAt(domain.getUpdatedAt())
                .executedAt(domain.getExecutedAt())
                .lastPartialFillAt(domain.getLastPartialFillAt())
                .rejectReason(domain.getRejectReason())
                .version(domain.getVersion())
                .build();
    }

    public static Transaction toDomain(RunnerTransactionEntity entity) {
        return Transaction.reconstitute()
                .id(entity.getId())
                .runnerId(entity.getRunnerId())
                .clientOrderId(entity.getClientOrderId())
                .exchangeOrderId(entity.getExchangeOrderId())
                .status(entity.getStatus())
                .type(entity.getType())
                .orderType(entity.getOrderType())
                .symbol(entity.getSymbol())
                .quantity(entity.getQuantity())
                .executedQuantity(entity.getExecutedQuantity())
                .price(entity.getPrice())
                .executedPrice(entity.getExecutedPrice())
                .total(entity.getTotal())
                .confidence(entity.getConfidence())
                .reasoning(entity.getReasoning())
                .targetLotId(entity.getTargetLotId())
                .strategyMetadata(metadataMap(entity.getStrategyMetadata()))
                .requestedAt(entity.getRequestedAt())
                .updatedAt(entity.getUpdatedAt())
                .executedAt(entity.getExecutedAt())
                .lastPartialFillAt(entity.getLastPartialFillAt())
                .rejectReason(entity.getRejectReason())
                .version(entity.getVersion())
                .build();
    }

    // ── TransactionMatch ─────────────────────────────────────────────────────

    public static RunnerTransactionMatchEntity toEntity(TransactionMatch domain) {
        Fee fee = domain.fee();
        return RunnerTransactionMatchEntity.builder()
                .id(domain.id())
                .runnerId(domain.runnerId())
                .buyTransactionId(domain.buyTransactionId())
                .sellTransactionId(domain.sellTransactionId())
                .matchedQuantity(domain.matchedQuantity())
                .buyPrice(domain.buyPrice())
                .sellPrice(domain.sellPrice())
                .pnlRealized(domain.pnlRealized())
                .feeAmount(fee.amount())
                .feeAsset(fee.asset())
                .feeType(fee.type())
                .feeConvertedAmount(fee.convertedAmount())
                .createdAt(domain.createdAt())
                .build();
    }

    public static TransactionMatch toDomain(RunnerTransactionMatchEntity entity) {
        Fee fee = new Fee(
                entity.getFeeAmount(),
                entity.getFeeAsset(),
                entity.getFeeType() != null ? entity.getFeeType() : FeeType.UNKNOWN,
                entity.getFeeConvertedAmount()
        );
        return new TransactionMatch(
                entity.getId(),
                entity.getRunnerId(),
                entity.getBuyTransactionId(),
                entity.getSellTransactionId(),
                entity.getMatchedQuantity(),
                entity.getBuyPrice(),
                entity.getSellPrice(),
                fee,
                entity.getPnlRealized(),
                entity.getCreatedAt()
        );
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Set<RunnerMarketDataSourceEntity> toSourceEntities(Set<String> sources) {
        if (sources == null) return Collections.emptySet();
        return sources.stream()
                .map(s -> new RunnerMarketDataSourceEntity(s.toUpperCase()))
                .collect(Collectors.toSet());
    }

    private static Set<String> fromSourceEntities(Set<RunnerMarketDataSourceEntity> entities) {
        if (entities == null) return Collections.emptySet();
        return entities.stream()
                .map(RunnerMarketDataSourceEntity::source)
                .collect(Collectors.toSet());
    }

    private static String metadataJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("strategy metadata is not serializable: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> metadataMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, METADATA);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored strategy metadata is not valid JSON: " + e.getMessage(), e);
        }
    }
}
