package com.marmitt.binance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marmitt.binance.auth.BinanceCredentials;
import com.marmitt.binance.auth.BinanceRequestSigner;
import com.marmitt.binance.boot.BinanceBootReadinessChecker;
import com.marmitt.binance.filters.BinanceOrderNormalizer;
import com.marmitt.binance.filters.OrderFilterValidator;
import com.marmitt.binance.filters.SymbolFilterCache;
import com.marmitt.binance.processor.receive.BinanceReceivedMessageProcessor;
import com.marmitt.binance.processor.send.BinanceSenderMessageProcessor;
import com.marmitt.binance.rest.BinanceAccountMapper;
import com.marmitt.binance.rest.BinanceHttpErrors;
import com.marmitt.binance.rest.BinanceOrderMapper;
import com.marmitt.binance.rest.BinanceRestRequestBuilder;
import com.marmitt.binance.rest.RestRequest;
import com.marmitt.core.dto.exchange.boot.ExchangeBootReadiness;
import com.marmitt.core.dto.processing.ProcessingResult;
import com.marmitt.core.dto.websocket.MessageContext;
import com.marmitt.core.dto.websocket.data.AccountDataDto;
import com.marmitt.core.dto.websocket.data.OrderDataDto;
import com.marmitt.core.dto.websocket.data.ProcessorResponse;
import com.marmitt.core.dto.websocket.request.MessageRequest;
import com.marmitt.core.dto.websocket.request.SendCancelOrderRequest;
import com.marmitt.core.dto.websocket.request.SendOrderRequest;
import com.marmitt.core.dto.websocket.request.StreamSubscriptionRequest;
import com.marmitt.core.exceptions.ExchangeQueryException;
import com.marmitt.core.ports.outbound.exchange.rest.ExchangeAccountQueryPort;
import com.marmitt.core.ports.outbound.exchange.rest.ExchangeBootReadinessPort;
import com.marmitt.core.ports.outbound.exchange.rest.ExchangeOrderExecutionPort;
import com.marmitt.core.ports.outbound.exchange.rest.ExchangeOrderQueryPort;
import com.marmitt.core.ports.outbound.exchange.streaming.ExchangeStreamingPort;
import com.marmitt.binance.rest.HttpClientPort;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class BinanceMarketStreamAdapter implements
        ExchangeStreamingPort,
        ExchangeOrderExecutionPort,
        ExchangeOrderQueryPort,
        ExchangeAccountQueryPort,
        ExchangeBootReadinessPort {

    private static final int BINANCE_ORDER_NOT_FOUND = -2013;

    private final BinanceReceivedMessageProcessor receivedMessageProcessor;
    private final BinanceSenderMessageProcessor senderMessageProcessor;
    private final BinanceUrlBuilder urlBuilder;
    private final BinanceBootReadinessChecker bootReadinessChecker;
    private final BinanceRestRequestBuilder requestBuilder;
    private final BinanceOrderMapper orderMapper;
    private final BinanceAccountMapper accountMapper;
    private final HttpClientPort httpClient;
    private final BinanceOrderNormalizer orderNormalizer;
    private final OrderFilterValidator filterValidator;
    private final ObjectMapper objectMapper;

    public BinanceMarketStreamAdapter(ObjectMapper objectMapper,
                                      BinanceConnectionConfig config,
                                      HttpClientPort httpClient,
                                      SymbolFilterCache filterCache) {
        var apiConfig          = new BinanceApiConfig(config.wsBaseUrl(), config.restBaseUrl());
        var credentials        = new BinanceCredentials(config.apiKey(), config.apiSecret());
        var signer             = new BinanceRequestSigner(credentials);
        var binanceUrlBuilder  = new BinanceUrlBuilder(apiConfig);
        var restRequestBuilder = new BinanceRestRequestBuilder(config.restBaseUrl(), signer);
        this.urlBuilder               = binanceUrlBuilder;
        this.senderMessageProcessor   = new BinanceSenderMessageProcessor(objectMapper, signer, binanceUrlBuilder);
        this.receivedMessageProcessor = new BinanceReceivedMessageProcessor(objectMapper);
        this.requestBuilder           = restRequestBuilder;
        this.httpClient               = httpClient;
        this.orderMapper              = new BinanceOrderMapper(objectMapper);
        this.accountMapper            = new BinanceAccountMapper(objectMapper);
        this.orderNormalizer          = new BinanceOrderNormalizer(filterCache);
        this.filterValidator          = new OrderFilterValidator(filterCache);
        this.objectMapper             = objectMapper;
        this.bootReadinessChecker     = new BinanceBootReadinessChecker(
                config.restBaseUrl(), restRequestBuilder, httpClient, objectMapper);
    }

    @Override
    public String getExchangeName() {
        return "BINANCE";
    }

    @Override
    public boolean requiresPostConnection() {
        return false;
    }

    @Override
    public String buildConnectionUrl(StreamSubscriptionRequest parameters, String exchangeName) {
        return urlBuilder.buildConnectionUrl(parameters);
    }

    @Override
    public String formatMessage(MessageRequest request) {
        // Normaliza e valida ordens manuais (WS) que não passam pelo fluxo de runner.
        // O fluxo de runner já normaliza no core; aqui a normalização é idempotente.
        if (request instanceof SendOrderRequest orderRequest) {
            request = filterValidator.validate(normalizeForDispatch(orderRequest));
        }
        return senderMessageProcessor.execute(request);
    }

    @Override
    public ProcessingResult<? extends ProcessorResponse> processMessage(String rawMessage, MessageContext context) {
        return receivedMessageProcessor.processMessage(rawMessage, context);
    }

    /**
     * Alinha quantidade/preço de uma ordem manual ao filtro do símbolo antes do dispatch,
     * reconstruindo a request imutável. Para o fluxo de runner os valores já chegam alinhados
     * (normalizados no core), tornando esta etapa idempotente.
     */
    private SendOrderRequest normalizeForDispatch(SendOrderRequest request) {
        BigDecimal quantity = orderNormalizer.normalizeQuantity(request.getSymbol(), request.getQuantity());
        BigDecimal price = orderNormalizer.normalizePrice(request.getSymbol(), request.getOrderSide(), request.getPrice());
        if (quantity.compareTo(request.getQuantity()) == 0
                && (price == null ? request.getPrice() == null : price.compareTo(request.getPrice()) == 0)) {
            return request;
        }
        return new SendOrderRequest(
                request.getExchangeName(),
                request.getSymbol(),
                quantity,
                price,
                request.getOrderType(),
                request.getOrderSide(),
                request.getClientOrderId());
    }

    // --- ExchangeOrderExecutionPort ---

    @Override
    public OrderDataDto submitOrder(SendOrderRequest request) {
        SendOrderRequest validated = filterValidator.validate(normalizeForDispatch(request));
        RestRequest req = requestBuilder.buildSubmitOrder(validated);
        try {
            HttpClientPort.HttpResponse response = httpClient.postForm(req.url(), req.headers(), req.body());
            if (response.isSuccessful()) {
                return orderMapper.fromJson(response.body());
            }
            throw queryException(response, "Order submission");
        } catch (IOException e) {
            throw new ExchangeQueryException("BINANCE", ExchangeQueryException.ErrorType.TEMPORARY,
                    "Order submission failed: " + e.getMessage(), e);
        }
    }

    @Override
    public OrderDataDto cancelOrder(SendCancelOrderRequest request) {
        RestRequest req = requestBuilder.buildCancelOrder(request);
        try {
            HttpClientPort.HttpResponse response = httpClient.delete(req.url(), req.headers());
            if (response.isSuccessful()) {
                return orderMapper.fromJson(response.body());
            }
            throw queryException(response, "Order cancellation");
        } catch (IOException e) {
            throw new ExchangeQueryException("BINANCE", ExchangeQueryException.ErrorType.TEMPORARY,
                    "Order cancellation failed: " + e.getMessage(), e);
        }
    }

    // --- ExchangeOrderQueryPort ---

    @Override
    public Optional<OrderDataDto> queryOrderByClientOrderId(String symbol, String clientOrderId) {
        RestRequest req = requestBuilder.buildQueryOrderByClientOrderId(symbol, clientOrderId);
        return querySingleOrder(req);
    }

    @Override
    public Optional<OrderDataDto> queryOrderByExchangeOrderId(String symbol, String exchangeOrderId) {
        RestRequest req = requestBuilder.buildQueryOrderByExchangeOrderId(symbol, exchangeOrderId);
        return querySingleOrder(req);
    }

    @Override
    public List<OrderDataDto> listOpenOrdersBySymbol(String symbol) {
        RestRequest req = requestBuilder.buildListOpenOrdersBySymbol(symbol);
        return queryOrderList(req);
    }

    @Override
    public List<OrderDataDto> listAllOpenOrders() {
        RestRequest req = requestBuilder.buildListAllOpenOrders();
        return queryOrderList(req);
    }

    // --- ExchangeAccountQueryPort ---

    @Override
    public AccountDataDto queryAccountSnapshot() {
        RestRequest req = requestBuilder.buildAccountSnapshot();
        try {
            HttpClientPort.HttpResponse response = httpClient.get(req.url(), req.headers());
            if (response.isSuccessful()) {
                return accountMapper.fromJson(response.body());
            }
            throw queryException(response, "Account snapshot");
        } catch (IOException e) {
            throw new ExchangeQueryException("BINANCE", ExchangeQueryException.ErrorType.TEMPORARY,
                    "Account snapshot failed: " + e.getMessage(), e);
        }
    }

    // --- ExchangeBootReadinessPort ---

    @Override
    public ExchangeBootReadiness checkBootReadiness() {
        try {
            return CompletableFuture.supplyAsync(bootReadinessChecker::check)
                    .get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return ExchangeBootReadiness.notReady("BINANCE", "CONNECTIVITY_FAILURE",
                    "Boot readiness check timed out after 10s");
        } catch (ExecutionException e) {
            return ExchangeBootReadiness.notReady("BINANCE", "UNKNOWN_ERROR",
                    "Boot readiness check failed: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExchangeBootReadiness.notReady("BINANCE", "UNKNOWN_ERROR",
                    "Boot readiness check interrupted");
        }
    }

    // --- helpers ---

    private Optional<OrderDataDto> querySingleOrder(RestRequest req) {
        try {
            HttpClientPort.HttpResponse response = httpClient.get(req.url(), req.headers());
            if (response.isSuccessful()) {
                return Optional.of(orderMapper.fromJson(response.body()));
            }
            if (response.statusCode() == 400 && isBinanceOrderNotFound(response.body())) {
                return Optional.empty();
            }
            throw queryException(response, "Order query");
        } catch (IOException e) {
            throw new ExchangeQueryException("BINANCE", ExchangeQueryException.ErrorType.TEMPORARY,
                    "Order query failed: " + e.getMessage(), e);
        }
    }

    private boolean isBinanceOrderNotFound(String body) {
        try {
            return objectMapper.readTree(body).path("code").asInt(0) == BINANCE_ORDER_NOT_FOUND;
        } catch (Exception e) {
            return false;
        }
    }

    private List<OrderDataDto> queryOrderList(RestRequest req) {
        try {
            HttpClientPort.HttpResponse response = httpClient.get(req.url(), req.headers());
            if (response.isSuccessful()) {
                return orderMapper.listFromJson(response.body());
            }
            throw queryException(response, "Open orders query");
        } catch (IOException e) {
            throw new ExchangeQueryException("BINANCE", ExchangeQueryException.ErrorType.TEMPORARY,
                    "Open orders query failed: " + e.getMessage(), e);
        }
    }

    private ExchangeQueryException queryException(HttpClientPort.HttpResponse response, String operation) {
        return new ExchangeQueryException("BINANCE", BinanceHttpErrors.errorType(response.statusCode()),
                operation + " failed HTTP " + response.statusCode() + ": " + response.body());
    }
}
