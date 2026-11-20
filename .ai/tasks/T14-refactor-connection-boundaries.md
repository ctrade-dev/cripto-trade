# T14 — Refactor Fronteiras Arquiteturais: Transporte e Reações de Negócio

**Complexidade:** Alta  
**Responsável:** Claude  
**Dependências:** T12 (remove ListenKeyManager e HttpClientPort do ciclo de user data), T13 (valida o padrão de ordem via WebSocket)  
**Status:** Concluído

---

## Contexto

A exploração da arquitetura de conexão revelou duas classes de problemas:

**1. Infraestrutura vazando para o core**

`ConnectionFailedHandler` vive no core mas contém lógica puramente de transporte: `ScheduledExecutorService`, backoff linear (`5s * attempt`), contagem de tentativas, scheduling de reconexão. O core passou a ser o orquestrador de retry de WebSocket — responsabilidade que pertence à camada de infraestrutura.

`HttpClientPort` é um port outbound do core que existe apenas para servir detalhe HTTP de adapter. Após T12 (remoção do `ListenKeyManager`), o único uso restante é nos adapters Binance para chamadas REST. Um port de transporte HTTP não deveria existir no core.

**2. Reações de negócio ausentes**

Quando uma conexão cai ou não reconecta após N tentativas, o core não toma nenhuma decisão de negócio: runners continuam tentando despachar ordens, nenhum kill switch é acionado, nenhuma pausa é aplicada. Os handlers existentes fazem apenas transição de estado — não há consequência para o domínio de trading.

**Separação que guia o refactor:**

| Responsabilidade | Onde deve viver |
|---|---|
| Retry, backoff, scheduling de reconexão | spring-application (infraestrutura) |
| Estado da conexão (CONNECTING → CONNECTED → CLOSED) | core (state machine de domínio) |
| Pausa de runners ao perder conexão | core (regra de negócio) |
| Kill switch em falha crítica | core (regra de negócio) |
| Port HTTP de transporte | adapter-binance (detalhe de provider) |

**Roadmap de longo prazo (fora do escopo desta tarefa):**

Depois de T13 provar o padrão de `ExchangeOrderPort`, um refactor futuro pode:
- Mover `WebSocketPort` para dentro dos adapters no path de ordens (core não envia frames WebSocket diretamente)
- Renomear `WebSocketConnectedEvent` → `ExchangeStreamEstablishedEvent` (eventos nomeados por domínio, não por transporte)
- Unificar `ExchangeStreamingPort` + `ExchangeOrderExecutionPort` em `ExchangeOrderPort`

Essas mudanças são de maior amplitude e dependem de T13 estar validado em produção. T14 trata apenas o que está errado hoje.

---

## Etapas

### Etapa 1 — Extrair lógica de reconexão do core: `ReconnectionStrategyPort`

**Problema:** `ConnectionFailedHandler` tem `ScheduledExecutorService`, constantes `RECONNECT_DELAY_SECONDS = 5` e `MAX_RECONNECT_ATTEMPTS = 10`, e o loop inteiro de retry com chamadas a `connectMarketStreamPort` e `connectUserStreamPort`.

**Solução:** Novo port outbound no core + implementação em spring-application.

**Arquivos — core:**
- Novo `core/.../ports/outbound/connection/ReconnectionStrategyPort.java`:
  ```java
  public interface ReconnectionStrategyPort {
      void scheduleReconnect(String exchangeName, StreamChannel channel,
                             UUID sessionToClose, int attempt);
  }
  ```
- `ConnectionFailedHandler.java` — simplificar:
  - Mantém: registrar estado ERROR e log de falha
  - Remove: `ScheduledExecutorService`, backoff, `scheduleReconnect()`, `reconnectMarketStream()`, `reconnectUserStream()`
  - Adiciona: `ReconnectionStrategyPort reconnectionStrategy` como dependência, chama `reconnectionStrategy.scheduleReconnect(...)` após registrar estado

**Arquivos — spring-application:**
- Novo `spring-application/.../infrastructure/connection/LinearBackoffReconnectionStrategy.java`:
  - Implementa `ReconnectionStrategyPort`
  - Move o conteúdo atual de `scheduleReconnect()`, `reconnectMarketStream()`, `reconnectUserStream()` do `ConnectionFailedHandler`
  - Configuração externalizável: `reconnect.base-delay-seconds` e `reconnect.max-attempts` via properties
- `HandleConnectionConfig.java` — injetar `LinearBackoffReconnectionStrategy` no bean `ConnectionFailedHandler`

**Resultado:** core define "o que fazer ao falhar" (registrar estado, acionar estratégia); spring-application define "como reconectar" (backoff, scheduler, retry count).

---

### Etapa 2 — Reações de negócio faltantes a eventos de conexão

**Problema:** Handlers atuais fazem apenas transição de estado. Não há consequência para o domínio de trading quando a conexão cai ou não reconecta.

**Casos de negócio a implementar:**

| Evento | Reação de negócio |
|---|---|
| `WebSocketFailedEvent` com `isCritical = true` (≥ 5 tentativas) | Acionar kill switch automaticamente |
| `WebSocketClosedEvent` inesperado (MARKET ou USER_DATA) | Pausar despacho de novas ordens |
| `WebSocketConnectedEvent` com `wasReconnection = true` | Retomar despacho de ordens |

**Arquivos — core:**
- Novo `core/.../handler/connection/CriticalConnectionFailureHandler.java`:
  - Escuta `WebSocketFailedEvent` onde `event.isCritical()` (já calculado como `attemptCount >= 5`)
  - Chama `HaltAllRunnersPort` (kill switch já existente de T8)
  - Log de auditoria: "exchange={} channel={} — conexão crítica, kill switch acionado"
