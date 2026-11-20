package com.marmitt.application.spring.config.core;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Series de candles que as estrategias declaram (T72): carga e manutencao pela REST da exchange.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ctrade.market-data.candles")
public class CandleSeriesProperties {

    /** Desligado: nenhuma serie e mantida e estrategias que declaram candles ficam em {@code WAITING_HISTORY}. */
    private boolean enabled = true;
    /** Ciclo que confere as series. Serie em dia nao consulta a exchange ate o proximo fechamento. */
    private long intervalMs = 1_000L;
    /** Espera antes de consultar de novo uma serie depois de falha da exchange ou de historico curto. */
    private long retryDelayMs = 10_000L;
}
