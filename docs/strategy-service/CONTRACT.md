# Strategy Service Contract

Contrato de rede para estratégias executadas **fora do processo** do ctrade. Cada versão de
estratégia sobe como uma app independente (Java, Node, Python, …) que implementa este contrato.
O ctrade é o cliente; a app da estratégia é o servidor.

- **Fonte de verdade da forma:** [`openapi.yaml`](./openapi.yaml) (usado para codegen/validação nos três lados).
- **Fonte de verdade da semântica:** este documento.
- **Espelho interno em Java:** `core/src/main/java/com/marmitt/core/dto/strategy/*`. Se divergir do
  spec, o spec prevalece **na fronteira de rede** — o core continua dono do contrato de domínio.

> Status: **proposta de contrato** para o desacoplamento out-of-process das estratégias (Fase 2 do
> roadmap de decoupling). Ainda não há adapter consumindo isto; ver "Onde isto se encaixa".
>
> **Revisão 1.1 (T40, 2026-10-19):** estado de warm-up chaveado por `(strategyRef, runnerId)`;
> `tickId`/`sequence` no request com deduplicação e **sem retry** do ctrade; descarte por idade da
> decisão do lado do ctrade; campos de PnL do contexto marcados a mercado (T43) e `totalCapital`
> documentado. Como não há consumidor, a revisão fica em `/v1`.

---

## Por que este contrato existe

Hoje as estratégias são classes Java linkadas no classpath e registradas no boot
(`InMemoryStrategyRepository`), então propor/melhorar uma estratégia exige recompilar e reiniciar a
aplicação. Empurrando a execução para trás de um contrato de rede, a linguagem atrás do endpoint
deixa de importar: o core permanece Java puro e o ecossistema de estratégias pode ser poliglota e de
iteração rápida — pré-condição para um ciclo autônomo de melhoria (IA propõe → valida → promove) sem
reiniciar o ctrade.

---

## Endpoints

| Método | Caminho | Papel |
|---|---|---|
| `POST` | `/v1/evaluate` | Uma decisão pura por tick. É a chamada quente. |
| `GET`  | `/v1/health`   | Liveness + **readiness agregada** (todas as versões prontas). |
| `GET`  | `/v1/health/{strategyRef}` | **Readiness escopada** de uma versão; ecoa `strategyRef` (obrigatório). |
| `GET`  | `/v1/meta`     | **Lista** das versões servidas (`strategyId`, `name`, `version`) para popular/verificar o catálogo; filtrável por `strategyRef`. |

---

## Convenções (as três regras inegociáveis)

### 1. Decimal como string, sempre

Todo valor decimal/monetário (`currentPrice`, `quantity`, `confidence`, capitais, PnL, …) trafega
como **string JSON**, nunca como `number`. Isso protege as invariantes financeiras
(`docs/PHASE0_INVARIANTS.md`) contra ruído de ponto flutuante introduzido fora do JVM.

Cada app **deve** (de)serializar com biblioteca decimal, **nunca** float/Number nativo:

| Linguagem | Use | Nunca |
|---|---|---|
| Java | `BigDecimal` | `double` / `float` |
| Python | `decimal.Decimal` | `float` |
| Node | `decimal.js` / `big.js` | `Number` |

```jsonc
"currentPrice": "64150.75000000"   // ✅ string
"currentPrice": 64150.75            // ❌ number — proibido
```

### 2. Timestamps em ISO-8601 UTC

Strings `date-time` (`2026-07-03T14:22:05.123Z`). Sem epoch, sem timezone local.

### 3. UUIDs como string `uuid`

`strategyRef`, `runnerId`, `lotId`, `transactionId`, etc.

---

## Semântica de `/evaluate`

### Request (`EvaluateRequest`)

- `strategyRef` — identidade da versão alvo (== `strategyId` de `/meta`). Permite que uma única app
  sirva múltiplas versões. Junto com `context.runnerId`, forma a **chave do estado de warm-up**
  (ver "Estado, warm-up e readiness").
- `tickId` — identificador opaco do tick (string), único por runner. Mesma `tickId` ⇒ mesma decisão
  (ver "Entrega e idempotência").
- `sequence` — inteiro monotônico crescente por runner, **não contíguo**. A app só acrescenta ao
  warm-up um tick com `sequence` maior que o último visto daquele runner. O ctrade o ancora no relógio
  (microssegundos), então ele continua crescente depois de um restart do ctrade sem estado persistido.
