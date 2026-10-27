# Runner Signal Processing

## Objetivo

Transformar ticks de mercado em decisoes de estrategia e, quando aplicavel,
persistir uma transacao BUY ou SELL antes de enviar a ordem para a exchange.

Este fluxo fica no core. O Spring apenas fornece fronteiras transacionais e
adaptadores externos.

## Quando Consultar

- Mudancas em processamento de sinais de trade.
- Bugs em ordens que nao sao enviadas ou sao enviadas indevidamente.
- Alteracoes no contexto entregue a estrategias.
- Regras de reserva de capital, exposicao ou lock de posicao antes do dispatch.

## Entradas E Saidas

- Entrada principal: `MarketDataDto`.
- Entrada derivada: runners operacionais por simbolo/exchange.
- Saida principal: transacao `PENDING` persistida e ordem enviada via
  `OrderDispatchPort`.
- Efeitos colaterais: reserva atomica de capital para BUY; lock de posicao para
  SELL.

## Fluxo Principal

1. `ProcessTradeSignalUseCase` recebe um tick de mercado.
2. Busca runners operacionais por `symbol` e `exchange` e deposita o tick na **mailbox** de cada um
   (`RunnerMailbox`, T42): capacidade 1, latest-wins, um runner por vez, tick fora de ordem
   descartado. A avaliacao abaixo roda no `runnerSignalExecutor`, ja com o runner relido; tick mais
   velho que `runner.signal.stale-tick-threshold-ms` e descartado antes de avaliar.
3. `RunnerSignalPolicy` filtra runners que podem avaliar o sinal.
   Antes de tudo roda a **protecao do lote** (ramo abaixo, T44), em qualquer nivel do Safe Mode;
   quando ela age no tick, a estrategia nao e consultada.
   Le o nivel do Safe Mode de cada portfolio uma vez por tick. Em `CANCEL_ALL`/`PANIC_SELL` o runner
   segue o ramo "Safe Mode" abaixo e a estrategia nao e consultada (T45).
4. `RunnerContextAssembler` monta `StrategyContextDto` com portfolio,
   posicoes, ordens pendentes, limites e PnL. A posicao e marcada ao preco do tick (em memoria):
   `unrealizedPnl` e o percentual do lote refletem o mercado (T43).
5. `StrategySignalEvaluator` resolve a estrategia **so por `strategyId`** e a avalia. Qualquer falha
   (excecao, retorno nulo, sinal invalido, estrategia remota indisponivel) vira HOLD implicito com o
   desfecho `STRATEGY_UNAVAILABLE` (T41). Estrategia remota = `RemoteTradingStrategy` posta no
   repositorio pelo catalogo persistido (`RemoteStrategyCatalogUseCase`, T54) e avaliada pelo
   `StrategyEvaluationPort` com o endereco/token do catalogo (contrato em `docs/strategy-service/`).
   Ela so e consultada quando habilitada e conferida via `/v1/meta` neste processo; desabilitada ou
   pendente, o runner fica em HOLD implicito sem chamar o servico.
6. Sinal `HOLD` encerra o fluxo sem efeito.
7. `PreTradeRiskPolicy` (T50) descarta, antes de persistir: `limitPrice` a mais de 10% do preco do
   tick (BUY e SELL) e BUY com o runner em `maxOpenPositions` ou `maxPendingOrders` (snapshot de
   exposicao carregado para todo BUY). SELL nunca e barrada por contagem, para nao travar saidas.
8. `OrderNormalizer` alinha quantidade e preco as regras de filtro da exchange
   (`stepSize`/`tickSize`) ANTES de materializar a transacao, via
   `OrderQuantityNormalizerPort`. O preco arredonda a favor do trader conforme o lado
   (BUY para baixo, SELL para cima; T37), com o lado derivado por
   `TradeIntentFactory.orderSideOf`. Exchange sem normalizador (ex.: MOCK) usa os
   valores originais.
9. Sinal `BUY` passa por `BuySignalHandler`.
10. Sinal `SELL` passa por `SellSignalHandler`.
11. A transacao e persistida antes do envio externo da ordem.

## BUY

1. `TradeIntentFactory` cria a intencao da transacao.
2. `BuySignalHandler` salva a transacao como `PENDING`.
3. `CapitalReservationPolicy` tenta reservar capital no `GlobalBalance`.
4. Se a reserva for aceita, a ordem e enviada por `OrderDispatchPort`.
5. A transacao continua `PENDING` ate a exchange confirmar via conciliacao.

