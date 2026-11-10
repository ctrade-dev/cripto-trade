package com.marmitt.application.spring.infrastructure.strategy;

import com.marmitt.core.ports.outbound.strategy.StrategyCallHealthPort;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guarda em memoria a sequencia de falhas atual de cada estrategia remota (T53 F21). Alimentado pelo decorator
 * que observa as chamadas; reinicia vazio a cada boot, o que basta para um sinal de "fora do ar agora".
 */
public class InMemoryStrategyCallHealth implements StrategyCallHealthPort {

    private final Map<UUID, FailingSince> failing = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryStrategyCallHealth(Clock clock) {
        this.clock = clock;
    }

    public void recordSuccess(UUID strategyRef) {
        failing.remove(strategyRef);
    }

    public void recordFailure(UUID strategyRef, String reason) {
        failing.compute(strategyRef, (ref, current) -> new FailingSince(
                current == null ? clock.instant() : current.since(), reason));
    }

    @Override
    public Optional<FailingSince> failingSince(UUID strategyRef) {
        return Optional.ofNullable(failing.get(strategyRef));
    }
}
