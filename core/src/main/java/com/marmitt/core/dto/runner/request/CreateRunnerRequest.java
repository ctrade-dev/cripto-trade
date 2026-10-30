package com.marmitt.core.dto.runner.request;

import lombok.Builder;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pedido de criacao de runner.
 *
 * <p>O runner nasce com a exchange de execucao ({@code exchangeName}) e com a fonte de preco
 * ({@code allowedMarketDataSources}) definidas. Sem fonte, ele nunca recebe tick e nunca opera; a falta
 * so aparecia no {@code MARKET_DATA_STALE}, depois do start (T53 rodada 8, T67).
 */
@Builder
public record CreateRunnerRequest(
        UUID portfolioId,
        UUID strategyId,
        String symbol,
        String exchangeName,
        Set<String> allowedMarketDataSources
) {
    public CreateRunnerRequest {
        Objects.requireNonNull(portfolioId, "portfolioId cannot be null");
        Objects.requireNonNull(strategyId, "strategyId cannot be null");
        Objects.requireNonNull(symbol, "symbol cannot be null");
        Objects.requireNonNull(exchangeName, "exchangeName cannot be null");

        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol cannot be blank");
        }

        if (exchangeName.isBlank()) {
            throw new IllegalArgumentException("exchangeName cannot be blank");
        }

        if (allowedMarketDataSources == null || allowedMarketDataSources.isEmpty()) {
            throw new IllegalArgumentException(
                    "allowedMarketDataSources cannot be empty: the runner needs a market data source, e.g. [\"BINANCE\"]");
        }
    }
}

