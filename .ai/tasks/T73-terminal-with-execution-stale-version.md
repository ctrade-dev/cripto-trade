# T73 — Encerramento com execução embutida grava versão antiga (T53 F32)

**Complexidade:** Baixa
**Responsável:** Claude
**Dependências:** T67 (encerramento com execução embutida)
**Status:** Implementada em `bugfix/t73-terminal-with-execution-stale-version` (2026-11-20)

---

## Descrição

Na rodada 13 da T53 (11-12, 19:42:28Z), a SELL MARKET `52334095` (ETHUSDT) recebeu `PARTIALLY_FILLED` e logo `EXPIRED`
com mais quantidade executada. O `EXPIRED` aplicou o fill e falhou ao gravar o encerramento com
`OptimisticLockingFailureException` em `RunnerTransactionEntity`. A ordem só fechou certa porque a resposta da WS API
trouxe o mesmo `EXPIRED` em seguida. Sem a repetição, a ordem ficaria aberta, com o lote travado, até o watchdog.

## Contexto técnico

- `ConciliationOrderUpdate.terminate` (T67): se o evento terminal (`CANCELED`, `EXPIRED`) traz mais execução do que a
  transação conhece, aplica o incremento como fill parcial e depois encerra.
- No fluxo normal, `RunnerConfig.conciliationOrderUpdateExecutor` roda o fill e o encerramento em **duas transações de
  banco** (`TransactionTemplate`), com o mesmo objeto `Transaction`.
- O fill grava a transação (`BuyFillHandler` com `saveTransaction`, `SellFillHandler` com
  `saveAtomicTransactionAndMatch`). O `@Version` sobe no banco, mas o objeto de domínio continua com a versão antiga:
  o adapter (`JdbcStrategyRunnerRepositoryAdapter`) não devolve a versão nova.
- O encerramento (`TerminationHandler`) grava o mesmo objeto com a versão antiga e o banco recusa.

**Estado depois da falha:** o fill fica gravado (match, lote, confirmação de capital); o status terminal, o
destravamento do lote e a liberação de margem são desfeitos. A repetição do evento ou o watchdog relê a transação,
não vê execução nova e encerra certo. Sem perda contábil; um `ERROR` e atraso no encerramento.

**Alcance:** BUY e SELL, `CANCELED` e `EXPIRED`, sempre que o evento terminal traz execução não vista. Os testes da T67
usavam repositório simulado, que não confere versão.

## Solução

Depois de aplicar o fill embutido, `terminate` relê a transação pelo id (`findTransactionById`) e encerra a partir da
versão gravada. Um caminho só, no `core`, no ponto onde o mesmo objeto atravessa duas gravações.

Alternativa descartada: o adapter devolver a versão nova ao objeto de domínio a cada gravação. É mais geral, mas mexe
na fronteira de persistência de todas as gravações de transação para corrigir um caso só.

## Riscos

- Releitura extra só quando o evento terminal traz execução não vista (raro).
- A transação some entre o fill e a releitura: não acontece no fluxo (nada apaga transação); falha explícita.

## Critérios de aceite

1. `EXPIRED` de BUY com execução não vista contra o banco real: `CANCELED`, execução mantida, reserva não executada
   devolvida e custo executado reservado no lote.
2. `CANCELED` de SELL com execução não vista contra o banco real: `CANCELED`, match gravado e resto do lote destravado.
3. Os dois testes falham sem a correção (`OptimisticLockingFailureException`) e passam com ela.
4. Testes da T67 verdes; `./gradlew test` completo.
5. Flow `order-conciliation.md` e registro da F32 na T53 atualizados.

## Validação

- `OrderTerminationConciliationIntegrationTest` (2 casos novos), `TerminalAfterPartialFillConciliationTest`.
- Sem teste na VPS: o caso depende de a Binance mandar o terminal com execução nova, que não dá para provocar.
  As rodadas seguintes da T53 confirmam pela ausência de `OptimisticLockingFailureException`.

## Fontes

- `.ai/tasks/T53-testnet-soak.md` (F32)
- `.ai/tasks/T67-expired-after-partial-fill.md`
- `core/.../orderconciliation/ConciliationOrderUpdate.java`, `spring-application/.../config/core/RunnerConfig.java`
