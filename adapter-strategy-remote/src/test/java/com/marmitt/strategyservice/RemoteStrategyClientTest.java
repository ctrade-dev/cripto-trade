package com.marmitt.strategyservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marmitt.core.domain.Symbol;
import com.marmitt.core.domain.strategy.StrategyEndpoint;
import com.marmitt.core.dto.strategy.PositionContext;
import com.marmitt.core.dto.strategy.StrategyContextDto;
import com.marmitt.core.dto.strategy.StrategyInputDto;
import com.marmitt.core.dto.strategy.StrategyOutputDto;
import com.marmitt.core.enums.TradingAction;
import com.marmitt.core.exceptions.StrategyUnavailableException;
import com.marmitt.core.exceptions.StrategyUnavailableException.Reason;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import com.marmitt.core.dto.strategy.OpenLotDto;
import com.marmitt.core.dto.strategy.PricePointDto;
import com.marmitt.core.ports.outbound.strategy.StrategyEvaluationPort;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T41: cliente do contrato — mapeamento, validacao, sem retry, decisao velha e sequence por runner.
 */
class RemoteStrategyClientTest {

    private static final UUID REF = UUID.fromString("5ea5e0a0-0000-4000-a000-000000000001");
    private static final StrategyEndpoint ENDPOINT = new StrategyEndpoint("http://strategy:8090/v2/", "token-1");
    private static final Instant TICK = Instant.parse("2026-10-19T12:00:00Z");
    private static final String BUY = """
            {"strategyName":"SMA","decision":"SHOULD_BUY","confidence":"0.80","quantity":"0.00100000",
             "limitPrice":null,"targetLotId":null,"targetTransactionId":null,"reasoning":"cross"}""";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FakeHttp http = new FakeHttp();

    @Test
    void validDecisionIsMappedToTheCore() {
        http.reply = request -> new StrategyServiceHttpPort.Response(200, BUY);

        StrategyOutputDto output = client(Duration.ofSeconds(1)).evaluate(REF, ENDPOINT, input(), context(UUID.randomUUID()));

        assertEquals(TradingAction.SHOULD_BUY, output.decision());
        assertEquals(0, new BigDecimal("0.001").compareTo(output.quantity()));
        assertNull(output.limitPrice());
        assertEquals("http://strategy:8090/v2/evaluate", http.calls.getFirst().url());
        assertEquals("Bearer token-1", http.calls.getFirst().headers().get("Authorization"));
    }

    @Test
    void requestCarriesTheWholeInteraction() throws Exception {
        http.reply = request -> new StrategyServiceHttpPort.Response(200, BUY);
        UUID runner = UUID.randomUUID();
        OpenLotDto lot = OpenLotDto.builder().lotId(UUID.randomUUID()).quantity(BigDecimal.ONE)
                .availableQuantity(BigDecimal.ONE).entryPrice(new BigDecimal("64000"))
                .currentPnlPercent(BigDecimal.ONE).openedAt(TICK).peakPrice(new BigDecimal("65500.25")).build();
        StrategyInputDto withHistory = input().withPriceHistory(List.of(
                new PricePointDto(new BigDecimal("64999.50"), TICK.minusSeconds(1)),
                new PricePointDto(new BigDecimal("65000.50"), TICK)));
        StrategyContextDto base = context(runner);
        StrategyContextDto withLot = StrategyContextDto.builder().runnerId(runner).portfolioId(base.portfolioId())
                .symbol(base.symbol()).positionContext(base.positionContext()).openLots(List.of(lot))
                .pendingOrders(List.of()).totalCapital(base.totalCapital()).availableCapital(base.availableCapital())
                .maxOperationAmount(base.maxOperationAmount()).minOperationAmount(base.minOperationAmount())
                .maxOpenPositions(1).currentOpenPositions(1).realizedPnl(base.realizedPnl())
                .unrealizedPnl(base.unrealizedPnl()).build();

        client(Duration.ofSeconds(1)).evaluate(REF, ENDPOINT, withHistory, withLot);

        JsonNode body = objectMapper.readTree(http.calls.getFirst().body());
        assertEquals(runner + ":" + TICK.toEpochMilli(), body.path("tickId").asText());
        assertTrue(body.path("sequence").isMissingNode(), "sequence saiu no contrato 2.0");
        assertEquals("65000.50", body.path("input").path("currentPrice").asText(), "Decimal trafega como string");
        JsonNode history = body.path("input").path("priceHistory");
        assertEquals(2, history.size());
        assertEquals("64999.50", history.get(0).path("price").asText());
        assertEquals(TICK.toString(), history.get(1).path("timestamp").asText());
        assertEquals("65500.25", body.path("context").path("openLots").get(0).path("peakPrice").asText());
    }

