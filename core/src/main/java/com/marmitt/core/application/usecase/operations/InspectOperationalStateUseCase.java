package com.marmitt.core.application.usecase.operations;

import com.marmitt.core.application.usecase.exchange.ExchangeBalanceReconciliation;
import com.marmitt.core.application.usecase.websocket.OpenLotDemand;
import com.marmitt.core.domain.portfolio.GlobalBalance;
import com.marmitt.core.domain.portfolio.Portfolio;
import com.marmitt.core.domain.runner.Position;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.domain.strategy.RemoteStrategy;
import com.marmitt.core.dto.connection.ConnectionKey;
import com.marmitt.core.dto.operations.ExchangeComparison;
import com.marmitt.core.dto.operations.OperationalCheck;
import com.marmitt.core.dto.operations.OperationalSeverity;
import com.marmitt.core.dto.operations.OperationalStateReport;
import com.marmitt.core.dto.exchange.ExchangeBalanceReport;
import com.marmitt.core.dto.reconciliation.ReconciliationRequest;
import com.marmitt.core.dto.reconciliation.ReconciliationSummaryDto;
import com.marmitt.core.enums.ConnectionStatus;
import com.marmitt.core.enums.RunnerStatus;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.enums.StreamChannel;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.ports.inbound.operations.InspectOperationalStatePort;
import com.marmitt.core.ports.inbound.reconciliation.ReconcileTradesPort;
import com.marmitt.core.ports.outbound.exchange.ExchangeAdapterDescriptor;
import com.marmitt.core.ports.outbound.marketdata.LatestPricePort;
import com.marmitt.core.ports.outbound.operations.InfrastructureSignalsPort;
import com.marmitt.core.ports.outbound.repository.DeadLetterEntryRepositoryPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.GlobalBalanceRepositoryPort;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import com.marmitt.core.ports.outbound.repository.RemoteStrategyCatalogRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;
import com.marmitt.core.ports.outbound.strategy.TradingStrategy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Estado operacional consolidado (T55): cada verificacao tem {@code id} estavel, severidade, sujeitos
 * afetados e a secao do runbook com a acao. Somente leitura; os limiares sao decisao de negocio da T55.
 *
 * <p>Comparacao com a exchange (Parte C2) so quando pedida ({@link ExchangeComparison}): reaproveita a
 * conferencia de saldo por exchange do boot ({@link ExchangeBalanceReconciliation}) e a reconciliacao de trades
 * ({@link ReconcileTradesPort}, ausente sem exchange com historico de trades configurada).
 */
public class InspectOperationalStateUseCase implements InspectOperationalStatePort {

    static final String RUNBOOK = "docs/OPERATIONS_RUNBOOK.md#";
    /** DLQ: a partir de 3 entradas e CRITICAL, como o HALT automatico da T25. */
    static final int DLQ_CRITICAL = 3;
    /** Transacao presa: alem do limiar do watchdog e de 3 ciclos dele e CRITICAL, como na T25. */
    static final int STUCK_CRITICAL_CYCLES = 3;

    private final PortfolioRepositoryPort portfolioRepository;
    private final GlobalBalanceRepositoryPort balanceRepository;
    private final StrategyRunnerRepositoryPort runnerRepository;
    private final DeadLetterEntryRepositoryPort deadLetterRepository;
    private final StrategyRepositoryPort strategyRepository;
    private final RemoteStrategyCatalogRepositoryPort catalogRepository;
    private final LatestPricePort latestPrices;
    private final WebSocketConnectionRepositoryPort connections;
    private final ExchangeAdapterRepositoryPort exchangeAdapters;
    private final InfrastructureSignalsPort infrastructure;
    private final ExchangeBalanceReconciliation balanceReconciliation;
    private final ReconcileTradesPort tradeReconciliation;
    private final Thresholds thresholds;
    private final Clock clock;
    private final OpenLotDemand openLotDemand;

