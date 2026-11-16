package com.marmitt.core.application.usecase.boot.phase2;

import com.marmitt.core.application.usecase.boot.BootContext;
import com.marmitt.core.application.usecase.boot.BootPhase;
import com.marmitt.core.domain.portfolio.DeadLetterEntry;
import com.marmitt.core.domain.portfolio.Portfolio;
import com.marmitt.core.domain.runner.ClientOrderId;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.dto.boot.BootExecutionCommand;
import com.marmitt.core.dto.portfolio.PortfolioZombieCandidate;
import com.marmitt.core.dto.portfolio.PortfolioZombieDetectionResult;
import com.marmitt.core.enums.BootFailureMode;
import com.marmitt.core.enums.PortfolioZombieDetectionStatus;
import com.marmitt.core.ports.outbound.repository.DeadLetterEntryRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Fase 2, deteccao de zumbis: ordens abertas na exchange sem par local, por portfolio e exchange. Em
 * {@code FAIL_FAST}, zumbi detectado ou falha da consulta falham o boot e as amostras vao para a DLQ.
 */
@Slf4j
public class ZombieDetectionPhase implements BootPhase {

    public static final String NAME = "phase2.zombie";

    private final PortfolioZombieDetectionUseCase portfolioZombieDetectionUseCase;
    private final DeadLetterEntryRepositoryPort deadLetterEntryRepository;
    private final StrategyRunnerRepositoryPort strategyRunnerRepository;

