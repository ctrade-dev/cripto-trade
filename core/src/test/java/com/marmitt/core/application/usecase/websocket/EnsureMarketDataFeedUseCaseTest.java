package com.marmitt.core.application.usecase.websocket;

import com.marmitt.core.domain.runner.Position;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.dto.connection.ConnectionKey;
import com.marmitt.core.dto.connection.ConnectionResultDto;
import com.marmitt.core.dto.websocket.request.MessageRequest;
import com.marmitt.core.dto.websocket.request.StreamSubscriptionRequest;
import com.marmitt.core.dto.wrapper.WebSocketConnectionManager;
import com.marmitt.core.enums.ConnectionStatus;
import com.marmitt.core.enums.RunnerStatus;
import com.marmitt.core.enums.StreamChannel;
import com.marmitt.core.enums.StreamType;
import com.marmitt.core.ports.inbound.websocket.ConnectMarketStreamPort;
import com.marmitt.core.ports.inbound.websocket.ConnectUserStreamPort;
import com.marmitt.core.ports.inbound.websocket.SendMessageWebSocketPort;
import com.marmitt.core.ports.outbound.exchange.ExchangeAdapterDescriptor;
import com.marmitt.core.ports.outbound.marketdata.LatestPricePort;
import com.marmitt.core.ports.outbound.repository.ExchangeAdapterRepositoryPort;
import com.marmitt.core.ports.outbound.repository.StrategyRunnerRepositoryPort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T56: a demanda vem dos runners operacionais; a decisao de abrir ou assinar vem da evidencia (conexao e
 * idade do ultimo tick), nunca de um registro de assinaturas.
 */
class EnsureMarketDataFeedUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private final StrategyRunnerRepositoryPort runners = mock(StrategyRunnerRepositoryPort.class);
    private final ExchangeAdapterRepositoryPort adapters = mock(ExchangeAdapterRepositoryPort.class);
    private final WebSocketConnectionRepositoryPort connections = mock(WebSocketConnectionRepositoryPort.class);
    private final LatestPricePort prices = mock(LatestPricePort.class);
    private final ConnectMarketStreamPort connectMarket = mock(ConnectMarketStreamPort.class);
    private final ConnectUserStreamPort connectUser = mock(ConnectUserStreamPort.class);
    private final SendMessageWebSocketPort send = mock(SendMessageWebSocketPort.class);
    private final ExchangeAdapterDescriptor binance = mock(ExchangeAdapterDescriptor.class);
    private final EnsureMarketDataFeedUseCase useCase = new EnsureMarketDataFeedUseCase(runners, adapters,
            connections, prices, connectMarket, connectUser, send, exchange -> StreamType.TICKER,
            Duration.ofSeconds(60), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void binanceRunner() {
        when(adapters.findAdapter("BINANCE")).thenReturn(Optional.of(binance));
        when(binance.hasUserStream()).thenReturn(true);
        StrategyRunner active = runner(RunnerStatus.ACTIVE, "BTCUSDT");
        StrategyRunner terminating = runner(RunnerStatus.TERMINATING, "ETHUSDT");
        StrategyRunner halted = runner(RunnerStatus.HALTED, "SOLUSDT");
        when(runners.findAll()).thenReturn(List.of(active, terminating, halted));
    }

    @Test
    void missingConnectionsAreOpenedWithEveryDemandedSymbol() {
        useCase.ensure();

        ArgumentCaptor<StreamSubscriptionRequest> request = ArgumentCaptor.forClass(StreamSubscriptionRequest.class);
        verify(connectMarket).execute(org.mockito.ArgumentMatchers.eq("BINANCE"), request.capture());
        assertEquals(List.of("BTC/USDT/TICKER", "ETH/USDT/TICKER"), pairs(request.getValue()),
                "ACTIVE e TERMINATING, nunca HALTED");
        verify(connectUser).execute("BINANCE");
        verify(send, never()).execute(any());
    }

    @Test
    void connectedStreamsWithFreshTicksNeedNothing() {
        connected(ConnectionKey.market("BINANCE"), NOW.minusSeconds(600));
        connected(new ConnectionKey("BINANCE", StreamChannel.USER_DATA), NOW.minusSeconds(600));
        when(prices.findUpdatedAt(anyString())).thenReturn(Optional.of(NOW.minusSeconds(2)));

        useCase.ensure();

        verify(connectMarket, never()).execute(anyString(), any());
        verify(connectUser, never()).execute(anyString());
        verify(send, never()).execute(any());
    }

    @Test
    void symbolWithoutRecentTickIsSubscribedAgain() {
        connected(ConnectionKey.market("BINANCE"), NOW.minusSeconds(600));
        connected(new ConnectionKey("BINANCE", StreamChannel.USER_DATA), NOW.minusSeconds(600));
        when(prices.findUpdatedAt("BTCUSDT")).thenReturn(Optional.of(NOW.minusSeconds(2)));
        when(prices.findUpdatedAt("ETHUSDT")).thenReturn(Optional.of(NOW.minusSeconds(75)));

        useCase.ensure();

        ArgumentCaptor<MessageRequest> request = ArgumentCaptor.forClass(MessageRequest.class);
        verify(send).execute(request.capture());
        assertEquals(List.of("ETH/USDT/TICKER"), pairs((StreamSubscriptionRequest) request.getValue()));
    }

    /** T53 rodada 11: conexao marcada como conectada com o socket fechado repetia a assinatura para sempre. */
    @Test
    void subscribeRejectedBySocketReopensTheConnectionInTheNextCycle() {
        WebSocketConnectionManager market = WebSocketConnectionManager.forExchange("BINANCE");
        market.setConnectionResult(ConnectionResultDto.connecting());
        market.setConnectionResult(new ConnectionResultDto(ConnectionStatus.CONNECTED, "up", NOW.minusSeconds(600),
                UUID.randomUUID(), java.util.Map.of()));
        when(connections.hasConnection(ConnectionKey.market("BINANCE"))).thenReturn(true);
        when(connections.getConnection(ConnectionKey.market("BINANCE"))).thenReturn(market);
        connected(new ConnectionKey("BINANCE", StreamChannel.USER_DATA), NOW.minusSeconds(600));
        when(prices.findUpdatedAt(anyString())).thenReturn(Optional.of(NOW.minusSeconds(120)));
        when(send.execute(any())).thenThrow(new IllegalStateException("WebSocket send rejected"));

        useCase.ensure();

        assertEquals(ConnectionStatus.ERROR, market.getConnectionResult().status());
        verify(connectMarket, never()).execute(anyString(), any());

        useCase.ensure();

        verify(connectMarket).execute(org.mockito.ArgumentMatchers.eq("BINANCE"), any());
    }

    @Test
    void freshConnectionGetsTimeForItsFirstTicks() {
        connected(ConnectionKey.market("BINANCE"), NOW.minusSeconds(10));
        connected(new ConnectionKey("BINANCE", StreamChannel.USER_DATA), NOW.minusSeconds(10));
        when(prices.findUpdatedAt(anyString())).thenReturn(Optional.empty());

        useCase.ensure();

        verify(send, never()).execute(any());
    }

    @Test
    void reconnectingStreamsAreLeftToTheReconnectionStrategy() {
        withStatus(ConnectionKey.market("BINANCE"), ConnectionStatus.RECONNECTING, NOW.minusSeconds(600));
        withStatus(new ConnectionKey("BINANCE", StreamChannel.USER_DATA), ConnectionStatus.CONNECTING,
                NOW.minusSeconds(600));

        useCase.ensure();

        verify(connectMarket, never()).execute(anyString(), any());
        verify(connectUser, never()).execute(anyString());
    }

    @Test
    void droppedConnectionIsReopened() {
        withStatus(ConnectionKey.market("BINANCE"), ConnectionStatus.CLOSED, NOW.minusSeconds(600));
        withStatus(new ConnectionKey("BINANCE", StreamChannel.USER_DATA), ConnectionStatus.ERROR,
                NOW.minusSeconds(600));

        useCase.ensure();

        verify(connectMarket).execute(org.mockito.ArgumentMatchers.eq("BINANCE"), any());
        verify(connectUser).execute("BINANCE");
    }

    @Test
    void exchangeWithoutUserStreamOnlyGetsMarketData() {
        when(binance.hasUserStream()).thenReturn(false);

        useCase.ensure();

        verify(connectMarket).execute(org.mockito.ArgumentMatchers.eq("BINANCE"), any());
        verify(connectUser, never()).execute(anyString());
    }

    @Test
    void openLotOfAStoppedRunnerDemandsItsSymbolButNoUserStream() {
        // T58: lote aberto precisa de preco para drawdown e PnL, mesmo com o runner parado.
        UUID portfolioId = UUID.randomUUID();
        StrategyRunner halted = owned(runner(RunnerStatus.HALTED, "SOLUSDT"), portfolioId);
        StrategyRunner archived = owned(runner(RunnerStatus.ARCHIVED, "XRPUSDT"), portfolioId);
        Position solLot = lot(halted);
        Position xrpLot = lot(archived);
        when(runners.findAll()).thenReturn(List.of(halted, archived));
        when(runners.findActivePositionsByPortfolioId(portfolioId)).thenReturn(List.of(solLot, xrpLot));

        useCase.ensure();

        ArgumentCaptor<StreamSubscriptionRequest> request = ArgumentCaptor.forClass(StreamSubscriptionRequest.class);
        verify(connectMarket).execute(org.mockito.ArgumentMatchers.eq("BINANCE"), request.capture());
        assertEquals(List.of("SOL/USDT/TICKER"), pairs(request.getValue()), "runner arquivado nao gera demanda");
        verify(connectUser, never()).execute(anyString());
    }

    @Test
    void stoppedRunnerWithoutLotDemandsNothing() {
        UUID portfolioId = UUID.randomUUID();
        StrategyRunner halted = owned(runner(RunnerStatus.HALTED, "SOLUSDT"), portfolioId);
        when(runners.findAll()).thenReturn(List.of(halted));
        when(runners.findActivePositionsByPortfolioId(portfolioId)).thenReturn(List.of());

        useCase.ensure();

        verify(connectMarket, never()).execute(anyString(), any());
        verify(connectUser, never()).execute(anyString());
    }

    // ---------------------------------------------------------------- helpers

    private static StrategyRunner owned(StrategyRunner runner, UUID portfolioId) {
        when(runner.getId()).thenReturn(UUID.randomUUID());
        when(runner.getPortfolioId()).thenReturn(portfolioId);
        return runner;
    }

    private static Position lot(StrategyRunner owner) {
        UUID runnerId = owner.getId();
        Position lot = mock(Position.class);
        when(lot.getRunnerId()).thenReturn(runnerId);
        return lot;
    }

    private void connected(ConnectionKey key, Instant since) {
        withStatus(key, ConnectionStatus.CONNECTED, since);
    }

    private void withStatus(ConnectionKey key, ConnectionStatus status, Instant since) {
        WebSocketConnectionManager manager = mock(WebSocketConnectionManager.class);
        when(manager.getConnectionResult()).thenReturn(
                new ConnectionResultDto(status, "test", since, UUID.randomUUID(), java.util.Map.of()));
        when(connections.hasConnection(key)).thenReturn(true);
        when(connections.getConnection(key)).thenReturn(manager);
    }

    private static List<String> pairs(StreamSubscriptionRequest request) {
        return request.getCurrencyPairs().stream()
                .map(p -> p.baseCurrency() + "/" + p.quoteCurrency() + "/" + p.streamType())
                .toList();
    }

    private static StrategyRunner runner(RunnerStatus status, String symbol) {
        StrategyRunner runner = mock(StrategyRunner.class);
        when(runner.getStatus()).thenReturn(status);
        when(runner.getSymbol()).thenReturn(symbol);
        when(runner.getExchangeId()).thenReturn("BINANCE");
        when(runner.getAllowedMarketDataSources()).thenReturn(Set.of("BINANCE"));
        return runner;
    }
}
