# T55 — Operabilidade por IA no Resto do Sistema

**Complexidade:** Alta
**Responsável:** Claude
**Dependências:** T54 (primeira API no padrão), T27 (envelope `ApiError`), T51 (estado operacional como
métrica). Base do monitoramento periódico por IA da T53.
**Status:** Concluído (2026-10-20) — Partes A, B, C, C2 e D entregues.

---

## Descrição

O ctrade será operado por um agente de IA (`.ai/product-direction.md`). A T54 entregou o catálogo de
estratégias no padrão definido lá: estado autoexplicativo, `code` estável nas recusas, idempotência,
`reason` com histórico, OpenAPI e runbook executável. O resto da API veio antes desse padrão. Esta task
leva o sistema inteiro ao mesmo padrão e cria o que o agente mais precisa para operar: **uma consulta
única que diz se o sistema está consistente e, se não estiver, o quê, onde e o que fazer**.

## Achado de segurança (prioritário)

Só `/api/admin/**` e `/api/exchanges/**` exigem `X-Admin-Key` (`AdminWebMvcConfig`). Ficaram abertos
endpoints que **mudam estado**:

| Endpoint | Efeito |
|---|---|
| `POST /api/websocket/exchange/orders/create` | **Envia ordem direto à exchange**, sem transação local, sem reserva de capital, sem conciliação nem proteções. Gera `binanceOnly` na reconciliação e pode gastar saldo real. |
| `POST /api/websocket/exchange/orders/cancel` | Cancela ordem direto na exchange, fora do core. |
| `POST /api/websocket/connect`, `/disconnect`, `.../market-data/*`, `.../order-notifications/*` | Liga/desliga conexões e assinaturas que o core usa. |
| `DELETE /api/listeners/clear` | Remove todos os listeners: o core para de receber market data e atualizações de ordem. |
| `POST /api/dead-letters/{id}/resolve` e `/reprocess` | Resolve ou reaplica efeito financeiro de entrada da DLQ. |
| `POST /api/portfolios`, `POST /api/portfolios/{id}/runners` | Cria portfolio e runner. |

As ordens diretas contrariam a regra de que toda ordem nasce de uma transação do core
(`PHASE0_INVARIANTS`).

**Os endpoints de conexão e assinatura são, hoje, o único jeito de começar a operar**: o boot não abre
conexões nem assina market data; só a estratégia de reconexão religa uma conexão que caiu. Depois de um
restart, sem `connect`/`subscribe`, os runners ficam sem tick. Por isso eles ficam (protegidos), e a
abertura automática vira task própria.

## Escopo proposto

### Parte A — Superfície da API

1. **Um perímetro só**: toda rota da API (leitura e escrita) exige `X-Admin-Key`.
2. **Remover o que contorna o core**: `POST /api/websocket/exchange/orders/create` e `/orders/cancel`
   (ordem e cancelamento direto na exchange) e `DELETE /api/listeners/clear`.
3. **Manter o controle de conexão e assinatura** (`/api/websocket/...`), protegido; na Parte B ganha
   `reason` e entra no histórico.

### Parte B — Padrão de resposta e erro

1. **`code` estável em toda recusa**, por exceção do core (hoje: `RunnerNotFound`, `RunnerOperation`,
   `SafeModeTransition`, `DeadLetterNotFound`, `DeadLetterConflict`, `PortfolioNotFound`,
   `ConcurrentPositionLock`, `ExchangeQuery`, `UnsupportedCapability`, validação de entrada) e para os
   erros genéricos (`VALIDATION_FAILED`, `MALFORMED_BODY`, `INTERNAL_ERROR`). Lista fechada publicada
   no OpenAPI.
2. **Estado resultante** em toda mudança (hoje vários comandos devolvem só a transição ou `202`).
3. **Idempotência** nos comandos do operador: repetir halt, resume, terminate, cancelar ordem já
   cancelada, resolver DLQ já resolvida etc. devolve o estado atual sem erro nem efeito.
