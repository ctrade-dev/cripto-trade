package com.marmitt.core.domain.portfolio;

import com.marmitt.core.enums.SafeModeOrigin;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.enums.SafeModeTrigger;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Registro de auditoria de uma mudança de nível do Safe Mode (T45).
 *
 * @param trigger gatilho da mudança automática (T71); nulo na mudança manual
 */
public record SafeModeChange(
        UUID id,
        UUID portfolioId,
        SafeModeStatus from,
        SafeModeStatus to,
        SafeModeOrigin origin,
        SafeModeTrigger trigger,
        String reason,
        Instant changedAt
) {
    public SafeModeChange {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(portfolioId, "portfolioId cannot be null");
        Objects.requireNonNull(from, "from cannot be null");
        Objects.requireNonNull(to, "to cannot be null");
        Objects.requireNonNull(origin, "origin cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");
        Objects.requireNonNull(changedAt, "changedAt cannot be null");
        if ((origin == SafeModeOrigin.AUTOMATIC) != (trigger != null)) {
            throw new IllegalArgumentException("trigger is required for AUTOMATIC and absent for MANUAL: "
                    + origin + "/" + trigger);
        }
    }
}
