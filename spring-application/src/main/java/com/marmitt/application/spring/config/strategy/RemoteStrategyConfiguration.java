package com.marmitt.application.spring.config.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marmitt.application.spring.adapter.strategy.OkHttpStrategyServiceHttpAdapter;
import com.marmitt.application.spring.infrastructure.strategy.InMemoryStrategyCallHealth;
import com.marmitt.application.spring.metrics.MeteredStrategyEvaluationPort;
import com.marmitt.core.application.usecase.strategy.RemoteStrategyCatalogUseCase;
import com.marmitt.core.ports.outbound.notification.ErrorNotificationPort;
import com.marmitt.core.ports.outbound.repository.RemoteStrategyCatalogRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import com.marmitt.core.ports.outbound.strategy.StrategyEvaluationPort;
import com.marmitt.strategyservice.RemoteStrategyClient;
import io.micrometer.core.instrument.MeterRegistry;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Composição das estratégias remotas: transporte OkHttp com timeout rígido, cliente do contrato,
 * métricas (T41), a saúde recente das chamadas (T53 F21) e o catálogo persistido administrado por API (T54).
 */
@Configuration
public class RemoteStrategyConfiguration {

    /** Sequencia de falhas atual de cada estrategia remota, lida pelo estado consolidado (T53 F21). */
    @Bean
    public InMemoryStrategyCallHealth strategyCallHealth() {
        return new InMemoryStrategyCallHealth(Clock.systemUTC());
    }

    @Bean
    public StrategyEvaluationPort strategyEvaluationPort(RemoteStrategyProperties properties,
                                                         ObjectMapper objectMapper,
                                                         MeterRegistry meterRegistry,
                                                         InMemoryStrategyCallHealth strategyCallHealth) {
        Duration timeout = Duration.ofMillis(properties.getTimeoutMs());
        OkHttpClient client = new OkHttpClient.Builder()
                .callTimeout(timeout)
                .connectTimeout(timeout)
                .retryOnConnectionFailure(false)
                .build();
        RemoteStrategyClient remote = new RemoteStrategyClient(new OkHttpStrategyServiceHttpAdapter(client),
                objectMapper, Duration.ofMillis(properties.getMaxDecisionAgeMs()), Clock.systemUTC());
        return new MeteredStrategyEvaluationPort(remote, meterRegistry, strategyCallHealth);
    }

    /**
     * Um bean para os dois ports ({@code ManageRemoteStrategiesPort} e {@code SyncRemoteStrategiesPort}):
     * operador e ciclo periódico precisam da mesma serialização.
     */
    @Bean
    public RemoteStrategyCatalogUseCase remoteStrategyCatalog(RemoteStrategyCatalogRepositoryPort catalog,
                                                              StrategyRepositoryPort strategyRepository,
                                                              StrategyEvaluationPort strategyEvaluationPort,
                                                              StrategyRunnerRepositoryPort runnerRepository,
                                                              ErrorNotificationPort errorNotification) {
        return new RemoteStrategyCatalogUseCase(catalog, strategyRepository, strategyEvaluationPort,
                runnerRepository, errorNotification, Clock.systemUTC());
    }
}
