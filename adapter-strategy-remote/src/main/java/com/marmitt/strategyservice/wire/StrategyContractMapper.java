package com.marmitt.strategyservice.wire;

import com.fasterxml.jackson.databind.JsonNode;
import com.marmitt.core.domain.Symbol;
import com.marmitt.core.dto.strategy.OpenLotDto;
import com.marmitt.core.dto.strategy.PendingOrderDto;
import com.marmitt.core.dto.strategy.PositionContext;
import com.marmitt.core.dto.strategy.PricePointDto;
import com.marmitt.core.dto.strategy.StrategyContextDto;
import com.marmitt.core.dto.strategy.StrategyInputDto;
import com.marmitt.core.dto.strategy.StrategyOutputDto;
import com.marmitt.core.enums.TradingAction;
import com.marmitt.core.enums.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Conversão entre os DTOs do core e o formato de rede do contrato, nos dois sentidos: o cliente do
 * ctrade usa core → rede para o request e rede → core para a decisão; o serviço de referência usa o
 * caminho inverso. Um único mapeamento para os dois lados garante que ambos falam o mesmo contrato.
 *
 * <p>A decisão de rede só é convertida depois de aprovada pelo {@link DecisionValidator}.
 */
public final class StrategyContractMapper {

    private StrategyContractMapper() {
    }

    // ---------------------------------------------------------------- core -> rede

    public static WireTypes.EvaluateRequest toRequest(UUID strategyRef, String tickId, StrategyInputDto input,
                                                      StrategyContextDto context) {
        return new WireTypes.EvaluateRequest(strategyRef.toString(), tickId, toWire(input), toWire(context));
    }

    public static WireTypes.Decision toWire(StrategyOutputDto output) {
        boolean hasSize = output.decision() == TradingAction.SHOULD_BUY || output.decision() == TradingAction.SHOULD_SELL;
        return new WireTypes.Decision(output.strategyName(), output.decision().name(),
                hasSize ? decimal(output.confidence()) : null,
                hasSize ? decimal(output.quantity()) : null,
                decimal(output.limitPrice()), uuid(output.targetLotId()), uuid(output.targetTransactionId()),
                output.reasoning() == null ? "" : output.reasoning(), instant(output.timestamp()),
                output.metadata());
    }

    private static WireTypes.MarketInput toWire(StrategyInputDto input) {
        return new WireTypes.MarketInput(input.symbol().value(), decimal(input.currentPrice()),
                decimal(input.previousPrice()), decimal(input.bidPrice()), decimal(input.askPrice()),
                decimal(input.volume()), decimal(input.high24h()), decimal(input.low24h()),
                instant(input.timestamp()), input.additionalData(),
                input.priceHistory().stream()
                        .map(point -> new WireTypes.PricePoint(decimal(point.price()), instant(point.timestamp())))
                        .toList());
    }

    private static WireTypes.StrategyContext toWire(StrategyContextDto context) {
        return new WireTypes.StrategyContext(uuid(context.runnerId()), uuid(context.portfolioId()),
                context.symbol().value(), toWire(context.positionContext()), lots(context.openLots()),
                context.pendingOrders().stream().map(StrategyContractMapper::toWire).toList(),
                decimal(context.totalCapital()), decimal(context.availableCapital()),
                decimal(context.maxOperationAmount()), decimal(context.minOperationAmount()),
                context.maxOpenPositions(), context.currentOpenPositions(),
                decimal(context.realizedPnl()), decimal(context.unrealizedPnl()));
    }

    private static WireTypes.PositionContext toWire(PositionContext position) {
        return new WireTypes.PositionContext(uuid(position.positionId()), position.symbol(),
                decimal(position.quantity()), decimal(position.averagePrice()), decimal(position.currentPrice()),
                decimal(position.unrealizedPnl()), decimal(position.realizedPnl()), lots(position.openLots()));
    }

    private static List<WireTypes.OpenLot> lots(List<OpenLotDto> lots) {
        return lots.stream().map(lot -> new WireTypes.OpenLot(uuid(lot.lotId()), decimal(lot.quantity()),
                decimal(lot.availableQuantity()), decimal(lot.entryPrice()), decimal(lot.currentPnlPercent()),
                instant(lot.openedAt()), decimal(lot.peakPrice()))).toList();
    }

    private static WireTypes.PendingOrder toWire(PendingOrderDto order) {
        return new WireTypes.PendingOrder(uuid(order.transactionId()), order.type().name(),
                decimal(order.quantity()), decimal(order.price()), order.status().name(), instant(order.requestedAt()));
    }

    // ---------------------------------------------------------------- rede -> core

