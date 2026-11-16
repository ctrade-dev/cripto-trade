package com.marmitt.application.spring.bootstrap;

import com.marmitt.application.spring.testsupport.DatabaseCleaner;
import com.marmitt.core.application.usecase.portfolio.AutomaticSafeModeTrigger;
import com.marmitt.core.application.usecase.portfolio.ResumeSafeModeAfterExchangeRecoveryUseCase;
import com.marmitt.core.application.usecase.runner.WatchdogFailures;
import com.marmitt.core.dto.portfolio.request.ChangeSafeModeRequest;
import com.marmitt.core.dto.portfolio.request.CreatePortfolioRequest;
import com.marmitt.core.enums.SafeModeOrigin;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.enums.SafeModeTrigger;
import com.marmitt.core.ports.inbound.portfolio.CreatePortfolioPort;
import com.marmitt.core.ports.inbound.portfolio.ManageSafeModePort;
import com.marmitt.core.ports.outbound.metrics.CircuitBreakerMetricsPort;
import com.marmitt.core.ports.outbound.notification.ErrorNotificationPort;
import com.marmitt.core.ports.outbound.operations.InfrastructureSignalsPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T71 contra o banco real: o HALT automatico por EXCHANGE_UNAVAILABLE volta a NORMAL depois dos minutos de exchange
 * saudavel, com o gatilho na auditoria; HALT por drawdown e HALT manual nao voltam. A retomada usa os beans reais e
 * um relogio controlado no lugar do relogio do sistema.
 */
class SafeModeAutoResumeIntegrationTest extends AbstractIntegrationTest {

    private static final Duration HEALTHY_FOR = Duration.ofMinutes(5);

    @Autowired private CreatePortfolioPort createPortfolio;
    @Autowired private ManageSafeModePort manageSafeMode;
    @Autowired private AutomaticSafeModeTrigger automaticSafeModeTrigger;
    @Autowired private PortfolioRepositoryPort portfolioRepository;
    @Autowired private StrategyRunnerRepositoryPort runnerRepository;
    @Autowired private InfrastructureSignalsPort infrastructureSignals;
    @Autowired private ExchangeAdapterRepositoryPort exchangeAdapters;
    @Autowired private WebSocketConnectionRepositoryPort connections;
    @Autowired private WatchdogFailures watchdogFailures;
    @Autowired private CircuitBreakerMetricsPort metrics;
    @Autowired private ErrorNotificationPort notification;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final MutableClock clock = new MutableClock(Instant.now());
    private ResumeSafeModeAfterExchangeRecoveryUseCase resume;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.reset(jdbcTemplate);
        resume = new ResumeSafeModeAfterExchangeRecoveryUseCase(portfolioRepository, runnerRepository, manageSafeMode,
                infrastructureSignals, exchangeAdapters, connections, watchdogFailures, metrics, notification, clock,
                HEALTHY_FOR, 3);
    }

    @Test
    void exchangeUnavailableHaltResumesAndOtherHaltsStay() {
        UUID exchange = portfolio("t71-exchange");
        UUID drawdown = portfolio("t71-drawdown");
        UUID manual = portfolio("t71-manual");
        automaticSafeModeTrigger.activate(exchange, SafeModeTrigger.EXCHANGE_UNAVAILABLE, "cycles=3");
        automaticSafeModeTrigger.activate(drawdown, SafeModeTrigger.MAX_DRAWDOWN_EXCEEDED, "drawdown=0.06");
        manageSafeMode.change(manual, SafeModeOrigin.MANUAL,
                ChangeSafeModeRequest.manual(SafeModeStatus.HALT, "operator halt", false));

        resume.evaluate();
        clock.advance(HEALTHY_FOR.minusSeconds(1));
        resume.evaluate();
        assertEquals(SafeModeStatus.HALT, manageSafeMode.status(exchange).level(), "antes dos minutos de saude");

        clock.advance(Duration.ofSeconds(1));
        resume.evaluate();

        assertEquals(SafeModeStatus.NORMAL, manageSafeMode.status(exchange).level());
        assertEquals(SafeModeOrigin.AUTOMATIC, manageSafeMode.status(exchange).origin());
        assertEquals(SafeModeStatus.HALT, manageSafeMode.status(drawdown).level());
        assertEquals(SafeModeStatus.HALT, manageSafeMode.status(manual).level());

        List<Map<String, Object>> audit = jdbcTemplate.queryForList(
                "SELECT to_status, origin, trigger, reason FROM safe_mode_changes WHERE portfolio_id = ? "
                        + "ORDER BY changed_at", exchange);
        assertEquals(2, audit.size());
        assertEquals("HALT", audit.get(0).get("to_status"));
        assertEquals("EXCHANGE_UNAVAILABLE", audit.get(0).get("trigger"));
        assertEquals("NORMAL", audit.get(1).get("to_status"));
        assertEquals("AUTOMATIC", audit.get(1).get("origin"));
        assertEquals("EXCHANGE_UNAVAILABLE", audit.get(1).get("trigger"));
        assertEquals("AUTO_RESUME EXCHANGE_UNAVAILABLE: exchange healthy for 5m", audit.get(1).get("reason"));
        assertEquals(null, jdbcTemplate.queryForObject(
                "SELECT safe_mode_trigger FROM portfolios WHERE id = ?", String.class, manual));
    }

    private UUID portfolio(String name) {
        return createPortfolio.execute(CreatePortfolioRequest.builder()
                .name(name + "-" + UUID.randomUUID())
                .initialCapitalAmount(new BigDecimal("1000"))
                .currency("USDT")
                .build()).portfolioId();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
