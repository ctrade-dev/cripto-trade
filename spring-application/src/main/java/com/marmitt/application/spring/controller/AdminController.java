package com.marmitt.application.spring.controller;

import com.marmitt.application.spring.web.OperatorCommandInterceptor;
import com.marmitt.core.enums.SafeModeStatus;
import org.springframework.web.bind.annotation.RequestHeader;

import com.marmitt.binance.filters.SymbolFilterCache;
import com.marmitt.core.domain.runner.RunnerStatusCause;
import com.marmitt.core.dto.portfolio.request.ChangeSafeModeRequest;
import com.marmitt.core.dto.portfolio.response.SafeModeStatusDto;
import com.marmitt.core.dto.runner.response.RunnerDto;
import com.marmitt.core.dto.runner.response.RunnerTransitionResult;
import com.marmitt.core.enums.SafeModeOrigin;
import com.marmitt.core.ports.inbound.portfolio.ManageSafeModePort;
import com.marmitt.core.dto.runner.request.UpdateRunnerLimitsRequest;
import com.marmitt.core.ports.inbound.runner.HaltRunnerPort;
import com.marmitt.core.ports.inbound.runner.ManageRunnerLifecyclePort;
import com.marmitt.core.ports.inbound.runner.RunnerOrderCommandPort;
import com.marmitt.core.ports.inbound.runner.QueryRunnerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final List<SymbolFilterCache> filterCaches;
    private final HaltRunnerPort haltRunner;
    private final QueryRunnerPort queryRunner;
    private final ManageSafeModePort manageSafeMode;
    private final ManageRunnerLifecyclePort runnerLifecycle;
    private final RunnerOrderCommandPort runnerOrderCommands;

    public AdminController(List<SymbolFilterCache> filterCaches,
                           HaltRunnerPort haltRunner,
                           QueryRunnerPort queryRunner,
                           ManageSafeModePort manageSafeMode,
                           ManageRunnerLifecyclePort runnerLifecycle,
                           RunnerOrderCommandPort runnerOrderCommands) {
        this.filterCaches = filterCaches;
        this.haltRunner = haltRunner;
        this.queryRunner = queryRunner;
        this.manageSafeMode = manageSafeMode;
        this.runnerLifecycle = runnerLifecycle;
        this.runnerOrderCommands = runnerOrderCommands;
    }

    @PostMapping("/filters/refresh")
    public ResponseEntity<Void> refreshFilters() {
        filterCaches.forEach(SymbolFilterCache::invalidateAll);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/runners/halt")
    public ResponseEntity<List<RunnerTransitionResult>> haltAll(
            @RequestHeader(OperatorCommandInterceptor.REASON) String reason) {
        return ResponseEntity.ok(haltRunner.haltAll(RunnerStatusCause.manual(reason)));
    }

    @PostMapping("/runners/{id}/halt")
    public ResponseEntity<RunnerTransitionResult> haltById(@PathVariable UUID id,
                                                           @RequestHeader(OperatorCommandInterceptor.REASON) String reason) {
        return ResponseEntity.ok(haltRunner.haltById(id, RunnerStatusCause.manual(reason)));
    }

    @GetMapping("/runners/status")
    public ResponseEntity<List<RunnerDto>> runnersStatus() {
        return ResponseEntity.ok(queryRunner.findAll());
    }

    @PostMapping("/runners/{id}/start")
    public ResponseEntity<RunnerTransitionResult> start(@PathVariable UUID id,
                                                        @RequestHeader(OperatorCommandInterceptor.REASON) String reason) {
        return ResponseEntity.ok(runnerLifecycle.start(id, reason));
    }

    @PostMapping("/runners/{id}/resume")
    public ResponseEntity<RunnerTransitionResult> resume(@PathVariable UUID id,
                                                         @RequestHeader(OperatorCommandInterceptor.REASON) String reason) {
        return ResponseEntity.ok(runnerLifecycle.resume(id, reason));
    }

    @PostMapping("/runners/{id}/terminate")
    public ResponseEntity<RunnerTransitionResult> terminate(@PathVariable UUID id,
                                                            @RequestHeader(OperatorCommandInterceptor.REASON) String reason) {
        return ResponseEntity.ok(runnerLifecycle.terminate(id, reason));
    }

    @PatchMapping("/runners/{id}")
    public ResponseEntity<RunnerTransitionResult> updateLimits(@PathVariable UUID id,
                                                               @RequestBody UpdateRunnerLimitsRequest request) {
        return ResponseEntity.ok(runnerLifecycle.updateLimits(id, request));
    }

    /** 202: o cancelamento e enviado; o CANCELED chega pelo stream. */
    @PostMapping("/runners/{id}/orders/{transactionId}/cancel")
    public ResponseEntity<Void> cancelOrder(@PathVariable UUID id, @PathVariable UUID transactionId) {
        runnerOrderCommands.cancelOrder(id, transactionId);
        return ResponseEntity.accepted().build();
    }

    /** 202: a venda a mercado e enviada; o fechamento chega pela conciliacao. */
    @PostMapping("/runners/{id}/positions/{positionId}/close")
    public ResponseEntity<Void> closePosition(@PathVariable UUID id, @PathVariable UUID positionId) {
        runnerOrderCommands.closePosition(id, positionId);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/portfolios/{id}/safe-mode")
    public ResponseEntity<SafeModeStatusDto> safeModeStatus(@PathVariable UUID id) {
        return ResponseEntity.ok(manageSafeMode.status(id));
    }

    /**
     * Muda o nivel do Safe Mode (T45). 202: os niveis de acao em massa executam de forma
     * assincrona; acompanhe o progresso pelo GET.
     */
    @PutMapping("/portfolios/{id}/safe-mode")
    public ResponseEntity<SafeModeStatusDto> changeSafeMode(@PathVariable UUID id,
                                                            @RequestBody SafeModeChange body,
                                                            @RequestHeader(OperatorCommandInterceptor.REASON) String reason) {
        return ResponseEntity.accepted().body(manageSafeMode.change(id, SafeModeOrigin.MANUAL,
                ChangeSafeModeRequest.manual(body.level(), reason, body.confirm())));
    }

    /**
     * Corpo da mudanca de Safe Mode; o motivo vem de {@code X-Reason}, como em todo comando (T55).
     */
    public record SafeModeChange(SafeModeStatus level, boolean confirm) {
    }
}
