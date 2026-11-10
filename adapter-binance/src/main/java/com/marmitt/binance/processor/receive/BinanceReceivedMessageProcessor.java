package com.marmitt.binance.processor.receive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marmitt.core.dto.processing.ProcessingResult;
import com.marmitt.core.dto.websocket.MessageContext;
import com.marmitt.core.dto.websocket.data.ProcessorResponse;
import com.marmitt.core.ports.outbound.exchange.adapter.ReceivedMessageProcessorPort;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class BinanceReceivedMessageProcessor implements ReceivedMessageProcessorPort {

    private final ObjectMapper objectMapper;
    private final Map<String, BinanceEventProcessor<?>> processorsByEventType;
    private final BookTickerProcessor bookTickerProcessor;

    public BinanceReceivedMessageProcessor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        List<BinanceEventProcessor<?>> processors = List.of(
                new TickerProcessor(objectMapper),
                new TradeProcessor(objectMapper)
        );

        this.processorsByEventType = processors.stream()
                .filter(p -> p.eventType() != null)
                .collect(Collectors.toMap(BinanceEventProcessor::eventType, Function.identity()));

        this.bookTickerProcessor = new BookTickerProcessor(objectMapper);
    }

    @Override
    public ProcessingResult<? extends ProcessorResponse> processMessage(String rawMessage, MessageContext context) {
        String correlationId = context.correlationId().toString();
        try {
            JsonNode root = objectMapper.readTree(rawMessage);
            if (isRequestResponse(root)) {
                return handleRequestResponse(root, correlationId, rawMessage);
            }
            JsonNode data = unwrapCombinedStream(root);

            JsonNode eventTypeNode = data.get("e");

            if (eventTypeNode == null) {
                if (isBookTicker(data)) {
                    return bookTickerProcessor.process(data, context);
                }
                log.debug("Unrecognized Binance message with no event type: correlationId={}", correlationId);
                return ProcessingResult.error(correlationId, "Unrecognized message: no event type field", rawMessage);
            }

            String eventType = eventTypeNode.asText();
            BinanceEventProcessor<?> processor = processorsByEventType.get(eventType);

            if (processor == null) {
                log.debug("No processor for Binance event type '{}': correlationId={}", eventType, correlationId);
                return ProcessingResult.error(correlationId,
                        "No processor registered for event type: " + eventType, rawMessage);
            }

            return processor.process(data, context);

        } catch (Exception e) {
            log.error("Failed to parse Binance message: correlationId={}", correlationId, e);
            return ProcessingResult.error(correlationId, "Failed to parse message: " + e.getMessage(), rawMessage, e);
        }
    }

    /**
     * Resposta a um pedido do socket de market data (SUBSCRIBE, UNSUBSCRIBE): {"result":null,"id":N} quando
     * aceito, {"error":{"code":..,"msg":..},"id":N} quando recusado. Nao e evento de mercado (T53 F26).
     */
    private static boolean isRequestResponse(JsonNode root) {
        return root.has("id") && (root.has("result") || root.has("error"));
    }

    private ProcessingResult<? extends ProcessorResponse> handleRequestResponse(
            JsonNode root, String correlationId, String rawMessage) {
        String requestId = root.path("id").asText();
        if (root.has("error")) {
            JsonNode error = root.get("error");
            return ProcessingResult.error(correlationId, "Market stream request rejected by Binance: id=" + requestId
                    + " code=" + error.path("code").asText() + " msg=" + error.path("msg").asText(), rawMessage);
        }
        log.debug("Market stream request accepted by Binance: id={} correlationId={}", requestId, correlationId);
        return ProcessingResult.ignored(correlationId, "stream-request-accepted");
    }

    /**
     * Combined stream events are wrapped as {"stream":"btcusdt@ticker","data":{...}}.
     * Returns the inner "data" node when present, otherwise returns the root as-is.
     */
    private JsonNode unwrapCombinedStream(JsonNode root) {
        if (root.has("stream") && root.has("data")) {
            return root.get("data");
        }
        return root;
    }

    /**
     * bookTicker events have no "e" field but always carry "u" (updateId),
     * "s" (symbol), "b" (bid), and "a" (ask).
     */
    private boolean isBookTicker(JsonNode data) {
        return data.has("u") && data.has("s") && data.has("b") && data.has("a");
    }
}
