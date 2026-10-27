# Portfolio Capital

## Objetivo

Controlar capital por portfolio, incluindo saldo disponivel, saldo reservado,
resultado realizado, reserva para BUY, confirmacao financeira de execucoes e
liberacao de margem em encerramentos.

## Quando Consultar

- Mudancas em `GlobalBalance`.
- Bugs de reserva, liberacao, PnL ou taxa.
- Alteracoes em Safe Mode, limite de exposicao por runner ou capital pooling.
- Eventos financeiros disparados pela conciliacao de ordens.
- DLQ e replay de eventos de capital.

## Entradas E Saidas

- Entrada para reserva: runner, portfolio, valor solicitado e exposicao atual.
- Entrada para confirmacao: `ExecutionConfirmedEvent`.
- Entrada para liberacao: `MarginReleaseEvent`.
- Saida principal: `GlobalBalance` atualizado.
- Efeitos colaterais: registro idempotente de evento de capital e, em falha
  persistente, dead letter.

## Modelo Principal

`GlobalBalance` mantem:

- `availableBalance`
- `reservedBalance`
- `realizedBalance`
- `initialCapital`
- `baseCurrency`
- `totalFeesPaid`
- `lastExecutionTime`

`getTotalBalance` considera `availableBalance + reservedBalance`.

## Criacao De Portfolio

1. `CreatePortfolioUseCase` valida unicidade de nome.
2. Cria `Portfolio`.
3. Registra o portfolio.
4. Cria `GlobalBalance` inicial com capital e moeda base.

## Safe Mode (T45)

Nivel por portfolio, persistido em `portfolios` com origem, motivo e data; cada mudanca grava
`safe_mode_changes`. Mudanca por `ManageSafeModePort` (`MANUAL` pela API admin, `AUTOMATIC` pelos
gatilhos de T43/T25). Regras em `Portfolio.changeSafeMode`:

- motivo obrigatorio; mesmo nivel nao muda nada (idempotente);
- subida pode pular niveis; `CANCEL_ALL` e `PANIC_SELL` exigem confirmacao;
- descida um nivel por vez (3 -> 2 -> 1 -> NORMAL);
- `AUTOMATIC` so sobe ate `HALT` e nao rebaixa nivel manual mais alto.

| Nivel | Efeito |
|---|---|
| `HALT` | Recusa reserva de BUY. A estrategia segue rodando e pode vender. |
| `CANCEL_ALL` | HALT + cancela ordens LIMIT em voo; estrategia nao e consultada. |
| `PANIC_SELL` | CANCEL_ALL + vende as posicoes abertas conforme a `LiquidationPolicy` de cada runner: MARKET (default) ou LIMIT com escalada para MARKET (T48). |

A execucao acontece nos ticks (`runner-signal-processing.md`). Capital volta pela conciliacao
normal do CANCELED/FILLED, sem atalho. Runners fora de operacao (kill switch) nao sao conduzidos.

## Circuit Breaker De Drawdown (T43)

`DrawdownMonitorScheduler` (spring, a cada `portfolio.circuit-breaker.check-interval-ms`) chama
`EvaluateDrawdownUseCase`. Por portfolio ativo:

1. Equity = `available + reserved + PnL flutuante` (`PortfolioMarkToMarket`: posicoes OPEN e CLOSING
   ao ultimo preco do `LatestPricePort`). Sem preco para alguma posicao, o portfolio e pulado (`WARN` uma
   vez por lote). Lote de runner parado tambem tem o simbolo assinado pelo coordenador de market data
   (T58, `OpenLotDemand`), entao a janela sem preco se limita ao primeiro ciclo apos o boot. O mesmo
   `PortfolioMarkToMarket` alimenta o PnL, que informa `unpricedPositions` e `priceAsOf`.
2. Grava a amostra em `equity_snapshots`; pico = maior amostra desde `agora - 24h` ou desde o ultimo
   reset manual para NORMAL, o que for mais recente.
3. Em NORMAL, `(pico - equity) / pico > max-drawdown-percent` (10%) aciona HALT `AUTOMATIC` pelo
   `AutomaticSafeModeTrigger`, com motivo `MAX_DRAWDOWN_EXCEEDED`, metrica e Discord. Sem retomada automatica.

## Gatilhos Automaticos (T25)

Todo HALT automatico do portfolio passa pelo `AutomaticSafeModeTrigger` (core): so age com o portfolio
em NORMAL (sem repetir mudanca, metrica, log ou alerta), muda o nivel pelo `ManageSafeModePort` com
origem `AUTOMATIC`, conta `circuit_breaker.activations.total{reason}` e notifica o Discord. Gatilhos
(`SafeModeTrigger`), cada um desligavel em `safe-mode.auto.*`:

