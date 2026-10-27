package com.marmitt.application.spring.infrastructure.persistence.repository;

import com.marmitt.application.spring.infrastructure.persistence.entity.RunnerPositionEntity;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;

@Repository
public interface RunnerPositionJdbcRepository extends CrudRepository<RunnerPositionEntity, UUID> {

    @Query("SELECT * FROM positions WHERE runner_id = :runnerId AND status = 'OPEN'")
    List<RunnerPositionEntity> findOpenByRunnerId(@Param("runnerId") UUID runnerId);

    @Query("""
            SELECT p.* FROM positions p
              JOIN strategy_runners r ON r.id = p.runner_id
             WHERE r.portfolio_id = :portfolioId
               AND p.status IN ('OPEN','CLOSING')
            """)
    List<RunnerPositionEntity> findActiveByPortfolioId(@Param("portfolioId") UUID portfolioId);

    @Query("SELECT * FROM positions WHERE runner_id = :runnerId AND UPPER(symbol) = UPPER(:symbol) AND status = 'OPEN' AND locked_by_transaction_id IS NULL")
    Optional<RunnerPositionEntity> findOpenByRunnerIdAndSymbol(
            @Param("runnerId") UUID runnerId,
            @Param("symbol") String symbol
    );

    @Query("""
            SELECT * FROM positions
             WHERE runner_id = :runnerId
               AND UPPER(symbol) = UPPER(:symbol)
               AND status = 'OPEN'
               AND locked_by_transaction_id IS NULL
             ORDER BY updated_at DESC
             LIMIT 1
             FOR UPDATE
            """)
    Optional<RunnerPositionEntity> findOpenByRunnerIdAndSymbolForUpdate(
            @Param("runnerId") UUID runnerId,
            @Param("symbol") String symbol
    );

    @Query("SELECT * FROM positions WHERE opened_by_transaction_id = :transactionId AND status IN ('OPEN','CLOSING') ORDER BY updated_at DESC LIMIT 1")
    Optional<RunnerPositionEntity> findByOpenedByTransactionId(
            @Param("transactionId") UUID transactionId
    );

    @Query("""
            SELECT * FROM positions
             WHERE opened_by_transaction_id = :transactionId
               AND status IN ('OPEN','CLOSING')
             ORDER BY updated_at DESC
             LIMIT 1
             FOR UPDATE
            """)
    Optional<RunnerPositionEntity> findByOpenedByTransactionIdForUpdate(
            @Param("transactionId") UUID transactionId
    );

    @Query("SELECT * FROM positions WHERE runner_id = :runnerId AND UPPER(symbol) = UPPER(:symbol) AND status IN ('OPEN','CLOSING') ORDER BY updated_at DESC LIMIT 1")
    Optional<RunnerPositionEntity> findActiveByRunnerIdAndSymbol(
            @Param("runnerId") UUID runnerId,
            @Param("symbol") String symbol
    );

    @Query("""
            SELECT * FROM positions
             WHERE locked_by_transaction_id = :transactionId
               AND status = 'CLOSING'
             ORDER BY updated_at DESC
             LIMIT 1
             FOR UPDATE
            """)
    Optional<RunnerPositionEntity> findLockedByTransactionIdForUpdate(
            @Param("transactionId") UUID transactionId
    );

    @Query("""
            SELECT * FROM positions
             WHERE id = :positionId
             FOR UPDATE
            """)
    Optional<RunnerPositionEntity> findByIdForUpdate(@Param("positionId") UUID positionId);

    @org.springframework.data.jdbc.repository.query.Modifying
    @Query("""
            UPDATE positions
               SET locked_by_transaction_id = :transactionId,
                   locked_quantity = :quantity,
                   locked_at = NOW(),
                   status = 'CLOSING',
                   updated_at = NOW(),
                   version = version + 1
             WHERE id = :positionId
               AND status = 'OPEN'
               AND locked_by_transaction_id IS NULL
               AND quantity >= :quantity
            """)
    int tryLockPositionForSell(
            @Param("positionId") UUID positionId,
            @Param("transactionId") UUID transactionId,
            @Param("quantity") BigDecimal quantity
    );

    @Query("""
            SELECT COUNT(*) > 0
              FROM positions
             WHERE id = :positionId
               AND status = 'CLOSING'
               AND locked_by_transaction_id = :transactionId
               AND locked_quantity = :quantity
            """)
    boolean isLockedByTransaction(
            @Param("positionId") UUID positionId,
            @Param("transactionId") UUID transactionId,
            @Param("quantity") BigDecimal quantity
    );
}
