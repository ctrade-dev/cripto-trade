# Monitoring Spec — ctrade

**Gerado a partir de:** `/.ai/flows/` + análise de logs sentinel existentes (T21)  
**Status:** Rascunho — calibrar thresholds em staging antes de ativar alertas

> **Atualização T23 (2026-06-26):** os gaps de instrumentação críticos catalogados aqui
> foram implementados em T23 (G1 `websocket.connection.state`, G2 `signal.evaluated.total`,
> G3 log de runner HALTED por DLQ, G4 campos das fases 2 do boot, G5 log de idempotência de
> capital). Entradas afetadas estão marcadas com **✅ T23** abaixo. A fase `phase2.ttl` deixou
> de existir na T31; referências a ela permanecem apenas como histórico e estão marcadas como
> obsoletas. Os novos alertas habilitados estão em `/.ai/tasks/T23-instrumentation-gaps.md`.

---

## Tabela Mestre de Monitoramento

| # | Fluxo | Indicador | Tipo | Query (LogQL/PromQL) | Janela | Condição de disparo | Canal | Prioridade |
|---|---|---|---|---|---|---|---|---|
| M1 | Boot | Boot não concluído | Log ausente | `absent_over_time({app="ctrade"} \|= "bootSequence: completed" [10m])` | 10 min desde startup | Ausência | `#alerts-error` | Alta |
| M2 | Boot | Runner com erros no recovery | Log present | `{app="ctrade"} \|= "bootRecovery: completed" \| json \| hasErrors="true"` | On occurrence | Presença | `#alerts-behavior` | Alta |
| M3 | Boot | Boot fail-fast | Event (T21) | Discord webhook via `BootAlertListener` | On occurrence | Presença | `#alerts-error` | Alta |
| M4 | WebSocket | Reconexões frequentes | Log rate | `rate({app="ctrade"} \|= "reconnect" [5m]) > 0.05` | 5 min | > 3 reconexões/min | `#alerts-behavior` | Média |
| M5 | WebSocket | Spike de ERRORs | Log rate | `rate({app="ctrade", level="ERROR"} [1m]) > 0.1` | 1 min | > 0.1 ERRORs/s | `#alerts-behavior` | Média |
| M6 | WebSocket | Alta taxa de WARNs | Log rate | `rate({app="ctrade", level="WARN"} [5m]) > 0.5` | 5 min | > 0.5 WARNs/s por 2 min | `#alerts-behavior` | Baixa |
| M7 | Order Conciliation | Nenhuma ordem submetida | Log ausente | `absent_over_time({app="ctrade"} \|= "PENDING->SUBMITTED" [30m])` | 30 min | Ausência após atividade recente | `#alerts-behavior` | Média |
| M8 | Order Conciliation | Nenhum fill confirmado | Log ausente | `absent_over_time({app="ctrade"} \|= "executionConfirmedReaction:" \| json \| isFinal="true" [2h])` | 2 h | Ausência com posições abertas | `#alerts-behavior` | Baixa |
| M9 | Order Conciliation | Nenhuma margem liberada | Log ausente | `absent_over_time({app="ctrade"} \|= "marginReleasedReaction:" [2h])` | 2 h | Ausência com transações terminadas | `#alerts-behavior` | Baixa |
| M10 | Signal Processing | Nenhuma reserva de capital | Log ausente | `absent_over_time({app="ctrade"} \|= "capital reserved" [1h])` | 1 h | Ausência com runners ativos | `#alerts-behavior` | Baixa |
| M11 | Capital / DLQ | Nova entrada DLQ de capital | Event (T21) | Discord webhook via `CapitalEventListener.@Recover` | On occurrence | Presença | `#alerts-error` | Alta |
| M12 | Capital / DLQ | DLQ acumulada sem resolução | Métrica (gap) | `dlq_unresolved_total > 0` (gauge planejado em T21) | Contínuo | > 0 por mais de 30 min | `#alerts-error` | Alta |
| M13 | Dead Letter | Replay não aplicado | Log | `{app="ctrade"} \|= "deadLetter:" \| json \| applied="false"` | On occurrence | Presença | `#alerts-behavior` | Média |
| M14 | Binance Stream | Sem execução report após ordem | Log correlacionado | *(gap — requer instrumentação)* | — | — | — | Alta |

