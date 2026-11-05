package com.marmitt.core.dto.strategy;

import com.marmitt.core.domain.Symbol;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Builder
public record StrategyInputDto(
        Symbol symbol,
        BigDecimal currentPrice,
        BigDecimal previousPrice,
        BigDecimal bidPrice,
        BigDecimal askPrice,
        BigDecimal volume,
        BigDecimal high24h,
        BigDecimal low24h,
        Instant timestamp,
        Map<String, Object> additionalData,
        List<PricePointDto> priceHistory
) {

    public StrategyInputDto {
        priceHistory = priceHistory == null ? List.of() : priceHistory;
    }

    public StrategyInputDto withPriceHistory(List<PricePointDto> history) {
        return new StrategyInputDto(symbol, currentPrice, previousPrice, bidPrice, askPrice, volume, high24h, low24h,
                timestamp, additionalData, history);
    }

    /**
     * Verifica se os dados de entrada da estratégia são válidos
     */
    public boolean isValid() {
        if (!isSymbolValid()) {
            return false;
        }

        if (!isCurrentPriceValid()) {
            return false;
        }

        if (!isTimestampValid()) {
            return false;
        }

        if (!isVolumeValid()) {
            return false;
        }

        if (!areBidAskConsistent()) {
            return false;
        }

        // 6. High/Low devem ser consistentes (se fornecidos)
        if (!areHighLowConsistent()) {
            return false;
        }

        return true;
    }

    private boolean isSymbolValid() {
        return symbol != null &&
               symbol.value() != null &&
               !symbol.value().trim().isEmpty();
    }

    private boolean isCurrentPriceValid() {
        return currentPrice != null &&
               currentPrice.compareTo(BigDecimal.ZERO) > 0 &&
               !currentPrice.equals(BigDecimal.ZERO);
    }

    private boolean isTimestampValid() {
        if (timestamp == null) {
            return false;
        }

        // Dados não podem ser mais antigos que 5 minutos
        Instant maxAge = Instant.now().minusSeconds(300); // 5 minutos
        return timestamp.isAfter(maxAge);
    }

    private boolean isVolumeValid() {
        return volume == null || volume.compareTo(BigDecimal.ZERO) > 0;
    }

    private boolean areBidAskConsistent() {
        if (bidPrice != null && askPrice != null) {
            return bidPrice.compareTo(askPrice) <= 0;
        }

        if (bidPrice != null) {
            return bidPrice.compareTo(BigDecimal.ZERO) > 0;
        }

        if (askPrice != null) {
            return askPrice.compareTo(BigDecimal.ZERO) > 0;
        }

        return true;
    }

    private boolean areHighLowConsistent() {
        if (high24h != null && low24h != null) {
            return high24h.compareTo(low24h) >= 0 &&
                   high24h.compareTo(BigDecimal.ZERO) > 0 &&
                   low24h.compareTo(BigDecimal.ZERO) > 0;
        }

        if (high24h != null) {
            return high24h.compareTo(BigDecimal.ZERO) > 0;
        }

        if (low24h != null) {
            return low24h.compareTo(BigDecimal.ZERO) > 0;
        }

        return true;
    }

    public boolean isPriceWithinSpread() {
        if (bidPrice != null && askPrice != null && currentPrice != null) {
            return currentPrice.compareTo(bidPrice) >= 0 &&
                   currentPrice.compareTo(askPrice) <= 0;
        }

        return true;
    }

    public boolean isPriceWithinDailyRange() {
        if (high24h != null && low24h != null && currentPrice != null) {
            return currentPrice.compareTo(low24h) >= 0 &&
                   currentPrice.compareTo(high24h) <= 0;
        }

        return true;
    }

    public boolean isValidWithPriceConsistency() {
        return isValid() &&
               isPriceWithinSpread() &&
               isPriceWithinDailyRange();
    }
}
