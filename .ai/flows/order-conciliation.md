# Order Conciliation

## Objetivo

Aplicar atualizacoes de ordens vindas da exchange ao estado local de
transacoes, posicoes, matches e eventos financeiros, preservando idempotencia e
transicoes validas.

O mesmo executor atende o fluxo normal de eventos e os fluxos de boot/recovery.

## Quando Consultar

- Bugs em `NEW`, `PARTIALLY_FILLED`, `FILLED`, `CANCELED`, `EXPIRED` ou
  `REJECTED`.
- Mudancas em idempotencia de fills.
- Alteracoes em abertura/reducao de posicao.
- Recuperacao de transacoes durante boot.
- Liberacao de margem em encerramentos sem fill final.

## Entradas E Saidas

- Entrada principal: `OrderDataDto`.
- Identificador: `clientOrderId`.
- Saida principal: `Transaction` atualizada.
- Efeitos colaterais: alteracao de `Position`, criacao de `TransactionMatch`,
  publicacao de `ExecutionConfirmedEvent` ou `MarginReleaseEvent`.

## Fluxo Principal

1. `OrderConciliationUseCase` recebe `OrderDataDto`.
2. `ConciliationOrderUpdateExecutor` abre a fronteira transacional apropriada.
3. `ConciliationOrderUpdate` valida `clientOrderId`.
4. Carrega a transacao local correspondente.
5. Roteia pelo status recebido da exchange.
6. Aplica transicao de estado, fill incremental ou encerramento.
7. Salva os agregados e publica eventos quando necessario.

## Roteamento De Status

- `NEW`: submete transacao `PENDING` para `SUBMITTED`; duplicatas sao
  ignoradas quando a transacao ja saiu de `PENDING`.
- `PARTIALLY_FILLED`: aplica apenas o delta ainda nao processado.
- `FILLED`: aplica o delta final e finaliza a transacao.
- `CANCELED`, `EXPIRED`, `REJECTED`: aplica transicao terminal e libera margem (T67):
  - transacao ja final ignora o evento (`duplicate <status> ignored`); o mesmo encerramento chega
    pelo stream e pela consulta do watchdog;
  - execucao informada no evento terminal e maior que a conhecida e aplicada antes, como fill
    parcial (fill que o stream perdeu e o watchdog leu por REST);
  - `EXPIRED` de transacao com execucao vira `CANCELED` (IG 6.1.2): a Binance expira o resto de uma
    ordem a mercado que esgota o livro. Devolve so a reserva nao executada (BUY) ou destrava o resto
    do lote (SELL).
- `FILLED` de BUY tambem libera margem: o excedente da reserva sobre o custo executado (T36).

Fills podem submeter uma transacao ainda `PENDING` quando a exchange envia fill
antes do evento `NEW`.

**Rastreamento end-to-end (T26):** apos resolver a transacao por `clientOrderId`,
`ConciliationOrderUpdate` ancora o `transactionId` no MDC durante todo o roteamento (put/remove
simples) e loga a propria falha in-scope. Esta e a **unica** classe que gerencia o MDC de
`transactionId` no projeto, entao as linhas `PENDING->SUBMITTED`, `BUY fill`, `SELL match
persisted`, falha de conciliacao etc. ficam recuperaveis via
`{app="ctrade"} | json | transactionId="X"` no Loki.

## BUY Fill

`BuyFillHandler` aplica quantidade executada na posicao:

1. Procura posicao por `openedByTransactionId`.
2. Se nao existir, procura posicao aberta do mesmo runner/simbolo.
3. Se ainda nao existir, cria nova `Position` (`Position.open`, com quantidade recebida e custo pago).
4. Associa a transacao de abertura quando necessario.
5. Salva posicao e transacao na mesma unidade transacional.
Fee e custo (T46): a posicao recebe a quantidade efetivamente recebida e o custo em quote
efetivamente pago (`FillCalculator.costIncrement`, diferenca dos valores executados cumulativos).
Fee no ativo base reduz a quantidade (padrao Binance para BUY); fee em quote ou terceiro ativo e
ignorada com log ERROR; sem ativo informado, a fee e ignorada (legado).

6. No fill final (`FILLED`), publica `MarginReleaseEvent` com o excedente
   `total reservado - valor executado` (`ReleaseReason.FILLED_EXCESS`, T36), na mesma unidade
   transacional. Sem excedente (execucao no proprio limite), nada e publicado.

Conflito concorrente ao criar posicao e tratado recarregando a posicao aberta e
aplicando o incremento.

## SELL Fill

`SellFillHandler` reduz a posicao alvo:

