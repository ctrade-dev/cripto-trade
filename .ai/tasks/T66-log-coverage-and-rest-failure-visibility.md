# T66 — Cobertura de Logs e Causa de Falha REST Visíveis

**Complexidade:** Média
**Responsável:** Claude
**Dependências:** T53 (rodada 7, achados F9 e F10), T63 (VPS), T64 (`scripts/round_report.py`), T25 (watchdog).
**Status:** Concluído — PR #172; validada na VPS em 2026-10-29 e na rodada 8 da T53 (cobertura de 100%).

---

## Descrição

A rodada 7 da T53 (primeira de 24h na VPS) foi reprovada sem nenhum erro de operação: o ctrade operou
normalmente, mas não deu para explicar o que aconteceu.

- **F9:** o Loki ficou ~12h (00:45Z → 13:05Z) sem receber nenhuma linha do ctrade, sem aviso nenhum. Os 4
  `ERROR` da rodada (contados em `logback_events_total`) caíram nesse buraco e o conteúdo se perdeu. O log
  do container não serviu de reserva, porque o volume de `TRACE` gira os 3 × 20 MB em ~1h30. O
  `round_report.py` relatou "ERROR: 0", porque só lê o Loki.
- **F10:** a consulta REST de ordens da Binance falhou por ~1h40 (07:38Z → 09:21Z) e o watchdog pôs o
  portfolio em `HALT` (`WATCHDOG_STUCK`). Não dá para saber se foi queda da testnet ou bloqueio por limite
  de peso: não há métrica com o tipo da falha, o código HTTP 418 (IP banido) cai como `TEMPORARY`, e o
  motivo gravado no evento do Safe Mode não diz por que a consulta falhou.

Sem isso a T53 não fecha: cada rodada pode ser reprovada por falta de evidência, e na produção o operador
(IA) precisa saber se a falha é da exchange ou causada pelo próprio ctrade.

## Contexto técnico

- `spring-application/src/main/resources/application.yml`: `logging.level.com.marmitt: TRACE` em todos os
  perfis. Na rodada 7, ~85 mil das ~110 mil linhas por hora eram `TRACE` (`[REPO] ...findById` a cada
  ciclo do watchdog e dos runners).
- `spring-application/src/main/resources/logback-spring.xml`: appender `Loki4jAppender` (loki4j 1.5.2,
  `spring-application/build.gradle`), "falha em silêncio" por desenho; sem métricas ligadas.
- `deploy/vps/docker-compose.yml`: `x-logging` `json-file` com `max-size: 20m`, `max-file: 3` para todos
  os serviços.
- `docker/grafana/provisioning/alerting/rules.yml`: as regras de log usam o Loki (`ctrade-error-spike` e
  `ctrade-warn-rate-high` com `noDataState: OK`), então ficam cegas quando o Loki para de receber. Na VPS o
  `DISCORD_WEBHOOK_URL` está preenchido.
- `adapter-binance/.../BinanceMarketStreamAdapter.java` (`queryException`): 400 → `INVALID_REQUEST`, 401/403
  → `AUTH`, 429 → `RATE_LIMIT`, o resto → `TEMPORARY`. O mesmo mapa está em
  `adapter-binance/.../trade/BinanceTradeHistoryAdapter.java`. A Binance responde 418 quando bane o IP
  depois de 429 repetidos.
- `spring-application/.../config/exchange/CircuitBreakerExchangeOrderQuery.java`: decorator do circuito
  `<exchange>-order-query`. Toda falha da consulta de ordem passa por ele.
- `core/.../usecase/runner/RecoverStaleTransactionsUseCase.java` (`recordFailure`): o motivo do
  `WATCHDOG_STUCK` leva `transactionId`, `runnerId`, `status` e `cycles`, mas não a `failureReason` nem a
  mensagem da última falha (`RecoverTransactionStatusResponse`).
- `scripts/round_report.py`, seção 4: conta `ERROR`/`WARN` só no Loki.

## Solução

### 1. Volume de log (F9)

- `com.marmitt` passa de `TRACE` para `DEBUG` no `application.yml`. Quem precisar de `TRACE` liga por
  variável de ambiente (`LOGGING_LEVEL_COM_MARMITT=TRACE`), sem mudar código. Isso corta ~75% do volume.
