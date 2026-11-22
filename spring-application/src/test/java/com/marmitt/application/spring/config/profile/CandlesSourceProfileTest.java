package com.marmitt.application.spring.config.profile;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T74: os candles das estrategias vem da mesma REST das ordens, salvo {@code BINANCE_CANDLES_BASE_URL}. O staging
 * opera na testnet e le os candles da Binance real, porque a testnet guarda poucas semanas de historico.
 */
class CandlesSourceProfileTest {

    @Test
    void mainnetReadsCandlesFromMainnet() throws IOException {
        Binder binder = ProfileYaml.binder("application.yml");

        assertThat(binder.bind("binance.candles-base-url", String.class).get()).isEqualTo("https://api.binance.com");
    }

    @Test
    void testnetReadsCandlesFromTestnetByDefault() throws IOException {
        Binder binder = ProfileYaml.binder("application.yml", "application-testnet.yml");

        assertThat(binder.bind("binance.candles-base-url", String.class).get())
                .isEqualTo("https://testnet.binance.vision");
    }

    @Test
    void stagingReadsCandlesFromMainnetAndTradesOnTestnet() throws IOException {
        Binder binder = ProfileYaml.binder(Map.of("BINANCE_CANDLES_BASE_URL", "https://api.binance.com"),
                "application.yml", "application-testnet.yml", "application-prod.yml");

        assertThat(binder.bind("binance.candles-base-url", String.class).get()).isEqualTo("https://api.binance.com");
        assertThat(binder.bind("binance.rest-base-url", String.class).get()).isEqualTo("https://testnet.binance.vision");
    }
}