---

## Análise por Fluxo

### 1. Boot Recovery

**O que pode parar silenciosamente:**
- Boot completa com `hasErrors=true` em modo `WARN_ONLY` — runner continua ACTIVE mas com inconsistências
- Phase 2 detecta zombies mas não bloqueia (WARN_ONLY) — ordens abertas na exchange sem conciliação local
- Phase 2 de saldo retorna `PENDING` — falta na conta coberta por ordens em voo, conciliadas so na Phase 3 (T57)

**Cadência esperada:** One-shot por startup. Deve concluir em < 3 min.

**Logs sentinel disponíveis:**
- `bootSequence: completed runId={} portfolios={} runners={}` — qualidade alta (M1)
- `bootRecovery: completed runnerId={} hasErrors={}` — qualidade alta, `hasErrors` é a chave (M2)
- `bootSequence.phase2.sanity: completed runId={} result={} exchanges={}` — **✅ T23 (G4)**, `result` agregado (pior status; por exchange desde a T57)
- `bootSequence.phase2.zombie: completed runId={} detected={} mode={}` — **✅ T23 (G4)**
- ~~`bootSequence.phase2.ttl: completed`~~ — **obsoleto:** fase removida na T31 (não há mais varredura TTL no boot)

**Gaps de instrumentação:**
- ~~Resultado do sanity check no log de conclusão~~ — **✅ T23 (G4):** `result={OK/PENDING/SKIPPED/FAILED/DEFICIT}` agregado (T57)
- ~~Contagem de zombies em WARN_ONLY no log de conclusão~~ — **✅ T23 (G4):** `detected={}` + `mode={WARN_ONLY/FAIL_FAST}`
- ~~Count de transações expiradas por TTL~~ — **obsoleto:** fase TTL removida na T31

---

### 2. WebSocket Event Routing

**O que pode parar silenciosamente:**
- WebSocket em estado OPEN mas sem mensagens chegando (exchange silenciosa ou conexão ghost)
- Listener individual com exception repetida — erro logado por ocorrência mas sem rate tracking
- Market stream conectado mas nenhum tick entregue ao `ProcessTradeSignalUseCase`

**Cadência esperada:** Mensagens de mercado contínuas enquanto a exchange opera. Reconexões devem ser raras (< 1 por hora em operação normal).

**Logs sentinel disponíveis:**
- `Processing SUCCESS - type={}` — **qualidade baixa** (só tipo da classe, sem correlationId ou exchange) (melhoria pendente)

**Gaps de instrumentação:**
- Nenhum counter de mensagens processadas por exchange/canal — impossível detectar "conectado mas silencioso" (parcial: o gauge abaixo cobre o estado da conexão, não o volume de mensagens)
- Nenhum rate de erros por listener individual
- ~~Estado da conexão WebSocket não exposto como métrica~~ — **✅ T23 (G1):** gauge `websocket.connection.state{exchange,channel}` (1=conectado / 0=desconectado), atualizado em connected/failed/closed/disconnected via `ConnectionStateEventListener`

---

### 3. Runner Signal Processing

**O que pode parar silenciosamente:**
- Runner recebendo ticks mas estratégia sempre retornando HOLD — nenhuma ordem gerada
- `CapitalReservationRejectedException` sendo descartada silenciosamente (sinal descartado sem trace)
- `ConcurrentPositionLockException` em SELL — sinal descartado, posição não vendida

**Cadência esperada:** Depende da estratégia. Em staging com dados sintéticos, BUY/SELL podem ser infrequentes. Não monitorar ausência de sinais em janelas curtas.

**Logs sentinel disponíveis:**
- `capital reserved transactionId={} runnerId={} amount={} portfolioId={}` — qualidade alta (M10)

