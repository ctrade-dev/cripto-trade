# Strategy Service Contract

Contrato de rede para estratégias executadas **fora do processo** do ctrade. Cada versão de
estratégia sobe como uma app independente (Java, Node, Python, …) que implementa este contrato.
O ctrade é o cliente; a app da estratégia é o servidor.

- **Fonte de verdade da forma:** [`openapi.yaml`](./openapi.yaml) (usado para codegen/validação nos três lados).
- **Fonte de verdade da semântica:** este documento.
- **Espelho interno em Java:** `core/src/main/java/com/marmitt/core/dto/strategy/*`. Se divergir do
  spec, o spec prevalece **na fronteira de rede** — o core continua dono do contrato de domínio.

> **Versão 2.0 (T69, 2026-11-05):** apps stateless e caminho unilateral; `requirements` em `/meta`,
> `input.priceHistory` e `openLots[].peakPrice`; sai `sequence` e o estado de warm-up; rotas em `/v2`.
>
> **Revisão 1.1 (T40, 2026-10-19):** sem retry do ctrade; descarte por idade da decisão do lado do ctrade;
> campos de PnL do contexto marcados a mercado (T43) e `totalCapital` documentado.

---

## Regras da app de estratégia

1. **Stateless.** A decisão é função só do request e da configuração da app. A app não guarda nada entre
   interações que influencie a decisão. Contadores de telemetria fora do contrato são permitidos.
2. **Caminho unilateral.** O ctrade chama a app; a app nunca chama o ctrade. Nada de I/O externo, rede ou
   persistência na decisão. Se faltar dado, expande-se este contrato, e o ctrade o entrega.
3. **Uma decisão vale só para o tick dela.** Ver "A decisão pode não virar ordem".
4. **Identidade imutável.** Ver "Versionamento e identidade".
5. **Uma configuração, uma app.** Os parâmetros são da app, fixados ao subir o processo, e o ctrade não os
   conhece. Outra configuração é outra app, com outro `strategyId`, para outro runner.

---

## Por que este contrato existe

Empurrando a execução das estratégias para trás de um contrato de rede, a linguagem atrás do endpoint deixa
de importar: o core permanece Java puro e o ecossistema de estratégias pode ser poliglota e de iteração
rápida, sem reiniciar o ctrade.

---

## Endpoints

| Método | Caminho | Papel |
|---|---|---|
| `POST` | `/v2/evaluate` | Uma decisão pura por tick. É a chamada quente. |
| `GET`  | `/v2/health`   | A app está no ar e pronta para responder. |
| `GET`  | `/v2/health/{strategyRef}` | O mesmo para uma versão servida; ecoa `strategyRef` (obrigatório). |
| `GET`  | `/v2/meta`     | **Lista** das versões servidas (`strategyId`, `name`, `version`, `contractVersion`, `requirements`), para verificar o catálogo; filtrável por `strategyRef`. |

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
  sirva múltiplas versões.
- `tickId` — identificador opaco do tick, só para rastreio.
- `input` (`MarketInput`) — dados do tick. `currentPrice` é obrigatório e positivo; os demais preços
  são opcionais. A validação semântica rica (spread, faixa diária, idade do dado) é do ctrade — a app
  recebe o dado já aceito.
- `input.priceHistory` — `[{price, timestamp}]`, do mais antigo para o mais novo, terminando no tick
  atual, sempre com exatamente os `ticks` declarados em `requirements` (vazio se nenhum). A série tem todos
  os ticks recebidos, mesmo os que o runner não avaliou. Pode ter um buraco no tempo depois de uma queda do
  stream de mercado: quem depende de tempo confere `timestamp`.
- `context` (`StrategyContext`) — estado operacional **read-only**: capital, posição agregada, lotes
  abertos e ordens em trânsito. `availableQuantity` de cada lote é o teto de SELL daquele lote.

#### Campos do contexto que pedem atenção

- **PnL marcado a mercado (T43).** `positionContext.currentPrice` é o preço do tick;
  `positionContext.unrealizedPnl` = `currentPrice × quantity − custo pago` (o custo já desconta a fee
  cobrada no ativo base, T46); `openLots[].currentPnlPercent` é a variação de `currentPrice` sobre o
  `entryPrice` do lote. São confiáveis para decisão. `context.unrealizedPnl` repete o da posição do
  símbolo do runner.
- **`openLots[].peakPrice`** é o maior preço visto desde a abertura do lote, incluindo o tick atual. Depois de
  um restart do ctrade recomeça em `max(entryPrice, preço atual)`.
- **`pendingOrders[].requestedAt`** é o instante da ordem: a idade dela é `input.timestamp − requestedAt`.
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

