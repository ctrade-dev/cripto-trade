package com.marmitt.application.spring.config.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationConfigTest {

    private final NotificationConfig config = new NotificationConfig();

    @Test
    void requiredWebhookMissingFailsStartup() {
        NotificationProperties properties = new NotificationProperties();
        properties.getDiscord().setRequired(true);
        properties.getDiscord().setWebhookUrl("  ");

        assertThatThrownBy(() -> config.errorNotificationPort(properties, new ObjectMapper(), HttpClient.newHttpClient()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DISCORD_WEBHOOK_URL");
    }

    @Test
    void requiredWebhookPresentBuildsAdapter() {
        NotificationProperties properties = new NotificationProperties();
        properties.getDiscord().setRequired(true);
        properties.getDiscord().setWebhookUrl("https://discord.example/api/webhooks/1/token");

        assertThat(config.errorNotificationPort(properties, new ObjectMapper(), HttpClient.newHttpClient())).isNotNull();
    }

    @Test
    void optionalWebhookMissingKeepsNoOpAdapter() {
        NotificationProperties properties = new NotificationProperties();

        assertThat(config.errorNotificationPort(properties, new ObjectMapper(), HttpClient.newHttpClient())).isNotNull();
    }
}