    public static StrategyOutputDto toCore(WireTypes.Decision decision) {
        TradingAction action = TradingAction.valueOf(decision.decision());
        boolean hasSize = action == TradingAction.SHOULD_BUY || action == TradingAction.SHOULD_SELL;
        return new StrategyOutputDto(decision.strategyName(), action,
                hasSize ? new BigDecimal(decision.confidence()) : BigDecimal.ZERO,
                hasSize ? new BigDecimal(decision.quantity()) : BigDecimal.ZERO,
                hasSize ? decimalOrNull(decision.limitPrice()) : null,
                action == TradingAction.SHOULD_SELL ? uuidOrNull(decision.targetLotId()) : null,
                action == TradingAction.SHOULD_CANCEL ? UUID.fromString(decision.targetTransactionId()) : null,
                decision.reasoning(), instantOrNull(decision.timestamp()),
                decision.metadata() == null ? Map.of() : decision.metadata());
    }

    public static StrategyInputDto toCore(WireTypes.MarketInput input) {
        return new StrategyInputDto(Symbol.of(input.symbol()), new BigDecimal(input.currentPrice()),
                decimalOrNull(input.previousPrice()), decimalOrNull(input.bidPrice()),
                decimalOrNull(input.askPrice()), decimalOrNull(input.volume()), decimalOrNull(input.high24h()),
                decimalOrNull(input.low24h()), Instant.parse(input.timestamp()),
                input.additionalData() == null ? Map.of() : input.additionalData(),
                input.priceHistory() == null ? List.of() : input.priceHistory().stream()
                        .map(point -> new PricePointDto(new BigDecimal(point.price()), Instant.parse(point.timestamp())))
                        .toList());
    }

    public static StrategyContextDto toCore(WireTypes.StrategyContext context) {
        List<OpenLotDto> openLots = coreLots(context.openLots());
        WireTypes.PositionContext position = context.positionContext();
        return new StrategyContextDto(UUID.fromString(context.runnerId()), UUID.fromString(context.portfolioId()),
                Symbol.of(context.symbol()),
                new PositionContext(uuidOrNull(position.positionId()), position.symbol(),
                        new BigDecimal(position.quantity()), new BigDecimal(position.averagePrice()),
                        new BigDecimal(position.currentPrice()), new BigDecimal(position.unrealizedPnl()),
                        new BigDecimal(position.realizedPnl()), coreLots(position.openLots())),
                openLots,
                context.pendingOrders().stream().map(order -> new PendingOrderDto(
                        UUID.fromString(order.transactionId()), TradingAction.valueOf(order.type()),
                        new BigDecimal(order.quantity()), new BigDecimal(order.price()),
                        TransactionStatus.valueOf(order.status()), Instant.parse(order.requestedAt()))).toList(),
                new BigDecimal(context.totalCapital()), new BigDecimal(context.availableCapital()),
                new BigDecimal(context.maxOperationAmount()), new BigDecimal(context.minOperationAmount()),
                context.maxOpenPositions(), context.currentOpenPositions(),
                new BigDecimal(context.realizedPnl()), new BigDecimal(context.unrealizedPnl()));
    }

    private static List<OpenLotDto> coreLots(List<WireTypes.OpenLot> lots) {
        return lots.stream().map(lot -> new OpenLotDto(UUID.fromString(lot.lotId()), new BigDecimal(lot.quantity()),
                new BigDecimal(lot.availableQuantity()), new BigDecimal(lot.entryPrice()),
                new BigDecimal(lot.currentPnlPercent()), Instant.parse(lot.openedAt()),
                new BigDecimal(lot.peakPrice()))).toList();
    }

    /**
     * Ticks de historico declarados em {@code requirements}. Requisito desconhecido e recusado: a estrategia
     * precisaria de algo que este ctrade nao entrega, e a falha deve aparecer na conferencia, nao no tick.
     *
     * @throws IllegalArgumentException se {@code requirements} faltar ou estiver fora do contrato
     */
    public static int priceHistoryTicks(JsonNode requirements) {
        if (requirements == null || !requirements.isObject()) {
            throw new IllegalArgumentException("requirements is required");
        }
        requirements.fieldNames().forEachRemaining(name -> {
            if (!"priceHistory".equals(name)) {
                throw new IllegalArgumentException("unknown requirement " + name);
            }
        });
        JsonNode priceHistory = requirements.get("priceHistory");
        if (priceHistory == null) {
            return 0;
        }
        JsonNode ticks = priceHistory.get("ticks");
        if (ticks == null || !ticks.canConvertToInt() || !ticks.isIntegralNumber()) {
            throw new IllegalArgumentException("requirements.priceHistory.ticks must be an integer");
        }
        return ticks.intValue();
    }

    // ---------------------------------------------------------------- escalares

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static BigDecimal decimalOrNull(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static String uuid(UUID value) {
        return value == null ? null : value.toString();
    }

    private static UUID uuidOrNull(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private static Instant instantOrNull(String value) {
        return value == null ? null : Instant.parse(value);
    }
}
