package com.marmitt.application.spring.bootstrap;

import com.marmitt.core.dto.portfolio.request.CreatePortfolioRequest;
import com.marmitt.core.ports.inbound.portfolio.CreatePortfolioPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T74 (A2): com {@code DB_SCHEMA} o ctrade migra, le e grava so no schema do ambiente. O container e o mesmo dos
 * outros testes de integracao, cujos dados ficam no {@code public}.
 */
@TestPropertySource(properties = "DB_SCHEMA=" + DatabaseSchemaIntegrationTest.SCHEMA)
class DatabaseSchemaIntegrationTest extends AbstractIntegrationTest {

    static final String SCHEMA = "env_it";

    @Autowired
    private CreatePortfolioPort createPortfolio;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migratesAndWritesOnlyInTheConfiguredSchema() {
        assertThat(jdbcTemplate.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + SCHEMA + ".flyway_schema_history WHERE success AND version IS NOT NULL",
                Integer.class)).isPositive();

        String name = "a2-" + UUID.randomUUID();
        createPortfolio.execute(CreatePortfolioRequest.builder()
                .name(name)
                .initialCapitalAmount(new BigDecimal("100"))
                .currency("USDT")
                .build());

        assertThat(count(SCHEMA, name)).isEqualTo(1);
        assertThat(count("public", name)).isZero();
    }

    private int count(String schema, String name) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL", Boolean.class, schema + ".portfolios");
        if (!Boolean.TRUE.equals(exists)) {
            return 0;
        }
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + schema + ".portfolios WHERE name = ?", Integer.class, name);
    }
}
