# T72 — Histórico em candles no contrato de estratégias (contrato 2.1)

**Complexidade:** Alta
**Responsável:** Claude
**Dependências:** T41 (estratégia remota), T56 (demanda de market data), T69 (contrato 2.0, `requirements`)
**Status:** Implementada em `feature/t72-strategy-candles` (2026-11-20); teste na VPS depois do merge, com a app de teste no `ctrade-strategies`

---

## Descrição

O ctrade deve atender estratégias de qualquer prazo: scalping, daytrade, swingtrade e position trade. O estudo
`docs/estudos/` do `ctrade-strategies` (branch local `feature/estudo-estilos-operacao`, ainda não publicada) mostra
que o contrato 2.0 só alimenta o sinal de entrada das duas pontas mais curtas.

A app é stateless e o caminho é unilateral (T69). Todo dado de mercado precisa vir no request. Hoje o único histórico
é `input.priceHistory`:

- pares `{price, timestamp}`, sem abertura, máxima, mínima, fechamento nem volume;
- teto de 1000 ticks, que na testnet (~0,65 tick/s) cobre cerca de 25 minutos;
- só em memória, vazio depois de um restart.

| Estilo | Gráfico de decisão | Entrada hoje | Saída hoje |
|---|---|---|---|
| Scalping | ticks, 1 min | cabe | cabe |
| Daytrade curto | janelas de minutos | cabe | cabe |
| Daytrade clássico | 5 a 15 min, contexto de 1 h | não cabe | cabe |
| Swingtrade | 1 h, 4 h, diário | não cabe | cabe |
| Position trade | diário, semanal | não cabe | cabe |

A saída já funciona em qualquer prazo, porque `openLots[]` traz `entryPrice`, `openedAt`, `peakPrice` e
`currentPnlPercent`. Falta o dado do sinal de entrada: uma média de 200 dias precisa de 200 candles diários.

## Contexto técnico (estado real)

| Peça | Onde | Estado |
|---|---|---|
| Histórico de ticks | `PriceHistoryPort` (`core/.../ports/outbound/marketdata`), `InMemoryPriceHistoryRepository` (`spring-application/.../repository`) | `MAX_TICKS = 1000`, por exchange e símbolo, em memória |
| Requisito declarado | `RemoteStrategyCatalogUseCase.divergence`, `remote_strategies.price_history_ticks` (V20) | Gravado na primeira conferência; diferença falha a conferência ("needs a new strategyRef") |
| Leitura do `/meta` | `RemoteStrategyClient.describe` | `requirements` fora do esquema vira `INVALID_RESPONSE`. Há teste que rejeita `{"candles":{"limit":500}}` (`RemoteStrategyClientTest.metaOutsideTheContractIsAnInvalidResponse`) |
| Portão do histórico | `ProcessTradeSignalUseCase.processRunner` | Sem os ticks declarados, não chama a app e conta `WAITING_HISTORY` |
| Fonte do tick | idem | A série usada é a da fonte do tick (`marketDataSource`), que pode ser outra exchange autorizada do runner |
| Demanda de market data | `EnsureMarketDataFeedUseCase`, `StreamDemand`, `OpenLotDemand`, `MarketDataFeedScheduler` | Abre os streams dos runners `ACTIVE`, `TERMINATING` e `HALTED` e dos símbolos com lote aberto, a cada 30 s e após `start`/`resume` |
| Candle | `StreamType.KLINE` comentado | **Nenhum código de candle** em `core`, `adapter-*` ou `spring-application` |
| Binance | `BinanceUrlBuilder` | Streams `@ticker`, `@trade` e `@bookTicker`; nenhum endpoint de klines |
| Coinbase | `adapter-coinbase` | Adapter de market data incluído no build |
| Versão | `RemoteStrategyCatalogUseCase.isSupportedContract` | Confere só a versão principal (`2`) |
| Proteção do lote | `PositionProtection` (`core/.../domain/runner`) | `maxLossPercentPerLot` por runner em (0, 1); `maxHoldingDuration` por runner, nulo desliga, mínimo 1 s; os dois pelo `PATCH /api/admin/runners/{id}` |
| Timeout | `application.yml` (`ctrade.strategy.remote.timeout-ms`) | 250 ms, sem retry |
| Log do body | `RemoteStrategyClient`, `OkHttpStrategyServiceHttpAdapter` | Nenhum dos dois loga o body do request; não há biblioteca de tracing no build |

