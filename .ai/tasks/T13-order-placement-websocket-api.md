# T13 — Order Placement via WebSocket API Binance

**Complexidade:** Alta  
**Responsável:** Claude  
**Dependências:** T12 (conexão WebSocket API estabelecida), T3 (signing), T5 (REST path existente como fallback)  
**Status:** Concluído

---

## Contexto

Com a migração do User Data Stream para o WebSocket API da Binance (T12), a aplicação passa a manter uma conexão autenticada em `wss://ws-api.binance.com:443/ws-api/v3`. Essa mesma conexão suporta envio de ordens e cancelamentos via `order.place` e `order.cancel` — sem custo extra de conexão ou autenticação adicional.

O código de formatação de mensagens já existe e está correto:
- `OrderProcessor` gera `{"id": "...", "method": "order.place", "params": {...assinado...}}`
- `CancelOrderProcessor` gera `{"id": "...", "method": "order.cancel", "params": {...assinado...}}`

O que falta é: (1) rotear ordens para essa conexão em vez do REST, e (2) processar a resposta que chega de volta como mensagem WebSocket na mesma conexão.

**Resposta do WebSocket API para `order.place`:**
```json
{
  "id": "clientOrderId",
  "status": 200,
  "result": {
    "symbol": "BTCUSDT",
    "orderId": 12345,
    "clientOrderId": "clientOrderId",
    "transactTime": 1234567890123,
    "price": "95000.00000000",
    "origQty": "0.001",
    "executedQty": "0.00000000",
    "cummulativeQuoteQty": "0.00000000",
    "status": "NEW",
    "side": "BUY",
    "type": "LIMIT",
    "timeInForce": "GTC"
  }
}
```

Distingue-se de confirmação de subscrição (T12) pela presença de `result.symbol` + `result.status` (string de order status). Respostas de erro têm `status != 200` e campo `error`.

**Fee:** não está na resposta de `order.place`. Chega posteriormente via `executionReport` no user data stream (T12). `OrderDataDto.fee` pode ser zero nesta resposta inicial.

---

## Fluxo após implementação

```
OrderDispatchAdapter.dispatch()
  → tryDispatchViaWebSocketApi()          ← novo, prioridade sobre REST
      → ExchangeStreamingPort.formatMessage(request)  ← já correto via OrderProcessor
      → webSocketRegistry.findUserStreamByExchangeName()  ← conexão do T12
      → webSocket.sendMessage(json)
      → retorna true (async — sem orderConciliation aqui)

Binance WebSocket API
  → {"id": "clientOrderId", "status": 200, "result": {"symbol": "BTCUSDT", "status": "NEW", ...}}

BinanceUserDataProcessor.processMessage()
  → detecta resposta de ordem (result.symbol + result.status)
  → OrderApiResponseProcessor → OrderDataDto{status=NEW, ...}

ProcessUserMessageHandler → OrderUpdateListener → orderConciliation.execute(NEW)

[mais tarde, via executionReport do user data stream]
  → orderConciliation.execute(FILLED/CANCELED/...)
```

Se a conexão WebSocket API não estiver ativa: fallback para REST (`tryDispatchViaRest()`).

---

## Etapas

### Etapa 1 — `OrderDispatchAdapter`: novo path `tryDispatchViaWebSocketApi()`

**Arquivo:**
- `spring-application/src/main/java/.../infrastructure/exchange/OrderDispatchAdapter.java`

**Mudanças:**
- Adicionar `tryDispatchViaWebSocketApi(SendOrderRequest, String exchangeId)`:
  - Verifica `webSocketRegistry.findUserStreamByExchangeName(exchangeId)` — se vazio, retorna `false`
  - Formata via `ExchangeStreamingPort.formatMessage(request)` (reutiliza `OrderProcessor`/`CancelOrderProcessor` já existentes)
  - Envia via `webSocket.sendMessage(json)`
  - Retorna `true` (sem `orderConciliation` aqui — resposta chega async via WebSocket)
- Alterar `dispatch()`: tentar WebSocket API antes de REST:
  ```
  tryDispatchViaWebSocketApi() → se false → tryDispatchViaRest() → se false → dispatchViaStreaming()
  ```
- Mesmo tratamento de `OrderFilterViolationException` / `SymbolFilterLoadException` (filtros aplicados dentro de `formatMessage()`)

**Escopo:** Apenas `OrderDispatchAdapter`. Sem mudança em adapters Binance ainda.

---

### Etapa 2 — `BinanceUserDataProcessor`: roteamento + `OrderApiResponseProcessor`

