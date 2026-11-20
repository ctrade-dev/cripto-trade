# T35 — Estratégias de Cenário para Testnet

**Complexidade:** Média
**Responsável:** Claude
**Dependências:** T34 (preço de ordem dirigido pela estratégia) para os cenários de ordem descansando; T32 (`SHOULD_CANCEL`) já concluída
**Status:** Concluído (as estratégias embutidas saíram na T65; hoje só existem como dublês dos testes de integração)

---

## Descrição

Hoje existe uma única estratégia real (`SimpleMovingAverageStrategy`), criada para exercitar o fluxo — mas com baixa efetividade e comportamento não determinístico (depende de cruzamento de média e do book). Para validar os fluxos em **testnet** de forma **repetível**, faltam estratégias que forcem cenários específicos sob demanda.

Esta task adiciona um conjunto de **estratégias de cenário determinísticas** — cada uma mira um ramo distinto do pipeline de sinais, alinhado às tags de `signal.evaluated.total{decision}` (ver `runner-signal-processing.md`). São ferramentas de teste em testnet, **não estratégias de produção**: ficam registradas apenas quando explicitamente habilitadas.

Cenários cobertos:

| # | Estratégia | Ramo exercitado | Outcome (`decision`) |
|---|---|---|---|
| 1 | `RestingBuyCancelStrategy` | BUY que descansa (preço abaixo) → cancela | `BUY` → `CANCEL`; libera **reserva de capital** na conciliação do `CANCELED` |
| 2 | `FilledBuyRestingSellCancelStrategy` | BUY que preenche → SELL que descansa (preço acima) → cancela | fill → `SELL` → `CANCEL`; libera **lock de posição** |
| 3 | `ImmediateRoundTripStrategy` | BUY preenche → SELL preenche → fecha lote | caminho feliz completo: `transaction_match`, PnL (≈0/negativo), fechamento de lote |
| 4 | `OverAllocationRejectStrategy` | BUY acima do capital disponível | `REJECTED_CAPITAL` |

Juntas com a `SimpleMovingAverageStrategy` (que cobre `HOLD` e o BUY/SELL "de alpha"), essas quatro acendem, sob demanda, cada ramo relevante do counter de sinais.

---

## Contexto técnico

### O que a estratégia já enxerga e pode decidir

- `StrategyContextDto` expõe `openLots`, `pendingOrders` (com `type` BUY/SELL, `transactionId`, `status`, `price`), `availableCapital`, `totalCapital`, `minOperationAmount`. Isso basta para as estratégias reagirem ao próprio estado sem infra.
- `StrategyOutputDto.cancel(name, targetTransactionId, reasoning)` já existe (T32). O `CancelSignalHandler` valida ownership + estado cancelável e despacha `OrderDispatchPort.cancel`; a liberação real ocorre na conciliação idempotente do `CANCELED`.
- **Preço da ordem:** vem da T34 (`buyAt`/`sellAt` com `limitPrice`). Cenários 1 e 2 dependem disso; cenário 4 não (só quantidade); cenário 3 usa preço marketable para garantir fill.

### Wiring atual de estratégias

- Estratégias são registradas em `InMemoryStrategyRepository.init()` por instanciação direta (`registerStrategy(new SimpleMovingAverageStrategy())`).
- Um runner referencia a estratégia por `strategyId` (UUID fixo por estratégia), atribuído na criação do runner (`POST` de runner via `PortfolioRunnerController` / `CreateRunnerRequest.strategyId`).
- Cada estratégia declara `getStrategyId()` (UUID fixo), `getStrategyName()`, `getStrategyVersion()`, `isEnabled()`.

### Guardas de execução (não empilhar)

- `RunnerSignalPolicy.canExecuteSignal` + política SINGLE barram novo BUY/SELL quando há posição/ordem aberta ou em trânsito (`REJECTED_POLICY`). `SHOULD_CANCEL` roteia **antes** dessa guarda.
- As estratégias de cenário devem consultar `pendingOrders`/`openLots` e **respeitar** esse estado — só abrem quando não há pendência, e usam o cancelamento para reciclar. Assim o loop de teste é estável e não gera enxurrada de ordens.

---

## Solução proposta

### Estratégias (módulo `strategy/`)

