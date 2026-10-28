# T64 — Script de Coleta do Relatório de Rodada

**Complexidade:** Baixa
**Responsável:** Claude
**Dependências:** T59 (procedimento do relatório, `GET /api/admin/events`), T63 (VPS com Loki e Prometheus).
**Status:** Em andamento

---

## Descrição

O relatório de fim de rodada da T53 segue `docs/monitoring/round-report.md` (T59), mas a coleta é manual:
paginar a linha do tempo, consultar o estado operacional com a exchange, agrupar `ERROR`/`WARN` do Loki,
comparar séries da JVM no Prometheus e contar as ordens por runner. São várias chamadas por rodada, e
esquecer uma deixa um critério sem evidência. A T53 já previa isso em "Riscos e limitações conhecidas"
("um relatório de rodada (endpoint ou script) pode virar task").

## Contexto técnico

- `GET /api/admin/events?since&until&limit` (máx. 1000, `truncated` + `nextSince`, dedupe por `id`).
- `GET /api/admin/operational-state?includeExchange=true&exchangeWindowHours=<1..168>`.
- `GET /api/boot/status`: fases do último boot (`phase2.sanity` cobre o critério 8 no restart).
- `GET /api/portfolios/{id}/runners`, `GET /api/runners/{id}/transactions`, `GET /api/portfolios/{id}/balance`.
- Loki: labels `app`, `host`, `level`; linhas em JSON (`logger_name`, `message`).
- Prometheus: `jvm_memory_used_bytes{area="heap"}`, `jvm_threads_live_threads`,
  `runner_signal_processing_time_seconds_{sum,count,max}{runnerId}`, `websocket_connection_state{channel}`,
  `process_uptime_seconds`.

## Solução

`scripts/round_report.py` (Python 3, só biblioteca padrão), um rascunho Markdown na saída padrão:

1. linha do tempo: contagem por tipo e origem, e a lista dos eventos que o relatório precisa explicar
   (automáticos, DLQ, boot com falha, ações de operador);
2. estado no fim: todos os checks, com a comparação contra a exchange na janela da rodada (até 168h), e
   as fases do último boot;
3. ordens por runner (`--portfolio`, repetível) e saldo do portfolio;
4. `ERROR` e `WARN` do Loki agrupados por logger + mensagem, com ids e números trocados por `#`;
5. JVM: primeira hora contra a última (piso do heap, threads, tempo de avaliação), e mudanças de estado
   das conexões WebSocket (o corte de 24h da Binance);
6. tabela dos 8 critérios com o passo onde está a evidência, para preencher.

**Fora do escopo:** o script não muda estado (não para runners nem reinicia o ctrade) e não dá veredito.
A classificação (`ctrade` ou testnet) e o resultado de cada critério ficam com quem assina o relatório.
Um endpoint no ctrade não foi escolhido: Loki e Prometheus ficam fora do processo, e o relatório é
ferramenta de operação da trilha testnet.

## Riscos

- Consultas do Loki limitadas a 5000 linhas por nível; o script avisa quando o limite é atingido.
- `includeExchange=true` consome cota da API da exchange: uma chamada por execução.

## Critérios de aceitação

- [x] Roda contra a rodada real na VPS e produz as seis seções sem erro.
- [x] Nenhuma chamada de escrita; chave admin lida do `.env` e nunca impressa.
- [x] `docs/monitoring/round-report.md`, `deploy/vps/README.md` e a T53 apontam para o script.

## Validação

Executado em 2026-10-28 contra a rodada 1 de 24h em andamento (túnel SSH, 2,2h de dados): seis seções
preenchidas, 4 `PROTECTIVE_EXIT` listados com causa, `ERROR` 0, WARN agrupados em 9 padrões.
