package com.marmitt.application.spring.config.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração das notificações ativas de erro.
 *
 * <p>{@code discord.webhook-url} vazio mantém o adapter como no-op — a aplicação sobe
 * normalmente sem Discord configurado. {@code grafana.explore-url-template} é opcional e,
 * quando presente, gera o link "ver logs" usando {@code {correlationId}} como placeholder.
 */
@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {

    private final Discord discord = new Discord();
    private final Grafana grafana = new Grafana();

    public Discord getDiscord() {
        return discord;
    }

    public Grafana getGrafana() {
        return grafana;
    }

    public static class Discord {
        /** URL do webhook Discord. Vazio ⇒ adapter no-op. */
        private String webhookUrl = "";

        /** T74: {@code true} (perfil {@code prod}) recusa subir sem {@code webhook-url}. */
        private boolean required = false;

        public String getWebhookUrl() {
            return webhookUrl;
        }

        public void setWebhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }
    }

    public static class Grafana {
        /** Template de URL do Grafana Explore; {@code {correlationId}} é substituído. Vazio ⇒ sem link. */
        private String exploreUrlTemplate = "";

        public String getExploreUrlTemplate() {
            return exploreUrlTemplate;
        }

        public void setExploreUrlTemplate(String exploreUrlTemplate) {
            this.exploreUrlTemplate = exploreUrlTemplate;
        }
    }
}
