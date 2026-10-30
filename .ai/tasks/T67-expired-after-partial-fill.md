# T67 — Ordem Expirada Após Preenchimento Parcial

**Complexidade:** Média
**Responsável:** Claude
**Dependências:** T53 (rodada 8, achados F11, F12 e F13), T25 (watchdog), T44 (saída de proteção), T66
(`round_report.py`).
**Status:** Concluído — PR #173; falta só a validação na próxima rodada da T53 (último critério).

---

## Descrição

A rodada 8 da T53 foi reprovada por um bug do ctrade. Às 01:20:45Z de 2026-10-30, um dia depois do ataque à
Binance, a testnet teve um pavio. Cinco runners dispararam a saída de proteção `MAX_LOSS` a mercado no mesmo
segundo. A SELL do runner do livro preencheu 0.00107 de 0.00154 BTC, e a Binance expirou o resto por falta de
liquidez (`EXPIRED` depois de `PARTIALLY_FILLED`, comportamento normal de ordem a mercado que esgota o livro).

O ctrade recusou a transição: `Transaction.expire()` só aceita `PENDING` e `SUBMITTED`. A transação ficou em
`PARTIAL`, o lote ficou travado em `CLOSING`, e o watchdog tentou conciliar a cada ciclo (~60 `ERROR` por hora).
Depois de 3 ciclos ele pôs o portfolio em `HALT` (`WATCHDOG_STUCK`). A rodada parou de operar 1h depois do
início.

Na produção, o efeito seria o mesmo em qualquer queda forte: a proteção vende, a ordem expira em parte e o
portfolio para até o operador intervir.

## Contexto técnico

- `core/.../domain/runner/Transaction.java` (`expire()`, linha ~280): `PENDING | SUBMITTED → EXPIRED`.
  `cancel()` já aceita `PARTIAL → CANCELED`. O IG 6.1.2 lista `PARTIAL → EXPIRED` entre as transições
  inválidas ("já teve execução — usa CANCELED").
- `core/.../orderconciliation/ConciliationOrderUpdate.java` (`execute`): `CANCELED`, `EXPIRED` e `REJECTED`
  chamam a transição do domínio e o `releaseAction` sem checar se a transação já é final. `FILLED` e
  `PARTIALLY_FILLED` repetidos já são ignorados (`duplicate ... ignored`).
- `core/.../orderconciliation/MarginReleaseBuilder.java`: `REJECTED` e `EXPIRED` devolvem a reserva inteira
  ("a ordem nunca foi executada"); `CANCELED` devolve `reservado − executado`. Uma BUY `EXPIRED` com execução
  parcial devolveria capital que já virou lote.
- `core/.../orderconciliation/TerminationHandler.java`: para SELL, destrava o lote
  (`Position.releaseFromFailedSell`, `CLOSING → OPEN`). Os fills parciais já baixaram a quantidade do lote no
  `SellFillHandler`. É o caminho que o `CANCELED` parcial já usa.
- `adapter-binance/.../ExecutionReportProcessor.java` e `.../rest/BinanceOrderMapper.java`: `EXPIRED` e
  `EXPIRED_IN_MATCH` viram `OrderStatus.EXPIRED`.
- `core/.../RecoverTransactionStatusUseCase.java`: o watchdog consulta a ordem por REST e entrega o status
  terminal à mesma conciliação. A resposta traz a quantidade executada acumulada.
- `adapter-binance/.../BinanceMarketStreamAdapter.java` (`cancelOrder`): erro HTTP vira
  `ExchangeQueryException` pelo `BinanceHttpErrors` (T66). O `-2011 Unknown order sent` (ordem que já terminou
  na exchange) cai como `INVALID_REQUEST`, e o `ProcessTradeSignalUseCase` registra `ERROR`.
- `core/.../usecase/runner/CreateRunnerUseCase.java`: aceita `allowedMarketDataSources` nulo ou vazio
  (F12).
- `scripts/round_report.py` (`section_logs`): conta ERROR/WARN pelas linhas devolvidas, com limite de 5000
  (F13).

## Solução

### 1. `EXPIRED` depois de execução parcial (F11, core)