Cada uma como `TradingStrategy` determinística, com um `Config` próprio (offset percentual, quantidade/alocação fixa), no padrão de `SimpleMovingAverageConfig`. Pacote `com.marmitt.strategy.impl.<snake_case>`.

**1. `RestingBuyCancelStrategy`** (`resting_buy_cancel`)
- Se **não** há pending nem lote aberto: emite `buyAt(qty, limitPrice = currentPrice × (1 − offset))` — preço abaixo do mercado ⇒ a BUY descansa sem preencher.
- No tick seguinte, vê a pending BUY em `pendingOrders` (`type = BUY`): emite `cancel(pendingBuy.transactionId())`.
- Repete o ciclo (place → cancel), reciclando a reserva de capital a cada volta.

**2. `FilledBuyRestingSellCancelStrategy`** (`filled_buy_resting_sell_cancel`)
- Se não há lote nem pending: emite `buyAt(qty, limitPrice = currentPrice × (1 + offsetFill))` — preço **acima do ask** (marketable) ⇒ preenche como taker.
- Quando surge lote aberto e não há pending SELL: emite `sellAt(qty, limitPrice = currentPrice × (1 + offset))` — preço **acima do mercado** ⇒ a SELL descansa.
- No tick seguinte, vê a pending SELL (`type = SELL`): emite `cancel(pendingSell.transactionId())`.
- **Escopo:** o lote permanece aberto; a estratégia cicla place-SELL/cancel-SELL sobre ele. Fechar o lote **não** é objetivo deste cenário (é o cenário 3). Documentar no reasoning para não confundir operador.

**3. `ImmediateRoundTripStrategy`** (`immediate_round_trip`)
- Sem lote/pending: emite `buyAt(qty, limitPrice acima do ask)` (marketable) ⇒ preenche.
- Com lote aberto: emite `sellAt(qty, limitPrice ≤ bid)` (marketable) ⇒ preenche e fecha o lote. PnL tende a ~0/negativo (spread + fees).
- Reabre após o fechamento. Exercita `transaction_match`/PnL/liberação de capital a cada ciclo.

**4. `OverAllocationRejectStrategy`** (`over_allocation_reject`)
- A cada tick sem pending, emite BUY (sem `limitPrice` — usa mercado) com `quantity` tal que `quantity × currentPrice > availableCapital` (ex.: `(availableCapital / currentPrice) × fator`, fator > 1).
- A `CapitalReservationPolicy` recusa a reserva ⇒ `REJECTED_CAPITAL`, sem dispatch. Não vira inflight, então não é barrada por SINGLE e reemite no tick seguinte.

### Registro condicional (spring-application)

- Registrar as quatro em `InMemoryStrategyRepository` **somente** sob flag, ex.: propriedade `ctrade.scenario-strategies.enabled=true` (default `false`) — evita qualquer chance de rodarem em produção. Sem a flag, `init()` registra apenas a `SimpleMovingAverageStrategy` como hoje.
- Ativação de um cenário = criar um runner apontando para o `strategyId` da estratégia desejada (fluxo de runner já existente), em ambiente testnet.

### Configuração (offsets)

- Offsets vêm do `Config` de cada estratégia, com defaults sensatos e **dentro da banda do filtro `PERCENT_PRICE`** da Binance (ver Riscos). Preferir percentual sobre `currentPrice`, nunca valor absoluto.

---

## Arquivos a modificar / criar

| Arquivo | Mudança |
|---|---|
| `strategy/.../impl/resting_buy_cancel/RestingBuyCancelStrategy.java` (+ `Config`) | Novo cenário 1 |
| `strategy/.../impl/filled_buy_resting_sell_cancel/FilledBuyRestingSellCancelStrategy.java` (+ `Config`) | Novo cenário 2 |
| `strategy/.../impl/immediate_round_trip/ImmediateRoundTripStrategy.java` (+ `Config`) | Novo cenário 3 |
| `strategy/.../impl/over_allocation_reject/OverAllocationRejectStrategy.java` (+ `Config`) | Novo cenário 4 |
| `spring-application/.../repository/InMemoryStrategyRepository.java` | Registro condicional por flag `ctrade.scenario-strategies.enabled` |
| `spring-application/src/main/resources/application*.yml` | Propriedade da flag (default off) |
| Testes em `strategy/.../` | Cada estratégia: transições de decisão a partir de `StrategyContextDto` sintético (place → cancel; over-alloc; round trip) |