    public InspectOperationalStateUseCase(PortfolioRepositoryPort portfolioRepository,
                                          GlobalBalanceRepositoryPort balanceRepository,
                                          StrategyRunnerRepositoryPort runnerRepository,
                                          DeadLetterEntryRepositoryPort deadLetterRepository,
                                          StrategyRepositoryPort strategyRepository,
                                          RemoteStrategyCatalogRepositoryPort catalogRepository,
                                          LatestPricePort latestPrices,
                                          WebSocketConnectionRepositoryPort connections,
                                          ExchangeAdapterRepositoryPort exchangeAdapters,
                                          InfrastructureSignalsPort infrastructure,
                                          ExchangeBalanceReconciliation balanceReconciliation,
                                          ReconcileTradesPort tradeReconciliation,
                                          Thresholds thresholds,
                                          Clock clock) {
        this.portfolioRepository = portfolioRepository;
        this.balanceRepository = balanceRepository;
        this.runnerRepository = runnerRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.strategyRepository = strategyRepository;
        this.catalogRepository = catalogRepository;
        this.latestPrices = latestPrices;
        this.connections = connections;
        this.exchangeAdapters = exchangeAdapters;
        this.infrastructure = infrastructure;
        this.balanceReconciliation = balanceReconciliation;
        this.tradeReconciliation = tradeReconciliation;
        this.thresholds = thresholds;
        this.clock = clock;
        this.openLotDemand = new OpenLotDemand(runnerRepository);
    }

    @Override
    public OperationalStateReport inspect(ExchangeComparison exchange) {
        Instant now = clock.instant();
        List<Portfolio> portfolios = portfolioRepository.findAll();
        List<StrategyRunner> runners = runnerRepository.findAll();
        List<StrategyRunner> active = runners.stream().filter(r -> r.getStatus() == RunnerStatus.ACTIVE).toList();
        Map<UUID, List<Transaction>> inFlightByRunner = new LinkedHashMap<>();
        for (StrategyRunner runner : runners) {
            if (runner.getStatus() != RunnerStatus.ARCHIVED) {
                inFlightByRunner.put(runner.getId(),
                        runnerRepository.findByRunnerIdAndStatuses(runner.getId(), TransactionStatus.IN_FLIGHT));
            }
        }

        List<OperationalCheck> checks = new ArrayList<>();
        checks.add(bootNotReady());
        checks.add(safeModeActive(portfolios));
        checks.add(capitalReservedWithoutOrder(portfolios, runners, inFlightByRunner));
        checks.add(capitalReservationStale(runners, inFlightByRunner, now));
        checks.add(transactionStuck(runners, inFlightByRunner, now));
        checks.add(positionLockOrphan(portfolios));
        checks.add(dlqUnresolved(portfolios));
        checks.add(runnersIn("RUNNER_HALTED", RunnerStatus.HALTED, runners, "halted runners do not trade"));
        checks.add(runnerReconciling(runners));
        checks.add(runnerStuckTerminating(runners, now));
        checks.add(runnerOnHoldStrategyUnusable(active));
        checks.add(strategyVerificationFailed());
        checks.add(marketDataStale(active, runners, now));
        checks.add(streamDisconnected(active));
        checks.add(orderDispatchBlocked(active));
        checks.add(restCircuitOpen());
        if (exchange.enabled()) {
            checks.add(exchangeBalanceDivergence());
            checks.add(exchangeTradeDivergence(runners, exchange.tradeWindow(), now));
        }
        return OperationalStateReport.of(now, checks);
    }

    // ============================================================
    // Verificacoes
    // ============================================================

    private OperationalCheck bootNotReady() {
        InfrastructureSignalsPort.BootState boot = infrastructure.bootState();
        if (boot.completed()) {
            return ok("BOOT_NOT_READY", "boot completed");
        }
        return check("BOOT_NOT_READY", OperationalSeverity.CRITICAL, "boot is " + boot.status()
                        + (boot.detail() == null ? "" : ": " + boot.detail()),
                List.of(subject("bootStatus", boot.status())), null);
    }