- `input` (`MarketInput`) — dados do tick. `currentPrice` é obrigatório e positivo; os demais preços
  são opcionais. A validação semântica rica (spread, faixa diária, idade do dado) é do ctrade — a app
  recebe o dado já aceito.
- `context` (`StrategyContext`) — estado operacional **read-only**: capital, posição agregada, lotes
  abertos e ordens em trânsito. `availableQuantity` de cada lote é o teto de SELL daquele lote.

#### Campos do contexto que pedem atenção

- **PnL marcado a mercado (T43).** `positionContext.currentPrice` é o preço do tick;
  `positionContext.unrealizedPnl` = `currentPrice × quantity − custo pago` (o custo já desconta a fee
  cobrada no ativo base, T46); `openLots[].currentPnlPercent` é a variação de `currentPrice` sobre o
  `entryPrice` do lote. São confiáveis para decisão. `context.unrealizedPnl` repete o da posição do
  símbolo do runner.
- **`totalCapital`** é `available + reserved` do portfolio, exposto para **dimensionar** a ordem (ex.:
  "alocar X% do capital"). Não é um limite: o teto de exposição do runner (`maxAllocationPercent`) e
  o saldo disponível são aplicados pelo ctrade na reserva, e uma ordem acima deles é recusada. Para o
  máximo que o runner pode gastar agora, use `maxOperationAmount`.
- **`maxOperationAmount`** é o menor entre `availableCapital` (saldo disponível do portfolio) e o espaço
  do runner no teto: `maxAllocationPercent × totalCapital` menos o custo pago do lote aberto e das BUY
  em voo (T62). É a mesma conta da reserva: uma BUY de valor até `maxOperationAmount` não é recusada
  pelo teto, salvo corrida com outro runner do mesmo portfolio. Com lote aberto, o custo dele ocupa o
  teto até a venda; `maxOperationAmount` pode ser `0`.

### Response (`Decision`) — regras de coerência por `decision`

`Decision` é modelada como `oneOf` das quatro variantes abaixo, com `discriminator` em `decision`.
Assim os campos específicos de cada ação são **obrigatórios na fronteira** (validação/codegen), e não
apenas por convenção — uma decisão malformada (ex.: `SHOULD_BUY` sem `quantity`) é rejeitada antes de
virar transação, em vez de falhar em runtime no signal path.

As **faixas** também são enforced na fronteira: como decimal trafega como string (então `minimum`/
`maximum` do JSON Schema, que valem para `number`, não se aplicam), `quantity` usa `PositiveDecimal`
(`> 0`), `confidence` usa `UnitFractionDecimal` (`[0,1]`) e `limitPrice` usa `NullablePositiveDecimal`
— todos via `pattern` (regex sem lookahead, portável entre Java/Python/JS). Assim `quantity: "-0.1"`
ou `confidence: "2"` são rejeitados no boundary. Os decimais de `input`/`context` seguem o `Decimal`
genérico: são dados que o ctrade **envia** já validados, não saída remota não-confiável.

| `decision` | Campos obrigatórios | Ignorados | Efeito |
|---|---|---|---|
| `SHOULD_HOLD` | — | `quantity`, `confidence` | Nenhuma ação neste tick. |
| `SHOULD_BUY` | `quantity > 0`, `confidence ∈ [0,1]` | — | `limitPrice` null ⇒ preço de mercado do tick. |
| `SHOULD_SELL` | `quantity > 0`, `confidence ∈ [0,1]` | — | `targetLotId` null ⇒ FIFO. Acima do `availableQuantity` do lote ou lote de outro runner: descartado, sem ordem (T61). |
| `SHOULD_CANCEL` | `targetTransactionId` | `quantity`, `confidence` | Cancela ordem em trânsito. |

- `limitPrice` — quando informado, deve ser positivo; é o valor que persiste, reserva capital e vai à
  exchange. `null` = usar o preço de mercado do tick (default). A mais de 10% do preço do tick, o
  ctrade descarta o sinal (`REJECTED_PRICE_DEVIATION`, T50).
- `quantity` é **absoluta** (ex.: `"0.05"` BTC), não percentual.

---

## Estado, warm-up e readiness

A app **pode** manter estado de warm-up (ex.: janela de histórico de uma média móvel) chaveado por
**`(strategyRef, context.runnerId)`**. Esse é o único efeito colateral admitido — nada de I/O externo,
rede ou persistência na decisão. A decisão deve ser função de `(input, context)` + estado de warm-up
acumulado **daquele runner**.

