package com.marmitt.application.spring.config.profile;

import com.marmitt.core.enums.BootAccountQueryPolicy;
import com.marmitt.core.enums.BootFailureMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T74 (A1): o perfil {@code prod} so muda como operar; a exchange continua vindo do perfil de exchange.
 */
class ProdProfileTest {

    @Test
    void prodProfileOverridesOperationSettings() throws IOException {
        Binder binder = binder("application.yml", "application-prod.yml");

        assertThat(binder.bind("logging.level.com.marmitt", String.class).get()).isEqualTo("INFO");
        assertThat(binder.bind("runner.boot.phase2.mode", BootFailureMode.class).get())
                .isEqualTo(BootFailureMode.FAIL_FAST);
        assertThat(binder.bind("runner.boot.phase2.account-query-policy", BootAccountQueryPolicy.class).get())
                .isEqualTo(BootAccountQueryPolicy.FAIL);
        assertThat(binder.bind("notification.discord.required", Boolean.class).get()).isTrue();
    }

    @Test
    void withoutProdTheSoakSettingsStay() throws IOException {
        Binder binder = binder("application.yml", "application-testnet.yml");

        assertThat(binder.bind("logging.level.com.marmitt", String.class).get()).isEqualTo("DEBUG");
        assertThat(binder.bind("runner.boot.phase2.mode", BootFailureMode.class).get())
                .isEqualTo(BootFailureMode.WARN_ONLY);
        assertThat(binder.bind("notification.discord.required", Boolean.class).get()).isFalse();
    }

    @Test
    void testnetProdKeepsTestnetExchangeAndProdOperation() throws IOException {
        Binder binder = binder("application.yml", "application-testnet.yml", "application-prod.yml");

        assertThat(binder.bind("binance.rest-base-url", String.class).get()).isEqualTo("https://testnet.binance.vision");
        assertThat(binder.bind("runner.boot.phase2.mode", BootFailureMode.class).get())
                .isEqualTo(BootFailureMode.FAIL_FAST);
    }

    @Test
    void prodProfileHasNoExchangeAddresses() throws IOException {
        Binder binder = binder("application-prod.yml");

        assertThat(binder.bind("binance", Object.class).isBound()).isFalse();
    }

    /** Ultimo arquivo tem precedencia, como na ordem de perfis do Spring. */
    private static Binder binder(String... files) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String file : files) {
            List<PropertySource<?>> loaded = loader.load(file, new ClassPathResource(file));
            loaded.forEach(sources::addFirst);
        }
        return Binder.get(environment);
    }
}
