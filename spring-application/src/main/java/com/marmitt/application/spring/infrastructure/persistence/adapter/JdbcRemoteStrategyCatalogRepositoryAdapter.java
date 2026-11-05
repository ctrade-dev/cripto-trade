package com.marmitt.application.spring.infrastructure.persistence.adapter;

import com.marmitt.core.domain.strategy.RemoteStrategy;
import com.marmitt.core.domain.strategy.RemoteStrategyEvent;
import com.marmitt.core.domain.strategy.RemoteStrategyEventType;
import com.marmitt.core.domain.strategy.RemoteStrategyStatus;
import com.marmitt.core.domain.strategy.RemoteStrategyVerification;
import com.marmitt.core.domain.strategy.StrategyEndpoint;
import com.marmitt.core.ports.outbound.repository.RemoteStrategyCatalogRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Catalogo de estrategias remotas e historico (T54), em JDBC direto.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcRemoteStrategyCatalogRepositoryAdapter implements RemoteStrategyCatalogRepositoryPort {

    private static final RowMapper<RemoteStrategy> STRATEGY = (rs, rowNum) -> new RemoteStrategy(
            rs.getObject("strategy_ref", UUID.class),
            rs.getString("name"),
            rs.getString("version"),
            new StrategyEndpoint(rs.getString("base_url"), rs.getString("token")),
            RemoteStrategyStatus.valueOf(rs.getString("status")),
            RemoteStrategyVerification.valueOf(rs.getString("verification")),
            rs.getString("last_error"),
            instant(rs.getTimestamp("last_verification_at")),
            rs.getString("served_contract_version"),
            rs.getObject("price_history_ticks", Integer.class),
            instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("updated_at")));

    private static final RowMapper<RemoteStrategyEvent> EVENT = (rs, rowNum) -> new RemoteStrategyEvent(
            rs.getObject("id", UUID.class),
            rs.getObject("strategy_ref", UUID.class),
            RemoteStrategyEventType.valueOf(rs.getString("type")),
            rs.getString("reason"),
            rs.getString("detail"),
            instant(rs.getTimestamp("occurred_at")));

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public Optional<RemoteStrategy> findByRef(UUID strategyRef) {
        return jdbcTemplate.query("SELECT * FROM remote_strategies WHERE strategy_ref = :ref",
                new MapSqlParameterSource("ref", strategyRef), STRATEGY).stream().findFirst();
    }

    @Override
    public List<RemoteStrategy> findAll() {
        return jdbcTemplate.query("SELECT * FROM remote_strategies ORDER BY created_at, strategy_ref", STRATEGY);
    }

    @Override
    @Transactional
    public void save(RemoteStrategy strategy, List<RemoteStrategyEvent> events) {
        long start = System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO remote_strategies (strategy_ref, name, version, base_url, token, status, verification,
                                               last_error, last_verification_at, served_contract_version,
                                               price_history_ticks, created_at, updated_at)
                VALUES (:ref, :name, :version, :baseUrl, :token, :status, :verification, :lastError,
                        :lastVerificationAt, :servedContractVersion, :priceHistoryTicks, :createdAt, :updatedAt)
                ON CONFLICT (strategy_ref) DO UPDATE SET
                    base_url = EXCLUDED.base_url,
                    token = EXCLUDED.token,
                    status = EXCLUDED.status,
                    verification = EXCLUDED.verification,
                    last_error = EXCLUDED.last_error,
                    last_verification_at = EXCLUDED.last_verification_at,
                    served_contract_version = EXCLUDED.served_contract_version,
                    price_history_ticks = EXCLUDED.price_history_ticks,
                    updated_at = EXCLUDED.updated_at
                """,
                new MapSqlParameterSource()
                        .addValue("ref", strategy.getStrategyRef())
                        .addValue("name", strategy.getName())
                        .addValue("version", strategy.getVersion())
                        .addValue("baseUrl", strategy.getEndpoint().baseUrl())
                        .addValue("token", strategy.getEndpoint().token())
                        .addValue("status", strategy.getStatus().name())
                        .addValue("verification", strategy.getVerification().name())
                        .addValue("lastError", strategy.getLastError())
                        .addValue("lastVerificationAt", timestamp(strategy.getLastVerificationAt()))
                        .addValue("servedContractVersion", strategy.getServedContractVersion())
                        .addValue("priceHistoryTicks", strategy.getPriceHistoryTicks())
                        .addValue("createdAt", timestamp(strategy.getCreatedAt()))
                        .addValue("updatedAt", timestamp(strategy.getUpdatedAt())));
        for (RemoteStrategyEvent event : events) {
            jdbcTemplate.update("""
                    INSERT INTO remote_strategy_events (id, strategy_ref, type, reason, detail, occurred_at)
                    VALUES (:id, :ref, :type, :reason, :detail, :occurredAt)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", event.id())
                            .addValue("ref", event.strategyRef())
                            .addValue("type", event.type().name())
                            .addValue("reason", event.reason())
                            .addValue("detail", event.detail())
                            .addValue("occurredAt", timestamp(event.occurredAt())));
        }
        log.trace("[REPO] remoteStrategy.save({}, events={}) - {}ms", strategy.getStrategyRef(), events.size(),
                RepoTiming.elapsedMs(start));
    }

    @Override
    public List<RemoteStrategyEvent> findEvents(UUID strategyRef) {
        return jdbcTemplate.query("""
                SELECT * FROM remote_strategy_events WHERE strategy_ref = :ref ORDER BY seq
                """, new MapSqlParameterSource("ref", strategyRef), EVENT);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