Chavear só por `strategyRef` misturaria o histórico de dois runners que usam a mesma versão em
símbolos diferentes (BTCUSDT e ETHUSDT) — a média móvel de um contaminaria o outro.

`GET /health` reporta `ready`:

- `ready=false` — app viva, mas ainda aquecendo (ex.: `"warming up: 12/20 ticks"`). Responde `503`.
- `ready=true` — warm-up concluído; a versão pode ir para `ACTIVE`.

**Readiness é escopada por versão.** Como o warm-up é chaveado por `strategyRef`, a readiness também
é: numa app que serve V1 e V2, a V1 pode estar `ready=true` enquanto a V2 ainda aquece. Há dois
endpoints, com **schemas de resposta distintos**:

- `GET /health` — readiness **agregada** (`Health`): `ready=true` só se **todas** as versões servidas
  estão prontas. Serve como probe de liveness e para apps de versão única. Não ecoa `strategyRef`.
- `GET /health/{strategyRef}` — readiness **escopada** (`ScopedHealth`): `ready` fala pela versão do
  path e a resposta **ecoa `strategyRef` (obrigatório e não-nulo)**. Com `?runnerId=`, `ready` fala
  pelo warm-up **daquele runner** naquela versão e a resposta ecoa também o `runnerId`; sem ele,
  `ready=true` só quando todos os runners vistos pela versão terminaram o warm-up.

A separação é o que dá **prova de schema**: uma resposta agregada não pode se passar por escopada
(faltaria o `strategyRef` obrigatório), então o portão de promoção não corre o risco de tomar um
`ready` app-wide como se fosse da V2 e promover uma versão fria.

**O portão de promoção do ctrade consulta `GET /health/{strategyRef}?runnerId=` da versão alvo para o
runner que vai trocar e não vira o ponteiro dele para uma versão com `ready=false`.** Isso é o que torna o deploy-ao-lado seguro: a
V2 sobe e aquece em paralelo enquanto a V1 opera, e a troca só ocorre quando a V2 se declara pronta —
sem janela cega.

---

## Entrega e idempotência

Cada chamada a `/evaluate` acrescenta o tick ao warm-up — por isso ela **não é segura de repetir**
cegamente. As regras:

- **O ctrade não faz retry de `/evaluate`.** Falha de transporte, timeout ou `5xx` viram HOLD
  implícito naquele tick (ver "Falha e timeout"); o próximo tick segue normalmente.
- **`tickId` deduplica a decisão.** Se a mesma `tickId` chegar de novo para o mesmo runner (ex.:
  retry de uma camada de transporte), a app devolve a decisão já calculada, sem recalcular.
- **`sequence` protege o warm-up.** A app só acrescenta ao histórico um tick com `sequence` maior que
  o último aceito daquele `(strategyRef, runnerId)`; um `sequence` menor ou igual é avaliado sem mexer
  no warm-up (ou respondido do cache, se a `tickId` já foi vista).

Assim, nem retry de transporte nem reordenação contam o mesmo tick duas vezes.

## Falha e timeout

A resposta de `/evaluate` **deve** ser sempre uma decisão válida. Em erro interno, a app deve preferir
devolver `SHOULD_HOLD` explícito a estourar exceção.

Do lado do ctrade, qualquer falha de transporte, timeout ou `5xx` é tratada como **HOLD implícito** —
o runner simplesmente não age naquele tick, sem penalizar os demais runners. Isso espelha a
normalização que o `StrategySignalEvaluator` já aplica hoje para retorno nulo/exceção da estratégia
in-process. Ou seja: uma app de estratégia indisponível degrada para "não opera", nunca para erro no
pipeline financeiro.

**Decisão velha também vira HOLD.** A decisão calculada para o tick de `input.timestamp` é aplicada
depois da latência da chamada. O ctrade descarta, como HOLD implícito e com métrica, a decisão cuja
idade (`agora − input.timestamp`, medida quando a resposta chega) excede
`ctrade.strategy.remote.max-decision-age`. É validação do lado do ctrade, sem campo novo na resposta;
o risco é maior em `SHOULD_BUY`/`SHOULD_SELL` sem `limitPrice`, que usam o preço do tick.

**HOLD implícito não deixa o lote sem saída.** A proteção do lote (T44) roda no ctrade a cada tick,
antes e independente da estratégia: lote com perda acima de `maxLossPercentPerLot` do runner (padrão
5% do custo) é vendido a mercado mesmo com a app fora do ar. Ela não faz parte deste contrato, e a
estratégia não é avisada além do que já vê no contexto (o lote some de `openLots`).

