package com.marmitt.core.dto.strategy;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Representa um lote de compra aberto, com quantidade disponivel para venda.
 */
@Builder
public record OpenLotDto(
        UUID lotId,
        BigDecimal quantity,
        BigDecimal availableQuantity,
        BigDecimal entryPrice,
        BigDecimal currentPnlPercent,
        Instant openedAt,
        BigDecimal peakPrice
) {
    public OpenLotDto {
        Objects.requireNonNull(lotId, "Lot ID cannot be null");
        Objects.requireNonNull(quantity, "Quantity cannot be null");
        Objects.requireNonNull(availableQuantity, "Available quantity cannot be null");
        Objects.requireNonNull(entryPrice, "Entry price cannot be null");
        Objects.requireNonNull(currentPnlPercent, "Current PnL percent cannot be null");
        Objects.requireNonNull(openedAt, "Opened at cannot be null");
        Objects.requireNonNull(peakPrice, "Peak price cannot be null");
    }
}
