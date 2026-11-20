package com.marmitt.strategyservice.wire;

import com.fasterxml.jackson.databind.JsonNode;
import com.marmitt.core.domain.Symbol;
import com.marmitt.core.dto.strategy.OpenLotDto;
import com.marmitt.core.dto.strategy.PendingOrderDto;
import com.marmitt.core.dto.strategy.PositionContext;
import com.marmitt.core.dto.strategy.CandleDto;
import com.marmitt.core.dto.strategy.CandleInterval;
import com.marmitt.core.dto.strategy.CandleRequirement;
import com.marmitt.core.dto.strategy.CandleSeriesDto;
import com.marmitt.core.dto.strategy.PricePointDto;
import com.marmitt.core.dto.strategy.StrategyRequirements;
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
                        .toList(),
                input.candles().stream().map(StrategyContractMapper::toWire).toList());
    }

    private static WireTypes.CandleSeries toWire(CandleSeriesDto series) {
        return new WireTypes.CandleSeries(series.interval().code(), series.candles().stream()
                .map(candle -> new WireTypes.Candle(instant(candle.openTime()), decimal(candle.open()),
                        decimal(candle.high()), decimal(candle.low()), decimal(candle.close()),
                        decimal(candle.volume())))
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
                        .toList(),
                input.candles() == null ? List.of() : input.candles().stream()
                        .map(StrategyContractMapper::toCore)
                        .toList());
    }

    private static CandleSeriesDto toCore(WireTypes.CandleSeries series) {
        return new CandleSeriesDto(interval(series.interval()), series.candles().stream()
                .map(candle -> new CandleDto(Instant.parse(candle.openTime()), new BigDecimal(candle.open()),
                        new BigDecimal(candle.high()), new BigDecimal(candle.low()), new BigDecimal(candle.close()),
                        new BigDecimal(candle.volume())))
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
     * Requisitos declarados em {@code requirements} (ticks, T69; candles, T72). Requisito desconhecido e recusado:
     * a estrategia precisaria de algo que este ctrade nao entrega, e a falha deve aparecer na conferencia, nao no
     * tick. So o formato e conferido aqui; tetos e repeticao sao regra do core
     * ({@link StrategyRequirements#violation()}).
     *
     * @throws IllegalArgumentException se {@code requirements} faltar ou estiver fora do contrato
     */
    public static StrategyRequirements requirements(JsonNode requirements) {
        if (requirements == null || !requirements.isObject()) {
            throw new IllegalArgumentException("requirements is required");
        }
        requirements.fieldNames().forEachRemaining(name -> {
            if (!"priceHistory".equals(name) && !"candles".equals(name)) {
                throw new IllegalArgumentException("unknown requirement " + name);
            }
        });
        return new StrategyRequirements(priceHistoryTicks(requirements.get("priceHistory")),
                candles(requirements.get("candles")));
    }

    private static int priceHistoryTicks(JsonNode priceHistory) {
        if (priceHistory == null) {
            return 0;
        }
        JsonNode ticks = priceHistory.get("ticks");
        if (ticks == null || !ticks.canConvertToInt() || !ticks.isIntegralNumber()) {
            throw new IllegalArgumentException("requirements.priceHistory.ticks must be an integer");
        }
        return ticks.intValue();
    }

    private static List<CandleRequirement> candles(JsonNode candles) {
        if (candles == null) {
            return List.of();
        }
        if (!candles.isArray()) {
            throw new IllegalArgumentException("requirements.candles must be an array");
        }
        List<CandleRequirement> required = new java.util.ArrayList<>();
        for (JsonNode candle : candles) {
            if (!candle.isObject()) {
                throw new IllegalArgumentException("requirements.candles items must be objects");
            }
            candle.fieldNames().forEachRemaining(name -> {
                if (!"interval".equals(name) && !"count".equals(name)) {
                    throw new IllegalArgumentException("unknown field requirements.candles." + name);
                }
            });
            JsonNode count = candle.get("count");
            if (count == null || !count.isIntegralNumber() || !count.canConvertToInt()) {
                throw new IllegalArgumentException("requirements.candles.count must be an integer");
            }
            JsonNode interval = candle.get("interval");
            if (interval == null || !interval.isTextual()) {
                throw new IllegalArgumentException("requirements.candles.interval is required");
            }
            required.add(new CandleRequirement(interval(interval.asText()), count.intValue()));
        }
        return required;
    }

    private static CandleInterval interval(String code) {
        return CandleInterval.fromCode(code)
                .orElseThrow(() -> new IllegalArgumentException("unknown candle interval " + code));
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