    private OperationalCheck safeModeActive(List<Portfolio> portfolios) {
        List<Portfolio> active = portfolios.stream().filter(p -> p.getSafeModeStatus().isActive()).toList();
        if (active.isEmpty()) {
            return ok("SAFE_MODE_ACTIVE", "every portfolio in NORMAL");
        }
        boolean massAction = active.stream().anyMatch(p -> p.getSafeModeStatus() != SafeModeStatus.HALT);
        return check("SAFE_MODE_ACTIVE", massAction ? OperationalSeverity.CRITICAL : OperationalSeverity.WARN,
                active.size() + " portfolio(s) in safe mode",
                active.stream().map(p -> subject("portfolioId", p.getId(), "level", p.getSafeModeStatus(),
                        "origin", p.getSafeModeOrigin(), "reason", p.getSafeModeReason(),
                        "since", p.getSafeModeChangedAt())).toList(),
                oldest(active.stream().map(Portfolio::getSafeModeChangedAt)));
    }

    /**
     * Saldo reservado sem nada que o justifique: nenhuma BUY em voo e nenhum lote aberto no portfolio.
     * O custo de uma BUY executada fica em {@code reserved} ate a SELL do lote casar
     * ({@code GlobalBalance.confirmExecution}), entao lote aberto tambem justifica reserva (T53, rodada 1).
     */
    private OperationalCheck capitalReservedWithoutOrder(List<Portfolio> portfolios, List<StrategyRunner> runners,
                                                         Map<UUID, List<Transaction>> inFlightByRunner) {
        List<Map<String, Object>> subjects = new ArrayList<>();
        for (Portfolio portfolio : portfolios) {
            BigDecimal reserved = balanceRepository.findByPortfolioId(portfolio.getId())
                    .map(GlobalBalance::getReservedBalance).orElse(BigDecimal.ZERO);
            if (reserved.signum() <= 0) {
                continue;
            }
            boolean buyInFlight = runners.stream()
                    .filter(runner -> runner.getPortfolioId().equals(portfolio.getId()))
                    .flatMap(runner -> inFlightByRunner.getOrDefault(runner.getId(), List.of()).stream())
                    .anyMatch(Transaction::isBuy);
            if (!buyInFlight && runnerRepository.findActivePositionsByPortfolioId(portfolio.getId()).isEmpty()) {
                subjects.add(subject("portfolioId", portfolio.getId(), "reserved", reserved.toPlainString()));
            }
        }
        if (subjects.isEmpty()) {
            return ok("CAPITAL_RESERVED_WITHOUT_ORDER", "every reservation has a BUY in flight or an open lot");
        }
        return check("CAPITAL_RESERVED_WITHOUT_ORDER", OperationalSeverity.CRITICAL,
                subjects.size() + " portfolio(s) with reserved capital and no BUY in flight nor open lot",
                subjects, null);
    }

    private OperationalCheck capitalReservationStale(List<StrategyRunner> runners,
                                                     Map<UUID, List<Transaction>> inFlightByRunner, Instant now) {
        List<Transaction> stale = new ArrayList<>();
        Map<UUID, UUID> portfolioByTransaction = new LinkedHashMap<>();
        for (StrategyRunner runner : runners) {
            for (Transaction tx : inFlightByRunner.getOrDefault(runner.getId(), List.of())) {
                if (tx.isBuy() && olderThan(tx.getRequestedAt(), thresholds.reservationStale(), now)) {
                    stale.add(tx);
                    portfolioByTransaction.put(tx.getId(), runner.getPortfolioId());
                }
            }
        }
        if (stale.isEmpty()) {
            return ok("CAPITAL_RESERVATION_STALE", "no BUY in flight older than " + thresholds.reservationStale());
        }
        return check("CAPITAL_RESERVATION_STALE", OperationalSeverity.WARN,
                stale.size() + " BUY order(s) holding capital for more than " + thresholds.reservationStale(),
                stale.stream().map(tx -> subject("portfolioId", portfolioByTransaction.get(tx.getId()),
                        "runnerId", tx.getRunnerId(), "transactionId", tx.getId(), "status", tx.getStatus(),
                        "total", tx.getTotal().toPlainString(), "requestedAt", tx.getRequestedAt())).toList(),
                oldest(stale.stream().map(Transaction::getRequestedAt)));
    }

