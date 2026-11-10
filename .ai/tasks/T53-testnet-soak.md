# T53 — Soak Test em Testnet

**Complexidade:** Média
**Responsável:** Claude (spec, procedimento, análise) + mantenedor (execução no ambiente)
**Dependências:** T2 (perfil testnet), T18 (relatório de reconciliação), T21/T51 (alertas), T25/T43/T44/T45/T48
(proteções), T41 (estratégia remota).
**Status:** Em andamento — rodada final de 72h aprovada (rodada 12, 2026-11-08); fase 4 em testes curtos, roteiro abaixo.

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
| Estratégias que exercitam cada ramo | estratégias de cenário da T35, usadas na fase 1; desde a T65 só existem como dublês dos testes de integração |
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
   velha que o TTL (`capital_reservation_oldest_age_seconds`), salvo LIMIT comprovadamente aberta na exchange
   (decisão de 2026-11-06).
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
5. **Falhas controladas:** ~~as cinco da fase 4, uma por rodada; o mantenedor executa a ação~~. Substituída
   pela decisão de 2026-11-09 (testes curtos executados pelo Claude).
6. **Critérios:** os 8 critérios valem. `ERROR` causado comprovadamente pela testnet é aceito quando
   registrado no histórico da rodada.
7. **Validação no fim da rodada (revista em 2026-10-21):** sem verificação periódica durante a rodada;
   os automatismos do ctrade reagem sozinhos. No fim, o Claude reconstrói a rodada pela linha do tempo
   de eventos e produz o relatório. Desenho na **T59** (eventos persistidos, `GET /api/admin/events`,
   procedimento do relatório).

## Decisão registrada (2026-11-05): rodada única com checkpoints

Substitui a sequência de rodadas de 24h e a validação só no fim (decisões 1 e 7 acima) a partir da próxima rodada.

- **Rodada única:** implementar antes as correções conhecidas e largar sempre uma rodada de **72h**, sem rodadas
  longas separadas para validar um item.
- **Checkpoints em 3h, 8h e 24h:** os 8 critérios são avaliados em cada um, além do fim. Os dados são coletados na VPS,
  sem depender do computador do mantenedor.
- **Aprovação:** chegar a 72h sem reprovação conclui os testes. Reprovou em qualquer ponto, encerra, corrige e
  recomeça do zero.
- **O que reprova:** um critério descumprido. Rejeição provocada de propósito pelo `SoakChaos` não reprova, e
  `ANOMALY` é um aviso a analisar, não uma reprovação automática.
- **Rodadas curtas** continuam valendo para validar uma correção específica antes de largar.

## Decisão registrada (2026-11-06): reserva antiga com ordem aberta na exchange

Vale a partir da rodada 12, decisão do mantenedor.

- **Não reprova:** reserva acima de 24h de uma LIMIT confirmada como aberta na exchange. O `WARN` de
  `CAPITAL_RESERVATION_STALE` é registrado no histórico da rodada, com a confirmação.
- **Reprova:** reserva acima de 24h cuja ordem não existe na exchange, porque aí o capital vazou.
- **Motivo:** o critério procura reserva vazada. Uma ordem real e visível é o alerta funcionando, e o `SoakTrend` e o
  `SoakBreakout` não cancelam as próprias compras.
- **Efeito conhecido:** esses runners podem ficar horas sem abrir outra ordem. Isso é calibração de estratégia, fora
  dos critérios da rodada.

## Decisão registrada (2026-11-09): testes curtos depois da rodada de 72h

Decisão do mantenedor, com a rodada 12 aprovada. Substitui a decisão 5 e a rodada única de 2026-11-05 daqui em diante.

- **72h só quando necessário:** uma nova rodada longa só se uma mudança justificar.
- **Testes curtos à vontade:** cada falha controlada e cada validação vira um teste curto na VPS, quantos forem
  necessários, pelo roteiro da fase 4 abaixo.
- **Quem executa:** o Claude provoca as falhas na VPS com os scripts de `deploy/vps`. Antes de começar, informa ao
  mantenedor a ordem e o tempo de cada teste. Ações da coluna da direita dos limites de autonomia do runbook
  (`PANIC_SELL`, descer HALT automático, mudar limites de risco ou a política de liquidação) continuam pedindo
  autorização, teste a teste.

## Pré-requisitos ainda abertos

- **VPS** contratada e com o ambiente do Docker Compose (bloqueia as fases 2 a 4). Spec na **T63**;
  procedimento em `deploy/vps/README.md` (subir, cadastrar as apps, limpar o banco, túnel).
- **Apps de estratégia para o soak:** o ctrade só define o contrato; as estratégias são apps
  independentes, em qualquer linguagem. Para as rodadas pesadas é preciso ter várias apps que
  implementem o contrato (o `strategy-service-reference` serve só a SMA com parâmetros fixos). Spec na
  **T60** (cinco perfis: tendência, reversão, livro, rompimento e caos), concluída: as cinco em
  `ctrade-dev/ctrade-strategies` (roteiro abaixo).
- ~~Catálogo remoto estático~~ resolvido pela **T54**: estratégias cadastradas por API, sem restart.
- **Acesso do Claude** aos endpoints, métricas e logs da VPS para o relatório de fim de rodada (túnel SSH,
  T63).

## Riscos e limitações conhecidas

- **A testnet não é o mercado real:** livro raso, preços que pulam e indisponibilidades da própria
  Binance. Fill estranho ou queda da testnet não é bug do ctrade, mas precisa ficar registrado.
- **Reset periódico da testnet** apaga saldos e ordens; rodada que atravessar um reset é descartada.
- **Ferramental de coleta:** `scripts/round_report.py` (T64) junta as evidências dos critérios num
  rascunho; a classificação e o veredito continuam manuais.

## Roteiro — Fase 1 (smoke de ramos, máquina local)

> **Histórico.** A fase 1 foi aprovada. Desde a T65 o ctrade não embute estratégia: as de cenário e a
> flag `CTRADE_SCENARIO_STRATEGIES_ENABLED` saíram. Repetir esta fase exige portar os quatro cenários
> para apps do `ctrade-strategies` e cadastrá-las por `/api/admin/strategies`.

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

Na VPS (T63) as apps sobem no mesmo Compose do ctrade, sem `ports`, com `baseUrl`
`http://<serviço>:8080/v1` e token por estratégia: `deploy/vps/up.sh` sobe tudo e
`deploy/vps/register-strategies.sh` faz o cadastro do passo 2 (`deploy/vps/README.md`).

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

- Um portfolio de soak (`initialCapitalAmount` 1000 USDT, decisão 3) com um runner por app em BTCUSDT:
  `{"strategyId":"<ref>","symbol":"BTCUSDT","exchangeName":"BINANCE","allowedMarketDataSources":["BINANCE"]}`.
  Sem `allowedMarketDataSources` a criação é recusada (T67).
- `maxAllocationPercent` dos runners somando mais que 100% de propósito, para haver disputa de capital
  (`PATCH /api/admin/runners/{id}`).
- O runner do caos com capital pequeno. A guarda de rejeições consecutivas (5) pode parar esse runner;
  o halt aparece na linha do tempo como `AUTOMATIC` e não reprova a rodada se vier do caos.
- `POST /api/admin/runners/{id}/start` em cada um. O id do runner criado vem em `runnerId`.

Durante a rodada, conferir `GET /api/admin/operational-state` junto com as transações: Safe Mode
automático recusa toda BUY com `RISK_VIOLATION`, e essas recusas aparecem como `REJECTED_CAPITAL` na
métrica de decisões, fáceis de confundir com o teto do runner.

### 4. No fim da rodada (agente)

Na VPS, `deploy/vps/round-start.sh` faz os passos 1 a 3 e `deploy/vps/round-close.sh` fecha a rodada
sozinho no horário (halt, cancelamento das ordens em voo, `locked`, conciliação, `/stats` e rascunho do
relatório); `deploy/vps/round-watch.sh`, iniciado pela largada, acusa anomalia durante a rodada em `~/round/ANOMALY` e no Discord (`deploy/vps/README.md`).

- `GET http://localhost:8095/stats` (e o do trend): contagem por decisão e por falha (`FAULT_<tipo>`).
  Entra no relatório da rodada para separar as falhas de propósito das inesperadas.
