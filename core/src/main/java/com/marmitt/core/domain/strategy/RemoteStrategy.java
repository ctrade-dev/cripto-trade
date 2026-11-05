package com.marmitt.core.domain.strategy;

import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entrada do catalogo de estrategias remotas (T54): uma versao de estrategia que roda fora do ctrade,
 * cadastrada pelo operador por API. Identidade = {@code strategyRef} (o {@code strategyId} que os
 * runners usam); nome e versao sao conferidos contra o proprio servico antes do uso.
 *
 * <p>Nao ha exclusao: retirar de uso e {@link #disable}. Cada mutacao devolve se algo mudou, para o
 * chamador registrar evento so quando houve mudanca (operacoes repetidas sao idempotentes).
 */
@Getter
public class RemoteStrategy {

    private final UUID strategyRef;
    private final String name;
    private final String version;
    private StrategyEndpoint endpoint;
    private RemoteStrategyStatus status;
    private RemoteStrategyVerification verification;
    /** Motivo da ultima conferencia que falhou; nulo quando a ultima conferencia passou ou ainda nao houve. */
    private String lastError;
    private Instant lastVerificationAt;
    /** Versao do contrato que o servico declarou na ultima conferencia bem-sucedida. */
    private String servedContractVersion;
    /** Ticks de historico declarados na primeira conferencia (T69); mudar exige novo {@code strategyRef}. */
    private Integer priceHistoryTicks;
    private final Instant createdAt;
    private Instant updatedAt;

    public RemoteStrategy(UUID strategyRef, String name, String version, StrategyEndpoint endpoint,
                          RemoteStrategyStatus status, RemoteStrategyVerification verification, String lastError,
                          Instant lastVerificationAt, String servedContractVersion, Integer priceHistoryTicks,
                          Instant createdAt, Instant updatedAt) {
        this.strategyRef = Objects.requireNonNull(strategyRef, "strategyRef cannot be null");
        this.name = requireText(name, "name");
        this.version = requireText(version, "version");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint cannot be null");
        this.status = Objects.requireNonNull(status, "status cannot be null");
        this.verification = Objects.requireNonNull(verification, "verification cannot be null");
        this.lastError = lastError;
        this.lastVerificationAt = lastVerificationAt;
        this.servedContractVersion = servedContractVersion;
        this.priceHistoryTicks = priceHistoryTicks;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt cannot be null");
    }

    /**
     * Cadastro novo: habilitado e aguardando a primeira conferencia.
     */
    public static RemoteStrategy register(UUID strategyRef, String name, String version, StrategyEndpoint endpoint,
                                          Instant now) {
        return new RemoteStrategy(strategyRef, name, version, endpoint, RemoteStrategyStatus.ENABLED,
                RemoteStrategyVerification.PENDING, null, null, null, null, now, now);
    }

    /**
     * Mesmo cadastro (nome, versao, endereco e token): repetir o cadastro e um no-op.
     */
    public boolean sameDefinition(String name, String version, StrategyEndpoint endpoint) {
        return this.name.equals(name) && this.version.equals(version) && this.endpoint.equals(endpoint);
    }

    public boolean isEnabled() {
        return status == RemoteStrategyStatus.ENABLED;
    }

    /**
     * Reabilita e pede nova conferencia: o servico pode ter mudado enquanto estava desabilitada.
     *
     * @return {@code false} se ja estava habilitada
     */
    public boolean enable(Instant now) {
        if (status == RemoteStrategyStatus.ENABLED) {
            return false;
        }
        status = RemoteStrategyStatus.ENABLED;
        verification = RemoteStrategyVerification.PENDING;
        updatedAt = now;
        return true;
    }

    /**
     * @return {@code false} se ja estava desabilitada
     */
    public boolean disable(Instant now) {
        if (status == RemoteStrategyStatus.DISABLED) {
            return false;
        }
        status = RemoteStrategyStatus.DISABLED;
        updatedAt = now;
        return true;
    }

    /**
     * Troca endereco e/ou token (a app mudou de lugar ou o token foi trocado). A identidade precisa ser
     * conferida de novo no endereco novo.
     *
     * @return {@code false} se o endpoint informado e igual ao atual
     */
    public boolean changeEndpoint(StrategyEndpoint newEndpoint, Instant now) {
        Objects.requireNonNull(newEndpoint, "endpoint cannot be null");
        if (endpoint.equals(newEndpoint)) {
            return false;
        }
        endpoint = newEndpoint;
        verification = RemoteStrategyVerification.PENDING;
        updatedAt = now;
        return true;
    }

    /**
     * @return {@code true} se o resultado mudou (antes nao estava conferida)
     */
    public boolean markVerified(String contractVersion, int declaredPriceHistoryTicks, Instant now) {
        boolean changed = verification != RemoteStrategyVerification.VERIFIED;
        verification = RemoteStrategyVerification.VERIFIED;
        lastError = null;
        servedContractVersion = contractVersion;
        priceHistoryTicks = declaredPriceHistoryTicks;
        lastVerificationAt = now;
        if (changed) {
            updatedAt = now;
        }
        return changed;
    }

    /**
     * @return {@code true} se o resultado mudou (antes nao estava falhando, ou falhava por outro motivo)
     */
    public boolean markFailed(String error, Instant now) {
        boolean changed = verification != RemoteStrategyVerification.FAILED || !Objects.equals(lastError, error);
        verification = RemoteStrategyVerification.FAILED;
        lastError = error;
        lastVerificationAt = now;
        if (changed) {
            updatedAt = now;
        }
        return changed;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value.strip();
    }
}
