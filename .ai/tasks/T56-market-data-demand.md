# T56 — Demanda de Market Data: Conexões e Assinaturas Automáticas

**Complexidade:** Média
**Responsável:** Claude
**Dependências:** T55 (estado consolidado: `MARKET_DATA_STALE`, `STREAM_DISCONNECTED`; runbook).
Follow-up registrado na T55. Pré-requisito prático da T53 (soak de 24h).
**Status:** Concluído (2026-10-20) — ver "Entrega" no fim.

---

## Descrição

A alimentação de cotações e o gerenciamento de transações são desacoplados por publicação/assinatura:

- **Feed:** conexões websocket, assinaturas e listeners recebem mensagens da exchange e publicam ticks
  por símbolo e fonte. Não sabem que runners existem.
- **Runners:** cada tick chega a `ProcessTradeSignalUseCase.execute`, que procura os runners
  operacionais do símbolo e da fonte (`findOperationalBySymbol`) e entrega na mailbox de cada um. Não
  sabem como o tick chegou.

O desacoplamento é desejado e fica. O que falta é a **demanda**: nenhuma parte declara quais símbolos
precisam estar chegando. Hoje alguém chama `POST /api/websocket/connect` e `.../market-data/subscribe`
à mão; depois de um restart (ou de uma reconexão que perde assinaturas), os runners ficam sem tick até
alguém lembrar. Para um sistema operado por agente e para rodadas de soak de 24h, é o ponto mais frágil.

Esta task cria um **coordenador de demanda**: deriva dos runners o que precisa estar chegando, compara
com a evidência do que está chegando e abre ou assina o que falta. Nem o feed nem os runners passam a
conhecer um ao outro — só o coordenador conhece os dois, pelos ports.

## Contexto técnico

- `core/.../usecase/runner/processsignal/ProcessTradeSignalUseCase.execute` — roteia tick por
  `findOperationalBySymbol(symbol, exchange)`; `RunnerSignalPolicy.canProcessRunner` filtra pela fonte
  (`allowedMarketDataSources`).
- `core/.../usecase/websocket/ConnectMarketStreamUseCase` (`ConnectMarketStreamPort`) — abre a conexão
  de market data com os pares do `StreamSubscriptionRequest` na URL; registra a conexão em
  `WebSocketConnectionRepositoryPort` (`ConnectionKey.market(exchange)`).
- `core/.../usecase/websocket/ConnectUserStreamUseCase` (`ConnectUserStreamPort`) — abre o stream de
  usuário quando o adapter tem (`ExchangeAdapterDescriptor.hasUserStream`). O
  `POST /api/websocket/connect` já chama os dois.
- `core/.../usecase/websocket/SendMessageWebSocketUseCase` (`SendMessageWebSocketPort`) — envia
  `StreamSubscriptionRequest` (`SUBSCRIBE`/`UNSUBSCRIBE`) numa conexão aberta.
- **Estado de assinatura não é guardado de forma explícita:** só um histórico de pedidos por conexão
  (`WebSocketConnectionManager.requestHistory`). Uma reconexão (`LinearBackoffReconnectionStrategy`)
  pode não refazer as assinaturas feitas depois do `connect`.
- `LatestPricePort.findUpdatedAt(symbol)` (T55) — quando o último tick do símbolo chegou.
- MOCK: o feed simulado (`MockMarketDataFeedEngine.subscribe`) é ligado pelo mesmo caminho de
  conexão/assinatura do adapter; um caminho só para MOCK e Binance.

## Solução proposta

1. **Demanda (core, regra de negócio).** Conjunto derivado dos runners operacionais (`ACTIVE` e
   `TERMINATING`, que ainda vende):
   - market data: para cada runner, `(fonte, símbolo)` para cada fonte em `allowedMarketDataSources`;
   - stream de usuário: cada `exchangeId` de runner operacional cujo adapter tem `hasUserStream`.
2. **Evidência em vez de registro.** Para não depender de um estado de assinatura que hoje não existe e
   que uma reconexão perde:
   - conexão de market data da fonte ausente ou não `CONNECTED` (e não `CONNECTING`/`RECONNECTING`) →
     abrir com **todos** os símbolos demandados daquela fonte;
   - conexão `CONNECTED` e símbolo demandado sem tick há mais que o limiar (o `market-data-stale-ms` da
     T55, 60s) → enviar `SUBSCRIBE` do símbolo;
   - conexão `CONNECTED` e símbolo demandado que ela nunca recebeu como pedido (histórico de pedidos da
     própria conexão) → enviar `SUBSCRIBE` na hora, sem esperar o limiar (T53 F27, largada com vários
     símbolos);
   - stream de usuário demandado ausente ou não conectado → abrir.
   Conexão em `CONNECTING`/`RECONNECTING` é deixada em paz (a reconexão está trabalhando).
3. **Reconciliador (core use case + scheduler Spring).** `EnsureMarketDataFeedUseCase`: no boot, a cada
   **30s** e logo depois de `start`/`resume` de runner. Idempotente; cada ação vira log `INFO` com o
   motivo (ex.: `marketData: subscribing BTCUSDT on BINANCE - no tick for 75s`).
4. **Tipo de stream:** `TICKER` por padrão, configurável por exchange.
5. **Sem cancelar assinatura** quando nenhum runner precisa mais do símbolo (primeira versão): o tick a
   mais é inofensivo e cancelar abre corrida com runner que volta.
6. **Endpoints manuais de websocket ficam**, protegidos, para diagnóstico; o caminho normal passa a ser
   o automático.