## Solução proposta

### 1. Contrato 2.1 (aditivo)

- `/meta`: `requirements.candles` opcional, uma lista de `{interval, count}`.
  - `interval` de uma lista fechada: `1m`, `5m`, `15m`, `1h`, `4h`, `1d`, `1w`.
  - `count` de 1 a 1000.
  - Até 3 séries, sem intervalo repetido.
- `/evaluate`: `input.candles` com uma série por intervalo declarado, na ordem declarada.
  - Cada série vai do mais antigo para o mais novo, com exatamente `count` candles **fechados**.
  - O último candle é o mais recente já fechado no instante do tick.
  - Cada candle: `openTime` (ISO-8601 UTC), `open`, `high`, `low`, `close` e `volume` (decimal como string; volume
    na moeda base).
- **Sem lacunas:** a série é contínua, um candle por intervalo. Se o ctrade não tiver a série completa e contínua, não
  chama a app (`WAITING_HISTORY`, como os ticks).
- **Estratégia sem candles:** recebe `input.candles` vazio, como `priceHistory` vazio para quem não declara ticks.
  O campo é sempre enviado (obrigatório no `MarketInput`).
- **Versão:** 2.1.0, mesma rota `/v2`, sem regra de versão menor na conferência. Não há estratégia de produção
  consumindo o contrato; as apps do `ctrade-strategies` sincronizam o contrato junto com a entrega.
- As regras atuais de requisito continuam: gravado no catálogo na primeira conferência, conferido a cada
  verificação, e mudar requisito exige novo `strategyId`.

O diff do `openapi.yaml` e do `CONTRACT.md` está no anexo.

### 2. `core`: requisitos como um objeto só

Um fluxo, um caminho: os requisitos deixam de ser um `int` de ticks espalhado e viram um objeto.

- `StrategyRequirements(int priceHistoryTicks, List<CandleRequirement> candles)` substitui o `priceHistoryTicks`
  solto em:
  - `RemoteStrategyIdentity`;
  - `RemoteStrategy`;
  - `RemoteTradingStrategy.requiredPriceHistoryTicks()`, que vira `requirements()`;
  - `RemoteStrategyView`, a visão do catálogo na API.
- Uma validação só (`StrategyRequirements.validate`) para tetos, intervalos e repetição. O `divergence` do catálogo
  compara o objeto inteiro.
- `CandleInterval` (enum com a duração de cada intervalo) e `CandleDto(openTime, open, high, low, close, volume)`
  em `core/.../dto/strategy`.
- Catálogo: migration nova com a coluna `remote_strategies.candle_requirements` (texto JSON, nula até a primeira
  conferência), ao lado de `price_history_ticks`.

### 3. `core`: série de candles por demanda

- Porta de saída `CandleHistoryPort` em `core/.../ports/outbound/marketdata`. Ela devolve os `count` últimos candles
  fechados até um instante e mantém a série em dia.
  - `latestClosed(exchange, symbol, interval, count, upTo)` devolve a série completa e contínua, ou vazio.
- Porta de saída `ExchangeCandleQueryPort` em `core/.../ports/outbound/exchange/rest`:
  - `supportedIntervals()`;
  - `fetchClosed(symbol, interval, endExclusive, limit)`, até 1000 por chamada.
- Use case `EnsureCandleSeriesUseCase`, chamado pelo `MarketDataFeedScheduler` no mesmo ciclo do
  `EnsureMarketDataFeedUseCase`.
  - **Demanda:** `(exchange de market data, símbolo, intervalo, maior count)` dos runners em `StreamDemand` cuja
    estratégia declara candles. A demanda sai do mesmo lugar que a dos streams (T56).
  - **Carga:** busca o que falta pela REST da exchange até a série ter o `count`.
  - **Manutenção:** em cada fechamento de intervalo, pede pela REST os candles fechados desde o último que tem. A mesma
    chamada carrega, atualiza e cobre buraco.
  - Sem demanda, a série é descartada.
