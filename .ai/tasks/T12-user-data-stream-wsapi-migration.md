# T12 — Migração User Data Stream → WebSocket API Binance

**Complexidade:** Alta  
**Responsável:** Claude  
**Dependências:** T4 (base implementada), T3 (signing já presente)  
**Status:** Concluído

---

## Contexto

A Binance removeu em 2026-02-04 os endpoints de listen key para SPOT:
- `POST /api/v3/userDataStream`
- `PUT /api/v3/userDataStream`
- `DELETE /api/v3/userDataStream`

O sistema retorna 410 ao inicializar o User Data Stream porque `ListenKeyManager` chama `POST /api/v3/userDataStream` em `BinanceUserStreamSession.open()`.

A nova abordagem usa o **WebSocket API** da Binance: conexão bidirecional onde autenticação e subscrição acontecem via mensagens JSON assinadas com HMAC-SHA256 dentro do próprio WebSocket. Não há mais listen key, sem keepalive REST, sem revogação.

**Novo fluxo:**
1. Conectar em `wss://ws-api.binance.com:443/ws-api/v3` (testnet: `wss://ws-api.testnet.binance.vision/ws-api/v3`)
2. Após WebSocket abrir → enviar `userDataStream.subscribe.signature` com HMAC-SHA256 assinado
3. Eventos chegam no formato `{"subscriptionId": N, "event": {"e": "executionReport", ...}}`

O gancho arquitetural já existe: `PostConnectionEstablishHandler` é chamado via `WebSocketConnectedEvent`, e `ExchangeAdapterRepositoryPort.findActiveSession(UUID)` permite recuperar a sessão ativa por `connectionId`.

---

## Etapas

### Etapa 1 — Config: adicionar `ws-api-base-url` em properties e YAML

**Arquivos:**
- `spring-application/src/main/java/.../config/exchange/BinanceProperties.java` — adicionar campo `@NotBlank private String wsApiBaseUrl`
- `spring-application/src/main/resources/application.yml` — adicionar `ws-api-base-url: wss://ws-api.binance.com:443/ws-api/v3`
- `spring-application/src/main/resources/application-testnet.yml` — adicionar `ws-api-base-url: wss://ws-api.testnet.binance.vision/ws-api/v3`

**Escopo:** Apenas config. Sem mudança de comportamento. Verificar que app sobe sem erro de binding.

---

### Etapa 2 — Core: `UserStreamSession.subscriptionMessage()` e `PostConnectionEstablishHandler`

**Arquivos:**
- `core/src/main/java/.../exchange/streaming/UserStreamSession.java` — adicionar método default:
  ```java
  default Optional<String> subscriptionMessage() {
      return Optional.empty();
  }
  ```
- `core/src/main/java/.../handler/connection/PostConnectionEstablishHandler.java` — estender para `StreamChannel.USER_DATA`:
  - Usar `event.connectionId()` diretamente (já disponível no record)
  - Recuperar sessão via `adapterRepository.findActiveSession(event.connectionId())`
  - Chamar `session.subscriptionMessage()` on-demand (timestamp fresco dentro do recvWindow)
  - Se presente, enviar via `webSocketRegistry.findUserStreamByExchangeName(event.exchange())`

**Escopo:** Mudança de interface (non-breaking via default) + extensão do handler. Sem mudança nos adapters ainda. O handler para USER_DATA não tem efeito até a Etapa 3 fornecer a implementação concreta.

---

### Etapa 3 — Adapter: reescrever sessão e remover `ListenKeyManager`

**Arquivos:**
- `adapter-binance/src/main/java/.../userdata/ListenKeyManager.java` — **DELETAR**
- `adapter-binance/src/main/java/.../userdata/BinanceUserStreamSession.java` — reescrever:
  - Constructor: `(String wsApiBaseUrl, BinanceRequestSigner signer, ObjectMapper objectMapper)`
  - `open()`: retorna `wsApiBaseUrl` (sem gerar payload aqui)
  - `subscriptionMessage()`: gera on-demand — `signer.signWebSocketParams(Map.of("recvWindow", 5000L))`, serializa `{"id": "<uuid>", "method": "userDataStream.subscribe.signature", "params": {...}}`, retorna `Optional.of(json)`
  - `close()`: apenas log (sem listen key para revogar)
