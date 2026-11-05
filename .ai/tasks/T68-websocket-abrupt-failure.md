# T68 — Falha abrupta do WebSocket não reconecta (T53 F15–F18)

**Complexidade:** Média
**Responsável:** Claude
**Dependências:** T53 (rodada 11), T56 (coordenador de market data), T25 (rejeições consecutivas), T49 (comandos do operador)
**Status:** Em andamento (PR #175 mergeado; falta validar em testnet o critério da decisão abaixo e a rodada de 72h)

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

## Decisão registrada (2026-11-05, mantenedor): envio bloqueado não conta para o `HALT`

Com sinal a cada tick e reconexão em ~6 s, uma queda curta gerou até 4 de 5 rejeições num runner (`SoakReversion`,
rodada curta 68). Opção escolhida: rejeição por conexão caída não conta para o `HALT` de rejeições consecutivas (T25).

**Desenho:** com o envio bloqueado, `ProcessTradeSignalUseCase.processTradeSignal` descarta o sinal antes de criar a
transação (`SignalDecision.DISPATCH_BLOCKED`) e o tick seguinte decide de novo. Assim não há rejeição a contar, e uma
queda longa não cria uma transação `REJECTED` por tick, que era o que o `HALT` interrompia. `ConsecutiveRejectionGuard`
não muda: recusas da exchange seguem contando. Sobra uma corrida: ordem que passou por este ponto e encontrou o
bloqueio no envio ainda é rejeitada e conta, no máximo uma por runner a cada queda. O mesmo portão vale para a
proteção do lote e o `PANIC_SELL`, que não teriam como sair e tentam de novo no tick seguinte.

## Riscos

- Mudança de contrato: `cancel` e `close` passam de `202` a `409` com o envio bloqueado. Aprovada pelo mantenedor
  ao pedir a correção da F17 (2026-11-05). Consumidor conhecido: `round-close.sh`, que só registra o código.
- Reconexão de `USER_DATA` real: validar em testnet, com queda provocada.

## Critérios de aceitação

- [x] `EOFException` sem mensagem leva a `ERROR` e agenda reconexão (teste).
- [x] Falha de conexão superada não altera o estado nem bloqueia o envio (teste).
- [x] Tarefa de reconexão não age com a conexão já recuperada e não publica falha crítica (teste).
- [x] Assinatura recusada marca `ERROR` e o ciclo seguinte reabre (teste).
- [x] `ORDER_DISPATCH_BLOCKED` acusa com envio bloqueado e runner `ACTIVE` (teste).
- [x] `cancel` e `close` devolvem `EXCHANGE_UNAVAILABLE` com envio bloqueado (teste).
- [x] Testnet: queda provocada nos dois canais reconecta sozinha, libera o envio e as ordens voltam (rodada curta 68).
- [x] Sinal durante o bloqueio não cria ordem nem para o runner (teste E2E e rodada curta 69: 16 quedas provocadas em
  ~9 min, 16 reconexões, 9 sinais descartados, 0 rejeições, 5 runners `ACTIVE`, conciliação 20 de 20).
- [x] Testnet: `round-watch.sh` desacoplado grava `ANOMALY` e avisa no Discord (`204`, rodadas 68 e 69). Falta o
  mantenedor confirmar que a mensagem chegou.
- [ ] Nova rodada de 72h na T53.

## Fontes

- `.ai/tasks/T53-testnet-soak.md` (rodada 11, F15–F18)
- `docs/OPERATIONS_RUNBOOK.md`, `.ai/flows/websocket-event-routing.md`