- `ProcessTradeSignalUseCase.processRunner`: o portão de histórico passa a conferir `requirements` inteiro. Faltou
  tick ou candle, `WAITING_HISTORY`, sem chamar a app.
- A fonte é a mesma do tick avaliado (`marketDataSource`). Se essa exchange não suporta o intervalo, o runner fica
  sem decisão e o estado operacional mostra o motivo, pelo mesmo caminho do `RUNNER_ON_HOLD_STRATEGY_UNUSABLE`.

**Por que REST no fechamento e não o stream `@kline`:** só candles fechados entram no contrato. Uma consulta logo
depois do fechamento entrega o mesmo dado que o stream, sem novo tipo de stream, sem reconexão e sem lógica separada
para buraco: quem cai e volta pede de novo desde o último candle. O custo é de uma consulta por série e por
intervalo, o que dá 1 por minuto na série de `1m`. `StreamType.KLINE` continua comentado.

### 4. `adapter-*`

- `adapter-binance`: `GET /api/v3/klines` (`symbol`, `interval`, `endTime`, `limit` ≤ 1000) e o mapeamento do array
  de klines para `CandleDto`. Só entram candles com `closeTime` no passado. Suporta os 7 intervalos.
- `adapter-mock`: candles gerados pela mesma caminhada de preço do mock, determinísticos por semente, com histórico
  inicial para os testes. Suporta os 7 intervalos.
- `adapter-coinbase`: fora desta task; declara nenhum intervalo suportado. Runner com candles e fonte Coinbase fica
  sem decisão com motivo explícito.

### 5. `spring-application`

- `InMemoryCandleRepository` implementa o `CandleHistoryPort`: série por chave, limitada ao maior `count` da
  demanda.
- Wiring em `MarketDataFeedConfig` e na config que monta o catálogo remoto; nada de regra no Spring.
- `adapter-strategy-remote`: DTO de fio de `input.candles`, sempre enviado (vazio quando não declarado), e leitura de
  `requirements.candles`.

### 6. Documentação

- `docs/strategy-service/openapi.yaml` e `CONTRACT.md`, conforme o anexo.
- `.ai/flows/runner-signal-processing.md` (portão de histórico) e o flow de market data (demanda de candles).
- `docs/OPERATIONS_RUNBOOK.md`: o motivo de "sem decisão" por intervalo não suportado, e a proteção recomendada
  para runners de prazo longo (decisão 5).

## Decisões

| # | Decisão | Recomendação | Motivo | Situação |
|---|---|---|---|---|
| 1 | Candle em formação | **Só fechados** | Decisão determinística e reproduzível; o preço ao vivo já vem em `currentPrice` e nos ticks. Com o candle em formação, a mesma série muda a cada tick e a app teria de saber disso | Decidido (2026-11-15): só fechados |
| 2 | Versão do contrato | ~~2.1 aditiva, escondendo `candles` de quem não declara~~ | **Decisão registrada (2026-11-15):** não há estratégia de produção, então o contrato muda sem garantia de compatibilidade. `input.candles` é sempre enviado (vazio quando não declarado), versão 2.1.0 na mesma rota `/v2`, sem regra de versão menor | Decidido |
| 3 | Persistir ou recarregar | **Só memória, recarga pela exchange** | A exchange é a fonte de verdade de dado público. A série máxima é 3 × 1000 candles por símbolo, e a carga é uma chamada por série. Persistir traria schema, migração e uma segunda fonte de verdade para sincronizar. Se a exchange não responder no boot, a estratégia espera (`WAITING_HISTORY`), que é o lado seguro | Decidido (2026-11-15) |
| 4 | Buracos | **Nunca entregar série com buraco** | O ctrade só chama a app com a série contínua. O buraco por queda é coberto pela própria consulta seguinte, que pede desde o último candle. Na Binance, minuto sem negócio vem como candle de volume 0 (a confirmar), então a série da exchange não tem buraco. Símbolo novo com menos histórico que o declarado nunca completa: o estado operacional precisa mostrar runner esperando histórico há muito tempo | Decidido (2026-11-15) |
| 5 | Proteção do lote em prazo longo | **Configurável hoje; falta orientação e um alerta** | `maxLossPercentPerLot` aceita até 0,99 e `maxHoldingDuration` aceita semanas (ISO-8601, nulo desliga), os dois por runner. Um position trade com stop de 20% funciona com `PATCH` de 0,20. Faltam três coisas: o padrão de 5% vale para todo runner novo, e quem cria um runner de prazo longo precisa lembrar de mudar; a venda da proteção é sempre a mercado; o drawdown do portfólio (10% em 24h) pode dar `HALT` de compras com posições longas oscilando, sem vender nada | Decidido (2026-11-15): padrão de 5% e drawdown mantidos; o runbook orienta a configurar a proteção de runners de prazo longo |
| 6 | Tamanho do body | **Tetos: 1000 candles por série e 3 séries** | O body não entra em log nem em trace. O pior caso é ~3000 candles, ~500 KB, ~5 ms de parse na app pela medição do estudo, bem dentro dos 250 ms. 1000 é o limite de uma chamada de klines da Binance (carga numa chamada só por série) e cobre a média de 200 dias com folga. 3 séries cobrem a análise em vários prazos típica (por exemplo 15m, 1h e 1d) | Decidido (2026-11-15): 1000 por série, 3 séries |

