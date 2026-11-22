package com.marmitt.core.application.usecase.portfolio;

import com.marmitt.core.dto.portfolio.response.PortfolioDto;
import com.marmitt.core.dto.portfolio.response.GlobalBalanceDto;
import com.marmitt.core.dto.portfolio.response.PortfolioPnlDto;
import com.marmitt.core.dto.portfolio.response.TransactionDto;
import com.marmitt.core.domain.portfolio.GlobalBalance;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.ports.inbound.portfolio.QueryPortfolioPort;
import com.marmitt.core.ports.outbound.marketdata.LatestPricePort;
import com.marmitt.core.ports.outbound.repository.GlobalBalanceRepositoryPort;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
public class QueryPortfolioUseCase implements QueryPortfolioPort {

    private static final List<TransactionStatus> ALL_ACTIVE_STATUSES = List.of(
            TransactionStatus.PENDING,
            TransactionStatus.SUBMITTED,
            TransactionStatus.PARTIAL,
            TransactionStatus.FILLED,
            TransactionStatus.CANCELED,
            TransactionStatus.REJECTED,
            TransactionStatus.EXPIRED
    );

    private final PortfolioRepositoryPort portfolioRepository;
    private final StrategyRunnerRepositoryPort strategyRunnerRepository;
    private final GlobalBalanceRepositoryPort globalBalanceRepository;
    private final PortfolioMarkToMarket markToMarket;

    public QueryPortfolioUseCase(
            PortfolioRepositoryPort portfolioRepository,
            StrategyRunnerRepositoryPort strategyRunnerRepository,
            GlobalBalanceRepositoryPort globalBalanceRepository,
            LatestPricePort latestPrices
    ) {
        this.portfolioRepository = portfolioRepository;
        this.strategyRunnerRepository = strategyRunnerRepository;
        this.globalBalanceRepository = globalBalanceRepository;
        this.markToMarket = new PortfolioMarkToMarket(strategyRunnerRepository, latestPrices);
    }

    @Override
    public Optional<PortfolioDto> findById(UUID portfolioId) {
        log.debug("Querying portfolio by ID: {}", portfolioId);
        return portfolioRepository.findById(portfolioId)
                .map(PortfolioDto::fromDomain);
    }

    @Override
    public Optional<PortfolioDto> findByName(String name) {
        log.debug("Querying portfolio by name: {}", name);
        return portfolioRepository.findByName(name)
                .map(PortfolioDto::fromDomain);
    }

    @Override
    public List<PortfolioDto> findBySymbol(String symbol) {
        log.debug("Querying portfolios by symbol: {}", symbol);
        return portfolioRepository.findBySymbol(symbol).stream()
                .map(PortfolioDto::fromDomain)
                .toList();
    }

    @Override
    public List<TransactionDto> findTransactionsByPortfolioId(UUID portfolioId, Instant from, Instant to) {
        log.debug("Querying transactions for portfolio: {}", portfolioId);

        if (portfolioRepository.findById(portfolioId).isEmpty()) {
            log.warn("Portfolio not found with ID: {}", portfolioId);
            return Collections.emptyList();
        }

        return strategyRunnerRepository.findByPortfolioId(portfolioId).stream()
                .flatMap(runner -> strategyRunnerRepository
                        .findByRunnerIdAndStatuses(runner.getId(), ALL_ACTIVE_STATUSES).stream())
                .filter(transaction -> from == null || !transaction.getRequestedAt().isBefore(from))
                .filter(transaction -> to == null || transaction.getRequestedAt().isBefore(to))
                .sorted(Comparator.comparing(Transaction::getRequestedAt))
                .map(TransactionDto::fromDomain)
                .toList();
    }

    @Override
    public Optional<GlobalBalanceDto> findBalanceByPortfolioId(UUID portfolioId) {
        log.debug("Querying global balance for portfolio: {}", portfolioId);

        if (portfolioRepository.findById(portfolioId).isEmpty()) {
            log.warn("Portfolio not found with ID: {}", portfolioId);
            return Optional.empty();
        }

        return globalBalanceRepository.findByPortfolioId(portfolioId)
                .map(GlobalBalanceDto::fromDomain);
    }

    @Override
    public Optional<PortfolioPnlDto> findPnlByPortfolioId(UUID portfolioId) {
        log.debug("Querying PnL for portfolio: {}", portfolioId);

        if (portfolioRepository.findById(portfolioId).isEmpty()) {
            log.warn("Portfolio not found with ID: {}", portfolioId);
            return Optional.empty();
        }

        Optional<GlobalBalance> balanceOpt = globalBalanceRepository.findByPortfolioId(portfolioId);
        if (balanceOpt.isEmpty()) {
            log.warn("GlobalBalance not found for portfolio: {}", portfolioId);
            return Optional.empty();
        }

        BigDecimal realized = balanceOpt.get().getRealizedBalance();
        // Flutuante ao ultimo preco (T43); posicao ainda sem preco nao entra na soma e e contada (T58).
        PortfolioMarkToMarket.Valuation valuation = markToMarket.value(portfolioId);
        BigDecimal unrealized = valuation.unrealizedPnl();

        return Optional.of(PortfolioPnlDto.builder()
                .portfolioId(portfolioId)
                .realizedPnL(realized)
                .unrealizedPnL(unrealized)
                .totalPnL(realized.add(unrealized))
                .unpricedPositions(valuation.unpriced().size())
                .priceAsOf(valuation.oldestPriceAt())
                .build());
    }
}