## Requisitos

A app declara em `/meta` o que precisa em cada interação:

```json
{ "requirements": { "priceHistory": { "ticks": 20 } } }
```

- `requirements` é obrigatório; `{}` significa que a estratégia não precisa de nada além do básico.
- `priceHistory.ticks` vai de 0 a 1000.
- O ctrade grava os requisitos no catálogo na primeira conferência. Requisito desconhecido, acima do teto ou
  diferente do gravado falha a conferência, e a estratégia não é usada: mudar um requisito exige novo
  `strategyId`.
- A conferência roda ao cadastrar, reabilitar, trocar o endereço e no boot do ctrade. Trocar a app no ar sem
  uma dessas ações não é detectado na hora.
- **Enquanto não tiver o histórico declarado, o ctrade não chama a app**: o tick conta como
  `WAITING_HISTORY`. A app não precisa tratar histórico curto, e pode responder `400` a um request incompleto.

`GET /health` e `GET /health/{strategyRef}` dizem só se a app está pronta para responder. Sem estado, não há
warm-up: reiniciar a app não muda a decisão seguinte.

---

## A decisão pode não virar ordem

**Uma decisão vale só para o tick dela.** Se não for executada, é descartada, e a estratégia é consultada de
novo no tick seguinte, com o estado atual. O ctrade não guarda decisão para depois, e a app não é avisada.
Motivos de descarte, além dos limites de risco:

- envio de ordens bloqueado por conexão perdida com a exchange (`DISPATCH_BLOCKED`), inclusive `SHOULD_CANCEL`;
- decisão velha (ver "Falha e timeout");
- corrida com um lock de venda ou com o runner parando.

A app **não presume** que o sinal virou ordem. Quem quer cancelar repete `SHOULD_CANCEL` enquanto a ordem estiver
em `pendingOrders`; o ctrade suprime as repetições de um envio recente.

---

## Entrega

**O ctrade não faz retry de `/evaluate`.** Falha de transporte, timeout ou `5xx` viram HOLD implícito naquele
tick; o próximo tick segue normalmente. Como a app é stateless, repetir um request é seguro.

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
- **Mudou metadado (nome, versão ou requisitos), é outra estratégia:** novo `strategyId` e novo runner.
- O runner resolve a estratégia por **`strategyId` explícito**, não por nome.
- Como uma app pode servir múltiplas versões, `/meta` retorna a **lista** das versões servidas.
- `contractVersion` (em `/meta`) declara a versão **deste contrato** que a app implementa, separada da
  `version` da estratégia. O caminho é versionado (`/v2`); mudança incompatível de contrato ⇒ `/v2`.

---

## Exemplo

**Request** `POST /v2/evaluate`

```json
{
  "strategyRef": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "tickId": "11111111-1111-1111-1111-111111111111:1751552525123",
  "input": {
    "symbol": "BTCUSDT",
    "currentPrice": "64150.75000000",
    "previousPrice": "64020.10000000",
    "bidPrice": "64150.00000000",
    "askPrice": "64151.50000000",
    "volume": "1832.44000000",
    "high24h": "64980.00000000",
    "low24h": "63110.00000000",
    "timestamp": "2026-07-03T14:22:05.123Z",
    "priceHistory": [
      { "price": "64020.10000000", "timestamp": "2026-07-03T14:22:03.611Z" },
      { "price": "64150.75000000", "timestamp": "2026-07-03T14:22:05.123Z" }
    ]
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
  T54) confere `/v2/meta` antes de colocar a estratégia em uso.
- **`adapter-strategy-remote`:** formato de rede deste contrato, validação da decisão e cliente (sem
  retry, descarte por idade).
- **spring-application:** transporte OkHttp com timeout, métricas e o ciclo periódico de conferência.
  Desde a T54 o catálogo é persistido e administrado por `/api/admin/strategies` (cadastro sem restart,
  token por estratégia, histórico de mudanças).
- **Apps de referência:** as de `ctrade-dev/ctrade-strategies` (T60), com testes de conformidade contra
  a cópia deste contrato. O antigo `strategy-service-reference` (SMA em Java) saiu na T65, junto com as
  estratégias embutidas no ctrade.

Fora do escopo ainda: troca de versão em runtime, harness de backtest, gate de invariantes,
shadow/canário e máquina de promoção (`REGISTERED → SHADOW → ACTIVE → DRAINING → RETIRED`).

Transporte inicial é **REST/JSON**; o `StrategyEvaluationPort` é agnóstico, então gRPC é uma troca de
adapter no futuro sem tocar no core, caso a serialização vire gargalo medido.