## Decisões registradas na implementação (2026-11-20)

- **Início e app de teste (mantenedor, 2026-11-20):** implementação autorizada antes do staging. A app de teste do
  critério 9 é uma app nova de soak no `ctrade-strategies` (ex.: `SoakCandles`), em PR separado, depois do merge.
- **Porta de guarda só guarda.** `CandleHistoryPort` tem `series`, `replace` e `retainOnly`. A regra de juntar, cortar
  e escolher (`CandleSeries`) fica no `core`, sem estado; o repositório em memória só troca a lista inteira, então a
  leitura de um runner nunca vê uma série pela metade.
- **Ciclo próprio de 1 s** (`CandleSeriesScheduler`, `ctrade.market-data.candles.interval-ms`), separado do ciclo de
  30 s dos streams: com 30 s, a série de `1m` ficaria atrasada até meio minuto depois de cada fechamento. A série em
  dia não consulta a exchange; o custo do ciclo é ler os runners. A demanda usa a mesma regra dos streams
  (`StreamDemand`), em cada fonte de `allowedMarketDataSources`.
- **Série atrasada não é entregue.** Além de completa e contínua, a série tem de terminar no último candle fechado no
  instante do tick. Nos poucos segundos depois de cada fechamento, até o candle novo chegar, o tick conta como
  `WAITING_HISTORY`. Documentado no contrato e no runbook.
- **"Fechado" pelo `closeTime` da Binance.** O adapter pede `limit + 1` (o último costuma estar em formação), descarta
  o candle cujo `closeTime` não passou e pagina para trás quando faltam candles (série de 1000).
- **Falha e histórico curto** esperam `retry-delay-ms` (10 s) antes de consultar a mesma série de novo, com log `WARN`
  (`candles: fetch failed`), sem `ERROR`: queda da exchange já tem os seus alarmes.
- **Estado operacional:** check novo `CANDLE_SERIES_UNAVAILABLE` (WARN), alimentado por `CandleSeriesGaps`, que o use
  case publica a cada ciclo: `UNSUPPORTED` na hora, `INCOMPLETE` depois do limiar de market data parado (60 s).
  Preferi um check próprio a estender o `RUNNER_ON_HOLD_STRATEGY_UNUSABLE`: a estratégia está utilizável, o que falta
  é a série, e o sujeito é a série, não o runner.
- **Mock:** candles por função pura de semente, símbolo, intervalo e abertura, em torno do mesmo preço inicial do feed
  de ticks. Não é a mesma caminhada aleatória dos ticks, que depende do relógio; o que importa para os testes é ser
  determinístico, contínuo e ter histórico para qualquer `count`.
