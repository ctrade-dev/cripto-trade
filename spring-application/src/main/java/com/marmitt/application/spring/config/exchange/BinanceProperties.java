package com.marmitt.application.spring.config.exchange;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "binance")
public class BinanceProperties {

    @NotBlank(message = "binance.ws-base-url cannot be blank")
    private String wsBaseUrl;

    @NotBlank(message = "binance.rest-base-url cannot be blank")
    private String restBaseUrl;

    /** REST dos candles das estrategias; padrao {@code rest-base-url} (application.yml). */
    @NotBlank(message = "binance.candles-base-url cannot be blank")
    private String candlesBaseUrl;

    @NotBlank(message = "binance.ws-api-base-url cannot be blank")
    private String wsApiBaseUrl;

    @NotBlank(message = "binance.api-key is required when binance.api-secret is defined")
    private String apiKey;

    @NotBlank(message = "binance.api-secret is required when binance.api-key is defined")
    private String apiSecret;

    private List<String> symbols = new ArrayList<>();

    public String getWsBaseUrl() {
        return wsBaseUrl;
    }

    public void setWsBaseUrl(String wsBaseUrl) {
        this.wsBaseUrl = wsBaseUrl;
    }

    public String getRestBaseUrl() {
        return restBaseUrl;
    }

    public void setRestBaseUrl(String restBaseUrl) {
        this.restBaseUrl = restBaseUrl;
    }

    public String getCandlesBaseUrl() {
        return candlesBaseUrl;
    }

    public void setCandlesBaseUrl(String candlesBaseUrl) {
        this.candlesBaseUrl = candlesBaseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(String apiSecret) {
        this.apiSecret = apiSecret;
    }

    public String getWsApiBaseUrl() {
        return wsApiBaseUrl;
    }

    public void setWsApiBaseUrl(String wsApiBaseUrl) {
        this.wsApiBaseUrl = wsApiBaseUrl;
    }

    public List<String> getSymbols() {
        return symbols;
    }

    public void setSymbols(List<String> symbols) {
        this.symbols = symbols;
    }
}