- Derrubar uma app (`docker compose stop chaos`) deve deixar só o runner dela em HOLD
  (`STRATEGY_UNAVAILABLE`); os demais seguem operando.

## Roteiro — Fase 4 (falhas controladas e testes curtos)

Decisão de 2026-11-09. Cada teste roda na VPS sobre uma sessão curta de `round-start.sh` (banco limpo, as cinco apps,
limites da rodada 7) e só começa com os 5 runners `ACTIVE` e o `SoakBreakout` fora de `WAITING_HISTORY`.

**Aprovação de todo teste:** os 8 critérios da T53 na janela do teste, mais o esperado do próprio teste. `ERROR`,
`HALT` e `WARN` provocados pelo teste são aceitos quando a causa é a falha provocada e ficam registrados. Um teste
reprovado vira achado, depois task, e o teste se repete com a correção.

### Testes

| # | Teste | Como provocar | Esperado | Autorização | Duração |
|---|---|---|---|---|---|
| F4.1 | Restart com ordem em voo | Parar o ctrade com ordem `SUBMITTED` do `SoakBook`. Três vezes: parada de ~10 s; parada de ~3 min, atravessando o prazo de 120 s da ordem; parada com lote aberto | Boot `SUCCESS`; `phase3` acha a ordem e aplica o status da exchange; o `SoakBook` volta a cancelar e recolocar; runners com histórico ficam em `WAITING_HISTORY` e voltam sozinhos; conciliação sem divergência; balanço do boot sem `FAIL_DEFICIT` | Não | ~40 min |
| F4.2 | Queda curta de WebSocket | Bloquear os dois canais (`MARKET` e `USER_DATA`) por 2 min, com o REST livre | `STREAM_DISCONNECTED`, `MARKET_DATA_STALE` e `ORDER_DISPATCH_BLOCKED` acusam; decisões descartadas como `DISPATCH_BLOCKED`; reconexão sozinha; nenhum `HALT`; estado volta a `OK` | Não | ~25 min |
| F4.3 | Queda longa de WebSocket | Bloquear os dois canais por 6 min, além das 10 tentativas de reconexão (5 s × tentativa, ~4,6 min) | Esgotadas as tentativas, `ERROR` de falha crítica e `HALT` automático de todos os runners (`CriticalConnectionFailureHandler`); a conexão volta depois do bloqueio; retomada manual dos runners | Sim: retomar os runners parados pelo sistema | ~35 min |
| F4.4 | Queda total da exchange | Bloquear WebSocket e REST (`api.testnet.binance.vision`) por 2 min, com ordem em voo | Watchdog falha sem entrar em Safe Mode (menos de 3 ciclos); circuit breaker do REST pode abrir e fechar; tudo volta sozinho | Não | ~25 min |
| F4.5 | Confirmação perdida | Logo depois de um cancelamento do `SoakBook`, descartar o que chega do `ws-api` por ~60 s: a ordem nova chega à exchange e a resposta não volta | A ordem fica `PENDING` só do nosso lado; depois da carência (10 min), o watchdog a acha por `clientOrderId` e aplica o status real; nenhuma ordem só na exchange no fim; conciliação sem divergência | Não | ~30 min |
| F4.6 | Ordem perdida | Mesmo momento, descartando o que sai para o `ws-api` e derrubando a conexão: a ordem nunca chega à exchange | Depois da carência, o watchdog não acha a ordem e a marca `EXPIRED`, devolvendo a reserva ou o lote; nenhum `HALT` | Não | ~30 min |
| F4.7 | Estratégia fora do ar | Parar o container do `SoakTrend` por 10 min, com lote aberto se houver; depois subir de novo | `STRATEGY_UNAVAILABLE` e HOLD implícito; runner segue `ACTIVE`; ao voltar, a app responde e o runner segue sem ação manual | Não | ~25 min |
| F4.8 | Proteção do lote sem estratégia | Com o `SoakChaos` segurando lote e a app parada, baixar `maxHoldingDuration` do runner dele para `PT5M` | `PROTECTIVE_EXIT`: venda MARKET do lote sem consultar a estratégia | Sim: mudar limite de risco | ~20 min |
| F4.9 | Kill switch e retomada | `POST /api/admin/runners/halt`; esperar 5 min; retomar um a um | Todos `HALTED` na hora; nenhuma ordem nova; LIMIT no livro segue (comportamento documentado); retomada sem divergência | Sim: retomar runners | ~20 min |
| F4.10 | Drill de Safe Mode, MARKET | `HALT` → `CANCEL_ALL` → `PANIC_SELL` → descer um nível por vez → retomar | Cada nível com o efeito do runbook; `PANIC_SELL` vende os lotes a mercado; `locked` 0 e nenhum lote aberto no fim; descida e retomada sem divergência | Sim: `PANIC_SELL` | ~30 min |
| F4.11 | Drill de Safe Mode, LIMIT | Igual ao F4.10, com `liquidationOrderType=LIMIT` nos runners | SELL LIMIT 1% abaixo do último preço, escalada para MARKET depois de 3 ticks sem preencher (T48) | Sim: `PANIC_SELL` e política de liquidação | ~30 min |

### Testes que dependem de desenvolvimento

| # | Teste | Desenvolvimento antes | Esperado | Duração |
|---|---|---|---|---|
| F4.12 | Cliente desconectado | ctrade: `GlobalExceptionHandler` deixa de registrar `ERROR` (e de responder 500) quando o cliente fecha a conexão | Ler `/transactions` e fechar no meio: nenhum `ERROR` | ~15 min |
| F4.13 | Calibração das apps de soak | ctrade-strategies: prazo de cancelamento nas compras do `SoakTrend` e do `SoakBreakout`, a partir de `requestedAt`, e uma venda do `SoakBook` que acompanhe o preço | Nenhuma ordem parada além do prazo; fills por hora sem buracos longos | ~2h |
| F4.14 | Carga | `round-start.sh` com N runners por app (ex.: 20 no total), com alocação dividida | Latência de avaliação e mailbox estáveis; sem tick descartado por atraso; os 8 critérios | ~1h |

### Desenvolvimento necessário

| Item | Onde | Para | Tipo |
|---|---|---|---|
| `fault.sh` | `deploy/vps` | Provocar as falhas de F4.1 a F4.7 com segurança: regra de firewall só no container do ctrade, sempre removida no fim (`trap`), e evidências antes e depois em `$ROUND_DIR/fault-<teste>.log` | Script |
| `drill.sh` | `deploy/vps` | F4.8 a F4.11 pela API, com `X-Reason`; `PANIC_SELL` só com confirmação explícita na linha de comando | Script |
| `round-start.sh` | `deploy/vps` | Duração em minutos para os testes curtos; N runners por app para a F4.14 | Script |
| `GlobalExceptionHandler` | ctrade | F4.12 | Código, PR |
| Prazos e preço das apps | ctrade-strategies | F4.13 | Código, PR |

Os scripts saem em PR antes do primeiro teste. As duas mudanças de código só começam com o aval do mantenedor.

## Registro de execuções

