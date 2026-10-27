# T61 — SELL Limitada ao Lote do Runner

**Complexidade:** Baixa
**Responsável:** Claude
**Dependências:** T41 (strategy service remoto), T44 (proteção do lote), T50 (limites pré-trade).
Origem: rodada das cinco apps da T60 na testnet (2026-10-27), fault `OVERSELL` do `SoakChaos`.
**Status:** Concluído — PR #166; validada na testnet em 2026-10-27 (critério 6).

---

## Descrição

O ctrade **envia para a exchange uma SELL maior que o lote**. Na rodada de 2026-10-27, o caos
(`CHAOS_HOLD_POSITION=true`) tinha um lote de 0,00174 BTC e pediu `SHOULD_SELL` de 0,00348. O ctrade
travou o lote, persistiu a transação e despachou; a Binance executou (a conta da testnet tem BTC de
sobra). Resultado:

- 0,00174 BTC vendidos a mais, saldo da conta que não pertence a nenhum runner;
- a SELL ficou `SUBMITTED` no ctrade e o lote travado (`CLOSING`);
- a conciliação falha a cada minuto: `Cannot sell more than current quantity: 0.00348000 > 0.00174000`
  (`Position.reduceQuantity`), `ERROR` no Loki.

Em produção, a venda a mais sai do saldo de outro runner ou da conta. O contrato já promete o
contrário: `availableQuantity` é o teto de SELL do lote (`docs/strategy-service/CONTRACT.md`,
`openapi.yaml` `OpenLot`), e a T60 espera "recusa sem ordem" para esse fault.

No mesmo caminho há um segundo furo: com `targetLotId`, o lote é buscado só pelo id
(`findPositionById`), **sem conferir que é do runner**. Uma estratégia poderia vender o lote de outro
runner do mesmo portfolio (ou de outro portfolio).

## Contexto técnico

- `core/.../processsignal/ProcessTradeSignalUseCase.processTradeSignal` — guardas (`RunnerSignalPolicy`,
  `PreTradeRiskPolicy`), normalização (`OrderNormalizer`), transação, ramo SELL.
- `core/.../processsignal/SellSignalHandler.handle` — `findTargetPosition` (por `targetLotId` ou o lote
  aberto do runner), persistência + lock (`persistSellAndLockPosition`), dispatch. Desfechos
  `DISPATCHED` e `NO_OPEN_POSITION`.
- `StrategyRunnerRepositoryPort.tryLockPositionForSell` →
  `RunnerPositionJdbcRepository.tryLockPositionForSell`: `UPDATE ... WHERE status='OPEN' AND
  locked_by_transaction_id IS NULL` — trava a quantidade pedida, sem comparar com a do lote.
- `RunnerContextAssembler.buildOpenLot`: `availableQuantity = quantity − lockedQuantity` (um lock por vez;
  lote travado = `CLOSING`, que o lock já recusa).
- Quem vende pelo pipeline: a estratégia, a proteção do lote (T44), o `PANIC_SELL` (T45) e o
  `FORCE_CLOSE_POSITION` (T49). Os três do core vendem o lote inteiro (`liquidationSignal`).
- `SignalDecision` (`signal.evaluated.total`): `REJECTED_NO_POSITION`, `REJECTED_LOCK`, ...

## Solução proposta

1. **Regra no core (`SellSignalHandler`).** Depois de resolver o lote alvo e antes de persistir:
   - lote de outro runner → descartado como `NO_OPEN_POSITION` (`WARN` com runner, lote e dono);
   - quantidade normalizada maior que a quantidade do lote → novo desfecho `REJECTED_QUANTITY`, nada
     persistido, travado ou enviado (`WARN` com pedido e teto).
   A comparação é contra `Position.quantity`: o lock é exclusivo (lote travado não chega aqui), então
   o disponível de um lote `OPEN` é a quantidade inteira — o mesmo valor que o contexto mostrou.
2. **Métrica.** `SignalDecision.REJECTED_QUANTITY`, registrado como as outras recusas. Não conta no
   `ConsecutiveRejectionGuard` (só rejeição da exchange conta).
3. **Defesa na persistência.** `tryLockPositionForSell` ganha `AND quantity >= :quantity`: mesmo que um
   caminho futuro pule a regra, o lock não trava acima do lote (vira `REJECTED_LOCK`).
4. **Contrato e runbook.** `CONTRACT.md` diz o que acontece com SELL acima do disponível ou de lote
   alheio (recusa, sem ordem). Sem mudança de schema.
5. **Testnet.** A SELL presa do caos (`58968921-…`) e o lote travado são dado de testnet: limpar e
   recomeçar (dado descartável), sem remediação no código.

## Decisão registrada (2026-10-27)

- Sem teste E2E pela métrica: a regra está no `SellSignalHandler` (unitário cobre os casos), o
  mapeamento para `REJECTED_QUANTITY` é um `switch` exaustivo e a trava no SQL tem teste de integração.
  O caminho inteiro é validado na testnet com o fault `OVERSELL` do caos (critério 6).

## Validação na testnet (portfolio `val-t61-t62`, 2026-10-27 16:23–16:41Z, cinco apps)

Caos com `CHAOS_HOLD_POSITION=true` e lote aberto de `0.00049`. O fault `OVERSELL` saiu 4 vezes
(`SHOULD_SELL 0.00098`); as 4 viraram `REJECTED_QUANTITY` com o `WARN` `SELL signal discarded - quantity=0.00098
above lot=... quantity=0.00049000`. Nenhuma transação SELL criada (o runner do caos ficou só com a BUY
`FILLED`), nenhum `ERROR`, sem Safe Mode. Na rodada anterior o mesmo caso foi à exchange, ficou
`SUBMITTED` e levou o portfolio a HALT pelo watchdog.

## Fora do escopo

- Venda parcial do lote: continua permitida (quantidade menor que o lote).
- O BTC vendido a mais na conta da testnet (não pertence a nenhum runner; o ctrade não o rastreia).
- Capital do contexto acima do teto do runner (T62).

## Riscos

- Normalização arredonda a quantidade para o `stepSize`: comparar depois de normalizar, com a mesma
  escala da posição, para um SELL exatamente do lote não ser recusado por arredondamento.
- Saídas do core (proteção, `PANIC_SELL`, `FORCE_CLOSE`) vendem `Position.quantity`; não podem ser
  barradas. Teste de regressão para elas.

## Critérios de aceitação

1. SELL da estratégia acima da quantidade do lote: nenhuma transação, lock ou ordem;
   `signal_evaluated_total{decision="REJECTED_QUANTITY"}` incrementa.
2. SELL com `targetLotId` de lote de outro runner: nenhuma transação, lock ou ordem.
3. SELL igual à quantidade do lote e SELL parcial seguem como hoje.
4. Proteção do lote, `PANIC_SELL` e `FORCE_CLOSE_POSITION` seguem vendendo o lote inteiro.
5. `tryLockPositionForSell` recusa quantidade acima do lote (teste de integração).
6. Testnet: o fault `OVERSELL` do caos com lote aberto vira `REJECTED_QUANTITY`, sem ordem e sem `ERROR`.

## Fontes

- Rodada da T60 de 2026-10-27 (portfolio `soak-t60-5`, runner do caos `b48329af-…`).
- `docs/strategy-service/CONTRACT.md` (teto de SELL por `availableQuantity`).
