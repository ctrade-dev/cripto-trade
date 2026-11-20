# T31 — Unificação do Recovery de Runtime: cobertura de zombies (`PENDING`)

**Complexidade:** Baixa  
**Responsável:** Claude  
**Dependências:** Nenhuma  
**Status:** Concluído (Opção 1 + dedup do boot). Observabilidade dedicada do zombie **adiada**, sem task (ver "Fora de escopo / Adiado").

---

## Descrição

Fecha o furo da reserva órfã (`PENDING` sem `exchangeOrderId`, "zombie") em runtime **sem criar fluxo novo**: estende o recovery de runtime existente para cobrir `PENDING` com **query-before-expire**, e **remove a expiração-cega de zombie do boot** (que duplicava o terminal-fallback do engine).

O motor por-transação (`RecoverTransactionStatusUseCase`) já fazia "consulta-e-concilia" e já aceitava `PENDING`; o único gap era o seletor de lote (`RecoverStaleTransactionsUseCase`), que filtrava só `SUBMITTED`/`PARTIAL`.

---

## O que foi implementado (Opção 1)

### 1. Runtime — `RecoverStaleTransactionsUseCase` passa a cobrir `PENDING`

- O lote seleciona em **dois cutoffs com budgets independentes** (cada um até `maxPerRun`, sem watchdog/use case novos):
  - confirmados `[SUBMITTED, PARTIAL]` pelo `stale-threshold-ms` (30s);
  - `[PENDING]` por uma **carência maior** `pending-grace-ms` (default **10 min**). Isso evita expirar um `PENDING` ainda em **dispatch**: o persist-first comita o `PENDING` antes do `orderDispatch.dispatch(...)`, e o timeout REST é ~30s — reusar 30s para `PENDING` poderia expirar uma ordem que ainda vai ser enviada/confirmada. *(Corrige Codex P1: "keep a dispatch grace period".)*
  - Budget **separado** é proposital: um confirmado faltante vai para DLQ mas **permanece** no status e é re-selecionado a cada ciclo; com budget compartilhado, um backlog de confirmados starvaria a limpeza de `PENDING` e o capital ficaria preso. *(Corrige Codex P2: "preserve a pending recovery budget".)*
- O **release de margem é type-aware**: `MarginReleaseBuilder` retorna vazio para SELL (SELL não reserva quote — só bloqueia uma `Position`). Assim boot **e** runtime saneiam `PENDING` SELL órfã com segurança (query-verify → terminal → unlock da `Position` no `TerminationHandler`, **sem** liberar quote de BUYs alheios — `GlobalBalance.release` subtrai cego do reserved). Corrige centralmente boot, runtime e conciliação normal. *(Corrige Codex P1 "avoid expiring PENDING SELLs with margin release", boot + runtime.)*
- Todos passam `forRuntimeWatchdog` = `MissingOrderPolicy.DERIVE_FROM_STATUS`; a política de not-found é **resolvida dentro do engine** (`RecoverTransactionStatusUseCase`):
  - `PENDING` (nunca confirmado) → terminal fallback (`EXPIRED`/`CANCELED`, **sem DLQ**);
  - `SUBMITTED`/`PARTIAL` (confirmado) → `REGISTER_DLQ`.
- O engine **relê a entidade atual** (status **e** `exchangeOrderId`) imediatamente antes do fallback (não usa o objeto pré-query). Se um evento USER_DATA promoveu `PENDING → SUBMITTED/PARTIAL` durante a query (que pode levar até 30s), o not-found vira **DLQ** com a identidade fresca (incluindo o `exchangeOrderId` real, evitando DLQ duplicada num ciclo posterior), nunca expiração; se já virou terminal, é no-op. *(Corrige Codex P1 "recheck status before derived fallback" + P2 "pass the reloaded transaction to DLQ".)*

### 2. Boot — `RunnerBootRecoveryUseCase` deixa de tratar zombie separadamente