---

## Riscos / pontos de atenção

- **Dependência de T34:** cenários 1, 2 e 3 exigem `limitPrice` no contrato. Sem a T34, apenas o cenário 4 (`REJECTED_CAPITAL`) e um round-trip **a preço de mercado** (fill não garantido) seriam expressáveis — insuficiente para teste determinístico. Entregar T34 antes.
- **Filtro `PERCENT_PRICE`/`PERCENT_PRICE_BY_SIDE` (Binance):** ordens muito distantes do mercado são **rejeitadas** pela exchange. O offset de "descansar" precisa ser grande o bastante para não preencher, mas dentro da banda permitida; idem o offset marketable. Tunar os defaults do `Config` com base nos filtros do símbolo (T6). Um offset fora da banda vira rejeição no dispatch, não ordem descansando.
- **Não empilhar:** cada estratégia deve gatear em `pendingOrders`/`openLots`. Combinado com a política SINGLE do runner, o loop fica estável. Sem esse gate, em testnet acumulam-se ordens rapidamente.
- **Cenário 2 deixa lote aberto:** por design cicla SELL-place/SELL-cancel sem fechar. Deixar isso explícito no reasoning e na doc do cenário para o operador não confundir com vazamento de posição.
- **Fronteira arquitetural:** toda a lógica é decisão determinística em `strategy/`, sem infra, rede ou schema de exchange. O registro condicional e a flag vivem em `spring-application`. Nenhum detalhe de provider entra em `strategy/`.
- **Isolamento de produção:** a flag default `false` garante que os cenários não existam no repositório de estratégias em produção. Reforçar em review que nenhum runner de produção referencie os `strategyId` de cenário.
- **Compatibilidade de contexto defasado:** o cancelamento pode chegar quando a ordem já preencheu; o `CancelSignalHandler` já trata como no-op logado (T32). As estratégias não devem assumir sucesso do cancel.

---

## Critérios de aceitação

1. As quatro estratégias existem em `strategy/`, cada uma determinística e coberta por teste unitário sobre `StrategyContextDto` sintético.
2. `RestingBuyCancelStrategy` alterna place-BUY (preço abaixo) → `SHOULD_CANCEL` da pending BUY, sem empilhar ordens.
3. `FilledBuyRestingSellCancelStrategy` preenche a BUY, coloca SELL descansando (preço acima) e a cancela, sem empilhar.
4. `ImmediateRoundTripStrategy` executa BUY→SELL fechando o lote a cada ciclo, produzindo PnL materializado (≈0/negativo).
5. `OverAllocationRejectStrategy` produz `REJECTED_CAPITAL` de forma repetível, sem dispatch.
6. As estratégias só são registradas quando `ctrade.scenario-strategies.enabled=true`; com a flag off, o repositório é idêntico ao de hoje.
7. Nenhum detalhe de exchange vaza para `strategy/`; nenhuma regra de negócio nova no `spring-application`.
8. Offsets configuráveis por `Config`, com defaults compatíveis com os filtros de símbolo da testnet.

---

## Cenário fora de escopo (nota)

**Boot/recovery de ordem órfã:** reusar a `RestingBuyCancelStrategy` **sem** cancelar (deixar a ordem descansando `PENDING`) e reiniciar a aplicação exercita as fases de sanity check / zombie detection / conciliação de limbo do boot (T7/T31, invariantes `PHASE0`). É um **procedimento operacional** sobre estratégia existente, não uma estratégia nova — documentar no runbook quando as estratégias de cenário estiverem entregues, sem código adicional aqui.

---

## Fontes

- `.ai/flows/runner-signal-processing.md` (outcomes de `signal.evaluated.total`, guardas de execução).
- `.ai/tasks/T32-strategy-cancel-action.md` (contrato `SHOULD_CANCEL`, `CancelSignalHandler`).
- `.ai/tasks/T34-strategy-driven-order-price.md` (fundação de preço).
- `strategy/.../impl/simple_moving_avager/` (padrão de estratégia + `Config`).
- `spring-application/.../repository/InMemoryStrategyRepository.java` (registro de estratégias).
- `docs/PHASE0_INVARIANTS.md` (reserva de capital, lock de SELL, PnL/match).
