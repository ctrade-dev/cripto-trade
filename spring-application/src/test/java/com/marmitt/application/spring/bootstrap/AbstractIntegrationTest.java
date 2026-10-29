package com.marmitt.application.spring.bootstrap;

import com.marmitt.application.spring.CTradeApplication;
import com.marmitt.application.spring.testsupport.strategy.sma.SmaTestStrategyConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(
        classes = CTradeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@Import(SmaTestStrategyConfiguration.class)
abstract class AbstractIntegrationTest {

    /** Chave da API nos testes de integração que chamam a borda HTTP (T55: toda a /api exige). */
    static final String ADMIN_KEY = "it-admin-key";

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("ctrade")
                .withUsername("ctrade")
                .withPassword("ctrade123");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("runner.boot.orchestrator-enabled", () -> "false");
        registry.add("admin.api.key", () -> ADMIN_KEY);
        // T56: os E2E publicam ticks na mao; o feed automatico do MOCK (1 tick/s) quebraria o determinismo.
        // Desliga pela variavel do placeholder (application.yml), para um teste poder religar pela chave
        // direta: o @DynamicPropertySource da superclasse e aplicado depois e venceria a mesma chave.
        registry.add("CTRADE_MARKET_DATA_AUTO_FEED_ENABLED", () -> "false");
    }
}
