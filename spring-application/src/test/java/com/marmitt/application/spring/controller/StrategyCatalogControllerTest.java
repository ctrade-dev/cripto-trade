package com.marmitt.application.spring.controller;

import com.marmitt.application.spring.web.GlobalExceptionHandler;
import com.marmitt.core.domain.strategy.RemoteStrategyStatus;
import com.marmitt.core.domain.strategy.RemoteStrategyVerification;
import com.marmitt.core.dto.strategy.request.RegisterRemoteStrategyRequest;
import com.marmitt.core.dto.strategy.response.RemoteStrategyView;
import com.marmitt.core.exceptions.StrategyCatalogException;
import com.marmitt.core.exceptions.StrategyCatalogException.Code;
import com.marmitt.core.ports.inbound.strategy.ManageRemoteStrategiesPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T54: borda HTTP do catalogo de estrategias. Regras no core; aqui so o mapeamento e os codigos de recusa.
 */
class StrategyCatalogControllerTest {

    private final ManageRemoteStrategiesPort catalog = mock(ManageRemoteStrategiesPort.class);
    private final UUID ref = UUID.randomUUID();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new StrategyCatalogController(catalog))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void registerReturnsTheResultingStateWithoutToken() throws Exception {
        when(catalog.register(any(), any())).thenReturn(view());

        mockMvc.perform(post("/api/admin/strategies").header("X-Reason", "first deploy")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"strategyRef":"%s","name":"SMA","version":"1.0.0","baseUrl":"http://s:8090/v1",
                         "token":"secret"}""".formatted(ref)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verification").value("VERIFIED"))
                .andExpect(jsonPath("$.usable").value(true))
                .andExpect(jsonPath("$.tokenConfigured").value(true))
                .andExpect(jsonPath("$.token").doesNotExist());
        verify(catalog).register(new RegisterRemoteStrategyRequest(ref, "SMA", "1.0.0", "http://s:8090/v1",
                "secret"), "first deploy");
    }

    @Test
    void refusalsCarryAStableCodeAndTheMatchingStatus() throws Exception {
        when(catalog.disable(ref, "why")).thenThrow(new StrategyCatalogException(Code.STRATEGY_NOT_FOUND, "missing"));
        when(catalog.enable(ref, " ")).thenThrow(new StrategyCatalogException(Code.REASON_REQUIRED, "reason"));
        when(catalog.register(any(), any())).thenThrow(
                new StrategyCatalogException(Code.STRATEGY_DEFINITION_CONFLICT, "different definition"));

        mockMvc.perform(post("/api/admin/strategies/{ref}/disable", ref).header("X-Reason", "why"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STRATEGY_NOT_FOUND"));
        mockMvc.perform(post("/api/admin/strategies/{ref}/enable", ref).header("X-Reason", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        mockMvc.perform(post("/api/admin/strategies").header("X-Reason", "why")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STRATEGY_DEFINITION_CONFLICT"));
    }

    private RemoteStrategyView view() {
        Instant now = Instant.parse("2026-10-20T12:00:00Z");
        return new RemoteStrategyView(ref, "SMA", "1.0.0", "http://s:8090/v1", true, RemoteStrategyStatus.ENABLED,
                RemoteStrategyVerification.VERIFIED, true, null, now, "2.0.0", 20, now, now, List.of());
    }
}