    /**
     * Ordem sem confirmacao da exchange ({@code PENDING}) alem do limiar do watchdog. Ordem confirmada que
     * descansa no livro ({@code SUBMITTED}/{@code PARTIAL}) e normal e nao entra aqui.
     */
    private OperationalCheck transactionStuck(List<StrategyRunner> runners,
                                              Map<UUID, List<Transaction>> inFlightByRunner, Instant now) {
        Duration critical = thresholds.watchdogStaleThreshold()
                .plus(thresholds.watchdogInterval().multipliedBy(STUCK_CRITICAL_CYCLES));
        List<Transaction> stuck = runners.stream()
                .flatMap(runner -> inFlightByRunner.getOrDefault(runner.getId(), List.of()).stream())
                .filter(tx -> tx.getStatus() == TransactionStatus.PENDING)
                .filter(tx -> olderThan(tx.getUpdatedAt(), thresholds.watchdogStaleThreshold(), now))
                .toList();
        if (stuck.isEmpty()) {
            return ok("TRANSACTION_STUCK", "no order waiting for exchange acknowledgement");
        }
        boolean beyondCycles = stuck.stream().anyMatch(tx -> olderThan(tx.getUpdatedAt(), critical, now));
        return check("TRANSACTION_STUCK", beyondCycles ? OperationalSeverity.CRITICAL : OperationalSeverity.WARN,
                stuck.size() + " order(s) PENDING without exchange acknowledgement",
                stuck.stream().map(tx -> subject("runnerId", tx.getRunnerId(), "transactionId", tx.getId(),
                        "clientOrderId", tx.getClientOrderId(), "type", tx.getType(),
                        "updatedAt", tx.getUpdatedAt())).toList(),
                oldest(stuck.stream().map(Transaction::getUpdatedAt)));
    }

    /**
     * Lote travado por uma SELL que ja terminou (ou nao existe): nunca mais sera vendido.
     */
    private OperationalCheck positionLockOrphan(List<Portfolio> portfolios) {
        List<Map<String, Object>> subjects = new ArrayList<>();
        List<Instant> since = new ArrayList<>();
        for (Portfolio portfolio : portfolios) {
            for (Position position : runnerRepository.findActivePositionsByPortfolioId(portfolio.getId())) {
                if (!position.isLocked()) {
                    continue;
                }
                Optional<Transaction> lock = runnerRepository.findTransactionById(position.getLockedByTransactionId());
                if (lock.isEmpty() || !lock.get().getStatus().isInFlight()) {
                    subjects.add(subject("runnerId", position.getRunnerId(), "positionId", position.getId(),
                            "lockedByTransactionId", position.getLockedByTransactionId(),
                            "lockStatus", lock.map(Transaction::getStatus).map(Enum::name).orElse("MISSING")));
                    since.add(position.getLockedAt());
                }
            }
        }
        if (subjects.isEmpty()) {
            return ok("POSITION_LOCK_ORPHAN", "every locked lot has its SELL in flight");
        }
        return check("POSITION_LOCK_ORPHAN", OperationalSeverity.CRITICAL,
                subjects.size() + " lot(s) locked by a SELL that is no longer in flight", subjects,
                oldest(since.stream()));
    }

    private OperationalCheck dlqUnresolved(List<Portfolio> portfolios) {
        List<Map<String, Object>> subjects = new ArrayList<>();
        long total = 0;
        for (Portfolio portfolio : portfolios) {
            long unresolved = deadLetterRepository.countUnresolvedByPortfolioId(portfolio.getId());
            if (unresolved > 0) {
                total += unresolved;
                subjects.add(subject("portfolioId", portfolio.getId(), "unresolved", unresolved));
            }
        }
        if (total == 0) {
            return ok("DLQ_UNRESOLVED", "no unresolved dead letter");
        }
        boolean critical = subjects.stream().anyMatch(s -> ((Long) s.get("unresolved")) >= DLQ_CRITICAL);
        return check("DLQ_UNRESOLVED", critical ? OperationalSeverity.CRITICAL : OperationalSeverity.WARN,
                total + " unresolved dead letter(s)", subjects, null);
    }

    private OperationalCheck runnersIn(String id, RunnerStatus status, List<StrategyRunner> runners, String meaning) {
        List<StrategyRunner> found = runners.stream().filter(r -> r.getStatus() == status).toList();
        if (found.isEmpty()) {
            return ok(id, "no runner " + status);
        }
        return check(id, OperationalSeverity.WARN, found.size() + " runner(s) " + status + ": " + meaning,
                found.stream().map(InspectOperationalStateUseCase::runnerSubject).toList(),
                oldest(found.stream().map(StrategyRunner::getStatusChangedAt)));
    }