    public ZombieDetectionPhase(PortfolioZombieDetectionUseCase portfolioZombieDetectionUseCase,
                                DeadLetterEntryRepositoryPort deadLetterEntryRepository,
                                StrategyRunnerRepositoryPort strategyRunnerRepository) {
        this.portfolioZombieDetectionUseCase = portfolioZombieDetectionUseCase;
        this.deadLetterEntryRepository = deadLetterEntryRepository;
        this.strategyRunnerRepository = strategyRunnerRepository;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean enabled(BootExecutionCommand command) {
        return command.phase2Enabled() && command.zombieEnabled();
    }

    @Override
    public void run(BootContext context) {
        BootExecutionCommand command = context.command();
        BootFailureMode mode = command.failureMode();
        log.info("bootSequence.phase2.zombie: start portfolios={} mode={} cutoffEnabled={}",
                context.portfolios().size(), mode, command.cutoffEnabled());

        int zombiesTotal = 0;
        for (Portfolio portfolio : context.portfolios()) {
            for (String exchange : portfolioExchanges(portfolio, context.eligibleRunners())) {
                long startedNs = System.nanoTime();
                PortfolioZombieDetectionResult result =
                        portfolioZombieDetectionUseCase.execute(portfolio.getId(), exchange, command.cutoffEnabled());
                long durationMs = (System.nanoTime() - startedNs) / 1_000_000L;
                zombiesTotal += result.zombieCount();
                context.observer().onPortfolioPhaseEvaluated(NAME, result.status().name(), exchange, mode.name(),
                        durationMs);

                switch (result.status()) {
                    case CLEAN -> log.info(
                            "bootSequence.phase2.zombie: portfolio={} exchange={} status={} openOrders={} zombies={}",
                            result.portfolioId(), result.exchangeId(), result.status(),
                            result.openOrders(), result.zombieCount());
                    case DETECTED -> {
                        boolean persistToDlq = mode == BootFailureMode.FAIL_FAST;
                        int persistedSamples = persistToDlq ? persistSamplesToDlq(result) : 0;
                        log.warn(
                                "bootSequence.phase2.zombie: portfolio={} exchange={} mode={} status={} code={} openOrders={} zombies={} invalidFormat={} unknownRunner={} noLocalMatch={} beforeCutoff={} unknownSymbol={} persistedSamples={} dlqPersisted={}",
                                result.portfolioId(), result.exchangeId(), mode, result.status(), result.code(),
                                result.openOrders(), result.zombieCount(), result.invalidFormatCount(),
                                result.unknownRunnerCount(), result.noLocalMatchCount(), result.beforeCutoffCount(),
                                result.unknownSymbolCount(), persistedSamples, persistToDlq);
                        result.samples().forEach(sample -> log.warn(
                                "bootSequence.phase2.zombie: sample portfolio={} exchange={} reason={} code={} clientOrderId={} exchangeOrderId={} symbol={}",
                                result.portfolioId(), result.exchangeId(), sample.reason(), sample.code(),
                                sample.clientOrderId(), sample.exchangeOrderId(), sample.symbol()));
                    }
                    case SKIPPED -> log.warn(
                            "bootSequence.phase2.zombie: portfolio={} exchange={} status={} code={} message={}",
                            result.portfolioId(), result.exchangeId(), result.status(), result.code(), result.message());
                    case FAILED -> log.error(
                            "bootSequence.phase2.zombie: portfolio={} exchange={} status={} code={} message={}",
                            result.portfolioId(), result.exchangeId(), result.status(), result.code(), result.message());
                }

                boolean criticalFailure = result.status() == PortfolioZombieDetectionStatus.FAILED
                        || result.status() == PortfolioZombieDetectionStatus.DETECTED;
                if (criticalFailure && mode == BootFailureMode.FAIL_FAST) {
                    throw context.failFast(NAME, result.code(),
                            "Phase2 zombie detection failed portfolio=" + result.portfolioId()
                                    + " exchange=" + result.exchangeId()
                                    + " message=" + result.message());
                }
            }
        }

        log.info("bootSequence.phase2.zombie: completed runId={} detected={} mode={}",
                context.runId(), zombiesTotal, mode.name());
    }

    private int persistSamplesToDlq(PortfolioZombieDetectionResult result) {
        int persisted = 0;
        for (PortfolioZombieCandidate sample : result.samples()) {
            UUID runnerId = resolveRunnerId(result.portfolioId(), sample);

            boolean alreadyOpen = deadLetterEntryRepository.existsUnresolvedByIdentity(
                    result.portfolioId(),
                    runnerId,
                    sample.clientOrderId(),
                    sample.exchangeOrderId(),
                    sample.reason()
            );

            if (alreadyOpen) {
                continue;
            }

            DeadLetterEntry entry = new DeadLetterEntry(
                    result.portfolioId(),
                    runnerId,
                    sample.clientOrderId(),
                    sample.exchangeOrderId(),
                    "source=boot.phase2.zombie"
                            + ", exchange=" + result.exchangeId()
                            + ", runnerId=" + runnerId
                            + ", code=" + sample.code()
                            + ", symbol=" + sample.symbol()
                            + ", reason=" + sample.reason(),
                    sample.reason()
            );
            deadLetterEntryRepository.save(entry);
            persisted++;
        }
        return persisted;
    }

    private UUID resolveRunnerId(UUID portfolioId, PortfolioZombieCandidate sample) {
        String clientOrderId = sample.clientOrderId();
        if (clientOrderId == null || clientOrderId.isBlank()) {
            return null;
        }

        var byTransaction = strategyRunnerRepository.findTransactionByClientOrderId(clientOrderId)
                .flatMap(tx -> strategyRunnerRepository.findById(tx.getRunnerId())
                        .filter(runner -> portfolioId.equals(runner.getPortfolioId()))
                        .map(StrategyRunner::getId));
        if (byTransaction.isPresent()) {
            return byTransaction.get();
        }

        String shortCode = ClientOrderId.getRunnerShortCode(clientOrderId);
        if (shortCode == null || shortCode.isBlank()) {
            return null;
        }

        return strategyRunnerRepository.findByShortCodeAndPortfolioId(shortCode.toLowerCase(Locale.ROOT), portfolioId)
                .map(StrategyRunner::getId)
                .orElse(null);
    }

    private static Set<String> portfolioExchanges(Portfolio portfolio, List<StrategyRunner> eligibleRunners) {
        return eligibleRunners.stream()
                .filter(runner -> runner.getPortfolioId().equals(portfolio.getId()))
                .map(StrategyRunner::getExchangeId)
                .filter(exchange -> exchange != null && !exchange.isBlank())
                .map(String::toUpperCase)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