**Arquivos:**
- `adapter-binance/src/main/java/.../processor/receive/BinanceUserDataProcessor.java` — estender detecção de mensagens (complementa Etapa 4 do T12):

  | Caso | Condição | Ação |
  |------|----------|------|
  | Push event (user data) | `root.has("subscriptionId") && root.has("event")` | Extrair `event`, processar pelo mapa |
  | Resposta de ordem | `root.has("status") && root.has("result") && result.has("symbol")` | `OrderApiResponseProcessor` |
  | Confirmação de subscrição | `root.has("status") && result.has("subscriptionId")` | Log info + ignored |
  | Erro de API | `root.has("status") && status != 200` | Log error + return error |

- `adapter-binance/src/main/java/.../processor/receive/OrderApiResponseProcessor.java` — novo:
  - Recebe o nó `result` da resposta
  - Delega para `BinanceOrderApiResponseMapper.fromResult(resultNode)` → `OrderDataDto`
  - Retorna `ProcessingResult.ok(orderDto)`

- `adapter-binance/src/main/java/.../processor/receive/BinanceOrderApiResponseMapper.java` — novo:

  | Campo `result` Binance | Campo `OrderDataDto` | Observação |
  |------------------------|----------------------|------------|
  | `orderId` (long)       | `orderId` (String)   | `String.valueOf()` |
  | `clientOrderId`        | `clientOrderId`      | |
  | `symbol`               | `symbol`             | `Symbol.of()` |
  | `side`                 | `side`               | BUY/SELL |
  | `type`                 | `type`               | LIMIT/MARKET/etc. |
  | `origQty`              | `quantity`           | |
  | `executedQty`          | `executedQuantity`   | |
  | `price`                | `price`              | |
  | `cummulativeQuoteQty` / `executedQty` | `executedPrice` | WAP; zero se `executedQty == 0` |
  | `status`               | `status`             | NEW/FILLED/CANCELED/REJECTED/etc. |
  | `transactTime`         | `timestamp`          | `Instant.ofEpochMilli()` |
  | —                      | `fee`                | `BigDecimal.ZERO` (chega via executionReport depois) |

  Resposta de erro (`status != 200`): mapear para `OrderDataDto` com status `REJECTED` e `rejectReason` do campo `error.msg`.

**Reutilização:** `BinanceEventProcessor<?>` interface existente pode ser reutilizada se `OrderApiResponseProcessor` implementá-la. Verificar na implementação se encaixa.

---

### Etapa 3 — Testes e validação testnet

**Arquivos:**
- Novo `BinanceOrderWebSocketApiIntegrationTest` em `spring-application/src/test/`:
  1. Simular WebSocket API respondendo `{"id": "...", "status": 200, "result": {"symbol": "BTCUSDT", "status": "NEW", ...}}`
  2. Verificar que `OrderApiResponseProcessor` parseia corretamente
  3. Verificar que `orderConciliation` é chamado com status NEW
  4. Simular `status != 200` → verificar REJECTED com `rejectReason`
  5. Simular ausência de conexão WebSocket API → fallback para REST funcionando

**Validação na testnet:**
1. Subir com profile `testnet` com T12 ativo (conexão WebSocket API estabelecida)
2. Disparar um ciclo de ordem — verificar nos logs: ausência de `POST /api/v3/order`, presença de `order.place` enviado via WebSocket
3. Verificar resposta chegando como mensagem WebSocket e `orderConciliation` executado com NEW
4. Verificar fill posterior via `executionReport` (user data stream T12) completando o ciclo

---

## Critérios de aceitação

1. Com conexão WebSocket API ativa (T12 rodando), ordens são enviadas via `order.place` WebSocket — nenhum `POST /api/v3/order` no log
2. Resposta `{"id": "...", "status": 200, "result": {...}}` é processada por `OrderApiResponseProcessor` → `orderConciliation` com NEW
3. Sem conexão WebSocket API: fallback para REST transparente (sem erro visível)
4. Cancelamentos via `order.cancel` seguem o mesmo path
5. Erro da API (`status != 200`) loga o motivo e propaga REJECTED via `orderConciliation`

---

## Invariantes preservados

- `OrderProcessor` e `CancelOrderProcessor` não mudam — apenas o canal de envio muda
- REST path permanece como fallback — nenhuma regressão para exchanges sem WebSocket API
- `ExchangeStreamingPort.formatMessage()` continua sendo o ponto único de formatação de mensagens de saída
- Fee zero na resposta inicial é esperado e aceito — o fill real chega via `executionReport`