- Removido o **Step 3 (`step3ExpireZombies`)** e a classificação zombie/limbo do **Step 1**.
- `PENDING` agora entra no **mesmo caminho de reconciliação** do antigo limbo (Step 4), via `RecoverTransactionStatusUseCase.forBoot` → **query-before-expire** também no boot (não há mais expiração local cega).
- **Carência de reconciliação para PENDING no boot** (`runner.boot.phase3.pending-grace-ms`, default 10 min): um `PENDING` mais novo que a carência é **deferido** (mantido PENDING, fora do Step 4) em vez de consultado/expirado — evita expirar uma ordem enviada logo antes do crash mas ainda não visível na query da exchange; o watchdog de runtime o trata depois. `SUBMITTED`/`PARTIAL` (confirmados) são sempre reconciliados, sem carência. Carência **`0`** significa "sem carência" → todos os `PENDING` reconciliam (cutoff `Instant.MAX`), não o contrário. *(Corrige Codex P1 "preserve boot grace before expiring PENDING orders" + P2 "do not defer all PENDING when grace is zero".)*
- Código removido por ser usado **apenas** pelo zombie:
  - método `buildSyntheticTerminalOrder` (duplicava o `applyTerminalFallback` do engine);
  - campo/param `pendingWithoutExchangeOrderIdTtlMs` (vinha de `PortfolioReservationTtlProperties.getTtlMs()`);
  - campo/param/import `ConciliationOrderUpdateExecutor conciliationOrderUpdate` (só o Step 3 usava);
  - bucket `zombies` em `RecoveryContext` e `zombiesCount` em `RecoverySummary`.

### 3. Boot — remoção do phase2 `reservation_ttl` (segunda expiração-cega)

Achado no review (Codex P1): o phase3 não era a única expiração-cega do boot. O `PortfolioReservationTtlUseCase` (phase2) rodava **antes** do phase3 e expirava `PENDING` sem `exchangeOrderId` por TTL **sem consultar a exchange** — o mesmo `buildSyntheticExpired` duplicado. Com defaults de produção (`reservation-ttl.enabled: true`, `ttl-ms: 300000`), uma ordem viva com ACK perdido era expirada antes do query-before-expire do phase3.

Como o phase3 agora **query-verifica todo `PENDING` inflight** no boot e o watchdog cobre runtime, a fase ficou redundante **e** nociva → **removida por inteiro**:

- Core: deletados `PortfolioReservationTtlUseCase`, `PortfolioReservationTtlResult`, `PortfolioReservationTtlStatus`; removidos a fase `phase2.reservation_ttl` e o método `runPhase2ReservationTtl` de `RunBootSequenceUseCase`; removidos os campos `ttlEnabled`/`ttlMs` de `BootExecutionCommand`.
- Spring: removidos o bean `portfolioReservationTtlUseCase` (`BootConfig`), o campo `portfolioReservationTtlProperties` + os args de TTL no `BootOrchestrator`; deletada `PortfolioReservationTtlProperties`; removido o bloco `reservation-ttl` do `application.yml`.

---

## Comportamento resultante

| Origem | Caminho (boot **e** runtime) | Exchange conhece? | Desfecho | DLQ? |
|---|---|---|---|---|
| `PENDING` (zombie) | query por `clientOrderId` | **sim** (ACK perdido, viva) | reconcilia `PENDING→SUBMITTED/FILLED`; capital **não** liberado | não |
| `PENDING` (zombie) | query por `clientOrderId` | **não** (nunca enviada) | `EXPIRED` via terminal fallback; capital liberado | **não** |
| `SUBMITTED`/`PARTIAL` | query por `clientOrderId` | não | runtime: **DLQ** · boot: terminal fallback | runtime sim |

Boot e runtime compartilham o **mesmo motor** (`RecoverTransactionStatusUseCase`); diferem só na política de not-found (boot sempre terminal-fallback; runtime DLQ para ordem confirmada).

---

## Arquivos alterados