**Gaps de instrumentação:**
- ~~Nenhum sinal observável de "runner processando vs. parado"~~ — **✅ T23 (G2):** counter `signal.evaluated.total{runnerId,decision}` com decisões `HOLD/BUY/SELL/CANCEL/REJECTED_CAPITAL/REJECTED_LOCK/REJECTED_NO_POSITION/REJECTED_POLICY` (mais `SAFE_MODE` da T45, `STRATEGY_UNAVAILABLE` da T41 e `PROTECTIVE_EXIT` da T44; `REJECTED_PRICE_DEVIATION`/`REJECTED_MAX_POSITIONS`/`REJECTED_MAX_PENDING` da T50; `REJECTED_QUANTITY` da T61) (outcomes mutuamente exclusivos; um BUY recusado por capital conta como `REJECTED_CAPITAL`, nunca `BUY`)
- ~~Falhas silenciosas de reserva de capital~~ — **✅ T23 (G2):** tag `decision=REJECTED_CAPITAL`
- ~~`ConcurrentPositionLockException` sem observabilidade~~ — **✅ T23 (G2):** tag `decision=REJECTED_LOCK`

---

### 4. Order Conciliation

**O que pode parar silenciosamente:**
- Transação `PENDING` que nunca transita para `SUBMITTED` (ordem enviada mas callback NEW perdido)
- Transação `SUBMITTED` que nunca recebe fill (ordem aberta indefinidamente)
- Posição travada por SELL que nunca destravou (fill final não chegou)

**Cadência esperada:** Após `capital reserved`, `PENDING->SUBMITTED` deve aparecer em segundos. Fills dependem de mercado.

**Logs sentinel disponíveis:**
- `PENDING->SUBMITTED transactionId={} exchangeOrderId={}` — qualidade alta (M7)
- `BUY fill positionId={} runnerId={} increment={} fillPrice={}` — qualidade alta
- `SELL match persisted matchId={} transactionId={} status={} executedQty={}` — qualidade alta
- `executionConfirmedReaction: matchId={} transactionId={} totalCost={} fee={} isFinal={}` — qualidade alta (M8)
- `marginReleasedReaction: transactionId={} releaseAmount={} reason={} portfolioId={}` — qualidade alta (M9)

**Gaps de instrumentação:**
- Nenhum counter de transações por status atual (PENDING/SUBMITTED age não rastreada)
- Correlação entre `capital reserved` e `PENDING->SUBMITTED` não é detectável via LogQL puro (requer join por transactionId com `line_format`)

---

### 5. Portfolio Capital

**O que pode parar silenciosamente:**
- `MarginReleaseEvent` em retry loop infinito (max-attempts=`Integer.MAX_VALUE`) sem chegar a DLQ
- `GlobalBalance.availableBalance` driftando de expectativa sem que nenhum ERROR ocorra
- Idempotência de capital events silenciando um evento que deveria ter efeito

**Cadência esperada:** `ExecutionConfirmedEvent` para cada SELL fill; `MarginReleaseEvent` para cada CANCELED/EXPIRED/REJECTED.

**Logs sentinel disponíveis:**
- `executionConfirmedReaction:` — qualidade alta (M8)
- `marginReleasedReaction:` — qualidade alta (M9)
- `CAPITAL_DLQ_PERSISTED:` — qualidade alta, indica falha terminal (M11)

**Gaps de instrumentação:**
- `availableBalance`, `reservedBalance`, `realizedBalance` não expostos como gauge Micrometer — impossível alertar em drift de saldo
- Retry count de `MarginReleaseEvent` não é observável externamente (loop silencioso)
- ~~Nenhum log para "capital event marked as duplicate (idempotency)"~~ — **✅ T23 (G5):** `executionConfirmedReaction: duplicate ignored ...` e `marginReleasedReaction: duplicate ignored ...` em nível `info` (antes `debug`, invisível no Loki)

---

### 6. Binance User Data Stream

**O que pode parar silenciosamente:**
- Subscription enviada mas confirmação com status != 200 (log de erro, mas stream não para)
- Stream conectado e subscrito mas sem `executionReport` chegando após ordem colocada
- Reconnect bem-sucedido mas subscription não renovada (timestamp vencido rejeitado silenciosamente)

**Cadência esperada:** Após cada ordem colocada, `executionReport` com `X=NEW` deve chegar em < 5s. Fills dependem de mercado.

**Logs sentinel disponíveis:**
- Nenhum sentinel de alta qualidade para "subscription confirmada" (a confirmação retorna `ProcessingResult.error()` por design — não notifica listeners)
- Eventos de `executionReport` chegam como `OrderDataDto` mas o log está em `order-conciliation` (`PENDING->SUBMITTED`)

