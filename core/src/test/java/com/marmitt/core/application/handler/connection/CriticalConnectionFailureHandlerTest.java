package com.marmitt.core.application.handler.connection;

import com.marmitt.core.domain.runner.RunnerStatusCause;
import com.marmitt.core.dto.events.WebSocketFailedEvent;
import com.marmitt.core.dto.runner.response.RunnerTransitionResult;
import com.marmitt.core.enums.RunnerStatusOrigin;
import com.marmitt.core.enums.StreamChannel;
import com.marmitt.core.ports.inbound.runner.HaltRunnerPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CriticalConnectionFailureHandlerTest {

    @Test
    void critical_event_triggers_haltAll() {
        RecordingHaltRunnerPort haltPort = new RecordingHaltRunnerPort();
        CriticalConnectionFailureHandler handler = new CriticalConnectionFailureHandler(haltPort);

        WebSocketFailedEvent critical = WebSocketFailedEvent.critical(
                "BINANCE", "Max attempts", StreamChannel.MARKET, 10);

        assertTrue(critical.isCritical());
        handler.execute(critical);

        assertTrue(haltPort.haltAllCalled, "haltAll must be called on critical failure");
        assertEquals(RunnerStatusOrigin.AUTOMATIC, haltPort.cause.origin());
        assertTrue(haltPort.cause.reason().contains("exchange=BINANCE"), haltPort.cause.reason());
    }

    @Test
    void non_critical_event_does_not_trigger_haltAll() {
        RecordingHaltRunnerPort haltPort = new RecordingHaltRunnerPort();
        CriticalConnectionFailureHandler handler = new CriticalConnectionFailureHandler(haltPort);

        WebSocketFailedEvent nonCritical = WebSocketFailedEvent.of(
                "BINANCE", "Connection dropped", UUID.randomUUID(), null, StreamChannel.MARKET);

        assertFalse(nonCritical.isCritical());
        handler.execute(nonCritical);

        assertFalse(haltPort.haltAllCalled, "haltAll must NOT be called for non-critical failure");
    }

    static class RecordingHaltRunnerPort implements HaltRunnerPort {
        boolean haltAllCalled = false;
        RunnerStatusCause cause;

        @Override
        public List<RunnerTransitionResult> haltAll(RunnerStatusCause cause) {
            haltAllCalled = true;
            this.cause = cause;
            return new ArrayList<>();
        }

        @Override
        public RunnerTransitionResult haltById(UUID id, RunnerStatusCause cause) {
            return null;
        }
    }
}
