package com.marmitt.binance.rest;

import com.marmitt.binance.request.BinanceOrderTypeRules;
import com.marmitt.binance.auth.BinanceRequestSigner;
import com.marmitt.core.dto.websocket.request.SendCancelOrderRequest;
import com.marmitt.core.dto.websocket.request.SendOrderRequest;
import com.marmitt.core.enums.OrderType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

public class BinanceRestRequestBuilder {

    private static final String ORDER_PATH       = "/api/v3/order";
    private static final String OPEN_ORDERS_PATH = "/api/v3/openOrders";
    private static final String ACCOUNT_PATH     = "/api/v3/account";
    private static final String MY_TRADES_PATH   = "/api/v3/myTrades";
    private static final String KLINES_PATH      = "/api/v3/klines";

    private final String restBaseUrl;
    private final BinanceRequestSigner signer;

    public BinanceRestRequestBuilder(String restBaseUrl, BinanceRequestSigner signer) {
        this.restBaseUrl = restBaseUrl;
        this.signer = signer;
    }

    public RestRequest buildQueryOrderByClientOrderId(String symbol, String clientOrderId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("origClientOrderId", clientOrderId);
        return get(ORDER_PATH, params);
    }

    public RestRequest buildQueryOrderByExchangeOrderId(String symbol, String exchangeOrderId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("orderId", exchangeOrderId);
        return get(ORDER_PATH, params);
    }

    public RestRequest buildSubmitOrder(SendOrderRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", request.getSymbol());
        params.put("side", request.getOrderSide().name());
        params.put("type", toRestOrderType(request.getOrderType()));
        params.put("quantity", request.getQuantity().toPlainString());
        params.put("newClientOrderId", request.getClientOrderId());
        if (BinanceOrderTypeRules.hasLimitPrice(request.getOrderType())) {
            params.put("price", request.getPrice().toPlainString());
            params.put("timeInForce", "GTC");
        }
        return post(ORDER_PATH, params);
    }

    public RestRequest buildCancelOrder(SendCancelOrderRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", request.getSymbol());
        params.put("origClientOrderId", request.getClientOrderId());
        return delete(ORDER_PATH, params);
    }

    public RestRequest buildListOpenOrdersBySymbol(String symbol) {
        return get(OPEN_ORDERS_PATH, Map.of("symbol", symbol));
    }

    public RestRequest buildListAllOpenOrders() {
        return get(OPEN_ORDERS_PATH, Map.of());
    }

    public RestRequest buildAccountSnapshot() {
        return get(ACCOUNT_PATH, Map.of());
    }

    public RestRequest buildMyTrades(String symbol, long startTime, long endTime) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("startTime", String.valueOf(startTime));
        params.put("endTime", String.valueOf(endTime));
        params.put("limit", "1000");
        return get(MY_TRADES_PATH, params);
    }

    public RestRequest buildMyTradesFromId(String symbol, long fromId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("fromId", String.valueOf(fromId));
        params.put("limit", "1000");
        return get(MY_TRADES_PATH, params);
    }

    /** Candles (T72): dado publico, sem assinatura nem chave. {@code endTime} filtra pela abertura do candle. */
    public RestRequest buildKlines(String symbol, String interval, long endTime, int limit) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("interval", interval);
        params.put("endTime", String.valueOf(endTime));
        params.put("limit", String.valueOf(limit));
        StringJoiner query = new StringJoiner("&");
        params.forEach((k, v) -> query.add(k + "=" + v));
        return new RestRequest("GET", restBaseUrl + KLINES_PATH + "?" + query, Map.of(), null);
    }

    private RestRequest get(String path, Map<String, String> params) {
        String query = buildSignedQuery(params);
        return new RestRequest("GET", restBaseUrl + path + "?" + query, apiKeyHeader(), null);
    }

    private RestRequest post(String path, Map<String, String> params) {
        String body = buildSignedQuery(params);
        return new RestRequest("POST", restBaseUrl + path, apiKeyHeader(), body);
    }

    private RestRequest delete(String path, Map<String, String> params) {
        String query = buildSignedQuery(params);
        return new RestRequest("DELETE", restBaseUrl + path + "?" + query, apiKeyHeader(), null);
    }

    private String buildSignedQuery(Map<String, String> params) {
        StringJoiner sj = new StringJoiner("&");
        params.forEach((k, v) -> sj.add(k + "=" + v));
        sj.add("timestamp=" + System.currentTimeMillis());
        return signer.signQueryString(sj.toString());
    }

    private Map<String, String> apiKeyHeader() {
        return Map.of("X-MBX-APIKEY", signer.getApiKey());
    }

    private String toRestOrderType(OrderType orderType) {
        return switch (orderType) {
            case MARKET            -> "MARKET";
            case LIMIT             -> "LIMIT";
            case STOP_LOSS         -> "STOP_LOSS";
            case STOP_LOSS_LIMIT   -> "STOP_LOSS_LIMIT";
            case TAKE_PROFIT       -> "TAKE_PROFIT";
            case TAKE_PROFIT_LIMIT -> "TAKE_PROFIT_LIMIT";
        };
    }
}
