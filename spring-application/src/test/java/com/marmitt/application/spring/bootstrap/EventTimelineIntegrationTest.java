package com.marmitt.application.spring.bootstrap;

import com.marmitt.core.domain.runner.PositionProtection;
import com.marmitt.core.domain.runner.RunnerStatusCause;
import com.marmitt.core.dto.boot.BootRun;
import com.marmitt.core.dto.operations.EventTimeline;
import com.marmitt.core.dto.operations.TimelineEvent;
import com.marmitt.core.dto.portfolio.request.ChangeSafeModeRequest;
import com.marmitt.core.dto.runner.request.CreateRunnerRequest;
import com.marmitt.core.dto.runner.response.CreateRunnerResponse;
import com.marmitt.core.enums.BootRunStatus;
import com.marmitt.core.enums.SafeModeOrigin;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.enums.TimelineEventType;
import com.marmitt.core.ports.inbound.operations.QueryEventTimelinePort;
import com.marmitt.core.ports.inbound.portfolio.ManageSafeModePort;
import com.marmitt.core.ports.inbound.runner.HaltRunnerPort;
import com.marmitt.core.ports.inbound.runner.ManageRunnerLifecyclePort;
import com.marmitt.core.ports.outbound.repository.BootRunRepositoryPort;
import com.marmitt.application.spring.testsupport.strategy.scenario.RestingBuyCancelStrategy;
import com.marmitt.application.spring.testsupport.strategy.scenario.ScenarioStrategyConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T59: o que o sistema e o operador fizeram aparece na linha do tempo, em ordem, lido das tabelas de origem.
 */
class EventTimelineIntegrationTest extends ScenarioStrategyE2ETestSupport {

    @Autowired
    private QueryEventTimelinePort timeline;

    @Autowired
    private ManageRunnerLifecyclePort runnerLifecycle;

    @Autowired
    private HaltRunnerPort haltRunner;

    @Autowired
    private ManageSafeModePort manageSafeMode;

    @Autowired
    private BootRunRepositoryPort bootRuns;

    @Test
    void automaticHaltManualResumeAndProtectiveExitAppearInOrder() {
        Instant testStart = Instant.now();
        UUID runnerId = createRunner();
        UUID portfolioId = portfolioIdOf(runnerId);

        runnerLifecycle.start(runnerId, "operator start");
        haltRunner.haltById(runnerId, RunnerStatusCause.automatic("consecutive order rejections: test"));
        manageSafeMode.change(portfolioId, SafeModeOrigin.AUTOMATIC,
                new ChangeSafeModeRequest(SafeModeStatus.HALT, "drawdown above limit", false));
        manageSafeMode.change(portfolioId, SafeModeOrigin.MANUAL,
                new ChangeSafeModeRequest(SafeModeStatus.NORMAL, "operator reviewed drawdown", false));
        runnerLifecycle.resume(runnerId, "operator resume");
        Instant afterCommands = Instant.now();
        UUID exitId = insertProtectiveExit(runnerId, afterCommands.plusMillis(10));
        insertDeadLetter(portfolioId, runnerId, afterCommands.plusMillis(20));
        insertOperatorAction(afterCommands.plusMillis(30));
        // Fim logo depois dos eventos semeados: nada que um scheduler grave depois entra na janela.
        Instant until = afterCommands.plusMillis(31);

        EventTimeline forPortfolio = timeline.find(testStart, until, portfolioId, null);

        assertEquals(List.of(
                        "RUNNER_STATUS_CHANGED CREATED>INITIALIZING MANUAL operator start",
                        "RUNNER_STATUS_CHANGED INITIALIZING>ACTIVE MANUAL operator start",
                        "RUNNER_STATUS_CHANGED ACTIVE>HALTED AUTOMATIC consecutive order rejections: test",
                        "SAFE_MODE_CHANGED NORMAL>HALT AUTOMATIC drawdown above limit",
                        "SAFE_MODE_CHANGED HALT>NORMAL MANUAL operator reviewed drawdown",
                        "RUNNER_STATUS_CHANGED HALTED>ACTIVE MANUAL operator resume",
                        "PROTECTIVE_EXIT null>null AUTOMATIC protective exit MAX_LOSS: lot below limit",
                        "DLQ_ENTRY null>null AUTOMATIC UNKNOWN_RUNNER"),
                forPortfolio.events().stream().map(EventTimelineIntegrationTest::describe).toList(),
                "sem o comando do operador: o filtro de portfolio so traz os tipos ligados a portfolio");
        TimelineEvent exit = forPortfolio.events().get(6);
        assertEquals(exitId, exit.transactionId());
        assertEquals(runnerId, exit.runnerId());
        assertEquals(portfolioId, exit.portfolioId());

        List<TimelineEventType> all = timeline.find(testStart, until, null, null)
                .events().stream().map(TimelineEvent::type).toList();
        assertTrue(all.contains(TimelineEventType.OPERATOR_ACTION), all.toString());

        EventTimeline firstPage = timeline.find(testStart, until, portfolioId, 3);
        assertTrue(firstPage.truncated());
        assertEquals(firstPage.events().get(2).occurredAt(), firstPage.nextSince());
    }