    @Test
    void nullMetaIsAnInvalidResponse() {
        RemoteStrategyClient client = client(Duration.ofSeconds(1));
        for (String body : new String[]{"null", "[null]"}) {
            http.reply = request -> new StrategyServiceHttpPort.Response(200, body);
            StrategyUnavailableException e = assertThrows(StrategyUnavailableException.class, () -> client.describe(REF, ENDPOINT));
            assertEquals(Reason.INVALID_RESPONSE, e.reason(), body);
        }
    }

    @Test
    void failuresBecomeStrategyUnavailableWithoutRetry() {
        RemoteStrategyClient client = client(Duration.ofSeconds(1));

        http.reply = request -> new StrategyServiceHttpPort.Response(503, "{}");
        assertReason(Reason.ERROR_RESPONSE, client);
        http.reply = request -> { throw new UncheckedIo(new IOException("timeout")); };
        assertReason(Reason.UNREACHABLE, client);
        http.reply = request -> new StrategyServiceHttpPort.Response(200, "not json");
        assertReason(Reason.INVALID_RESPONSE, client);
        http.reply = request -> new StrategyServiceHttpPort.Response(200, BUY.replace("\"0.00100000\"", "\"-1\""));
        assertReason(Reason.INVALID_RESPONSE, client);

        assertEquals(4, http.calls.size(), "Uma chamada por avaliacao: sem retry");
    }

    @Test
    void numericDecimalsAndBadTimestampAreOutsideTheContract() {
        RemoteStrategyClient client = client(Duration.ofSeconds(1));

        // Decimal como numero JSON (float) nao pode virar valor financeiro.
        http.reply = request -> new StrategyServiceHttpPort.Response(200, BUY.replace("\"0.00100000\"", "0.001"));
        assertReason(Reason.INVALID_RESPONSE, client);
        http.reply = request -> new StrategyServiceHttpPort.Response(200, BUY.replace("\"0.80\"", "0.8"));
        assertReason(Reason.INVALID_RESPONSE, client);
        http.reply = request -> new StrategyServiceHttpPort.Response(200,
                BUY.replace("\"reasoning\":\"cross\"", "\"reasoning\":\"cross\",\"timestamp\":\"not-a-time\""));
        assertReason(Reason.INVALID_RESPONSE, client);
    }

    @Test
    void decisionOlderThanTheLimitIsDiscarded() {
        http.reply = request -> new StrategyServiceHttpPort.Response(200, BUY);
        RemoteStrategyClient client = new RemoteStrategyClient(http, objectMapper,
                Duration.ofMillis(500), Clock.fixed(TICK.plusMillis(800), ZoneOffset.UTC));

        StrategyUnavailableException e = assertThrows(StrategyUnavailableException.class,
                () -> client.evaluate(REF, ENDPOINT, input(), context(UUID.randomUUID())));
        assertEquals(Reason.STALE_DECISION, e.reason());
    }

    @Test
    void describeReadsTheServedIdentityFromMeta() {
        http.reply = request -> new StrategyServiceHttpPort.Response(200,
                "[{\"strategyId\":\"" + REF + "\",\"name\":\"SMA\",\"version\":\"1.0.0\",\"contractVersion\":\"2.0.0\","
                        + "\"requirements\":{\"priceHistory\":{\"ticks\":20}}}]");

        var identity = client(Duration.ofSeconds(1)).describe(REF, ENDPOINT);

        assertEquals(REF, identity.strategyId());
        assertEquals("2.0.0", identity.contractVersion());
        assertEquals(20, identity.priceHistoryTicks());
        assertEquals("http://strategy:8090/v2/meta?strategyRef=" + REF, http.calls.getFirst().url());
    }

