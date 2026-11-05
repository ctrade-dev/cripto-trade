package com.marmitt.application.spring.bootstrap;

import com.marmitt.core.domain.runner.RunnerStatusCause;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.marmitt.core.domain.strategy.RemoteStrategy;
import com.marmitt.core.domain.strategy.RemoteStrategyEvent;
import com.marmitt.core.domain.strategy.RemoteStrategyEventType;
import com.marmitt.core.dto.strategy.request.RegisterRemoteStrategyRequest;
import com.marmitt.core.dto.strategy.response.RemoteStrategyView;
import com.marmitt.core.ports.inbound.runner.HaltRunnerPort;
import com.marmitt.core.ports.inbound.strategy.ManageRemoteStrategiesPort;
import com.marmitt.core.ports.outbound.repository.RemoteStrategyCatalogRepositoryPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T41 — runner operando com estrategia remota pelo pipeline real. O strategy service e um
 * MockWebServer cadastrado no catalogo por API (T54, conferido via /v1/meta). Decisao valida vira
 * ordem; 5xx, timeout, decisao fora do contrato e decisao velha viram HOLD com STRATEGY_UNAVAILABLE.
 * Desabilitar no catalogo deixa o runner em HOLD; reabilitar confere de novo e o runner volta a operar.
 */
class RemoteStrategyE2ETest extends ScenarioStrategyE2ETestSupport {

    private static final UUID REF = UUID.fromString("5ea5e0a0-0000-4000-a000-0000000000e2");
    private static final UUID NEEDS_HISTORY_REF = UUID.randomUUID();
    private static volatile String lastEvaluateBody = "";
    private static final MockWebServer SERVICE = new MockWebServer();

    @Autowired
    private HaltRunnerPort haltRunner;

    @Autowired
    private ManageRemoteStrategiesPort catalog;

    @Autowired
    private RemoteStrategyCatalogRepositoryPort catalogRepository;
    private static volatile Mode mode = Mode.BUY;

    enum Mode { BUY, ERROR, SLOW, STALE, MALFORMED }

