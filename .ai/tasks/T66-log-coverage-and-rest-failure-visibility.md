# T66 — Cobertura de Logs e Causa de Falha REST Visíveis

**Complexidade:** Média
**Responsável:** Claude
**Dependências:** T53 (rodada 7, achados F9 e F10), T63 (VPS), T64 (`scripts/round_report.py`), T25 (watchdog).
**Status:** Em andamento

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
configuração era a da T63 (índice TSDB + compactor com retenção, `tsdb_shipper.shared_store` vazio). O
compose local, sem o problema, usa a configuração padrão da imagem (`boltdb-shipper`).

- `deploy/vps/loki.yml` passa a ser a configuração padrão da imagem `grafana/loki:2.9.0` (a mesma do
  local), só com a telemetria desligada. Fica sem retenção automática: o volume com `DEBUG` é pequeno para
  o disco, e a limpeza entre rodadas está no próprio arquivo.
- As métricas do loki4j continuam: provam, pelo Prometheus, que o envio não parou.
- Validação na VPS: consultar dados com mais de 3h (fora da memória do ingester, `query_ingesters_within`)
  depois da troca.

## Riscos

- Com `DEBUG` no lugar de `TRACE`, some o rastro `[REPO]` de cada acesso ao banco. Nenhum alerta,
  relatório ou runbook usa essas linhas; antes de mudar, a implementação confere `docs/` e as regras do
  Grafana.
- A regra de buraco no Loki compara duas fontes com atrasos diferentes (lote de 5s do loki4j, scrape do
  Prometheus). A janela de 10 min e uma margem evitam alarme falso, e a implementação valida a regra
  parando o Loki por alguns minutos na VPS.
- O `errorType` tem poucos valores fixos, então a cardinalidade da métrica fica baixa.

## Critérios de aceitação

- [ ] Volume de log do ctrade na VPS cai para menos da metade, sem `TRACE` por padrão.
- [ ] `ctrade-app` na VPS guarda pelo menos 72h de log no container.
- [ ] Métricas do loki4j no Prometheus.
- [ ] Loki da VPS devolve logs com mais de 3h depois da troca da configuração.
- [ ] `docker compose stop loki` por ~10 min na VPS dispara `ctrade-log-ingestion-gap`, e o
      `round_report.py` aponta o buraco.
- [ ] `round_report.py` contra a rodada 7 mostra os 4 `ERROR` do Prometheus e as horas sem cobertura.
- [ ] 418 e 429 viram `RATE_LIMIT`, com teste unitário do mapeamento.
- [ ] `exchange_order_query_failures_total` conta por `errorType` e `CIRCUIT_OPEN`, com teste unitário.
- [ ] Motivo do `WATCHDOG_STUCK` com a última falha, com teste unitário em
      `RecoverStaleTransactionsStuckTriggerTest`.
- [ ] `docs/monitoring/round-report.md`, `deploy/vps/README.md`, `docs/OPERATIONS_RUNBOOK.md` (seção do
      Safe Mode) e a T53 atualizados.

## Fontes

- `.ai/tasks/T53-testnet-soak.md`, rodada 7 (F9, F10)
- `.ai/tasks/T64-round-report-script.md`, `.ai/tasks/T63-vps-soak-environment.md`
- `docs/monitoring/round-report.md`, `docker/grafana/provisioning/alerting/rules.yml`
- Binance Spot API, limites (HTTP 429 e 418): https://developers.binance.com/docs/binance-spot-api-docs/rest-api/limits
