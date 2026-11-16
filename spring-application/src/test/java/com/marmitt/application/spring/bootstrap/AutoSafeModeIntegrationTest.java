package com.marmitt.application.spring.bootstrap;

import com.marmitt.application.spring.testsupport.DatabaseCleaner;
import com.marmitt.core.dto.portfolio.request.ChangeSafeModeRequest;
import com.marmitt.core.dto.portfolio.request.CreatePortfolioRequest;
import com.marmitt.core.enums.SafeModeOrigin;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.ports.inbound.portfolio.CreatePortfolioPort;
import com.marmitt.core.ports.inbound.portfolio.EvaluateOperationalTriggersPort;
import com.marmitt.core.ports.inbound.portfolio.ManageSafeModePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T25 — gatilho de DLQ contra o banco real: 3 entradas não resolvidas levam o portfolio a HALT com origem
 * AUTOMATIC, a avaliação seguinte não registra nova mudança e a retomada manual continua funcionando.
 */
class AutoSafeModeIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CreatePortfolioPort createPortfolio;

    @Autowired
    private EvaluateOperationalTriggersPort evaluateOperationalTriggers;

    @Autowired
    private ManageSafeModePort manageSafeMode;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID portfolioId;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.reset(jdbcTemplate);
        portfolioId = createPortfolio.execute(CreatePortfolioRequest.builder()
                .name("t25-" + UUID.randomUUID())
                .initialCapitalAmount(new BigDecimal("1000"))
                .currency("USDT")
                .build()).portfolioId();
    }

    @Test
    void dlqThresholdHaltsThePortfolioOnceAndManualResumeStillWorks() {
        deadLetters(2);
        evaluateOperationalTriggers.evaluate();
        assertEquals(SafeModeStatus.NORMAL, manageSafeMode.status(portfolioId).level(), "2 entradas: abaixo do limite");

        deadLetters(1);
        evaluateOperationalTriggers.evaluate();
        evaluateOperationalTriggers.evaluate();

        assertEquals(SafeModeStatus.HALT, manageSafeMode.status(portfolioId).level());
        assertEquals(SafeModeOrigin.AUTOMATIC, manageSafeMode.status(portfolioId).origin());
        assertEquals(1, automaticChanges(), "A segunda avaliacao com HALT ativo nao registra nova mudanca");

        manageSafeMode.change(portfolioId, SafeModeOrigin.MANUAL,
                ChangeSafeModeRequest.manual(SafeModeStatus.NORMAL, "t25 resolvido", false));
        assertEquals(SafeModeStatus.NORMAL, manageSafeMode.status(portfolioId).level());
    }

    private void deadLetters(int count) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update("""
                            INSERT INTO dead_letter_entries (id, portfolio_id, raw_payload, reason, is_resolved, created_at)
                            VALUES (?, ?, '{}', 'T25_TEST', FALSE, ?)
                            """,
                    UUID.randomUUID(), portfolioId, Timestamp.from(Instant.now()));
        }
    }

    private int automaticChanges() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM safe_mode_changes WHERE portfolio_id = ? AND origin = 'AUTOMATIC'",
                Integer.class, portfolioId);
        return count == null ? 0 : count;
    }
}
