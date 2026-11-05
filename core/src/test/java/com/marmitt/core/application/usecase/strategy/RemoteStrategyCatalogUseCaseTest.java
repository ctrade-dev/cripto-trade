package com.marmitt.core.application.usecase.strategy;

import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.strategy.RemoteStrategy;
import com.marmitt.core.domain.strategy.RemoteStrategyEvent;
import com.marmitt.core.domain.strategy.RemoteStrategyEventType;
import com.marmitt.core.domain.strategy.RemoteStrategyStatus;
import com.marmitt.core.domain.strategy.RemoteStrategyVerification;
import com.marmitt.core.domain.strategy.StrategyEndpoint;
import com.marmitt.core.dto.notification.ErrorNotificationEvent;
import com.marmitt.core.dto.notification.ErrorNotificationType;
import com.marmitt.core.dto.strategy.request.RegisterRemoteStrategyRequest;
import com.marmitt.core.dto.strategy.request.UpdateRemoteStrategyRequest;
import com.marmitt.core.dto.strategy.response.RemoteStrategyView;
import com.marmitt.core.enums.RunnerStatus;
import com.marmitt.core.exceptions.StrategyCatalogException;
import com.marmitt.core.exceptions.StrategyCatalogException.Code;
import com.marmitt.core.exceptions.StrategyUnavailableException;
import com.marmitt.core.ports.outbound.notification.ErrorNotificationPort;
import com.marmitt.core.ports.outbound.repository.RemoteStrategyCatalogRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import com.marmitt.core.ports.outbound.strategy.StrategyEvaluationPort;
import com.marmitt.core.ports.outbound.strategy.StrategyEvaluationPort.RemoteStrategyIdentity;
import com.marmitt.core.ports.outbound.strategy.TradingStrategy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T54: catalogo dinamico de estrategias remotas — cadastro, mudancas idempotentes com historico e a
 * conferencia de identidade como unico caminho para a estrategia entrar em uso.
 */
class RemoteStrategyCatalogUseCaseTest {

    private static final UUID REF = UUID.randomUUID();
    private static final String URL = "http://strategy:8090/v1";
    private static final RemoteStrategyIdentity IDENTITY = new RemoteStrategyIdentity(REF, "SMA", "1.0.0", "2.0.0", 20);

