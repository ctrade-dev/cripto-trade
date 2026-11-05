package com.marmitt.application.spring.bootstrap;

import com.marmitt.core.domain.runner.RunnerStatusCause;
import com.marmitt.core.domain.runner.Position;
import com.marmitt.core.dto.runner.request.CreateRunnerRequest;
import com.marmitt.core.dto.runner.response.CreateRunnerResponse;
import com.marmitt.core.enums.RunnerStatus;
import com.marmitt.core.ports.inbound.runner.HaltRunnerPort;
import com.marmitt.core.ports.inbound.runner.ManageRunnerLifecyclePort;
import com.marmitt.core.ports.inbound.runner.RunnerOrderCommandPort;
import com.marmitt.core.exceptions.RunnerOperationException;
import com.marmitt.core.ports.outbound.marketdata.LatestPricePort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.strategy.TradingStrategy;
import com.marmitt.application.spring.testsupport.strategy.scenario.FilledBuyRestingSellCancelStrategy;
import com.marmitt.application.spring.testsupport.strategy.scenario.RestingBuyCancelStrategy;
import com.marmitt.application.spring.testsupport.strategy.scenario.ScenarioStrategyConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * T49 — ciclo de vida do runner e comandos do operador contra o MOCK.
 */
class RunnerLifecycleE2ETest extends ScenarioStrategyE2ETestSupport {

    @Autowired
    private ManageRunnerLifecyclePort runnerLifecycle;

    @Autowired
    private RunnerOrderCommandPort runnerOrderCommands;

    @Autowired
    private HaltRunnerPort haltRunner;

    @Autowired
    private LatestPricePort latestPrices;

    @Autowired
    private ExchangeAdapterRepositoryPort exchangeAdapters;