- Novo `core/.../handler/connection/ConnectionLostOrderDispatchGuard.java`:
  - Escuta `WebSocketClosedEvent` inesperado (canal MARKET ou USER_DATA)
  - Sinaliza `OrderDispatchPort` para parar de aceitar novos despachos via flag em `ExchangeAdapterRepositoryPort`
  - Escuta `WebSocketConnectedEvent` com `wasReconnection = true` → remove flag, retoma despacho

**Arquivos — spring-application:**
- `ConnectionStateEventListener.java` — adicionar os dois novos handlers
- `HandleConnectionConfig.java` — registrar beans dos novos handlers

**Escopo limitado:** `ConnectionLostOrderDispatchGuard` só bloqueia novos despachos — ordens em voo não são afetadas. Kill switch via `CriticalConnectionFailureHandler` usa o mesmo mecanismo do T8.

---

### Etapa 3 — `HttpClientPort` fora do core (pós-T12)

**Problema:** `HttpClientPort` é um port outbound definido no core, mas o core não tem nenhuma necessidade de negócio de fazer HTTP — é detalhe de transporte de adapters. O port está no lugar errado, não no nível de abstração errado.

**Princípio:** A abstração `HttpClientPort` é válida e deve continuar existindo — ela isola os adapters da biblioteca HTTP concreta (OkHttp hoje, outra amanhã). O que muda é onde ela é declarada: não no core (domínio), mas em `spring-application` como port de infraestrutura compartilhada.

```
Antes:
  core/ define HttpClientPort          ← errado: core não precisa de HTTP
  adapter-binance/ usa HttpClientPort
  spring-application/ implementa (OkHttpClientAdapter)

Depois:
  spring-application/ define HttpClientPort   ← infraestrutura compartilhada
  adapter-binance/ usa HttpClientPort         ← sem mudança de contrato
  spring-application/ implementa (OkHttpClientAdapter)  ← sem mudança
```

Trocar OkHttp por outra biblioteca afeta apenas `OkHttpClientAdapter` em spring-application. Nenhum adapter precisa mudar.

**Arquivos:**
- `core/.../ports/outbound/http/HttpClientPort.java` — **MOVER** para `spring-application/.../ports/http/HttpClientPort.java` (mesmo contrato, pacote de infraestrutura)
- `adapter-binance` — atualizar imports para o novo pacote: `BinanceMarketStreamAdapter`, `BinanceRestRequestBuilder`
- `spring-application/.../adapter/binance/OkHttpClientAdapter.java` — atualizar referência ao port (mesmo arquivo, pacote local agora)
- Verificar: nenhuma classe em `core/` importa `HttpClientPort` após a mudança

**Pré-condição:** T12 deve estar merged (sem `ListenKeyManager`, sem uso de `HttpClientPort` no ciclo de user data).

---

### Etapa 4 — Testes e validação

**Cobertura mínima por etapa:**

| Etapa | Teste |
|---|---|
| 1 | `LinearBackoffReconnectionStrategy` unit test: verifica delay = `5 * attempt`, para em `maxAttempts`, chama `connectMarketStreamPort` e `connectUserStreamPort` nos canais corretos |
| 1 | `ConnectionFailedHandler` unit test: verifica que delega para `ReconnectionStrategyPort` e não tem scheduler próprio |
| 2 | `CriticalConnectionFailureHandler` unit test: `isCritical=true` → `HaltAllRunnersPort.execute()` chamado; `isCritical=false` → não chamado |
| 2 | `ConnectionLostOrderDispatchGuard` unit test: closed inesperado → flag de bloqueio setada; reconnection → flag removida |
| 3 | Compilação limpa sem referências a `core/.../http/HttpClientPort` |
| 3 | `OkHttpClientAdapter` integration test: comportamento HTTP inalterado após mudança de pacote |

---

## Critérios de aceitação

1. `ConnectionFailedHandler` não tem `ScheduledExecutorService` nem constantes de backoff
2. `LinearBackoffReconnectionStrategy` em spring-application tem o comportamento atual exato de reconexão
3. Falha crítica (≥ 5 tentativas) aciona kill switch automaticamente via `CriticalConnectionFailureHandler`
4. Despacho de ordens é bloqueado em fechamento inesperado de conexão e retomado na reconexão
5. `HttpClientPort` não existe em `core/` — apenas em `adapter-binance/`
6. Todos os testes existentes de reconexão passam (`BinanceListenKeyReconnectIntegrationTest` ou substituto pós-T12)

---

## Invariantes preservados

- State machine de conexão (CONNECTING → CONNECTED → CLOSING → CLOSED) permanece no core — é domínio
- `PostConnectionEstablishHandler` não muda — já está correto (envia subscrições pós-conexão)
- Kill switch de T8 é reutilizado, não duplicado
- Nenhuma mudança em `WebSocketPort`, `OkHttp3WebSocketAdapter` ou `OkHttp3ListenerConverter` — transporte físico não é tocado

---

## Roadmap pós-T14 (não escopo)

Após T13 validado em produção:
- `ExchangeOrderPort` unificado (adapter decide REST vs WebSocket internamente)
- `WebSocketPort` removido do core para path de ordens
- Eventos renomeados de `WebSocket*Event` para `ExchangeStream*Event`
- `ExchangeStreamingPort.formatMessage()` substituído por port semântico
