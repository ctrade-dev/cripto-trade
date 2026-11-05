package com.marmitt.core.application.usecase.strategy;

import com.marmitt.core.domain.strategy.StrategyEndpoint;
import com.marmitt.core.dto.strategy.StrategyContextDto;
import com.marmitt.core.dto.strategy.StrategyInputDto;
import com.marmitt.core.dto.strategy.StrategyOutputDto;
import com.marmitt.core.ports.outbound.strategy.StrategyEvaluationPort;
import com.marmitt.core.ports.outbound.strategy.TradingStrategy;

import java.util.Objects;
import java.util.UUID;

/**
 * Estratégia que roda fora do processo (T41). É só mais uma {@link TradingStrategy}: o pipeline de
 * sinal não sabe que a decisão veio da rede. Identidade (id, nome, versão) e endereço vêm do catálogo
 * (T54).
 *
 * <p>Fica no repositório de estratégias enquanto existir no catálogo, mas só é consultada quando
 * {@link #isEnabled()}: habilitada no catálogo e conferida contra o próprio serviço neste processo.
 * Sem isso, os runners que apontam para ela ficam em HOLD implícito.
 */
class RemoteTradingStrategy implements TradingStrategy {

    private final UUID strategyRef;
    private final String name;
    private final String version;
    private final StrategyEvaluationPort evaluation;
    private volatile StrategyEndpoint endpoint;
    private volatile boolean enabled;
    private volatile int requiredPriceHistoryTicks;

    RemoteTradingStrategy(UUID strategyRef, String name, String version, StrategyEndpoint endpoint,
                          StrategyEvaluationPort evaluation) {
        this.strategyRef = Objects.requireNonNull(strategyRef, "strategyRef cannot be null");
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.version = Objects.requireNonNull(version, "version cannot be null");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint cannot be null");
        this.evaluation = Objects.requireNonNull(evaluation, "evaluation cannot be null");
        this.enabled = false;
    }

    @Override
    public UUID getStrategyId() {
        return strategyRef;
    }

    @Override
    public StrategyOutputDto executeStrategy(StrategyInputDto inputData, StrategyContextDto strategyContext) {
        return evaluation.evaluate(strategyRef, endpoint, inputData, strategyContext);
    }

    @Override
    public String getStrategyName() {
        return name;
    }

    @Override
    public String getStrategyVersion() {
        return version;
    }

    @Override
    public int requiredPriceHistoryTicks() {
        return requiredPriceHistoryTicks;
    }

    void requirePriceHistoryTicks(int ticks) {
        this.requiredPriceHistoryTicks = ticks;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    StrategyEndpoint endpoint() {
        return endpoint;
    }

    /**
     * Novo endereço vale na próxima chamada. Quem troca o endereço tira a estratégia de uso até conferir
     * a identidade no endereço novo.
     */
    void changeEndpoint(StrategyEndpoint endpoint) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint cannot be null");
    }
}