| Arquivo | Mudança |
|---|---|
| `core/.../dto/runner/request/RecoverTransactionStatusRequest.java` | `forRuntimeWatchdog` → `DERIVE_FROM_STATUS`; novo valor de enum `DERIVE_FROM_STATUS` |
| `core/.../usecase/runner/RecoverTransactionStatusUseCase.java` | not-found `DERIVE_FROM_STATUS` **relê a entidade** atual antes do fallback (PENDING→fallback; confirmado→DLQ com identidade fresca; terminal→no-op) |
| `core/.../usecase/runner/RecoverStaleTransactionsUseCase.java` | `PENDING` elegível; **dois cutoffs com budgets independentes** (confirmados vs PENDING) |
| `core/.../orderconciliation/MarginReleaseBuilder.java` | **type-aware**: SELL → sem release de quote (corrige boot+runtime+conciliação normal) |
| `core/.../orderconciliation/TerminationHandler.java` | javadoc: SELL só desbloqueia Position, não libera margem |
| `core/.../dto/runner/request/RecoverStaleTransactionsRequest.java` | + `pendingUpdatedBefore` |
| `spring-application/.../bootstrap/RunnerTransactionRecoveryWatchdog.java` | calcula `pendingUpdatedBefore` (carência maior) |
| `spring-application/.../bootstrap/RunnerTransactionRecoveryProperties.java` (+`application.yml`) | + `pending-grace-ms` (default 600000) |
| `core/.../usecase/runner/RunnerBootRecoveryUseCase.java` | Remoção do Step 3 e da trilha de zombie; `PENDING` reconciliado junto do limbo; **defere PENDING jovem** (carência de boot) |
| `spring-application/.../bootstrap/RunnerBootPhase3Properties.java` (+`application.yml` phase3, +`RunnerConfig`) | + `pending-grace-ms` (default 600000) para o boot |
| `core/.../dto/runner/RecoveryContext.java` | Removido bucket `zombies` |
| `core/.../usecase/boot/RunBootSequenceUseCase.java` | Log sem `zombies={}`; **removida a fase `phase2.reservation_ttl`** |
| `core/.../dto/boot/BootExecutionCommand.java` | Removidos `ttlEnabled`/`ttlMs` |
| `core/.../usecase/boot/phase2/PortfolioReservationTtlUseCase.java` (+Result/+Status) | **Deletados** |
| `spring-application/.../config/core/RunnerConfig.java` | Boot bean sem `reservationTtlProperties` nem `conciliationOrderUpdateExecutor` |
| `spring-application/.../config/core/BootConfig.java` | Removido bean `portfolioReservationTtlUseCase` |
| `spring-application/.../bootstrap/BootOrchestrator.java` | Removido `portfolioReservationTtlProperties` + args de TTL |
| `spring-application/.../bootstrap/PortfolioReservationTtlProperties.java` | **Deletada** |
| `spring-application/src/main/resources/application.yml` | Removido bloco `reservation-ttl` |

### Testes

| Arquivo | Mudança |
|---|---|
| `RecoverTransactionStatusUseCaseTest` | + `executeMarksPendingTransactionExpiredWhenExchangeDoesNotFindOrderInRuntimeMode`; + `executeRoutesToDlqWhenPendingGetsConfirmedDuringQueryInRuntimeMode` (guarda da corrida; assert DLQ com `exchangeOrderId` recarregado) |
| `MarginReleaseBuilderTest` (novo) | SELL EXPIRED/CANCELED → sem release; BUY EXPIRED → release total (regressão) |
| `RunnerBootRecoverySelectionTest` (novo) | carência de PENDING no boot: jovem deferido; velho reconcilia; **grace=0 reconcilia todos**; SUBMITTED sempre |
| `RunnerTransactionRecoveryWatchdogIntegrationTest` | + `...NotFoundOnExchangeWithoutDlq`, `...FoundAliveOnExchange`, `...NotExpirePendingWithinDispatchGrace`, `...NotLetConfirmedBacklogStarvePendingCleanup`, `...ExpirePendingSellWithoutReleasingUnrelatedReservedCapital`; stub de corrida ajustado para o lote em dois cutoffs |
| `RunnerBootRecoveryIntegrationTest` | `recoveryShouldExpireZombie...` → `...QueryVerifyAndExpirePendingBuyNotFoundOnExchange`; + `...ReconcilePendingBuyFoundAliveOnExchangeAndKeepReserve`, + `...DeferYoungPendingWithoutExpiringIt`; removidos asserts de `zombiesCount` e a property `reservation-ttl` |
| `BootOrchestrator{Observability,DlqOperational,BootMinimum}Test` | Removido o mock `PortfolioReservationTtlUseCase` e a property `ttlProperties` da composição |

Validação: `:core:test` e `:spring-application:test` (suíte completa) — todos verdes.

