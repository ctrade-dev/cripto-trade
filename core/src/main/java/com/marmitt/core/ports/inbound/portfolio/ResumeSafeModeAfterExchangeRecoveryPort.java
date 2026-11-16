package com.marmitt.core.ports.inbound.portfolio;

/**
 * Avaliação periódica da retomada automática do Safe Mode (T71): o HALT automático por exchange fora do ar
 * volta a NORMAL depois de a exchange ficar saudável por um tempo seguido.
 */
public interface ResumeSafeModeAfterExchangeRecoveryPort {

    void evaluate();
}