- **Conciliação:** `EXPIRED` da exchange para uma transação com execução vira `CANCELED`. A máquina de estados
  do IG (6.1.2) já define isso: `PARTIAL → EXPIRED` é inválida ("já teve execução — usa CANCELED"). O
  `Transaction.expire()` não muda.
- **Margem e lote:** sem regra nova, porque é o caminho do `CANCELED` parcial. A BUY devolve só
  `reservado − executado`, e a SELL destrava o lote com o resto, que volta a `OPEN` para a próxima venda.
- **Resto abaixo do mínimo da exchange:** hoje a baixa de resíduo (`Position.writeOffDust`, T46) só roda no
  fill final da SELL. Um `CANCELED` parcial que deixa resto invendável reabriria um lote que nunca mais vende.
  A baixa sai do `SellFillHandler` para um colaborador próprio (`UnsellableRemainderWriteOff`), usado também
  pelo `TerminationHandler`: resto invendável é baixado como resíduo e o lote fecha.
- **Execução que só chega no evento terminal:** se o `CANCELED`/`EXPIRED` trouxer quantidade executada maior
  que a conhecida (por exemplo, o watchdog leu por REST um fill que o stream perdeu), a conciliação aplica
  primeiro esse incremento pelo caminho de fill parcial e só depois encerra. Sem isso, o fill some da
  contabilidade e a conciliação com a exchange acusa a diferença.

**Decisão registrada (2026-10-30): `CANCELED` em vez de `PARTIAL → EXPIRED`.** A primeira versão desta spec
abria a transição `PARTIAL → EXPIRED` no domínio e mudava a regra de margem do `EXPIRED`. Na implementação,
o IG 6.1.2 mostrou que a transição é inválida por desenho e que o `CANCELED` já cobre ordem interrompida
depois de executar. Mapear na conciliação mantém a máquina de estados e a fórmula de margem como estão (um
fluxo, um caminho). O `EXPIRED` continua significando "nenhuma execução", com devolução total da reserva.

### 2. Encerramento repetido (F11, core)

- `CANCELED`, `EXPIRED` e `REJECTED` para uma transação já final são ignorados com `DEBUG`
  (`duplicate <status> ignored`), como já acontece com `FILLED`. Na rodada, o `EXPIRED` chegou duas vezes
  (stream e consulta) e o segundo virou `ERROR`.

### 3. Cancelamento de ordem que já terminou (F11, adapter + core)

- **Adapter:** o `-2011 Unknown order sent` do cancelamento vira um tipo próprio, `ORDER_NOT_FOUND`, no
  `ExchangeQueryException`. O conhecimento do código da Binance fica no `adapter-binance`.
- **Core:** cancelamento com `ORDER_NOT_FOUND` registra `WARN` e não muda a transação. O status final chega
  pelo stream ou pelo watchdog, que já sabem tratar ordem que a exchange não encontra.

### 4. Runner sem fonte de market data (F12, core)

- `CreateRunnerRequest` recusa `allowedMarketDataSources` nulo ou vazio com `IllegalArgumentException`, que
  a API devolve como `400 INVALID_REQUEST` com a mensagem do que falta. A validação fica no compact
  constructor, junto das de `symbol` e `exchangeName`. Um runner sem fonte nunca recebe tick e nunca opera,
  então aceitar a criação só adia o erro.
- Fica de fora usar a exchange do runner como fonte padrão: seria um segundo jeito de definir a fonte e
  esconderia a escolha.
- **Decisão do mantenedor (2026-10-30):** obrigar. O runner nasce com a exchange de execução
  (`exchangeName`, já obrigatória) e com a fonte de preço definidas, ou não nasce.

### 5. Contagem do relatório (F13, script)

- `round_report.py` conta ERROR/WARN no Loki com `count_over_time` (sem limite) e compara esse número com o
  Prometheus. As linhas devolvidas continuam servindo só para agrupar os padrões, e o limite atingido segue
  sinalizado.

**Fora do escopo:**

- Mudar a saída de proteção (MARKET contra LIMIT, tamanho da perda no livro raso). A proteção agiu como
  desenhada; a perda veio do livro da testnet.