4. **`reason` obrigatório e histórico** nos comandos do operador que ainda não têm: halt, resume,
   terminate, limites do runner, cancelar ordem, fechar posição, resolver/reprocessar DLQ, criar
   portfolio/runner (o Safe Mode já exige). Histórico consultável num log único de ações do operador
   (decisão 4).
5. **OpenAPI completo**: descrição de campos, estados e códigos em todos os controllers.

### Parte C — Estado consolidado e inconsistências

`GET /api/admin/operational-state` → visão única para o agente verificar o sistema:

```json
{
  "status": "OK | WARN | CRITICAL",
  "generatedAt": "...",
  "checks": [
    {"id": "CAPITAL_RESERVED_WITHOUT_ORDER", "status": "CRITICAL", "summary": "...",
     "subjects": [{"portfolioId": "...", "runnerId": "...", "amount": "..."}],
     "since": "...", "action": "OPERATIONS_RUNBOOK#..."}
  ]
}
```

Cada verificação tem `id` estável, severidade, os sujeitos afetados e a seção do runbook a seguir.
Verificações propostas:

| id | O que detecta | Fonte |
|---|---|---|
| `SAFE_MODE_ACTIVE` | Portfolio fora de `NORMAL` (nível, origem, desde quando) | Portfolio (T45/T25/T43) |
| `CAPITAL_RESERVED_WITHOUT_ORDER` | `reserved > 0` sem ordem BUY em voo que o explique | Saldos × transações |
| `CAPITAL_RESERVATION_STALE` | Reserva mais velha que o limiar | T51 |
| `TRANSACTION_STUCK` | `PENDING`/`SUBMITTED`/`PARTIAL` além do limiar do watchdog | Transações |
| `POSITION_LOCK_ORPHAN` | Lote travado por transação já terminal | Posições × transações |
| `DLQ_UNRESOLVED` | Entradas não resolvidas | DLQ |
| `RUNNER_HALTED` / `RUNNER_RECONCILING` / `RUNNER_STUCK_TERMINATING` | Runners fora de operação | Runners (T49/T25) |
| `RUNNER_ON_HOLD_STRATEGY_UNUSABLE` | Runner `ACTIVE` cuja estratégia não está utilizável | Runners × catálogo (T54) |
| `STRATEGY_VERIFICATION_FAILED` | Estratégia habilitada sem passar na conferência | Catálogo (T54) |
| `STRATEGY_UNAVAILABLE` | Runner `ACTIVE` cuja estratégia só falha há mais de 60s (T53 F21) | Chamadas às apps (decorator de métricas) |
| `MARKET_DATA_STALE` | Sem tick recente para símbolo com runner ativo | Último preço / tick |
| `STREAM_DISCONNECTED` | Stream de market data ou de usuário desconectado com runner ativo | Conexões websocket |
| `EXCHANGE_REST_CIRCUIT_OPEN` | Circuit breaker das consultas REST aberto | T24 |
| `BOOT_NOT_READY` | Boot não concluído ou com falha | Boot |
| `EXCHANGE_BALANCE_DIVERGENCE` | Saldo local ≠ saldo na exchange | T29, **sob demanda** (decisão 3) |
| `EXCHANGE_TRADE_DIVERGENCE` | `binanceOnly`/`localOnly` na janela | T18, **sob demanda** (decisão 3) |

Também exposto como métrica (`operational_state_status` e por `id`) para alerta no Grafana.

### Parte D — Runbook executável

`docs/OPERATIONS_RUNBOOK.md` reescrito como procedimentos para agente: para cada `id` de verificação e
cada alerta, a consulta de diagnóstico, a ação com o corpo exato, a resposta esperada e como confirmar
que resolveu. As seções atuais viram a fonte; o formato muda.

## Decisões registradas (2026-10-20)