7. **Runbook:** a seção "Partida depois de um boot" deixa de ter passos manuais (vira "conferir que o
   reconciliador abriu"); os procedimentos de `MARKET_DATA_STALE` e `STREAM_DISCONNECTED` passam a
   começar por "aguardar um ciclo do reconciliador". Corrige também o passo redundante atual (o
   `connect` já abre o stream de usuário).

## Decisões registradas (2026-10-20)

1. Não cancelar assinaturas sobrando na primeira versão.
2. `TICKER` como tipo de stream padrão, configurável por exchange.
3. Ciclo de 30s, mais execução imediata depois de `start`/`resume`.
4. Endpoints manuais de websocket mantidos, protegidos, para diagnóstico.

## Fora do escopo

- Cancelar assinaturas sem demanda.
- Registro explícito de assinaturas por conexão.
- Mudar o roteamento de ticks ou o contrato entre feed e runners.

## Riscos

- **Preço indexado só por símbolo** (`LatestPricePort`): com duas fontes para o mesmo símbolo, um tick de
  uma fonte esconde a falta da outra. Hoje cada runner usa uma fonte; registrar como limitação.
- **Assinatura repetida:** reenviar `SUBSCRIBE` de símbolo já assinado precisa ser inofensivo na
  Binance e no MOCK — confirmar na implementação.
- **Símbolo com tick raro:** o limiar de 60s pode reassinar à toa um par pouco negociado; com `TICKER` na
  Binance o tick é por segundo.
- **Reconexão em andamento:** o reconciliador não pode abrir conexão concorrente com a estratégia de
  reconexão; por isso ignora `CONNECTING`/`RECONNECTING`.

## Critérios de aceitação

1. Depois do boot, sem nenhuma chamada manual, os runners operacionais recebem ticks e o stream de
   usuário fica conectado onde o adapter tem um.
2. Runner novo iniciado (`start`) para um símbolo ainda não assinado recebe tick sem ação manual.
3. Conexão caída e não recuperada pela reconexão é reaberta pelo ciclo seguinte; símbolo sem tick
   recebe nova assinatura.
4. Nada é aberto ou assinado sem runner operacional que precise; reexecutar o ciclo sem mudança não gera
   ação.
5. `MARKET_DATA_STALE` e `STREAM_DISCONNECTED` voltam a `OK` sozinhos depois de um restart.
6. Runbook atualizado: partida sem passos manuais.

## Validação

- Unitários do use case: demanda derivada (fontes, `TERMINATING`, `hasUserStream`), conexão ausente,
  conexão conectada sem tick, conexão em reconexão ignorada, idempotência.
- Integração com o MOCK: subir o contexto com runner `ACTIVE`, sem chamar `connect`, e provar que o tick
  chega ao runner.

## Fontes

- `.ai/tasks/T55-ai-operability.md` (follow-up registrado)
- `docs/OPERATIONS_RUNBOOK.md` (Partida depois de um boot, `market-data-stale`, `stream-disconnected`)
- `.ai/product-direction.md` (sistema operado por IA)

## Entrega

### Decisões de implementação (2026-10-20)

- **`EnsureMarketDataFeedUseCase`** (core, `EnsureMarketDataFeedPort`): demanda dos runners `ACTIVE` e
  `TERMINATING`; abre conexão ausente ou caída (`IDLE`, `DISCONNECTED`, `CLOSED`, `ERROR`) com todos os
  símbolos da fonte; com a conexão `CONNECTED` há mais que o limiar, reassina símbolo sem tick há mais
  que o limiar; deixa `CONNECTING`/`RECONNECTING` em paz. O limiar é o mesmo `market-data-stale-ms` do
  estado consolidado (T55): o que a T55 acusa, a T56 corrige. Assinar de novo é inofensivo no MOCK
  (`computeIfAbsent`) e na Binance (assinatura repetida não gera erro).
- **Gatilho imediato sem acoplar:** `ManageRunnerLifecycleUseCase` avisa um `RunnerActivationObserverPort`
  depois de `start`/`resume` que deixa o runner `ACTIVE`; a composição liga o coordenador nele. O ciclo
  de vida não sabe que o feed existe; falha do observador não desfaz a transição.
- **Spring:** `MarketDataFeedScheduler` (5s após subir, depois a cada 30s), `MarketDataFeedProperties`
  (`ctrade.market-data.auto-feed.*`: `enabled`, `interval-ms`, `default-stream-type` = `TICKER`,
  `stream-types` por exchange).
- **Testes de integração:** o coordenador fica desligado por padrão nos testes
  (`CTRADE_MARKET_DATA_AUTO_FEED_ENABLED=false` no `AbstractIntegrationTest`), porque o feed do MOCK
  publica 1 tick/s e quebraria os E2E determinísticos. Desliga pela variável do placeholder para que um
  teste possa religar pela chave direta — o `@DynamicPropertySource` da superclasse é aplicado depois e
  venceria a mesma chave.

### Validação

- `EnsureMarketDataFeedUseCaseTest`: demanda (ACTIVE e TERMINATING, nunca HALTED), conexão ausente,
  conectada com tick recente (nada a fazer), símbolo sem tick reassinado, conexão recém-aberta ganha
  tempo, reconexão em andamento ignorada, conexão caída reaberta, exchange sem stream de usuário.
- `ManageRunnerLifecycleUseCaseTest`: `start` avisa o observador.
- `MarketDataAutoFeedIntegrationTest` (Postgres + MOCK): sem chamar `/api/websocket`, a conexão do MOCK
  é aberta, o tick chega e o runner avalia.
- Runbook: partida sem passos manuais; `market-data-stale` e `stream-disconnected` começam pelo
  coordenador.