    static {
        SERVICE.setDispatcher(new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith("/v2/meta")) {
                    return json("[{\"strategyId\":\"" + REF + "\",\"name\":\"RemoteE2E\",\"version\":\"1.0.0\","
                            + "\"contractVersion\":\"2.0.0\",\"requirements\":{\"priceHistory\":{\"ticks\":1}}},"
                            + "{\"strategyId\":\"" + NEEDS_HISTORY_REF + "\",\"name\":\"NeedsHistory\",\"version\":\"1.0.0\","
                            + "\"contractVersion\":\"2.0.0\",\"requirements\":{\"priceHistory\":{\"ticks\":1000}}}]");
                }
                lastEvaluateBody = request.getBody().clone().readUtf8();
                BigDecimal limit = MARKET_PRICE.multiply(new BigDecimal("1.01"));
                String buy = "{\"strategyName\":\"RemoteE2E\",\"decision\":\"SHOULD_BUY\",\"confidence\":\"0.9\","
                        + "\"quantity\":\"0.00100000\",\"limitPrice\":\"" + limit.toPlainString() + "\",\"reasoning\":\"e2e\"}";
                return switch (mode) {
                    case BUY -> json(buy);
                    case ERROR -> new MockResponse().setResponseCode(503).setBody("{\"message\":\"down\"}");
                    case SLOW -> json(buy).setBodyDelay(2, TimeUnit.SECONDS);
                    case STALE -> json(buy).setBodyDelay(600, TimeUnit.MILLISECONDS);
                    case MALFORMED -> json(buy.replace("\"0.00100000\"", "\"-1\""));
                };
            }
        });
        try {
            SERVICE.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void remoteCatalog(DynamicPropertyRegistry registry) {
        registry.add("ctrade.strategy.remote.timeout-ms", () -> "1500");
        registry.add("ctrade.strategy.remote.max-decision-age-ms", () -> "400");
    }

    /**
     * T54: a estrategia entra pelo catalogo administrado por API. Cadastro repetido e no-op, entao cada
     * teste parte da estrategia cadastrada e habilitada.
     */
    @BeforeEach
    void registerInTheCatalog() {
        mode = Mode.BUY;
        catalog.register(new RegisterRemoteStrategyRequest(REF, "RemoteE2E", "1.0.0",
                SERVICE.url("/v2").toString(), "e2e-token"), "e2e setup");
        catalog.enable(REF, "e2e setup");
    }

    @AfterAll
    static void stopService() throws IOException {
        SERVICE.shutdown();
    }

    @Test
    void remoteDecisionBecomesAnOrderThroughTheNormalPipeline() {
        assertTrue(catalog.get(REF).usable(), "cadastrada e conferida no ato");
        UUID runnerId = createAndActivateRunner(createPortfolio(), REF);

        driveTicks(6);
        awaitCondition(() -> transactionsOfType(runnerId, "BUY").stream().anyMatch(tx -> "FILLED".equals(tx.status())),
                WAIT_TIMEOUT, "BUY decidida pela estrategia remota e preenchida", runnerId);

        assertTrue(signalEvaluatedCount(runnerId, "BUY") >= 1.0);
        assertEquals(0.0, signalEvaluatedCount(runnerId, "STRATEGY_UNAVAILABLE"));
        assertTrue(lastEvaluateBody.contains("\"priceHistory\":[{\"price\":"), lastEvaluateBody);
    }

    /** T69: sem os ticks declarados a estrategia nao e consultada, e a espera aparece na metrica. */
    @Test
    void strategyIsNotCalledUntilTheDeclaredPriceHistoryIsAvailable() {
        catalog.register(new RegisterRemoteStrategyRequest(NEEDS_HISTORY_REF, "NeedsHistory", "1.0.0",
                SERVICE.url("/v2").toString(), "e2e-token"), "e2e setup");
        assertEquals(1000, catalog.get(NEEDS_HISTORY_REF).priceHistoryTicks());
        UUID runnerId = createAndActivateRunner(createPortfolio(), NEEDS_HISTORY_REF);
        int callsBefore = SERVICE.getRequestCount();

        driveTicks(4);
        awaitCondition(() -> signalEvaluatedCount(runnerId, "WAITING_HISTORY") > 0, WAIT_TIMEOUT,
                "tick contado como espera de historico", runnerId);

        assertEquals(callsBefore, SERVICE.getRequestCount(), "a estrategia nao foi chamada");
        assertEquals(0, transactionCount(runnerId));
        haltRunner.haltById(runnerId, RunnerStatusCause.manual("test"));
    }

    @Test
    void unusableRemoteAnswersBecomeHoldWithStrategyUnavailable() {
        for (Mode failure : new Mode[]{Mode.ERROR, Mode.SLOW, Mode.STALE, Mode.MALFORMED}) {
            mode = failure;
            double failuresBefore = remoteFailures(failure);
            UUID runnerId = createAndActivateRunner(createPortfolio(), REF);

            publishTick(MARKET_PRICE);
            awaitCondition(() -> signalEvaluatedCount(runnerId, "STRATEGY_UNAVAILABLE") >= 1.0, WAIT_TIMEOUT,
                    failure + " vira STRATEGY_UNAVAILABLE", runnerId);

            assertEquals(0, transactionCount(runnerId), failure + " nao pode gerar ordem");
            assertTrue(remoteFailures(failure) > failuresBefore,
                    failure + " deve contar em strategy.remote.failures; contadores=" + allRemoteFailures());
            // Isola a proxima iteracao: runner parado nao e mais avaliado nos ticks seguintes.
            haltRunner.haltById(runnerId, RunnerStatusCause.manual("test"));
        }
    }

    @Test
    void disablingLeavesTheRunnerOnHoldAndEnablingPutsItBackToWork() {
        UUID runnerId = createAndActivateRunner(createPortfolio(), REF);
        RemoteStrategyView disabled = catalog.disable(REF, "e2e: strategy under review");
        assertFalse(disabled.usable());
        assertTrue(disabled.runners().stream().anyMatch(runner -> runner.runnerId().equals(runnerId)),
                "o catalogo mostra o runner afetado");

        int callsBefore = SERVICE.getRequestCount();
        driveTicks(4);
        sleep(QUIET_WINDOW.toMillis());
        assertEquals(0, transactionCount(runnerId), "estrategia desabilitada nao gera ordem");
        assertEquals(callsBefore, SERVICE.getRequestCount(), "estrategia desabilitada nao e consultada");

        RemoteStrategyView enabled = catalog.enable(REF, "e2e: review done");
        assertTrue(enabled.usable(), "reabilitar confere de novo e volta ao uso");
        driveTicks(6);
        awaitCondition(() -> transactionsOfType(runnerId, "BUY").stream().anyMatch(tx -> "FILLED".equals(tx.status())),
                WAIT_TIMEOUT, "runner volta a operar apos reabilitar", runnerId);
    }

    @Test
    void catalogStateAndHistoryArePersistedWithoutTheToken() {
        catalog.disable(REF, "e2e: persistence check");

        RemoteStrategy stored = catalogRepository.findByRef(REF).orElseThrow();
        assertEquals("e2e-token", stored.getEndpoint().token(), "o token fica no banco para as chamadas");
        RemoteStrategyView view = catalog.get(REF);
        assertTrue(view.tokenConfigured());
        assertFalse(view.toString().contains("e2e-token"), "o token nunca aparece na visao do operador");
        List<RemoteStrategyEventType> history = catalogRepository.findEvents(REF).stream()
                .map(RemoteStrategyEvent::type).toList();
        assertEquals(RemoteStrategyEventType.REGISTERED, history.getFirst());
        assertEquals(RemoteStrategyEventType.DISABLED, history.getLast());
        assertEquals("e2e: persistence check", catalogRepository.findEvents(REF).getLast().reason());
    }

    private String allRemoteFailures() {
        StringBuilder out = new StringBuilder();
        meterRegistry.find("strategy.remote.failures").counters().forEach(counter ->
                out.append(counter.getId().getTag("reason")).append('=').append(counter.count()).append(' '));
        return out.toString();
    }

    private double remoteFailures(Mode failure) {
        String reason = switch (failure) {
            case ERROR -> "ERROR_RESPONSE";
            case SLOW -> "UNREACHABLE";
            case STALE -> "STALE_DECISION";
            case MALFORMED -> "INVALID_RESPONSE";
            case BUY -> throw new IllegalArgumentException();
        };
        try {
            return meterRegistry.get("strategy.remote.failures")
                    .tags("strategyRef", REF.toString(), "reason", reason).counter().count();
        } catch (io.micrometer.core.instrument.search.MeterNotFoundException e) {
            return 0.0;
        }
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