    private final InMemoryCatalog catalog = new InMemoryCatalog();
    private final InMemoryStrategies strategies = new InMemoryStrategies();
    private final StrategyEvaluationPort evaluation = mock(StrategyEvaluationPort.class);
    private final StrategyRunnerRepositoryPort runners = mock(StrategyRunnerRepositoryPort.class);
    private final ErrorNotificationPort notification = mock(ErrorNotificationPort.class);
    private final RemoteStrategyCatalogUseCase useCase = new RemoteStrategyCatalogUseCase(catalog, strategies,
            evaluation, runners, notification, Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void registerVerifiesOnTheSpotAndPutsTheStrategyInUse() {
        when(evaluation.describe(eq(REF), any())).thenReturn(IDENTITY);

        RemoteStrategyView view = useCase.register(request("token-1"), "first deploy");

        assertEquals(RemoteStrategyStatus.ENABLED, view.status());
        assertEquals(RemoteStrategyVerification.VERIFIED, view.verification());
        assertTrue(view.usable());
        assertTrue(view.tokenConfigured());
        assertEquals("2.0.0", view.servedContractVersion());
        assertEquals(20, view.priceHistoryTicks());
        assertEquals(20, strategies.findById(REF).orElseThrow().requiredPriceHistoryTicks());
        assertTrue(strategies.findById(REF).orElseThrow().isEnabled());
        assertEquals(List.of(RemoteStrategyEventType.REGISTERED, RemoteStrategyEventType.VERIFIED), eventTypes());
        assertEquals("first deploy", catalog.events.get(REF).getFirst().reason());
    }

    @Test
    void serviceDownStillRegistersAsFailedAndTheRetryPutsItInUse() {
        when(evaluation.describe(eq(REF), any())).thenThrow(
                new StrategyUnavailableException(StrategyUnavailableException.Reason.UNREACHABLE, "connection refused"));

        RemoteStrategyView view = useCase.register(request(null), "first deploy");

        assertEquals(RemoteStrategyVerification.FAILED, view.verification());
        assertFalse(view.usable());
        assertTrue(view.lastError().contains("UNREACHABLE"), view.lastError());
        assertFalse(strategies.findById(REF).orElseThrow().isEnabled(), "registrada, mas fora de uso");

        useCase.syncAll();
        assertEquals(List.of(RemoteStrategyEventType.REGISTERED, RemoteStrategyEventType.VERIFICATION_FAILED),
                eventTypes(), "falha repetida pelo mesmo motivo nao gera evento novo");

        when(evaluation.describe(eq(REF), any())).thenReturn(IDENTITY);
        useCase.syncAll();

        RemoteStrategyView recovered = useCase.get(REF);
        assertEquals(RemoteStrategyVerification.VERIFIED, recovered.verification());
        assertTrue(recovered.usable());
        assertNull(recovered.lastError());
        assertEquals(RemoteStrategyEventType.VERIFIED, eventTypes().getLast());
    }

    @Test
    void divergentIdentityOrContractIsNeverUsed() {
        for (RemoteStrategyIdentity served : List.of(
                new RemoteStrategyIdentity(REF, "SMA", "2.0.0", "2.0.0", 20),
                new RemoteStrategyIdentity(REF, "SMA", "1.0.0", "1.1.0", 20),
                new RemoteStrategyIdentity(REF, "SMA", "1.0.0", "2.0.0", -1),
                new RemoteStrategyIdentity(REF, "SMA", "1.0.0", "2.0.0", 1001),
                new RemoteStrategyIdentity(UUID.randomUUID(), "SMA", "1.0.0", "2.0.0", 20))) {
            InMemoryStrategies fresh = new InMemoryStrategies();
            StrategyEvaluationPort port = mock(StrategyEvaluationPort.class);
            when(port.describe(eq(REF), any())).thenReturn(served);
            RemoteStrategyCatalogUseCase isolated = new RemoteStrategyCatalogUseCase(new InMemoryCatalog(), fresh,
                    port, runners, notification, Clock.systemUTC());

            RemoteStrategyView view = isolated.register(request(null), "first deploy");

            assertEquals(RemoteStrategyVerification.FAILED, view.verification(), served.toString());
            assertFalse(fresh.findById(REF).orElseThrow().isEnabled(), served.toString());
        }
    }

    /** T69: mudou metadado, e outra estrategia. O requisito gravado nao e sobrescrito pela nova declaracao. */
    @Test
    void aChangedRequirementWithoutANewStrategyRefIsNeverUsed() {
        Instant now = Instant.parse("2026-10-20T12:00:00Z");
        catalog.save(new RemoteStrategy(REF, "SMA", "1.0.0", new StrategyEndpoint(URL, null),
                RemoteStrategyStatus.ENABLED, RemoteStrategyVerification.PENDING, null, null, "2.0.0", 50, now, now),
                List.of());
        when(evaluation.describe(eq(REF), any())).thenReturn(IDENTITY);

        useCase.syncAll();

        RemoteStrategy stored = catalog.findByRef(REF).orElseThrow();
        assertEquals(RemoteStrategyVerification.FAILED, stored.getVerification());
        assertTrue(stored.getLastError().contains("new strategyRef"), stored.getLastError());
        assertEquals(50, stored.getPriceHistoryTicks());
        assertFalse(strategies.findById(REF).orElseThrow().isEnabled());
    }

    @Test
    void repeatingTheSameRegistrationIsANoOpAndADifferentOneIsAConflict() {
        when(evaluation.describe(eq(REF), any())).thenReturn(IDENTITY);
        useCase.register(request("token-1"), "first deploy");

        RemoteStrategyView again = useCase.register(request("token-1"), "first deploy");
        assertEquals(RemoteStrategyVerification.VERIFIED, again.verification());
        assertEquals(2, catalog.events.get(REF).size(), "cadastro repetido nao gera evento");
        verify(evaluation, times(1)).describe(eq(REF), any());

        StrategyCatalogException conflict = assertThrows(StrategyCatalogException.class,
                () -> useCase.register(request("token-2"), "first deploy"));
        assertEquals(Code.STRATEGY_DEFINITION_CONFLICT, conflict.catalogCode());
    }

    @Test
    void builtInStrategyIdCannotBeTakenByTheCatalog() {
        TradingStrategy builtIn = mock(TradingStrategy.class);
        when(builtIn.getStrategyId()).thenReturn(REF);
        when(builtIn.getStrategyName()).thenReturn("SimpleMovingAverageStrategy");
        strategies.registerStrategy(builtIn);

        StrategyCatalogException e = assertThrows(StrategyCatalogException.class,
                () -> useCase.register(request(null), "first deploy"));
        assertEquals(Code.STRATEGY_REF_IN_USE, e.catalogCode());
    }

    @Test
    void everyChangeRequiresAReasonAndInvalidInputHasItsOwnCode() {
        assertEquals(Code.REASON_REQUIRED, assertThrows(StrategyCatalogException.class,
                () -> useCase.register(request(null), " ")).catalogCode());
        assertEquals(Code.INVALID_STRATEGY_DEFINITION, assertThrows(StrategyCatalogException.class,
                () -> useCase.register(new RegisterRemoteStrategyRequest(REF, "SMA", "1.0.0", "ftp://x/v1", null),
                        "why")).catalogCode());
        assertEquals(Code.INVALID_STRATEGY_DEFINITION, assertThrows(StrategyCatalogException.class,
                () -> useCase.register(new RegisterRemoteStrategyRequest(null, "SMA", "1.0.0", URL, null),
                        "why")).catalogCode());
        assertEquals(Code.STRATEGY_NOT_FOUND, assertThrows(StrategyCatalogException.class,
                () -> useCase.disable(UUID.randomUUID(), "why")).catalogCode());
    }

    @Test
    void disableTakesTheStrategyOutOfUseAndAlertsWhenActiveRunnersAreLeftOnHold() {
        when(evaluation.describe(eq(REF), any())).thenReturn(IDENTITY);
        useCase.register(request(null), "first deploy");
        StrategyRunner active = runner(RunnerStatus.ACTIVE);
        StrategyRunner archived = runner(RunnerStatus.ARCHIVED);
        when(runners.findAll()).thenReturn(List.of(active, archived));

        RemoteStrategyView view = useCase.disable(REF, "strategy losing money");

        assertEquals(RemoteStrategyStatus.DISABLED, view.status());
        assertFalse(view.usable());
        assertEquals(2, view.runners().size());
        assertFalse(strategies.findById(REF).orElseThrow().isEnabled());
        ArgumentCaptor<ErrorNotificationEvent> alert = ArgumentCaptor.forClass(ErrorNotificationEvent.class);
        verify(notification).notifyError(alert.capture());
        assertEquals(ErrorNotificationType.STRATEGY_DISABLED, alert.getValue().type());

        useCase.disable(REF, "again");
        assertEquals(RemoteStrategyEventType.DISABLED, eventTypes().getLast());
        assertEquals(1, eventTypes().stream().filter(RemoteStrategyEventType.DISABLED::equals).count(),
                "desabilitar de novo nao gera evento");
        verify(notification, times(1)).notifyError(any());

        useCase.syncAll();
        assertFalse(strategies.findById(REF).orElseThrow().isEnabled(), "o ciclo nao reativa desabilitada");
    }

    @Test
    void enableVerifiesAgainBeforeUse() {
        when(evaluation.describe(eq(REF), any())).thenReturn(IDENTITY);
        useCase.register(request(null), "first deploy");
        when(runners.findAll()).thenReturn(List.of());
        useCase.disable(REF, "pause");

        RemoteStrategyView view = useCase.enable(REF, "resume");

        assertTrue(view.usable());
        verify(evaluation, times(2)).describe(eq(REF), any());
        assertEquals(RemoteStrategyEventType.ENABLED, eventTypes().get(3));
    }

    @Test
    void endpointChangeIsVerifiedAtTheNewAddressAndUsedOnTheNextCall() {
        when(evaluation.describe(eq(REF), any())).thenReturn(IDENTITY);
        useCase.register(request("token-1"), "first deploy");

        RemoteStrategyView view = useCase.update(REF,
                new UpdateRemoteStrategyRequest("http://strategy-b:9000/v1/", null), "moved to new host");

        StrategyEndpoint moved = new StrategyEndpoint("http://strategy-b:9000/v1", "token-1");
        assertEquals("http://strategy-b:9000/v1", view.baseUrl());
        assertTrue(view.usable());
        verify(evaluation).describe(REF, moved);
        assertEquals(moved, ((RemoteTradingStrategy) strategies.findById(REF).orElseThrow()).endpoint());
        RemoteStrategyEvent change = catalog.events.get(REF).stream()
                .filter(event -> event.type() == RemoteStrategyEventType.ENDPOINT_CHANGED).findFirst().orElseThrow();
        assertTrue(change.detail().contains("token unchanged"), change.detail());

        useCase.update(REF, new UpdateRemoteStrategyRequest("http://strategy-b:9000/v1", null), "same");
        assertEquals(1, eventTypes().stream().filter(RemoteStrategyEventType.ENDPOINT_CHANGED::equals).count(),
                "mesmo endereco nao gera evento");
    }

    @Test
    void tokenNeverLeaksInTheEndpointDescription() {
        assertFalse(new StrategyEndpoint(URL, "secret-token").toString().contains("secret-token"));
    }

    @Test
    void bootRegistersCatalogEntriesAndOnlyVerifiesTheEnabledOnes() {
        Instant now = Instant.parse("2026-10-20T12:00:00Z");
        UUID disabledRef = UUID.randomUUID();
        catalog.save(new RemoteStrategy(REF, "SMA", "1.0.0", new StrategyEndpoint(URL, null),
                RemoteStrategyStatus.ENABLED, RemoteStrategyVerification.VERIFIED, null, now, "2.0.0", 20, now, now), List.of());
        catalog.save(new RemoteStrategy(disabledRef, "OLD", "0.9.0", new StrategyEndpoint(URL, null),
                RemoteStrategyStatus.DISABLED, RemoteStrategyVerification.VERIFIED, null, now, "2.0.0", 20, now, now), List.of());
        when(evaluation.describe(eq(REF), any())).thenReturn(IDENTITY);

        useCase.syncAll();

        assertTrue(strategies.findById(REF).orElseThrow().isEnabled());
        assertFalse(strategies.findById(disabledRef).orElseThrow().isEnabled());
        verify(evaluation, never()).describe(eq(disabledRef), any());
        assertTrue(catalog.events.getOrDefault(REF, List.of()).isEmpty(), "conferida de novo, mesmo resultado");
    }

    // ---------------------------------------------------------------- helpers

    private static RegisterRemoteStrategyRequest request(String token) {
        return new RegisterRemoteStrategyRequest(REF, "SMA", "1.0.0", URL, token);
    }

    private List<RemoteStrategyEventType> eventTypes() {
        return catalog.events.getOrDefault(REF, List.of()).stream().map(RemoteStrategyEvent::type).toList();
    }

    private static StrategyRunner runner(RunnerStatus status) {
        StrategyRunner runner = mock(StrategyRunner.class);
        when(runner.getId()).thenReturn(UUID.randomUUID());
        when(runner.getStrategyId()).thenReturn(REF);
        when(runner.getShortCode()).thenReturn("r" + status.name().toLowerCase().substring(0, 3));
        when(runner.getSymbol()).thenReturn("BTCUSDT");
        when(runner.getStatus()).thenReturn(status);
        return runner;
    }

    private static final class InMemoryCatalog implements RemoteStrategyCatalogRepositoryPort {
        private final Map<UUID, RemoteStrategy> byRef = new LinkedHashMap<>();
        private final Map<UUID, List<RemoteStrategyEvent>> events = new LinkedHashMap<>();

        @Override
        public Optional<RemoteStrategy> findByRef(UUID strategyRef) {
            return Optional.ofNullable(byRef.get(strategyRef));
        }

        @Override
        public List<RemoteStrategy> findAll() {
            return new ArrayList<>(byRef.values());
        }

        @Override
        public void save(RemoteStrategy strategy, List<RemoteStrategyEvent> newEvents) {
            byRef.put(strategy.getStrategyRef(), strategy);
            if (!newEvents.isEmpty()) {
                events.computeIfAbsent(strategy.getStrategyRef(), ref -> new ArrayList<>()).addAll(newEvents);
            }
        }

        @Override
        public List<RemoteStrategyEvent> findEvents(UUID strategyRef) {
            return events.getOrDefault(strategyRef, List.of());
        }
    }

    private static final class InMemoryStrategies implements StrategyRepositoryPort {
        private final Map<UUID, TradingStrategy> byId = new ConcurrentHashMap<>();

        @Override
        public Optional<TradingStrategy> findById(UUID strategyId) {
            return Optional.ofNullable(byId.get(strategyId));
        }

        @Override
        public void registerStrategy(TradingStrategy strategy) {
            byId.putIfAbsent(strategy.getStrategyId(), strategy);
        }
    }
}
