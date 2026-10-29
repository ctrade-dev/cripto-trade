package com.marmitt.application.spring.testsupport.strategy.scenario;

import com.marmitt.core.domain.Symbol;
import com.marmitt.core.dto.strategy.OpenLotDto;
import com.marmitt.core.dto.strategy.PendingOrderDto;
import com.marmitt.core.dto.strategy.PositionContext;
import com.marmitt.core.dto.strategy.StrategyContextDto;
import com.marmitt.core.dto.strategy.StrategyInputDto;
import com.marmitt.core.enums.TradingAction;
import com.marmitt.core.enums.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fixtures sintéticas para os testes determinísticos das estratégias de cenário (T35).
 * Constroem {@link StrategyContextDto}/{@link StrategyInputDto} sem infra.
 */
final class ScenarioTestFixtures {

    static final String SYMBOL = "BTCUSDT";

    private ScenarioTestFixtures() {
    }

    static StrategyInputDto input(BigDecimal price) {
        return StrategyInputDto.builder()
                .symbol(Symbol.of(SYMBOL))
                .currentPrice(price)
                .bidPrice(price)
                .askPrice(price)
                .timestamp(Instant.now())
                .build();
    }

    static StrategyContextDto context(BigDecimal availableCapital,
                                      List<OpenLotDto> openLots,
                                      List<PendingOrderDto> pendingOrders) {
        return context(UUID.randomUUID(), availableCapital, openLots, pendingOrders);
    }

    /**
     * Variante com {@code runnerId} explícito: os cenários com teto de ciclos escopam o orçamento
     * por runner, então ticks que precisam compartilhar o mesmo orçamento têm de usar o mesmo id.
     */
    static StrategyContextDto context(UUID runnerId,
                                      BigDecimal availableCapital,
                                      List<OpenLotDto> openLots,
                                      List<PendingOrderDto> pendingOrders) {
        return StrategyContextDto.builder()
                .runnerId(runnerId)
                .portfolioId(UUID.randomUUID())
                .symbol(Symbol.of(SYMBOL))
                .positionContext(PositionContext.empty(SYMBOL))
                .openLots(openLots)
                .pendingOrders(pendingOrders)
                .totalCapital(new BigDecimal("10000"))
                .availableCapital(availableCapital)
                .maxOperationAmount(new BigDecimal("10000"))
                .minOperationAmount(new BigDecimal("10"))
                .maxOpenPositions(1)
                .currentOpenPositions(openLots.isEmpty() ? 0 : 1)
                .realizedPnl(BigDecimal.ZERO)
                .unrealizedPnl(BigDecimal.ZERO)
                .build();
    }

    static OpenLotDto openLot(BigDecimal quantity) {
        return OpenLotDto.builder()
                .lotId(UUID.randomUUID())
                .quantity(quantity)
                .availableQuantity(quantity)
                .entryPrice(new BigDecimal("65000"))
                .currentPnlPercent(BigDecimal.ZERO)
                .openedAt(Instant.now())
                .build();
    }

    static PendingOrderDto pending(TradingAction type, UUID transactionId) {
        return PendingOrderDto.builder()
                .transactionId(transactionId)
                .type(type)
                .quantity(new BigDecimal("0.001"))
                .price(new BigDecimal("64000"))
                .status(TransactionStatus.PENDING)
                .requestedAt(Instant.now())
                .build();
    }
}
