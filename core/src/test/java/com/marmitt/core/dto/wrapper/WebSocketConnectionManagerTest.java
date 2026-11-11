package com.marmitt.core.dto.wrapper;

import com.marmitt.core.dto.common.CurrencyPair;
import com.marmitt.core.dto.websocket.request.StreamSubscriptionRequest;
import com.marmitt.core.enums.StreamAction;
import com.marmitt.core.enums.StreamType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketConnectionManagerTest {

    private static final String EXCHANGE = "BINANCE";

    @Test
    void noSubscriptionWhenNothingWasRequested() {
        assertTrue(WebSocketConnectionManager.forExchange(EXCHANGE).accumulatedSubscription().isEmpty());
    }

    /** T53 F28: a abertura com um simbolo e as assinaturas seguintes somam; o ultimo pedido sozinho nao basta. */
    @Test
    void subscriptionAccumulatesEverySubscribeInOrder() {
        WebSocketConnectionManager manager = WebSocketConnectionManager.forExchange(EXCHANGE);
        manager.addRequestToHistory(request(StreamAction.SUBSCRIBE, "BTC"));
        manager.addRequestToHistory(request(StreamAction.SUBSCRIBE, "ETH", "BNB"));
        manager.addRequestToHistory(request(StreamAction.SUBSCRIBE, "BTC", "SOL"));

        StreamSubscriptionRequest subscription = manager.accumulatedSubscription().orElseThrow();

        assertEquals(StreamAction.SUBSCRIBE, subscription.getStreamAction());
        assertEquals(EXCHANGE, subscription.getExchangeName());
        assertEquals(List.of(pair("BTC"), pair("ETH"), pair("BNB"), pair("SOL")), subscription.getCurrencyPairs());
    }

    @Test
    void unsubscribeRemovesUntilSubscribedAgain() {
        WebSocketConnectionManager manager = WebSocketConnectionManager.forExchange(EXCHANGE);
        manager.addRequestToHistory(request(StreamAction.SUBSCRIBE, "BTC", "ETH"));
        manager.addRequestToHistory(request(StreamAction.UNSUBSCRIBE, "ETH"));

        assertEquals(List.of(pair("BTC")), manager.accumulatedSubscription().orElseThrow().getCurrencyPairs());

        manager.addRequestToHistory(request(StreamAction.SUBSCRIBE, "ETH"));

        assertEquals(List.of(pair("BTC"), pair("ETH")), manager.accumulatedSubscription().orElseThrow().getCurrencyPairs());
    }

    private static StreamSubscriptionRequest request(StreamAction action, String... bases) {
        return new StreamSubscriptionRequest(EXCHANGE, java.util.Arrays.stream(bases).map(WebSocketConnectionManagerTest::pair).toList(), action);
    }

    private static CurrencyPair pair(String base) {
        return new CurrencyPair(base, "USDT", StreamType.TICKER);
    }
}