1. **Superfície:** remover ordem/cancelamento direto na exchange e `DELETE /api/listeners/clear`;
   manter conexão e assinatura de websocket (são o único jeito de começar a operar hoje), protegidas;
   toda a API, inclusive leituras, atrás de `X-Admin-Key`.
2. **Limiares:** DLQ ≥ 1 `WARN`, ≥ 3 `CRITICAL`; reserva sem ordem `CRITICAL`; transação presa `WARN`
   acima do limiar do watchdog e `CRITICAL` acima de 3 ciclos; Safe Mode `HALT` `WARN`,
   `CANCEL_ALL`/`PANIC_SELL` `CRITICAL`; sem tick há mais de 60s com runner ativo `CRITICAL`; stream
   desconectado com runner ativo `CRITICAL`.
3. **Comparação com a exchange** (registro local × Binance: saldo pela T29, trades pela T18): só sob
   demanda (`?includeExchange=true`), porque consome cota da API da Binance.
4. **Histórico:** log único `operator_actions` (quem, ação, alvo, `reason`, resultado, quando),
   consultável por API — responde "o que foi feito e por quê" numa consulta.
5. **`reason` obrigatório** nos comandos existentes já nesta task.
6. **Entrega:** um PR por parte, começando pela A.

## Follow-up registrado

- **Abertura automática de conexões:** o ctrade abrir e manter as conexões/assinaturas que os runners
  ativos precisam (inclusive após restart), sem depender de chamada manual. Task própria depois da T55.

## Riscos

- Mudança de contrato da API (auth, `reason`, códigos): qualquer script existente que chame a API quebra.
  Hoje não há consumidor além dos testes.
- Verificações caras no caminho da consulta: a Parte C precisa de consultas agregadas no banco, sem
  varrer tabelas inteiras a cada chamada.
- Falso positivo em verificação de consistência gera ação errada do agente: cada verificação precisa de
  teste com o caso positivo e o negativo.

## Critérios de aceitação (preliminares)

1. Nenhuma rota que muda estado responde sem `X-Admin-Key`; nenhuma rota envia ordem fora do core.
2. Toda recusa da API traz `code` da lista publicada no OpenAPI.
3. Repetir qualquer comando do operador já aplicado não falha nem gera efeito ou registro novo.
4. Todo comando do operador exige `reason` e aparece no histórico.
5. `GET /api/admin/operational-state` aponta cada inconsistência listada com sujeitos e ação, e devolve
   `OK` num sistema consistente (teste com Postgres para cada verificação, positivo e negativo).
6. Runbook cobre cada `id` de verificação com diagnóstico, ação, resposta esperada e confirmação.

## Fontes

- `.ai/product-direction.md` (princípios de operabilidade por IA)
- `.ai/tasks/T54-dynamic-strategy-catalog.md` (padrão de referência)
- `docs/OPERATIONS_RUNBOOK.md`, `docs/PHASE0_INVARIANTS.md`
- `spring-application/.../config/AdminWebMvcConfig.java`, `.../controller/*`

## Entrega

### Parte A — Superfície da API (2026-10-20)

- `AdminWebMvcConfig`: `X-Admin-Key` em `/api/**` (leitura e escrita). `/actuator` fica fora. O `401`
  traz `code=ADMIN_KEY_INVALID` (desde a T54).
- Removidos (sem consumidor além da própria rota): `POST /api/websocket/exchange/orders/create` e
  `/orders/cancel` com `OrderManagementService`, `OrderManagementMapper`, `OrderCreateRequest`,
  `OrderCancelRequest`, `OrderManagementResponse`; `DELETE /api/listeners/clear` com
  `clearAllListeners` no port de entrada, no use case, no port de saída e no repositório.
- Mantidos e protegidos: conexão e assinatura de websocket (único jeito de começar a operar).
- **Bug encontrado e corrigido:** `GET /api/dead-letters` sem filtro respondia `500` no Postgres (`? IS NULL`
  com parâmetro nulo sem tipo). `DeadLetterEntryJdbcRepository.findUnresolved` passou a usar
  `CAST(:param AS UUID)`. O operador não conseguia listar a DLQ inteira.