Se a reserva for rejeitada, o sinal e descartado sem dispatch.

## SELL

1. `SellSignalHandler` resolve a posicao alvo por `targetLotId` ou por posicao
   aberta do runner/simbolo.
2. Lote de outro runner e descartado como sem posicao (`REJECTED_NO_POSITION`); quantidade
   (normalizada) acima da do lote e descartada como `REJECTED_QUANTITY` — nada e persistido,
   travado ou enviado (T61). O `tryLockPositionForSell` tambem recusa travar acima do lote.
3. A transacao SELL e persistida como `PENDING`.
4. A posicao e travada com `tryLockPositionForSell`.
5. A ordem e enviada por `OrderDispatchPort`.

Se nao houver posicao aberta, o sinal e descartado. Se outra venda travou a
mesma posicao, o fluxo falha com `ConcurrentPositionLockException`.

## Protecao Do Lote (T44)

A cada tick, antes da estrategia e do Safe Mode, `PositionProtectionHandler` avalia o lote ativo do
runner (no maximo um por simbolo) contra `StrategyRunner.positionProtection`:

- `maxLossPercentPerLot` — perda em relacao ao `costBasis` do lote (fracao, default `0.05`);
- `maxHoldingDuration` — idade maxima do lote (desligada por padrao).

Lote que viola um limite:

1. `checkRunnerNotHalted` (kill switch tem precedencia).
2. Sem lock: vira sinal de venda MARKET do lote inteiro (`TradeIntentFactory.liquidationSignal`) e
   segue o ramo SELL acima. Despachada, gera alerta `PROTECTIVE_EXIT` pelo `ErrorNotificationPort`.
3. Travado por SELL LIMIT em voo: `CancelSignalHandler.cancelResting` envia o cancelamento; a venda
   sai num tick seguinte, quando o `CANCELED` destrava o lote.
4. Travado por SELL MARKET (a propria saida ou o PANIC_SELL): nada a fazer; a estrategia segue.

Quando age (2 ou 3), a protecao registra `PROTECTIVE_EXIT` e a estrategia nao e consultada no tick.
Lotes abertos antes da feature entram na protecao. Sem estado proprio: cada tick reavalia o lote.
Limites alterados por `PATCH /api/admin/runners/{id}` (`maxLossPercentPerLot`, `maxHoldingDuration`
ISO-8601, `PT0S` desliga a idade; e, para o PANIC_SELL, `liquidationOrderType`,
`liquidationMaxSlippagePercent`, `liquidationEscalationTicks`).

## Safe Mode (T45)

Nos niveis `CANCEL_ALL` e `PANIC_SELL`, a cada tick do runner:

1. `checkRunnerNotHalted` (kill switch tem precedencia).
2. `SafeModeHandler.cancelRestingOrders` envia cancelamento para cada ordem LIMIT em voo
   (`TransactionStatus.IN_FLIGHT`) por `CancelSignalHandler.cancelResting`, o mesmo caminho da
   protecao do lote. Ordens MARKET nao sao canceladas, nem a SELL LIMIT de liquidacao ainda no prazo.
3. Em `PANIC_SELL`, `SafeModeHandler.liquidations` le as posicoes ativas (OPEN e CLOSING) do runner
   e aplica a `LiquidationPolicy` do runner (T48):
   - `MARKET` (default): cada lote sem lock vira venda do lote inteiro com `OrderType.MARKET`;
   - `LIMIT`: o lote sem tentativa recebe uma SELL LIMIT a `preco do tick × (1 − maxSlippagePercent)`
     (normalizada SELL→ceiling, T37). Com o lote travado por ela, cada tick conta; ao atingir
     `escalationTicks` a LIMIT e cancelada. Destravado o lote apos a tentativa (cancelada, rejeitada
     ou parcial), o restante sai a MARKET.
   Posicao travada por SELL LIMIT da estrategia espera o cancelamento a destravar; posicao travada
   pela propria liquidacao nao e vendida de novo.

A tentativa LIMIT fica em memoria por lote (a `Transaction` nao registra a origem da ordem) e e
descartada fora do `PANIC_SELL`. Apos restart, a LIMIT em voo e cancelada como qualquer outra e o lote
ganha nova tentativa.