    @Test
    void emptyRequirementsMeanNoPriceHistory() {
        assertEquals(0, describeWith("{}").priceHistoryTicks());
    }

    @Test
    void metaOutsideTheContractIsAnInvalidResponse() {
        for (String requirements : List.of("null", "{\"candles\":{\"limit\":500}}",
                "{\"priceHistory\":{}}", "{\"priceHistory\":{\"ticks\":\"20\"}}",
                "{\"priceHistory\":{\"ticks\":1.5}}")) {
            StrategyUnavailableException e = assertThrows(StrategyUnavailableException.class,
                    () -> describeWith(requirements), requirements);
            assertEquals(Reason.INVALID_RESPONSE, e.reason(), requirements);
        }
    }

    private StrategyEvaluationPort.RemoteStrategyIdentity describeWith(String requirements) {
        http.reply = request -> new StrategyServiceHttpPort.Response(200,
                "[{\"strategyId\":\"" + REF + "\",\"name\":\"SMA\",\"version\":\"1.0.0\","
                        + "\"contractVersion\":\"2.0.0\",\"requirements\":" + requirements + "}]");
        return client(Duration.ofSeconds(1)).describe(REF, ENDPOINT);
    }

    private void assertReason(Reason expected, RemoteStrategyClient client) {
        StrategyUnavailableException e = assertThrows(StrategyUnavailableException.class,
                () -> client.evaluate(REF, ENDPOINT, input(), context(UUID.randomUUID())));
        assertEquals(expected, e.reason());
    }

    private RemoteStrategyClient client(Duration maxAge) {
        return new RemoteStrategyClient(http, objectMapper, maxAge,
                Clock.fixed(TICK.plusMillis(100), ZoneOffset.UTC));
    }

    private static StrategyInputDto input() {
        BigDecimal price = new BigDecimal("65000.50");
        return new StrategyInputDto(Symbol.of("BTCUSDT"), price, null, price, price, BigDecimal.ONE,
                price, price, TICK, Map.of(), List.of());
    }

    private static StrategyContextDto context(UUID runnerId) {
        return StrategyContextDto.builder()
                .runnerId(runnerId)
                .portfolioId(UUID.randomUUID())
                .symbol(Symbol.of("BTCUSDT"))
                .positionContext(PositionContext.empty("BTCUSDT"))
                .openLots(List.of())
                .pendingOrders(List.of())
                .totalCapital(new BigDecimal("10000"))
                .availableCapital(new BigDecimal("10000"))
                .maxOperationAmount(new BigDecimal("2000"))
                .minOperationAmount(new BigDecimal("10"))
                .maxOpenPositions(1)
                .currentOpenPositions(0)
                .realizedPnl(BigDecimal.ZERO)
                .unrealizedPnl(BigDecimal.ZERO)
                .build();
    }

    private record Call(String url, Map<String, String> headers, String body) {
    }

    private static final class UncheckedIo extends RuntimeException {
        private UncheckedIo(IOException cause) {
            super(cause);
        }
    }

    private static final class FakeHttp implements StrategyServiceHttpPort {
        private final List<Call> calls = new ArrayList<>();
        private Function<Call, Response> reply;

        @Override
        public Response get(String url, Map<String, String> headers) throws IOException {
            return answer(new Call(url, headers, null));
        }

        @Override
        public Response postJson(String url, Map<String, String> headers, String body) throws IOException {
            return answer(new Call(url, headers, body));
        }

        private Response answer(Call call) throws IOException {
            calls.add(call);
            try {
                return reply.apply(call);
            } catch (UncheckedIo e) {
                throw (IOException) e.getCause();
            }
        }
    }
}
