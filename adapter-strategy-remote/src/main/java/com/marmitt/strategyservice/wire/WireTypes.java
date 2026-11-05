package com.marmitt.strategyservice.wire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Formato de rede do contrato do strategy service ({@code docs/strategy-service/openapi.yaml}, 2.0).
 *
 * <p>Espelha o spec campo a campo: decimais e instantes trafegam como {@code String} (decimal como
 * string, ISO-8601 UTC); UUIDs como string. A conversão para os DTOs do core fica no
 * {@link StrategyContractMapper} — nenhum tipo daqui entra no core.
 */
public final class WireTypes {

    private WireTypes() {
    }

    public record EvaluateRequest(String strategyRef, String tickId, MarketInput input, StrategyContext context) {
    }

    public record MarketInput(String symbol, String currentPrice, String previousPrice, String bidPrice,
                              String askPrice, String volume, String high24h, String low24h,
                              String timestamp, Map<String, Object> additionalData,
                              List<PricePoint> priceHistory) {
    }

    public record PricePoint(String price, String timestamp) {
    }

    public record StrategyContext(String runnerId, String portfolioId, String symbol,
                                  PositionContext positionContext, List<OpenLot> openLots,
                                  List<PendingOrder> pendingOrders, String totalCapital,
                                  String availableCapital, String maxOperationAmount,
                                  String minOperationAmount, int maxOpenPositions, int currentOpenPositions,
                                  String realizedPnl, String unrealizedPnl) {
    }

    public record PositionContext(String positionId, String symbol, String quantity, String averagePrice,
                                  String currentPrice, String unrealizedPnl, String realizedPnl,
                                  List<OpenLot> openLots) {
    }

    public record OpenLot(String lotId, String quantity, String availableQuantity, String entryPrice,
                          String currentPnlPercent, String openedAt, String peakPrice) {
    }

    public record PendingOrder(String transactionId, String type, String quantity, String price,
                               String status, String requestedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Decision(String strategyName, String decision, String confidence, String quantity,
                           String limitPrice, String targetLotId, String targetTransactionId,
                           String reasoning, String timestamp, Map<String, Object> metadata) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(String strategyId, String name, String version, String contractVersion,
                       JsonNode requirements) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Health(String status, boolean ready, String detail, String strategyRef) {
    }

    public record Error(String message) {
    }
}
