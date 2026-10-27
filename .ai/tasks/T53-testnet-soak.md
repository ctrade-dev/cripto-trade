# T53 — Soak Test em Testnet

**Complexidade:** Média
**Responsável:** Claude (spec, procedimento, análise) + mantenedor (execução no ambiente)
**Dependências:** T2 (perfil testnet), T18 (relatório de reconciliação), T21/T51 (alertas), T25/T43/T44/T45/T48
(proteções), T41 (estratégia remota).
**Status:** Pendente — fase 1 pronta para rodar (roteiro abaixo); fases 2–4 bloqueadas pelos pré-requisitos abertos.

---

## Descrição

Antes de qualquer preparação para produção (perfil `prod`, segredos), o mantenedor quer estabilidade
comprovada em testnet por um período longo. Hoje o `GO_LIVE_CHECKLIST` (item 10) e o
`TESTING_ROADMAP` (§4) só dizem "rodar soak test"; não há procedimento, duração nem critério de
aprovação. Esta task define **como rodar, o que observar e quando uma rodada passa**, e mantém o
registro das execuções. Código só entra se uma rodada revelar lacuna — cada achado vira task própria.

## O que já existe (sem código novo)

| Necessidade | Onde |
|---|---|
| Conectar na testnet | perfil `testnet` (`application-testnet.yml`), credenciais `binance.api-key/api-secret` (T2) |
| Estratégias que exercitam cada ramo | estratégias de cenário com teto de ciclos, `CTRADE_SCENARIO_STRATEGIES_ENABLED=true` (T35) |
| Estratégia fora do processo | apps que implementam o contrato, cadastradas por `/api/admin/strategies` (T41, T54) |
| Ciclo de vida do runner | `POST /api/admin/runners/{id}/start|resume|terminate`, `PATCH` de limites (T49) |
| Conferência local × exchange | `GET /api/reconciliation?symbol=&from=&to=` → `binanceOnly`, `localOnly` (T18) |
| Estado operacional | `/actuator/health` (`safeMode`), métricas da T51, Grafana + Loki + alertas Discord (T9/T17/T21) |
| Proteções | drawdown 24h (T43), proteção do lote (T44), Safe Mode manual/automático (T45/T25), PANIC_SELL LIMIT (T48) |
| Dados descartáveis | dado de testnet não é remediado: limpar e recomeçar (`scripts/reset_test_data.sql`) |

## Proposta de fases

Cada fase só começa com a anterior aprovada. Uma rodada reprovada gera achado → task → nova rodada.

1. **Smoke de ramos (curta).** Estratégias de cenário com `maxCycles`: BUY que descansa → cancela,
   fill → SELL que descansa → cancela, round-trip feliz, recusa por capital. Prova que cada ramo
   funciona contra a Binance real.
2. **Soak com estratégia local.** SMA local num símbolo, janela longa e contínua, sem intervenção.
3. **Soak com estratégia remota.** Mesma janela, com a estratégia no `strategy-service-reference`.
4. **Falhas controladas** (sobre o soak da fase 3):
   - restart da aplicação com ordem em voo (boot recovery);
   - queda de rede/WebSocket por alguns minutos (reconexão, watchdog);
   - estratégia remota fora do ar (HOLD implícito, proteção do lote);
   - drill de Safe Mode: `HALT` → `CANCEL_ALL` → `PANIC_SELL` (MARKET e LIMIT) → retomada;
   - kill switch e retomada do runner.

## Critérios de aprovação de uma rodada (proposta)

1. **Reconciliação** da janela: `binanceOnly = 0` e `localOnly = 0`.
2. **DLQ:** nenhuma entrada não resolvida ao fim da janela, ou cada uma explicada e com task.
3. **Capital:** com o runner sem posição e sem ordem em voo, `reserved = 0`; nenhuma reserva mais
   velha que o TTL (`capital_reservation_oldest_age_seconds`).
