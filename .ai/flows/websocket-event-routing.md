# WebSocket Event Routing

## Objetivo

Descrever como mensagens WebSocket entram na aplicacao, viram eventos raw,
sao processadas por adapters de exchange e chegam aos listeners de preco ou
ordem.

Este fluxo e a ponte entre transporte externo e casos de uso do core. Ele deve
preservar a separacao entre payload externo, DTO interno e regra de negocio.

## Quando Consultar

- Bugs em mensagens WebSocket que nao chegam aos use cases.
- Mudancas em `OkHttp3WebSocketAdapter` ou `OkHttp3ListenerConverter`.
- Alteracoes em `ProcessMessageHandler` ou `ProcessUserMessageHandler`.
- Problemas de roteamento entre market stream e user data stream.
- Mudancas em listeners de preco ou ordem.
- Review de novas exchanges/adapters WebSocket.

## Entradas E Saidas

- Entrada de conexao market: `ConnectMarketStreamUseCase`.
- Entrada de conexao user data: `ConnectUserStreamUseCase`.
- Entrada raw: mensagem WebSocket como `String`.
- Saida de processamento: `MarketDataDto` ou `OrderDataDto`.
- Efeitos colaterais: notificacao de `PriceUpdateListener` ou
  `OrderUpdateListener`; atualizacao de estatisticas/estado da conexao.

## Canais

O roteamento separa dois canais:

- `StreamChannel.MARKET`: market data e, em alguns adapters, mensagens de ordem
  por canal publico/geral.
- `StreamChannel.USER_DATA`: eventos privados de conta/ordem.

`OkHttp3ListenerConverter` escolhe o evento raw pelo canal:

- `MARKET` -> `RawMarketMessageReceivedEvent`.
- `USER_DATA` -> `RawUserDataMessageReceivedEvent`.

## Fluxo Market Stream

1. `ConnectMarketStreamUseCase` recebe exchange e `StreamSubscriptionRequest`.
2. Busca `ExchangeStreamingPort` no `ExchangeAdapterRepositoryPort`.
3. Registra `ConnectionKey.market(exchangeName)`.
4. Salva o request no historico da conexao.
5. Pede ao adapter a URL com `buildConnectionUrl`.
6. Busca `WebSocketPort` no `WebSocketPortRegistryPort`.
7. Chama `webSocket.connect(url, exchangeName, connectionId)`.
8. `OkHttp3ListenerConverter` publica `WebSocketConnectedEvent` no `onOpen`.
9. `ConnectionStateEventListener` chama handlers de conexao.
10. `PostConnectionEstablishHandler` envia mensagem de subscribe se o adapter
    exigir post-connection.
11. Mensagens recebidas publicam `RawMarketMessageReceivedEvent`.
12. `ProcessMessageEventListener` (no `marketDataExecutor`, thread unica — T42) chama `ProcessMessageHandler`.
13. `ProcessMessageHandler` processa a mensagem via `ExchangeStreamingPort`.
14. Resultado `MarketDataDto` notifica `PriceUpdateListener`.
15. Resultado `OrderDataDto` notifica `OrderUpdateListener`.

## Fluxo User Data

1. `ConnectUserStreamUseCase` abre a conexao privada e cria a sessao de user
   stream.
2. O WebSocket usa listener convertido com canal `USER_DATA`.
3. Mensagens recebidas publicam `RawUserDataMessageReceivedEvent`.
4. `ProcessUserMessageEventListener` (no `userDataExecutor`, separado do market data — T42) chama
   `ProcessUserMessageHandler`.
5. O handler processa via `ExchangeUserStreamPort`.
6. Resultado processavel precisa conter `OrderDataDto`.
7. O handler notifica apenas `OrderUpdateListener`.

## Executores (T42)

| Executor | Uso | Cheio |
|---|---|---|
| `marketDataExecutor` (1 thread, fila 10.000) | parsing de market data e respostas da WS API; roteia ticks para as mailboxes | descarta com log ERROR (nao bloqueia o socket) |
| `userDataExecutor` (2–4 threads, fila 1.000) | fills, cancels, rejects | `CallerRunsPolicy` — fill nunca se perde |
| `runnerSignalExecutor` (`runner.signal.threads`) | avaliacao dos runners (mailbox) | fila ilimitada, no maximo uma tarefa por runner |

Fill nunca espera tick; nenhuma avaliacao de estrategia roda na thread do socket.

## Listeners Registrados

`InMemoryListenerRepository` registra listeners padrao no `@PostConstruct`:

- `MarketDataPriceUpdateListener`: logging/cache simples de ultimo preco.
- `PortfolioStrategyRunnerPriceUpdateListener`: chama `ProcessTradeSignalPort`.
- `PortfolioStrategyRunnerOrderUpdateListener`: chama `OrderConciliationPort`.

Isso conecta:

- market data -> `runner-signal-processing.md`;
- order update -> `order-conciliation.md`.

## Estado Da Conexao

Eventos de conexao sao publicados pelo listener convertido:

- `WebSocketConnectedEvent`
- `WebSocketClosingEvent`
- `WebSocketClosedEvent`
- `WebSocketFailedEvent`

