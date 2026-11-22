package com.marmitt.application.spring.config.exchange;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marmitt.application.spring.repository.InMemoryExchangeAdapterRepository;
import com.marmitt.application.spring.repository.InMemoryWebSocketPortRegistry;
import com.marmitt.core.ports.outbound.events.EventPublisherPort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceAdapterConfigurationIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestApplication.class)
            .withPropertyValues(
                    "binance.ws-base-url=wss://stream.binance.com:9443",
                    "binance.rest-base-url=https://api.binance.com",
                    "binance.candles-base-url=https://api.binance.com",
                    "binance.ws-api-base-url=wss://ws-api.binance.com:443/ws-api/v3"
            );

    @Test
    void shouldRegisterBinanceAdapterWhenBothCredentialsArePresent() {
        contextRunner
                .withPropertyValues(
                        "binance.api-key=test-key",
                        "binance.api-secret=test-secret"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    ExchangeAdapterRepositoryPort repository = context.getBean(ExchangeAdapterRepositoryPort.class);
                    assertThat(repository.hasAdapter("BINANCE")).isTrue();
                    assertThat(repository.hasAdapter("MOCK")).isTrue();
                });
    }

    @Test
    void shouldStartWithoutBinanceAdapterWhenApiKeyIsPresentWithoutApiSecret() {
        contextRunner
                .withPropertyValues("binance.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    ExchangeAdapterRepositoryPort repository = context.getBean(ExchangeAdapterRepositoryPort.class);
                    assertThat(repository.hasAdapter("BINANCE")).isFalse();
                });
    }

    @Test
    void shouldStartWithoutBinanceAdapterWhenApiSecretIsPresentWithoutApiKey() {
        contextRunner
                .withPropertyValues("binance.api-secret=test-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    ExchangeAdapterRepositoryPort repository = context.getBean(ExchangeAdapterRepositoryPort.class);
                    assertThat(repository.hasAdapter("BINANCE")).isFalse();
                });
    }

    @Test
    void shouldStartWithoutBinanceAdapterWhenBothCredentialsAreAbsent() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            ExchangeAdapterRepositoryPort repository = context.getBean(ExchangeAdapterRepositoryPort.class);
            assertThat(repository.hasAdapter("BINANCE")).isFalse();
            assertThat(repository.hasAdapter("MOCK")).isTrue();
        });
    }

    @SpringBootConfiguration
    @Import({
            InMemoryExchangeAdapterRepository.class,
            InMemoryWebSocketPortRegistry.class,
            MockAdapterConfiguration.class,
            CoinbaseAdapterConfiguration.class,
            BinanceAdapterConfiguration.class,
            ExchangeCircuitBreakerConfiguration.class,
            ExchangeCircuitBreakerProperties.class
    })
    static class TestApplication {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        EventPublisherPort eventPublisherPort() {
            return new EventPublisherPort() {
                @Override
                public void publishEvent(Object event) {
                }

                @Override
                public void publishEventSync(Object event) {
                }
            };
        }
    }
}