- Mudar a sensibilidade do watchdog. Continua com o mantenedor.

## Riscos

- `EXPIRED` também chega por ordem que nunca preencheu (por exemplo, a SELL da tendência na mesma rodada).
  Sem execução, o resultado é o de hoje: `EXPIRED`, reserva inteira para BUY, lote destravado para SELL.
- Aplicar um fill a partir do evento terminal depende do preço médio executado na resposta. Sem preço
  positivo, a transição do domínio recusa o fill (como hoje para um `PARTIALLY_FILLED` sem preço), em vez
  de inventar o preço.
- Recusar runner sem fonte muda o contrato do `POST /api/portfolios/{id}/runners` (decisão do mantenedor).
  Os chamadores conhecidos (roteiro da T53 e testes de integração) já mandam o campo.
- O `CANCELED` repetido depois de `FILLED` deixa de lançar exceção e passa a ser ignorado. Terminal nunca
  muda, e um encerramento atrasado não é erro.

## Critérios de aceitação

- [x] SELL `PARTIAL` que recebe `EXPIRED`: transação `CANCELED`, executado preservado, lote com o resto em
      `OPEN` e destravado (`TerminalAfterPartialFillConciliationTest`, números da rodada 8).
- [x] BUY `PARTIAL` que recebe `EXPIRED`: transação `CANCELED`, devolve `reservado − executado`
      (`TerminalAfterPartialFillConciliationTest`).
- [x] `EXPIRED` sem execução: continua `EXPIRED`, com devolução total (teste novo; os existentes passam sem
      mudança).
- [x] SELL encerrada em parte com resto invendável: resíduo baixado e lote fechado
      (`TerminalAfterPartialFillConciliationTest`).
- [x] Evento terminal com execução maior que a conhecida aplica o incremento antes de encerrar
      (`TerminalAfterPartialFillConciliationTest`). O watchdog entrega a resposta REST ao mesmo
      `ConciliationOrderUpdate.execute`, então o teste cobre os dois caminhos.
- [x] `CANCELED`/`EXPIRED`/`REJECTED` repetidos ignorados sem `ERROR` (`TerminalAfterPartialFillConciliationTest`,
      `ConciliationOrderUpdateIdempotencyTest`).
- [x] `-2011 Unknown order sent.` no cancelamento vira `ORDER_NOT_FOUND`; outros `-2011` seguem o mapa HTTP
      (`BinanceMarketStreamAdapterTest`). O core registra `WARN` sem mexer na transação, nos dois caminhos de
      cancelamento (`CancelSignalHandlerTest`).
- [x] Criação de runner sem `allowedMarketDataSources` recusada (`CreateRunnerRequestTest`; a API devolve
      `400 INVALID_REQUEST` pelo `GlobalExceptionHandler`).
- [x] `round_report.py` conta ERROR/WARN pelo `count_over_time`. Contra a rodada 8: WARN 13 420 no Loki ×
      13 414 no Prometheus, sem o falso "8414 WARN sem linha".
- [x] `docs/OPERATIONS_RUNBOOK.md` (`TRANSACTION_STUCK`), IG 6.1.2, flow map da conciliação,
      `docs/monitoring/round-report.md`, T53 e roteiro da VPS atualizados.
- [ ] Próxima rodada da T53 sem `HALT` por esse motivo; se houver pavio com expiração parcial, a transação
      fecha e o lote volta a operar.

## Fontes

- `.ai/tasks/T53-testnet-soak.md`, rodada 8 (F11, F12, F13)
- `.ai/tasks/T25-safe-mode-automatico.md`, `.ai/tasks/T44-strategy-independent-position-protection.md`, `.ai/tasks/T46-fee-asset-and-net-position.md`, `.ai/tasks/T66-log-coverage-and-rest-failure-visibility.md`
- Binance Spot API, status de ordem (`EXPIRED`, `EXPIRED_IN_MATCH`) e erro `-2011`:
  https://developers.binance.com/docs/binance-spot-api-docs/enums e
  https://developers.binance.com/docs/binance-spot-api-docs/errors
