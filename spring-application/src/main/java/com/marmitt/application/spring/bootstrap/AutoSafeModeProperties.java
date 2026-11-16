package com.marmitt.application.spring.bootstrap;

import com.marmitt.core.enums.SafeModeTrigger;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Gatilhos automáticos de Safe Mode e de halt de runner (T25). Valores decididos pelo usuário em
 * 2026-10-19. {@code enabled=false} desliga todos sem afetar o Safe Mode manual nem o drawdown (T43,
 * que tem a própria chave {@code portfolio.circuit-breaker.enabled}).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "safe-mode.auto")
public class AutoSafeModeProperties {

    private boolean enabled = true;
    private long evaluationIntervalMs = 30_000L;
    private Dlq dlq = new Dlq();
    private BalanceDivergence balanceDivergence = new BalanceDivergence();
    private Watchdog watchdog = new Watchdog();
    private Rejections rejections = new Rejections();
    private Resume resume = new Resume();

    /** Gatilhos de HALT do portfolio ligados; o drawdown sempre entra (governado pela T43). */
    public Set<SafeModeTrigger> enabledTriggers() {
        Set<SafeModeTrigger> triggers = EnumSet.of(SafeModeTrigger.MAX_DRAWDOWN_EXCEEDED);
        if (enabled && dlq.isEnabled()) {
            triggers.add(SafeModeTrigger.DLQ_THRESHOLD);
        }
        if (enabled && balanceDivergence.isEnabled()) {
            triggers.add(SafeModeTrigger.BALANCE_DIVERGENCE);
        }
        if (enabled && watchdog.isEnabled()) {
            triggers.add(SafeModeTrigger.WATCHDOG_STUCK);
            triggers.add(SafeModeTrigger.EXCHANGE_UNAVAILABLE);
        }
        return triggers;
    }

    /** Limite efetivo de rejeições consecutivas; desligado = nunca atinge. */
    public int effectiveMaxConsecutiveRejections() {
        return enabled && rejections.isEnabled() ? rejections.getMaxConsecutive() : Integer.MAX_VALUE;
    }

    @Getter
    @Setter
    public static class Toggle {
        private boolean enabled = true;
    }

    @Getter
    @Setter
    public static class BalanceDivergence extends Toggle {
        /** Intervalo da conferencia de saldo por exchange durante a operação (T57): uma consulta de conta por exchange. */
        private long evaluationIntervalMs = 300_000L;
    }

    @Getter
    @Setter
    public static class Dlq extends Toggle {
        /** Entradas não resolvidas no portfolio que acionam HALT. */
        private int threshold = 3;
    }

    @Getter
    @Setter
    public static class Watchdog extends Toggle {
        /** Ciclos consecutivos sem resolver a mesma transação que acionam HALT. */
        private int maxStuckCycles = 3;
    }

    /**
     * Retomada automatica do HALT por exchange fora do ar (T71). Valores aprovados pelo mantenedor em 2026-11-15.
     */
    @Getter
    @Setter
    public static class Resume extends Toggle {
        /** Minutos seguidos de exchange saudavel antes de voltar a NORMAL. */
        private int healthyMinutes = 5;
        /** Retomadas automaticas por portfolio em 24h; acima disso o HALT fica para o operador. */
        private int maxPer24h = 3;
    }

    @Getter
    @Setter
    public static class Rejections extends Toggle {
        /** Ordens rejeitadas seguidas que param o runner. */
        private int maxConsecutive = 5;
    }
}
