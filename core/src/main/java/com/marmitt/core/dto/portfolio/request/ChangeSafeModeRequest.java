package com.marmitt.core.dto.portfolio.request;

import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.enums.SafeModeTrigger;

/**
 * Pedido de mudança de nível do Safe Mode (T45). {@code confirm} é obrigatório ao subir para
 * {@code CANCEL_ALL} ou {@code PANIC_SELL}; {@code reason} é sempre obrigatório (auditoria).
 *
 * @param trigger gatilho da mudança automática (T71); nulo no pedido do operador
 */
public record ChangeSafeModeRequest(
        SafeModeStatus level,
        String reason,
        boolean confirm,
        SafeModeTrigger trigger
) {

    /** Pedido do operador (API admin). */
    public static ChangeSafeModeRequest manual(SafeModeStatus level, String reason, boolean confirm) {
        return new ChangeSafeModeRequest(level, reason, confirm, null);
    }

    /** Pedido de um gatilho automático: HALT (T25, T43) ou retomada (T71). */
    public static ChangeSafeModeRequest automatic(SafeModeStatus level, SafeModeTrigger trigger, String reason) {
        return new ChangeSafeModeRequest(level, reason, false, trigger);
    }
}