    @Test
    void bootRunsSurviveInTheTimeline() {
        Instant startedAt = Instant.now();
        String runId = UUID.randomUUID().toString();
        bootRuns.save(new BootRun(runId, "FAIL_FAST", BootRunStatus.FAILED, startedAt, startedAt.plusMillis(1500),
                "phase1.infrastructure", "exchange not ready"));

        TimelineEvent boot = timeline.find(startedAt, startedAt.plusSeconds(5), null, null).events().stream()
                .filter(event -> runId.equals(event.id()))
                .findFirst().orElseThrow();

        assertEquals(TimelineEventType.BOOT_FAILED, boot.type());
        assertEquals("BOOT", boot.origin());
        assertEquals("exchange not ready", boot.reason());
        assertEquals("mode=FAIL_FAST failurePhase=phase1.infrastructure durationMs=1500", boot.detail());
        assertTrue(timeline.find(startedAt, startedAt.plusSeconds(5), UUID.randomUUID(), null).events().isEmpty(),
                "boot nao e ligado a portfolio");
    }

    // ---------------------------------------------------------------- helpers

    private UUID createRunner() {
        RestingBuyCancelStrategy strategy = new RestingBuyCancelStrategy(ScenarioStrategyConfig.boundedConfig(1));
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

    private UUID insertProtectiveExit(UUID runnerId, Instant at) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO transactions(id, runner_id, client_order_id, status, type, symbol, quantity, price, total,
                                         reasoning, requested_at, updated_at, order_type)
                VALUES (?, ?, ?, 'FILLED', 'SELL', ?, 0.001, 65000, 65, ?, ?, ?, 'MARKET')
                """, id, runnerId, "t59-" + id, SYMBOL,
                PositionProtection.EXIT_REASONING_PREFIX + "MAX_LOSS: lot below limit", Timestamp.from(at), Timestamp.from(at));
        return id;
    }

    /** Ja resolvida: uma entrada pendente acionaria o gatilho de Safe Mode da DLQ durante o teste. */
    private void insertDeadLetter(UUID portfolioId, UUID runnerId, Instant at) {
        jdbcTemplate.update("""
                INSERT INTO dead_letter_entries(id, portfolio_id, runner_id, client_order_id, raw_payload, reason,
                                                is_resolved, created_at)
                VALUES (?, ?, ?, 'x1-dlq', '{}', 'UNKNOWN_RUNNER', TRUE, ?)
                """, UUID.randomUUID(), portfolioId, runnerId, Timestamp.from(at));
    }

    private void insertOperatorAction(Instant at) {
        jdbcTemplate.update("""
                INSERT INTO operator_actions(id, actor, method, path, reason, http_status, duration_ms, occurred_at)
                VALUES (?, 'claude', 'POST', '/api/admin/runners/x/halt', 'test', 200, 5, ?)
                """, UUID.randomUUID(), Timestamp.from(at));
    }

    private static String describe(TimelineEvent event) {
        return event.type() + " " + event.from() + ">" + event.to() + " " + event.origin() + " " + event.reason();
    }
}
