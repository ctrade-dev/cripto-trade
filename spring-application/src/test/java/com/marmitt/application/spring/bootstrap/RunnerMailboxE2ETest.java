package com.marmitt.application.spring.bootstrap;

import com.marmitt.core.domain.Symbol;
import com.marmitt.core.dto.websocket.data.MarketDataDto;
import com.marmitt.application.spring.testsupport.strategy.scenario.RestingBuyCancelStrategy;
import com.marmitt.application.spring.testsupport.strategy.scenario.ScenarioStrategyConfig;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T42 — mailbox no pipeline real: tick que envelheceu alem do limite nao e avaliado.
 */
class RunnerMailboxE2ETest extends ScenarioStrategyE2ETestSupport {

    @Test
    void staleTickIsDiscardedWithoutEvaluatingTheStrategy() {
        UUID runnerId = activateScenario(new RestingBuyCancelStrategy(ScenarioStrategyConfig.boundedConfig(1)));

        processTradeSignalPort.execute(tickAt(Instant.now().minusSeconds(30)));

        awaitCondition(() -> discarded(runnerId, "STALE") >= 1.0, WAIT_TIMEOUT, "tick velho descartado", runnerId);
        sleep(QUIET_WINDOW.toMillis());
        assertEquals(0, transactionCount(runnerId), "Tick velho nao pode gerar ordem");
        assertEquals(0.0, signalEvaluatedCount(runnerId, "BUY"), "A estrategia nao foi consultada");
    }

    private MarketDataDto tickAt(Instant receivedAt) {
        mockAdapter.seedReferencePrice(SYMBOL, MARKET_PRICE);
        return new MarketDataDto(MARKET_DATA_EXCHANGE, Symbol.of(SYMBOL), MARKET_PRICE, MARKET_PRICE, MARKET_PRICE,
                new BigDecimal("1000"), MARKET_PRICE, MARKET_PRICE, BigDecimal.ZERO, BigDecimal.ZERO, receivedAt);
    }

    private double discarded(UUID runnerId, String reason) {
        try {
            return meterRegistry.get("runner.ticks.discarded")
                    .tags("runnerId", runnerId.toString(), "reason", reason)
                    .counter()
                    .count();
        } catch (MeterNotFoundException e) {
            return 0.0;
        }
    }
}