**Gaps de instrumentação (críticos):**
- Nenhum indicador de "subscription ativa e saudável" — o stream pode estar conectado sem receber nada
- Correlação entre `capital reserved` (ordem enviada) e `PENDING->SUBMITTED` (NEW recebido) exige lógica fora do Loki
- Nenhum counter de `executionReport` recebidos por runnerId

---

### 7. Dead Letter Replay

**O que pode parar silenciosamente:**
- DLQ entries acumulando sem notificação (principal blind spot — resolvido por T21)
- Replay retornando `applied=false` — entry permanece aberta, operador não é avisado
- Runner em estado HALTED por DLQ — operação parada mas sem alerta ativo

**Cadência esperada:** DLQ deve estar zerada em operação normal. Qualquer entrada não-zero é sinal de ação necessária.

**Logs sentinel disponíveis:**
- `deadLetter: reprocessed id={} requestedBy={}` — qualidade alta
- `deadLetter: resolved id={} resolvedBy={}` — qualidade alta
- `CAPITAL_DLQ_PERSISTED: dlqId={}` — qualidade alta (M11)

**Gaps de instrumentação:**
- `dlq.unresolved.total` gauge ainda não existe (planejado em T21 Camada 1)
- ~~Nenhum log explícito quando boot recovery decide HALTAR um runner por DLQ pendente~~ — **✅ T23 (G3):** `bootRecovery: runner halted runnerId={} reason=DLQ_PENDING portfolioId={}`, emitido **apenas** quando o step de fato executa `ACTIVE→HALTED` por DLQ pendente (não dispara para runners não-ACTIVE nem para halts por outros erros)
- Replay `applied=false` é logado internamente mas sem campo estruturado `applied` para query (melhoria pendente)

---

## Gaps de Instrumentação (insumos para tasks futuras)

### Gaps de log (sem nova classe, só adicionar campo)

| Arquivo | Log atual | Campo faltante | Impacto | Status |
|---|---|---|---|---|
| `RunBootSequenceUseCase` | `bootSequence.phase2.sanity: completed` | `runId`, `result` (OK/PENDING/SKIPPED/FAILED/DEFICIT) | Detectar sanity anômalo | ✅ T23 (G4) |
| `RunBootSequenceUseCase` | `bootSequence.phase2.zombie: completed` | `runId`, `detected` (count), `mode` (FAIL_FAST/WARN_ONLY) | Detectar zombies em WARN_ONLY | ✅ T23 (G4) |
| ~~`RunBootSequenceUseCase`~~ | ~~`bootSequence.phase2.ttl: completed`~~ | ~~`runId`, `expired`~~ | — | Obsoleto (fase removida na T31) |
| `RunBootSequenceUseCase` | `bootSequence.phase1: completed exchanges={}` | `runId` | Correlacionar fases pelo mesmo boot | Já tinha `runId` (sem alteração) |
| `ProcessMessageEventListener` | `Processing SUCCESS - type={}` | `correlationId`, `exchange` | Detectar "conectado mas silencioso" por exchange | Pendente |
| `ManageDeadLetterUseCase` | DLQ replay internamente | `applied=true/false` como campo estruturado | Detectar replays não aplicados via LogQL (M13) | Pendente |
| `RunnerBootRecoveryUseCase` | Runner HALTED por DLQ | Log explícito `bootRecovery: runner halted runnerId={} reason=DLQ_PENDING` | Detectar runners parados | ✅ T23 (G3) |

### Gaps de métrica (requerem Micrometer)

| Métrica | Tipo | Onde registrar | Impacto | Status |
|---|---|---|---|---|
| `dlq.unresolved.total` | Gauge | `JdbcDeadLetterEntryRepositoryAdapter` | Alertar em DLQ acumulada (M12) — planejado em T21 | Pendente |
| `capital.event.retry.count{eventType}` | Counter | `CapitalEventListener` retry intercept | Detectar retry loop silencioso de margem | Pendente |
| `websocket.connection.state{exchange,channel}` | Gauge (0/1) | `ConnectionStateEventListener` → `WebSocketConnectionStateGauge` | Detectar "conectado mas silencioso" | ✅ T23 (G1) |
| `portfolio.balance.available{portfolioId}` | Gauge | `ExecutionConfirmedReaction` / `MarginReleasedReaction` | Alertar em drift de saldo | Pendente |
| `signal.evaluated.total{runnerId,decision}` | Counter | `ProcessTradeSignalUseCase` → `SignalMetricsPort` / `MicrometerSignalMetricsAdapter` | Detectar runner sempre em HOLD | ✅ T23 (G2) |

