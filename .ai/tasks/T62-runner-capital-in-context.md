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

**Correção (2026-10-27):** a primeira versão desta spec atribuía as ~1.230 `REJECTED_CAPITAL` da
rodada a este problema. Errado: todas foram `RISK_VIOLATION` — o watchdog pôs o portfolio em Safe Mode
`HALT` às 13:30 por causa da SELL presa da T61, e o Safe Mode recusa todo BUY (comportamento correto).
Nenhum `RUNNER_LIMIT_EXCEEDED` foi registrado. O problema desta task vem da leitura do código, ainda não
foi observado na testnet: uma estratégia que dimensione por `maxOperationAmount` com fração alta
(ex.: tendência com 50% de ~860 = 430 > teto de 400) será recusada tick após tick, sem contar no
`ConsecutiveRejectionGuard`.

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
4. Teste reproduz a recusa por `RUNNER_LIMIT_EXCEEDED` com `maxOperationAmount` atual e a ausência dela depois.

## Fontes

- Rodada da T60 de 2026-10-27 (portfolio `soak-t60-5`).