`HALT` nao muda este fluxo: a estrategia roda e pode vender; o BUY e recusado na reserva de capital.
O tipo da ordem e decidido aqui (`Transaction.orderType`): `LIMIT` para sinais da estrategia,
`MARKET` para as saidas decididas pelo core (fechar posicao, protecao do lote) e, no `PANIC_SELL`, o
tipo da `LiquidationPolicy` do runner.

## Ciclo De Vida E Comandos Do Operador (T49)

- `StrategyRunner.canAcceptSignals()` = ACTIVE ou TERMINATING fora de reconciliacao: o runner e
  conduzido pelo pipeline. `canOpenPositions()` = so ACTIVE: BUY barrado em TERMINATING
  (`RunnerSignalPolicy` e releitura `requireRunner` antes do persist e do dispatch).
- `RunnerOrderCommandPort`, implementado pelo `ProcessTradeSignalUseCase`:
  - `cancelOrder` passa pelo mesmo `CancelSignalHandler` do `SHOULD_CANCEL`;
  - `closePosition` gera o sinal de liquidacao MARKET (`TradeIntentFactory.liquidationSignal`) e
    segue o ramo SELL, com o ultimo preco (`LatestPricePort`) como referencia. Runner halted e recusado.
  - `PATCH` altera limites de capital e a protecao do lote; pedido com um campo invalido nao aplica
    os demais.

## Componentes Principais

| Componente | Papel |
| --- | --- |
| `core/src/main/java/com/marmitt/core/application/usecase/runner/processsignal/ProcessTradeSignalUseCase.java` | Orquestra ticks, runners, contexto, decisao e handlers BUY/SELL. |
| `core/src/main/java/com/marmitt/core/application/usecase/runner/processsignal/OrderNormalizer.java` | Resolve `OrderQuantityNormalizerPort` por exchange e alinha quantidade/preco antes do persist. |
| `core/src/main/java/com/marmitt/core/application/usecase/runner/processsignal/RunnerContextAssembler.java` | Monta `StrategyContextDto`. |
| `core/src/main/java/com/marmitt/core/application/usecase/runner/processsignal/BuySignalHandler.java` | Persiste BUY, reserva capital e faz dispatch. |
| `core/src/main/java/com/marmitt/core/application/usecase/runner/processsignal/SellSignalHandler.java` | Persiste SELL, trava posicao e faz dispatch. |
| `core/src/main/java/com/marmitt/core/application/usecase/runner/processsignal/CapitalReservationPolicy.java` | Valida Safe Mode, fundos e limite de exposicao por runner. |
| `core/src/main/java/com/marmitt/core/application/usecase/runner/processsignal/PreTradeRiskPolicy.java` | Sanidade de preco e limites `maxOpenPositions`/`maxPendingOrders` antes do persist (T50). |
| `core/src/main/java/com/marmitt/core/application/usecase/runner/processsignal/SafeModeHandler.java` | Cancela ordens LIMIT em voo e gera a liquidacao nos niveis CANCEL_ALL/PANIC_SELL (T45), MARKET ou LIMIT com escalada conforme a politica do runner (T48). |
| `core/src/main/java/com/marmitt/core/domain/runner/LiquidationPolicy.java` | Politica de liquidacao do PANIC_SELL por runner: tipo, afastamento da LIMIT e ticks ate escalar (T48). |
| `core/src/main/java/com/marmitt/core/application/usecase/runner/processsignal/PositionProtectionHandler.java` | Detecta lote que viola a protecao, gera a saida MARKET ou cancela a SELL LIMIT que o trava (T44). |
| `core/src/main/java/com/marmitt/core/domain/runner/PositionProtection.java` | Limites do lote por runner e a regra de violacao (perda sobre o custo, idade). |
| `spring-application/src/main/java/com/marmitt/application/spring/config/core/RunnerConfig.java` | Injeta fronteiras transacionais com `TransactionTemplate`. |

## Invariantes

- Nenhuma ordem externa deve ser enviada antes da transacao local existir.
- BUY so pode seguir para dispatch depois de reserva de capital aceita.
- Quantidade e preco persistidos/reservados devem ser identicos aos enviados a
  exchange: a normalizacao acontece antes do persist, e o `OrderFilterValidator`
  no dispatch apenas valida (rejeita desalinhamento), sem reajustar valores.
