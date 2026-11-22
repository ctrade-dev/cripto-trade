# Operations Runbook — CTrade

## Propósito e como usar

O ctrade é operado por um **agente de IA** (`.ai/product-direction.md`). Este runbook é escrito para
ele: cada procedimento é uma sequência de chamadas com a resposta esperada e como confirmar que
resolveu. Um humano consegue seguir os mesmos passos.

- **Ponto de partida de toda verificação:** `GET /api/admin/operational-state`. Cada verificação que não
  está `OK` traz `runbook` com a âncora da seção deste documento (ex.: `#dlq-unresolved`).
- **Alertas** (Discord, Grafana) apontam para as mesmas seções — ver [Alertas](#alertas).
- **Mecanismos** (Safe Mode, drawdown, gatilhos, ciclo de vida, proteção do lote, catálogo) estão em
  [Referência](#referencia), para quem precisa entender o porquê.

## Convenções

```bash
CTRADE=http://<host>:8080          # base da API
KEY=<ADMIN_API_KEY>                # chave admin
# Leitura
curl -s -H "X-Admin-Key: $KEY" "$CTRADE/api/admin/operational-state"
# Comando: sempre com motivo; X-Operator identifica quem pediu
curl -s -X POST -H "X-Admin-Key: $KEY" -H "X-Reason: <motivo>" -H "X-Operator: <agente>" \
     "$CTRADE/api/admin/runners/<id>/halt"
```

- Toda chamada a `/api/**` exige `X-Admin-Key`; sem ela, `401 ADMIN_KEY_INVALID`. `/actuator` não exige.
- Todo comando (`POST`, `PUT`, `PATCH`, `DELETE`) exige `X-Reason`; sem ele, `400 REASON_REQUIRED` e nada
  é executado. `X-Operator` é opcional (fica `unknown`).
- Toda recusa traz `code` estável no `ApiError` (lista no OpenAPI, `/v3/api-docs`). Reagir pelo `code`,
  não pela mensagem.
- Repetir um comando já aplicado devolve o estado atual, sem efeito — é seguro repetir depois de timeout.
- Todo comando, aceito ou recusado, fica em `GET /api/admin/operator-actions` (`since`, `pathContains`,
  `limit`). Antes de agir sobre algo, consultar o que já foi feito nele:
  `GET /api/admin/operator-actions?pathContains=<id>`.
- O que o **sistema** fez sozinho (Safe Mode automático, halt de runner por rejeições, saída protetiva,
  DLQ, boot) e o que o operador fez estão juntos, em ordem, em `GET /api/admin/events` (`since`, `until`,
  `portfolioId`, `limit`; T59). Para "o que aconteceu desde X", começar por ela.
- Não existe endpoint de ordem direta na exchange: toda ordem nasce de um runner.
- **Ambiente de soak na VPS (testnet, T63):** a API só é alcançada por túnel SSH, e a `KEY` é a da VPS.
  Subir, atualizar, cadastrar as apps e limpar o banco: [`deploy/vps/README.md`](../deploy/vps/README.md).

<a id="ciclo-de-verificacao"></a>
## Ciclo de verificação

1. `GET /api/admin/operational-state`.
2. `status=OK`: nada a fazer.
3. Para cada verificação `CRITICAL`, depois cada `WARN`: abrir a seção do `runbook`, seguir o
   diagnóstico e a ação.
4. Confirmar: repetir o passo 1; a verificação tratada volta a `OK` (ou fica explicada no registro da
   ação). Se não voltar depois do procedimento, ver [Quando escalar](#quando-escalar).

<a id="limites-de-autonomia"></a>
## Limites de autonomia

> Confirmado pelo mantenedor em 2026-10-20. Mudanças nesta divisão são decisão dele.

| O agente faz sozinho | O agente pede confirmação ao mantenedor antes |
|---|---|
| Diagnosticar, consultar, reconectar streams, conferir estratégias | Subir o Safe Mode para `PANIC_SELL` (vende tudo) |
| Subir o Safe Mode para `HALT` ou `CANCEL_ALL` | Descer o Safe Mode de um HALT **automático** (drawdown, DLQ, divergência de saldo) |
| `halt` de runner; cancelar ordem LIMIT | Fechar posição à mão (`.../positions/{id}/close`) |
| Resolver/reprocessar DLQ quando a causa está identificada | Encerrar runner (`terminate`) |
| Habilitar/desabilitar estratégia, trocar endereço | Mudar limites de risco ou a política de liquidação do runner |

<a id="quando-escalar"></a>
### Quando escalar

Escalar ao mantenedor (Discord) com o `operational-state`, as ações tentadas e o resultado quando:
o procedimento não resolve; a ação está na coluna da direita acima; ou há sinal de perda financeira
sem explicação (PnL, saldo, divergência com a exchange).

---

<a id="partida-depois-de-um-boot"></a>
## Partida depois de um boot

O coordenador de market data (T56) abre sozinho as conexões e assinaturas que os runners `ACTIVE`,
`TERMINATING` e `HALTED` precisam: market data de cada fonte em `allowedMarketDataSources` e o stream de
usuário da exchange que tem um. O runner parado entra porque pode ter ordem LIMIT no livro, cujo
preenchimento só chega pelo stream de usuário, e porque a retomada exige os canais de pé (T53 F23); ele
continua sem negociar. Runner de outro status com lote aberto também tem o símbolo assinado (T58), só para o
lote ter preço (drawdown, PnL). Roda 5s depois do boot, a cada 30s (`ctrade.market-data.auto-feed.*`) e logo após `start`/`resume`.

1. `GET /api/boot/status` → esperado `status=SUCCESS`. Senão, ver [boot-not-ready](#boot-not-ready).
2. Aguardar até 1 minuto e conferir `GET /api/admin/operational-state`: `MARKET_DATA_STALE` e
   `STREAM_DISCONNECTED` em `OK`. Logs `marketData: connecting ...` / `subscribing ...` mostram o que foi
   aberto e por quê.
3. Não resolveu: ver [market-data-stale](#market-data-stale) ou [stream-disconnected](#stream-disconnected).

Conexão manual (diagnóstico, ou com `ctrade.market-data.auto-feed.enabled=false`):
`POST /api/websocket/connect`
`{"exchange":"BINANCE","symbols":[{"baseCurrency":"BTC","quoteCurrency":"USDT","streamType":"TICKER"}]}`
abre o market data e, se a exchange tiver, o stream de usuário. Canal já de pé (`CONNECTED`, `CONNECTING`,
`RECONNECTING`) fica como está e volta com o status atual, então o pedido serve para reabrir só o canal que
caiu.

---

## Procedimentos por verificação

<a id="boot-not-ready"></a>
### BOOT_NOT_READY — boot não concluído

- **Diagnóstico:** `GET /api/boot/status` → `status`, `failurePhase`, `failureMessage`, fases.
  `RUNNING`: aguardar e repetir. `NOT_STARTED`: orquestrador desligado (`runner.boot.orchestrator-enabled`).
- **Ação (`FAILED`):** pela fase:
  - `phase1.infrastructure`: exchange inacessível ou credencial inválida — conferir conectividade e
    chaves; reiniciar o ctrade depois de corrigir.
  - `phase2.sanity` com `DEFICIT`: a soma dos portfolios de uma exchange passa do saldo da conta. Todos
    os portfolios daquela exchange já foram para HALT automático. Diagnosticar como em
    [exchange-balance-divergence](#exchange-balance-divergence) e **escalar**.
  - `phase2.zombie` / `phase3`: ver os logs da fase; reiniciar depois de corrigir a causa.
- **Confirmar:** após restart, `GET /api/boot/status` → `SUCCESS` e a verificação em `OK`.

<a id="safe-mode-active"></a>
### SAFE_MODE_ACTIVE — portfolio fora de NORMAL

- **Diagnóstico:** `subjects` traz `level`, `origin`, `reason`, `since`.
  `GET /api/admin/portfolios/<id>/safe-mode` mostra o progresso (`restingOrders`, `liquidatingOrders`,
  `openPositions`, `idleRunners`).
  - `origin=AUTOMATIC`: um gatilho disparou (motivo em `reason`: `MAX_DRAWDOWN_EXCEEDED`,
    `DLQ_THRESHOLD`, `BALANCE_DIVERGENCE`, `WATCHDOG_STUCK`, `EXCHANGE_UNAVAILABLE`). Tratar a causa primeiro
    (seção [Gatilhos](#ref-gatilhos)). `EXCHANGE_UNAVAILABLE` volta sozinho com a exchange saudável
    ([Retomada automática](#ref-auto-resume)): não descer à mão.
  - `origin=MANUAL`: conferir em `GET /api/admin/operator-actions?pathContains=<portfolioId>` quem
    subiu e por quê.
- **Ação:** descer **um nível por vez** depois que a causa estiver resolvida:
  `PUT /api/admin/portfolios/<id>/safe-mode` `{"level":"<um abaixo>","confirm":false}` + `X-Reason`.
  Descer de HALT **automático** exige confirmação do mantenedor ([Limites](#limites-de-autonomia)), exceto o
  `EXCHANGE_UNAVAILABLE`, que o próprio sistema retoma. Mexer no Safe Mode de um HALT `EXCHANGE_UNAVAILABLE`
  desliga a retomada automática dele (a origem vira `MANUAL`).
- **Esperado:** `202` com o novo nível; `409 SAFE_MODE_STEP_TOO_LARGE` se pular nível.
- **Confirmar:** nível `NORMAL` e a verificação em `OK`.

<a id="capital-reserved-without-order"></a>
### CAPITAL_RESERVED_WITHOUT_ORDER — capital reservado sem BUY em voo

Capital preso: a reserva não vai voltar sozinha.

- **Diagnóstico:** `GET /api/portfolios/<id>/balance` (confirmar `reserved`);
  `GET /api/dead-letters?portfolioId=<id>` — uma liberação de reserva que falhou costuma estar na DLQ.
- **Ação:** se houver entrada da DLQ correspondente, seguir [dlq-unresolved](#dlq-unresolved)
  (reprocessar devolve a reserva). Sem DLQ: **escalar** — não há comando para mexer em saldo à mão.
- **Confirmar:** `reserved` igual à soma das BUYs em voo (zero sem nenhuma) e a verificação em `OK`.

<a id="capital-reservation-stale"></a>
### CAPITAL_RESERVATION_STALE — BUY em voo há mais de 24h

Normalmente uma LIMIT que a estratégia deixou longe do preço.

- **Diagnóstico:** `subjects` traz runner, transação, total e desde quando. Conferir se a estratégia
  ainda quer a ordem e o preço de mercado.
- **Ação (se a ordem não faz mais sentido):** `POST /api/admin/runners/<runnerId>/orders/<transactionId>/cancel`
  + `X-Reason`. Esperado `202`; o `CANCELED` chega pelo stream e devolve a reserva.
- **Confirmar:** a transação some de `subjects`; `reserved` cai pelo total da ordem.

<a id="transaction-stuck"></a>
### TRANSACTION_STUCK — ordem PENDING sem confirmação da exchange

O watchdog consulta a exchange e resolve sozinho na maioria dos casos. `CRITICAL` = passou de 3 ciclos.

- **Diagnóstico:** `EXCHANGE_REST_CIRCUIT_OPEN` e `STREAM_DISCONNECTED` estão `OK`? Se não, tratá-los
  primeiro (o watchdog depende deles). `GET /api/runners/<runnerId>/transactions` para o histórico.
- **Ação:** com a exchange acessível, aguardar 1 ciclo do watchdog (60s). Persistindo `CRITICAL`, o
  portfolio vai para HALT automático: `EXCHANGE_UNAVAILABLE` se os 3 ciclos falharam só por exchange fora do
  ar (volta sozinho, [Retomada automática](#ref-auto-resume)), senão `WATCHDOG_STUCK`. No `WATCHDOG_STUCK`,
  **escalar** com o `clientOrderId`.
- **Motivo do HALT (T66):** o motivo do `WATCHDOG_STUCK` e do `EXCHANGE_UNAVAILABLE` termina em `lastFailure=<FailureReason>: <mensagem>`,
  a última falha da consulta. `EXCHANGE_QUERY_RETRYABLE_FAILURE` com `circuit open` ou `HTTP 5xx` = exchange
  instável; `HTTP 429`/`418` = limite de peso estourado pelo próprio ctrade (o IP fica banido por um tempo).
  `INVALID_EXCHANGE_RESPONSE` = a exchange respondeu, mas a conciliação recusou a resposta: é bug do
  ctrade, não da exchange (ex.: `Cannot expire from status: PARTIAL`, corrigido na T67). **Escalar** com o
  `clientOrderId` e a mensagem; baixar o HALT não resolve, a transação continua presa.
- **Confirmar:** a transação sai de `PENDING` (terminal ou `SUBMITTED`) e a verificação em `OK`.

<a id="position-lock-orphan"></a>
### POSITION_LOCK_ORPHAN — lote travado por SELL que já terminou

O lote nunca mais será vendido, nem pela proteção do lote.

- **Diagnóstico:** `subjects` traz posição, transação do lock e o status dela.
  `GET /api/dead-letters?runnerId=<runnerId>` — a conciliação da SELL pode ter caído na DLQ.
- **Ação:** havendo DLQ, seguir [dlq-unresolved](#dlq-unresolved). Sem DLQ: `halt` do runner e
  **escalar** (não há comando para destravar lote à mão).
- **Confirmar:** a posição sai de `subjects`.

<a id="dlq-unresolved"></a>
### DLQ_UNRESOLVED — entradas não resolvidas

`CRITICAL` (≥ 3) também coloca o portfolio em HALT automático (`DLQ_THRESHOLD`).

- **Diagnóstico:** `GET /api/dead-letters?portfolioId=<id>` → `reason`, `clientOrderId`, `rawPayload`.
- **Ação, por entrada:**
  - Efeito de capital reaplicável (reserva, liberação, execução confirmada):
    `POST /api/dead-letters/<id>/reprocess` + `X-Reason`. Esperado `200` com `reprocessed=true`;
    `409 DEAD_LETTER_REPROCESS_REFUSED` = não reaplicável automaticamente.
  - Sem efeito a reaplicar, ou já corrigido por outro caminho: `POST /api/dead-letters/<id>/resolve`
    + `X-Reason` explicando a causa.
- **Confirmar:** a verificação em `OK`; se havia HALT automático, ver [safe-mode-active](#safe-mode-active).

<a id="runner-halted"></a>
### RUNNER_HALTED — runner parado

- **Diagnóstico:** `GET /api/admin/events?portfolioId=<portfolioId>`: o último `RUNNER_STATUS_CHANGED`
  do runner diz quem parou (`origin`: `MANUAL`, `AUTOMATIC`, `BOOT`) e por quê (`reason`). `AUTOMATIC`
  com "consecutive order rejections" = 5 rejeições seguidas (saldo, filtros, credencial; alerta
  `[RUNNER_AUTO_HALTED]`); `BOOT` com "reconciliation failed" = a reconciliação do boot achou erro (DLQ
  pendente, ordem sem resposta).
- **Ação (causa resolvida):** `POST /api/admin/runners/<id>/resume` + `X-Reason`. Esperado `200` com
  `newStatus=ACTIVE`; `409 RUNNER_RECONCILIATION_INCOMPLETE` = a reconciliação não fechou (ver logs e
  DLQ do runner).
- **Confirmar:** o runner sai de `subjects`.
- Parar um runner (`halt`) não tira do livro as ordens LIMIT dele; se a intenção é zerar exposição,
  cancelá-las em seguida (`GET /api/runners/<id>/transactions` → em voo → `.../orders/<tx>/cancel`).

<a id="runner-reconciling"></a>
### RUNNER_RECONCILING — runner reconciliando

Transitório depois de start/resume/boot. Persistindo, a reconciliação ficou pendente.

- **Ação:** repetir o comando que a iniciou (`start` ou `resume`) — é idempotente e retoma de onde
  parou. Recusa `RUNNER_RECONCILIATION_INCOMPLETE`: ver DLQ e `TRANSACTION_STUCK` do runner.

<a id="runner-stuck-terminating"></a>
### RUNNER_STUCK_TERMINATING — encerramento sem concluir

O runner é arquivado sozinho quando não tem posição nem ordem em voo.

- **Diagnóstico:** `GET /api/runners/<id>/transactions` (ordens em voo) e posições abertas.
- **Ação:** cancelar as LIMIT em voo (`.../orders/<tx>/cancel`); posições abertas esperam a estratégia
  vender ou exigem fechar à mão (confirmação do mantenedor).
- **Confirmar:** o runner vai a `ARCHIVED`.

<a id="runner-on-hold-strategy-unusable"></a>
### RUNNER_ON_HOLD_STRATEGY_UNUSABLE — runner ACTIVE sem estratégia utilizável

O runner está em HOLD: não compra nem vende pela estratégia (a proteção do lote continua).

- **Diagnóstico:** `GET /api/admin/strategies/<strategyId>` → `status`, `verification`, `usable`,
  `lastError`. Estratégia interna (não está no catálogo) ausente é erro de configuração: **escalar**.
- **Ação:** ver [strategy-verification-failed](#strategy-verification-failed); desabilitada de propósito
  → reabilitar só se o motivo do `DISABLED` (em `.../events`) já não vale.
- **Confirmar:** `usable=true` e a verificação em `OK`.

<a id="strategy-unavailable"></a>
### STRATEGY_UNAVAILABLE — estratégia sem decisão há mais de 60s

Runner `ACTIVE` cuja estratégia só falhou desde `failingSince` (T53 F21): em HOLD implícito, sem comprar nem vender
pela estratégia; a proteção do lote continua. Falha esporádica seguida de sucesso não aparece. O catálogo só
percebe na próxima conferência, então esta verificação costuma vir antes de
[strategy-verification-failed](#strategy-verification-failed).

- **Diagnóstico:** `lastReason` em `subjects` (`UNREACHABLE` = app fora ou endereço errado; `ERROR_RESPONSE` =
  app de pé respondendo erro; `TIMEOUT`); log `priceUpdate: strategy unavailable`; métrica
  `strategy.remote.failures`.
- **Ação:** app fora → subir de novo a app (fora do ctrade); erro de resposta → ver o log da app. O ctrade volta
  sozinho na primeira resposta boa, sem ação no runner.
- **Confirmar:** a verificação em `OK` e decisões `HOLD`/`BUY`/`SELL` de novo em `signal.evaluated.total`.

<a id="strategy-verification-failed"></a>
### STRATEGY_VERIFICATION_FAILED — estratégia remota habilitada e inutilizável

- **Diagnóstico:** `lastError` em `subjects` (ou `GET /api/admin/strategies/<ref>`):
  - `service unavailable (UNREACHABLE)`: app da estratégia fora ou endereço errado;
  - `service declares ...`: nome/versão diferente do cadastro (app trocada no mesmo endereço);
  - `unsupported contract version`: app em versão maior do contrato.
- **Ação:** app fora → aguardar (conferida a cada 30s); endereço mudou →
  `PATCH /api/admin/strategies/<ref>` `{"baseUrl":"..."}` + `X-Reason`; versão nova → cadastrar com
  `strategyRef` novo e apontar runners novos para ela.
- **Esperado:** resposta com `usable=true`.
- **Confirmar:** a verificação em `OK`.

<a id="market-data-stale"></a>
### MARKET_DATA_STALE — sem tick há mais de 60s

Cada `subject` traz `demand`:

- `ACTIVE_RUNNER` (CRITICAL): sem tick, nenhuma decisão, proteção do lote nem liquidação acontece.
- `OPEN_LOT` (WARN, T58): só lote aberto de runner parado. Ninguém negocia (`halt` não negocia nada),
  mas o drawdown pula o portfolio e o PnL flutuante sai parcial (`unpricedPositions`).

O coordenador (T56) reabre a conexão caída e reassina o símbolo sem tick a cada 30s. Símbolo de runner que
entrou com a conexão já aberta é assinado no mesmo ciclo, sem esperar os 60s (T53 F27); a confirmação da Binance
(`{"result":null,"id":N}`) não gera log de erro (F26). Uma recusa (`Market stream request rejected by Binance`)
continua `ERROR`: o símbolo ou o pedido está errado.

- **Diagnóstico:** logs `marketData:` (o coordenador está tentando? `no adapter for market data
  source` = a fonte do runner não tem adapter registrado); `GET /api/websocket/all` para o estado da
  conexão. Logo após boot ou reconexão, aguardar um ciclo.
- **Ação:** persistindo depois de 2 ciclos com a conexão `CONNECTED`, a fonte não está publicando o
  símbolo: conferir se o símbolo existe na exchange e se `allowedMarketDataSources` do runner está certo.
  Coordenador desligado: conexão manual (ver [Partida](#partida-depois-de-um-boot)).
- **Confirmar:** em até 60s o símbolo sai de `subjects`.

<a id="candle-series-unavailable"></a>
### CANDLE_SERIES_UNAVAILABLE — série de candles declarada não está pronta (T72)

Estratégia que declara `requirements.candles` só é chamada com todas as séries completas, contínuas e em dia;
sem isso o runner fica sem decisão (`WAITING_HISTORY`), mas a proteção do lote continua. Sempre WARN. Cada
`subject` traz exchange, símbolo, intervalo e `reason`:

- `UNSUPPORTED` (na hora): a fonte de market data do runner não oferece o intervalo (Coinbase não oferece
  candles; Binance e mock oferecem `1m` a `1w`).
- `INCOMPLETE` (depois de 60s): a série não completou. `detail` diz quantos candles há do pedido.

- **Diagnóstico:** logs `candles:` (`fetch failed` = a exchange recusou ou caiu; a série tenta de novo a cada
  10s). `INCOMPLETE` parado com o mesmo número de candles por muito tempo = o símbolo não tem tanto histórico
  na exchange (símbolo novo) e não vai completar.
- **Ação:** `UNSUPPORTED`: trocar `allowedMarketDataSources` do runner para uma fonte com o intervalo, ou usar
  outra estratégia. Histórico curto: a estratégia precisa de outro `strategyId` com `count` menor. Falha da
  exchange: seguir [exchange-rest-circuit-open](#exchange-rest-circuit-open) se o circuito abriu; senão aguardar.
- **Confirmar:** a série sai de `subjects` e `signal.evaluated.total{decision=WAITING_HISTORY}` do runner para
  de crescer (fora dos poucos segundos depois de cada fechamento de intervalo, que são normais).

<a id="stream-disconnected"></a>
### STREAM_DISCONNECTED — stream necessário não conectado

- **Diagnóstico:** `subjects` traz exchange, canal e status (`NOT_OPENED` = nunca aberto desde o boot).
  `CONNECTING`/`RECONNECTING` = a reconexão está trabalhando e o coordenador não interfere: aguardar.
  CRITICAL = um runner `ACTIVE` precisa do canal. WARN = só runners `HALTED` ou `TERMINATING` precisam:
  ordens no livro ficam sem acompanhamento e a retomada responde `409 EXCHANGE_UNAVAILABLE` até o canal
  voltar.
- **Ação:** o coordenador (T56) reabre a cada 30s. Persistindo, ver os logs `marketData:` e a causa da
  falha de conexão (credencial, rede, chave de listen da Binance); coordenador desligado → conexão
  manual (ver [Partida](#partida-depois-de-um-boot)).
- **Confirmar:** status `CONNECTED` em `GET /api/websocket/all` e a verificação em `OK`.

<a id="order-dispatch-blocked"></a>
### ORDER_DISPATCH_BLOCKED — envio de ordens bloqueado por conexão perdida

- **Diagnóstico:** um stream (`MARKET` ou `USER_DATA`) caiu e ainda não voltou. Sinais de compra e venda não viram
  ordem (`signal.evaluated.total{decision="DISPATCH_BLOCKED"}`), nem os da proteção do lote e do `PANIC_SELL`, que
  tentam de novo no tick seguinte. O runner não para por causa disso. `cancel` e `close` do operador respondem `409 EXCHANGE_UNAVAILABLE`, sem enviar nada. Ordens que já
  estão na exchange seguem vivas.
- **Ação:** a reconexão é automática: uma fila por canal, com espera de 5s × tentativa (`Scheduling reconnect
  attempt N/10`), e o coordenador a cada 30s. Depois de 10 tentativas seguidas sem conexão (~4,6 min), o ctrade
  registra `Max reconnect attempts` (`ERROR`), para todos os runners (`HALT` automático, falha crítica de conexão) e
  deixa a reconexão com o coordenador, que segue pedindo os canais dos runners parados; a contagem recomeça
  quando o canal conecta. Com a rede de volta e `STREAM_DISCONNECTED` em `OK`, retomar os runners (`resume`). Persistindo, ver os logs
  `Scheduling reconnect`, `marketData:` e [stream-disconnected](#stream-disconnected).
- **Confirmar:** log `All lost channels reconnected` e a verificação em `OK`.

<a id="exchange-balance-divergence"></a>
### EXCHANGE_BALANCE_DIVERGENCE — saldo local diferente da exchange

Só com `GET /api/admin/operational-state?includeExchange=true` (consome cota da API da exchange; somente
leitura). Mesma conferência do boot e da avaliação periódica (T57): por exchange, a **soma** dos portfolios
que têm runner nela contra a conta, por moeda/ativo. Portfolio é orçamento virtual sobre conta
compartilhada; sobra na conta é normal.

- **Diagnóstico:** um `subject` por exchange e ativo, com `localExpected`, `exchangeTotal`, `signedDelta`
  (conta − esperado), `unallocated` (sobra fora dos portfolios), `inFlight` e `result`:
  - moeda de cotação (ex.: USDT): esperado = `available + reserved − custo dos lotes abertos`;
  - ativo comprado (ex.: BTC): esperado = soma da quantidade dos lotes abertos;
  - `OK`: a conta cobre o esperado (o excedente vai em `unallocated`);
  - `PENDING` (não alarma): falta, mas cabe nas ordens em voo — fill na exchange ainda não conciliado.
    Repetir em alguns minutos; se persistir, ver [transaction-stuck](#transaction-stuck);
  - `DEFICIT` (CRITICAL): o ctrade espera mais do que a conta tem (capital ou cripto que não existe);
  - `FAILED` (WARN): a consulta falhou; `SKIPPED`: a exchange não tem consulta de conta.
  Cruzar com [exchange-trade-divergence](#exchange-trade-divergence) e com
  `GET /api/exchanges/<exchange>/balances`. Causas prováveis de `DEFICIT`: saque/movimentação na conta
  fora do ctrade, fill que o ctrade não registrou, fee cobrada em terceiro ativo (BNB).
- **Ação:** `DEFICIT` → a avaliação periódica (a cada 5 min, `safe-mode.auto.balance-divergence`) põe
  em HALT todos os portfolios da exchange na segunda leitura seguida; se ainda não estiverem em `HALT`,
  subir (`PUT .../safe-mode`) e **escalar**. Não há comando para ajustar saldo à mão; retomada só com o
  mantenedor.
- **Confirmar:** nova consulta com `includeExchange=true` sem `DEFICIT`.

<a id="exchange-trade-divergence"></a>
### EXCHANGE_TRADE_DIVERGENCE — fills diferentes entre o ctrade e a exchange

Só com `includeExchange=true`; janela `exchangeWindowHours` (padrão 24, máx. 168). Por símbolo/exchange
dos runners não arquivados, compara os fills locais com o histórico de trades da exchange (T18).

- **Diagnóstico:** `subjects` traz `matched`, `exchangeOnly` (fill que o ctrade não registrou),
  `localOnly` (fill que a exchange não tem) e `divergent` (quantidade/preço diferentes). Detalhe por fill:
  `GET /api/reconciliation?symbol=<s>&exchangeId=<ex>&from=<iso>&to=<iso>`.
  `result=NOT_CHECKED`: a exchange não tem histórico de trades configurado (ex.: MOCK); `QUERY_FAILED`
  (WARN): a consulta falhou.
- **Ação:** qualquer divergência é CRITICAL: `halt` dos runners do símbolo e **escalar** com o relatório de
  reconciliação. `exchangeOnly` costuma vir de evento perdido no stream de usuário; conferir também
  [dlq-unresolved](#dlq-unresolved).
- **Confirmar:** nova consulta em `OK`.

<a id="exchange-rest-circuit-open"></a>
### EXCHANGE_REST_CIRCUIT_OPEN — consultas REST à exchange suspensas

5 falhas de instabilidade abrem o circuito por 60s (T24). Boot recovery e watchdog ficam sem consultar.

- **Diagnóstico:** status da Binance, credenciais, rate limit; logs `ExchangeQueryException`. Por tipo,
  sem depender do log (T66): `exchange_order_query_failures_total{errorType}` no Prometheus (`TEMPORARY` =
  rede/5xx, `RATE_LIMIT` = 429/418, `CIRCUIT_OPEN` = chamada barrada pelo circuito).
- **Ação:** nenhuma no ctrade: o circuito testa sozinho na próxima consulta após 60s. Persistindo
  mais de 10 min, **escalar**.
- **Confirmar:** a verificação em `OK`.

---

<a id="alertas"></a>
## Alertas

| Alerta | Seção |
|---|---|
| Discord `[DLQ_PERSISTED]` | [dlq-unresolved](#dlq-unresolved) |
| Discord `[BOOT_FAIL_FAST]` | [boot-not-ready](#boot-not-ready) |
| Discord `[CIRCUIT_BREAKER_TRIGGERED]` | [safe-mode-active](#safe-mode-active) (HALT automático) |
| Discord `[PROTECTIVE_EXIT]` | Informativo: a proteção vendeu um lote (T44). Conferir se a estratégia está saudável. |
| Discord `[RUNNER_AUTO_HALTED]` | [runner-halted](#runner-halted) |
| Discord `[STRATEGY_DISABLED]` | [runner-on-hold-strategy-unusable](#runner-on-hold-strategy-unusable) |
| Grafana `operational_state_status = 2` | [Ciclo de verificação](#ciclo-de-verificacao) |
| Grafana *Safe Mode ativo* | [safe-mode-active](#safe-mode-active) |
| Grafana *Runner travado em TERMINATING* | [runner-stuck-terminating](#runner-stuck-terminating) |
| Grafana *Circuit breaker da exchange aberto* | [exchange-rest-circuit-open](#exchange-rest-circuit-open) |
| Grafana *Cancel rate > 10/min* | Estratégia em loop de ordem/cancelamento: `halt` do runner e conferir a estratégia. |
| Grafana *Rejection rate > 50%* | `signal_evaluated_total` por `decision` (capital, preço, limites); ajustar a causa. |
| Grafana *Utilização global > 80%* | Capital quase todo reservado; ver [capital-reservation-stale](#capital-reservation-stale). |

Na VPS (T74), os alertas do Grafana valem para o staging (rótulo `env="staging"`) e chegam no canal do staging;
o soak (`env="soak"`) fica de fora.

---

<a id="segredos"></a>
## Segredos e rotação (staging, T74)

Os segredos do staging ficam só no `.env` da VPS (`deploy/vps`, permissão `600`), nunca no git nem em chat:
`BINANCE_API_KEY_STG`/`BINANCE_API_SECRET_STG`, `ADMIN_API_KEY_STG`, `DB_PASSWORD_STG`,
`DISCORD_WEBHOOK_URL_STG`. O agente lê valores só para usar (por exemplo, a chave admin num `curl`) e nunca os
imprime.

- **Quando:** chaves da Binance e `ADMIN_API_KEY_STG` a cada 90 dias; qualquer segredo na hora, se houver suspeita
  de vazamento (apareceu em log, chat, commit ou print).
- **Quem:** a chave da Binance e o webhook do Discord são do mantenedor (ele cria, o agente nunca vê). A chave
  admin e a senha do banco podem ser geradas na VPS (`uuidgen`, `openssl rand -hex 24`) por quem opera.
- **Chave da Binance:** criar a nova na conta do staging (sem saque), trocar no `.env`, `docker compose up -d
  ctrade-app`, confirmar o boot (`GET /api/boot/status` em `SUCCESS`) e só então apagar a antiga na Binance.
- **Chave admin:** trocar no `.env` e `docker compose up -d ctrade-app`. Rotinas que leem a chave do `.env`
  pegam a nova sozinhas.
- **Senha do banco:** trocar `DB_PASSWORD_STG` no `.env`, `./db-environments.sh` (aplica no Postgres) e
  `docker compose up -d ctrade-app`.
- **Webhook do Discord:** apagar o webhook no canal, criar outro, trocar no `.env` e `docker compose up -d
  ctrade-app grafana`.
- **Confirmar:** boot `SUCCESS`, estado operacional sem piora e, no webhook, uma notificação chegando.

<a id="restore-staging"></a>
## Restore do staging (T74, A5)

Backups em `~/backups/staging` na VPS (diário e antes de toda implantação, 14 dias) e cópia no computador do
mantenedor (`deploy/vps/README.md`, "Backup do staging"). Restaurar troca o estado do staging pelo do backup: tudo
depois dele (ordens, fills, eventos) some do banco e passa a existir só na exchange.

- **Quando:** migração do Flyway que falhou na implantação, ou banco do staging corrompido. Migração com bug volta
  pelo restore e uma migração nova corrige; não há script de rollback.
- **Quem decide:** o mantenedor. Restaurar apaga dado do staging, que é tratado como produção: o agente propõe, com
  o backup escolhido e o que se perde, e só executa com o sim.
- **Passos** (em `~/ctrade/deploy/vps`):
  1. `./backup-staging.sh`: backup do estado atual, mesmo quebrado, para análise.
  2. `docker compose stop ctrade-app`.
  3. Voltar o checkout para o commit compatível com o backup, se a causa foi a versão nova.
  4. `docker compose exec -T postgres psql -U ctrade -d ctrade -v ON_ERROR_STOP=1 -c "DROP SCHEMA staging CASCADE"`.
  5. `docker compose exec -T postgres pg_restore -U ctrade -d ctrade --exit-on-error < ~/backups/staging/<backup>.dump`
     (recria o schema `staging` com o dono `ctrade_stg`).
  6. `./up.sh` e conferir boot `SUCCESS`. O `up.sh` faz outro backup antes de subir, agora do estado restaurado.
- **Confirmar:** saldos e conciliação contra a exchange (`GET /api/reconciliation`). O que aconteceu na exchange
  depois do backup aparece como divergência e segue os procedimentos de conciliação deste runbook.

---

<a id="referencia"></a>
## Referência

### Pré-requisito da conta Binance: BNB para taxas desligado (T46)

A opção **"Usar BNB para pagar taxas"** deve ficar **desligada** (testnet e produção). Ligada, cada fill
chega com fee em BNB: o sistema segue, mas a fee fica fora do PnL, com log `ERROR`
`unsupported BUY fee asset` / `unsupported SELL fee asset`.

### Safe Mode do portfolio (T45)

`GET|PUT /api/admin/portfolios/{id}/safe-mode`; corpo do `PUT`: `{"level","confirm"}` + `X-Reason`.

| Nível | Efeito |
|---|---|
| `HALT` | Nenhum BUY novo. A estratégia segue e pode vender. |
| `CANCEL_ALL` | HALT + cancela ordens LIMIT em voo; estratégia não é consultada. Exige `confirm`. |
| `PANIC_SELL` | CANCEL_ALL + vende todas as posições: MARKET por padrão, ou LIMIT protegida conforme o runner (T48). Exige `confirm`. |

- O `PUT` responde `202`; a execução acontece nos ticks seguintes (sem tick, nada é liquidado).
- `idleRunners > 0`: runners parados pelo kill switch não são conduzidos; retomar para liquidá-los.
- Liquidação LIMIT por runner (T48): `PATCH /api/admin/runners/{id}`
  `{"liquidationOrderType":"LIMIT","liquidationMaxSlippagePercent":0.01,"liquidationEscalationTicks":3}`;
  SELL LIMIT 1% abaixo do último preço, escalada para MARKET após 3 ticks sem preencher.
- Descida um nível por vez. Mudanças em `safe_mode_changes`.

### Circuit breaker de drawdown (T43)

A cada 10s, equity = `available + reserved + PnL flutuante`. Queda acima de 10% do pico de 24h → HALT
automático (`MAX_DRAWDOWN_EXCEEDED`). Sem retomada automática; o pico é recalculado após o reset.
Lote sem preço: o portfolio é pulado (`WARN drawdown: no market price` uma vez por lote; ver
[market-data-stale](#market-data-stale), `demand=OPEN_LOT`).

`GET /api/portfolios/<id>/pnl`: `unrealizedPnL` soma só os lotes com preço; `unpricedPositions > 0` quer
dizer que o flutuante é parcial (lote sem tick ainda, ex.: logo após restart) e `priceAsOf` é o preço mais
antigo usado. Não ler `unrealizedPnL = 0` como "lote no zero a zero" sem olhar `unpricedPositions`.
Gauges `circuit_breaker.drawdown.current`, `.peak_24h`, `circuit_breaker.equity.current`.
`portfolio.circuit-breaker.enabled=false` desliga.

<a id="ref-gatilhos"></a>
### Gatilhos automáticos (T25)

| Motivo | Condição | Antes de retomar |
|---|---|---|
| `DLQ_THRESHOLD` | ≥ 3 entradas não resolvidas | [dlq-unresolved](#dlq-unresolved) |
| `BALANCE_DIVERGENCE` | Soma dos portfolios de uma exchange acima da conta (`DEFICIT`), no boot ou em 2 leituras seguidas da avaliação periódica (5 min); HALT de todos os portfolios da exchange | [exchange-balance-divergence](#exchange-balance-divergence); escalar |
| `WATCHDOG_STUCK` | Mesma transação sem resolver em 3 ciclos, com alguma falha que não é exchange fora do ar | [transaction-stuck](#transaction-stuck) |
| `EXCHANGE_UNAVAILABLE` | Mesma transação sem resolver em 3 ciclos, todos por exchange fora do ar (timeout, 5xx, limite de peso) | Nada: volta sozinho ([Retomada automática](#ref-auto-resume)) |

Runner: 5 rejeições seguidas param só ele (`[RUNNER_AUTO_HALTED]`). Retomada manual, exceto o HALT
`EXCHANGE_UNAVAILABLE` do portfolio. `safe-mode.auto.*` (cada gatilho com `enabled`; `EXCHANGE_UNAVAILABLE`
liga com `watchdog.enabled`).

<a id="ref-auto-resume"></a>
### Retomada automática do HALT por exchange fora do ar (T71)

O HALT `EXCHANGE_UNAVAILABLE` volta a NORMAL sozinho quando, por 5 minutos seguidos: o circuito REST está
fechado, o envio de ordens liberado, os streams que os runners precisam conectados e o watchdog sem falha nos
runners do portfolio. No máximo 3 retomadas automáticas por portfolio em 24h.

- **Log e alerta:** `autoResume: HALT -> NORMAL ...` (`WARN`), Discord `SAFE_MODE_AUTO_RESUMED`, contador
  `circuit_breaker.auto_resumes.total{trigger}`. Auditoria em `GET /api/admin/events` (`SAFE_MODE_CHANGED`,
  origem `AUTOMATIC`, motivo `AUTO_RESUME EXCHANGE_UNAVAILABLE: exchange healthy for 5m`).
- **Teto atingido:** `autoResume: HALT kept ... auto-resume limit reached` (`ERROR`) e Discord. A exchange caiu e
  voltou 3 vezes em 24h: conferir a instabilidade (`EXCHANGE_REST_CIRCUIT_OPEN`, `STREAM_DISCONNECTED`) e descer
  à mão com a confirmação do mantenedor.
- **Não volta depois de 5 min:** a exchange segue não saudável para algum runner do portfolio. Ver
  `autoResume: exchange unhealthy again ... - <motivo>` (`INFO`) e o estado operacional.
- **Desligar:** `safe-mode.auto.resume.enabled=false` (reinício). Ajustes: `healthy-minutes`, `max-per24h`.

### Ciclo de vida do runner (T49)

| Ação | Endpoint (todos com `X-Reason`) |
|---|---|
| Iniciar | `POST /api/admin/runners/{id}/start` (reconcilia e ativa) |
| Parar | `POST /api/admin/runners/{id}/halt` — **não cancela** ordens LIMIT que descansam no livro: elas seguem com capital reservado; cancelar uma a uma (`.../orders/{transactionId}/cancel`) se o objetivo é zerar exposição |
| Retomar | `POST /api/admin/runners/{id}/resume` |
| Encerrar | `POST /api/admin/runners/{id}/terminate` (arquiva sozinho quando zerado) |
| Limites, proteção, liquidação | `PATCH /api/admin/runners/{id}` (campo omitido mantém) |
| Cancelar ordem | `POST /api/admin/runners/{id}/orders/{transactionId}/cancel` (`202`; `409` com o envio bloqueado) |
| Fechar posição | `POST /api/admin/runners/{id}/positions/{positionId}/close` (`202`, SELL MARKET do lote; `409` com o envio bloqueado) |

O `X-Reason` de `start`, `halt`, `resume` e `terminate` vira o motivo da transição no histórico do runner
(`RUNNER_STATUS_CHANGED` em `GET /api/admin/events`, origem `MANUAL`). Transições do sistema ficam com
origem `AUTOMATIC` (rejeições seguidas, falha crítica de conexão, arquivamento do runner zerado) ou `BOOT`.

Códigos de recusa: `RUNNER_INVALID_STATE`, `RUNNER_RECONCILIATION_INCOMPLETE`, `EXCHANGE_UNAVAILABLE`,
`ORDER_NOT_FOUND`, `POSITION_NOT_FOUND`, `POSITION_LOCKED` (cancelar a SELL LIMIT antes),
`RUNNER_NOT_ACCEPTING_SIGNALS` (retomar antes), `NO_MARKET_PRICE` (esperar o primeiro tick).

### Proteção do lote (T44)

Venda MARKET do lote, sem a estratégia, quando a perda passa de `maxLossPercentPerLot` (0.05) ou a idade
passa de `maxHoldingDuration`. Vale com a estratégia fora do ar e em HALT. Ajuste:
`PATCH /api/admin/runners/{id}` `{"maxLossPercentPerLot":0.08}` / `{"maxHoldingDuration":"PT24H"}`
(`"PT0S"` desliga a idade). Sinais: `[PROTECTIVE_EXIT]`, `signal.evaluated.total{decision=PROTECTIVE_EXIT}`.

**Runner de prazo longo (swing, position; T72, decisão 5):** o padrão de 5% vale para todo runner novo e corta
uma operação longa na primeira oscilação normal. Ao criar o runner de uma estratégia que declara candles de
`1h` ou mais, ajustar a proteção ao prazo dela logo depois de criar, antes do `start`, por exemplo
`{"maxLossPercentPerLot":0.20,"maxHoldingDuration":"P30D"}`. A saída da proteção é sempre a mercado. O
drawdown do portfólio (10% em 24h) continua valendo e pode pôr o portfólio em `HALT` de compras com posições
longas oscilando, sem vender nada.

### Estratégia remota e catálogo (T41, T54)

A estratégia é uma app que implementa `docs/strategy-service/CONTRACT.md`; o catálogo é operado por API.

- Cadastrar: `POST /api/admin/strategies`
  `{"strategyRef","name","version","baseUrl":"http://host:porta/v2","token"}`. Esperado
  `verification=VERIFIED`, `usable=true`; `FAILED` = ler `lastError` (conferido de novo a cada 30s).
  `409 STRATEGY_DEFINITION_CONFLICT`: versão nova = `strategyRef` novo; endereço novo = `PATCH`.
- Consultar: `GET /api/admin/strategies[/{ref}]`, histórico em `.../events`.
- Endereço/token: `PATCH /api/admin/strategies/{ref}` `{"baseUrl","token"}` (nulo mantém; token vazio remove).
- Tirar de uso / voltar: `POST .../disable` / `.../enable`. Sem exclusão.
- Sinais: `signal.evaluated.total{decision=STRATEGY_UNAVAILABLE}`,
  `strategy.remote.failures{reason}`, `strategy.remote.latency`. `timeout-ms` 250, `max-decision-age-ms` 2000.

### Circuit breaker das consultas REST (T24)

5 falhas de instabilidade (rede, 5xx, rate limit) abrem o circuito por 60s; erro de requisição ou de
autenticação não conta. `resilience4j_circuitbreaker_state{name="binance-order-query",state="open"}`.
`exchange.order-query.circuit-breaker.enabled=false` desliga.

### Processamento de ticks (T42)

`runner.ticks.discarded{reason}`: `COALESCED` normal em rajadas; `STALE` = fila saturada (aumentar
`runner.signal.threads` ou investigar estratégia lenta); `OUT_OF_ORDER` raro.
`runner.signal.processing.time` com WARN `slow evaluation` acima de 1s. ERROR
`marketDataExecutor: queue full` = parsing não acompanha o feed.

### Métricas de estado (T51, T55)

`operational_state_status`, `operational_check_status{check}` (0=OK, 1=WARN, 2=CRITICAL);
`circuit_breaker_current_level` (0=NORMAL … 3=PANIC_SELL), `capital_utilization_global`,
`capital_utilization_runner`, `capital_reservation_oldest_age_seconds`, `runner_lifecycle_current_state`.
`/actuator/health` → componente `safeMode` (continua `UP` com Safe Mode ativo).
