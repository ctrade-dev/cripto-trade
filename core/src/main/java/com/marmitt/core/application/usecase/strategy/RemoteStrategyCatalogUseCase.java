package com.marmitt.core.application.usecase.strategy;

import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.strategy.RemoteStrategy;
import com.marmitt.core.domain.strategy.RemoteStrategyEvent;
import com.marmitt.core.domain.strategy.RemoteStrategyEventType;
import com.marmitt.core.domain.strategy.StrategyEndpoint;
import com.marmitt.core.dto.notification.ErrorNotificationEvent;
import com.marmitt.core.dto.strategy.request.RegisterRemoteStrategyRequest;
import com.marmitt.core.dto.strategy.request.UpdateRemoteStrategyRequest;
import com.marmitt.core.dto.strategy.response.RemoteStrategyView;
import com.marmitt.core.enums.RunnerStatus;
import com.marmitt.core.exceptions.StrategyCatalogException;
import com.marmitt.core.exceptions.StrategyCatalogException.Code;
import com.marmitt.core.exceptions.StrategyUnavailableException;
import com.marmitt.core.ports.inbound.strategy.ManageRemoteStrategiesPort;
import com.marmitt.core.ports.inbound.strategy.SyncRemoteStrategiesPort;
import com.marmitt.core.ports.outbound.notification.ErrorNotificationPort;
import com.marmitt.core.ports.outbound.repository.RemoteStrategyCatalogRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import com.marmitt.core.ports.outbound.marketdata.PriceHistoryPort;
import com.marmitt.core.ports.outbound.strategy.StrategyEvaluationPort;
import com.marmitt.core.ports.outbound.strategy.StrategyEvaluationPort.RemoteStrategyIdentity;
import com.marmitt.core.ports.outbound.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Catalogo de estrategias remotas (T54): cadastro e mudancas pelo operador e o alinhamento das
 * estrategias em uso com o catalogo. Substitui o catalogo estatico da T41.
 *
 * <p><b>Um caminho de conferencia.</b> Cadastro, reabilitacao, troca de endereco e o ciclo periodico
 * passam pelo mesmo {@link #sync}: garante a {@link RemoteTradingStrategy} no repositorio de estrategias
 * e, se a entrada esta habilitada e ainda nao foi conferida neste processo, confere a identidade pelo
 * proprio servico ({@code /v2/meta}). Conferida, a estrategia passa a ser consultada pelos runners;
 * enquanto nao, eles ficam em HOLD implicito.
 *
 * <p><b>Idempotencia e historico.</b> Mudanca repetida nao falha nem gera evento; conferencia repetida
 * com o mesmo resultado tambem nao. Toda mudanca pedida pelo operador exige {@code reason}.
 *
 * <p>As operacoes que mudam estado sao serializadas: o volume e baixo (operador e um ciclo periodico) e
 * isso evita corrida entre a API e o ciclo sobre a mesma estrategia.
 */
@Slf4j
public class RemoteStrategyCatalogUseCase implements ManageRemoteStrategiesPort, SyncRemoteStrategiesPort {

    static final int SUPPORTED_CONTRACT_MAJOR = 2;

    private final RemoteStrategyCatalogRepositoryPort catalog;
    private final StrategyRepositoryPort strategyRepository;
    private final StrategyEvaluationPort evaluation;
    private final StrategyRunnerRepositoryPort runnerRepository;
    private final ErrorNotificationPort notification;
    private final Clock clock;

    public RemoteStrategyCatalogUseCase(RemoteStrategyCatalogRepositoryPort catalog,
                                        StrategyRepositoryPort strategyRepository,
                                        StrategyEvaluationPort evaluation,
                                        StrategyRunnerRepositoryPort runnerRepository,
                                        ErrorNotificationPort notification,
                                        Clock clock) {
        this.catalog = catalog;
        this.strategyRepository = strategyRepository;
        this.evaluation = evaluation;
        this.runnerRepository = runnerRepository;
        this.notification = notification;
        this.clock = clock;
    }

    // ============================================================
    // Operador
    // ============================================================

    @Override
    public synchronized RemoteStrategyView register(RegisterRemoteStrategyRequest request, String why) {
        String reason = requireReason(why);
        if (request.strategyRef() == null) {
            throw invalid("strategyRef is required");
        }
        StrategyEndpoint endpoint = endpoint(request.baseUrl(), request.token());
        String name = requireText(request.name(), "name");
        String version = requireText(request.version(), "version");

        Optional<RemoteStrategy> existing = catalog.findByRef(request.strategyRef());
        if (existing.isPresent()) {
            if (existing.get().sameDefinition(name, version, endpoint)) {
                return view(existing.get());
            }
            throw new StrategyCatalogException(Code.STRATEGY_DEFINITION_CONFLICT, "Strategy " + request.strategyRef()
                    + " is already registered with a different name, version, baseUrl or token;"
                    + " use PATCH to change the endpoint or register a new strategyRef for a new version");
        }
        Optional<TradingStrategy> inUse = strategyRepository.findById(request.strategyRef());
        if (inUse.isPresent() && !(inUse.get() instanceof RemoteTradingStrategy)) {
            throw new StrategyCatalogException(Code.STRATEGY_REF_IN_USE, "strategyRef " + request.strategyRef()
                    + " belongs to the built-in strategy " + inUse.get().getStrategyName());
        }

        Instant now = clock.instant();
        RemoteStrategy strategy = RemoteStrategy.register(request.strategyRef(), name, version, endpoint, now);
        List<RemoteStrategyEvent> events = new ArrayList<>();
        events.add(RemoteStrategyEvent.of(strategy.getStrategyRef(), RemoteStrategyEventType.REGISTERED, reason,
                name + " " + version + " at " + endpoint.baseUrl(), now));
        events.addAll(sync(strategy));
        catalog.save(strategy, events);
        log.info("remoteStrategy: registered strategyRef={} name={} version={} endpoint={} verification={} reason={}",
                strategy.getStrategyRef(), name, version, endpoint, strategy.getVerification(), reason);
        return view(strategy);
    }

    @Override
    public synchronized RemoteStrategyView update(UUID strategyRef, UpdateRemoteStrategyRequest request,
                                                  String why) {
        String reason = requireReason(why);
        RemoteStrategy strategy = load(strategyRef);
        StrategyEndpoint current = strategy.getEndpoint();
        StrategyEndpoint requested = endpoint(
                request.baseUrl() != null ? request.baseUrl() : current.baseUrl(),
                request.token() != null ? request.token() : current.token());
        Instant now = clock.instant();
        if (!strategy.changeEndpoint(requested, now)) {
            return view(strategy);
        }
        runtime(strategy).ifPresent(remote -> remote.setEnabled(false));
        List<RemoteStrategyEvent> events = new ArrayList<>();
        events.add(RemoteStrategyEvent.of(strategyRef, RemoteStrategyEventType.ENDPOINT_CHANGED, reason,
                endpointChange(current, requested), now));
        events.addAll(sync(strategy));
        catalog.save(strategy, events);
        log.info("remoteStrategy: endpoint changed strategyRef={} {} verification={} reason={}",
                strategyRef, endpointChange(current, requested), strategy.getVerification(), reason);
        return view(strategy);
    }

    @Override
    public synchronized RemoteStrategyView enable(UUID strategyRef, String reason) {
        String why = requireReason(reason);
        RemoteStrategy strategy = load(strategyRef);
        Instant now = clock.instant();
        if (!strategy.enable(now)) {
            return view(strategy);
        }
        List<RemoteStrategyEvent> events = new ArrayList<>();
        events.add(RemoteStrategyEvent.of(strategyRef, RemoteStrategyEventType.ENABLED, why, null, now));
        events.addAll(sync(strategy));
        catalog.save(strategy, events);
        log.info("remoteStrategy: enabled strategyRef={} verification={} reason={}",
                strategyRef, strategy.getVerification(), why);
        return view(strategy);
    }

    @Override
    public synchronized RemoteStrategyView disable(UUID strategyRef, String reason) {
        String why = requireReason(reason);
        RemoteStrategy strategy = load(strategyRef);
        Instant now = clock.instant();
        if (!strategy.disable(now)) {
            return view(strategy);
        }
        runtime(strategy).ifPresent(remote -> remote.setEnabled(false));
        catalog.save(strategy, List.of(
                RemoteStrategyEvent.of(strategyRef, RemoteStrategyEventType.DISABLED, why, null, now)));
        RemoteStrategyView view = view(strategy);
        List<RemoteStrategyView.RunnerRef> active = view.runners().stream()
                .filter(runner -> runner.status() == RunnerStatus.ACTIVE)
                .toList();
        log.warn("remoteStrategy: disabled strategyRef={} activeRunnersOnHold={} reason={}",
                strategyRef, active.size(), why);
        if (!active.isEmpty()) {
            notification.notifyError(ErrorNotificationEvent.strategyDisabled(
                    "Strategy " + strategy.getName() + " " + strategy.getVersion() + " disabled",
                    active.size() + " active runner(s) on HOLD until it is enabled again: "
                            + active.stream().map(RemoteStrategyView.RunnerRef::shortCode).toList()
                            + ". Reason: " + why,
                    now));
        }
        return view;
    }

    @Override
    public List<RemoteStrategyView> list() {
        return catalog.findAll().stream().map(this::view).toList();
    }

    @Override
    public RemoteStrategyView get(UUID strategyRef) {
        return view(load(strategyRef));
    }

    @Override
    public List<RemoteStrategyEvent> events(UUID strategyRef) {
        load(strategyRef);
        return catalog.findEvents(strategyRef);
    }

    // ============================================================
    // Ciclo periodico
    // ============================================================

    @Override
    public synchronized void syncAll() {
        for (RemoteStrategy strategy : catalog.findAll()) {
            try {
                // Conferencia que acontece neste ciclo grava o horario da tentativa mesmo sem mudanca de
                // resultado: o operador ve que a conferencia segue acontecendo.
                boolean verifying = strategy.isEnabled()
                        && !runtime(strategy).map(RemoteTradingStrategy::isEnabled).orElse(false);
                List<RemoteStrategyEvent> events = sync(strategy);
                if (verifying || !events.isEmpty()) {
                    catalog.save(strategy, events);
                }
            } catch (RuntimeException e) {
                // Uma entrada com falha inesperada nao pode impedir as demais.
                log.error("remoteStrategy: sync failed strategyRef={} - {}", strategy.getStrategyRef(), e.toString(), e);
            }
        }
    }

    // ============================================================
    // Alinhamento com o repositorio de estrategias
    // ============================================================

    /**
     * Garante a estrategia no repositorio com o endereco do catalogo e, se habilitada e ainda nao
     * conferida neste processo, confere a identidade. Muda {@code strategy} em memoria; quem chama grava.
     *
     * @return eventos de conferencia (so quando o resultado mudou)
     */
    private List<RemoteStrategyEvent> sync(RemoteStrategy strategy) {
        Optional<RemoteTradingStrategy> found = ensureRuntime(strategy);
        if (found.isEmpty()) {
            return List.of();
        }
        RemoteTradingStrategy remote = found.get();
        if (!strategy.isEnabled()) {
            remote.setEnabled(false);
            return List.of();
        }
        if (remote.isEnabled()) {
            return List.of();
        }
        return verify(strategy, remote);
    }

    private Optional<RemoteTradingStrategy> ensureRuntime(RemoteStrategy strategy) {
        Optional<TradingStrategy> registered = strategyRepository.findById(strategy.getStrategyRef());
        if (registered.isEmpty()) {
            RemoteTradingStrategy remote = new RemoteTradingStrategy(strategy.getStrategyRef(), strategy.getName(),
                    strategy.getVersion(), strategy.getEndpoint(), evaluation);
            strategyRepository.registerStrategy(remote);
            return Optional.of(remote);
        }
        if (!(registered.get() instanceof RemoteTradingStrategy remote)) {
            log.error("remoteStrategy: strategyRef={} collides with built-in strategy {} - catalog entry ignored",
                    strategy.getStrategyRef(), registered.get().getStrategyName());
            return Optional.empty();
        }
        if (!remote.endpoint().equals(strategy.getEndpoint())) {
            remote.setEnabled(false);
            remote.changeEndpoint(strategy.getEndpoint());
        }
        return Optional.of(remote);
    }

    private List<RemoteStrategyEvent> verify(RemoteStrategy strategy, RemoteTradingStrategy remote) {
        Instant now = clock.instant();
        String failure;
        RemoteStrategyIdentity served = null;
        try {
            served = evaluation.describe(strategy.getStrategyRef(), strategy.getEndpoint());
            failure = divergence(strategy, served);
        } catch (StrategyUnavailableException e) {
            failure = "service unavailable (" + e.reason() + "): " + e.getMessage();
        } catch (RuntimeException e) {
            failure = "unexpected failure: " + e;
        }

        if (failure == null) {
            remote.requirePriceHistoryTicks(served.priceHistoryTicks());
            remote.setEnabled(true);
            if (strategy.markVerified(served.contractVersion(), served.priceHistoryTicks(), now)) {
                log.info("remoteStrategy: verified strategyRef={} name={} version={} contract={}",
                        strategy.getStrategyRef(), served.name(), served.version(), served.contractVersion());
                return List.of(RemoteStrategyEvent.of(strategy.getStrategyRef(), RemoteStrategyEventType.VERIFIED,
                        null, served.name() + " " + served.version() + " contract " + served.contractVersion(), now));
            }
            return List.of();
        }
        remote.setEnabled(false);
        if (strategy.markFailed(failure, now)) {
            log.error("remoteStrategy: verification FAILED strategyRef={} - {}", strategy.getStrategyRef(), failure);
            return List.of(RemoteStrategyEvent.of(strategy.getStrategyRef(),
                    RemoteStrategyEventType.VERIFICATION_FAILED, null, failure, now));
        }
        return List.of();
    }

    private static String divergence(RemoteStrategy expected, RemoteStrategyIdentity served) {
        if (!expected.getStrategyRef().equals(served.strategyId())) {
            return "service declares strategyId " + served.strategyId();
        }
        if (!expected.getName().equals(served.name()) || !expected.getVersion().equals(served.version())) {
            return "service declares " + served.name() + " " + served.version()
                    + ", catalog expects " + expected.getName() + " " + expected.getVersion();
        }
        if (!isSupportedContract(served.contractVersion())) {
            return "unsupported contract version " + served.contractVersion()
                    + " (supported major " + SUPPORTED_CONTRACT_MAJOR + ")";
        }
        int ticks = served.priceHistoryTicks();
        if (ticks < 0 || ticks > PriceHistoryPort.MAX_TICKS) {
            return "service requires " + ticks + " price history ticks, supported 0 to " + PriceHistoryPort.MAX_TICKS;
        }
        if (expected.getPriceHistoryTicks() != null && expected.getPriceHistoryTicks() != ticks) {
            return "service requires " + ticks + " price history ticks, catalog expects "
                    + expected.getPriceHistoryTicks() + " - a changed requirement needs a new strategyRef";
        }
        return null;
    }

    private static boolean isSupportedContract(String contractVersion) {
        if (contractVersion == null) {
            return false;
        }
        String major = contractVersion.split("\\.", 2)[0];
        return String.valueOf(SUPPORTED_CONTRACT_MAJOR).equals(major);
    }

    // ============================================================
    // Apoio
    // ============================================================

    private Optional<RemoteTradingStrategy> runtime(RemoteStrategy strategy) {
        return strategyRepository.findById(strategy.getStrategyRef())
                .filter(RemoteTradingStrategy.class::isInstance)
                .map(RemoteTradingStrategy.class::cast);
    }

    private RemoteStrategyView view(RemoteStrategy strategy) {
        List<RemoteStrategyView.RunnerRef> runners = runnerRepository.findAll().stream()
                .filter(runner -> strategy.getStrategyRef().equals(runner.getStrategyId()))
                .map(RemoteStrategyCatalogUseCase::runnerRef)
                .toList();
        boolean usable = runtime(strategy).map(RemoteTradingStrategy::isEnabled).orElse(false);
        return new RemoteStrategyView(strategy.getStrategyRef(), strategy.getName(), strategy.getVersion(),
                strategy.getEndpoint().baseUrl(), strategy.getEndpoint().hasToken(), strategy.getStatus(),
                strategy.getVerification(), usable, strategy.getLastError(), strategy.getLastVerificationAt(),
                strategy.getServedContractVersion(), strategy.getPriceHistoryTicks(), strategy.getCreatedAt(), strategy.getUpdatedAt(), runners);
    }

    private static RemoteStrategyView.RunnerRef runnerRef(StrategyRunner runner) {
        return new RemoteStrategyView.RunnerRef(runner.getId(), runner.getShortCode(), runner.getSymbol(),
                runner.getStatus());
    }

    private RemoteStrategy load(UUID strategyRef) {
        return catalog.findByRef(strategyRef).orElseThrow(() -> new StrategyCatalogException(
                Code.STRATEGY_NOT_FOUND, "No remote strategy registered with strategyRef " + strategyRef));
    }

    private static StrategyEndpoint endpoint(String baseUrl, String token) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw invalid("baseUrl is required");
        }
        try {
            return new StrategyEndpoint(baseUrl, token);
        } catch (IllegalArgumentException e) {
            throw invalid(e.getMessage());
        }
    }

    private static String endpointChange(StrategyEndpoint before, StrategyEndpoint after) {
        String change = before.baseUrl().equals(after.baseUrl())
                ? "baseUrl unchanged"
                : "baseUrl " + before.baseUrl() + " -> " + after.baseUrl();
        boolean tokenChanged = before.hasToken() != after.hasToken()
                || (before.hasToken() && !before.token().equals(after.token()));
        return change + (tokenChanged ? ", token changed" : ", token unchanged");
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new StrategyCatalogException(Code.REASON_REQUIRED, "reason is required for every catalog change");
        }
        return reason.strip();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required");
        }
        return value.strip();
    }

    private static StrategyCatalogException invalid(String message) {
        return new StrategyCatalogException(Code.INVALID_STRATEGY_DEFINITION, message);
    }
}
