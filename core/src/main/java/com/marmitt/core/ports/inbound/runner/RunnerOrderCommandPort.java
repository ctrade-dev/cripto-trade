package com.marmitt.core.ports.inbound.runner;

import java.util.UUID;

/**
 * Comandos do operador sobre ordens e posições de um runner (IG §12.5, T49). Passam pelos mesmos
 * caminhos da estratégia e do Safe Mode: o cancelamento valida o alvo como o {@code SHOULD_CANCEL};
 * o fechamento vende o lote inteiro a mercado pelo pipeline normal de SELL.
 */
public interface RunnerOrderCommandPort {

    /**
     * Envia o cancelamento de uma ordem em voo do runner. O {@code CANCELED} chega pelo stream.
     *
     * @throws com.marmitt.core.exceptions.RunnerOperationException se a ordem não é do runner ou o envio à
     *         exchange está bloqueado por conexão perdida ({@code EXCHANGE_UNAVAILABLE})
     */
    void cancelOrder(UUID runnerId, UUID transactionId);

    /**
     * Vende a mercado o lote inteiro de uma posição aberta do runner, ao último preço conhecido como
     * referência. Runner precisa estar ACTIVE ou TERMINATING (runner halted: retomar antes).
     *
     * @throws com.marmitt.core.exceptions.RunnerOperationException se a posição não pode ser vendida agora,
     *         inclusive com o envio à exchange bloqueado ({@code EXCHANGE_UNAVAILABLE})
     */
    void closePosition(UUID runnerId, UUID positionId);
}