---

## Fora de escopo / Adiado

- **Observabilidade dedicada do zombie** (contador `reconciled_live` vs `expired_orphan`, alerta ativo do caso "viva", gancho com T21/T25): **não implementada nesta entrega**. Hoje os desfechos aparecem nos contadores genéricos do recovery (`recovered`/`routedToDlq`) e nos logs. Fica como follow-up.
- **Cancelar ordem viva por idade/TTL global** — anula estratégia; é da **T32** (opt-in por runner, default off).
- **Verbo `OrderDispatchPort.cancel`** — **T33**.
- **Carência de TTL para `PENDING` recém-criado** — removida; runtime/boot consultam a exchange antes de expirar (mais correto que a expiração cega). O cutoff do watchdog continua dando folga para ACK atrasado antes da seleção.

---

## Riscos / pontos de atenção

- **Política por status é invariante de segurança:** `PENDING`→terminal fallback, `SUBMITTED`/`PARTIAL`→DLQ no runtime. Inverter geraria falso conflito (zombie nunca-enviado na DLQ) ou perda de sinal (ordem confirmada sumindo sem DLQ).
- **Boot passou a exigir capacidade de order query também quando só há `PENDING`** (antes o zombie expirava local sem query). Em exchange sem order query, `PENDING` inflight leva ao mesmo halt que o limbo — consistente com "não expirar sem verificar".
- **Corrida query × fill tardio:** mitigada em três camadas — (1) carência generosa de `PENDING` (10 min) torna improvável um fill após tanto silêncio; (2) re-leitura do status no engine antes do fallback; (3) a mutação final passa pela conciliação idempotente sob *striped lock* por `clientOrderId`. **Resíduo aceito (Codex P2 "make fallback status check atomic"):** o re-read (2) é fora do lock (3), então uma promoção `PENDING→SUBMITTED` na janela ~ms entre re-read e aquisição do lock ainda poderia expirar ordem confirmada. Exige a exchange responder *not-found* **e** entregar um fill no mesmo instante (contraditório), após 10 min de silêncio — praticamente impossível. Decisão: **aceitar e documentar**; o caminho 100% atômico (terminação condicional dentro do lock) fica como hardening futuro.
- **Liberação de capital:** sempre via conciliação, nunca direta.

---

## Critérios de aceitação

1. ✅ Um `PENDING` (zombie) velho é **consultado por `clientOrderId`** antes de expirar, em runtime, pelo mesmo recovery (sem use case paralelo).
2. ✅ `PENDING` viva (caso 3) é **reconciliada** (`SUBMITTED`); capital **não** liberado.
3. ✅ `PENDING` não encontrada vira **`EXPIRED`** via conciliação — **sem** DLQ.
4. ✅ `SUBMITTED`/`PARTIAL` not-found continua indo para **DLQ** no runtime (sem regressão).
5. ✅ Não existe `RuntimeZombieCleanupUseCase`/watchdog/properties dedicados; cobertura de zombie é extensão do recovery.
6. ✅ Boot não tem mais **nenhuma** expiração-cega: phase3 sem Step 3 **e** phase2 `reservation_ttl` removido; `PENDING` tratado só por query-before-expire (phase3) + watchdog de runtime. No boot, `PENDING` **jovem** (< `pending-grace-ms`) é **deferido**, nunca expirado prematuramente.
7. ✅ A política de not-found do runtime é resolvida no engine pelo status **relido imediatamente antes do fallback** (`DERIVE_FROM_STATUS`); promoção concorrente `PENDING→SUBMITTED/PARTIAL` durante a query vira DLQ, não expiração.
8. ✅ `PENDING` só é elegível no watchdog após `pending-grace-ms` (default 10 min), evitando expirar ordem ainda em dispatch; tem **budget independente** do backlog de confirmados; e o **release de margem é type-aware** (SELL terminada só desbloqueia a Position, não libera quote — boot, runtime e conciliação normal).
9. ⏳ **Adiado** — métrica distinguindo `reconciled_live` de `expired_orphan` com alerta ativo.
10. ✅ Boot, recovery (`SUBMITTED`/`PARTIAL`) e reconciliação existentes seguem sem regressão (suíte completa verde).