- Validação: `CapitalDeadLetterReplayIntegrationTest` passa a mandar a chave e prova que leitura sem
  chave é `401 ADMIN_KEY_INVALID` e que as rotas removidas respondem `404`; suíte completa verde.
- Docs: `README.md` (perímetro, sem ordem direta) e `OPERATIONS_RUNBOOK.md` (seção "Acesso à API").

### Parte B — Padrão de resposta e erro (2026-10-20)

- **Um mecanismo para todo comando:** cabeçalhos `X-Reason` (obrigatório) e `X-Operator` (opcional).
  `OperatorCommandInterceptor` (depois da chave) recusa escrita sem `X-Reason` com
  `400 REASON_REQUIRED`, sem executar, e grava todo comando em `operator_actions`
  (`V18__create_operator_actions.sql`): ator, método, caminho, operação (controller.método), motivo,
  status HTTP, `errorCode`, duração. Consulta: `GET /api/admin/operator-actions`. O catálogo (T54) e o
  Safe Mode passaram o motivo do corpo para o cabeçalho; DLQ usa `X-Operator`/`X-Reason` no lugar de
  `resolvedBy`/`requestedBy`/`resolutionNote` (DTOs de request removidos).
- **Códigos:** `CodedError` no core, implementado pelas exceções de negócio; `RunnerOperationException` e
  `SafeModeTransitionException` com código por causa (`RUNNER_RECONCILIATION_INCOMPLETE`,
  `EXCHANGE_UNAVAILABLE`, `ORDER_NOT_FOUND`, `POSITION_NOT_FOUND`, `POSITION_LOCKED`,
  `RUNNER_NOT_ACCEPTING_SIGNALS`, `NO_MARKET_PRICE`, `CONFIRMATION_REQUIRED`, `SAFE_MODE_STEP_TOO_LARGE`…).
  `GlobalExceptionHandler` põe `code` em toda resposta de erro, inclusive as genéricas
  (`INVALID_REQUEST`, `MALFORMED_BODY`, `RESOURCE_NOT_FOUND`, `METHOD_NOT_ALLOWED`, `INTERNAL_ERROR`…).
- **Idempotência no core:** `start`/`resume` em runner `ACTIVE`, `terminate` em `TERMINATING`/`ARCHIVED`,
  `halt` em `HALTED` (já era), cancelar ordem já terminal, fechar posição já fechada ou já em venda
  MARKET, resolver/reprocessar DLQ já resolvida e Safe Mode no mesmo nível (já era) devolvem o estado
  atual sem efeito. O log registra a tentativa repetida como mais uma ação, com o status da resposta.
- **OpenAPI:** `OpenApiConfig` publica a chave como esquema de segurança, a lista de códigos e os
  cabeçalhos `X-Reason`/`X-Operator` em todo comando.
- **Fora desta parte:** comandos que ainda respondem `202` sem estado (cancelar ordem, fechar posição,
  conexão websocket) seguem assim; o estado resultante deles fica visível pelo estado consolidado da
  Parte C. Criação de portfolio/runner não é idempotente (não há chave natural no pedido).
- Validação: testes do core para idempotência e códigos; `CapitalDeadLetterReplayIntegrationTest`
  prova `REASON_REQUIRED`, o registro de comando recusado e aceito (ator, motivo, operação, status,
  código); suíte completa verde.

### Parte C — Estado consolidado (2026-10-20)

- `GET /api/admin/operational-state` (`InspectOperationalStateUseCase`, core): 15 verificações, todas
  listadas (inclusive `OK`), com `id`, severidade, `subjects`, `since` e `runbook`; status geral = pior
  severidade. Exportado como `operational_state_status` e `operational_check_status{check}` pelo
  `OperationalStateScheduler` (a cada 15s).