- `MAX_DRAWDOWN_EXCEEDED` — drawdown 24h (T43, chave propria).
- `DLQ_THRESHOLD` — `EvaluateOperationalTriggersUseCase` (a cada 30s): >= 3 entradas nao resolvidas.
- `BALANCE_DIVERGENCE` — soma dos portfolios de uma exchange acima da conta (`DEFICIT` da
  `ExchangeBalanceReconciliation`, T57): no boot (`RunBootSequenceUseCase`) e na avaliacao periodica
  (`EvaluateExchangeBalanceUseCase`, 5 min, 2 leituras seguidas). HALT de todos os portfolios da exchange.
- `WATCHDOG_STUCK` — mesma transacao `FAILED` em 3 ciclos seguidos do watchdog
  (`RecoverStaleTransactionsUseCase`, contagem em memoria).

Por runner, fora do Safe Mode: `ConsecutiveRejectionGuard` (na `OrderConciliationUseCase`) para o runner
apos 5 ordens distintas rejeitadas seguidas (`HaltRunnerPort`, alerta `RUNNER_AUTO_HALTED`); ordem
aceita zera a sequencia. Retomada sempre manual (portfolio pela API do Safe Mode, runner pelo `resume`).

## Reserva Para BUY

`CapitalReservationPolicy` decide se um sinal BUY pode seguir:

1. Runner precisa estar associado a portfolio conhecido.
2. Safe Mode do portfolio nao pode bloquear operacao.
3. `GlobalBalance` precisa existir.
4. O valor solicitado precisa caber no espaco do runner
   (`RunnerExposureService.allocationRoom`): `maxAllocationPercent × totalBalance` menos a exposicao,
   que e o custo pago do lote ativo (OPEN ou CLOSING) mais a parte nao executada das BUY em voo. SELL
   em voo nao soma. O lote preenchido continua contando ate ser vendido (T62, decisao B). O mesmo
   espaco e o `maxOperationAmount` do contexto da estrategia.
5. Reserva atomica precisa mover capital de disponivel para reservado.

Falha em qualquer passo levanta `CapitalReservationRejectedException`. O
`BuySignalHandler` descarta o sinal e nao envia ordem externa.

## Confirmacao Financeira

`ExecutionConfirmedReaction` processa `ExecutionConfirmedEvent` apos match de
SELL:

1. Registra idempotencia por `matchId`.
2. Resolve runner e portfolio.
3. Carrega `GlobalBalance`.
4. Chama `confirmExecution(totalCost, pnlRealized, feeConverted)`.
5. Salva o saldo.

Esse metodo reduz reserva, aplica PnL realizado, devolve custo ao disponivel e
acumula taxa convertida.

### Devolucao do excedente da reserva (T36)

A reserva e feita ao **preco-limite** (`TradeIntentFactory`: `total = quantity x price`) e a baixa
em `confirmExecution` usa o **custo executado** (`SellFillHandler`:
`totalCost = fillIncrement x position.getAveragePrice()`). Para a diferenca nao ficar presa, no
fill final de uma BUY o `BuyFillHandler` publica `MarginReleaseEvent` com
`ReleaseReason.FILLED_EXCESS` e `releaseAmount = total - executedValue`
(`MarginReleaseBuilder.buildFilledExcess`). Assim o `reserved` de um lote aberto e exatamente o
custo executado, e um round trip fechado termina com `reserved = 0`.

A fee nao entra nesse calculo (divergencia consciente do IG §7.2.3), porque a venda baixa
`reserved` sem fee. A fee da SELL entra no PnL do match; a fee da BUY hoje nao e contabilizada
(ver T36, "Achado novo"). Nao ha safety buffer: com apenas ordens `LIMIT` a reserva e nominal (decisao T36; buffer obrigatorio so quando existir `MARKET`, IG §7.2.2).

### Custo do lote e residuo invendavel (T46)

O `totalCost` confirmado na venda vem do `cost_basis` da posicao, nao de `quantidade x preco medio`,
para que a soma das baixas feche exatamente com o que ficou reservado. Quando sobra na posicao uma
quantidade abaixo do `stepSize`, o residuo e baixado como perda por um `ExecutionConfirmedEvent`
com `pnlRealized = -custo`: `reserved` cai pelo custo, `available` nao muda e o realizado registra a
perda.

## Liberacao De Margem

`MarginReleasedReaction` processa `MarginReleaseEvent` (terminais de falha e excedente de BUY
`FILLED`):

1. Registra idempotencia por `transactionId`.
2. Resolve runner e portfolio.
3. Carrega `GlobalBalance`.
4. Faz guarda defensiva contra liberacao maior que reservado.
5. Chama `release(amount)`.
6. Salva o saldo.

## Observabilidade

Quando a idempotencia rejeita um evento duplicado (`matchId`/`transactionId` ja
processado), ambas as reactions emitem log em nivel `info` (T23 G5):
`executionConfirmedReaction: duplicate ignored ...` e
`marginReleasedReaction: duplicate ignored ...`. Antes era `debug` (invisivel no
Loki), o que escondia replays inesperados de eventos de capital. Catalogo de
indicadores em `/.ai/monitoring-spec.md`.