- `adapter-binance/src/main/java/.../BinanceUserStreamSessionAdapter.java` — atualizar:
  - Remover campos `restBaseUrl`, `httpClient`; manter `objectMapper`
  - Receber `wsApiBaseUrl` como String direta no construtor
  - Criar `BinanceRequestSigner` com credenciais
  - `createSession()` instancia `BinanceUserStreamSession(wsApiBaseUrl, signer, objectMapper)`
- `spring-application/src/main/java/.../config/exchange/BinanceUserStreamConfiguration.java` — atualizar:
  - Remover `@Bean binanceRestClient()` (mover para `BinanceMarketStreamConfiguration`)
  - Atualizar `binanceUserStreamSessionAdapter()`: passar `wsApiBaseUrl` e `BinanceRequestSigner`
- `spring-application/src/main/java/.../config/exchange/BinanceMarketStreamConfiguration.java` — receber `binanceRestClient` movido

**Reutilização:** `BinanceRequestSigner.signWebSocketParams()` já implementa o signing correto para WebSocket API (alphabetical sort + HMAC-SHA256). Usado como está.

---

### Etapa 4 — Processor: unwrap do novo formato de evento

**Arquivo:**
- `adapter-binance/src/main/java/.../processor/receive/BinanceUserDataProcessor.java` — atualizar `processMessage()`:

  | Caso | Condição | Ação |
  |------|----------|------|
  | Push event | `root.has("subscriptionId") && root.has("event")` | Extrair `root.get("event")`, processar via mapa existente |
  | Confirmação de subscrição | `root.has("status") && root.has("id")` | status 200 → log info + return ignored; outro → log error + return error |
  | Fallback | nenhum dos anteriores | Processar `root` diretamente (compat.) |

  `ExecutionReportProcessor` não muda — apenas o nó de entrada muda de `root` para `root.get("event")`.

---

### Etapa 5 — Testes: reescrever integração e validar na testnet

**Arquivo:**
- `spring-application/src/test/java/.../userdata/BinanceListenKeyReconnectIntegrationTest.java` — reescrever cenário:
  1. WebSocket conecta em `wsApiBaseUrl` (sem listen key)
  2. `WebSocketConnectedEvent` dispara → subscription message enviada via WebSocket
  3. MockWebServer retorna confirmação `{"id": "...", "status": 200, "result": {"subscriptionId": 0}}`
  4. Estado final: CONNECTED
  5. Simular push event `{"subscriptionId": 0, "event": {"e": "executionReport", ...}}` e verificar pipeline

**Validação na testnet:**
1. Subir com profile `testnet` e credenciais válidas
2. Verificar log: ausência de "Listen key obtained", presença de "User data stream subscription confirmed"
3. Disparar ordem → verificar `executionReport` chegando e processando pelo pipeline
4. Simular queda de conexão → verificar reconexão com nova mensagem de subscrição assinada

---

## Critérios de aceitação

1. App inicia sem chamar `/api/v3/userDataStream` (nenhum 410 nos logs)
2. Após WebSocket abrir, subscription message assinada é enviada automaticamente
3. Eventos de `executionReport` chegam e percorrem o pipeline de conciliação como antes
4. Reconexão automática emite nova subscription message com timestamp fresco
5. `BinanceListenKeyReconnectIntegrationTest` passa com o novo protocolo

---

## Invariantes preservados

- `ExecutionReportProcessor` e pipeline de conciliação não mudam — apenas o unwrap externo da Etapa 4
- Reconexão continua sendo gerenciada pelo `ConnectionFailedHandler` existente; `session.close()` é no-op para listen key, e a nova `open()` entrega URL nova + subscription message fresca
- Fronteira arquitetural mantida: `wsApiBaseUrl` não vaza para o core; `BinanceConnectionConfig` não é alterado
