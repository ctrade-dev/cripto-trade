# Relatório de Fim de Rodada (T59)

Procedimento para o agente no fim de cada rodada do soak em testnet (`.ai/tasks/T53-testnet-soak.md`).
Produz a linha do "Registro de execuções" da T53 e o bloco "Rodada N — detalhes". Só leitura: nenhum
passo muda estado.

Durante a rodada ninguém verifica o sistema: os automatismos do ctrade (Safe Mode, proteção do lote, halt
por rejeições) reagem sozinhos, e tudo o que fizerem fica gravado. O relatório reconstrói a rodada no fim.

## Coleta automática (T64)

`scripts/round_report.py` faz os passos 1 a 4 de uma vez e devolve um rascunho em Markdown com a tabela
dos critérios para preencher. Na VPS, depois do restart do fim da rodada:

```bash
python3 ~/ctrade/scripts/round_report.py --start <START> --env-file ~/ctrade/deploy/vps/.env     --portfolio <portfolioId> > ~/round.md
```

A classificação dos `ERROR`/`WARN` e o veredito de cada critério continuam manuais (passos 3 e 5).

O passo 3 do script compara, hora a hora, as linhas do Loki com as que o ctrade registrou
(`logback_events_total`, no Prometheus) e avisa quando há `ERROR`/`WARN` contados que não estão no Loki,
com os minutos aproximados (T66). Cobertura abaixo de 95% ou "sem linha no Loki" pede o log do container
(`docker compose logs --since <START> ctrade-app`) antes de fechar o critério 6.

## Entradas

| O quê | Valor |
|---|---|
| `START`, `END` | Início e fim da rodada (ISO-8601 UTC), anotados ao subir e ao fechar |
| `CTRADE`, `KEY` | Base da API e `ADMIN_API_KEY` (convenções do runbook) |
| Loki, Prometheus | Endereços do Compose (`localhost:3100`, `localhost:9090` na máquina da rodada) |

## Passos

### 1. Linha do tempo

```bash
curl -s -H "X-Admin-Key: $KEY" "$CTRADE/api/admin/events?since=$START&until=$END&limit=1000"
```

- Se `truncated=true`, repetir com `since=<nextSince>` até `truncated=false`; descartar eventos com `id`
  repetido.
- Separar por `type` e por `origin`:
  - `SAFE_MODE_CHANGED` e `RUNNER_STATUS_CHANGED` com `origin=AUTOMATIC`: cada um precisa de causa
    identificada (`reason`). É o critério 5.
  - `PROTECTIVE_EXIT`: conferir o `reason` (perda máxima ou tempo máximo) contra a configuração do runner.
  - `DLQ_ENTRY`: cada entrada explicada (critério 2).
  - `BOOT_FAILED`: toda falha de boot é achado.
  - `OPERATOR_ACTION`: o que o mantenedor ou o agente fizeram durante a rodada.

### 2. Estado no fim

```bash
curl -s -H "X-Admin-Key: $KEY" "$CTRADE/api/admin/operational-state?includeExchange=true&exchangeWindowHours=24"
```

- `EXCHANGE_TRADE_DIVERGENCE` e `EXCHANGE_BALANCE_DIVERGENCE` sem `CRITICAL` (critérios 1 e 8).
- `CAPITAL_*`, `TRANSACTION_STUCK`, `DLQ_UNRESOLVED` em `OK` (critérios 2, 3 e 4).
- Para janelas acima de 24h, a divergência de trades é conferida em fatias de 24h (`exchangeWindowHours`
  máximo de 168).

### 3. Logs

No Loki, `ERROR` e `WARN` do período (`level` é label; a mensagem não é), agrupando as linhas depois de
trocar ids e números por `#`:

```bash
curl -s -G "http://localhost:3100/loki/api/v1/query_range" \
     --data-urlencode 'query={app=~".+", level="ERROR"}' \
     --data-urlencode "start=<START em ns>" --data-urlencode "end=<END em ns>" --data-urlencode "limit=5000"
```

Cada `ERROR` é classificado como **ctrade** (achado) ou **testnet** (aceito se registrado, decisão 6 da
T53). `WARN` repetido sem causa conhecida também vira achado.

### 4. Estabilidade do processo (critério 7)

No Prometheus, comparar o início e o fim da rodada:

| Série | Esperado |
|---|---|
| `jvm_memory_used_bytes{area="heap"}` (mínimos depois de GC) | Sem crescimento contínuo |
| `jvm_threads_live_threads` | Estável |
| `runner_signal_processing_time_seconds` (avaliação do sinal, por runner) | Média e máximo estáveis |

### 5. Critérios

Para cada um dos 8 critérios da T53: aprovado ou não, com a evidência (passo e valor).

### 6. Registro

1. Linha na tabela "Registro de execuções" da T53: data, fase, duração, estratégias e símbolo,
   resultado, achados.
2. Bloco "Rodada N — detalhes": linha do tempo resumida (eventos automáticos com causa), números dos
   critérios, achados com F-número. Cada achado de código vira task.
