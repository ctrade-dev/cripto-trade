# T71 — HALT por exchange indisponível volta sozinho

**Complexidade:** Média
**Responsável:** Claude
**Dependências:** T25 (gatilhos automáticos), T43 (drawdown), T45 (níveis do Safe Mode), T66 (motivo da falha)
**Status:** Concluída — implementada no #193 e testada na VPS na curta 116 (2026-11-16)

---

## Descrição

Na rodada 13 da T53, a testnet passou cerca de 3 minutos sem responder (11-13, 02:40–02:43Z). Uma SELL ficou 3
ciclos do watchdog sem resposta, e o portfólio entrou em `HALT` automático (`WATCHDOG_STUCK`). A exchange voltou em
minutos, mas o `HALT` só sai por comando manual. O portfólio ficou **9h40 sem comprar** até a retomada.

**Decisão do mantenedor (2026-11-15):** o `HALT` causado por exchange indisponível volta sozinho depois de N minutos
de exchange saudável. Os demais motivos continuam só com retomada manual.

Isso muda uma regra escrita: hoje "gatilho automático só aciona `HALT`; a retomada é sempre manual" (`SafeModeTrigger`,
`SafeModeOrigin`, Blueprint 11.1.C). A exceção fica restrita a um gatilho.

## Contexto técnico

- `RecoverStaleTransactionsUseCase.recordFailure`: em `max-stuck-cycles` (3) ciclos seguidos sem resolver a mesma
  transação, chama `AutomaticSafeModeTrigger.activate(..., WATCHDOG_STUCK, "... lastFailure=...")`. A falha de cada
  ciclo tem um `FailureReason`, e só `EXCHANGE_QUERY_RETRYABLE_FAILURE` significa exchange fora do ar (timeout, 5xx,
  limite de peso).
- `AutomaticSafeModeTrigger`: caminho único do `HALT` automático. Só age com o portfólio em `NORMAL`.
- `Portfolio.changeSafeMode`: `AUTOMATIC` só sobe até `HALT`, e descer é um nível por vez. O portfólio guarda
  `safeModeStatus`, `safeModeOrigin`, `safeModeReason` e `safeModeChangedAt`, mas **não guarda qual gatilho** causou o
  `HALT` (está só no texto do motivo).
- Saúde da exchange já existe no `core` pelas mesmas portas dos checks `EXCHANGE_REST_CIRCUIT_OPEN`,
  `STREAM_DISCONNECTED` e `ORDER_DISPATCH_BLOCKED` (`InfrastructureSignalsPort`, `WebSocketConnectionRepositoryPort`).

## Solução proposta

### 1. Gatilho próprio para exchange indisponível (`core`)

- Novo `SafeModeTrigger.EXCHANGE_UNAVAILABLE`. O watchdog o usa quando **todos** os ciclos contados da transação
  presa falharam com `EXCHANGE_QUERY_RETRYABLE_FAILURE`. Qualquer outra falha no meio (`INVALID_EXCHANGE_RESPONSE`,
  `TERMINAL_FAILURE`, `ORDER_QUERY_*`, exceção) mantém `WATCHDOG_STUCK`, que continua manual.
- O enum diz quais gatilhos voltam sozinhos (`autoResumable()`): só `EXCHANGE_UNAVAILABLE`. A regra mora no tipo, sem
  `if` espalhado.
- `EXCHANGE_UNAVAILABLE` entra no mesmo `enabledTriggers` (`AutoSafeModeProperties`), ligado por padrão como o
  `WATCHDOG_STUCK` de hoje.

### 2. O portfólio guarda o gatilho do HALT automático (`core` + migration)

- `Portfolio` ganha `safeModeTrigger` (opcional): preenchido quando a origem é `AUTOMATIC` e limpo em qualquer mudança
  `MANUAL`.
