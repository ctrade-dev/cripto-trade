package com.marmitt.core.application.usecase.portfolio;

import com.marmitt.core.application.usecase.runner.WatchdogFailures;
import com.marmitt.core.application.usecase.websocket.StreamDemand;
import com.marmitt.core.application.usecase.websocket.StreamGaps;
import com.marmitt.core.domain.portfolio.Portfolio;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.dto.notification.ErrorNotificationEvent;
import com.marmitt.core.dto.portfolio.request.ChangeSafeModeRequest;
import com.marmitt.core.enums.SafeModeOrigin;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.enums.SafeModeTrigger;
import com.marmitt.core.exceptions.SafeModeTransitionException;
import com.marmitt.core.ports.inbound.portfolio.ManageSafeModePort;
import com.marmitt.core.ports.inbound.portfolio.ResumeSafeModeAfterExchangeRecoveryPort;
import com.marmitt.core.ports.outbound.metrics.CircuitBreakerMetricsPort;
import com.marmitt.core.ports.outbound.notification.ErrorNotificationPort;
import com.marmitt.core.ports.outbound.operations.InfrastructureSignalsPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Retomada automática do HALT por exchange fora do ar (T71; exceção à retomada manual do Blueprint 11.1.C).
 *
 * <p>A cada tick, para cada portfolio ativo em HALT automático de gatilho {@link SafeModeTrigger#autoResumable()}:
 * <ol>
 *   <li><b>Exchange saudável agora:</b> circuito REST fechado e envio de ordens liberado nas exchanges dos runners,
 *       streams que eles precisam conectados ({@link StreamGaps}) e nenhum runner do portfolio com transação sem
 *       resolver no último ciclo do watchdog ({@link WatchdogFailures}).</li>
 *   <li><b>Saudável há {@code healthyFor} seguidos:</b> guarda desde quando está saudável; tick não saudável zera.
 *       Só em memória: depois de um restart a contagem recomeça.</li>
 *   <li><b>Teto:</b> no máximo {@code maxPer24h} retomadas automáticas por portfolio nas últimas 24h, contadas na
 *       auditoria ({@code safe_mode_changes}). No teto, o HALT fica para o operador e o alerta sai uma vez.</li>
 * </ol>
 * Atendidas as três, muda para NORMAL com origem {@code AUTOMATIC} pelo {@link ManageSafeModePort}: a regra de
 * quem pode descer fica no {@link Portfolio}, e uma mudança do operador no meio torna a origem {@code MANUAL} e a
 * retomada é recusada.
 */
@Slf4j
public class ResumeSafeModeAfterExchangeRecoveryUseCase implements ResumeSafeModeAfterExchangeRecoveryPort {

    private static final Duration WINDOW = Duration.ofHours(24);

    private final PortfolioRepositoryPort portfolioRepository;
    private final StrategyRunnerRepositoryPort runnerRepository;
    private final ManageSafeModePort manageSafeMode;
    private final InfrastructureSignalsPort infrastructure;
    private final ExchangeAdapterRepositoryPort exchangeAdapters;
    private final StreamGaps streamGaps;
    private final WatchdogFailures watchdogFailures;
    private final CircuitBreakerMetricsPort metrics;
    private final ErrorNotificationPort notification;
    private final Clock clock;
    private final Duration healthyFor;
    private final int maxPer24h;

    /** Desde quando a exchange de cada portfolio em HALT retomável está saudável sem interrupção. */
    private final Map<UUID, Instant> healthySince = new HashMap<>();
    /** Portfolios cujo alerta de teto já saiu neste HALT. */
    private final Set<UUID> limitAlerted = new HashSet<>();

    public ResumeSafeModeAfterExchangeRecoveryUseCase(PortfolioRepositoryPort portfolioRepository,
                                                      StrategyRunnerRepositoryPort runnerRepository,
                                                      ManageSafeModePort manageSafeMode,
                                                      InfrastructureSignalsPort infrastructure,
                                                      ExchangeAdapterRepositoryPort exchangeAdapters,
                                                      WebSocketConnectionRepositoryPort connections,
                                                      WatchdogFailures watchdogFailures,
                                                      CircuitBreakerMetricsPort metrics,
                                                      ErrorNotificationPort notification,
                                                      Clock clock,
                                                      Duration healthyFor,
                                                      int maxPer24h) {
        if (healthyFor.isNegative() || healthyFor.isZero()) {
            throw new IllegalArgumentException("healthyFor must be positive: " + healthyFor);
        }
        if (maxPer24h <= 0) {
            throw new IllegalArgumentException("maxPer24h must be positive: " + maxPer24h);
        }
        this.portfolioRepository = portfolioRepository;
        this.runnerRepository = runnerRepository;
        this.manageSafeMode = manageSafeMode;
        this.infrastructure = infrastructure;
        this.exchangeAdapters = exchangeAdapters;
        this.streamGaps = new StreamGaps(connections, exchangeAdapters);
        this.watchdogFailures = watchdogFailures;
        this.metrics = metrics;
        this.notification = notification;
        this.clock = clock;
        this.healthyFor = healthyFor;
        this.maxPer24h = maxPer24h;
    }

    @Override
    public void evaluate() {
        Set<UUID> halted = new HashSet<>();
        for (Portfolio portfolio : portfolioRepository.findAll()) {
            if (!portfolio.isActive() || !portfolio.isInAutoResumableHalt()) {
                continue;
            }
            halted.add(portfolio.getId());
            try {
                evaluate(portfolio);
            } catch (RuntimeException e) {
                log.error("autoResume: evaluation failed portfolioId={} - {}", portfolio.getId(), e.getMessage(), e);
            }
        }
        healthySince.keySet().retainAll(halted);
        limitAlerted.retainAll(halted);
    }

    private void evaluate(Portfolio portfolio) {
        UUID portfolioId = portfolio.getId();
        SafeModeTrigger trigger = portfolio.getSafeModeTrigger();
        Optional<String> problem = unhealthy(runnerRepository.findByPortfolioId(portfolioId));
        if (problem.isPresent()) {
            if (healthySince.remove(portfolioId) != null) {
                log.info("autoResume: exchange unhealthy again portfolioId={} - {}", portfolioId, problem.get());
            }
            return;
        }
        Instant now = clock.instant();
        Instant since = healthySince.computeIfAbsent(portfolioId, id -> {
            log.info("autoResume: exchange healthy portfolioId={} trigger={} - resume after {}m",
                    id, trigger, healthyFor.toMinutes());
            return now;
        });
        if (Duration.between(since, now).compareTo(healthyFor) < 0) {
            return;
        }
        long resumes = portfolioRepository.countSafeModeChanges(portfolioId, SafeModeOrigin.AUTOMATIC,
                SafeModeStatus.NORMAL, now.minus(WINDOW));
        if (resumes >= maxPer24h) {
            if (limitAlerted.add(portfolioId)) {
                String reason = "auto-resume limit reached: " + resumes + " in 24h (max " + maxPer24h
                        + ") - resume manually";
                log.error("autoResume: HALT kept portfolioId={} trigger={} - {}", portfolioId, trigger, reason);
                notification.notifyError(ErrorNotificationEvent.circuitBreakerTriggered(
                        trigger + " - portfolio " + portfolio.getName() + " stays in HALT", reason, now));
            }
            return;
        }
        String reason = "AUTO_RESUME " + trigger + ": exchange healthy for " + healthyFor.toMinutes() + "m";
        try {
            manageSafeMode.change(portfolioId, SafeModeOrigin.AUTOMATIC,
                    ChangeSafeModeRequest.automatic(SafeModeStatus.NORMAL, trigger, reason));
        } catch (SafeModeTransitionException e) {
            // Operador mudou o Safe Mode depois da leitura: a decisao passa a ser dele.
            log.warn("autoResume: resume refused portfolioId={} - {}", portfolioId, e.getMessage());
            healthySince.remove(portfolioId);
            return;
        }
        healthySince.remove(portfolioId);
        metrics.recordAutoResume(portfolioId, trigger.name());
        log.warn("autoResume: HALT -> NORMAL portfolioId={} trigger={} healthySince={} resumesIn24h={}",
                portfolioId, trigger, since, resumes + 1);
        notification.notifyError(ErrorNotificationEvent.safeModeAutoResumed(
                trigger + " - portfolio " + portfolio.getName() + " back to NORMAL", reason, now));
    }

    /**
     * Motivo de a exchange dos runners não estar saudável, ou vazio se está.
     */
    private Optional<String> unhealthy(List<StrategyRunner> runners) {
        Set<String> exchanges = new TreeSet<>();
        for (StrategyRunner runner : runners) {
            if (StreamDemand.needsStreams(runner)) {
                exchanges.add(runner.getExchangeId());
            }
        }
        List<String> openCircuits = infrastructure.openRestCircuits().stream().filter(exchanges::contains).toList();
        if (!openCircuits.isEmpty()) {
            return Optional.of("REST circuit open " + openCircuits);
        }
        List<String> blocked = exchanges.stream().filter(exchangeAdapters::isDispatchBlocked).toList();
        if (!blocked.isEmpty()) {
            return Optional.of("order dispatch blocked " + blocked);
        }
        List<StreamGaps.StreamGap> gaps = streamGaps.of(runners);
        if (!gaps.isEmpty()) {
            return Optional.of("streams not connected " + gaps.stream()
                    .map(gap -> gap.exchange() + "/" + gap.channel()).toList());
        }
        if (watchdogFailures.anyFailing(runners.stream().map(StrategyRunner::getId).toList())) {
            return Optional.of("watchdog still failing to resolve transactions");
        }
        return Optional.empty();
    }
}
