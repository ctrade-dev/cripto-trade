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

O coordenador de market data (T56) abre sozinho as conexões e assinaturas que os runners operacionais
precisam: market data de cada fonte em `allowedMarketDataSources` e o stream de usuário da exchange que
tem um. Runner parado com lote aberto também tem o símbolo assinado (T58), só para o lote ter preço
(drawdown, PnL); ele continua sem negociar. Roda 5s depois do boot, a cada 30s (`ctrade.market-data.auto-feed.*`) e logo após `start`/`resume`.

1. `GET /api/boot/status` → esperado `status=SUCCESS`. Senão, ver [boot-not-ready](#boot-not-ready).
2. Aguardar até 1 minuto e conferir `GET /api/admin/operational-state`: `MARKET_DATA_STALE` e
   `STREAM_DISCONNECTED` em `OK`. Logs `marketData: connecting ...` / `subscribing ...` mostram o que foi
   aberto e por quê.
3. Não resolveu: ver [market-data-stale](#market-data-stale) ou [stream-disconnected](#stream-disconnected).

Conexão manual (diagnóstico, ou com `ctrade.market-data.auto-feed.enabled=false`):
`POST /api/websocket/connect`
`{"exchange":"BINANCE","symbols":[{"baseCurrency":"BTC","quoteCurrency":"USDT","streamType":"TICKER"}]}`
abre o market data e, se a exchange tiver, o stream de usuário.

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
    `DLQ_THRESHOLD`, `BALANCE_DIVERGENCE`, `WATCHDOG_STUCK`). Tratar a causa primeiro (seção
    [Gatilhos](#ref-gatilhos)).
  - `origin=MANUAL`: conferir em `GET /api/admin/operator-actions?pathContains=<portfolioId>` quem
    subiu e por quê.
- **Ação:** descer **um nível por vez** depois que a causa estiver resolvida:
  `PUT /api/admin/portfolios/<id>/safe-mode` `{"level":"<um abaixo>","confirm":false}` + `X-Reason`.
  Descer de HALT **automático** exige confirmação do mantenedor ([Limites](#limites-de-autonomia)).
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
  portfolio vai para HALT automático (`WATCHDOG_STUCK`). **Escalar** com o `clientOrderId`.
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

O coordenador (T56) reabre a conexão caída e reassina o símbolo sem tick a cada 30s.

- **Diagnóstico:** logs `marketData:` (o coordenador está tentando? `no adapter for market data
  source` = a fonte do runner não tem adapter registrado); `GET /api/websocket/all` para o estado da
  conexão. Logo após boot ou reconexão, aguardar um ciclo.
- **Ação:** persistindo depois de 2 ciclos com a conexão `CONNECTED`, a fonte não está publicando o
  símbolo: conferir se o símbolo existe na exchange e se `allowedMarketDataSources` do runner está certo.
  Coordenador desligado: conexão manual (ver [Partida](#partida-depois-de-um-boot)).
- **Confirmar:** em até 60s o símbolo sai de `subjects`.

<a id="stream-disconnected"></a>
### STREAM_DISCONNECTED — stream necessário não conectado

- **Diagnóstico:** `subjects` traz exchange, canal e status (`NOT_OPENED` = nunca aberto desde o boot).
  `CONNECTING`/`RECONNECTING` = a reconexão está trabalhando e o coordenador não interfere: aguardar.
- **Ação:** o coordenador (T56) reabre a cada 30s. Persistindo, ver os logs `marketData:` e a causa da
  falha de conexão (credencial, rede, chave de listen da Binance); coordenador desligado → conexão
  manual (ver [Partida](#partida-depois-de-um-boot)).
- **Confirmar:** status `CONNECTED` em `GET /api/websocket/all` e a verificação em `OK`.

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

- **Diagnóstico:** status da Binance, credenciais, rate limit; logs `ExchangeQueryException`.
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
| `WATCHDOG_STUCK` | Mesma transação sem resolver em 3 ciclos | [transaction-stuck](#transaction-stuck) |

Runner: 5 rejeições seguidas param só ele (`[RUNNER_AUTO_HALTED]`). Retomada sempre manual.
`safe-mode.auto.*` (cada gatilho com `enabled`).

### Ciclo de vida do runner (T49)

| Ação | Endpoint (todos com `X-Reason`) |
|---|---|
| Iniciar | `POST /api/admin/runners/{id}/start` (reconcilia e ativa) |
| Parar | `POST /api/admin/runners/{id}/halt` — **não cancela** ordens LIMIT que descansam no livro: elas seguem com capital reservado; cancelar uma a uma (`.../orders/{transactionId}/cancel`) se o objetivo é zerar exposição |
| Retomar | `POST /api/admin/runners/{id}/resume` |
| Encerrar | `POST /api/admin/runners/{id}/terminate` (arquiva sozinho quando zerado) |
| Limites, proteção, liquidação | `PATCH /api/admin/runners/{id}` (campo omitido mantém) |
| Cancelar ordem | `POST /api/admin/runners/{id}/orders/{transactionId}/cancel` (`202`) |
| Fechar posição | `POST /api/admin/runners/{id}/positions/{positionId}/close` (`202`, SELL MARKET do lote) |

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

### Estratégia remota e catálogo (T41, T54)

A estratégia é uma app que implementa `docs/strategy-service/CONTRACT.md`; o catálogo é operado por API.

- Cadastrar: `POST /api/admin/strategies`
  `{"strategyRef","name","version","baseUrl":"http://host:porta/v1","token"}`. Esperado
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
