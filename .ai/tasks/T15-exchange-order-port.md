# T15 — ExchangeOrderPort: Mover seleção de transporte de despacho para adapter-binance

**Complexidade:** Média  
**Responsável:** Claude  
**Dependências:** T14 (refactor fronteiras arquiteturais: transporte e negócio)  
**Status:** Concluído

---

## Contexto

Após T14, `OrderDispatchAdapter` (spring-application) ainda contém a lógica de seleção de transporte para despacho de ordens: ela testa se o WebSocket de user data está conectado, decide usar o WS API da Binance, cai para REST, ou cai para streaming via market stream.

Essa decisão é um detalhe de provider — o adapter-binance é quem sabe quais transportes existem e em que ordem de preferência usá-los. A camada spring-application não deveria coordenar essa lógica.

O problema concreto: para cada despacho, `OrderDispatchAdapter` precisa de duas buscas em repositórios distintos:
- `webSocketRegistry.findUserStreamByExchangeName(id)` — verifica conectividade
- `adapterRepository.findStreamingByName(id)` — obtém o adapter para formatar a mensagem

Isso acopla spring-application ao modelo interno do Binance (user data WS para API de ordens, market WS para streaming fallback).

**Separação que guia o refactor:**

| Responsabilidade | Onde deve viver |
|---|---|
| Verificar bloqueio de despacho por perda de conexão | spring-application (`OrderDispatchAdapter`) |
| Selecionar transporte: WS API → REST → Streaming | adapter-binance (`BinanceOrderAdapter`) |
| Resultado síncrono de REST → trigger de conciliação | spring-application (`OrderDispatchAdapter`) |

---

## Etapas

### Etapa 1 — `ExchangeOrderPort` e `OrderSubmissionResult` no core

**Novo port outbound** `core/.../ports/outbound/exchange/ExchangeOrderPort.java`:
```java
public interface ExchangeOrderPort {
    String getExchangeName();
    OrderSubmissionResult submitOrder(SendOrderRequest request);
}
```

**Tipo de retorno** `core/.../dto/exchange/OrderSubmissionResult.java`:
- `dispatched()` — enviado de forma assíncrona (WS ou streaming); fill chegará via user data stream
- `completed(OrderDataDto dto)` — REST síncrono; conciliação imediata necessária
- `failed(String reason)` — rejeitado por filtro local ou falha de envio

### Etapa 2 — `BinanceOrderAdapter` em adapter-binance

`adapter-binance/.../BinanceOrderAdapter.java` implementa `ExchangeOrderPort`.

Recebe no construtor via injeção de spring-application:
- `WebSocketPort userStreamWebSocket` — WS API de ordens (user data channel)
- `WebSocketPort marketStreamWebSocket` — fallback de streaming
- `ExchangeStreamingPort streamingAdapter` — formatação de mensagem
- `ExchangeOrderExecutionPort restAdapter` — submissão REST

Lógica de fallback encapsulada aqui:
1. `userStreamWs.isConnected()` → formata + envia via user stream → `Dispatched`
2. `restAdapter.submitOrder(request)` → normaliza clientOrderId → `Completed(dto)`
3. `UnsupportedOperationException` → formata + envia via market stream → `Dispatched`
4. `OrderFilterViolationException | SymbolFilterLoadException` → `Failed(reason)`

### Etapa 3 — Extensão do repositório de adapters

Adicionar em `ExchangeAdapterRepositoryPort`:
```java
void registerOrderPort(ExchangeOrderPort port);
Optional<ExchangeOrderPort> findOrderPortByName(String exchangeName);
```

`InMemoryExchangeAdapterRepository` recebe `List<ExchangeOrderPort> orderPorts` no construtor — Spring injeta todos os beans que implementam o port.

### Etapa 4 — Simplificação de `OrderDispatchAdapter`

Remove: `WebSocketPortRegistryPort`, `tryDispatchViaWebSocketApi`, `tryDispatchViaRest`, `dispatchViaStreaming`, `normalizeClientOrderId`.

Nova lógica:
```java
if (isDispatchBlocked) → rejeita via conciliação
ExchangeOrderPort port = adapterRepository.findOrderPortByName(id).orElseThrow(...)
OrderSubmissionResult result = port.submitOrder(toSendOrderRequest(command))
if result.isCompleted() → orderConciliation.execute(result.syncResult())
if result.isFailed()    → orderConciliation.execute(toRejectedOrder(command, reason))
// dispatched = sem ação (fill chega async)
```

### Etapa 5 — Wiring em spring-application

Em `BinanceMarketStreamConfiguration`:
```java
@Bean
public BinanceOrderAdapter binanceOrderAdapter(
    OkHttp3WebSocketAdapter binanceUserStreamWebSocketPort,   // user data WS
    OkHttp3WebSocketAdapter binanceMarketWebSocketPort,       // market WS
    BinanceMarketStreamAdapter binanceMarketStreamAdapter) {  // streaming + REST
    return new BinanceOrderAdapter(...);
}
```

---

## Critérios de aceitação

1. `OrderDispatchAdapter` não referencia `WebSocketPortRegistryPort`
2. `BinanceOrderAdapter` contém toda a lógica de seleção de transporte
3. Despacho via WS API → `Dispatched`; via REST → `Completed(dto)` com conciliação; via streaming → `Dispatched`
4. Violações de filtro retornam `Failed(reason)` com conciliação REJECTED
5. Compilação limpa em todos os módulos
6. Todos os testes de `BinanceOrderAdapterTest` e `OrderDispatchAdapterTest` passam

---

## Invariantes preservados

- `InMemoryWebSocketPortRegistry` não muda — ainda necessário para connection management
- `WebSocketPortRegistryPort` permanece no core — usado por ConnectMarketStream, ConnectUserStream, LinearBackoffReconnectionStrategy
- `BinanceMarketStreamAdapter` e `BinanceUserStreamAdapter` sem alteração
- `OrderConciliationPort` permanece chamado somente de spring-application