---

## Versionamento e identidade

- **Uma versão = um `strategyId` (UUID) próprio.** V2 não reusa o UUID da V1.
- Durante a coexistência V1/V2, o runner deve resolver por **`strategyId` explícito**, não por nome
  (o fallback por nome fica ambíguo com duas versões de mesmo nome no ar).
- Como uma app pode servir múltiplas versões, `/meta` retorna a **lista** das versões servidas e
  `/health/{strategyRef}` reporta a readiness **daquela** versão — o catálogo e o portão de promoção
  operam por `strategyId`, nunca por um estado app-wide único.
- `contractVersion` (em `/meta`) declara a versão **deste contrato** que a app implementa, separada da
  `version` da estratégia. O caminho é versionado (`/v1`); mudança incompatível de contrato ⇒ `/v2`.

---

## Exemplo

**Request** `POST /v1/evaluate`

```json
{
  "strategyRef": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "tickId": "11111111-1111-1111-1111-111111111111:1751552525123:4182",
  "sequence": 4182,
  "input": {
    "symbol": "BTCUSDT",
    "currentPrice": "64150.75000000",
    "previousPrice": "64020.10000000",
    "bidPrice": "64150.00000000",
    "askPrice": "64151.50000000",
    "volume": "1832.44000000",
    "high24h": "64980.00000000",
    "low24h": "63110.00000000",
    "timestamp": "2026-07-03T14:22:05.123Z"
  },
  "context": {
    "runnerId": "11111111-1111-1111-1111-111111111111",
    "portfolioId": "22222222-2222-2222-2222-222222222222",
    "symbol": "BTCUSDT",
    "positionContext": {
      "symbol": "BTCUSDT",
      "quantity": "0",
      "averagePrice": "0",
      "currentPrice": "0",
      "unrealizedPnl": "0",
      "realizedPnl": "0",
      "openLots": []
    },
    "openLots": [],
    "pendingOrders": [],
    "totalCapital": "10000.00000000",
    "availableCapital": "10000.00000000",
    "maxOperationAmount": "2000.00000000",
    "minOperationAmount": "50.00000000",
    "maxOpenPositions": 5,
    "currentOpenPositions": 0,
    "realizedPnl": "0",
    "unrealizedPnl": "0"
  }
}
```

**Response** `200`

```json
{
  "strategyName": "SimpleMovingAverageStrategy",
  "decision": "SHOULD_BUY",
  "confidence": "0.80",
  "quantity": "0.03118000",
  "limitPrice": null,
  "targetLotId": null,
  "targetTransactionId": null,
  "reasoning": "Cruzamento de SMA (Alta)",
  "timestamp": "2026-07-03T14:22:05.140Z",
  "metadata": { "sma": "63980.42000000" }
}
```

---

## Autenticação

Opcional: quando a estratégia tem token cadastrado no catálogo do ctrade (T54, um token por
estratégia), o ctrade envia `Authorization: Bearer <token>` em todas as chamadas a ela. A app que exige token responde `401` sem ele. O serviço deve ficar em rede interna.

## Onde isto se encaixa

Camada 1 do desacoplamento de estratégias — **implementada na T41**:

- **Core:** `StrategyEvaluationPort` + `RemoteTradingStrategy` (proxy que delega `executeStrategy`);
  falha vira HOLD com o desfecho `STRATEGY_UNAVAILABLE`. O catálogo (`RemoteStrategyCatalogUseCase`,
  T54) confere `/v1/meta` antes de colocar a estratégia em uso.
- **`adapter-strategy-remote`:** formato de rede deste contrato, validação da decisão e cliente (sem
  retry, `tickId`/`sequence` por runner, descarte por idade).
- **spring-application:** transporte OkHttp com timeout, métricas e o ciclo periódico de conferência.
  Desde a T54 o catálogo é persistido e administrado por `/api/admin/strategies` (cadastro sem restart,
  token por estratégia, histórico de mudanças).
- **`strategy-service-reference`:** app de referência que serve o SMA por este contrato; o teste de
  paridade in-process × remoto é o portão da migração.

Fora do escopo ainda: troca de versão em runtime, harness de backtest, gate de invariantes,
shadow/canário e máquina de promoção (`REGISTERED → SHADOW → ACTIVE → DRAINING → RETIRED`).

Transporte inicial é **REST/JSON**; o `StrategyEvaluationPort` é agnóstico, então gRPC é uma troca de
adapter no futuro sem tocar no core, caso a serialização vire gargalo medido.