- Sinais técnicos pelo port `InfrastructureSignalsPort` (boot e circuit breakers REST), implementado no
  Spring; `LatestPricePort` ganhou `findUpdatedAt` para o tick por símbolo. Limiares em
  `ctrade.observability` (`market-data-stale-ms` 60s, `reservation-stale-ms` 24h) e na configuração do
  watchdog e do encerramento.
- **Decisões de implementação:**
  - `TRANSACTION_STUCK` só para `PENDING` (sem confirmação da exchange): ordem `SUBMITTED`/`PARTIAL`
    descansando no livro é normal e geraria falso alarme. Ordem aberta há muito tempo aparece em
    `CAPITAL_RESERVATION_STALE` (BUY, WARN após 24h).
  - Market data é verificado pela idade do último tick; a conexão de usuário só é exigida quando a
    exchange tem stream de usuário (`ExchangeAdapterDescriptor.hasUserStream`), para o MOCK não acusar.
  - `CAPITAL_RESERVED_WITHOUT_ORDER` só dispara com `reserved > 0` e nenhuma BUY em voo no portfolio
    (comparar valores exatos daria falso positivo com fills parciais).
- **Parte C2 (pendente):** `EXCHANGE_BALANCE_DIVERGENCE` e `EXCHANGE_TRADE_DIVERGENCE`, sob demanda. A
  comparação de saldo hoje só existe dentro do boot e a de trades só sobe com Binance configurada; ficam
  para quando forem extraídas para reuso, sem reimplementar.
- Validação: `InspectOperationalStateUseCaseTest` (caso que dispara e que não dispara por verificação),
  `OperationalStateIntegrationTest` (Postgres: endpoint e métrica); suíte completa verde.

### Parte D — Runbook executável (2026-10-20)

- `docs/OPERATIONS_RUNBOOK.md` reescrito para o agente operador: convenções da API (chave, `X-Reason`,
  `code`, idempotência, log de ações), ciclo de verificação a partir de `operational-state`, partida
  depois de um boot (abrir conexões e assinaturas), um procedimento por verificação com âncora estável
  (`#dlq-unresolved` etc., o mesmo `runbook` que a verificação devolve) — diagnóstico, ação com o comando
  exato, resposta esperada e confirmação —, mapa de alertas Discord/Grafana para as seções e a referência
  dos mecanismos (conteúdo anterior, condensado).
- **Limites de autonomia** (o que o agente faz sozinho × o que pede ao mantenedor): proposta confirmada
  pelo mantenedor em 2026-10-20.

### Parte C2 — Comparação com a exchange (2026-10-20)

- `GET /api/admin/operational-state?includeExchange=true&exchangeWindowHours=24` acrescenta
  `EXCHANGE_BALANCE_DIVERGENCE` e `EXCHANGE_TRADE_DIVERGENCE`. Sem o parâmetro (e no ciclo das métricas)
  nada consulta a exchange.
- **Sem reimplementar:** o saldo usa a mesma `PortfolioBootSanityUseCase` da sanidade do boot (mesmo
  limiar `runner.boot.phase2.portfolio.sanity-check.threshold`); os trades usam o `ReconcileTradesPort`
  da T18, que só existe com Binance configurada — sem ele, `NOT_CHECKED` (OK), não alarme.
- Severidades: saldo local maior que a exchange CRITICAL, menor WARN, consulta que falhou WARN; fill só na
  exchange, só local ou divergente CRITICAL; janela de 1 a 168h.
- `InspectOperationalStatePort.inspect(ExchangeComparison)`; `inspect()` sem comparação continua como
  atalho.
- Validação: testes do core (só roda quando pedido; deficit × surplus; fill faltando; sem histórico de
  trades) e `OperationalStateIntegrationTest` (17 verificações com o parâmetro; janela inválida
  `INVALID_REQUEST`). Runbook: procedimentos `#exchange-balance-divergence` e `#exchange-trade-divergence`.

