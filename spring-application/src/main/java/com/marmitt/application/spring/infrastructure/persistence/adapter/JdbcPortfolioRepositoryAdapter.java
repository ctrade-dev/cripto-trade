package com.marmitt.application.spring.infrastructure.persistence.adapter;

import com.marmitt.application.spring.infrastructure.persistence.mapper.PortfolioEntityMapper;
import com.marmitt.application.spring.infrastructure.persistence.repository.PortfolioJdbcRepository;
import com.marmitt.core.domain.portfolio.Portfolio;
import com.marmitt.core.domain.portfolio.SafeModeChange;
import com.marmitt.core.enums.SafeModeOrigin;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.exceptions.SafeModeTransitionException;
import com.marmitt.core.ports.outbound.repository.PortfolioRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcPortfolioRepositoryAdapter implements PortfolioRepositoryPort {

    private final PortfolioJdbcRepository portfolioRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public Optional<Portfolio> findById(UUID portfolioId) {
        long start = System.nanoTime();
        Optional<Portfolio> result = portfolioRepository.findById(portfolioId)
                .map(PortfolioEntityMapper::toDomain);
        log.trace("[REPO] findById({}) - {}ms", portfolioId, RepoTiming.elapsedMs(start));
        return result;
    }

    @Override
    public Optional<Portfolio> findByName(String portfolioName) {
        long start = System.nanoTime();
        Optional<Portfolio> result = portfolioRepository.findByNameIgnoreCase(portfolioName)
                .map(PortfolioEntityMapper::toDomain);
        log.trace("[REPO] findByName({}) - {}ms", portfolioName, RepoTiming.elapsedMs(start));
        return result;
    }

    @Override
    public List<Portfolio> findBySymbol(String symbol) {
        long start = System.nanoTime();
        List<Portfolio> result = portfolioRepository.findBySymbol(symbol).stream()
                .map(PortfolioEntityMapper::toDomain)
                .toList();
        log.trace("[REPO] findBySymbol({}) - {}ms - {} results", symbol, RepoTiming.elapsedMs(start), result.size());
        return result;
    }

    @Override
    public List<Portfolio> findAll() {
        long start = System.nanoTime();
        List<Portfolio> result = StreamSupport.stream(portfolioRepository.findAll().spliterator(), false)
                .map(PortfolioEntityMapper::toDomain)
                .toList();
        log.trace("[REPO] findAll() - {}ms - {} results", RepoTiming.elapsedMs(start), result.size());
        return result;
    }

    @Override
    @Transactional
    public void registerPortfolio(Portfolio portfolio) {
        long start = System.nanoTime();
        UUID portfolioId = portfolio.getId();

        if (portfolioRepository.existsById(portfolioId)) {
            log.warn("Portfolio already exists with ID: {}", portfolioId);
            return;
        }

        Optional<Portfolio> existingByName = portfolioRepository.findByNameIgnoreCase(portfolio.getName())
                .map(PortfolioEntityMapper::toDomain);
        if (existingByName.isPresent() && !existingByName.get().getId().equals(portfolioId)) {
            throw new IllegalArgumentException(
                    "Portfolio with name '" + portfolio.getName() + "' already exists with different ID"
            );
        }

        portfolioRepository.save(PortfolioEntityMapper.toPortfolioEntity(portfolio));

        log.info("Portfolio registered - ID: {}, Name: {}", portfolioId, portfolio.getName());
        log.trace("[REPO] registerPortfolio({}) - {}ms", portfolioId, RepoTiming.elapsedMs(start));
    }

    @Override
    @Transactional
    public void saveSafeModeChange(Portfolio portfolio, SafeModeChange change) {
        long start = System.nanoTime();
        int updated = jdbcTemplate.update("""
                UPDATE portfolios
                   SET safe_mode_status = :status,
                       safe_mode_origin = :origin,
                       safe_mode_trigger = :trigger,
                       safe_mode_reason = :reason,
                       safe_mode_changed_at = :changedAt,
                       version = COALESCE(version, 0) + 1
                 WHERE id = :id
                   AND COALESCE(version, 0) = :version
                """,
                new MapSqlParameterSource()
                        .addValue("status", change.to().name())
                        .addValue("origin", change.origin().name())
                        .addValue("trigger", triggerName(change))
                        .addValue("reason", change.reason())
                        .addValue("changedAt", Timestamp.from(change.changedAt()))
                        .addValue("id", portfolio.getId())
                        .addValue("version", portfolio.getVersion() == null ? 0L : portfolio.getVersion()));
        if (updated == 0) {
            throw new SafeModeTransitionException(
                    "Portfolio " + portfolio.getId() + " was changed concurrently - reload and retry");
        }
        jdbcTemplate.update("""
                INSERT INTO safe_mode_changes(id, portfolio_id, from_status, to_status, origin, trigger, reason, changed_at)
                VALUES (:id, :portfolioId, :from, :to, :origin, :trigger, :reason, :changedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", change.id())
                        .addValue("portfolioId", change.portfolioId())
                        .addValue("from", change.from().name())
                        .addValue("to", change.to().name())
                        .addValue("origin", change.origin().name())
                        .addValue("trigger", triggerName(change))
                        .addValue("reason", change.reason())
                        .addValue("changedAt", Timestamp.from(change.changedAt())));
        log.trace("[REPO] saveSafeModeChange({}, {} -> {}) - {}ms",
                portfolio.getId(), change.from(), change.to(), RepoTiming.elapsedMs(start));
    }

    @Override
    public long countSafeModeChanges(UUID portfolioId, SafeModeOrigin origin, SafeModeStatus to, Instant since) {
        long start = System.nanoTime();
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM safe_mode_changes
                 WHERE portfolio_id = :portfolioId
                   AND origin = :origin
                   AND to_status = :to
                   AND changed_at >= :since
                """,
                new MapSqlParameterSource()
                        .addValue("portfolioId", portfolioId)
                        .addValue("origin", origin.name())
                        .addValue("to", to.name())
                        .addValue("since", Timestamp.from(since)),
                Long.class);
        log.trace("[REPO] countSafeModeChanges({}, {}, {}, {}) - {}ms",
                portfolioId, origin, to, since, RepoTiming.elapsedMs(start));
        return count == null ? 0L : count;
    }

    private static String triggerName(SafeModeChange change) {
        return change.trigger() == null ? null : change.trigger().name();
    }
}
