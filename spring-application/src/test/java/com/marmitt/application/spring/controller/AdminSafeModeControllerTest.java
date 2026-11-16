package com.marmitt.application.spring.controller;

import com.marmitt.application.spring.web.GlobalExceptionHandler;
import com.marmitt.core.dto.portfolio.request.ChangeSafeModeRequest;
import com.marmitt.core.dto.portfolio.response.SafeModeStatusDto;
import com.marmitt.core.enums.SafeModeOrigin;
import com.marmitt.core.enums.SafeModeStatus;
import com.marmitt.core.exceptions.PortfolioNotFoundException;
import com.marmitt.core.exceptions.SafeModeTransitionException;
import com.marmitt.core.ports.inbound.portfolio.ManageSafeModePort;
import com.marmitt.core.ports.inbound.runner.HaltRunnerPort;
import com.marmitt.core.ports.inbound.runner.ManageRunnerLifecyclePort;
import com.marmitt.core.ports.inbound.runner.QueryRunnerPort;
import com.marmitt.core.ports.inbound.runner.RunnerOrderCommandPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T45: borda HTTP do Safe Mode. Regras de transicao ficam no core; aqui so o mapeamento.
 */
class AdminSafeModeControllerTest {

    private final ManageSafeModePort manageSafeMode = mock(ManageSafeModePort.class);
    private final UUID portfolioId = UUID.randomUUID();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminController(List.of(), mock(HaltRunnerPort.class),
                        mock(QueryRunnerPort.class), manageSafeMode, mock(ManageRunnerLifecyclePort.class),
                        mock(RunnerOrderCommandPort.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void putChangesAsManualAndReturnsAccepted() throws Exception {
        when(manageSafeMode.change(eq(portfolioId), eq(SafeModeOrigin.MANUAL), any()))
                .thenReturn(statusDto(SafeModeStatus.CANCEL_ALL, 3));

        mockMvc.perform(put("/api/admin/portfolios/{id}/safe-mode", portfolioId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Reason", "exchange instavel")
                        .content("{\"level\":\"CANCEL_ALL\",\"confirm\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.level").value("CANCEL_ALL"))
                .andExpect(jsonPath("$.restingOrders").value(3));

        verify(manageSafeMode).change(portfolioId, SafeModeOrigin.MANUAL,
                ChangeSafeModeRequest.manual(SafeModeStatus.CANCEL_ALL, "exchange instavel", true));
    }

    @Test
    void rejectedTransitionIsConflict() throws Exception {
        when(manageSafeMode.change(eq(portfolioId), eq(SafeModeOrigin.MANUAL), any()))
                .thenThrow(new SafeModeTransitionException("PANIC_SELL requires explicit confirmation"));

        mockMvc.perform(put("/api/admin/portfolios/{id}/safe-mode", portfolioId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Reason", "x")
                        .content("{\"level\":\"PANIC_SELL\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownPortfolioIsNotFound() throws Exception {
        when(manageSafeMode.status(portfolioId)).thenThrow(new PortfolioNotFoundException(portfolioId));

        mockMvc.perform(get("/api/admin/portfolios/{id}/safe-mode", portfolioId))
                .andExpect(status().isNotFound());
    }

    @Test
    void getReturnsLevelAndProgress() throws Exception {
        when(manageSafeMode.status(portfolioId)).thenReturn(statusDto(SafeModeStatus.PANIC_SELL, 0));

        mockMvc.perform(get("/api/admin/portfolios/{id}/safe-mode", portfolioId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("PANIC_SELL"))
                .andExpect(jsonPath("$.openPositions").value(2));
    }

    private SafeModeStatusDto statusDto(SafeModeStatus level, int restingOrders) {
        return new SafeModeStatusDto(portfolioId, level, SafeModeOrigin.MANUAL, "r", Instant.now(),
                restingOrders, 0, 2, 0);
    }
}