| # | Data | Fase | Duração | Estratégia / símbolo | Resultado | Achados → tasks |
|---|---|---|---|---|---|---|
| 1 | 2026-10-20 | 1 (smoke) | ~15 min | 4 cenários / BTCUSDT, máquina local | Ramos OK; trades 100% iguais à exchange; DLQ vazia. Reprovada por F1 (bug) e por ruído de `ERROR` (F5). | F1–F6 abaixo |
| 2 | 2026-10-20 | 1 (smoke) | ~17 min | 4 cenários / BTCUSDT, máquina local | **Aprovada.** F1, F2, F4 e F5 confirmados corrigidos; 271 fills 100% iguais à exchange; DLQ vazia; zero `ERROR`. | F3 (T57), F7 abaixo |
| 3 | 2026-10-21 | 1 (smoke) | ~8 min | cenários 1, 2 e 3 / BTCUSDT, máquina local, banco da rodada 2 | **Aprovada.** T57 validada: `EXCHANGE_BALANCE_DIVERGENCE` `OK` em 64 leituras durante a operação, com lotes abertos e ordens no livro; trades 100% iguais; DLQ vazia; zero `ERROR`. | F8 abaixo |
| 4 | 2026-10-27 | 3 (apps T60) | ~42 min | 5 apps / BTCUSDT, máquina local | **Reprovada.** SELL acima do lote foi à exchange, ficou `SUBMITTED` e o watchdog pôs o portfolio em Safe Mode. | T61, T62; `baseUrl` em `127.0.0.1` (detalhes na T60) |
| 5 | 2026-10-27 | 3 (apps T60) | ~1h55 | 5 apps / BTCUSDT, máquina local, banco limpo | **Aprovada.** T61 e T62 validadas; 30 idas e voltas (tendência, reversão, rompimento); zero `ERROR`; estado operacional `OK` o tempo todo. | Livro sem preenchimento: calibração da app (T60) |
| 6 | 2026-10-28 | smoke VPS (T63) | 1h | 5 apps / BTCUSDT, VPS Oracle ARM, banco limpo | **Aprovada.** 22 idas e voltas (tendência 7, reversão 10, rompimento 4, livro 1); zero `ERROR`; estado operacional `OK` o tempo todo; só a porta 22 aberta. | — |
| 7 | 2026-10-28/29 | 3 (apps T60), 1ª de 24h | 24h | 5 apps / BTCUSDT, VPS Oracle ARM, banco limpo | **Reprovada** (critério 6). 349 fills, 348 iguais à exchange (o outro é a órfã de antes da rodada); Safe Mode `HALT` automático às 07:39Z por falha de ~1h40 na consulta REST de ordens da Binance; 4 `ERROR` perdidos num buraco de ~12h do Loki. Boot do restart `SUCCESS`; JVM estável. | F9, F10 abaixo → T66 |
| 8 | 2026-10-30 | 3 (apps T60), 2ª de 24h | ~12h (encerrada) | 5 apps / BTCUSDT, VPS Oracle ARM, banco limpo | **Reprovada** (critérios 4, 5 e 6). Pavio às 01:20:45Z (dia seguinte ao ataque à Binance): SELL de proteção a mercado preencheu em parte e o resto voltou `EXPIRED`; o ctrade não aceita `PARTIAL → EXPIRED`, a transação ficou presa e o watchdog pôs o portfolio em `HALT` às 01:23:54Z; ~60 `ERROR`/h até o fim. 67 fills, 67 iguais à exchange. Loki com 100% de cobertura (T66 validada). | F11, F12, F13 abaixo → T67 |
| 9 | 2026-10-30/31 | 3 (apps T60), 3ª de 24h | 24h | 5 apps / BTCUSDT, VPS Oracle ARM, banco limpo | **Aprovada** (1ª de 24h limpa). T67 validada em operação: zero `ERROR`, nenhum `HALT`, estado operacional `OK` a rodada toda; 532 fills, 532 iguais à exchange; DLQ vazia; Loki com 100% de cobertura. | F14 abaixo |
| 10 | 2026-10-31/11-01 | 3 (apps T60), 4ª de 24h | 24h | 5 apps / BTCUSDT, VPS Oracle ARM, banco limpo | **Aprovada** (2ª de 24h limpa seguida). Zero `ERROR`, nenhum evento automático, estado operacional `OK` a rodada toda; 168 fills, 168 iguais à exchange; DLQ vazia; Loki com 100% de cobertura. Largada e fechamento pelos scripts da VPS (PR #174), fechamento no horário. | F14 de novo (rompimento) |
| 11 | 2026-11-02/05 | 3 (apps T60), final de 72h | 72h | 5 apps / BTCUSDT, VPS Oracle ARM, banco limpo | **Reprovada** (critérios 3, 5 e 6). Limpa até 55h47. Às 20:26Z de 04/11 o canal `USER_DATA` caiu sem mensagem de erro e não reconectou; o `MARKET` caiu igual às 01:38Z de 05/11. Envio bloqueado, runner do livro em `HALT` por recusas, sem preço até o fim, 1309 `ERROR`. 938 fills, 938 iguais à exchange; DLQ vazia. | F15, F16, F17, F18 abaixo |
| 12 | 2026-11-05/08 | 3 (apps T60), final de 72h com checkpoints | 72h | 5 apps / BTCUSDT, VPS Oracle ARM, banco limpo | **Aprovada** (1ª de 72h limpa). Checkpoints de 3h, 8h e 24h e fim sem reprovação; 886 fills, 888 de 888 iguais à exchange; 1 `ERROR` explicado; reconexão do `MARKET` sem perda; restart final com boot `SUCCESS`. T68 e T69 validadas. | Cliente desconectado como `ERROR`; calibração das apps; monitor (PR #178) |
| Curta 69 | 2026-11-05 | Validação da T68, fora da numeração | 1h | 5 apps / BTCUSDT, VPS Oracle ARM, banco limpo | Sem veredito de soak. 16 quedas provocadas em cerca de 9 min: 16 reconexões, 9 sinais descartados, nenhuma rejeição, runners ativos, 20 de 20 fills iguais à exchange. | - |
| Curta 70 | 2026-11-05 | Validação da T69, fora da numeração | 1h | 5 apps / BTCUSDT, VPS Oracle ARM, banco limpo | Sem veredito de soak. `USER_DATA` fora por 49 s: cancelamento vencido descartado na queda e enviado depois da reconexão. Conciliação 27 de 27, `locked` 0. 27 fills contra 41 da curta 69, sem causa medida. | Acompanhar fills e avaliações na rodada 12 |
| Curta 101 | 2026-11-09 | 4, sessão 1 (F4.2, F4.4) | 2h30 | 5 apps / BTCUSDT, VPS Oracle ARM, banco limpo | **F4.2 e F4.4 passaram.** WebSockets fora por 2 min: `CRITICAL` com `STREAM_DISCONNECTED`, `MARKET_DATA_STALE` e `ORDER_DISPATCH_BLOCKED`, reconexão 4 s depois da rede voltar, sem `HALT`. Exchange inteira fora por 2 min: watchdog falhou 2 ciclos, Safe Mode seguiu `NORMAL`, tudo voltou sozinho. 192 `ERROR`, todos das tentativas de reconexão provocadas. Fechamento: comparação de 3h com a exchange OK, DLQ vazia. | F19, F20 |
| Curta 102 | 2026-11-09 | 4, sessão 1 (F4.7, F4.1) | 1h20 | 5 apps / BTCUSDT, VPS Oracle ARM, banco limpo | **F4.7 e F4.1 passaram.** App do `SoakTrend` fora por 10 min: 161 `STRATEGY_UNAVAILABLE`, runner `ACTIVE`, volta sem ação manual. Três restarts (10 s, 3 min passando o prazo de 120 s do `SoakBook`, 30 s com lote aberto): boot `SUCCESS`, `phase3` recuperou as ordens em voo, sanidade `OK`. Zero `ERROR`. Conciliação 45 de 45. | F21; F7 de novo |
| Curta 103 | 2026-11-09 | 4, sessão 2 (F4.5, F4.6) | 1h25 | 5 apps / BTCUSDT, VPS Oracle ARM, banco limpo | **F4.5 e F4.6 passaram.** Confirmação perdida: a SELL do `SoakBook` chegou à exchange e ficou `PENDING` do nosso lado; o próprio `SoakBook` a cancelou 2 min depois e o `CANCELED` do stream a conciliou (o watchdog não chegou a agir). Ordem perdida: a SELL nunca chegou à exchange e virou `EXPIRED` pelo watchdog depois da carência, com o lote destravado. 2 `ERROR`, as quedas provocadas do `USER_DATA`. Nenhum `HALT`. | F22 |
| Curta 104 | 2026-11-09 | 4, sessão 3 (F4.2 de novo, F4.3, F4.9) | 1h50 | 5 apps / BTCUSDT, VPS Oracle ARM, banco limpo, com PR #181 | **F4.2 e F4.9 passaram; F4.3 passou no `HALT`, falhou na volta.** F4.2: tentativas 1, 2, 3… com espera crescente e uma fila por canal; os dois canais voltaram 7 e 12 s depois da rede; 18 `ERROR` (eram 95). F4.3: `HALT` automático 4 min 35 s depois do corte; o `MARKET` voltou sozinho, o `USER_DATA` não, e a retomada respondeu `409`; destravado pela API. F4.9: `HALTED` na hora, nenhuma ordem nova em 5 min, retomada um a um sem `ERROR`. Conciliação 54 de 54. | F23, F24 |
| Curta 105 | 2026-11-10 | 4, F4.3 de novo | 45 min | 5 apps / BTCUSDT, VPS Oracle ARM, banco limpo, com PR #183 | **F4.3 passou.** WebSockets fora por 6 min: os dois canais esgotaram as 10 tentativas às 13:12:29Z e o `HALT` automático parou os 5 runners; `STREAM_DISCONNECTED` seguiu visível como `WARN`; o coordenador reabriu o `USER_DATA` a cada 30 s com todos parados; os dois canais voltaram sozinhos 29 s depois da rede; retomada `200` sem intervenção nas conexões e estado `OK`. 49 `ERROR`, todos da queda provocada. Conciliação 38 de 38. | F23, F24 resolvidos |
| Curta 106 | 2026-11-10 | 4, sessão 4 (F4.5 de novo, F4.8, F4.10, F4.11) | 2h30 | 5 apps / BTCUSDT, VPS Oracle ARM, banco limpo, com PR #183 | **F4.8, F4.10 e F4.11 passaram; a F4.5 não exercitou o watchdog.** F4.5: a SELL ficou `PENDING` com o `SoakBook` parado e foi resolvida pelo fill do stream, não pelo watchdog. F4.8: venda MARKET do lote do `SoakChaos` 1 s depois de `PT5M`. F4.10: `HALT` → `CANCEL_ALL` (3 ordens no livro → 0) → `PANIC_SELL` (2 lotes vendidos a mercado em menos de 10 s) → descida até `NORMAL`. F4.11: SELL LIMIT 1% abaixo do último preço, preenchidas na hora, sem escalar para MARKET. 1 `ERROR`, provocado. Conciliação 135 de 135. | F4.13 confirmado |
| Curta 107 | 2026-11-10 | 4, sessão 5 (F4.7 de novo; F4.14 não abriu) | 45 min | 5 apps / BTCUSDT, VPS Oracle ARM, banco limpo, com PR #184 | **F4.7 passou.** `SoakTrend` fora por 10 min: `STRATEGY_UNAVAILABLE` como `WARN` cerca de 60 s depois da primeira falha, com runner, estratégia, `failingSince` e motivo; runner `ACTIVE` o tempo todo; estado `OK` 21 s depois da volta e decisões normais sem ação manual. A F4.14 não abriu: `RUNNERS_PER_APP=4` bate no índice único de runner ativo por estratégia, símbolo, exchange e portfólio (F25). 0 `ERROR`. Conciliação 52 de 52. | F21 resolvido; F25 |

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

### Rodada 7 — detalhes (2026-10-28 14:41:43Z → 2026-10-29 14:41:43Z, VPS)

Versões: ctrade `6fec21c`, ctrade-strategies `23009ca` (imagens `linux/arm64`, Loki 2.9.0, Prometheus
3.5.0). Portfolio `a682d53c` (1000 USDT), um runner por app. Coleta com `scripts/round_report.py` (T64),
completada à mão onde o Loki não tinha dado.

| # | Critério | Evidência | Resultado |
|---|---|---|---|
| 1 | Conciliação | `/api/reconciliation` na janela exata: 349 fills, 348 iguais, 0 divergentes, 0 só no ctrade, 1 só na exchange (`order-8113829`, SELL 0.00148, 17:56Z) | Explicado: ordem que descansava desde antes da limpeza do banco (prefixo de runner que não existe nesta rodada); a limpeza deixou passar uma ordem no livro (F6) |
| 2 | DLQ | nenhuma entrada | OK |
| 3 | Capital | `CAPITAL_*` `OK` no fim | OK |
| 4 | Transação presa | `TRANSACTION_STUCK` `OK`; a SELL `68809bbb` ficou 16h no livro, legítima | OK |
| 5 | Safe Mode / halt | 1 `HALT` automático (`WATCHDOG_STUCK`, 07:39:15Z) | Causa identificada (F10), mas a raiz da falha da exchange não |
| 6 | `ERROR` | 4 no `logback_events_total` (06:37, 06:45, 07:40, 12:22Z); nenhum no Loki | **Reprovado**: conteúdo perdido (F9) |
| 7 | Estabilidade | heap mínimo 43.6 → 46.5 MB, threads 50 → 51, avaliação média 10.9 → 10.7 ms; WebSocket só o corte de 24h | OK |
| 8 | Saldo no restart | boot `SUCCESS` (3.2s), sem `FAIL_DEFICIT`; `EXCHANGE_*` `OK` depois | OK |

- **Ordens:** tendência 53 idas e voltas, reversão 79, rompimento 19, livro 22 (436 SELLs canceladas pelo
  `SHOULD_CANCEL` da app), caos 2 BUYs e 1 SELL. Resultado realizado −38.92 USDT.
- **Caos:** `/stats` com ~400 falhas de cada tipo (`FAULT_SERVER_ERROR`, `TIMEOUT`, `DISCONNECT`,
  `MALFORMED`, `INCOHERENT`); o ctrade tratou todas como `HOLD` (`WARN strategy unavailable`). Nenhuma
  ordem saiu de resposta com falha.
- **MAX_LOSS acima de 5%:** 5 saídas de proteção, 4 delas às 15:48:32Z com perda de ~10% do custo. Foi
  um pavio da testnet: o BTC foi de ~80 600 a ~72 000 e voltou em 2s; as vendas a mercado executaram no
  fundo do livro raso (médias 74 727 e 68 861). Às 18:51:28Z o mesmo, de ~78 900 a ~73 900. A proteção
  agiu como desenhada; o tamanho da perda vem do livro da testnet. Sem task.
- **Fim:** runners em `HALTED`; a SELL do livro que descansava cancelada pelo operador; `locked` BTC = 0
  antes do restart. Safe Mode `HALT` continua ativo (baixar fica com o mantenedor).

**Achados:**

- **F9 — Observabilidade (alto): logs perdidos sem aviso.** O Loki não recebeu nada do ctrade de ~00:45Z
  a ~13:05Z (12h), com o ctrade operando normalmente (sinais, ordens e scrapes do Prometheus contínuos) e
  sem erro no Loki. O appender (loki4j 1.5.2) descarta em silêncio. O log do container não serviu de
  reserva: com `TRACE` ligado, a rotação de 3 × 20 MB guarda só ~1h30. Os 4 `ERROR` da rodada se perderam,
  e o `round_report.py` mostrou "ERROR: 0" porque só lê o Loki. → T66. **Causa (T66):** o envio não
  parou; o Loki da VPS (configuração da T63, índice TSDB + compactor) perdia o que saía da memória do
  ingester, e na manhã seguinte não devolvia mais nada da rodada.
- **F10 — Exchange (médio): consulta REST de ordens falhou por ~1h40 e acionou o HALT.** O circuito
  `binance-order-query` registrou falhas de 07:38Z a 09:21Z (83 minutos com falha, aberto ~100 min); antes
  e depois, ~200 consultas/h com sucesso. O envio de ordens pelo WebSocket seguiu normal. Com a consulta
  falhando, o watchdog não conseguiu confirmar a SELL que descansava no livro por 3 ciclos (45s) e pôs o
  portfolio em `HALT`, como desenhado na T25. Não dá para saber se foi queda da testnet ou bloqueio por
  limite de peso (HTTP 418/429): não há métrica de status do cliente REST e os logs se perderam (F9).
  → T66 (visibilidade). A sensibilidade do watchdog a falha da exchange (HALT em 45s de consulta
  indisponível) é decisão de risco do mantenedor.

### Rodada 8 — detalhes (2026-10-30 00:27:52Z → 12:23:18Z, VPS, encerrada antes das 24h)

Versões: ctrade `1a07986` (com a T66), ctrade-strategies `b0fb837` (imagens `linux/arm64`, Loki 3.4.2,
Prometheus 3.5.0). Portfolio `5e80ed6b` (1000 USDT), um runner por app com os limites da rodada 7 (40% cada,
caos 20%, `maxLossPercentPerLot` 5%). Coleta com `scripts/round_report.py`. Contexto: começou ~4h depois do
ataque à Binance de 29/10 (20:05Z, ~US$ 6,3 bi; depósitos e saques suspensos), com o mercado em queda.

| # | Critério | Evidência | Resultado |
|---|---|---|---|
| 1 | Conciliação | `/api/reconciliation` na janela: 67 fills, 67 iguais, 0 divergentes | OK |
| 2 | DLQ | nenhuma entrada | OK |
| 3 | Capital | `CAPITAL_*` `OK` no fim | OK |
| 4 | Transação presa | SELL `45baab11` (livro) em `PARTIAL` desde 01:20:45Z; na exchange já `EXPIRED` | **Reprovado** (F11) |
| 5 | Safe Mode / halt | `HALT` automático `WATCHDOG_STUCK` às 01:23:54Z, motivo com `lastFailure` (T66) | **Reprovado**: bug do ctrade (F11) |
| 6 | `ERROR` | 667 no Loki e no Prometheus: 663 da F11, 2 do `EXPIRED` repetido, 1 `Unknown order sent`, 1 do HALT | **Reprovado** (F11) |
| 7 | Estabilidade | heap mínimo 48.5 → 40.4 MB, threads 49 → 49, avaliação média 9.2 → 7.0 ms | OK (12h) |
| 8 | Saldo no restart | sem restart na rodada; boot inicial `SUCCESS` | — |

- **Largada:** a primeira, às 00:23Z, saiu sem `allowedMarketDataSources` nos runners (F12);
  `MARKET_DATA_STALE` acusou `CRITICAL` em 3 min, sem nenhuma ordem. Banco limpo de novo e largada às
  00:27:52Z.
- **Ordens:** tendência 12 idas e voltas, reversão 11, rompimento 5, livro 3 (16 SELLs canceladas pelo
  `SHOULD_CANCEL`), caos 1. Resultado realizado −63.25 USDT, quase todo do pavio.
- **Pavio às 01:20:45Z:** 5 saídas de proteção `MAX_LOSS` no mesmo segundo (perdas de 5% a 15% do
  custo). A SELL a mercado do livro preencheu 0.00107 de 0.00154 BTC (média 63 998) e a Binance expirou o
  resto por falta de liquidez. A da tendência expirou sem preencher, e o `EXPIRED` chegou duas vezes.
- **Logs (T66):** cobertura de 100% hora a hora; ERROR 667 × 667, WARN 13 397 × 13 401. Os ERROR das
  01:20Z voltaram do Loki 11h depois, com o stack trace.
- **Fim:** encerrada às 12:23:18Z (o `HALT` bloqueava toda BUY desde 01:23Z). Runners `HALTED`; `locked`
  BTC e USDT = 0. Ficam no banco: o lote do livro em `CLOSING` (0.00047 BTC, travado pela SELL presa) e
  um lote do caos `OPEN` (0.00056 BTC). Safe Mode `HALT` ativo; a próxima limpeza do banco zera tudo.

**Achados:**

- **F11 — Bug (alto): `EXPIRED` depois de preenchimento parcial não é conciliado.** `Transaction.expire()`
  só aceita `PENDING`/`SUBMITTED`; a Binance expira o resto de uma ordem a mercado que esgota o livro
  (e `EXPIRED_IN_MATCH` cai no mesmo status). A transação fica em `PARTIAL`, o lote fica travado, o
  watchdog tenta a cada ciclo e aciona o `HALT`. Junto: `EXPIRED` repetido gera `ERROR` (`Cannot expire
  from status: EXPIRED`), o `MarginReleaseBuilder` devolveria a reserva inteira de uma BUY `EXPIRED` com
  execução parcial, e o cancelamento de uma ordem que já terminou na exchange (`-2011 Unknown order
  sent`) sobe como `ERROR`. → T67.
- **F12 — Operação (baixo): runner criado sem fonte de market data.** `POST /api/portfolios/{id}/runners`
  sem `allowedMarketDataSources` aceita o runner e o `start` também; o runner nunca recebe tick. O
  `MARKET_DATA_STALE` pegou em 3 min. Erro meu de operação (o roteiro da fase 1 traz o campo). → T67
  decide entre recusar na criação ou só documentar no roteiro da VPS.
- **F13 — Ferramenta (baixo): `round_report.py` acusa WARN "sem linha no Loki" quando a consulta bate no
  limite.** Com mais de 5000 WARN, compara o Prometheus com as linhas devolvidas (cortadas no limite) e
  não com a contagem do Loki (`count_over_time`). Na rodada: "8414 WARN sem linha" falso. → T67.

### Rodada 9 — detalhes (2026-10-30 13:11:20Z → 2026-10-31 13:11:20Z, VPS)

Versões: ctrade `ed5ba59` (com a T67), ctrade-strategies `b0fb837` (imagens `linux/arm64`, Loki 3.4.2,
Prometheus 3.5.0). Portfolio `9aea13ca` (1000 USDT), um runner por app com os limites da rodada 7. Coleta
com `scripts/round_report.py` na janela exata. Monitor na VPS (`~/ctrade-watch.sh`) a cada 5 min, com
checkpoint a cada 4h.

| # | Critério | Evidência | Resultado |
|---|---|---|---|
| 1 | Conciliação | `/api/reconciliation` da largada até o fechamento: 532 fills, 532 iguais, 0 divergentes, 0 só de um lado | OK |
| 2 | DLQ | nenhuma entrada | OK |
| 3 | Capital | `CAPITAL_*` `OK` no fim | OK |
| 4 | Transação presa | `TRANSACTION_STUCK` `OK` em todos os checkpoints e no fim | OK |
| 5 | Safe Mode / halt | nenhum evento automático; o portfolio ficou em `NORMAL` | OK |
| 6 | `ERROR` | 0 no Loki e 0 no Prometheus | OK |
| 7 | Estabilidade | heap mínimo 46.0 → 40.9 MB, threads 49 → 48, avaliação média 9.4 → 8.8 ms (máx. 420 → 391 ms) | OK |
| 8 | Saldo no restart | sem restart na rodada; boot inicial `SUCCESS` | — |

- **Largada:** a criação de runner sem `allowedMarketDataSources` devolveu `400` na VPS (T67).
- **Ordens:** tendência 102 idas e voltas, reversão 107, rompimento 32, livro 24 (509 SELLs canceladas pelo
  `SHOULD_CANCEL` da app), caos 1 BUY. Resultado realizado −1.78 USDT. BTC entre ~76 500 e ~78 100, sem
  pavio; nenhuma saída de proteção e nenhuma expiração parcial (o caminho novo da T67 não foi exercido em
  operação, só nos testes).
- **WARN:** 2782 no Loki × 2782 no Prometheus. Todos do caos: ~400 de cada falha (`ERROR_RESPONSE`,
  `TIMEOUT`, `DISCONNECT`, `MALFORMED`, `INCOHERENT`); o ctrade tratou como `HOLD`, descartou a SELL acima
  do lote e ignorou o cancelamento de transação que não existe.
- **Fim:** o monitor caiu junto com a sessão do agente pouco antes do fim, e o fechamento só rodou às
  16:28Z (runners seguiram `ACTIVE` ~3h17 além da janela, sem incidente: 0 `ERROR`, conciliação 532/532
  até 16:35Z). Runners em `HALTED`; 3 LIMIT no livro canceladas pelo operador; `locked` BTC e USDT = 0.
  Ficam lotes `OPEN` do livro (0.00154 BTC) e do caos (0.00051 BTC); a próxima limpeza do banco zera tudo.

**Achados:**

- **F14 — Estratégia (baixo): LIMIT longe do preço deixa o runner parado por horas.** Com
  `maxPendingOrders` 1, a BUY da tendência (76 828) e da reversão (76 962) ficaram no livro de 14:38Z a
  ~17:30Z depois que o preço subiu, e a saída do rompimento (*trailing stop* como SELL LIMIT a 78 029)
  ficou acima do preço depois que ele caiu. As apps não reposicionam nem cancelam ordem velha. É
  comportamento das apps de estratégia (decisão delas, fora do ctrade); não reprova a rodada. Registrar
  para a calibração das apps (T60).

### Rodada 10 — detalhes (2026-10-31 16:43:06Z → 2026-11-01 16:43:06Z, VPS)

Versões: ctrade `ed5ba59`, ctrade-strategies `b0fb837` (as da rodada 9). Portfolio `61ca4c8d` (1000 USDT),
limites da rodada 7. Largada pela primeira versão de `round-start.sh` e fechamento por `round-close.sh`,
que acordou às 16:43:06Z sem a sessão do agente; monitor `round-watch.sh` a cada 5 min enquanto a máquina
do mantenedor estava ligada (desligada à noite: a conferência da noite foi feita de uma vez de manhã, pelas
métricas, eventos e conciliação).

| # | Critério | Evidência | Resultado |
|---|---|---|---|
| 1 | Conciliação | `/api/reconciliation` na janela exata: 168 fills, 168 iguais, 0 divergentes, 0 só de um lado | OK |
| 2 | DLQ | nenhuma entrada | OK |
| 3 | Capital | `CAPITAL_*` `OK` no fim | OK |
| 4 | Transação presa | `TRANSACTION_STUCK` `OK` em todas as conferências e no fim | OK |
| 5 | Safe Mode / halt | nenhum evento automático na rodada; portfolio em `NORMAL` | OK |
| 6 | `ERROR` | 0 no Loki e 0 no Prometheus | OK |
| 7 | Estabilidade | heap mínimo 45.1 → 41.1 MB, threads 49 → 48, avaliação média 9.1 → 8.4 ms (máx. 370 → 261 ms); sem restart do container | OK |
| 8 | Saldo no restart | sem restart na rodada; boot inicial `SUCCESS` | — |

- **Ordens:** tendência 36 idas e voltas, reversão 23, livro 17 (488 SELLs canceladas pelo `SHOULD_CANCEL`),
  rompimento 7, caos 1 BUY. Resultado realizado +1.28 USDT. Mercado parado (BTC ~77 500–77 800, faixa de
  100–200 USDT por hora na noite de sábado), por isso menos fills que na rodada 9.
- **WARN:** 2939 no Loki × 2939 no Prometheus, todos do caos (falhas de propósito tratadas como `HOLD`,
  SELL acima do lote descartada, cancelamento de transação que não existe ignorado) e 1 SELL sem lote.
- **Linha do tempo:** só ações do operador. As 2 "fim da rodada 0" às 17:07Z são o teste do
  `round-close.sh` com runners fictícios (halt 404), sem efeito na rodada.
- **Fim:** runners `HALTED` às 16:43:06Z; 4 LIMIT canceladas (202); em voo 0; `locked` BTC e USDT = 0.
  Ficam lotes `OPEN` do livro (0.00153 BTC) e do caos (0.00051 BTC).
- **F14 de novo:** a SELL do rompimento ficou acima do preço de 18:47Z em diante, no mercado parado.

### Rodada 11 — detalhes (2026-11-02 12:39:09Z → 2026-11-05 12:39:09Z, VPS)

Versões: ctrade `f630f4b`, ctrade-strategies `b0fb837`. Portfolio `c1a89765` (1000 USDT), limites da rodada 7.
Largada e fechamento pelos scripts da VPS; o fechamento rodou no horário, sem a sessão do agente. Arquivos em
`~/round-11/` e log completo até 12:02Z em `~/round-11-evidence/` (VPS).

| # | Critério | Evidência | Resultado |
|---|---|---|---|
| 1 | Conciliação | `/api/reconciliation`: 938 fills, 938 iguais, 0 divergentes, 0 só de um lado; `EXCHANGE_TRADE_DIVERGENCE` `OK` | OK |
| 2 | DLQ | nenhuma entrada não resolvida | OK |
| 3 | Capital | `CAPITAL_RESERVATION_STALE` `WARN`: 2 BUY com reserva há mais de 24h e as 3 BUY em voo não canceladas no fim (F17) | **Reprovado** |
| 4 | Transação presa | `TRANSACTION_STUCK` `OK`; as 3 em voo estão de fato abertas na exchange | OK |
| 5 | Safe Mode / halt | `HALT` automático do livro às 22:36:07Z: causa identificada, mas é efeito da F15 | **Reprovado** |
| 6 | `ERROR` | 1309 no ctrade (1305 de assinatura em socket fechado a cada 30s; os demais da F15 e do `HALT`) | **Reprovado** |
| 7 | Estabilidade | heap mínimo 42.7 → 47.0 MB, threads 49 → 42, sem restart; avaliação média 8.3 ms na 1ª hora e sem dado na última (sem tick) | OK no que deu para medir |
| 8 | Saldo no restart | sem restart na rodada; boot inicial `SUCCESS`; `EXCHANGE_BALANCE_DIVERGENCE` `OK` | — |

- **Linha do tempo (UTC):**
  - 04/11 13:01:01: Binance fecha o `MARKET` com código 1001 após 48h22; reconectou em 2 s. O caminho de
    fechamento limpo funciona.
  - 04/11 20:26:00: `USER_DATA` cai com `EOFException` sem mensagem (F15). Envio de ordens bloqueado.
  - 04/11 22:36:02: fill do SELL do livro, que chegou sem o stream de usuário. 5 BUY recusadas
    (`dispatch blocked: connection lost`) e `HALT` às 22:36:07.
  - 05/11 01:37:56: último tick. `MARKET` cai às 01:38:00, também com `EOFException` sem mensagem.
  - 05/11 12:39: fechamento: halt 200 nos 5 runners, cancelamento 202 nas 3 BUY, mas nada cancelado (F17).
- **Ordens:** tendência 268 idas e voltas, reversão 125, rompimento 40, livro 35, caos 1 BUY. Resultado
  realizado -7.99 USDT (apps de teste, sem significado).
- **WARN:** 7298, os padrões já conhecidos do caos e o SELL acima do lote descartado.
- **Fim:** 3 BUY LIMIT abertas na exchange (`locked` USDT 494.76) e lote `OPEN` do caos (0.00051 BTC), sem
  proteção avaliando desde o último tick. Ficam assim até o ctrade ser reiniciado.
- **Ainda não exercitado:** reconexão depois de falha abrupta, porque ela não acontece com a F15.

- **F15 — Bug (alto): falha abrupta do WebSocket nunca reconecta.** `OkHttp3ListenerConverter.onFailure` publica
  o evento com o `Throwable`. `ConnectionFailedHandler` chama `ConnectionResultDto.failure(reason, id, cause)`,
  que monta um `Map.of` com `cause.getMessage()`; com mensagem nula (`EOFException`) dá `NullPointerException`
  (`ConnectionResultDto.java:109`). O handler aborta antes de `setConnectionResult` e de `scheduleReconnect`: a
  conexão segue `CONNECTED` e o bloqueio de envio nunca é liberado. O outro `failure` com `cause` no mesmo
  arquivo tem o mesmo `Map.of`. Só o restart do ctrade recupera. → task a abrir.
- **F16 — Observabilidade (alto): `STREAM_DISCONNECTED` não vê o stream morto.** O check lê o mesmo estado que
  a F15 deixou errado e ficou `OK` até o fim, com o gauge `websocket_connection_state` em 0 nos dois canais.
  De 20:26Z a 22:36Z o estado operacional não acusou nada, com o envio de ordens bloqueado.
  `EnsureMarketDataFeedUseCase` também confia nesse estado e repete a assinatura em socket fechado em vez de
  reabrir a conexão. → task a abrir.
- **F17 — Bug (médio): cancelamento devolve 202 e não faz nada com o envio bloqueado.** `OrderDispatchAdapter.cancel`
  só registra `cancel: blocked` e retorna. O operador recebe 202, a ordem segue aberta e o capital preso. O
  comentário do código prevê que a estratégia reemita o cancelamento, o que não cobre o cancelamento do operador
  nem runner parado. → decidir com a F15.
- **F18 — Operação (médio): monitor da rodada depende da sessão do agente.** O SSH do `round-watch.sh` caiu logo
  depois das 17:51Z de 04/11 e terminou com código 0 (o `tail` do pipeline esconde o erro), antes da primeira
  falha. Ninguém olhou a rodada por cerca de 18h. O monitor deve rodar na VPS e avisar por conta própria.

### Rodada 12 — detalhes (2026-11-05 23:16:44Z → 2026-11-08 23:16:44Z, VPS)

Versões: ctrade `7698773` (contrato 2.0, T68 e T69), ctrade-strategies `4d6296b`. Monitor trocado no ar em 07/11 18:23Z
pelo `2b55c8d` (PR #178), sem reiniciar o ctrade. Portfolio `59047142` (1000 USDT), limites da rodada 7.

| # | Critério | Evidência | Resultado |
|---|---|---|---|
| 1 | Conciliação | 888 fills, 888 iguais, 0 divergentes, 0 só de um lado; `EXCHANGE_TRADE_DIVERGENCE` `OK` em 72h | OK |
| 2 | DLQ | nenhuma entrada | OK |
| 3 | Capital | `CAPITAL_RESERVATION_STALE` `WARN` de 07/11 16:21Z a 08/11 ~08:04Z: BUY do `SoakTrend` confirmada aberta (`NEW` no watchdog, `locked` igual ao total). Não reprova pela decisão de 2026-11-06. No fim, `locked` 0 e reserva só dos 2 lotes abertos | OK |
| 4 | Transação presa | `TRANSACTION_STUCK` `OK` o tempo todo; 0 em voo depois do fechamento | OK |
| 5 | Safe Mode / halt | nenhum evento automático; os 5 `HALT` são do fechamento | OK |
| 6 | `ERROR` | 1: `Broken pipe` em `/transactions` às 13:18:06Z de 07/11, leitura do mantenedor truncada com `head` | OK (explicado) |
| 7 | Estabilidade | heap mínimo 43.2 → 42.0 MB, threads 50 → 48, avaliação média 9.5 → 9.1 ms, p99 do `/evaluate` ~3.5 ms | OK |
| 8 | Saldo no restart | restart em 2026-11-09 12:04:58Z: boot `SUCCESS`, `phase2.sanity` `OK` (BTC esperado 0.00208, os 2 lotes abertos), 0 `ERROR` | OK |

- **T68:** o `MARKET` fechou às 00:01:01Z de 08/11 e reconectou em 17 s, sem `ERROR`. O `USER_DATA` não caiu; o
  corte abrupto segue validado nas rodadas curtas 69 e 70.
- **T69:** o `SoakBook` pediu 2053 cancelamentos, todos entre 120 e 170 s (mais 1 do fechamento), com a métrica `CANCEL` igual ao número de
  cancelamentos. Sem queda do `USER_DATA`, o descarte com envio bloqueado não ocorreu nesta rodada (validado na curta 70).
- **Fluxo:** 612 fills nas primeiras 24h e 24 nas 14h seguintes, com `SoakBook`, `SoakReversion` e `SoakTrend` parados
  em LIMIT que o preço não alcançou; depois voltou. O `SoakBook` ficou cerca de 19h recolocando a mesma venda.
- **Achados (backlog):** cliente desconectado vira `ERROR` no `GlobalExceptionHandler`; calibrar prazos e
  sensibilidade das apps de soak; monitor só avisava o primeiro `ERROR` (corrigido no PR #178).


### Fase 4, sessão 1 — detalhes (2026-11-09, VPS, curtas 101 e 102)

A sessão 1 do roteiro (F4.2, F4.4, F4.7, F4.1) precisou de duas sessões curtas: o laço de espera do agente usava
`pgrep -f` com o nome do teste e achava a si mesmo, e cerca de 2h passaram entre a F4.2 e a F4.4. Esperas agora olham o
arquivo de saída do teste.

| Teste | Resultado | Evidência |
|---|---|---|
| F4.2 Queda curta de WebSocket | Passou | `fault-ws` 14:05:33Z–14:07:34Z; reconexão dos dois canais às 14:07:38Z e envio liberado; estado `OK` às 14:09:34Z |
| F4.4 Queda total da exchange | Passou | `fault-exchange` 16:10:26Z–16:12:28Z; watchdog `failed=2` em 16:11:20Z e 16:12:20Z, `recovered` a partir de 16:13:20Z; Safe Mode `NORMAL` |
| F4.7 Estratégia fora do ar | Passou | `SoakTrend` parado 16:35:27Z–16:45:29Z; 161 `STRATEGY_UNAVAILABLE` e nenhuma outra decisão; depois `HOLD`/`BUY`/`SELL` normais e catálogo `VERIFIED` |
| F4.1 Restart com ordem em voo | Passou | paradas de 10 s, 180 s e 30 s com lote; boot `SUCCESS` nas três; `phase3` com `hasErrors=false`; runners com histórico em `WAITING_HISTORY` voltaram sozinhos; 0 `ERROR` |

**Achados:**

- **F19 — Bug (alto): o contador de reconexão não sobe.** Nas 95 tentativas da F4.2 (e nas 96 da F4.4) o log diz sempre
  `Scheduling reconnect attempt 1/10`. `LinearBackoffReconnectionStrategy` só passa `attempt + 1` quando a reconexão
  lança exceção na hora; a falha assíncrona da conexão nova chega como um evento de falha novo, com `attemptCount` 1.
  Resultado: o intervalo fica em 5 s e as 10 tentativas nunca se esgotam, então o `HALT` de
  `CriticalConnectionFailureHandler` não dispara numa queda longa. **A F4.3 fica bloqueada até a correção.**
- **F20 — Bug (médio): cadeias de reconexão em paralelo no `MARKET`.** A partir de ~20 s de queda o `MARKET` agenda duas
  tentativas a cada 5 s (14:05:52Z e 14:05:53Z, e assim por diante): 70 tentativas em 2 min contra 25 do `USER_DATA`.
  Provavelmente a mesma causa da F19 (cada falha agenda uma reconexão nova sem cancelar a anterior); a investigar junto.
- **F21 — Observabilidade (baixo): app de estratégia fora não aparece no estado operacional.** Na F4.7 o estado ficou
  `OK` durante os 10 min; o catálogo segue `VERIFIED` e só as métricas e o `WARN` do log mostram o
  `STRATEGY_UNAVAILABLE`. Um operador que olha só o estado não vê a app caída.
- **F7 de novo:** o fechamento da curta 102 deu `EXCHANGE_TRADE_DIVERGENCE` `CRITICAL` porque a janela de 2h do relatório
  atravessa o reset do banco e pega 57 fills da curta 101 (`exchangeOnly`). Na janela da própria sessão, 45 de 45.
  Sessões curtas seguidas pedem o relatório com a janela a partir de `START`.


### Fase 4, sessão 2 — detalhes (2026-11-09, VPS, curta 103)

| Teste | Resultado | Evidência |
|---|---|---|
| F4.5 Confirmação perdida | Passou | entrada do `ws-api` descartada 19:47:18Z–19:47:49Z; SELL `5b7a6d44` criada às 19:47:19Z ficou `PENDING`; às 19:49:25Z o `SoakBook` pediu o cancelamento, a exchange conhecia a ordem e o `CANCELED` do stream a conciliou e destravou o lote. O caminho do watchdog por `clientOrderId` não foi exercido: a estratégia chegou antes |
| F4.6 Ordem perdida | Passou | saída para o `ws-api` descartada 19:53:47Z–19:54:17Z; SELL `7606e0cf` nunca chegou à exchange; `EXPIRED` às 20:03:51Z (carência de 10 min) e lote destravado; nenhum `HALT` |

**Achados:**

- **F22 — Bug (médio): cancelamento repetido de ordem que a exchange não conhece.** Na F4.6, de 19:55:48Z a 20:03:47Z
  o `SoakBook` pediu o cancelamento da SELL `PENDING` a cada tick; cada pedido foi à exchange e voltou
  `ORDER_NOT_FOUND` (222 vezes em 8 min, log `cancel: order unknown to the exchange ... awaiting its final status`).
  Nada quebrou, mas é cota de API gasta à toa e, com vários runners, pode encostar no limite da Binance. O
  `CancelSignalHandler` deveria segurar novos cancelamentos da mesma ordem `PENDING` desconhecida até o watchdog dar o
  status final.
- **F4.5 sem o caminho do watchdog:** para exercitá-lo, repetir com a app do `SoakBook` parada logo depois do corte
  (a ordem fica sem pedido de cancelamento até a carência).


### Fase 4, sessão 3 — detalhes (2026-11-09, VPS, curta 104)

Primeira sessão com o PR #181 (F19, F20) na VPS.

| Teste | Resultado | Evidência |
|---|---|---|
| F4.2 Queda curta de WebSocket (de novo) | Passou | `fault-ws` 22:49:26Z–22:51:27Z; `Scheduling reconnect attempt 1/10 … 7/10` com espera de 5 s × tentativa, uma fila por canal; `Reconnected after 5 failed attempt(s)` no `USER_DATA` às 22:51:34Z e `after 7` no `MARKET` às 22:51:39Z (o `MARKET` conta também as falhas do coordenador); estado `OK`; 18 `ERROR` |
| F4.3 Queda longa de WebSocket | `HALT` passou; volta falhou (F23) | `fault-ws` 23:03:29Z–23:09:31Z; `Max reconnect attempts (10)` no `MARKET` e `HALT` dos 5 runners às 23:08:04Z; `MARKET` de volta às 23:09:40Z pelo coordenador (lote aberto); `USER_DATA` em `ERROR` até a intervenção; retomada às 23:17:10Z respondeu `409 Exchange BINANCE is not available`; destravado às 23:18:39Z (ver F24) e retomada às 23:19:02Z; a SELL `ca2e9b61` que estava no livro seguia `NEW` e foi conciliada quando o stream voltou |
| F4.9 Kill switch e retomada | Passou | `halt` 23:19:38Z, todos `HALTED`; nenhuma ordem nova até a retomada; a SELL LIMIT do `SoakBook` seguiu no livro; retomada de um em um, 23:24:39Z–23:28:04Z, estado `OK`, nenhum `ERROR` novo |

Fechamento: em voo 0, `locked` 0, conciliação 54 de 54. As anomalias do monitor são as quedas provocadas, os `HALT`
da F4.3 e da F4.9 e os dois 500 da F24.

**Achados:**

- **F23 — Bug (alto): o `HALT` automático por queda longa trava o sistema.** Três coisas se juntam. (1) A última
  tentativa agendada do `USER_DATA` (8/10, 23:07:48Z) foi descartada como `stale reconnect task` porque a tentativa
  do coordenador já tinha trocado o id da conexão; com a fila única do PR #181, o descarte encerrou a fila do canal sem
  agendar a próxima. Antes do `HALT`, o coordenador produzia uma falha nova a cada 30 s e a fila voltava. (2) Depois do
  `HALT` o coordenador só pede o `USER_DATA` para runner `ACTIVE`, e não há nenhum. (3) A retomada exige a exchange
  disponível, ou seja, o `USER_DATA` conectado. Ninguém sai disso sozinho, e uma ordem no livro pode encher sem o
  ctrade ver. O texto do runbook (PR #181) que diz que a reconexão fica com o coordenador vale só para o `MARKET`.
- **F24 — Bug (médio): a conexão manual do runbook não destrava.** `POST /api/websocket/connect` respondeu 500
  (`Invalid transition from CONNECTED to CONNECTING`) porque abre o `MARKET` primeiro e ele já estava conectado.
  `POST /api/websocket/disconnect` respondeu 500 (`Invalid transition from ERROR to DISCONNECTING`) no `USER_DATA`,
  mas derrubou o `MARKET` antes de falhar; o `connect` seguinte abriu os dois canais.

**F4.3 repetida (curta 105, 2026-11-10, com o PR #183):** passou. `fault-ws` 13:07:54Z–13:13:56Z; `Max reconnect
attempts (10)` no `USER_DATA` e no `MARKET` às 13:12:29Z e `HALT` dos 5 runners (o segundo `halt-all` não tinha o que
parar); depois do `HALT`, `STREAM_DISCONNECTED` como `WARN` e `marketData: connecting user stream` a cada 30 s;
`Reconnected after 11 failed attempt(s)` nos dois canais às 13:14:25Z e envio liberado; retomada às 13:23:17Z com
`200` nos 5 runners, sem conexão manual. Duas SELL que estavam no livro encheram durante a queda e foram conciliadas
na volta. F23 e F24 resolvidos.

### Fase 4, sessão 4 — detalhes (2026-11-10, VPS, curta 106)

Autorizados pelo mantenedor: `PANIC_SELL`, liquidação `LIMIT` e `maxHoldingDuration` `PT5M`.

| Teste | Resultado | Evidência |
|---|---|---|
| F4.5 Confirmação perdida (de novo) | Inconclusivo: resolvida pelo stream | A SELL `6963eeeb` ficou `PENDING` com o `book` parado às 14:50:00Z; às 14:55:27Z o fill chegou pelo stream (`PENDING` → `SUBMITTED` → `FILLED`) antes da carência do watchdog. A busca por `clientOrderId` segue coberta só por teste unitário. As vendas do `SoakBook` enchem rápido demais para a ordem esperar a carência |
| F4.8 Proteção do lote sem estratégia | Passou | Lote `b6e348ab` do `SoakChaos`, aberto às 14:43:21Z; app parada às 14:59:45Z; `PT5M` às 14:59:46Z; venda MARKET `8877bcc6` (`protection: MAX_HOLDING`) `FILLED` às 14:59:47Z; limite voltou a `null` e a app subiu de novo |
| F4.10 Drill de Safe Mode, MARKET | Passou | `HALT` às 15:16:05Z: nenhuma BUY nova, uma BUY LIMIT que estava no livro encheu; `CANCEL_ALL` às 15:17:07Z: 3 ordens no livro → 0; `PANIC_SELL` às 15:18:08Z: 2 lotes vendidos a mercado em menos de 10 s, 0 lotes e 0 em voo; descida de um nível por vez até `NORMAL` às 15:21:15Z |
| F4.11 Drill de Safe Mode, LIMIT | Passou (sem escalada) | `liquidationOrderType=LIMIT` às 15:22:16Z, com 4 lotes abertos; `PANIC_SELL` às 15:24:22Z: SELL LIMIT a 73621.21 (1% abaixo do último preço), executadas a 74364.86 na hora, porque a LIMIT abaixo do mercado já cruza o livro; a escalada para MARKET depois de 3 ticks não foi exercitada; `NORMAL` às 15:28:30Z e liquidação de volta a `MARKET` em todos os runners (conferido no banco) |

Fechamento: em voo 0, `locked` 0, conciliação 135 de 135. Um `ERROR`, da queda do `USER_DATA` provocada na F4.5.

**Observações:**

- As BUY LIMIT do `SoakTrend`, do `SoakBreakout`, do `SoakReversion` e do `SoakChaos` ficaram de 17 a 24 min no
  livro sem encher. É o que a F4.13 quer corrigir: falta prazo de cancelamento nas compras das apps de soak.
- Restam dois caminhos sem teste de ponta a ponta: o watchdog por `clientOrderId` (F4.5) e a escalada da liquidação
  LIMIT (F4.11). Os dois precisam de uma ordem que fique parada. Com a F4.13 isso pode ser provocado de propósito.

### Fase 4, sessão 5 — detalhes (2026-11-10, VPS, curta 107)

Primeira sessão com o PR #184 (F21) na VPS.

| Teste | Resultado | Evidência |
|---|---|---|
| F4.7 Estratégia fora do ar (de novo) | Passou | `trend` parado às 17:28:46Z; primeira falha às 17:28:49Z; `STRATEGY_UNAVAILABLE` `WARN` na amostra das 17:30:06Z (limite de 60 s), com `runnerId`, `strategyName`, `failingSince` e `lastReason`; runner `ACTIVE` em todas as amostras; `trend` de volta às 17:38:48Z, estado `OK` às 17:39:09Z, só `HOLD` do `SoakTrend` nos minutos seguintes |
| F4.14 Carga | Não abriu (F25) | `RUNNERS_PER_APP=4 ./round-start.sh` criou o primeiro runner de cada app e o segundo respondeu 500: `duplicate key value violates unique constraint "idx_runners_active_unique"` |

Fechamento: em voo 0, `locked` 0, conciliação 52 de 52, 0 `ERROR`. Os `WARN` são a queda do `SoakTrend`, as falhas
de propósito do `SoakChaos` (que alternam com respostas boas e por isso não abrem `STRATEGY_UNAVAILABLE`) e 38
`slow evaluation` dos timeouts do `trend` fora do ar.

**Achado:**

- **F25 — Script (baixo): `RUNNERS_PER_APP` contraria uma regra do domínio.** O índice
  `idx_runners_active_unique` permite um só runner ativo por estratégia, símbolo, exchange e portfólio. N cópias da
  mesma app no mesmo portfólio e no mesmo símbolo não existem. A F4.14 precisa mudar o `round-start.sh`: um portfólio
  por cópia ou um símbolo por cópia. Decisão com o mantenedor.

## Fontes

- `docs/GO_LIVE_CHECKLIST.md` (item 10), `docs/TESTING_ROADMAP.md` (§4 e §5)
- `docs/OPERATIONS_RUNBOOK.md` (Safe Mode, gatilhos automáticos, estratégia remota, estado operacional)
- `.ai/tasks/T2-testnet-config.md`, `.ai/tasks/T35-testnet-scenario-strategies.md`,
  `.ai/tasks/T18-trade-reconciliation-report.md`