**Rastreamento end-to-end (T26):** os logs das reactions de capital
(`executionConfirmedReaction: ... transactionId=...`, `marginReleasedReaction: ...`,
`CAPITAL_DLQ_PERSISTED`) carregam o `transactionId` no **texto** da mensagem, recuperaveis por
`{app="ctrade"} |= "transactionId=X"`. O `CapitalEventListener` **nao** gerencia MDC de
`transactionId` — esse rastreamento estruturado (MDC) fica concentrado na `ConciliationOrderUpdate`.

## DLQ De Capital

`CapitalEventListener` consome eventos apos commit em uma transacao nova. Quando
as tentativas se esgotam, o payload e persistido em dead letter para investigacao
ou replay.

`ManageDeadLetterUseCase` lista pendencias, marca resolvidas e reprocessa via
`DeadLetterReprocessingPort`.

## Componentes Principais

| Componente | Papel |
| --- | --- |
| `core/src/main/java/com/marmitt/core/domain/portfolio/GlobalBalance.java` | Regras de saldo disponivel, reservado e realizado. |
| `core/src/main/java/com/marmitt/core/domain/portfolio/Portfolio.java` | Estado do portfolio e regras de transicao do Safe Mode. |
| `core/src/main/java/com/marmitt/core/application/usecase/portfolio/ManageSafeModeUseCase.java` | Muda e consulta o Safe Mode (status com o que falta convergir). |
| `core/src/main/java/com/marmitt/core/application/usecase/portfolio/CreatePortfolioUseCase.java` | Cria portfolio e saldo inicial. |
| `core/src/main/java/com/marmitt/core/application/usecase/runner/processsignal/CapitalReservationPolicy.java` | Reserva capital para BUY. |
| `core/src/main/java/com/marmitt/core/application/reaction/ExecutionConfirmedReaction.java` | Aplica efeito financeiro de execucao confirmada. |
| `core/src/main/java/com/marmitt/core/application/reaction/MarginReleasedReaction.java` | Libera margem de transacao encerrada. |
| `spring-application/src/main/java/com/marmitt/application/spring/handler/CapitalEventListener.java` | Consome eventos financeiros com retry e DLQ. |
| `core/src/main/java/com/marmitt/core/application/usecase/portfolio/ManageDeadLetterUseCase.java` | Operacao de DLQ. |

## Invariantes

- Saldos nao podem ficar negativos.
- BUY nao pode reservar o mesmo capital mais de uma vez.
- Liberacao de margem terminal nao pode duplicar efeito financeiro.
- Eventos de capital precisam ser idempotentes.
- Safe Mode deve impedir novas reservas quando ativo.
- Mudanca de nivel do Safe Mode sempre grava auditoria; gatilho automatico nunca passa de HALT.
- Equity do breaker nunca soma `realizedBalance` (ja contido em `available`).
- Limite de exposicao do runner deve considerar exposicao atual mais reserva
  solicitada.
- Capital reservado por uma transacao terminal deve voltar ao disponivel, exceto o custo executado
  de um lote ainda aberto, que sai na venda via `confirmExecution` (T36).

## Validacao

- `core/src/test/java/com/marmitt/core/domain/portfolio/GlobalBalanceInvariantsTest.java`
- `core/src/test/java/com/marmitt/core/application/usecase/runner/orderconciliation/MarginReleaseBuilderTest.java`
- `spring-application/src/test/java/com/marmitt/application/spring/bootstrap/ScenarioImmediateRoundTripE2ETest.java` (`reserved = 0` apos round trip)
- `spring-application/src/test/java/com/marmitt/application/spring/handler/CapitalEventListenerTest.java`
- `spring-application/src/test/java/com/marmitt/application/spring/bootstrap/CapitalDeadLetterReplayIntegrationTest.java`
- `spring-application/src/test/java/com/marmitt/application/spring/deadletter/CapitalDeadLetterReprocessingAdapterTest.java`
- `spring-application/src/test/java/com/marmitt/application/spring/controller/DeadLetterControllerTest.java`
- `core/src/test/java/com/marmitt/core/application/usecase/portfolio/ManageDeadLetterUseCaseTest.java`
- Comando recomendado:
  `./scripts/gradle-run.ps1 -q :core:test :spring-application:test`

## Fontes

- `docs/IMPLEMENTATION_GUIDE.md`, secoes 7 e 10.
- `docs/PHASE0_INVARIANTS.md`.
- `core/src/main/java/com/marmitt/core/domain/portfolio/`.
- `core/src/main/java/com/marmitt/core/application/usecase/portfolio/`.
- `spring-application/src/main/java/com/marmitt/application/spring/handler/CapitalEventListener.java`.