- Preco normalizado nunca fica contra a intencao do trader: BUY nunca acima e SELL
  nunca abaixo do preco pretendido, com deslocamento menor que um tick (T37).
- SELL so pode seguir para dispatch depois de posicao travada.
- Falha em um runner nao deve impedir a avaliacao dos demais runners do tick.
- `RunnerContextAssembler` exige `GlobalBalance`; sem ele a avaliacao nao deve
  seguir.
- O core nao deve depender de detalhes do adapter da exchange.

## Observabilidade

`ProcessTradeSignalUseCase` emite o counter `signal.evaluated.total{runnerId,decision}` via
`SignalMetricsPort` (T23 G2; impl Micrometer `MicrometerSignalMetricsAdapter` no spring). O
`core` nao depende de Micrometer — a porta espelha o padrao de `BootExecutionObserverPort`.

As tags `decision` sao **outcomes mutuamente exclusivos**, nao a decisao bruta da estrategia:

- `HOLD` — sinal HOLD.
- `BUY` / `SELL` — ordem efetivamente despachada.
- `CANCEL` — `SHOULD_CANCEL` roteado.
- `REJECTED_CAPITAL` — BUY recusado na reserva de capital (engolido em `BuySignalHandler`).
- `REJECTED_LOCK` — SELL perdeu o lock concorrente da posicao.
- `REJECTED_NO_POSITION` — SELL sem posicao aberta do runner para vender (inclui `targetLotId` de
  lote alheio, T61).
- `REJECTED_QUANTITY` — SELL acima da quantidade do lote alvo (T61).
- `REJECTED_POLICY` — sinal barrado pela guarda de execucao (sobretudo politica
  SINGLE com posicao/ordem ja aberta) antes do pipeline de trade.
- `REJECTED_PRICE_DEVIATION` / `REJECTED_MAX_POSITIONS` / `REJECTED_MAX_PENDING` — limites de risco
  pre-trade (T50).
- `SAFE_MODE` — tick conduzido pelo Safe Mode (T45).
- `STRATEGY_UNAVAILABLE` — estrategia sem decisao utilizavel (T41).
- `PROTECTIVE_EXIT` — a protecao do lote agiu no tick (T44); a venda despachada tambem conta `SELL`.

Para distinguir despacho de rejeicao sem efeito colateral escondido, `BuySignalHandler` e
`SellSignalHandler` retornam `BuyOutcome`/`SellOutcome`; o registro fica centralizado no
orquestrador. Catalogo de indicadores/alertas em `/.ai/monitoring-spec.md`.

**Rastreamento end-to-end (T26):** **apenas quando a transacao e persistida e despachada**
(outcome `DISPATCHED`), o orquestrador emite `signal: transaction created transactionId={}
correlationId={} runnerId={} side={}`. Sinais descartados antes do commit (BUY recusado por
capital, SELL sem posicao, falha de lock com rollback) nao emitem o log — evita transacao
fantasma. O `correlationId` (do tick, herdado do MDC) cria o "join" tick→transacao. Este fluxo
**nao** gerencia MDC de `transactionId` (que vive so na conciliacao); seus logs carregam o id no
texto da mensagem, recuperaveis por `{app="ctrade"} |= "transactionId=X"`.

## Validacao

- `spring-application/src/test/java/com/marmitt/application/spring/bootstrap/ProcessTradeSignalMockIntegrationTest.java`
- `spring-application/src/test/java/com/marmitt/application/spring/bootstrap/PositionProtectionE2ETest.java`
- `core/src/test/java/com/marmitt/core/domain/runner/PositionProtectionTest.java`
- `core/src/test/java/com/marmitt/core/domain/runner/StrategyRunnerInvariantsTest.java`
- `core/src/test/java/com/marmitt/core/domain/portfolio/GlobalBalanceInvariantsTest.java`
- Comando recomendado para mudancas neste fluxo:
  `./scripts/gradle-run.ps1 -q :core:test :spring-application:test`

## Fontes

- `docs/IMPLEMENTATION_GUIDE.md`, secoes 12, 13 e 14.
- `docs/PHASE0_INVARIANTS.md`.
- `core/src/main/java/com/marmitt/core/application/usecase/runner/processsignal/`.
- `spring-application/src/main/java/com/marmitt/application/spring/config/core/RunnerConfig.java`.
