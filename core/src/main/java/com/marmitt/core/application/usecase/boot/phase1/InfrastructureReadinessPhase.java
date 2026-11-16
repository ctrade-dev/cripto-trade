package com.marmitt.core.application.usecase.boot.phase1;

import com.marmitt.core.application.usecase.boot.BootContext;
import com.marmitt.core.application.usecase.boot.BootPhase;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.dto.boot.BootExecutionCommand;
import com.marmitt.core.dto.exchange.boot.ExchangeBootReadiness;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Fase 1: cada exchange usada pelos runners elegiveis precisa estar pronta; a primeira que nao estiver falha o boot. */
@Slf4j
public class InfrastructureReadinessPhase implements BootPhase {

    public static final String NAME = "phase1.infrastructure";

    private final ExchangeAdapterRepositoryPort exchangeAdapterRepository;

    public InfrastructureReadinessPhase(ExchangeAdapterRepositoryPort exchangeAdapterRepository) {
        this.exchangeAdapterRepository = exchangeAdapterRepository;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean enabled(BootExecutionCommand command) {
        return command.phase1Enabled();
    }

    @Override
    public void run(BootContext context) {
        Set<String> exchanges = context.eligibleRunners().stream()
                .map(StrategyRunner::getExchangeId)
                .filter(exchange -> exchange != null && !exchange.isBlank())
                .map(String::toUpperCase)
                .collect(Collectors.toCollection(TreeSet::new));

        if (exchanges.isEmpty()) {
            log.info("bootSequence.phase1: no exchange to validate");
            return;
        }

        log.info("bootSequence.phase1: start exchanges={}", exchanges.size());

        for (String exchange : exchanges) {
            ExchangeBootReadiness readiness = exchangeAdapterRepository.findAdapter(exchange)
                    .orElseThrow(() -> new IllegalStateException(
                            "Boot readiness capability is not registered for exchange=" + exchange))
                    .bootReadiness().checkBootReadiness();

            if (!readiness.ready()) {
                throw context.failFast(NAME, readiness.code(),
                        "Phase1 readiness failed exchange=" + exchange + " message=" + readiness.message());
            }

            log.info("bootSequence.phase1: exchange={} ready code={} message={}",
                    exchange, readiness.code(), readiness.message());
        }

        log.info("bootSequence.phase1: completed runId={} exchanges={}", context.runId(), exchanges.size());
    }
}
