package com.marmitt.application.spring.bootstrap;

import com.marmitt.core.domain.runner.RunnerStatusCause;
import com.marmitt.core.domain.runner.ClientOrderId;
import com.marmitt.core.domain.runner.Position;
import com.marmitt.core.domain.runner.StrategyRunner;
import com.marmitt.core.domain.runner.Transaction;
import com.marmitt.core.dto.connection.ConnectionKey;
import com.marmitt.core.dto.runner.request.CreateRunnerRequest;
import com.marmitt.core.enums.ConnectionStatus;
import com.marmitt.core.enums.OrderType;
import com.marmitt.core.enums.TransactionStatus;
import com.marmitt.core.enums.TransactionType;
import com.marmitt.core.ports.outbound.marketdata.LatestPricePort;
import com.marmitt.core.ports.outbound.repository.WebSocketConnectionRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * T56 — sem nenhuma chamada a /api/websocket, o coordenador de demanda abre a conexao do MOCK e assina o
 * simbolo do runner ativo; o feed simulado passa a alimentar o runner. T58 — lote aberto de runner parado
 * tambem gera demanda: o preco chega e o runner continua sem negociar.
 */
class MarketDataAutoFeedIntegrationTest extends ScenarioStrategyE2ETestSupport {

    private static final UUID SMA_STRATEGY_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    /** Simbolo que nenhum outro teste assina: o tick so chega pela demanda do lote. */
    private static final String LOT_SYMBOL = "SOLUSDT";

    @Autowired
    private LatestPricePort latestPrices;

    @Autowired
    private WebSocketConnectionRepositoryPort connections;

    @DynamicPropertySource
    static void autoFeed(DynamicPropertyRegistry registry) {
        registry.add("ctrade.market-data.auto-feed.enabled", () -> "true");
        registry.add("ctrade.market-data.auto-feed.initial-delay-ms", () -> "200");
        registry.add("ctrade.market-data.auto-feed.interval-ms", () -> "500");
        // A conexao do MOCK sobrevive entre os testes da classe: com limiar curto, o simbolo que o teste
        // seguinte demanda e reassinado em ~1s, em vez de esperar os 60s de producao.
        registry.add("ctrade.observability.market-data-stale-ms", () -> "1000");
    }

    @Test
    void activeRunnerReceivesTicksWithoutAnyManualConnection() {
        UUID runnerId = createActiveRunnerOnMockMarketData();

        awaitCondition(() -> connections.hasConnection(ConnectionKey.market(MOCK_EXCHANGE))
                        && connections.getConnection(ConnectionKey.market(MOCK_EXCHANGE)).getConnectionResult().status()
                        == ConnectionStatus.CONNECTED,
                WAIT_TIMEOUT, "conexao de market data do MOCK aberta pelo coordenador", runnerId);
        awaitCondition(() -> latestPrices.findUpdatedAt(SYMBOL).isPresent(), WAIT_TIMEOUT,
                "tick do simbolo do runner chegando", runnerId);
        awaitCondition(() -> signalEvaluatedCount(runnerId, "HOLD") >= 1.0, WAIT_TIMEOUT,
                "o runner avaliou um tick do feed", runnerId);
    }

    @Test
    void openLotOfAStoppedRunnerGetsPricesButTheRunnerDoesNotTrade() {
        // T58: o lote de runner parado precisa de preco (drawdown, PnL); o runner continua sem negociar.
        // Runner nunca ativado (CREATED): parado desde o inicio, a demanda so pode vir do lote.
        UUID runnerId = createRunnerOnMockMarketData(LOT_SYMBOL);
        StrategyRunner runner = strategyRunnerRepository.findById(runnerId).orElseThrow();
        BigDecimal quantity = new BigDecimal("0.001");
        BigDecimal price = new BigDecimal("82853");
        Transaction buy = new Transaction(runnerId, ClientOrderId.generate(runner.getShortCode(), TransactionType.BUY),
                TransactionType.BUY, OrderType.LIMIT, LOT_SYMBOL, quantity, price, quantity.multiply(price),
                new BigDecimal("0.90"), "t58 seed buy", null, Map.of());
        strategyRunnerRepository.saveTransaction(buy);
        Position lot = Position.open(runnerId, LOT_SYMBOL, quantity, quantity.multiply(price));
        lot.associateBuyTransaction(buy.getId());
        strategyRunnerRepository.savePosition(lot);

        awaitCondition(() -> latestPrices.findUpdatedAt(LOT_SYMBOL).isPresent(), WAIT_TIMEOUT,
                "tick do simbolo do lote chegando com o runner parado", runnerId);

        assertEquals(0.0, signalEvaluatedCount(runnerId, "HOLD"), "runner parado nao avalia tick");
        assertEquals(1, strategyRunnerRepository.findByRunnerIdAndStatuses(runnerId,
                List.of(TransactionStatus.values())).size(), "so a BUY semeada: nenhuma ordem do runner parado");
    }

    private UUID createRunnerOnMockMarketData(String symbol) {
        UUID runnerId = createRunnerPort.execute(CreateRunnerRequest.builder()
                .portfolioId(createPortfolio())
                .strategyId(SMA_STRATEGY_ID)
                .symbol(symbol)
                .exchangeName(MOCK_EXCHANGE)
                .allowedMarketDataSources(Set.of(MOCK_EXCHANGE))
                .build()).runnerId();
        assertNotNull(runnerId);
        return runnerId;
    }

    private UUID createActiveRunnerOnMockMarketData() {
        UUID runnerId = createRunnerOnMockMarketData(SYMBOL);
        StrategyRunner runner = strategyRunnerRepository.findById(runnerId).orElseThrow();
        runner.startInitializing(RunnerStatusCause.manual("test"));
        runner.activate(RunnerStatusCause.manual("test"));
        strategyRunnerRepository.save(runner);
        return runnerId;
    }
}
