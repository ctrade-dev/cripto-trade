package com.marmitt.core.ports.outbound.connection;

import com.marmitt.core.enums.StreamChannel;

import java.util.UUID;

/**
 * Reconexao de um canal de WebSocket depois de uma falha. A estrategia conta as falhas seguidas de cada canal
 * (exchange + canal): cada falha agenda a proxima tentativa, uma por vez, e, esgotadas as tentativas, publica a
 * falha critica. A contagem so recomeca quando o canal conecta de novo.
 */
public interface ReconnectionStrategyPort {

    /** Registra mais uma falha seguida do canal e agenda a proxima tentativa, se nenhuma estiver agendada. */
    void scheduleReconnect(String exchangeName, StreamChannel channel, UUID sessionToClose);

    /** O canal conectou: zera a contagem de falhas e libera novas tentativas. */
    void connectionEstablished(String exchangeName, StreamChannel channel);
}
