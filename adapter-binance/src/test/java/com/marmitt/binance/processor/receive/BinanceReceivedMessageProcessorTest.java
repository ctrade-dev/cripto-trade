package com.marmitt.binance.processor.receive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marmitt.core.dto.processing.ProcessingResult;
import com.marmitt.core.dto.websocket.MessageContext;
import com.marmitt.core.dto.websocket.data.MarketDataDto;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mensagens do socket de market data da Binance: eventos de mercado e respostas aos pedidos de assinatura. */
class BinanceReceivedMessageProcessorTest {

    private final BinanceReceivedMessageProcessor processor = new BinanceReceivedMessageProcessor(new ObjectMapper());

    private static MessageContext ctx() {
        return MessageContext.create("BINANCE", UUID.randomUUID());
    }

    /** T53 F26: a confirmacao de uma assinatura feita com a conexao aberta virava ERROR. */
    @Test
    void acceptedSubscriptionIsIgnoredNotAnError() {
        ProcessingResult<?> result = processor.processMessage("{\"result\":null,\"id\":1762802453378}", ctx());

        assertInstanceOf(ProcessingResult.Ignored.class, result);
        assertFalse(result.isError());
        assertEquals("stream-request-accepted", result.getErrorMessage().orElseThrow());
    }

    @Test
    void rejectedSubscriptionStaysAnError() {
        ProcessingResult<?> result = processor.processMessage(
                "{\"error\":{\"code\":2,\"msg\":\"Invalid request: unknown variant\"},\"id\":7}", ctx());

        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().orElseThrow().contains("code=2"));
    }

    @Test
    void bookTickerIsStillParsed() {
        ProcessingResult<?> result = processor.processMessage(
                "{\"u\":400900217,\"s\":\"BNBUSDT\",\"b\":\"25.35190000\",\"B\":\"31.21000000\","
                        + "\"a\":\"25.36520000\",\"A\":\"40.66000000\"}", ctx());

        assertTrue(result.isSuccess());
        assertInstanceOf(MarketDataDto.class, result.getData().orElseThrow());
    }

    @Test
    void messageWithoutEventTypeOrRequestIdIsStillAnError() {
        ProcessingResult<?> result = processor.processMessage("{\"foo\":1}", ctx());

        assertTrue(result.isError());
    }
}