`ConnectionStateEventListener` delega para handlers do core. Falhas podem
acionar reconnect por `ConnectionFailedHandler`, usando historico de request para
market stream e nova sessao para user data stream. Evento de conexao ja substituida e ignorado
no handler e no guard de envio (`isSupersededBy`), e a tarefa de reconexao nao age se o coordenador
(T56) ja recuperou a conexao (T68).

O mesmo listener atualiza o gauge `websocket.connection.state{exchange,channel}` via
`WebSocketConnectionStateGauge` (T23 G1): `1` em `WebSocketConnectedEvent`, `0` em
`WebSocketFailedEvent`/`WebSocketClosedEvent`/`WebSocketDisconnectedEvent`. Combinar
gauge=1 com ausencia de mensagens detecta conexao "ghost" (OPEN mas silenciosa).
Catalogo de indicadores/alertas em `/.ai/monitoring-spec.md`.

## Variacoes E Falhas

- Adapter de streaming ausente: `ConnectMarketStreamUseCase` retorna falha.
- WebSocket registrado ausente: erro de configuracao ao conectar/enviar.
- Mensagem raw nula/vazia: handlers rejeitam com erro.
- Adapter retorna erro/warning sem payload: estatistica de erro da conexao e
  atualizada e listeners nao sao notificados.
- Listener individual com exception: erro e logado, mas os demais listeners
  continuam sendo chamados.
- User data nao aceita payload diferente de `OrderDataDto`; tipos inesperados sao
  descartados com warning.

## Componentes Principais

| Componente | Papel |
| --- | --- |
| `core/src/main/java/com/marmitt/core/application/usecase/websocket/ConnectMarketStreamUseCase.java` | Abre conexao de market stream e guarda historico de subscription. |
| `core/src/main/java/com/marmitt/core/application/usecase/websocket/ConnectUserStreamUseCase.java` | Abre conexao privada de user data stream. |
| `core/src/main/java/com/marmitt/core/application/usecase/websocket/SendMessageWebSocketUseCase.java` | Formata/envia mensagens usando adapter da exchange. |
| `spring-application/src/main/java/com/marmitt/application/spring/adapter/OkHttp3WebSocketAdapter.java` | Implementa `WebSocketPort` com OkHttp. |
| `spring-application/src/main/java/com/marmitt/application/spring/adapter/OkHttp3ListenerConverter.java` | Converte callbacks OkHttp em eventos internos. |
| `spring-application/src/main/java/com/marmitt/application/spring/event/RawMarketMessageReceivedEvent.java` | Evento raw para market stream. |
| `spring-application/src/main/java/com/marmitt/application/spring/event/RawUserDataMessageReceivedEvent.java` | Evento raw para user data stream. |
| `spring-application/src/main/java/com/marmitt/application/spring/handler/ProcessMessageEventListener.java` | Listener Spring async para market messages. |
| `spring-application/src/main/java/com/marmitt/application/spring/handler/ProcessUserMessageEventListener.java` | Listener Spring async para user data messages. |
| `core/src/main/java/com/marmitt/core/application/handler/ProcessMessageHandler.java` | Processa market raw e notifica listeners de preco/ordem. |
| `core/src/main/java/com/marmitt/core/application/handler/ProcessUserMessageHandler.java` | Processa user data raw e notifica listeners de ordem. |
| `spring-application/src/main/java/com/marmitt/application/spring/repository/InMemoryListenerRepository.java` | Registra listeners padrao. |
| `spring-application/src/main/java/com/marmitt/application/spring/repository/InMemoryWebSocketPortRegistry.java` | Separa portas WebSocket de market e user stream. |
| `spring-application/src/main/java/com/marmitt/application/spring/repository/InMemoryExchangeAdapterRepository.java` | Registra capacidades dos adapters de exchange. |

## Invariantes

- Payload externo deve ser traduzido por adapter antes de tocar listeners de
  dominio.
- `MARKET` e `USER_DATA` usam chaves e portas WebSocket separadas.
- Market stream pode notificar preco e ordem; user data deve notificar somente
  ordem processavel.
- Falha em um listener nao pode impedir notificacao dos demais.
- Reconnect de market depende do historico de subscription; limpar esse historico
  quebra recuperacao automatica.
- O core conhece contratos (`ExchangeStreamingPort`, `ExchangeUserStreamPort`),
  nao detalhes de OkHttp ou payload Binance.

## Validacao

- `spring-application/src/test/java/com/marmitt/application/spring/userdata/UserDataStreamConciliationIntegrationTest.java`
- `spring-application/src/test/java/com/marmitt/application/spring/userdata/BinanceListenKeyReconnectIntegrationTest.java`
- `spring-application/src/test/java/com/marmitt/application/spring/bootstrap/ProcessTradeSignalMockIntegrationTest.java`
- `spring-application/src/test/java/com/marmitt/application/spring/bootstrap/OrderLifecycleMockIntegrationTest.java`
- Comando recomendado:
  `./scripts/gradle-run.ps1 -q :core:test :spring-application:test`

## Fontes

- `/.ai/flows/runner-signal-processing.md`.
- `/.ai/flows/order-conciliation.md`.
- `core/src/main/java/com/marmitt/core/application/usecase/websocket/`.
- `core/src/main/java/com/marmitt/core/application/handler/`.
- `spring-application/src/main/java/com/marmitt/application/spring/adapter/`.
- `spring-application/src/main/java/com/marmitt/application/spring/handler/`.
