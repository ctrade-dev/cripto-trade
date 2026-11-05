package com.marmitt.core.ports.outbound.exchange;

import com.marmitt.core.dto.runner.OrderCancelCommand;
import com.marmitt.core.dto.runner.OrderDispatchCommand;

/**
 * Porta de saída para envio de ordens a uma exchange.
 * <p>
 * Fire-and-forget: envia a ordem e retorna imediatamente.
 * A confirmação (SUBMITTED) e a rejeição (REJECTED) chegam de forma assíncrona
 * via pipeline de mensagens WebSocket → {@code OrderConciliationUseCase}.
 * <p>
 * <b>Contrato de exceções:</b> lança exceção apenas em falhas técnicas de envio
 * (WebSocket desconectado, falha de serialização). A transação permanece {@code PENDING}
 * e o Boot Sequence reconcilia o estado via {@code clientOrderId}.
 *
 * @see <a href="docs/IMPLEMENTATION_GUIDE.md">IG Seção 6.2.1, 6.3</a>
 */
public interface OrderDispatchPort {

    /**
     * Despacha uma ordem para a exchange via WebSocket.
     *
     * @param command dados da ordem a enviar
     * @throws IllegalStateException se o adapter da exchange não for encontrado
     * @throws RuntimeException      se o envio falhar por razão técnica
     */
    void dispatch(OrderDispatchCommand command);

    /**
     * Cancela uma ordem viva na exchange via canal de saída.
     * <p>
     * Fire-and-forget, como {@link #dispatch}: envia o cancelamento e retorna. O {@code CANCELED}
     * (e a eventual liberação de capital) chega de forma assíncrona via stream e é conciliado pelo
     * caminho idempotente. Não marca a transação como terminal de forma otimista.
     *
     * @param command alvo do cancelamento (agnóstico de provider)
     */
    void cancel(OrderCancelCommand command);

    /**
     * O envio para a exchange esta bloqueado por conexao perdida? Com o bloqueio, {@link #dispatch} rejeita a
     * ordem e {@link #cancel} nao envia nada.
     */
    boolean isBlocked(String exchangeId);
}