4. **Transações:** nenhuma `PENDING`/`SUBMITTED` presa além do limiar do watchdog.
5. **Safe Mode automático / halt de runner:** nenhum disparo sem causa identificada.
6. **Logs:** nenhum `ERROR` sem explicação no Loki.
7. **Estabilidade do processo:** memória e threads da JVM sem crescimento contínuo; latência de
   avaliação (`processing time`) e de conciliação estáveis ao longo da janela.
8. **Saldo:** saldo local coerente com o da exchange (checagem de saldo do boot sem `FAIL_DEFICIT` no
   restart do fim da rodada).

## Decisões registradas (2026-10-20)

1. **Duração:** smoke (fase 1) de 1–2h; soak (fases 2 e 3) em rodadas de **24h**, aprovadas com
   **2 rodadas limpas seguidas**; uma rodada final de **72h** antes de encerrar a trilha testnet.
2. **Onde roda:** rodadas acima de 12h numa **VPS** a contratar. O smoke pode rodar na máquina local.
3. **Símbolo:** **BTCUSDT**. Capital do portfolio abaixo do saldo da conta testnet (conferido antes
   de cada rodada; proposta inicial de 1.000 USDT).
4. **Estratégias:** as rodadas mais pesadas usam **várias estratégias remotas ao mesmo tempo**, cada
   uma num processo, para estressar o ctrade (mailbox por runner, chamadas remotas concorrentes,
   capital disputado). Limiares da SMA apertados (≈ 0,2%) para gerar várias idas e voltas por hora. Na testnet, com tick a cada ~1,5 s e janela de 20 ticks, o limiar
   que deu várias idas e voltas por hora foi **0,02%** (T60, rodada curta de 2026-10-22).
5. **Falhas controladas:** as cinco da fase 4, **uma por rodada**; o mantenedor executa a ação, o
   roteiro e a análise ficam com o Claude.
6. **Critérios:** os 8 critérios valem. `ERROR` causado comprovadamente pela testnet é aceito quando
   registrado no histórico da rodada.
7. **Validação no fim da rodada (revista em 2026-10-21):** sem verificação periódica durante a rodada;
   os automatismos do ctrade reagem sozinhos. No fim, o Claude reconstrói a rodada pela linha do tempo
   de eventos e produz o relatório. Desenho na **T59** (eventos persistidos, `GET /api/admin/events`,
   procedimento do relatório).

## Pré-requisitos ainda abertos

- **VPS** contratada e com o ambiente do Docker Compose (bloqueia as fases 2 a 4).
- **Apps de estratégia para o soak:** o ctrade só define o contrato; as estratégias são apps
  independentes, em qualquer linguagem. Para as rodadas pesadas é preciso ter várias apps que
  implementem o contrato (o `strategy-service-reference` serve só a SMA com parâmetros fixos). Spec na
  **T60** (cinco perfis: tendência, reversão, livro, rompimento e caos). Tendência e caos prontos
  em `ctrade-dev/ctrade-strategies` (roteiro abaixo); os outros três em andamento.
- ~~Catálogo remoto estático~~ resolvido pela **T54**: estratégias cadastradas por API, sem restart.
- **Acesso do Claude** aos endpoints, métricas e logs da VPS para o relatório de fim de rodada.

## Riscos e limitações conhecidas

- **A testnet não é o mercado real:** livro raso, preços que pulam e indisponibilidades da própria
  Binance. Fill estranho ou queda da testnet não é bug do ctrade, mas precisa ficar registrado.
- **Reset periódico da testnet** apaga saldos e ordens; rodada que atravessar um reset é descartada.
- **Ferramental de coleta:** hoje a verificação dos critérios é manual (endpoints, Grafana, SQL). Se
  ficar trabalhosa, um relatório de rodada (endpoint ou script) pode virar task.

## Roteiro — Fase 1 (smoke de ramos, máquina local)

Objetivo: em 1–2h, provar contra a Binance testnet que cada ramo das estratégias de cenário funciona e
que, no fim, o registro do ctrade bate com o da exchange. Divisão: **o mantenedor sobe a infra e a
aplicação** (credenciais são dele); **o agente faz as chamadas de API, acompanha e analisa** — a sessão
roda na mesma máquina e alcança `localhost:8080`.

