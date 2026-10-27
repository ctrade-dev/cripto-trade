# T62 — Capital do Contexto pelo Teto do Runner

**Complexidade:** Baixa
**Responsável:** Claude
**Dependências:** T41 (contrato do strategy service), T50 (limites pré-trade). Origem: rodada das cinco
apps da T60 na testnet (2026-10-27).
**Status:** Planejado — decisão de negócio pendente (item 1 da solução).

---

## Descrição

O contrato diz à estratégia: "para o máximo que o runner pode gastar agora, use
`maxOperationAmount`/`availableCapital`" (`docs/strategy-service/CONTRACT.md`). O ctrade preenche
`maxOperationAmount` com o **saldo disponível do portfolio inteiro**, sem o teto do runner
(`maxAllocationPercent`). A estratégia dimensiona acima do que pode reservar e o ctrade recusa na
reserva (`RUNNER_LIMIT_EXCEEDED` → `REJECTED_CAPITAL`), tick após tick.

Na rodada de 2026-10-27 (portfolio de 1000 USDT, runners com teto de 0,4): ~330 `REJECTED_CAPITAL` em
20 minutos — a tendência pedia 50% de ~860 (430 > 400) a cada tick de sinal; o livro abriu 11 ordens em
214 BUY pedidos. A recusa não conta no `ConsecutiveRejectionGuard` (só exchange conta), então nada para;
a estratégia fica cega para o próprio teto e o log/métrica enche de recusas que não são de risco.

## Contexto técnico

- `RunnerContextAssembler.assemble`: `maxOperationAmount = balance.getAvailableBalance()`, igual a
  `availableCapital`.
- `CapitalReservationPolicy.validateAndReserve`: recusa quando
  `exposicaoEmVoo + valor > maxAllocationPercent × totalBalance`; depois reserva atomicamente do saldo.
- `RunnerExposureService`: `inFlightSummary`/`loadSnapshot` — exposição = soma das ordens **em voo**.
  Lote já preenchido **não** conta na exposição do runner.
- Contrato: `docs/strategy-service/CONTRACT.md` (seção de capital), `openapi.yaml` `StrategyContext`.

## Solução proposta

1. **Teto calculado num lugar só.** `maxOperationAmount = max(0, min(disponível do portfolio,
   maxAllocationPercent × totalBalance − exposição do runner))`, com a mesma conta da reserva
   (`CapitalReservationPolicy` expõe o espaço restante; o assembler e a reserva usam a mesma função —
   um fluxo, um caminho). `availableCapital` segue o saldo do portfolio.
2. **Contrato.** Sem mudança de schema; `CONTRACT.md` e `openapi.yaml` passam a dizer exatamente o que
   `maxOperationAmount` é (teto do runner naquele tick) — hoje a descrição já promete isso.
3. **Apps da T60.** Já dimensionam por `min(maxOperationAmount, availableCapital)`; nenhuma mudança.

## Decisão de negócio pendente

**O que conta na exposição do runner?** Hoje só ordens em voo. Um runner com lote aberto pode comprar de
novo até o teto inteiro, porque o lote preenchido sai da conta (com "novas compras somam ao lote", o lote
cresce sem limite de alocação). Opções:

- **A — manter (só em voo):** a T62 só alinha o contexto à regra atual.
- **B — em voo + custo dos lotes abertos:** o teto passa a limitar o capital do runner de fato. Muda a
  regra de reserva (risco) e o valor que as estratégias recebem.

Recomendação: **B**, porque é o que `maxAllocationPercent` sugere para quem opera. Mas é regra de
risco: precisa do ok do mantenedor (`development-manual.md` §11).

## Fora do escopo

- Venda acima do lote (T61).
- Mudar o significado de `availableCapital` ou `totalCapital`.

## Riscos

- Com a opção B, runners que hoje compram com lote aberto passam a ser recusados: rodada na testnet
  antes de produção.
- O valor do contexto é uma fotografia do tick; a reserva continua sendo a autoridade (corrida com outro
  runner do mesmo portfolio continua possível e é recusada na reserva).

## Critérios de aceitação

1. `maxOperationAmount` nunca passa do espaço restante do teto do runner nem do disponível do portfolio.
2. Estratégia que dimensiona por `maxOperationAmount` não recebe `REJECTED_CAPITAL` por
   `RUNNER_LIMIT_EXCEEDED` sem corrida.
3. Reserva e contexto usam a mesma função (teste cobre os dois pelo mesmo cenário).
4. Rodada das apps da T60: `REJECTED_CAPITAL` só em corrida entre runners.

## Fontes

- Rodada da T60 de 2026-10-27 (portfolio `soak-t60-5`).
