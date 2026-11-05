package com.marmitt.application.spring.bootstrap;

import com.marmitt.application.spring.testsupport.DatabaseCleaner;
import com.marmitt.core.dto.portfolio.request.CreatePortfolioRequest;
import com.marmitt.core.ports.inbound.portfolio.CreatePortfolioPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.marmitt.application.spring.CTradeApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T55 — estado operacional consolidado contra o banco real: a consulta lista todas as verificações,
 * aponta a DLQ acumulada com o portfolio afetado e o estado vira métrica.
 */
@SpringBootTest(
        classes = CTradeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
class OperationalStateIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CreatePortfolioPort createPortfolio;

    @Autowired
    private OperationalStateScheduler operationalStateScheduler;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID portfolioId;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.reset(jdbcTemplate);
        portfolioId = createPortfolio.execute(CreatePortfolioRequest.builder()
                .name("t55-" + UUID.randomUUID())
                .initialCapitalAmount(new BigDecimal("1000"))
                .currency("USDT")
                .build()).portfolioId();
    }

    @Test
    void consolidatedStateListsEveryCheckAndPointsTheAffectedPortfolio() throws Exception {
        mockMvc.perform(get("/api/admin/operational-state").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checks.length()").value(16))
                .andExpect(jsonPath("$.checks[?(@.id=='DLQ_UNRESOLVED')].status").value("OK"))
                // Nos testes o orquestrador de boot fica desligado: o boot nunca conclui.
                .andExpect(jsonPath("$.checks[?(@.id=='BOOT_NOT_READY')].status").value("CRITICAL"));

        deadLetters(3);

        mockMvc.perform(get("/api/admin/operational-state").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CRITICAL"))
                .andExpect(jsonPath("$.checks[?(@.id=='DLQ_UNRESOLVED')].status").value("CRITICAL"))
                .andExpect(jsonPath("$.checks[?(@.id=='DLQ_UNRESOLVED')].subjects[0].portfolioId")
                        .value(portfolioId.toString()))
                .andExpect(jsonPath("$.checks[?(@.id=='DLQ_UNRESOLVED')].runbook")
                        .value("docs/OPERATIONS_RUNBOOK.md#dlq-unresolved"));
    }

    @Test
    void exchangeComparisonIsAddedOnlyOnRequest() throws Exception {
        mockMvc.perform(get("/api/admin/operational-state").param("includeExchange", "true")
                        .param("exchangeWindowHours", "6").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checks.length()").value(18))
                .andExpect(jsonPath("$.checks[?(@.id=='EXCHANGE_BALANCE_DIVERGENCE')].status").value("OK"))
                // Sem Binance configurada nos testes nao ha historico de trades: nao conferido, nao alarme.
                .andExpect(jsonPath("$.checks[?(@.id=='EXCHANGE_TRADE_DIVERGENCE')].status").value("OK"));

        mockMvc.perform(get("/api/admin/operational-state").param("includeExchange", "true")
                        .param("exchangeWindowHours", "0").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void consolidatedStateIsExportedAsMetrics() {
        deadLetters(1);

        operationalStateScheduler.scheduledTick();

        assertEquals(2.0, meterRegistry.get("operational_state_status").gauge().value(),
                "status geral CRITICAL (boot nao concluido nos testes)");
        assertEquals(1.0, meterRegistry.get("operational_check_status").tag("check", "DLQ_UNRESOLVED")
                .gauge().value(), "1 entrada na DLQ: WARN");
    }

    private void deadLetters(int count) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update("""
                            INSERT INTO dead_letter_entries (id, portfolio_id, raw_payload, reason, is_resolved, created_at)
                            VALUES (?, ?, '{}', 'T55_TEST', FALSE, ?)
                            """,
                    UUID.randomUUID(), portfolioId, Timestamp.from(Instant.now()));
        }
    }
}
