package com.marmitt.core.application.usecase.portfolio;

import com.marmitt.core.domain.portfolio.Portfolio;
import com.marmitt.core.dto.notification.ErrorNotificationEvent;
import com.marmitt.core.dto.portfolio.request.ChangeSafeModeRequest;
import com.marmitt.core.enums.SafeModeOrigin;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.enums.SafeModeTrigger;
import com.marmitt.core.ports.inbound.portfolio.ManageSafeModePort;
import com.marmitt.core.ports.outbound.metrics.CircuitBreakerMetricsPort;
import com.marmitt.core.ports.outbound.notification.ErrorNotificationPort;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Caminho único do HALT automático do portfolio (T43, T25): todo gatilho — drawdown, DLQ, divergência
 * de saldo, watchdog — passa por aqui.
 *
 * <ul>
 *   <li>Só age com o portfolio em NORMAL: com Safe Mode já ativo (automático ou manual, inclusive nível
 *       mais alto) não repete mudança, métrica, log nem alerta.</li>
 *   <li>Muda o nível pelo {@link ManageSafeModePort} com origem {@code AUTOMATIC} (auditado em
 *       {@code safe_mode_changes}), conta em {@code circuit_breaker.activations.total} e notifica.</li>
 *   <li>Gatilho fora de {@code enabledTriggers} é ignorado (configuração por gatilho).</li>
 * </ul>
 */
@Slf4j
public class AutomaticSafeModeTrigger {

    private final PortfolioRepositoryPort portfolioRepository;
    private final ManageSafeModePort manageSafeMode;
    private final CircuitBreakerMetricsPort metrics;
    private final ErrorNotificationPort notification;
    private final Clock clock;
    private final Set<SafeModeTrigger> enabledTriggers;

    public AutomaticSafeModeTrigger(PortfolioRepositoryPort portfolioRepository,
                                    ManageSafeModePort manageSafeMode,
                                    CircuitBreakerMetricsPort metrics,
                                    ErrorNotificationPort notification,
                                    Clock clock,
                                    Set<SafeModeTrigger> enabledTriggers) {
        this.portfolioRepository = portfolioRepository;
        this.manageSafeMode = manageSafeMode;
        this.metrics = metrics;
        this.notification = notification;
        this.clock = clock;
        this.enabledTriggers = enabledTriggers.isEmpty()
                ? EnumSet.noneOf(SafeModeTrigger.class)
                : EnumSet.copyOf(enabledTriggers);
    }

    /**
     * @param details contexto legível do disparo (valores medidos e limite)
     * @return {@code true} quando o HALT foi acionado agora
     */
    public boolean activate(UUID portfolioId, SafeModeTrigger trigger, String details) {
        if (!enabledTriggers.contains(trigger)) {
            log.debug("autoSafeMode: trigger {} disabled - portfolioId={} {}", trigger, portfolioId, details);
            return false;
        }
        Portfolio portfolio = portfolioRepository.findById(portfolioId).orElse(null);
        if (portfolio == null || portfolio.getSafeModeStatus() != SafeModeStatus.NORMAL) {
            return false;
        }
        String reason = trigger.name() + " " + details;
        manageSafeMode.change(portfolioId, SafeModeOrigin.AUTOMATIC,
                ChangeSafeModeRequest.automatic(SafeModeStatus.HALT, trigger, reason));
        metrics.recordActivation(portfolioId, trigger.name());
        log.error("autoSafeMode: HALT activated portfolioId={} trigger={} {}", portfolioId, trigger, details);
        notification.notifyError(ErrorNotificationEvent.circuitBreakerTriggered(
                trigger.name() + " - portfolio " + portfolio.getName() + " in HALT", reason, clock.instant()));
        return true;
    }
}
