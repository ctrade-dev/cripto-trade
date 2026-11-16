package com.marmitt.core.domain.portfolio;

import com.marmitt.core.enums.CapitalPoolingMode;
import com.marmitt.core.enums.SafeModeOrigin;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.enums.SafeModeTrigger;
import com.marmitt.core.exceptions.SafeModeTransitionException;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Getter
public class Portfolio {

    private final UUID id;
    private final String name;
    private boolean isActive;
    private final Instant createdAt;

    // Novos campos — modelo alvo (IG 3.2.1)
    private SafeModeStatus safeModeStatus;
    private CapitalPoolingMode capitalPoolingMode;

    /** Quem fez a última mudança do Safe Mode; nulo se nunca mudou (T45). */
    private SafeModeOrigin safeModeOrigin;
    /** Gatilho da última mudança automática (T71); nulo se a última mudança foi manual ou se nunca mudou. */
    private SafeModeTrigger safeModeTrigger;
    private String safeModeReason;
    private Instant safeModeChangedAt;

    private final Long version;

    /**
     * Construtor de criação — para novos portfolios.
     */
    public Portfolio(
            UUID id,
            String name
    ) {
        this.id = id;
        this.name = name;
        this.isActive = true;
        this.createdAt = Instant.now();
        this.safeModeStatus = SafeModeStatus.NORMAL;
        this.capitalPoolingMode = CapitalPoolingMode.SHARED;
        this.version = null;
    }

    /**
     * Construtor de reconstituição — para carregar do banco de dados.
     */
    public Portfolio(
            UUID id,
            String name,
            boolean isActive,
            Instant createdAt,
            SafeModeStatus safeModeStatus,
            CapitalPoolingMode capitalPoolingMode,
            SafeModeOrigin safeModeOrigin,
            SafeModeTrigger safeModeTrigger,
            String safeModeReason,
            Instant safeModeChangedAt,
            Long version
    ) {
        this.id = id;
        this.name = name;
        this.isActive = isActive;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
        this.safeModeStatus = Objects.requireNonNull(safeModeStatus, "safeModeStatus cannot be null");
        this.capitalPoolingMode = Objects.requireNonNull(capitalPoolingMode, "capitalPoolingMode cannot be null");
        this.safeModeOrigin = safeModeOrigin;
        this.safeModeTrigger = safeModeTrigger;
        this.safeModeReason = safeModeReason;
        this.safeModeChangedAt = safeModeChangedAt;
        this.version = version;
    }

    /**
     * Muda o nível do Safe Mode (Blueprint 11.1.B.1 e 11.1.C, T45, T71).
     *
     * <ul>
     *   <li>Mesmo nível: nenhuma mudança (idempotente).</li>
     *   <li>Subida: pode pular níveis; {@code CANCEL_ALL} e {@code PANIC_SELL} exigem confirmação.</li>
     *   <li>Descida: um nível por vez (3→2→1→NORMAL).</li>
     *   <li>{@code AUTOMATIC} sempre traz o gatilho e só sobe até {@code HALT}; já em HALT ou mais, não muda.</li>
     *   <li>{@code AUTOMATIC} só desce {@code HALT → NORMAL} quando o HALT atual é automático, do mesmo gatilho e
     *       o gatilho é {@link SafeModeTrigger#autoResumable()} (T71). Se o operador mexeu depois do HALT, a
     *       origem é {@code MANUAL} e a descida automática é recusada.</li>
     * </ul>
     *
     * @param trigger gatilho da mudança automática; nulo na mudança manual
     * @return o registro de auditoria da mudança, ou vazio se o nível já era o pedido
     * @throws SafeModeTransitionException se a transição violar as regras acima
     */
    public Optional<SafeModeChange> changeSafeMode(SafeModeStatus target, SafeModeOrigin origin,
                                                   SafeModeTrigger trigger, String reason, boolean confirmed) {
        Objects.requireNonNull(target, "target cannot be null");
        Objects.requireNonNull(origin, "origin cannot be null");
        if ((origin == SafeModeOrigin.AUTOMATIC) != (trigger != null)) {
            throw new IllegalArgumentException("trigger is required for AUTOMATIC and absent for MANUAL: "
                    + origin + "/" + trigger);
        }
        if (reason == null || reason.isBlank()) {
            throw new SafeModeTransitionException("REASON_REQUIRED", "A reason is required to change the safe mode");
        }
        if (target == safeModeStatus) {
            return Optional.empty();
        }
        if (origin == SafeModeOrigin.AUTOMATIC) {
            if (target == SafeModeStatus.NORMAL) {
                requireAutoResumable(trigger);
            } else if (target != SafeModeStatus.HALT) {
                throw new SafeModeTransitionException(
                        "Automatic triggers can only raise the safe mode to HALT, requested " + target);
            } else if (safeModeStatus.isActive()) {
                return Optional.empty();
            }
        }
        boolean raising = target.compareTo(safeModeStatus) > 0;
        if (raising && target.requiresConfirmation() && !confirmed) {
            throw new SafeModeTransitionException("CONFIRMATION_REQUIRED", target + " requires explicit confirmation");
        }
        if (!raising && safeModeStatus.ordinal() - target.ordinal() > 1) {
            throw new SafeModeTransitionException("SAFE_MODE_STEP_TOO_LARGE",
                    "Safe mode must be lowered one level at a time: " + safeModeStatus + " -> " + target);
        }

        SafeModeChange change = new SafeModeChange(UUID.randomUUID(), id, safeModeStatus, target, origin, trigger,
                reason, Instant.now());
        this.safeModeStatus = target;
        this.safeModeOrigin = origin;
        this.safeModeTrigger = trigger;
        this.safeModeReason = reason;
        this.safeModeChangedAt = change.changedAt();
        return Optional.of(change);
    }

    /**
     * Se este portfolio está num HALT que volta sozinho (T71): HALT automático de gatilho
     * {@link SafeModeTrigger#autoResumable()}.
     */
    public boolean isInAutoResumableHalt() {
        return safeModeStatus == SafeModeStatus.HALT
                && safeModeOrigin == SafeModeOrigin.AUTOMATIC
                && safeModeTrigger != null
                && safeModeTrigger.autoResumable();
    }

    private void requireAutoResumable(SafeModeTrigger trigger) {
        if (!isInAutoResumableHalt() || safeModeTrigger != trigger) {
            throw new SafeModeTransitionException("AUTO_RESUME_NOT_ALLOWED",
                    "Automatic resume needs an automatic HALT by an auto-resumable trigger, current "
                            + safeModeStatus + " origin=" + safeModeOrigin + " trigger=" + safeModeTrigger
                            + ", requested by " + trigger);
        }
    }
}