- **Catálogo:** `remote_strategies.candle_requirements` em JSON (`V23`). A API do catálogo troca `priceHistoryTicks`
  por `requirements` (`priceHistoryTicks` e `candles`).
- **`StrategyInputDto.withHistory(ticks, candles)`** substitui `withPriceHistory`: um caminho só para o histórico.

## Questão em aberto: cadência de avaliação (fora de escopo)

Uma estratégia de swing continuará sendo chamada a cada tick, cerca de 0,65 por segundo por runner, e responderá
quase sempre HOLD, reenviando os mesmos candles. Avaliação do custo no pior caso (3 × 1000 candles):

- **ctrade:** serializar ~500 KB por chamada. Estimo poucos milissegundos (suposição, não medido). Sem cache, isso se
  repete a cada tick, embora a série só mude no fechamento do intervalo.
- **App:** ~5 ms de parse por chamada, na mesma ordem.
- **Rede:** ~325 KB/s por runner entre containers. Aceitável na VPS, mas cresce com o número de runners.

Quando incomodar, há dois caminhos baratos, sem mudar o contrato:

- o ctrade guarda a série já serializada até o próximo fechamento;
- ou um requisito futuro de cadência ("avaliar no fechamento do candle de 1h").

## Fora de escopo

- Cadência de avaliação (acima).
- Mudanças nas apps do `ctrade-strategies`: sincronizam o contrato depois, por `scripts/sync_contract.py`.
- Candles na Coinbase.
- Venda a descoberto, alavancagem, profundidade do livro.

## Riscos

- **Peso da API da Binance:** carga inicial de várias séries no boot. Mitigação: uma chamada por série, espaçadas pelo
  coordenador.
- **Relógio:** "candle fechado" depende da hora da exchange. Mitigação: usar o `closeTime` vindo da exchange, nunca
  o relógio local para decidir se fechou.
- **Requisitos como objeto:** mexe em todos os chamadores de `priceHistoryTicks`. Mitigação: migrar todos no mesmo
  PR e remover o campo antigo, sem caminho paralelo.

## Critérios de aceite

1. `/meta` com `candles` válidos é aceito e gravado; intervalo fora da lista, `count` fora de 1..1000, mais de 3
   séries ou intervalo repetido falham a conferência.
2. Mudar `candles` sem novo `strategyId` falha a conferência.
3. App sem `candles` recebe `input.candles` vazio; o resto do request não muda.
4. App com `candles` só é chamada com todas as séries completas e contínuas; antes disso, `WAITING_HISTORY`.
5. Série entregue: do mais antigo para o mais novo, `count` exato, só fechados, último fechado antes do tick.
6. Queda e volta: a série é recomposta pela consulta seguinte, sem buraco entregue à app.
7. Restart: a série é recarregada da exchange e a app volta a ser chamada sem esperar dias.
8. Mock entrega candles determinísticos; teste de integração ponta a ponta com uma app falsa que declara
   `1h × 200`.
9. Na VPS testnet: runner com app de teste declarando `1m × 30` e `1h × 200` recebe as séries certas, conferidas
   contra a REST da Binance.

## Validação

- `./gradlew test` completo.
- Na VPS, depois do merge, cerca de 1h: app de teste com candles; conferir as séries; reiniciar no meio.

## Ordem de entrega

1. Contrato (openapi e CONTRACT) e `StrategyRequirements` no `core`, migrando os chamadores de `priceHistoryTicks`.
2. `CandleHistoryPort`, `ExchangeCandleQueryPort`, `EnsureCandleSeriesUseCase` e o portão de histórico.
3. Binance e mock; repositório em memória; wiring.
4. Cliente remoto, migration, docs, PR em rascunho, CI e espera por comentários; merge pelo mantenedor; teste na
   VPS.

## O que foi verificado e o que é suposição

**Verificado no código:**

- Nenhum código de candle; `StreamType.KLINE` comentado; Binance só com `@ticker`, `@trade` e `@bookTicker`.
- `MAX_TICKS = 1000`; histórico em memória; portão `WAITING_HISTORY` em `ProcessTradeSignalUseCase`.
- Requisito gravado em `remote_strategies.price_history_ticks` e comparado no `divergence`; o cliente já rejeita
  `candles` desconhecido no `/meta`.
