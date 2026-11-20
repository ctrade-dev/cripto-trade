# T18 — Trade Reconciliation Report

**Complexidade:** Média  
**Responsável:** Claude  
**Dependências:** T5 (REST API Binance), T16 (ExchangeAdapterDescriptor)  
**Status:** Concluído

---

## Descrição

Após horas de operação em testnet, é necessário validar se o que a Binance executou bate com o que o sistema registrou localmente. Esta tarefa implementa um endpoint de auditoria read-only que:

1. Consulta o histórico de trades executados na Binance via `GET /api/v3/myTrades`
2. Consulta as transações e fills registrados localmente no banco
3. Cruza os dois lados pelo `clientOrderId` (chave local) e `orderId` (chave Binance)
4. Retorna um relatório com as divergências encontradas

**O relatório não corrige nada.** É uma ferramenta de inspeção para o operador identificar se o sistema está operando corretamente.

---

## Modelo do relatório

```
ReconciliationReportDto
├── period: { from, to }
├── symbol: String
├── summary: { total, matched, divergent, binanceOnly, localOnly }
└── entries: List<ReconciliationEntryDto>
    ├── status: MATCHED | DIVERGENT | BINANCE_ONLY | LOCAL_ONLY
    ├── clientOrderId: String (null se BINANCE_ONLY sem clientOrderId reconhecido)
    ├── binanceSide: { orderId, executedQty, avgPrice, totalQuote, fees, time }  (null se LOCAL_ONLY)
    └── localSide:   { transactionId, runnerId, executedQty, avgPrice, totalQuote, status, updatedAt }  (null se BINANCE_ONLY)
```

**Categorias de status:**

| Status | Significado |
|---|---|
| `MATCHED` | Trade existe nos dois lados com qty e valor dentro da tolerância |
| `DIVERGENT` | Trade existe nos dois lados mas qty ou valor discrepam além da tolerância |
| `BINANCE_ONLY` | Trade executado na Binance sem transação local correspondente (fill perdido) |
| `LOCAL_ONLY` | Transação local marcada como FILLED/PARTIALLY_FILLED sem trade Binance confirmando |

---

## Escopo técnico

### 1. Port de consulta histórica Binance (outbound, em `core`)

```java
// core/src/main/java/com/marmitt/core/ports/outbound/exchange/TradeHistoryQueryPort.java
public interface TradeHistoryQueryPort {
    List<BinanceTradeDto> fetchTrades(String symbol, Instant from, Instant to);
}
```

```java
// core/src/main/java/com/marmitt/core/dto/exchange/BinanceTradeDto.java
public record BinanceTradeDto(
    long orderId,
    String clientOrderId,
    String symbol,
    BigDecimal price,
    BigDecimal qty,
    BigDecimal quoteQty,
    BigDecimal commission,
    String commissionAsset,
    Instant time,
    boolean isBuyer
) {}
```

---

### 2. Use case de reconciliação (em `core`)

```java
// core/.../usecase/reconciliation/ReconcileTradesUseCase.java
public class ReconcileTradesUseCase {

    public ReconciliationReportDto reconcile(ReconciliationRequest request) {
        List<BinanceTradeDto> binanceTrades = tradeHistoryQueryPort.fetchTrades(
            request.symbol(), request.from(), request.to());

        List<Transaction> localTransactions = strategyRunnerRepository
            .findByPeriodAndSymbol(request.symbol(), request.from(), request.to());

        return ReconciliationMatcher.match(binanceTrades, localTransactions, request);
    }
}
```

**Lógica de matching em `ReconciliationMatcher`:**
1. Indexar `localTransactions` por `clientOrderId`
2. Para cada trade Binance: buscar local por `clientOrderId`
   - Encontrou: comparar `executedQty` e `totalQuote` com tolerância de 8 casas decimais → `MATCHED` ou `DIVERGENT`
   - Não encontrou: `BINANCE_ONLY`
3. Transações locais não referenciadas por nenhum trade Binance → `LOCAL_ONLY`
   - Filtrar apenas status `FILLED` e `PARTIALLY_FILLED` (SUBMITTED/PENDING não são divergência)

---

### 3. Implementação do port na Binance (em `adapter-binance`)