- Na VPS, só o `ctrade-app` ganha um `logging` próprio, com retenção para uma rodada de 72h inteira
  (`max-size: 100m`, `max-file: 10`; ~1 GB num disco com 37 GB livres). O log do container vira a
  reserva real do Loki.

### 2. Buraco no Loki visível (F9)

- Ligar as métricas do loki4j (`<metricsEnabled>true</metricsEnabled>`). Lotes enviados, descartados e com
  erro passam a aparecer no Prometheus, que é independente do Loki. A implementação confirma os nomes e
  que caem no registry do Spring (`Metrics.globalRegistry`).
- Regra nova no Grafana, `ctrade-log-ingestion-gap`: a contagem de linhas do ctrade no Loki em 10 min fica
  muito abaixo de `increase(logback_events_total[10m])` do Prometheus, ou o Loki fica sem dado com o
  ctrade no ar (`up{job="ctrade"} == 1`). O alerta vai pelo contact point existente.
- `round_report.py`: a seção 4 passa a comparar, hora a hora, as linhas por nível no Loki com
  `logback_events_total` do Prometheus. Ela lista as horas com cobertura abaixo de 95% e, quando o
  Prometheus contou `ERROR` que o Loki não tem, mostra o total e os minutos. Ela nunca mais escreve
  "ERROR: 0" se o Prometheus contou algum.

### 3. Causa da falha REST visível (F10)

- **Adapter (conhecimento do provedor):** 418 passa a `RATE_LIMIT` nos dois mapas do `adapter-binance`
  (`BinanceMarketStreamAdapter` e `BinanceTradeHistoryAdapter`), extraídos para um único mapeamento
  compartilhado no adapter (um fluxo, um caminho).
- **Métrica (mecânica técnica, `spring-application`):** o `CircuitBreakerExchangeOrderQuery` conta cada
  falha em `exchange_order_query_failures_total{exchange, errorType}`, com `errorType` do
  `ExchangeQueryException` e `CIRCUIT_OPEN` quando a chamada nem sai.
- **Core:** o motivo do `WATCHDOG_STUCK` inclui a `failureReason` e a mensagem da última falha (cortada),
  por exemplo `... cycles=3 lastFailure=EXCHANGE_QUERY_RETRYABLE_FAILURE: Order query circuit open ...`.
  O evento `SAFE_MODE_CHANGED` da linha do tempo (T59) passa a dizer, sozinho, se foi exchange fora ou
  limite de peso.

**Fora do escopo:**

- Mudar a sensibilidade do watchdog. Hoje são 3 ciclos (~45s) de consulta indisponível até o `HALT`.
  Isso é limite de risco e fica com o mantenedor, depois de uma rodada com esta task mostrando a causa
  real.
- Gauge de peso usado (`X-MBX-USED-WEIGHT-1M`). É útil para prevenir bloqueio, mas é outra frente; vira
  task se o 418/429 aparecer.
- Trocar ou atualizar o loki4j. Só entra se as métricas mostrarem defeito do appender.

### Decisão registrada (2026-10-29): a causa do buraco era o Loki da VPS

Investigação durante a implementação: o ctrade enviou os logs normalmente. O Loki da VPS é que perdia o
que gravava. Os chunks ficaram no disco (75 arquivos, 52 MB), mas o índice não os achava, e as consultas
só viam o que ainda estava na memória do ingester (~2h). Às 14:47Z o Loki ainda devolvia a rodada até
00:45Z. Às 15:30Z já não devolvia nada da rodada, nem as horas de 13h e 14h que tinha mostrado antes. A
configuração era a da T63 (índice TSDB + compactor com retenção, `tsdb_shipper.shared_store` vazio), no
Loki 2.9.

- As métricas do loki4j continuam: provam, pelo Prometheus, que o envio não parou.
- Validação na VPS: consultar dados com mais de 3h (fora da memória do ingester, `query_ingesters_within`)
  depois da troca.

**Primeira tentativa, reprovada:** `deploy/vps/loki.yml` com a configuração padrão do `grafana/loki:2.9.0`
(`boltdb-shipper`, schema v11). Às 18:45Z o Loki não devolvia nada antes das 17:34Z, quando os primeiros
chunks saíram do ingester (janelas de 10 min: 0 linhas no Loki contra ~2.500–2.900 no Prometheus). O
índice e os chunks estavam no disco. O querier do 2.9 não os achava, e só o ingester respondia, até
`query_store_max_look_back_period` (2h41m na VPS). O compose local tinha o mesmo defeito, sem ninguém notar,
porque as consultas locais cobrem só as últimas horas.

