# T67 — Ordem Expirada Após Preenchimento Parcial

**Complexidade:** Média
**Responsável:** Claude
**Dependências:** T53 (rodada 8, achados F11, F12 e F13), T25 (watchdog), T44 (saída de proteção), T66
(`round_report.py`).
**Status:** A fazer

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
  `cancel()` já aceita `PARTIAL → CANCELED`.
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

- **Domínio:** `Transaction.expire()` passa a aceitar `PARTIAL → EXPIRED`, como o `cancel()`.
- **Margem:** `EXPIRED` usa a mesma regra do `CANCELED` (`reservado − executado`; sem execução, é a reserva
  inteira). Só o `REJECTED` continua com devolução total, porque nunca executa. É uma única fórmula para os
  dois encerramentos que podem ter execução (um fluxo, um caminho).
- **Lote:** sem código novo. O `TerminationHandler` destrava o lote com o resto, e ele volta a `OPEN` para a
  próxima venda. O caminho é o mesmo do `CANCELED` parcial.
- **Resto abaixo do mínimo da exchange:** hoje a baixa de resíduo (`Position.writeOffDust`, T46) só roda no
  fill final da SELL. Um `CANCELED` ou `EXPIRED` parcial que deixa resto invendável reabriria um lote que
  nunca mais vende. O encerramento parcial de SELL passa a usar a mesma `SellableQuantityPolicy`: resto
  invendável é baixado como resíduo e o lote fecha. Isso vale para os dois status.
- **Execução que só chega no evento terminal:** se o `CANCELED`/`EXPIRED` trouxer quantidade executada maior
  que a conhecida (por exemplo, o watchdog leu por REST um fill que o stream perdeu), a conciliação aplica
  primeiro esse incremento pelo caminho de fill parcial e só depois encerra. Sem isso, o fill some da
  contabilidade e a conciliação com a exchange acusa a diferença.

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

- `CreateRunnerUseCase` recusa `allowedMarketDataSources` nulo ou vazio com `INVALID_REQUEST` e uma mensagem
  que diz o que falta. Um runner sem fonte nunca recebe tick e nunca opera, então aceitar a criação só
  adia o erro.
- Fica de fora usar a exchange do runner como fonte padrão: seria um segundo jeito de definir a fonte e
  esconderia a escolha.

### 5. Contagem do relatório (F13, script)

- `round_report.py` conta ERROR/WARN no Loki com `count_over_time` (sem limite) e compara esse número com o
  Prometheus. As linhas devolvidas continuam servindo só para agrupar os padrões, e o limite atingido segue
  sinalizado.

**Fora do escopo:**

- Mudar a saída de proteção (MARKET contra LIMIT, tamanho da perda no livro raso). A proteção agiu como
  desenhada; a perda veio do livro da testnet.
- Mudar a sensibilidade do watchdog. Continua com o mantenedor.

## Riscos

- `EXPIRED` também chega por ordem que nunca preencheu (por exemplo, a SELL da tendência na mesma rodada). Os
  testes cobrem os dois casos: sem execução, o resultado é o de hoje (reserva inteira para BUY, lote
  destravado para SELL).
- Aplicar um fill a partir do evento terminal depende do preço médio executado na resposta. Sem preço positivo,
  a conciliação mantém a regra atual de resposta inválida (`INVALID_EXCHANGE_RESPONSE`), em vez de inventar o
  preço.
- Recusar runner sem fonte quebra quem cria runner sem o campo. Os chamadores conhecidos são o roteiro da T53
  e os testes; a implementação ajusta os dois.

## Critérios de aceitação

- [ ] SELL `PARTIAL` que recebe `EXPIRED`: transação `EXPIRED`, executado preservado, lote com o resto em
      `OPEN` e destravado. Teste de conciliação.
- [ ] BUY `PARTIAL` que recebe `EXPIRED`: devolve `reservado − executado`, e o lote da parte executada
      continua. Teste do `MarginReleaseBuilder` e de conciliação.
- [ ] `EXPIRED` sem execução: comportamento de hoje, nos testes existentes sem mudança.
- [ ] SELL `CANCELED`/`EXPIRED` parcial com resto invendável: resíduo baixado e lote fechado. Teste de
      conciliação.
- [ ] Evento terminal com execução maior que a conhecida aplica o incremento antes de encerrar, pelo stream e
      pelo watchdog. Testes de conciliação e do `RecoverTransactionStatusUseCase`.
- [ ] `CANCELED`/`EXPIRED`/`REJECTED` repetidos ignorados sem `ERROR`. Teste de conciliação.
- [ ] `-2011` no cancelamento vira `ORDER_NOT_FOUND`, e o core registra `WARN` sem mexer na transação. Testes
      no adapter e no core.
- [ ] Criação de runner sem `allowedMarketDataSources` recusada com `INVALID_REQUEST`. Teste do use case e
      do controller.
- [ ] `round_report.py` conta ERROR/WARN pelo `count_over_time`; contra a rodada 8, não acusa WARN sem linha.
- [ ] `docs/OPERATIONS_RUNBOOK.md` (`TRANSACTION_STUCK`), T53 (F11–F13) e o roteiro da VPS atualizados.
- [ ] Próxima rodada da T53 sem `HALT` por esse motivo; se houver pavio com expiração parcial, a transação
      fecha e o lote volta a operar.

## Fontes

- `.ai/tasks/T53-testnet-soak.md`, rodada 8 (F11, F12, F13)
- `.ai/tasks/T25-safe-mode-automatico.md`, `.ai/tasks/T44-strategy-independent-position-protection.md`, `.ai/tasks/T46-fee-asset-and-net-position.md`, `.ai/tasks/T66-log-coverage-and-rest-failure-visibility.md`
- Binance Spot API, status de ordem (`EXPIRED`, `EXPIRED_IN_MATCH`) e erro `-2011`:
  https://developers.binance.com/docs/binance-spot-api-docs/enums e
  https://developers.binance.com/docs/binance-spot-api-docs/errors
