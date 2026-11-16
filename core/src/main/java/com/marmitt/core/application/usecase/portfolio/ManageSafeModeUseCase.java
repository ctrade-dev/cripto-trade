package com.marmitt.core.application.usecase.portfolio;

import com.marmitt.core.domain.portfolio.Portfolio;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.portfolio.request.ChangeSafeModeRequest;
import com.marmitt.core.dto.portfolio.response.SafeModeStatusDto;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.SafeModeOrigin;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.exceptions.PortfolioNotFoundException;
import com.marmitt.core.exceptions.SafeModeTransitionException;
import com.marmitt.core.ports.inbound.portfolio.ManageSafeModePort;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;

/**
 * Muda e consulta o Safe Mode do portfolio (T45, Blueprint 11.1.B.1 e 11.1.C).
 *
 * <p>As regras de transicao ficam no {@link Portfolio}; este use case so carrega, aplica e grava a
 * mudanca com a auditoria. A execucao dos niveis {@code CANCEL_ALL}/{@code PANIC_SELL} nao acontece
 * aqui: ela e conduzida a cada tick pelo {@code ProcessTradeSignalUseCase}, o que a mantem assincrona
 * e retomavel apos restart. O status mostra o que ainda falta convergir.
 */
@Slf4j
public class ManageSafeModeUseCase implements ManageSafeModePort {

    private final PortfolioRepositoryPort portfolioRepository;
    private final StrategyRunnerRepositoryPort strategyRunnerRepository;

    public ManageSafeModeUseCase(PortfolioRepositoryPort portfolioRepository,
                                 StrategyRunnerRepositoryPort strategyRunnerRepository) {
        this.portfolioRepository = portfolioRepository;
        this.strategyRunnerRepository = strategyRunnerRepository;
    }

    @Override
    public SafeModeStatusDto status(UUID portfolioId) {
        return statusOf(load(portfolioId));
    }

    @Override
    public SafeModeStatusDto change(UUID portfolioId, SafeModeOrigin origin, ChangeSafeModeRequest request) {
        if (request == null || request.level() == null) {
            throw new SafeModeTransitionException("A target safe mode level is required");
        }
        Portfolio portfolio = load(portfolioId);
        portfolio.changeSafeMode(request.level(), origin, request.trigger(), request.reason(), request.confirm())
                .ifPresentOrElse(change -> {
                    portfolioRepository.saveSafeModeChange(portfolio, change);
                    log.warn("safeMode: portfolioId={} {} -> {} origin={} trigger={} reason={}",
                            portfolioId, change.from(), change.to(), change.origin(), change.trigger(),
                            change.reason());
                }, () -> log.info("safeMode: portfolioId={} already {} - no change (origin={})",
                        portfolioId, portfolio.getSafeModeStatus(), origin));
        return statusOf(portfolio);
    }

    private Portfolio load(UUID portfolioId) {
        return portfolioRepository.findById(portfolioId)
                .orElseThrow(() -> new PortfolioNotFoundException(portfolioId));
    }

    private SafeModeStatusDto statusOf(Portfolio portfolio) {
        int restingOrders = 0;
        int liquidatingOrders = 0;
        int openPositions = 0;
        int idleRunners = 0;
        for (StrategyRunner runner : strategyRunnerRepository.findByPortfolioId(portfolio.getId())) {
            if (!runner.canAcceptSignals()) {
                idleRunners++;
                continue;
            }
            List<Transaction> inFlight = strategyRunnerRepository.findByRunnerIdAndStatuses(
                    runner.getId(), TransactionStatus.IN_FLIGHT);
            for (Transaction transaction : inFlight) {
                if (transaction.getOrderType() == OrderType.LIMIT) {
                    restingOrders++;
                } else {
                    liquidatingOrders++;
                }
            }
            openPositions += strategyRunnerRepository.findOpenPositionsByRunnerId(runner.getId()).size();
        }
        return new SafeModeStatusDto(portfolio.getId(), portfolio.getSafeModeStatus(),
                portfolio.getSafeModeOrigin(), portfolio.getSafeModeReason(), portfolio.getSafeModeChangedAt(),
                restingOrders, liquidatingOrders, openPositions, idleRunners);
    }
}