### 0. Antes de começar (mantenedor)

- Chaves da Binance testnet (testnet.binance.vision) com permissão de trade; conferir que a conta tem
  saldo de USDT e BTC.
- Docker rodando. Banco limpo (dado de testnet é descartável): com a aplicação **parada** e o Postgres
  no ar, `./scripts/reset-testnet-db.ps1` apaga todos os dados e mantém o schema (recusa rodar com a
  aplicação em `localhost:8080`). Alternativa mais drástica: `docker compose --profile base down -v`
  apaga o volume inteiro.

### 1. Subir infraestrutura e aplicação (mantenedor)

```powershell
docker compose --profile base --profile observability up -d   # Postgres + Prometheus/Loki/Grafana

$env:SPRING_PROFILES_ACTIVE = "testnet"
$env:BINANCE_API_KEY = "<chave testnet>"
$env:BINANCE_API_SECRET = "<segredo testnet>"
$env:ADMIN_API_KEY = "<uma chave qualquer, passada ao agente>"
$env:CTRADE_SCENARIO_STRATEGIES_ENABLED = "true"
$env:DISCORD_WEBHOOK_URL = "<opcional>"
./gradlew :spring-application:bootRun
```

Avisar o agente quando aparecer `Started CTradeApplication`, informando o valor de `ADMIN_API_KEY`.

### 2. Conferência inicial (agente)

1. `GET /api/boot/status` → `SUCCESS`.
2. `GET /api/exchanges/BINANCE/balances?asset=USDT` → saldo da conta (o capital de cada portfolio fica
   abaixo dele, para a sanidade de saldo passar).
3. `GET /api/admin/operational-state` → sem runners ainda; esperado `OK` (ou só `WARN` explicável).

### 3. Preparação dos cenários (agente)

Um portfolio por cenário, para o resultado de um não contaminar o outro:

- `POST /api/portfolios` `{"name":"smoke-<cenário>","initialCapitalAmount":1000,"currency":"USDT"}`
- `POST /api/portfolios/{id}/runners`
  `{"strategyId":"<id>","symbol":"BTCUSDT","exchangeName":"BINANCE","allowedMarketDataSources":["BINANCE"]}`

| Cenário | `strategyId` | Ramo |
|---|---|---|
| 1 Resting BUY → cancel | `b1000000-0000-4000-a000-000000000001` | BUY 5% abaixo descansa; cancelada; reserva devolvida |
| 2 Fill → resting SELL → cancel | `b1000000-0000-4000-a000-000000000002` | BUY preenche; SELL 5% acima descansa; cancelada; lote destravado |
| 3 Round trip | `b1000000-0000-4000-a000-000000000003` | BUY preenche; SELL preenche; lote fechado com PnL |
| 4 Recusa por capital | `b1000000-0000-4000-a000-000000000004` | BUY 2× o capital: `REJECTED_CAPITAL`, nada vai à exchange |

### 4. Execução, um cenário por vez (agente)

As estratégias de cenário registradas na aplicação não têm teto de ciclos (`maxCycles = 0`): com tick por
segundo, repetem o ciclo a cada poucos segundos. Por isso cada cenário roda **~5 minutos** e é parado.

1. `POST /api/admin/runners/{id}/start` (`X-Reason: smoke fase 1 cenário N`) → `ACTIVE`. O coordenador
   de market data (T56) abre a conexão e a assinatura de BTCUSDT sozinho.
2. Acompanhar a cada minuto: `GET /api/admin/operational-state`, `GET /api/runners/{id}/transactions`,
   `GET /api/portfolios/{pid}/balance`.
3. Conferir o ramo esperado da tabela acima (status das transações, `reserved` voltando a zero, lote
   aberto/fechado).
4. `POST /api/admin/runners/{id}/halt`; esperar as ordens em voo terminarem (`CANCELED`/`FILLED`).

O alerta *Cancel rate > 10/min* dos cenários 1 e 2 é esperado nesta fase.