    private OperationalCheck runnerReconciling(List<StrategyRunner> runners) {
        List<StrategyRunner> found = runners.stream().filter(StrategyRunner::isReconciling).toList();
        if (found.isEmpty()) {
            return ok("RUNNER_RECONCILING", "no runner reconciling");
        }
        return check("RUNNER_RECONCILING", OperationalSeverity.WARN,
                found.size() + " runner(s) reconciling (not trading until it completes)",
                found.stream().map(InspectOperationalStateUseCase::runnerSubject).toList(),
                oldest(found.stream().map(StrategyRunner::getStatusChangedAt)));
    }

    private OperationalCheck runnerStuckTerminating(List<StrategyRunner> runners, Instant now) {
        List<StrategyRunner> found = runners.stream()
                .filter(r -> r.getStatus() == RunnerStatus.TERMINATING)
                .filter(r -> olderThan(r.getStatusChangedAt(), thresholds.terminationTimeout(), now))
                .toList();
        if (found.isEmpty()) {
            return ok("RUNNER_STUCK_TERMINATING", "no runner stuck in TERMINATING");
        }
        return check("RUNNER_STUCK_TERMINATING", OperationalSeverity.WARN,
                found.size() + " runner(s) in TERMINATING for more than " + thresholds.terminationTimeout(),
                found.stream().map(InspectOperationalStateUseCase::runnerSubject).toList(),
                oldest(found.stream().map(StrategyRunner::getStatusChangedAt)));
    }

    private OperationalCheck runnerOnHoldStrategyUnusable(List<StrategyRunner> active) {
        List<StrategyRunner> onHold = active.stream()
                .filter(runner -> strategyRepository.findById(runner.getStrategyId())
                        .map(TradingStrategy::isEnabled).orElse(false) == false)
                .toList();
        if (onHold.isEmpty()) {
            return ok("RUNNER_ON_HOLD_STRATEGY_UNUSABLE", "every ACTIVE runner has a usable strategy");
        }
        return check("RUNNER_ON_HOLD_STRATEGY_UNUSABLE", OperationalSeverity.WARN,
                onHold.size() + " ACTIVE runner(s) on HOLD: strategy missing, disabled or not verified",
                onHold.stream().map(r -> subject("runnerId", r.getId(), "shortCode", r.getShortCode(),
                        "strategyId", r.getStrategyId(), "strategyName", r.getStrategyName())).toList(), null);
    }

    private OperationalCheck strategyVerificationFailed() {
        List<RemoteStrategy> failing = catalogRepository.findAll().stream()
                .filter(RemoteStrategy::isEnabled)
                .filter(s -> strategyRepository.findById(s.getStrategyRef()).map(TradingStrategy::isEnabled)
                        .orElse(false) == false)
                .toList();
        if (failing.isEmpty()) {
            return ok("STRATEGY_VERIFICATION_FAILED", "every enabled remote strategy is usable");
        }
        return check("STRATEGY_VERIFICATION_FAILED", OperationalSeverity.WARN,
                failing.size() + " enabled remote strategy(ies) not usable",
                failing.stream().map(s -> subject("strategyRef", s.getStrategyRef(), "name", s.getName(),
                        "version", s.getVersion(), "verification", s.getVerification(),
                        "lastError", s.getLastError())).toList(),
                oldest(failing.stream().map(RemoteStrategy::getLastVerificationAt)));
    }