- Migration nova (`V22`): coluna `portfolios.safe_mode_trigger` (nula) e `safe_mode_changes.trigger` (nula), para a
  auditoria dizer o gatilho sem depender do texto do motivo. Banco existente: nulo para tudo que já está gravado.
- Regra nova em `Portfolio.changeSafeMode`: `AUTOMATIC` pode descer `HALT → NORMAL` **somente se** o `HALT` atual for
  `AUTOMATIC` com gatilho `autoResumable()`. Qualquer outro caso continua recusado (`SafeModeTransitionException`).
  Assim:
  - se o operador mexeu no Safe Mode depois do `HALT`, a origem vira `MANUAL` e nada volta sozinho;
  - um `HALT` por drawdown, DLQ, divergência ou `WATCHDOG_STUCK` nunca volta sozinho;
  - um nível acima de `HALT` (`CANCEL_ALL`, `PANIC_SELL`) nunca é tocado.

### 3. Retomada automática (`core` + agendamento no Spring)

- Novo use case `ResumeSafeModeAfterExchangeRecoveryUseCase`, chamado a cada tick do `OperationalTriggerScheduler`.
  O tick já existe e roda os outros gatilhos. Para cada portfólio em `HALT` `AUTOMATIC` com gatilho `autoResumable()`:
  1. **Exchange saudável agora:** circuito REST fechado, streams necessários conectados, envio de ordens liberado e
     nenhuma transação do portfólio contando ciclos de falha no watchdog.
  2. **Saudável há N minutos seguidos:** o use case guarda desde quando a exchange está saudável. Qualquer tick
     não saudável zera a contagem.
  3. **Teto de retomadas:** no máximo M retomadas automáticas por portfólio em 24h, contadas pelo
     `safe_mode_changes`. Passou do teto, o `HALT` fica manual e o alerta diz por quê.
  4. Atendidas as três, muda para `NORMAL` com origem `AUTOMATIC` e motivo
     `AUTO_RESUME EXCHANGE_UNAVAILABLE: exchange healthy for <N>m`. Registra a métrica e notifica, como o `HALT`.
- Configuração em `safe-mode.auto.resume.*`: `enabled`, `healthy-minutes` e `max-per24h`.

### Parâmetros de risco

**Decisão registrada (2026-11-15):** o mantenedor aprovou N = 5 e M = 3, com a retomada ligada por padrão.

| Parâmetro | Valor | Motivo |
|---|---|---|
| `healthy-minutes` (N) | 5 | Maior que os 3 min de falha que disparam o `HALT`; cobre a oscilação vista na testnet |
| `max-per-24h` (M) | 3 | Exchange que cai e volta várias vezes no dia pede olho humano |
| `enabled` | `true` em testnet e produção | Produção é operada pelo agente; sem retomada, uma queda de minutos para o dia |

### 4. Documentação

- `docs/BLUEPRINT.md` 11.1.C e `docs/OPERATIONS_RUNBOOK.md` (Safe Mode e gatilhos): exceção da retomada automática,
  condições e como desligar.
- Javadoc de `SafeModeTrigger`, `SafeModeOrigin` e `Portfolio.changeSafeMode`.
- `.ai/flows/` do Safe Mode (`portfolio-capital.md` ou o flow aplicável): novo passo da retomada.

### Decisões registradas na implementação (2026-11-16)

- **Chave de configuração:** `safe-mode.auto.resume.*`, junto dos demais gatilhos (`safe-mode.auto.*`), em vez de
  `ctrade.safe-mode.auto-resume.*`. Com `safe-mode.auto.enabled=false` o tick não roda e nada volta sozinho.
- **O gatilho viaja no pedido:** `ChangeSafeModeRequest` ganhou `trigger` (`manual(...)` e `automatic(...)`), e
  `Portfolio.changeSafeMode` exige o gatilho com origem `AUTOMATIC` e o recusa com `MANUAL`. A descida automática
  exige o mesmo gatilho do HALT atual.