### 5. Fechamento (agente)

1. Todos os runners em `HALTED`, sem ordem em voo.
2. `GET /api/admin/operational-state?includeExchange=true&exchangeWindowHours=3`:
   `EXCHANGE_TRADE_DIVERGENCE` e `EXCHANGE_BALANCE_DIVERGENCE` sem `CRITICAL`.
3. Relatório da rodada pelo procedimento `docs/monitoring/round-report.md` (T59): linha do tempo de
   eventos, estado no fim, logs, estabilidade e os 8 critérios.
4. Registrar a execução na tabela abaixo; cada achado vira task.

### Aprovação da fase 1

Os 4 ramos se comportaram como na tabela; nenhuma divergência de trades com a exchange; nenhum saldo
local acima do da exchange; DLQ vazia; `reserved = 0` em todos os portfolios no fim; nenhum `ERROR` sem
explicação (erro comprovadamente da testnet é aceito se registrado).

## Roteiro — Apps de estratégia da T60 (fase 3)

As apps vivem em `ctrade-dev/ctrade-strategies` (README de lá: parâmetros, falhas do caos, `GET /stats`).
Disponíveis: **tendência**, **reversão**, **livro**, **rompimento** e **caos**.

### 1. Subir as apps (mantenedor)

```powershell
cd ..\ctrade-strategies
docker compose up -d --build     # trend 8091, reversion 8092, book 8093, breakout 8094, chaos 8095 (127.0.0.1)
```

Na VPS: os serviços entram na rede do Compose do ctrade, sem `ports`, e o `baseUrl` passa a ser
`http://<serviço>:8080/v1`. Token por estratégia em `TREND_TOKEN`/`CHAOS_TOKEN` (o mesmo vai no cadastro).

### 2. Cadastrar (agente)

`POST /api/admin/strategies` com `X-Reason`, uma vez por app (cadastro repetido com a mesma definição
é idempotente):

| App | `strategyRef` | `name` | `version` | `baseUrl` local |
|---|---|---|---|---|
| tendência | `c7a1b2d3-0001-4000-8000-000000000001` | `SoakTrend` | `1.0.0` | `http://127.0.0.1:8091/v1` |
| reversão | `c7a1b2d3-0002-4000-8000-000000000002` | `SoakReversion` | `1.0.0` | `http://127.0.0.1:8092/v1` |
| livro | `c7a1b2d3-0003-4000-8000-000000000003` | `SoakBook` | `1.0.0` | `http://127.0.0.1:8093/v1` |
| rompimento | `c7a1b2d3-0004-4000-8000-000000000004` | `SoakBreakout` | `1.0.0` | `http://127.0.0.1:8094/v1` |
| caos | `c7a1b2d3-0005-4000-8000-000000000005` | `SoakChaos` | `1.0.0` | `http://127.0.0.1:8095/v1` |

Esperado `verification=VERIFIED` e `usable=true`. Use `127.0.0.1`, não `localhost`: o Java resolve
`localhost` para `::1`, o Docker publica só em IPv4, e no Windows a conexão nova trava além do timeout
de 250 ms (a estratégia fica `UNREACHABLE` depois que a conexão keep-alive cai).

### 3. Portfolio e runners (agente; limites combinados com o mantenedor no início da rodada)

- Um portfolio de soak (`initialCapitalAmount` 1000 USDT, decisão 3) com um runner por app em BTCUSDT.
- `maxAllocationPercent` dos runners somando mais que 100% de propósito, para haver disputa de capital
  (`PATCH /api/admin/runners/{id}`).
- O runner do caos com capital pequeno. A guarda de rejeições consecutivas (5) pode parar esse runner;
  o halt aparece na linha do tempo como `AUTOMATIC` e não reprova a rodada se vier do caos.
- `POST /api/admin/runners/{id}/start` em cada um. O id do runner criado vem em `runnerId`.

Durante a rodada, conferir `GET /api/admin/operational-state` junto com as transações: Safe Mode
automático recusa toda BUY com `RISK_VIOLATION`, e essas recusas aparecem como `REJECTED_CAPITAL` na
métrica de decisões, fáceis de confundir com o teto do runner.