    /**
     * Simbolo sem tick alem do limiar. Com runner {@code ACTIVE} e CRITICAL (opera sem preco); so com lote
     * aberto de runner parado e WARN (ninguem opera, mas drawdown e PnL ficam sem preco; T58, decisao
     * 2026-10-21). {@code demand} diz qual dos dois.
     */
    private OperationalCheck marketDataStale(List<StrategyRunner> active, List<StrategyRunner> runners, Instant now) {
        Map<String, String> demandBySymbol = new TreeMap<>();
        openLotDemand.runnersWithOpenLots(runners).forEach(r -> demandBySymbol.put(r.getSymbol(), "OPEN_LOT"));
        active.forEach(r -> demandBySymbol.put(r.getSymbol(), "ACTIVE_RUNNER"));
        List<Map<String, Object>> subjects = new ArrayList<>();
        List<Instant> since = new ArrayList<>();
        boolean activeStale = false;
        for (Map.Entry<String, String> entry : demandBySymbol.entrySet()) {
            Optional<Instant> updatedAt = latestPrices.findUpdatedAt(entry.getKey());
            if (updatedAt.isEmpty() || olderThan(updatedAt.get(), thresholds.marketDataStale(), now)) {
                subjects.add(subject("symbol", entry.getKey(), "demand", entry.getValue(),
                        "lastTickAt", updatedAt.orElse(null)));
                updatedAt.ifPresent(since::add);
                activeStale |= entry.getValue().equals("ACTIVE_RUNNER");
            }
        }
        if (subjects.isEmpty()) {
            return ok("MARKET_DATA_STALE", "every symbol with an ACTIVE runner or an open lot ticked in the last "
                    + thresholds.marketDataStale());
        }
        return check("MARKET_DATA_STALE", activeStale ? OperationalSeverity.CRITICAL : OperationalSeverity.WARN,
                subjects.size() + " symbol(s) with ACTIVE runners or open lots and no tick in the last "
                        + thresholds.marketDataStale(), subjects, oldest(since.stream()));
    }

    /**
     * Conexao registrada que nao esta conectada, e stream de usuario ausente para exchange que precisa
     * dele, com runner ACTIVE. Market data parado e coberto por {@code MARKET_DATA_STALE}.
     */
    private OperationalCheck streamDisconnected(List<StrategyRunner> active) {
        Map<ConnectionKey, ConnectionStatus> statuses = new LinkedHashMap<>();
        connections.getAllConnections().forEach((key, manager) -> statuses.put(key,
                manager.getConnectionResult() == null ? null : manager.getConnectionResult().status()));

        List<Map<String, Object>> subjects = new ArrayList<>();
        Set<String> exchanges = new TreeSet<>();
        Set<String> marketSources = new TreeSet<>();
        for (StrategyRunner runner : active) {
            exchanges.add(runner.getExchangeId());
            marketSources.addAll(runner.getAllowedMarketDataSources());
        }
        for (String exchange : exchanges) {
            boolean needsUserStream = exchangeAdapters.findAdapter(exchange)
                    .map(ExchangeAdapterDescriptor::hasUserStream).orElse(false);
            ConnectionKey key = new ConnectionKey(exchange, StreamChannel.USER_DATA);
            ConnectionStatus status = statuses.get(key);
            if (needsUserStream && status != ConnectionStatus.CONNECTED) {
                subjects.add(subject("exchange", exchange, "channel", StreamChannel.USER_DATA,
                        "status", status == null ? "NOT_OPENED" : status));
            }
        }
        for (String source : marketSources) {
            ConnectionKey key = ConnectionKey.market(source);
            if (statuses.containsKey(key) && statuses.get(key) != ConnectionStatus.CONNECTED) {
                subjects.add(subject("exchange", source, "channel", StreamChannel.MARKET, "status", statuses.get(key)));
            }
        }
        if (subjects.isEmpty()) {
            return ok("STREAM_DISCONNECTED", "every stream needed by ACTIVE runners is connected");
        }
        return check("STREAM_DISCONNECTED", OperationalSeverity.CRITICAL,
                subjects.size() + " stream(s) needed by ACTIVE runners not connected", subjects, null);
    }

    /**
     * Envio de ordens bloqueado por conexao perdida (T68). E um fato a parte do status das conexoes: mesmo
     * que o status minta, as ordens desses runners estao sendo recusadas aqui dentro.
     */
    private OperationalCheck orderDispatchBlocked(List<StrategyRunner> active) {
        Set<String> exchanges = new TreeSet<>();
        for (StrategyRunner runner : active) {
            if (exchangeAdapters.isDispatchBlocked(runner.getExchangeId())) {
                exchanges.add(runner.getExchangeId());
            }
        }
        if (exchanges.isEmpty()) {
            return ok("ORDER_DISPATCH_BLOCKED", "order dispatch open for every exchange with ACTIVE runners");
        }
        return check("ORDER_DISPATCH_BLOCKED", OperationalSeverity.CRITICAL,
                exchanges.size() + " exchange(s) with ACTIVE runners and order dispatch blocked by a lost connection",
                exchanges.stream().map(exchange -> subject("exchange", exchange)).toList(), null);
    }