**Reprodução e correção:** dois Loki descartáveis na VPS, com tempos curtos (`chunk_idle_period: 1m`,
`max_chunk_age: 2m`, `query_ingesters_within: 3m`) e 30 linhas de teste. Passada a janela do ingester
(~43 min), o 2.9 (`boltdb-shipper`) devolveu 0 linhas. O `grafana/loki:3.4.2`, com a configuração padrão
da imagem (TSDB, schema v13), devolveu as 30, achadas pelo querier no disco.

- VPS e compose local passam ao `grafana/loki:3.4.2`. `deploy/vps/loki.yml` é a configuração padrão dessa
  imagem, só com a telemetria desligada. O local usa a configuração da própria imagem.
- Sem retenção automática: o volume com `DEBUG` é pequeno para o disco, e a limpeza entre rodadas está no
  próprio arquivo.
- O volume antigo do Loki fica ilegível com o schema novo e é descartado (dados de testnet).

## Riscos

- Com `DEBUG` no lugar de `TRACE`, some o rastro `[REPO]` de cada acesso ao banco. Nenhum alerta,
  relatório ou runbook usa essas linhas; antes de mudar, a implementação confere `docs/` e as regras do
  Grafana.
- A regra de buraco no Loki compara duas fontes com atrasos diferentes (lote de 5s do loki4j, scrape do
  Prometheus). A janela de 10 min e uma margem evitam alarme falso, e a implementação valida a regra
  parando o Loki por alguns minutos na VPS.
- O `errorType` tem poucos valores fixos, então a cardinalidade da métrica fica baixa.

## Critérios de aceitação

- [x] Volume de log do ctrade na VPS cai para menos da metade, sem `TRACE` por padrão. *(~112 mil → ~26 mil
      linhas por hora; o `TRACE` era 77%.)*
- [x] `ctrade-app` na VPS guarda pelo menos 72h de log no container. *(10 × 100 MB; com `DEBUG`, ~10 MB/h,
      cerca de 96h.)*
- [x] Métricas do loki4j no Prometheus. *(`loki4j_send_batches_total`, `loki4j_send_errors_total{reason}`,
      `loki4j_retry_errors_total`, `loki4j_drop_events_total`, `loki4j_unsent_events`.)*
- [x] Loki da VPS devolve logs com mais de 3h depois da troca da configuração. *(Loki 3.4.2. Às 22:35Z, janelas
      de 10 min entre 19:10Z e 21:30Z: Loki 1952/2264/2512/2808/2688 contra Prometheus
      1913/2266/2528/2785/2694.)*
- [x] `docker compose stop loki` por ~10 min na VPS dispara `ctrade-log-ingestion-gap`, e o
      `round_report.py` aponta o buraco. *(Loki parado às 15:34:30Z: o alerta disparou às 15:36:30Z e
      voltou ao normal 1 min depois do Loki subir. O loki4j guardou e reenviou as linhas (0 descartadas),
      então esse teste não deixou buraco. O apontamento do buraco foi validado contra a rodada 7.)*
- [x] `round_report.py` contra a rodada 7 mostra os 4 `ERROR` do Prometheus e as horas sem cobertura.
- [x] 418 e 429 viram `RATE_LIMIT`, com teste unitário do mapeamento.
- [x] `exchange_order_query_failures_total` conta por `errorType` e `CIRCUIT_OPEN`, com teste unitário.
- [x] Motivo do `WATCHDOG_STUCK` com a última falha, com teste unitário em
      `RecoverStaleTransactionsStuckTriggerTest`.
- [x] `docs/monitoring/round-report.md`, `deploy/vps/README.md`, `docs/OPERATIONS_RUNBOOK.md` (seção do
      Safe Mode) e a T53 atualizados.

## Fontes

- `.ai/tasks/T53-testnet-soak.md`, rodada 7 (F9, F10)
- `.ai/tasks/T64-round-report-script.md`, `.ai/tasks/T63-vps-soak-environment.md`
- `docs/monitoring/round-report.md`, `docker/grafana/provisioning/alerting/rules.yml`
- Binance Spot API, limites (HTTP 429 e 418): https://developers.binance.com/docs/binance-spot-api-docs/rest-api/limits