- **Saúde da exchange:** só das exchanges dos runners do portfolio que precisam dos streams (`StreamDemand`). A
  regra dos streams foi extraída do estado consolidado para `StreamGaps`, usada pelos dois (um fluxo, um caminho).
- **Watchdog → retomada:** `WatchdogFailures` (core, bean único) guarda os runners com transação sem resolver no
  último ciclo; o watchdog publica, a retomada lê.
- **Teto atingido:** `ERROR` e Discord uma vez por HALT; o HALT fica para o operador.
- **Restart:** a contagem de minutos saudáveis é em memória e recomeça; o teto vem do banco.

## Fora de escopo

- Retomada automática de qualquer outro gatilho.
- Retomar runners: o `HALT` do portfólio não para runners, só barra compras. Runners parados por outros motivos
  continuam como estão.
- Mudar `max-stuck-cycles` (3).

## Riscos

- **Retomar com a exchange ainda instável:** mitigado pelos N minutos seguidos de saúde, pelo teto M por dia e por o
  watchdog continuar ativo (nova falha dispara `HALT` de novo).
- **Classificar errado uma falha como exchange indisponível:** só conta `EXCHANGE_QUERY_RETRYABLE_FAILURE`, que já é
  a classificação usada para decidir nova tentativa (T66). Qualquer outra falha no meio mantém o gatilho manual.
- **Corrida entre retomada automática e comando manual:** as duas passam por `Portfolio.changeSafeMode` com versão
  otimista (`saveSafeModeChange`). Se o operador mudou antes, a origem é `MANUAL` e a retomada é recusada.

## Critérios de aceite

1. Watchdog com 3 ciclos só de `EXCHANGE_QUERY_RETRYABLE_FAILURE` gera `HALT` com gatilho `EXCHANGE_UNAVAILABLE`;
   com qualquer outra falha no meio, `WATCHDOG_STUCK`.
2. `Portfolio.changeSafeMode`: `AUTOMATIC` desce `HALT → NORMAL` só com origem `AUTOMATIC` e gatilho
   `autoResumable()`. Os demais casos são recusados. Coberto por teste de domínio.
3. Use case: retoma só após N minutos seguidos de saúde; tick não saudável zera a contagem; acima de M por 24h não
   retoma e alerta. Coberto por teste unitário com relógio controlado.
4. Integração: `HALT` por `EXCHANGE_UNAVAILABLE` volta a `NORMAL` sozinho; `HALT` por drawdown e `HALT` manual não
   voltam.
5. Auditoria: `safe_mode_changes` mostra o `HALT` e a retomada, com origem, gatilho e motivo.
6. VPS, cerca de 30 min: desconectar o ctrade da rede por cerca de 4 min com uma ordem em voo, ver o `HALT`
   `EXCHANGE_UNAVAILABLE`, reconectar e ver a volta a `NORMAL` depois de N minutos, com compras retomadas e 0
   `ERROR` fora da queda provocada.
7. Docs do item 4 atualizados.

## Validação

- `./gradlew test` completo e a migration aplicada no banco de teste.
- Teste na VPS do critério 6, na sessão de testes curtos depois da rodada 14.

## Ordem de entrega

1. Domínio: gatilho novo, `safeModeTrigger` no portfólio, regra de descida automática, migration.
2. Watchdog classificando o gatilho.
3. Use case de retomada e agendamento.
4. Docs, PR em rascunho, CI e espera por comentários; merge pelo mantenedor; teste na VPS.

## Fontes

- `.ai/tasks/T53-testnet-soak.md` (rodada 13, observação das 9h40)
- `docs/BLUEPRINT.md` 11.1, `docs/OPERATIONS_RUNBOOK.md` (Safe Mode)
- `core/.../usecase/runner/RecoverStaleTransactionsUseCase.java`, `core/.../usecase/portfolio/AutomaticSafeModeTrigger.java`,
  `core/.../domain/portfolio/Portfolio.java`, `core/.../enums/SafeModeTrigger.java`