    private OperationalCheck restCircuitOpen() {
        List<String> open = infrastructure.openRestCircuits();
        if (open.isEmpty()) {
            return ok("EXCHANGE_REST_CIRCUIT_OPEN", "every exchange REST circuit closed");
        }
        return check("EXCHANGE_REST_CIRCUIT_OPEN", OperationalSeverity.CRITICAL,
                open.size() + " exchange(s) with the REST query circuit open",
                open.stream().map(exchange -> subject("exchange", exchange)).toList(), null);
    }

    // ============================================================
    // Comparacao com a exchange (C2)
    // ============================================================

    /**
     * Conferencia de saldo por exchange (T57, mesma unidade do boot): soma dos portfolios contra a conta,
     * por moeda/ativo, um sujeito por ativo com o excedente fora dos portfolios ({@code unallocated}).
     * {@code DEFICIT} e CRITICAL; consulta que falhou e WARN (nao conferido); {@code PENDING} (falta
     * coberta por ordens em voo) e exchange sem consulta de conta nao alarmam. Somente leitura: o HALT
     * vem do boot e da avaliacao periodica.
     */
    private OperationalCheck exchangeBalanceDivergence() {
        List<Map<String, Object>> subjects = new ArrayList<>();
        OperationalSeverity worst = OperationalSeverity.OK;
        for (ExchangeBalanceReport report : balanceReconciliation.reconcileAll()) {
            OperationalSeverity severity = switch (report.status()) {
                case OK, PENDING, SKIPPED -> OperationalSeverity.OK;
                case FAILED -> OperationalSeverity.WARN;
                case DEFICIT -> OperationalSeverity.CRITICAL;
            };
            worst = worst.compareTo(severity) >= 0 ? worst : severity;
            if (report.assets().isEmpty()) {
                subjects.add(subject("exchange", report.exchange(), "result", report.status(),
                        "code", report.code(), "message", report.message(),
                        "portfolios", report.portfolios().size()));
            }
            for (ExchangeBalanceReport.AssetBalance asset : report.assets()) {
                subjects.add(subject("exchange", report.exchange(), "asset", asset.asset(),
                        "result", asset.status(), "localExpected", plain(asset.localExpected()),
                        "exchangeTotal", plain(asset.exchangeTotal()), "signedDelta", plain(asset.signedDelta()),
                        "unallocated", plain(asset.unallocated()), "inFlight", plain(asset.inFlight()),
                        "portfolios", report.portfolios().size()));
            }
        }
        String summary = switch (worst) {
            case CRITICAL -> "ctrade expects more than an exchange account holds";
            case WARN -> "exchange balance could not be checked";
            default -> "exchange accounts cover the portfolios";
        };
        return check("EXCHANGE_BALANCE_DIVERGENCE", worst, summary, subjects, null);
    }