### 4. No fim da rodada (agente)

- `GET http://localhost:8095/stats` (e o do trend): contagem por decisão e por falha (`FAULT_<tipo>`).
  Entra no relatório da rodada para separar as falhas de propósito das inesperadas.
- Derrubar uma app (`docker compose stop chaos`) deve deixar só o runner dela em HOLD
  (`STRATEGY_UNAVAILABLE`); os demais seguem operando.

## Registro de execuções

| # | Data | Fase | Duração | Estratégia / símbolo | Resultado | Achados → tasks |
|---|---|---|---|---|---|---|
| 1 | 2026-10-20 | 1 (smoke) | ~15 min | 4 cenários / BTCUSDT, máquina local | Ramos OK; trades 100% iguais à exchange; DLQ vazia. Reprovada por F1 (bug) e por ruído de `ERROR` (F5). | F1–F6 abaixo |
| 2 | 2026-10-20 | 1 (smoke) | ~17 min | 4 cenários / BTCUSDT, máquina local | **Aprovada.** F1, F2, F4 e F5 confirmados corrigidos; 271 fills 100% iguais à exchange; DLQ vazia; zero `ERROR`. | F3 (T57), F7 abaixo |
| 3 | 2026-10-21 | 1 (smoke) | ~8 min | cenários 1, 2 e 3 / BTCUSDT, máquina local, banco da rodada 2 | **Aprovada.** T57 validada: `EXCHANGE_BALANCE_DIVERGENCE` `OK` em 64 leituras durante a operação, com lotes abertos e ordens no livro; trades 100% iguais; DLQ vazia; zero `ERROR`. | F8 abaixo |
| 4 | 2026-10-27 | 3 (apps T60) | ~42 min | 5 apps / BTCUSDT, máquina local | **Reprovada.** SELL acima do lote foi à exchange, ficou `SUBMITTED` e o watchdog pôs o portfolio em Safe Mode. | T61, T62; `baseUrl` em `127.0.0.1` (detalhes na T60) |
| 5 | 2026-10-27 | 3 (apps T60) | ~1h55 | 5 apps / BTCUSDT, máquina local, banco limpo | **Aprovada.** T61 e T62 validadas; 30 idas e voltas (tendência, reversão, rompimento); zero `ERROR`; estado operacional `OK` o tempo todo. | Livro sem preenchimento: calibração da app (T60) |

### Rodada 1 — detalhes (2026-10-20)

- **Cenário 1 (resting BUY → cancel):** 4 BUYs, todas `CANCELED`; `reserved` voltou a 0.
- **Cenário 2 (fill → resting SELL → cancel):** 1 BUY `FILLED`, 4 SELLs `CANCELED`; lote `OPEN` destravado
  no fim (0.001 BTC deixado aberto — fechar posição à mão exige confirmação do mantenedor).
- **Cenário 3 (round trip):** 65 BUY + 65 SELL `FILLED` em ~2 min; 65 lotes fechados; `reserved` 0; PnL
  realizado +0.00335 USDT; `totalFeesPaid` 0.
- **Cenário 4 (recusa por capital):** 25 `REJECTED_CAPITAL`, nenhuma ordem na exchange.
- **Feed (T56):** market data e stream de usuário da Binance abertos sozinhos, sem chamada manual.
- **Comparação com a exchange (T55-C2):** `EXCHANGE_TRADE_DIVERGENCE` OK (todos os fills das 2h batem).

**Achados:**

- **F1 — Bug (alto): `CANCELED` do stream da Binance não é conciliado.** `ExecutionReportProcessor` lê só
  o campo `c`; no `executionReport` de cancelamento a Binance põe em `c` o id do pedido de cancelamento e
  o id original em `C`. Todo cancelamento feito pelo ctrade (estratégia, Safe Mode, proteção do lote,
  operador) só é conciliado ~60s depois, pelo watchdog. Nesse intervalo a reserva fica presa, o lote
  travado, a estratégia reenvia o cancelamento a cada tick e a Binance responde `-2011 Unknown order`
  (77 `ERROR` na rodada); o cancelamento do operador chegou a voltar `502 EXCHANGE_QUERY_FAILED`.