`BinanceTradeHistoryAdapter` chama `GET /api/v3/myTrades`:

```
GET /api/v3/myTrades?symbol=BTCUSDT&startTime=<epoch_ms>&endTime=<epoch_ms>&limit=1000
Authorization: HMAC-SHA256 (já implementado em T3)
```

Paginação: se Binance retornar 1000 registros (limite máximo), reemitir query com `fromId` do último trade até cobrir o período completo.

---

### 4. Query nova no repositório local

```java
// StrategyRunnerRepositoryPort — adicionar:
List<Transaction> findFilledBySymbolAndPeriod(String symbol, Instant from, Instant to);
```

Inclui status: `FILLED`, `PARTIALLY_FILLED`. Ignora `SUBMITTED`, `PENDING`, `CANCELLED`.

---

### 5. Endpoint REST (em `spring-application`)

```
GET /api/reconciliation?symbol=BTCUSDT&from=2025-01-01T00:00:00Z&to=2025-01-02T00:00:00Z&exchangeId=BINANCE
```

Parâmetros:
- `symbol` — obrigatório
- `from` — ISO-8601, obrigatório
- `to` — ISO-8601, obrigatório
- `exchangeId` — identificador da exchange, default `BINANCE` (único exchange suportado atualmente)
- `includeMatched` — boolean, default `false` (oculta MATCHED para reduzir ruído)

Response: `200 OK` com `ReconciliationReportDto` em JSON.

---

### 6. Wiring em `spring-application`

Em `BinanceMarketStreamConfiguration` (ou nova `BinanceReconciliationConfiguration`):
```java
@Bean
public TradeHistoryQueryPort binanceTradeHistoryAdapter(...) {
    return new BinanceTradeHistoryAdapter(binanceRestClient);
}

@Bean
public ReconcileTradesUseCase reconcileTradesUseCase(
    TradeHistoryQueryPort tradeHistoryQueryPort,
    StrategyRunnerRepositoryPort strategyRunnerRepository) {
    return new ReconcileTradesUseCase(tradeHistoryQueryPort, strategyRunnerRepository);
}
```

---

## Arquivos a criar/editar

| Arquivo | Ação |
|---|---|
| `core/.../ports/outbound/exchange/TradeHistoryQueryPort.java` | Criar |
| `core/.../dto/exchange/BinanceTradeDto.java` | Criar |
| `core/.../dto/reconciliation/ReconciliationReportDto.java` | Criar |
| `core/.../dto/reconciliation/ReconciliationEntryDto.java` | Criar |
| `core/.../dto/reconciliation/ReconciliationRequest.java` | Criar |
| `core/.../usecase/reconciliation/ReconcileTradesUseCase.java` | Criar |
| `core/.../usecase/reconciliation/ReconciliationMatcher.java` | Criar |
| `core/.../ports/outbound/repository/StrategyRunnerRepositoryPort.java` | Editar — adicionar `findFilledBySymbolAndPeriod` |
| `adapter-binance/.../BinanceTradeHistoryAdapter.java` | Criar |
| `spring-application/.../controller/ReconciliationController.java` | Criar |
| `spring-application/.../persistence/adapter/JdbcStrategyRunnerRepositoryAdapter.java` | Editar — implementar nova query |
| `spring-application/.../persistence/repository/RunnerTransactionJdbcRepository.java` | Editar — adicionar query JDBC |

---

## Critérios de aceitação

1. `GET /api/reconciliation?symbol=BTCUSDT&from=...&to=...` retorna `200 OK` com JSON estruturado.
2. Um trade executado na Binance aparece como `MATCHED` com a transação local correspondente.
3. Uma transação local `FILLED` sem confirmação Binance aparece como `LOCAL_ONLY`.
4. O campo `summary.binanceOnly` é `0` após uma sessão sem erros de conciliação de fills.
5. O relatório cobre fills de ordens parcialmente preenchidas (múltiplos fills para o mesmo `clientOrderId`).
6. `includeMatched=false` (default) omite entradas `MATCHED` do array `entries` mas mantém o count no `summary`.
7. Período com mais de 1000 trades (limite da API Binance) é paginado automaticamente — relatório completo.
8. Se Binance retornar erro (ex: API key inválida), endpoint retorna `502` com mensagem de erro descritiva.