    /**
     * Fills da janela no ctrade contra o historico de trades da exchange, por simbolo: fill so na exchange,
     * so local ou divergente e CRITICAL (o registro local nao e a verdade da conta).
     */
    private OperationalCheck exchangeTradeDivergence(List<StrategyRunner> runners, Duration window, Instant now) {
        Set<String> pairs = runners.stream()
                .filter(r -> r.getStatus() != RunnerStatus.ARCHIVED)
                .map(r -> r.getExchangeId().toUpperCase() + ":" + r.getSymbol())
                .collect(Collectors.toCollection(TreeSet::new));
        if (tradeReconciliation == null) {
            return check("EXCHANGE_TRADE_DIVERGENCE", OperationalSeverity.OK,
                    "not checked: no exchange with trade history configured",
                    pairs.stream().map(p -> subject("pair", p, "result", "NOT_CHECKED")).toList(), null);
        }
        List<Map<String, Object>> subjects = new ArrayList<>();
        OperationalSeverity worst = OperationalSeverity.OK;
        for (String pair : pairs) {
            String exchange = pair.substring(0, pair.indexOf(':'));
            String symbol = pair.substring(pair.indexOf(':') + 1);
            try {
                ReconciliationSummaryDto summary = tradeReconciliation.reconcile(
                        new ReconciliationRequest(symbol, exchange, now.minus(window), now, false)).summary();
                int problems = summary.exchangeOnly() + summary.localOnly() + summary.divergent();
                if (problems > 0) {
                    worst = OperationalSeverity.CRITICAL;
                    subjects.add(subject("exchange", exchange, "symbol", symbol, "matched", summary.matched(),
                            "exchangeOnly", summary.exchangeOnly(), "localOnly", summary.localOnly(),
                            "divergent", summary.divergent()));
                }
            } catch (IllegalArgumentException e) {
                subjects.add(subject("exchange", exchange, "symbol", symbol, "result", "NOT_CHECKED",
                        "message", e.getMessage()));
            } catch (RuntimeException e) {
                worst = worst == OperationalSeverity.CRITICAL ? worst : OperationalSeverity.WARN;
                subjects.add(subject("exchange", exchange, "symbol", symbol, "result", "QUERY_FAILED",
                        "message", e.getMessage()));
            }
        }
        if (worst == OperationalSeverity.OK) {
            return check("EXCHANGE_TRADE_DIVERGENCE", OperationalSeverity.OK,
                    "every fill in the last " + window + " matches the exchange", subjects, null);
        }
        return check("EXCHANGE_TRADE_DIVERGENCE", worst,
                worst == OperationalSeverity.CRITICAL ? "fills differ between ctrade and the exchange"
                        : "trade history could not be checked", subjects, now.minus(window));
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    // ============================================================
    // Apoio
    // ============================================================

    private static OperationalCheck ok(String id, String summary) {
        return new OperationalCheck(id, OperationalSeverity.OK, summary, List.of(), null, runbook(id));
    }

    private static OperationalCheck check(String id, OperationalSeverity status, String summary,
                                          List<Map<String, Object>> subjects, Instant since) {
        return new OperationalCheck(id, status, summary, subjects, since, runbook(id));
    }

    private static String runbook(String id) {
        return RUNBOOK + id.toLowerCase().replace('_', '-');
    }

    private static Map<String, Object> runnerSubject(StrategyRunner runner) {
        return subject("runnerId", runner.getId(), "shortCode", runner.getShortCode(), "symbol", runner.getSymbol(),
                "status", runner.getStatus(), "since", runner.getStatusChangedAt());
    }

    /**
     * Mapa de sujeito na ordem informada; valores nulos ficam de fora.
     */
    private static Map<String, Object> subject(Object... keyValues) {
        Map<String, Object> subject = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                subject.put((String) keyValues[i], value instanceof Enum<?> e ? e.name()
                        : value instanceof UUID || value instanceof Instant ? value.toString() : value);
            }
        }
        return subject;
    }

    private static boolean olderThan(Instant instant, Duration limit, Instant now) {
        return instant != null && Duration.between(instant, now).compareTo(limit) > 0;
    }

    private static Instant oldest(java.util.stream.Stream<Instant> instants) {
        return instants.filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
    }

    /**
     * Limiares das verificacoes (decisao de negocio da T55; os do watchdog e do encerramento vem da
     * configuracao desses mecanismos).
     *
     * @param marketDataStale        sem tick ha mais que isto, com runner ACTIVE: CRITICAL (60s)
     * @param reservationStale       BUY em voo segurando capital ha mais que isto: WARN (24h)
     * @param watchdogStaleThreshold limiar do watchdog de transacoes
     * @param watchdogInterval       intervalo do watchdog (3 ciclos alem do limiar: CRITICAL)
     * @param terminationTimeout     runner em TERMINATING ha mais que isto (T51)
     */
    public record Thresholds(Duration marketDataStale, Duration reservationStale, Duration watchdogStaleThreshold,
                             Duration watchdogInterval, Duration terminationTimeout) {
    }
}
