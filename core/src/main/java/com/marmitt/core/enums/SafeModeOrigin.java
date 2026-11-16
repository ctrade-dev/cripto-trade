package com.marmitt.core.enums;

/**
 * Quem mudou o nível do Safe Mode (T45).
 *
 * <p>{@code AUTOMATIC} é o gatilho do próprio sistema (drawdown T43, gatilhos da T25), sempre com o
 * {@link SafeModeTrigger}, e só pode subir até {@link SafeModeStatus#HALT}: escalar para níveis de ação em
 * massa é decisão exclusiva do operador (Blueprint 11.1.B.1 e 11.1.C). Retomar também é do operador, exceto
 * o HALT automático de gatilho {@link SafeModeTrigger#autoResumable()} (T71).
 */
public enum SafeModeOrigin {
    MANUAL,
    AUTOMATIC
}
