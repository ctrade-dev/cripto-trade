package com.marmitt.application.spring.controller;

import com.marmitt.application.spring.web.ApiError;
import com.marmitt.core.domain.strategy.RemoteStrategyEvent;
import com.marmitt.application.spring.web.OperatorCommandInterceptor;
import com.marmitt.core.dto.strategy.request.RegisterRemoteStrategyRequest;
import com.marmitt.core.dto.strategy.request.UpdateRemoteStrategyRequest;
import com.marmitt.core.dto.strategy.response.RemoteStrategyView;
import com.marmitt.core.ports.inbound.strategy.ManageRemoteStrategiesPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Catalogo de estrategias remotas (T54). So traduz HTTP para o port; regras, idempotencia e codigos
 * de recusa sao do core. Protegido por {@code X-Admin-Key} como o resto de {@code /api/admin}.
 */
@RestController
@RequestMapping("/api/admin/strategies")
@Tag(name = "Strategy catalog", description = """
        Remote strategies the runners can use. A strategy is an independent app implementing
        docs/strategy-service/CONTRACT.md. Every change requires the X-Reason header, is recorded in the history and
        returns the resulting state; repeating a change already applied is a no-op. Refusals carry a stable
        `code` in ApiError: STRATEGY_NOT_FOUND (404), STRATEGY_DEFINITION_CONFLICT (409),
        STRATEGY_REF_IN_USE (409), INVALID_STRATEGY_DEFINITION (400), REASON_REQUIRED (400).""")
@ApiResponse(responseCode = "4XX", description = "Refused; see `code`",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
public class StrategyCatalogController {

    private final ManageRemoteStrategiesPort catalog;

    public StrategyCatalogController(ManageRemoteStrategiesPort catalog) {
        this.catalog = catalog;
    }

    @PostMapping
    @Operation(summary = "Register a remote strategy", description = """
            Registers and verifies on the spot via the service's /v2/meta. A service that is down does not
            block registration: the strategy stays verification=FAILED (see lastError) and is retried
            periodically; runners pointing to it stay on HOLD until it is usable=true. Registering the same
            definition again returns the existing entry; a different definition for the same strategyRef is
            STRATEGY_DEFINITION_CONFLICT (a new version needs a new strategyRef).""")
    public ResponseEntity<RemoteStrategyView> register(@RequestBody RegisterRemoteStrategyRequest request,
                                                       @RequestHeader(OperatorCommandInterceptor.REASON) String reason) {
        return ResponseEntity.ok(catalog.register(request, reason));
    }

    @GetMapping
    @Operation(summary = "List the catalog", description = "State of every remote strategy, with the runners using it.")
    public ResponseEntity<List<RemoteStrategyView>> list() {
        return ResponseEntity.ok(catalog.list());
    }

    @GetMapping("/{strategyRef}")
    @Operation(summary = "Get one strategy")
    public ResponseEntity<RemoteStrategyView> get(@PathVariable UUID strategyRef) {
        return ResponseEntity.ok(catalog.get(strategyRef));
    }

    @GetMapping("/{strategyRef}/events")
    @Operation(summary = "History of one strategy", description = """
            Oldest first: REGISTERED, ENABLED, DISABLED, ENDPOINT_CHANGED (operator changes, with reason) and
            VERIFIED, VERIFICATION_FAILED (verification results, with detail; repeated identical results are
            not recorded again).""")
    public ResponseEntity<List<RemoteStrategyEvent>> events(@PathVariable UUID strategyRef) {
        return ResponseEntity.ok(catalog.events(strategyRef));
    }

    @PatchMapping("/{strategyRef}")
    @Operation(summary = "Change baseUrl and/or token", description = """
            Null keeps the current value; an empty token removes it. The strategy leaves use and is verified
            again at the new endpoint.""")
    public ResponseEntity<RemoteStrategyView> update(@PathVariable UUID strategyRef,
                                                     @RequestBody UpdateRemoteStrategyRequest request,
                                                     @RequestHeader(OperatorCommandInterceptor.REASON) String reason) {
        return ResponseEntity.ok(catalog.update(strategyRef, request, reason));
    }

    @PostMapping("/{strategyRef}/enable")
    @Operation(summary = "Enable", description = "Verifies again before use.")
    public ResponseEntity<RemoteStrategyView> enable(@PathVariable UUID strategyRef,
                                                     @RequestHeader(OperatorCommandInterceptor.REASON) String reason) {
        return ResponseEntity.ok(catalog.enable(strategyRef, reason));
    }

    @PostMapping("/{strategyRef}/disable")
    @Operation(summary = "Disable", description = """
            Takes the strategy out of use. Runners pointing to it stay on HOLD (lot protection still applies);
            nothing is stopped. Disabling with ACTIVE runners raises a STRATEGY_DISABLED alert. There is no
            delete.""")
    public ResponseEntity<RemoteStrategyView> disable(@PathVariable UUID strategyRef,
                                                      @RequestHeader(OperatorCommandInterceptor.REASON) String reason) {
        return ResponseEntity.ok(catalog.disable(strategyRef, reason));
    }
}
