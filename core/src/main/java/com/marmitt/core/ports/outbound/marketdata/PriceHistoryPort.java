package com.marmitt.core.ports.outbound.marketdata;

import com.marmitt.core.dto.strategy.PricePointDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Ultimos ticks por exchange e simbolo, para as estrategias que declaram historico de precos (T69). As
 * estrategias sao stateless: quem guarda a serie e o ctrade. Volatil por design: apos restart fica vazio
 * ate os ticks seguintes chegarem.
 */
public interface PriceHistoryPort {

    /** Teto de ticks guardados por exchange e simbolo, e portanto do que uma estrategia pode declarar. */
    int MAX_TICKS = 1000;

    /**
     * Chamado na thread de market data: nao pode bloquear. Tick mais velho que o ultimo guardado e ignorado.
     */
    void record(String exchangeId, String symbol, BigDecimal price, Instant timestamp);

    /**
     * @param upTo instante do tick em avaliacao: a mailbox pode ter recebido ticks mais novos
     * @return ate {@code count} ticks ate {@code upTo}, do mais antigo para o mais novo
     */
    List<PricePointDto> latest(String exchangeId, String symbol, int count, Instant upTo);
}
