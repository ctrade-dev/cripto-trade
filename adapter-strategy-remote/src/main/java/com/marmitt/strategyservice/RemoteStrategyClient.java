package com.marmitt.strategyservice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.marmitt.core.domain.strategy.StrategyEndpoint;
import com.marmitt.core.dto.strategy.StrategyContextDto;
import com.marmitt.core.dto.strategy.StrategyInputDto;
import com.marmitt.core.dto.strategy.StrategyOutputDto;
import com.marmitt.core.exceptions.StrategyUnavailableException;
import com.marmitt.core.exceptions.StrategyUnavailableException.Reason;
import com.marmitt.core.ports.outbound.strategy.StrategyEvaluationPort;
import com.marmitt.strategyservice.wire.DecisionValidator;
import com.marmitt.strategyservice.wire.StrategyContractMapper;
import com.marmitt.strategyservice.wire.WireTypes;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cliente do contrato do strategy service (T41, contrato 2.0). O endereco e o token chegam do catalogo a
 * cada chamada (T54); o cliente nao guarda lista de estrategias.
 *
 * <ul>
 *   <li><b>Sem retry</b> (T40): qualquer falha vira {@link StrategyUnavailableException} e o tick é
 *       HOLD; o próximo tick segue normalmente.</li>
 *   <li><b>{@code tickId}</b> = runner + instante do tick, so para rastreio: a app e stateless (T69).</li>
 *   <li><b>Resposta validada</b> contra o contrato antes de virar decisão ({@link DecisionValidator}).</li>
 *   <li><b>Decisão velha</b> ({@code agora − input.timestamp > maxDecisionAge}) é descartada.</li>
 * </ul>
 * O timeout rígido da chamada é do {@link StrategyServiceHttpPort}.
 */
public class RemoteStrategyClient implements StrategyEvaluationPort {

    private final StrategyServiceHttpPort http;
    private final ObjectMapper objectMapper;
    private final Duration maxDecisionAge;
    private final Clock clock;

    public RemoteStrategyClient(StrategyServiceHttpPort http, ObjectMapper objectMapper, Duration maxDecisionAge,
                                Clock clock) {
        this.http = http;
        this.objectMapper = strictTextual(objectMapper);
        this.maxDecisionAge = maxDecisionAge;
        this.clock = clock;
    }

    @Override
    public StrategyOutputDto evaluate(UUID strategyRef, StrategyEndpoint endpoint, StrategyInputDto input,
                                      StrategyContextDto context) {
        String tickId = context.runnerId() + ":" + input.timestamp().toEpochMilli();
        String body = write(StrategyContractMapper.toRequest(strategyRef, tickId, input, context));

        StrategyServiceHttpPort.Response response = call(() -> http.postJson(endpoint.baseUrl() + "/evaluate",
                headers(endpoint), body), strategyRef);
        WireTypes.Decision decision = read(response.body(), WireTypes.Decision.class, strategyRef);
        List<String> violations = DecisionValidator.violations(decision);
        if (!violations.isEmpty()) {
            throw new StrategyUnavailableException(Reason.INVALID_RESPONSE,
                    "decision outside the contract for " + strategyRef + ": " + violations);
        }

        Duration age = Duration.between(input.timestamp(), Instant.now(clock));
        if (age.compareTo(maxDecisionAge) > 0) {
            throw new StrategyUnavailableException(Reason.STALE_DECISION,
                    "decision for " + strategyRef + " arrived " + age.toMillis() + "ms after the tick (max "
                            + maxDecisionAge.toMillis() + "ms)");
        }
        try {
            return StrategyContractMapper.toCore(decision);
        } catch (RuntimeException e) {
            throw new StrategyUnavailableException(Reason.INVALID_RESPONSE,
                    "decision for " + strategyRef + " cannot be mapped: " + e.getMessage(), e);
        }
    }

    /**
     * Copia do mapper que recusa numero/booleano JSON onde o contrato exige string (decimal como
     * string, uuid, timestamp): sem isso o Jackson converteria {@code 0.1} em {@code "0.1"} e um valor
     * financeiro vindo de float passaria pela validacao.
     */
    private static ObjectMapper strictTextual(ObjectMapper source) {
        ObjectMapper strict = source.copy();
        strict.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return strict;
    }

    @Override
    public RemoteStrategyIdentity describe(UUID strategyRef, StrategyEndpoint endpoint) {
        StrategyServiceHttpPort.Response response = call(() -> http.get(
                endpoint.baseUrl() + "/meta?strategyRef=" + strategyRef, headers(endpoint)), strategyRef);
        WireTypes.Meta[] served = read(response.body(), WireTypes.Meta[].class, strategyRef);
        if (served == null) {
            throw new StrategyUnavailableException(Reason.INVALID_RESPONSE, "empty /meta response for " + strategyRef);
        }
        return Arrays.stream(served)
                .filter(meta -> meta != null && strategyRef.toString().equalsIgnoreCase(meta.strategyId()))
                .findFirst()
                .map(meta -> identity(meta, strategyRef))
                .orElseThrow(() -> new StrategyUnavailableException(Reason.INVALID_RESPONSE,
                        "service at " + endpoint.baseUrl() + " does not serve " + strategyRef));
    }

    private static RemoteStrategyIdentity identity(WireTypes.Meta meta, UUID strategyRef) {
        try {
            return new RemoteStrategyIdentity(UUID.fromString(meta.strategyId()), meta.name(), meta.version(),
                    meta.contractVersion(), StrategyContractMapper.priceHistoryTicks(meta.requirements()));
        } catch (IllegalArgumentException e) {
            throw new StrategyUnavailableException(Reason.INVALID_RESPONSE,
                    "/meta outside the contract for " + strategyRef + ": " + e.getMessage(), e);
        }
    }

    private static Map<String, String> headers(StrategyEndpoint endpoint) {
        if (!endpoint.hasToken()) {
            return Map.of();
        }
        return Map.of("Authorization", "Bearer " + endpoint.token());
    }

    private StrategyServiceHttpPort.Response call(HttpCall call, UUID strategyRef) {
        StrategyServiceHttpPort.Response response;
        try {
            response = call.execute();
        } catch (IOException e) {
            throw new StrategyUnavailableException(Reason.UNREACHABLE,
                    "strategy service unreachable for " + strategyRef + ": " + e.getMessage(), e);
        }
        if (!response.isSuccessful()) {
            throw new StrategyUnavailableException(Reason.ERROR_RESPONSE,
                    "strategy service answered " + response.statusCode() + " for " + strategyRef);
        }
        return response;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize strategy request", e);
        }
    }

    private <T> T read(String body, Class<T> type, UUID strategyRef) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new StrategyUnavailableException(Reason.INVALID_RESPONSE,
                    "unreadable response for " + strategyRef + ": " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface HttpCall {
        StrategyServiceHttpPort.Response execute() throws IOException;
    }
}
