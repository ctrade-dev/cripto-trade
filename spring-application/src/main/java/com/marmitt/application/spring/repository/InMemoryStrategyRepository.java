package com.marmitt.application.spring.repository;

import com.marmitt.core.ports.outbound.repository.StrategyRepositoryPort;
import com.marmitt.core.ports.outbound.strategy.TradingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estrategias em uso pelos runners, por {@code strategyId}. So entram aqui as estrategias remotas do
 * catalogo (T54), registradas pelo {@code RemoteStrategyCatalogUseCase}: o ctrade nao embute nenhuma
 * implementacao de estrategia (T65).
 */
@Slf4j
@Repository
public class InMemoryStrategyRepository implements StrategyRepositoryPort {

    private final Map<UUID, TradingStrategy> strategiesById = new ConcurrentHashMap<>();

    @Override
    public Optional<TradingStrategy> findById(UUID strategyId) {
        TradingStrategy strategy = strategiesById.get(strategyId);
        if (strategy != null) {
            log.debug("Strategy found by ID: {} -> {}", strategyId, strategy.getStrategyName());
        } else {
            log.debug("Strategy not found by ID: {}", strategyId);
        }
        return Optional.ofNullable(strategy);
    }

    @Override
    public void registerStrategy(TradingStrategy strategy) {
        if (strategy == null) {
            log.warn("Attempted to register null strategy");
            return;
        }

        UUID strategyId = strategy.getStrategyId();
        String strategyName = strategy.getStrategyName();

        if (strategiesById.containsKey(strategyId)) {
            log.warn("Strategy with ID {} already registered, skipping: {}", strategyId, strategyName);
            return;
        }

        // Identidade = strategyId (T41): duas versoes podem ter o mesmo nome.
        strategiesById.put(strategyId, strategy);

        log.info("Strategy registered - ID: {}, Name: {}, Version: {}, Enabled: {}",
                strategyId, strategyName, strategy.getStrategyVersion(), strategy.isEnabled());
    }
}