- `isSupportedContract` confere só a versão principal.
- A série do tick é a da fonte de market data, que pode ser outra exchange do runner.
- `Requirements` com `additionalProperties: false`; `MarketInput` sem essa restrição.
- `PositionProtection`: `maxLossPercentPerLot` em (0, 1), `maxHoldingDuration` opcional, mínimo 1 s, ISO-8601,
  ajustáveis por runner no `PATCH`.
- Timeout de `/evaluate` de 250 ms, sem retry.
- O body do request não é logado no `RemoteStrategyClient` nem no `OkHttpStrategyServiceHttpAdapter`; não há
  tracing no build.

**Suposição, a confirmar na implementação:**

- `GET /api/v3/klines` da Binance aceita `limit` até 1000, existe na testnet e tem peso baixo por chamada.
- Minuto sem negócio vem como candle de volume 0, então a série da exchange não tem buraco.
- O tempo de serialização no Java: estimado em poucos milissegundos para 3000 candles, não medido.
- A Coinbase não oferece `4h` nem `1w` nativos; não entra nesta task.
- Os números do estudo (83 KB e 0,8 ms para 500 candles) foram medidos do lado da app e não refeitos aqui.

---

## Anexo: diff proposto do contrato (não aplicado)

### `docs/strategy-service/openapi.yaml`

```diff
 info:
   title: CTrade Strategy Service Contract
-  version: 2.0.0
+  version: 2.1.0
@@ MarketInput
-      required: [symbol, currentPrice, timestamp, priceHistory]
+      required: [symbol, currentPrice, timestamp, priceHistory, candles]
@@
         priceHistory:
           type: array
           maxItems: 1000
           items: { $ref: '#/components/schemas/PricePoint' }
           description: >
             Do mais antigo para o mais novo, terminando no tick atual, com exatamente
             os ticks declarados em requirements.priceHistory.ticks (vazio se nenhum).
+        candles:
+          type: array
+          maxItems: 3
+          items: { $ref: '#/components/schemas/CandleSeries' }
+          description: >
+            (2.1) Uma serie por intervalo declarado em requirements.candles, na mesma
+            ordem. Vazio quando a estrategia nao declara candles.
         additionalData:
@@ PricePoint
+    CandleInterval:
+      type: string
+      enum: [1m, 5m, 15m, 1h, 4h, 1d, 1w]
+
+    Candle:
+      type: object
+      required: [openTime, open, high, low, close, volume]
+      properties:
+        openTime:
+          type: string
+          format: date-time
+          description: Abertura do periodo, ISO-8601 UTC.
+        open: { $ref: '#/components/schemas/Decimal' }
+        high: { $ref: '#/components/schemas/Decimal' }
+        low: { $ref: '#/components/schemas/Decimal' }
+        close: { $ref: '#/components/schemas/Decimal' }
+        volume:
+          $ref: '#/components/schemas/Decimal'
+          description: Volume negociado no periodo, na moeda base.
+
+    CandleSeries:
+      type: object
+      required: [interval, candles]
+      properties:
+        interval: { $ref: '#/components/schemas/CandleInterval' }
+        candles:
+          type: array
+          minItems: 1
+          maxItems: 1000
+          items: { $ref: '#/components/schemas/Candle' }
+          description: >
+            So candles fechados, do mais antigo para o mais novo, continuos (um por
+            intervalo), com exatamente o count declarado. O ultimo e o mais recente
+            ja fechado no instante do tick.
+
+    CandleRequirement:
+      type: object
+      additionalProperties: false
+      required: [interval, count]
+      properties:
+        interval: { $ref: '#/components/schemas/CandleInterval' }
+        count:
+          type: integer
+          minimum: 1
+          maximum: 1000
+
@@ Meta
         contractVersion:
           type: string
-          description: Versao do contrato implementada, ex. "2.0.0".
+          description: Versao do contrato implementada, ex. "2.1.0".
@@ Requirements
       description: >
-        O que a estrategia precisa em cada interacao. Sem os ticks declarados o
-        ctrade nao a chama. Requisito desconhecido, acima do teto ou diferente do
-        gravado no catalogo falha a conferencia.
+        O que a estrategia precisa em cada interacao. Sem os ticks e os candles
+        declarados o ctrade nao a chama. Requisito desconhecido, acima do teto ou
+        diferente do gravado no catalogo falha a conferencia.
       properties:
         priceHistory:
           type: object
           additionalProperties: false
           required: [ticks]
           properties:
             ticks:
               type: integer
               minimum: 0
               maximum: 1000
+        candles:
+          type: array
+          maxItems: 3
+          uniqueItems: true
+          items: { $ref: '#/components/schemas/CandleRequirement' }
+          description: >
+            (2.1) Series de candles fechados, no maximo 3, sem intervalo repetido.
```

