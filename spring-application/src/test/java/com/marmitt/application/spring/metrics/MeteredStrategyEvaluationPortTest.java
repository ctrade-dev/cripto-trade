package com.marmitt.application.spring.metrics;

import com.marmitt.application.spring.infrastructure.strategy.InMemoryStrategyCallHealth;
import com.marmitt.core.domain.strategy.StrategyEndpoint;
import com.marmitt.core.dto.strategy.StrategyContextDto;
import com.marmitt.core.dto.strategy.StrategyInputDto;
import com.marmitt.core.dto.strategy.StrategyOutputDto;
import com.marmitt.core.exceptions.StrategyUnavailableException;
import com.marmitt.core.ports.outbound.strategy.StrategyEvaluationPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MeteredStrategyEvaluationPortTest {

    private static final Instant NOW = Instant.parse("2026-11-10T15:00:00Z");
    private static final UUID REF = UUID.randomUUID();

    /** T53 F21: a sequencia de falhas comeca na primeira falha e um sucesso a zera. */
    @Test
    void failureStreakStartsAtTheFirstFailureAndASuccessClearsIt() {
        AtomicBoolean down = new AtomicBoolean(true);
        StrategyEvaluationPort remote = new StrategyEvaluationPort() {
            @Override
            public StrategyOutputDto evaluate(UUID ref, StrategyEndpoint endpoint, StrategyInputDto input,
                                              StrategyContextDto context) {
                if (down.get()) {
                    throw new StrategyUnavailableException(StrategyUnavailableException.Reason.UNREACHABLE, "refused");
                }
                return StrategyOutputDto.hold("Soak", "ok");
            }

            @Override
            public RemoteStrategyIdentity describe(UUID ref, StrategyEndpoint endpoint) {
                throw new UnsupportedOperationException();
            }
        };
        InMemoryStrategyCallHealth health = new InMemoryStrategyCallHealth(Clock.fixed(NOW, ZoneOffset.UTC));
        MeteredStrategyEvaluationPort port = new MeteredStrategyEvaluationPort(remote, new SimpleMeterRegistry(), health);
        StrategyEndpoint endpoint = mock(StrategyEndpoint.class);

        assertThrows(StrategyUnavailableException.class, () -> port.evaluate(REF, endpoint, null, null));
        assertThrows(StrategyUnavailableException.class, () -> port.evaluate(REF, endpoint, null, null));
        assertEquals(NOW, health.failingSince(REF).orElseThrow().since());
        assertEquals("UNREACHABLE: refused", health.failingSince(REF).orElseThrow().lastReason());

        down.set(false);
        port.evaluate(REF, endpoint, null, null);
        assertTrue(health.failingSince(REF).isEmpty());
    }
}
