package com.marmitt.application.spring.config.profile;

import com.marmitt.core.enums.BootAccountQueryPolicy;
import com.marmitt.core.enums.BootFailureMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T74 (A1): o perfil {@code prod} so muda como operar; a exchange continua vindo do perfil de exchange.
 */
class ProdProfileTest {

    @Test
    void prodProfileOverridesOperationSettings() throws IOException {
        Binder binder = ProfileYaml.binder("application.yml", "application-prod.yml");

        assertThat(binder.bind("logging.level.com.marmitt", String.class).get()).isEqualTo("INFO");
        assertThat(binder.bind("runner.boot.phase2.mode", BootFailureMode.class).get())
                .isEqualTo(BootFailureMode.FAIL_FAST);
        assertThat(binder.bind("runner.boot.phase2.account-query-policy", BootAccountQueryPolicy.class).get())
                .isEqualTo(BootAccountQueryPolicy.FAIL);
        assertThat(binder.bind("notification.discord.required", Boolean.class).get()).isTrue();
    }

    @Test
    void withoutProdTheSoakSettingsStay() throws IOException {
        Binder binder = ProfileYaml.binder("application.yml", "application-testnet.yml");

        assertThat(binder.bind("logging.level.com.marmitt", String.class).get()).isEqualTo("DEBUG");
        assertThat(binder.bind("runner.boot.phase2.mode", BootFailureMode.class).get())
                .isEqualTo(BootFailureMode.WARN_ONLY);
        assertThat(binder.bind("notification.discord.required", Boolean.class).get()).isFalse();
    }

    @Test
    void testnetProdKeepsTestnetExchangeAndProdOperation() throws IOException {
        Binder binder = ProfileYaml.binder("application.yml", "application-testnet.yml", "application-prod.yml");

        assertThat(binder.bind("binance.rest-base-url", String.class).get()).isEqualTo("https://testnet.binance.vision");
        assertThat(binder.bind("runner.boot.phase2.mode", BootFailureMode.class).get())
                .isEqualTo(BootFailureMode.FAIL_FAST);
    }

    @Test
    void prodProfileHasNoExchangeAddresses() throws IOException {
        Binder binder = ProfileYaml.binder("application-prod.yml");

        assertThat(binder.bind("binance", Object.class).isBound()).isFalse();
    }
}
