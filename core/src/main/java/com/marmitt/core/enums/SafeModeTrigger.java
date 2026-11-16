package com.marmitt.core.enums;

/**
 * Motivo de um HALT automático do portfolio (T43, T25; Blueprint 11.1.A). Gatilho automático só aciona
 * {@code HALT}; a retomada é manual, exceto para os gatilhos {@link #autoResumable()} (T71).
 */
public enum SafeModeTrigger {
    /** Equity caiu além do limite em relação ao pico de 24h (T43). */
    MAX_DRAWDOWN_EXCEEDED,
    /** Entradas não resolvidas na DLQ do portfolio no limite (T25). */
    DLQ_THRESHOLD,
    /** Soma dos portfolios acima do saldo da conta numa exchange, no boot ou na operacao (T25, T57). */
    BALANCE_DIVERGENCE,
    /** Mesma transação sem resolver em ciclos consecutivos do watchdog, com alguma falha que não é exchange fora do ar (T25). */
    WATCHDOG_STUCK,
    /**
     * Mesma transação sem resolver em ciclos consecutivos do watchdog, todos por exchange fora do ar: timeout,
     * 5xx ou limite de peso (T71). Volta sozinho depois de a exchange ficar saudável.
     */
    EXCHANGE_UNAVAILABLE;

    /**
     * Se o HALT deste gatilho pode voltar a NORMAL sem o operador (T71). Só a exchange fora do ar: os demais
     * indicam estado local possivelmente errado ou perda, e pedem olho humano.
     */
    public boolean autoResumable() {
        return this == EXCHANGE_UNAVAILABLE;
    }
}