1. Resolve posicao, nesta ordem: `targetLotId` da transacao; posicao travada por aquela transacao
   (`locked_by_transaction_id`, status `CLOSING`); posicao aberta do runner/simbolo.
2. Aplica reducao incremental.
3. Destrava a posicao no fill final.
4. Cria `TransactionMatch`.
5. Publica `ExecutionConfirmedEvent` com `totalCost` vindo do `cost_basis` da posicao (fracao
   proporcional; restante exato quando a venda zera a posicao).
6. No fill final, se sobrar quantidade que a exchange nao aceita vender (`SellableQuantityPolicy`,
   via `OrderQuantityNormalizerPort`), baixa o residuo como perda (`Position.writeOffDust`), fecha a
   posicao e publica um segundo `ExecutionConfirmedEvent` (custo do residuo, PnL = -custo,
   `matchId` deterministico por posicao). A baixa mora em `UnsellableRemainderWriteOff` e vale
   tambem para a SELL interrompida depois de executar em parte (`TerminationHandler`, T67).

Fee da SELL (T46): em quote entra no PnL; sem ativo, assumida em quote (legado); em base ou
terceiro ativo fica no match como pendente de conversao, fora do PnL, com log ERROR.

Posicoes ativas precisam ter `openedByTransactionId`; ausencia dessa origem e
erro de consistencia.

## Idempotencia

- `FILLED` duplicado e ignorado se a quantidade recebida nao aumentar a
  quantidade ja registrada ou se a transacao ja for terminal.
- `PARTIALLY_FILLED` duplicado e ignorado se a quantidade recebida for nula ou
  menor/igual ao executado atual.
- O efeito economico e calculado pelo incremento, nao pelo total recebido.
- Eventos financeiros derivados devem ser idempotentes no fluxo de capital.

## Componentes Principais

| Componente | Papel |
| --- | --- |
| `core/src/main/java/com/marmitt/core/application/usecase/runner/OrderConciliationUseCase.java` | Entrada do caso de uso. |
| `core/src/main/java/com/marmitt/core/application/usecase/runner/orderconciliation/ConciliationOrderUpdateExecutor.java` | Contrato compartilhado entre eventos normais e boot. |
| `core/src/main/java/com/marmitt/core/application/usecase/runner/orderconciliation/ConciliationOrderUpdate.java` | Roteia status e aplica transicoes. |
| `core/src/main/java/com/marmitt/core/application/usecase/runner/orderconciliation/BuyFillHandler.java` | Aplica fills BUY em posicao. |
| `core/src/main/java/com/marmitt/core/application/usecase/runner/orderconciliation/SellFillHandler.java` | Aplica fills SELL, matches e evento financeiro. |
| `core/src/main/java/com/marmitt/core/domain/runner/Transaction.java` | Maquina de estados da transacao. |
| `spring-application/src/main/java/com/marmitt/application/spring/config/core/RunnerConfig.java` | Implementa executor com `TransactionTemplate`. |

## Invariantes

- SELL sem `targetLotId` (FIFO) precisa continuar resolvivel depois do lock: travar a posicao a tira
  de `OPEN`, entao a busca por posicao aberta sozinha nao a encontra.
- Transicoes de `Transaction` sao monotonicas; estados terminais nao devem ser
  reabertos.
- Eventos duplicados nao podem duplicar quantidade, PnL, match ou efeito
  financeiro.
- A soma economica dos fills aplicados nao pode exceder a execucao informada
  pela exchange.
- SELL nao pode vender quantidade indisponivel.
- Posicao travada por SELL deve ser destravada no encerramento final.
- A soma dos custos baixados de um lote (vendas + residuo) e exatamente o quote pago por ele (T46).
- Quantidade do lote = quantidade recebida, liquida de fee cobrada no ativo base (T46).

## Validacao

- `core/src/test/java/com/marmitt/core/application/usecase/runner/orderconciliation/ConciliationOrderUpdateIdempotencyTest.java`
- `core/src/test/java/com/marmitt/core/domain/runner/TransactionInvariantsTest.java`
- `spring-application/src/test/java/com/marmitt/application/spring/userdata/UserDataStreamConciliationIntegrationTest.java`
- `spring-application/src/test/java/com/marmitt/application/spring/bootstrap/OrderTerminationConciliationIntegrationTest.java`
- Comando recomendado:
  `./scripts/gradle-run.ps1 -q :core:test :spring-application:test`

## Fontes

- `docs/IMPLEMENTATION_GUIDE.md`, secoes 5, 6, 10 e 15.
- `docs/PHASE0_INVARIANTS.md`.
- `core/src/main/java/com/marmitt/core/application/usecase/runner/orderconciliation/`.
- `core/src/main/java/com/marmitt/core/domain/runner/Transaction.java`.