### `docs/strategy-service/CONTRACT.md`

```diff
+> **Versão 2.1 (T72):** `requirements.candles` e `input.candles` (sempre presente, vazio sem declaração), só
+> candles fechados. Rotas seguem em `/v2`.
+>
 > **Versão 2.0 (T69, 2026-11-05):** apps stateless e caminho unilateral; `requirements` em `/meta`,
@@ ### Request (`EvaluateRequest`)
   os ticks recebidos, mesmo os que o runner não avaliou. Pode ter um buraco no tempo depois de uma queda do
   stream de mercado: quem depende de tempo confere `timestamp`.
+- `input.candles` (2.1) — uma série por intervalo declarado em `requirements.candles`, na mesma ordem. Cada
+  série tem exatamente `count` candles **fechados** (`openTime`, `open`, `high`, `low`, `close`, `volume`),
+  do mais antigo para o mais novo, contínuos, terminando no último já fechado antes do tick. O candle em
+  formação não entra: o preço ao vivo é `currentPrice`. Vazio quando a estratégia não declara candles.
@@ ## Requisitos
 ```json
-{ "requirements": { "priceHistory": { "ticks": 20 } } }
+{ "requirements": {
+    "priceHistory": { "ticks": 20 },
+    "candles": [ { "interval": "1h", "count": 200 }, { "interval": "1d", "count": 200 } ]
+} }
 ```
@@
 - `priceHistory.ticks` vai de 0 a 1000.
+- `candles` (2.1): até 3 séries, intervalos `1m`, `5m`, `15m`, `1h`, `4h`, `1d`, `1w`, sem repetir intervalo,
+  `count` de 1 a 1000.
+- Os candles vêm da exchange de market data do runner. Se ela não oferece o intervalo, o runner fica sem
+  decisão, e o estado operacional mostra o motivo.
@@
-- **Enquanto não tiver o histórico declarado, o ctrade não chama a app**: o tick conta como
-  `WAITING_HISTORY`. A app não precisa tratar histórico curto, e pode responder `400` a um request incompleto.
+- **Enquanto não tiver o histórico declarado (ticks e candles completos e contínuos), o ctrade não chama a
+  app**: o tick conta como `WAITING_HISTORY`. A app não precisa tratar histórico curto nem buraco, e pode
+  responder `400` a um request incompleto.
@@ ## Versionamento e identidade
   `version` da estratégia. O caminho é versionado (`/v2`); mudança incompatível de contrato ⇒ `/v2`.
```

## Fontes

- `ctrade-strategies`, `docs/estudos/` (branch `feature/estudo-estilos-operacao`)
- `docs/strategy-service/openapi.yaml`, `docs/strategy-service/CONTRACT.md`
- `.ai/tasks/T56-market-data-demand.md`, `.ai/tasks/T69-stateless-strategy-apps.md`
- `.ai/development-manual.md` (disposição de responsabilidades, um fluxo um caminho, seção 10)
- `core/.../ports/outbound/marketdata/PriceHistoryPort.java`, `core/.../usecase/strategy/RemoteStrategyCatalogUseCase.java`,
  `core/.../usecase/runner/processsignal/ProcessTradeSignalUseCase.java`, `core/.../domain/runner/PositionProtection.java`,
  `adapter-strategy-remote/.../RemoteStrategyClient.java`