- **F2 — Bug meu (T55-C): `CAPITAL_RESERVED_WITHOUT_ORDER` falso positivo.** O custo da BUY executada fica
  em `reserved` até a SELL do lote casar (`GlobalBalance.confirmExecution`); a verificação ignora lotes
  abertos e acusou CRITICAL no portfolio do cenário 2.
- **F3 — Desenho: comparação de saldo por portfolio contra a conta inteira.** Com vários portfolios na
  mesma conta, cada um dá `WARN_SURPLUS` (1000 local × 9917 na conta) e a soma dos portfolios acima do
  saldo da conta não seria detectada. Vale para a sanidade do boot e para a T55-C2.
- **F4 — Menor: `retrievedAt` do saldo da exchange com data de 13 dias atrás** (a confirmar se é o
  `updateTime` da conta com nome enganoso ou cache).
- **F5 — Ruído: `outboundAccountPosition` sem processador.** A Binance envia atualização de saldo da
  conta após cada fill; o ctrade registra `ERROR` (147 na rodada).
- **F6 — Operação: `halt` não cancela ordens que descansam no livro** (por desenho). Após o `halt`, a
  ordem LIMIT continua com capital reservado até cancelar à mão; o runbook deve dizer isso.

**Correções (bugfix pós-rodada 1, 2026-10-20):** F1 (`C` no `executionReport`), F2 (lote aberto justifica
reserva), F4 (`retrievedAt` = instante da consulta; `accountUpdatedAt` à parte), F5 (eventos de conta
ignorados sem erro) e F6 (runbook). **F3 continua aberto** e depende de decisão do mantenedor; até lá, o
runbook descreve a limitação. Próximo passo: repetir a fase 1.
- A confirmar: comissão zero na testnet (`totalFeesPaid` 0 em 130 fills).

### Rodada 2 — detalhes (2026-10-20, após o bugfix)

Banco limpo com `scripts/reset-testnet-db.ps1`; boot `SUCCESS`; `operational-state` `OK` antes de começar.

- **Cenário 1 (resting BUY → cancel):** 73 BUYs `CANCELED` em ~5 min (rodada 1: 4), cada `CANCELED`
  conciliado na hora pelo stream; `reserved` 0. **F1 corrigido.**
- **Cenário 2 (fill → resting SELL → cancel):** 1 BUY `FILLED`, 66 SELLs `CANCELED`. Com o lote aberto
  (82.853 USDT reservados), `CAPITAL_RESERVED_WITHOUT_ORDER` ficou `OK`. **F2 corrigido.** A SELL que
  descansava após o `halt` foi cancelada pelo operador em segundos (rodada 1: `502`). Lote de 0.001 BTC
  deixado aberto (desenho do cenário; fechar à mão exige o mantenedor).
- **Cenário 3 (round trip):** 135 BUY + 135 SELL `FILLED`; `reserved` 0; PnL realizado −0.01401 USDT;
  `available` 999.98599 (bate com 1000 + PnL).
- **Cenário 4 (recusa por capital):** a BUY de 2× o capital recusada com `RUNNER_LIMIT_EXCEEDED` (78
  vezes, `WARN`); a transação é desfeita, não fica registro e nada vai à exchange.
- **Saldo da exchange:** `retrievedAt` = instante da consulta e `accountUpdatedAt` à parte. **F4
  corrigido.** USDT da conta caiu 82.867 desde a rodada 1 = custo do lote do cenário 2 (82.853) + PnL do
  cenário 3 (0.014).
- **Logs:** zero `ERROR` na rodada inteira (rodada 1: 77 de `-2011` + 147 de `outboundAccountPosition`).
  **F5 corrigido.**
