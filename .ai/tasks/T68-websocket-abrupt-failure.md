# T68 — Falha abrupta do WebSocket não reconecta (T53 F15–F18)

**Complexidade:** Média
**Responsável:** Claude
**Dependências:** T53 (rodada 11), T56 (coordenador de market data), T25 (rejeições consecutivas), T49 (comandos do operador)
**Status:** Em andamento

---

## Descrição

Na rodada 11 da T53 (72h), depois de 55h47 limpas, os canais `USER_DATA` e `MARKET` caíram com
`EOFException` sem mensagem e nunca reconectaram. O envio de ordens ficou bloqueado, um runner parou por
rejeições, a marcação a mercado e a proteção do lote ficaram sem preço, e só um restart recuperaria. O estado
operacional seguiu dizendo que os streams estavam conectados, e o cancelamento do operador devolveu `202` sem
cancelar nada. O monitor da rodada tinha caído junto com o SSH e não avisou ninguém.

## Contexto técnico

- `ConnectionResultDto.failure(reason, connectionId, cause)` monta `Map.of` com `cause.getMessage()`. Mensagem
  nula dá `NullPointerException` (linha 109).
- `ConnectionFailedHandler` aborta antes de `setConnectionResult` e `scheduleReconnect`; o status fica
  `CONNECTED`. `InspectOperationalStateUseCase.streamDisconnected` e `EnsureMarketDataFeedUseCase` leem esse status.
- `ConnectionLostOrderDispatchGuard` roda antes do handler e bloqueia o envio; só uma nova conexão libera.
- `OrderDispatchAdapter.cancel` com envio bloqueado só loga e retorna. `dispatch` rejeita a ordem depois do `202`
  do fechamento de posição.
- `LinearBackoffReconnectionStrategy.reconnectMarketStream` não confere o estado atual. Com a F15 corrigida, o
  coordenador (a cada 30s) e a tarefa agendada (5s) passam a disputar a reconexão. A tarefa que chega depois
  encontra `CONNECTED`, `prepareMarketManager` lança transição inválida e ela repete até `maxAttempts`, publicando
  falha crítica com a conexão saudável.
- `ConnectionFailedHandler` e o guard não ignoram evento de conexão superada, ao contrário de
  `ConnectionClosedHandler` e do gauge. Uma falha tardia do socket antigo marcaria a conexão nova como `ERROR`.

## Solução

Responsáveis: regra em `core`; infraestrutura e operação em `spring-application` e `deploy/vps`.

1. **F15 — registro da falha.** Uma única `failure` com causa, tolerante a mensagem nula (a outra sobrecarga
   delega). `errorMessage` passa a ser a mensagem ou o tipo da exceção.
2. **F15 — evento superado.** Predicado único em `WebSocketConnectionManager` (`isSupersededBy(connectionId)`),
   usado por `ConnectionClosedHandler`, `ConnectionFailedHandler` e `ConnectionLostOrderDispatchGuard`.
3. **F15 — uma reconexão por vez.** A tarefa agendada não faz nada se a conexão já está `CONNECTED`, `CONNECTING`
   ou `RECONNECTING`, nos dois canais.
4. **F16 — evidência.** Em `EnsureMarketDataFeedUseCase`, assinatura recusada pelo socket marca a conexão como
   `ERROR`, e o ciclo seguinte reabre em vez de repetir para sempre.
5. **F16 — estado operacional.** Novo check `ORDER_DISPATCH_BLOCKED` (`CRITICAL`), por exchange com runner `ACTIVE`,
   independente do status das conexões. Procedimento no runbook.
6. **F16 — alerta.** Regra do Grafana para `websocket_connection_state` em 0 por 2 min.
7. **F17 — comandos do operador.** `cancelOrder` e `closePosition` recusam com envio bloqueado
   (`409 EXCHANGE_UNAVAILABLE`) em vez de `202`. O `SHOULD_CANCEL` da estratégia, o Safe Mode e a proteção do lote
   seguem como estão (o tick seguinte tenta de novo).
8. **F18 — monitor.** `round-watch.sh` passa a rodar na VPS, desacoplado, até o fim da rodada: grava
   `watch.log`, grava `ANOMALY` na primeira anomalia e avisa no Discord a cada mudança do tipo de anomalia.
   `round-start.sh` o inicia junto com o fechamento. O argumento de lote some (um caminho só).

## Fora de escopo

- `attemptCount` do evento de falha é sempre 1, então uma queda longa tenta a cada 5s sem escalar para a falha
  crítica. Bom para disponibilidade; não muda aqui.
- Lacuna de fills durante a queda: já coberta pelo watchdog por REST (observado na rodada 11).

## Decisão de negócio pendente

**Rejeição por envio bloqueado conta para o `HALT` de rejeições consecutivas (T25)?** Hoje conta. Com sinal a cada
tick e reconexão em 5s, uma queda curta pode parar um runner que esteja tentando comprar, com retomada manual. É
regra de risco: não muda sem o mantenedor. A queda provocada desta task mede o efeito.

## Riscos

- Mudança de contrato: `cancel` e `close` passam de `202` a `409` com o envio bloqueado. Aprovada pelo mantenedor
  ao pedir a correção da F17 (2026-11-05). Consumidor conhecido: `round-close.sh`, que só registra o código.
- Reconexão de `USER_DATA` real: validar em testnet, com queda provocada.

## Critérios de aceitação

- [ ] `EOFException` sem mensagem leva a `ERROR` e agenda reconexão (teste).
- [ ] Falha de conexão superada não altera o estado nem bloqueia o envio (teste).
- [ ] Tarefa de reconexão não age com a conexão já recuperada e não publica falha crítica (teste).
- [ ] Assinatura recusada marca `ERROR` e o ciclo seguinte reabre (teste).
- [ ] `ORDER_DISPATCH_BLOCKED` acusa com envio bloqueado e runner `ACTIVE` (teste).
- [ ] `cancel` e `close` devolvem `EXCHANGE_UNAVAILABLE` com envio bloqueado (teste).
- [ ] Testnet: queda provocada nos dois canais reconecta sozinha, libera o envio e as ordens voltam.
- [ ] Testnet: `round-watch.sh` desacoplado grava `ANOMALY` e avisa no Discord; o aviso é conferido pelo mantenedor.
- [ ] Nova rodada de 72h na T53.

## Fontes

- `.ai/tasks/T53-testnet-soak.md` (rodada 11, F15–F18)
- `docs/OPERATIONS_RUNBOOK.md`, `.ai/flows/websocket-event-routing.md`