    @Test
    void createdRunnerStartsWithoutRestartAndResumesAfterKillSwitch() {
        UUID runnerId = createRunner(new RestingBuyCancelStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        assertEquals(RunnerStatus.CREATED, status(runnerId));

        assertEquals(RunnerStatus.ACTIVE, runnerLifecycle.start(runnerId, "test").newStatus());
        tickUntil(() -> !transactionsOfType(runnerId, "BUY").isEmpty(), "primeira BUY apos o start", runnerId);

        haltRunner.haltById(runnerId, RunnerStatusCause.manual("test"));
        assertEquals(RunnerStatus.HALTED, status(runnerId));
        double evaluatedWhileHalted = totalEvaluations(runnerId);
        driveTicks(3);
        assertEquals(evaluatedWhileHalted, totalEvaluations(runnerId), "Runner halted nao avalia sinais");

        assertEquals(RunnerStatus.ACTIVE, runnerLifecycle.resume(runnerId, "test").newStatus());
        driveTicks(3);
        assertTrue(totalEvaluations(runnerId) > evaluatedWhileHalted, "Retomado, o runner volta a avaliar sinais");
    }

    @Test
    void operatorCancelsARestingOrderAndTheCapitalReturns() {
        UUID runnerId = activateScenario(new RestingBuyCancelStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        UUID portfolioId = portfolioIdOf(runnerId);
        tickUntil(() -> transactionsOfType(runnerId, "BUY").stream().anyMatch(tx -> "SUBMITTED".equals(tx.status())),
                "BUY descansando", runnerId);
        UUID buyId = transactionsOfType(runnerId, "BUY").getFirst().id();

        haltRunner.haltById(runnerId, RunnerStatusCause.manual("test"));   // operador para o runner e puxa a ordem
        runnerOrderCommands.cancelOrder(runnerId, buyId);

        awaitCondition(() -> "CANCELED".equals(transactionsOfType(runnerId, "BUY").getFirst().status()),
                WAIT_TIMEOUT, "BUY cancelada pelo comando", runnerId);
        awaitCondition(() -> balance(portfolioId).reserved().signum() == 0, WAIT_TIMEOUT, "reserva devolvida", runnerId);
        // T55: cancelar de novo uma ordem ja terminal e no-op, sem erro nem efeito.
        runnerOrderCommands.cancelOrder(runnerId, buyId);
        assertEquals("CANCELED", transactionsOfType(runnerId, "BUY").getFirst().status());
        assertEquals(0, balance(portfolioId).reserved().signum());
    }

    /** Com o envio bloqueado nao nasce ordem nenhuma: nada a rejeitar, nada conta para o HALT (T68). */
    @Test
    void signalsWhileDispatchIsBlockedCreateNoOrderAndDoNotHaltTheRunner() {
        UUID runnerId = activateScenario(new RestingBuyCancelStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        String exchange = strategyRunnerRepository.findById(runnerId).orElseThrow().getExchangeId();

        exchangeAdapters.blockDispatch(exchange);
        try {
            for (int tick = 0; tick < 8; tick++) {
                publishTick(MARKET_PRICE);
                sleep(100);
            }
            awaitCondition(() -> signalEvaluatedCount(runnerId, "DISPATCH_BLOCKED") > 0, WAIT_TIMEOUT,
                    "sinal descartado pelo bloqueio", runnerId);
            assertTrue(transactions(runnerId).isEmpty(), "nenhuma ordem durante o bloqueio");
            assertEquals(RunnerStatus.ACTIVE, strategyRunnerRepository.findById(runnerId).orElseThrow().getStatus());
        } finally {
            exchangeAdapters.unblockDispatch(exchange);
        }
    }

    /** T69: a decisao vale so para o tick dela. Descartado o cancelamento, o proximo tick decide de novo. */
    @Test
    void strategyCancelWhileDispatchIsBlockedIsDroppedAndDecidedAgainAfterwards() {
        UUID runnerId = activateScenario(new RestingBuyCancelStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        String exchange = strategyRunnerRepository.findById(runnerId).orElseThrow().getExchangeId();
        publishTick(MARKET_PRICE);
        awaitCondition(() -> transactionsOfType(runnerId, "BUY").stream().anyMatch(tx -> "SUBMITTED".equals(tx.status())),
                WAIT_TIMEOUT, "BUY descansando", runnerId);
        double cancelsBefore = signalEvaluatedCount(runnerId, "CANCEL");

        exchangeAdapters.blockDispatch(exchange);
        try {
            sleep(100);
            publishTick(MARKET_PRICE);
            awaitCondition(() -> signalEvaluatedCount(runnerId, "DISPATCH_BLOCKED") > 0, WAIT_TIMEOUT,
                    "cancelamento descartado pelo bloqueio", runnerId);
            assertEquals(cancelsBefore, signalEvaluatedCount(runnerId, "CANCEL"), "nada foi enviado");
            assertEquals("SUBMITTED", transactionsOfType(runnerId, "BUY").getFirst().status());
        } finally {
            exchangeAdapters.unblockDispatch(exchange);
        }

        tickUntil(() -> transactionsOfType(runnerId, "BUY").stream().anyMatch(tx -> "CANCELED".equals(tx.status())),
                "cancelamento depois do desbloqueio", runnerId);
    }

    /** T53 rodada 11: com o envio bloqueado o comando respondia 202 e a ordem seguia na exchange. */
    @Test
    void operatorCancelIsRefusedWhileDispatchIsBlockedAndWorksAfterwards() {
        UUID runnerId = activateScenario(new RestingBuyCancelStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        tickUntil(() -> transactionsOfType(runnerId, "BUY").stream().anyMatch(tx -> "SUBMITTED".equals(tx.status())),
                "BUY descansando", runnerId);
        UUID buyId = transactionsOfType(runnerId, "BUY").getFirst().id();
        haltRunner.haltById(runnerId, RunnerStatusCause.manual("test"));
        String exchange = strategyRunnerRepository.findById(runnerId).orElseThrow().getExchangeId();

        exchangeAdapters.blockDispatch(exchange);
        try {
            RunnerOperationException refused = assertThrows(RunnerOperationException.class,
                    () -> runnerOrderCommands.cancelOrder(runnerId, buyId));
            assertEquals("EXCHANGE_UNAVAILABLE", refused.code());
            assertEquals("SUBMITTED", transactionsOfType(runnerId, "BUY").getFirst().status());
        } finally {
            exchangeAdapters.unblockDispatch(exchange);
        }

        runnerOrderCommands.cancelOrder(runnerId, buyId);
        awaitCondition(() -> "CANCELED".equals(transactionsOfType(runnerId, "BUY").getFirst().status()),
                WAIT_TIMEOUT, "BUY cancelada depois do bloqueio", runnerId);
    }

    @Test
    void terminatingRunnerIsClosedByTheOperatorAndArchived() {
        UUID runnerId = activateScenario(
                new FilledBuyRestingSellCancelStrategy(ScenarioStrategyConfig.boundedConfig(1)));
        UUID portfolioId = portfolioIdOf(runnerId);
        tickUntil(() -> positionCount(runnerId, "OPEN") == 1 && transactionsOfType(runnerId, "SELL").isEmpty()
                && !hasInFlight(runnerId), "lote aberto sem SELL", runnerId);

        assertEquals(RunnerStatus.TERMINATING, runnerLifecycle.terminate(runnerId, "test").newStatus());
        Position lot = strategyRunnerRepository.findOpenPositionsByRunnerId(runnerId).getFirst();
        latestPrices.record(SYMBOL, MARKET_PRICE);
        runnerOrderCommands.closePosition(runnerId, lot.getId());

        awaitCondition(() -> positionCount(runnerId, "CLOSED") == 1, WAIT_TIMEOUT, "lote fechado a mercado", runnerId);
        awaitCondition(() -> balance(portfolioId).reserved().signum() == 0, WAIT_TIMEOUT, "reserved zerado", runnerId);
        awaitStable(runnerId, QUIET_WINDOW);

        runnerLifecycle.archiveCompleted();
        assertEquals(RunnerStatus.ARCHIVED, status(runnerId));
        List<String> sells = jdbcTemplate.query(
                "SELECT order_type || ':' || status FROM transactions WHERE runner_id = ? AND type = 'SELL'",
                (rs, rowNum) -> rs.getString(1), runnerId);
        assertEquals(List.of("MARKET:FILLED"), sells, "O fechamento e uma unica SELL MARKET");
    }

    // ---------------------------------------------------------------- helpers

    private UUID createRunner(TradingStrategy strategy) {
        strategyRepository.registerStrategy(strategy);
        CreateRunnerResponse response = createRunnerPort.execute(CreateRunnerRequest.builder()
                .portfolioId(createPortfolio())
                .strategyId(strategy.getStrategyId())
                .symbol(SYMBOL)
                .exchangeName(MOCK_EXCHANGE)
                .allowedMarketDataSources(Set.of(MARKET_DATA_EXCHANGE))
                .build());
        assertNotNull(response.runnerId(), "Runner creation failed: " + response.message());
        return response.runnerId();
    }

    private RunnerStatus status(UUID runnerId) {
        return strategyRunnerRepository.findById(runnerId).orElseThrow().getStatus();
    }

    private double totalEvaluations(UUID runnerId) {
        return List.of("HOLD", "BUY", "SELL", "CANCEL", "REJECTED_CAPITAL", "REJECTED_LOCK",
                        "REJECTED_NO_POSITION", "REJECTED_POLICY", "SAFE_MODE").stream()
                .mapToDouble(decision -> signalEvaluatedCount(runnerId, decision))
                .sum();
    }

    private boolean hasInFlight(UUID runnerId) {
        return transactions(runnerId).stream()
                .anyMatch(tx -> List.of("PENDING", "SUBMITTED", "PARTIAL").contains(tx.status()));
    }

    private void tickUntil(BooleanSupplier condition, String what, UUID runnerId) {
        Instant deadline = Instant.now().plus(WAIT_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            publishTick(MARKET_PRICE);
            Instant tickDeadline = Instant.now().plus(Duration.ofMillis(600));
            while (Instant.now().isBefore(tickDeadline) && !condition.getAsBoolean()) {
                sleep(50);
            }
        }
        fail("Timeout esperando " + what + System.lineSeparator() + stateDump(runnerId));
    }
}