- **Comissão:** `totalFeesPaid` 0 em 271 fills — **confirmado: testnet sem comissão**; a fee no ativo
  (T46) só será exercitada em produção ou com conta que cobre comissão.
- **Comparação com a exchange:** `EXCHANGE_TRADE_DIVERGENCE` com 271 casados, 0 só locais, 0 divergentes;
  os 131 "só na exchange" são todos da rodada 1 (20:21–20:23, antes do reset), conferido por
  `GET /api/exchanges/BINANCE/trades` em janelas antes/depois do reset (ver F7).
  `EXCHANGE_BALANCE_DIVERGENCE` `WARN_SURPLUS` nos 4 portfolios (F3, esperado até a T57).
- **Fim:** 4 runners `HALTED`, nenhuma ordem em voo, DLQ vazia, 21 ações de operador auditadas com
  `X-Reason`.

**Achado:**

- **F7 — Operação (baixo): janela de comparação de trades atravessa o reset do banco.** Depois de limpar
  o banco, `includeExchange` com janela que alcança antes do reset acusa `CRITICAL` pelos fills apagados.
  Só acontece em testnet (dado descartável); procedimento: usar janela que comece depois do reset ou
  conferir os horários dos "só na exchange". Sem task por ora.

### Rodada 3 — detalhes (2026-10-21, após a T57)

Sem limpar o banco (estado da rodada 2: 4 portfolios, lote aberto de 0.001 BTC no cenário 2). Boot
`SUCCESS`; `EXCHANGE_BALANCE_DIVERGENCE` `OK` já na subida, com o lote aberto (critério 4 da T57).

- **Conferência por exchange (T57):** `GET /api/admin/operational-state?includeExchange=true` a cada ~5s,
  64 leituras, todas `OK`:
  - cenário 3 (75 idas e voltas em ~2,5 min): sem falta passageira; os fills fecham em menos de 1s e
    nenhuma leitura pegou ordem em voo (`PENDING` não apareceu);
  - cenários 1 e 2 juntos (BUY e SELL descansando no livro): `inFlight` ≈79 USDT e 0.001 BTC, cobertos
    pelo `locked` da conta.
  - fim: USDT esperado 3833.70832 (4 portfolios − custo de 2 lotes abertos) × conta 9751.12366; BTC 0.002
    × 1.003; `unallocated` 5917.41534 USDT e 1.001 BTC (critério 1 da T57).
- **Cenários:** 1 → 106 BUYs `CANCELED` no total; 2 → 99 SELLs `CANCELED`; 3 → 217 BUY / 216 SELL
  `FILLED` (o `halt` caiu entre a BUY e a SELL: segundo lote de 0.001 BTC aberto, por desenho). Ordens
  que descansavam após o `halt` canceladas pelo operador (F6).
- **Ainda não exercitado em testnet:** o caminho de `DEFICIT` (HALT no boot e na avaliação periódica) e o
  `PENDING`; cobertos por testes unitários e de integração da T57.

**Achado:**

- **F8 — Observabilidade (baixo): sem preço logo após o restart.** Até um runner ativar o feed de market
  data (T56), o drawdown do portfolio com lote aberto é pulado (`WARN drawdown: no market price`, 3 vezes
  em 20s). Por desenho (equity incompleto gravaria pico/vale falso), mas com todos os runners parados e
  lote aberto o portfolio fica sem drawdown até alguém ativar um runner do símbolo. Avaliar se o
  coordenador de demanda (T56) deve assinar símbolos com lote aberto. → T58, entregue e validada na
  testnet em 2026-10-21 (restart com runners parados: preço em 6s, nenhum aviso de drawdown).

## Fontes

- `docs/GO_LIVE_CHECKLIST.md` (item 10), `docs/TESTING_ROADMAP.md` (§4 e §5)
- `docs/OPERATIONS_RUNBOOK.md` (Safe Mode, gatilhos automáticos, estratégia remota, estado operacional)
- `.ai/tasks/T2-testnet-config.md`, `.ai/tasks/T35-testnet-scenario-strategies.md`,
  `.ai/tasks/T18-trade-reconciliation-report.md`