### Gaps de lógica de correlação (requerem código de monitoramento)

> **T26:** o `transactionId` é campo MDC (campo JSON no Loki) na **conciliação**
> (`ConciliationOrderUpdate`) — `| json | transactionId="X"` recupera o ciclo de conciliação. As
> demais fronteiras (criação do sinal, capital, recovery watchdog/boot) carregam o `transactionId`
> no **texto** da mensagem, recuperáveis por `|= "transactionId=X"`; o log
> `signal: transaction created ... correlationId=...` faz o join tick→transação. O MDC ficou
> concentrado na conciliação para reduzir complexidade/risco de bug (um único site). A correlação
> ponta-a-ponta do stream Binance (subscription→executionReport) permanece gap.

| Correlação | Descrição | Complexidade |
|---|---|---|
| `capital reserved` → `PENDING->SUBMITTED` em < 2 min | Detectar ordem enviada mas sem callback NEW | Média — o join é por `transactionId`, mas as duas linhas estão em níveis diferentes: `capital reserved` (em `ProcessTradeSignalUseCase`) carrega o id só no **texto** (`\|= "transactionId=X"`); `PENDING->SUBMITTED` (na conciliação) carrega no **MDC** (`\| json`). Um monitor deve usar o filtro de linha para o lado da reserva |
| Subscription Binance confirmada → `executionReport` em < 60s após ordem | Detectar stream saudável por end-to-end | Alta — requer rastrear estado da sessão |

---

## Resumo de Prioridades de Implementação

### Implementar agora (T21 já cobre ou cobre com mínimo esforço)
- M1, M2, M3 — Boot alerts
- M4, M5, M6 — Rate alerts via Grafana + Discord contact point
- M11 — DLQ capital via Discord webhook (T21 Camada 1)
- M12 — DLQ gauge (T21 Camada 1, 3 linhas de Micrometer)

### Implementar após calibração em staging
- M7, M8, M9, M10 — Alertas de ausência (thresholds dependem do volume real observado)
- M13 — Replay não aplicado (requer melhoria de log em `ManageDeadLetterUseCase`)

### ✅ Entregue pela T23 (gaps de instrumentação críticos)
- `websocket.connection.state` gauge (G1) — detectar "conectado mas silencioso"
- `signal.evaluated.total{runnerId,decision}` counter (G2) — detectar runner travado / rejeições silenciosas
- Log de runner HALTED por DLQ no boot recovery (G3)
- Campos estruturados nas fases 2 do boot (G4)
- Log de idempotência de capital event (G5)

> Alertas habilitados após T23 (queries LogQL/PromQL) estão na seção
> "Alertas habilitados após esta task" de `/.ai/tasks/T23-instrumentation-gaps.md`.

### ✅ Entregue pela T51 (estado operacional)
- `circuit_breaker.current_level`, `circuit_breaker.duration.active.seconds`, `circuit_breaker.runner.suspended.count` por portfolio
- `capital.utilization.global` (portfolio), `capital.utilization.runner` e `capital.reservation.oldest.age.seconds` (runner)
- `runner.lifecycle.transitions.total`, `runner.lifecycle.current_state`, `runner.lifecycle.terminating.duration`, `runner.lifecycle.termination.stuck`
- Health `/actuator/health` com o componente `safeMode` (nível por portfolio)
- Alertas Grafana (grupo `ctrade-operational`): Safe Mode ativo, runner travado em TERMINATING, cancel rate > 10/min, rejection rate > 50% em 5 min, utilização global > 80%

### ✅ Entregue pela T24
- `resilience4j_circuitbreaker_state{name="binance-order-query"}` e `resilience4j_circuitbreaker_calls_*` (Resilience4j)
- Alerta Grafana `ctrade-exchange-circuit-open` (grupo `ctrade-operational`)

### Ainda requer task própria
- `portfolio.balance.available` gauge — alertar em drift de saldo
- `capital.event.retry.count` counter — detectar retry loop silencioso de margem
- Correlação `capital reserved` → `PENDING->SUBMITTED` (gap de lógica)
