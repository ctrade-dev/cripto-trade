package com.marmitt.application.spring.bootstrap;

import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.portfolio.response.TransactionDto;
import com.marmitt.core.dto.strategy.StrategyContextDto;
import com.marmitt.core.dto.strategy.StrategyInputDto;
import com.marmitt.core.dto.strategy.StrategyOutputDto;
import com.marmitt.core.enums.TradingAction;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.ports.inbound.portfolio.QueryPortfolioPort;
import com.marmitt.core.ports.outbound.strategy.TradingStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T74 — o {@code metadata} da decisao da estrategia (ex.: {@code exitKind}, {@code stop}) fica gravado na ordem e sai
 * na exportacao das transacoes do portfolio por periodo, para comparar o staging com o backtest da estrategia.
 */
class StrategyDecisionMetadataE2ETest extends ScenarioStrategyE2ETestSupport {

    @Autowired
    private QueryPortfolioPort queryPortfolio;

    @Test
    void decisionMetadataIsStoredWithTheOrderAndExportedByPeriod() {
        Instant before = Instant.now().minus(1, ChronoUnit.SECONDS);
        UUID runnerId = activateScenario(new BuyOnceWithMetadataStrategy());
        UUID portfolioId = portfolioIdOf(runnerId);

        driveTicks(1);
        awaitCondition(() -> transactionCount(runnerId) == 1, WAIT_TIMEOUT, "BUY da estrategia gravada", runnerId);

        Transaction buy = strategyRunnerRepository.findByRunnerIdAndStatuses(runnerId,
                List.of(TransactionStatus.values())).getFirst();
        assertThat(buy.getStrategyMetadata()).isEqualTo(BuyOnceWithMetadataStrategy.METADATA);
        assertThat(strategyRunnerRepository.findTransactionById(buy.getId()).orElseThrow().getStrategyMetadata())
                .as("relido do banco").isEqualTo(BuyOnceWithMetadataStrategy.METADATA);

        List<TransactionDto> inWindow = queryPortfolio.findTransactionsByPortfolioId(portfolioId, before,
                Instant.now().plus(1, ChronoUnit.MINUTES));
        assertThat(inWindow).singleElement().satisfies(dto -> {
            assertThat(dto.runnerId()).isEqualTo(runnerId);
            assertThat(dto.reasoning()).isEqualTo("t74: entrada no canal");
            assertThat(dto.strategyMetadata()).isEqualTo(BuyOnceWithMetadataStrategy.METADATA);
        });
        assertThat(queryPortfolio.findTransactionsByPortfolioId(portfolioId, null, before)).isEmpty();
        assertThat(queryPortfolio.findTransactionsByPortfolioId(portfolioId, null, null)).hasSize(1);
    }

    /** Uma BUY no preco do tick, com metadata de tipos variados (texto, numero, objeto, nulo). */
    private static final class BuyOnceWithMetadataStrategy implements TradingStrategy {

        static final Map<String, Object> METADATA = metadata();

        private final UUID id = UUID.randomUUID();
        private boolean enabled = true;
        private boolean bought;

        private static Map<String, Object> metadata() {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("exitKind", null);
            metadata.put("stop", "61234.50");
            metadata.put("atr", 1234.5);
            metadata.put("channel", Map.of("high", "65000.00", "window", 20));
            return metadata;
        }

        @Override
        public StrategyOutputDto executeStrategy(StrategyInputDto input, StrategyContextDto context) {
            if (bought) {
                return StrategyOutputDto.hold(getStrategyName(), "t74: ja comprou");
            }
            bought = true;
            return new StrategyOutputDto(getStrategyName(), TradingAction.SHOULD_BUY, BigDecimal.ONE,
                    new BigDecimal("0.001"), input.currentPrice(), null, null, "t74: entrada no canal", Instant.now(),
                    METADATA);
        }

        @Override
        public UUID getStrategyId() {
            return id;
        }

        @Override
        public String getStrategyName() {
            return "T74DecisionMetadata";
        }

        @Override
        public String getStrategyVersion() {
            return "1.0.0";
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
