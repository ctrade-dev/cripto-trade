package com.marmitt.application.spring.bootstrap;

import com.marmitt.application.spring.testsupport.DatabaseCleaner;
import com.marmitt.core.domain.portfolio.GlobalBalance;
import com.marmitt.core.dto.portfolio.request.CreatePortfolioRequest;
import com.marmitt.core.dto.portfolio.response.CreatePortfolioResponse;
import com.marmitt.core.ports.inbound.portfolio.CreatePortfolioPort;
import com.marmitt.core.ports.outbound.repository.GlobalBalanceRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F31: a reserva atomica e a gravacao otimista do saldo disputam a mesma linha. Uma confirmacao que leu o
 * saldo antes de uma reserva nao pode grava-lo por cima e apagar a reserva.
 */
class GlobalBalanceConcurrentReservationIntegrationTest extends AbstractIntegrationTest {

    private static final BigDecimal INITIAL_CAPITAL = new BigDecimal("1000.00000000");
    private static final BigDecimal FIRST_RESERVATION = new BigDecimal("100.00000000");
    private static final BigDecimal SECOND_RESERVATION = new BigDecimal("42.19605650");

    @Autowired
    private CreatePortfolioPort createPortfolioPort;

    @Autowired
    private GlobalBalanceRepositoryPort globalBalanceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        DatabaseCleaner.reset(jdbcTemplate);
    }

    @Test
    void confirmationReadBeforeAReservationCannotOverwriteIt() {
        UUID portfolioId = createPortfolio();
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, FIRST_RESERVATION));

        GlobalBalance readBeforeReservation = balance(portfolioId);
        assertTrue(globalBalanceRepository.reserveAtomic(portfolioId, SECOND_RESERVATION));
        readBeforeReservation.confirmExecution(FIRST_RESERVATION, BigDecimal.ZERO, BigDecimal.ZERO);

        assertThrows(OptimisticLockingFailureException.class,
                () -> globalBalanceRepository.save(readBeforeReservation));

        GlobalBalance current = balance(portfolioId);
        assertEquals(0, FIRST_RESERVATION.add(SECOND_RESERVATION).compareTo(current.getReservedBalance()));

        current.confirmExecution(FIRST_RESERVATION, BigDecimal.ZERO, BigDecimal.ZERO);
        globalBalanceRepository.save(current);

        GlobalBalance afterRetry = balance(portfolioId);
        assertEquals(0, SECOND_RESERVATION.compareTo(afterRetry.getReservedBalance()));
        assertEquals(0, INITIAL_CAPITAL.subtract(SECOND_RESERVATION).compareTo(afterRetry.getAvailableBalance()));
    }

    private GlobalBalance balance(UUID portfolioId) {
        return globalBalanceRepository.findByPortfolioId(portfolioId)
                .orElseThrow(() -> new IllegalStateException("Balance not found: " + portfolioId));
    }

    private UUID createPortfolio() {
        CreatePortfolioResponse response = createPortfolioPort.execute(
                CreatePortfolioRequest.builder()
                        .name("f31-concurrent-reservation-" + UUID.randomUUID())
                        .initialCapitalAmount(INITIAL_CAPITAL)
                        .currency("USDT")
                        .build()
        );
        assertNotNull(response.portfolioId(), "Portfolio creation failed: " + response.message());
        return response.portfolioId();
    }
}
