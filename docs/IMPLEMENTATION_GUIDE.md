# Implementation Guide — CTrade

## 1. Propósito e Escopo

Este documento detalha **como construir** cada componente definido no Blueprint. Enquanto o Blueprint define "o quê" e "por quê", este guia responde "como" — com decisões técnicas de implementação, padrões de código e contratos entre componentes.

### Escopo

- Comunicação entre agregados: síncrona vs assíncrona, garantias de entrega, retry e timeout
- Consistência de dados: eventual consistency, window máximo, impacto de saldo desatualizado
- Granularidade de Position: definição por modo (Hedging vs Netting), composição interna
- Escopo das Accounting Policies: aplicação em aberturas vs fechamentos, mutabilidade dinâmica
- Capital Request: abstração (chamada direta vs serviço), sincronia
- Validação de Trade Parameters: níveis de validação, configuração, tratamento de falha
- Context Injection: composição do contexto injetado na Strategy
- Cooldown: granularidade (por símbolo, direção, global), regras de reset

## 2. Relação com outros documentos

- **[Blueprint](BLUEPRINT.md):** Define a arquitetura que este guia implementa
- **Operations Runbook:** Consome as decisões técnicas deste guia para definir procedimentos operacionais

---

## 3. Modelo de Dados

Esta seção especifica campo-a-campo como transformar o modelo de dados atual (agregado único — Portfolio) no modelo alvo definido pelo Blueprint (dois agregados — Portfolio + StrategyRunner). Serve como referência autoritativa para a implementação das entidades, Value Objects, enums e schema do banco de dados.

> **Referência:** Blueprint Seção 3 (Modelo de Dados e Relacionamentos) e Seção 7 (Aggregate Boundaries).

### 3.1 Estado Atual (Resumo)

O modelo atual utiliza um **único agregado** (Portfolio) que concentra responsabilidades financeiras, operacionais e de execução. A tabela abaixo mapeia cada entidade/VO ao seu package, tabela DB e campos-chave.

| Entidade / VO            | Package (Domain)            | Tabela DB             | Campos-Chave                                                                                                                                                                                                         |
|--------------------------|-----------------------------|-----------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Portfolio**            | `domain.portfolio`          | `portfolios`          | `id`, `name`, `strategyId`, `strategyName`, `symbol`, `initialCapitalAmount/Currency`, `orderExecutionExchange`, `isActive`, `createdAt`                                                                             |
| **Balance**              | `domain.portfolio`          | `portfolio_balances`  | `portfolioId` (PK/FK), `availableAmount/Currency`, `investedAmount/Currency`, `realizedPnL`, `lastExecutionTime`                                                                                                     |
| **Position**             | `domain.portfolio`          | `positions`           | `portfolioId` (PK/FK), `symbol`, `quantityAmount/Currency`, `averagePriceAmount/Currency`, `currentPriceAmount/Currency`                                                                                             |
| **Transaction**          | `domain.portfolio`          | `transactions`        | `id`, `portfolioId` (FK), `clientOrderId`, `status`, `type`, `symbol`, `quantity` (3 cols), `executedQuantity` (3 cols), `price` (3 cols), `executedPrice` (3 cols), `total` (3 cols), `fee` (3 cols), `targetLotId` |
| **TransactionMatch**     | `domain.portfolio`          | `transaction_matches` | `buyTransactionId` + `sellTransactionId` (PK composta), `matchedQuantity`, `createdAt`                                                                                                                               |
| **Asset** (VO)           | `domain.portfolio`          | — (embutido)          | `amount` (BigDecimal), `currency` (String), `type` (AssetType)                                                                                                                                                       |
| **AccountingPolicy**     | `domain.portfolio.contrats` | — (interface)         | `calculatePosition()`, `matchOrder()`, `computeCost()`                                                                                                                                                               |
| **FifoAccountingPolicy** | `domain.portfolio.contrats` | —                     | Implementação FIFO com `DUST_THRESHOLD = 1e-8`                                                                                                                                                                       |
| **StrategyOutputDto** (record) ¹ | `core.dto.strategy`     | —                     | `strategyName`, `decision` (`TradingAction`), `confidence`, `quantity`, `limitPrice` (opcional), `targetLotId`, `targetTransactionId`, `reasoning`, `timestamp`, `metadata`                                          |
| **TradingAssetsConfig**  | `domain.portfolio`          | —                     | Classificação de ativos (FIAT, STABLECOIN, CRYPTO)                                                                                                                                                                   |

> ¹ **Nota de reconciliação (T34):** o design histórico chamava este contrato de `TradingDecision` (VO em
> `domain.portfolio`) com campos `decision`, `symbol`, `quantity`, `price`, `targetLotId`, `reasoning`,
> `confidence`. A implementação real é o **`StrategyOutputDto`** (record em `core.dto.strategy`). Diferenças
> em relação ao design antigo:
> - `price` → `limitPrice` **opcional** (T34): `null` usa o preço de mercado do tick; quando presente, é o
>   preço-limite que persiste, reserva capital e é enviado à exchange.
> - `symbol` **não existe e é intencional**: o runner é vinculado a um único símbolo (`runner.getSymbol()`),
>   então ecoar o símbolo na saída da estratégia seria redundante e abriria risco de divergência.
> - `targetTransactionId` (alvo de `SHOULD_CANCEL`, T32), `strategyName`, `timestamp` e `metadata` são campos
>   operacionais adicionados além do design original.

**Problemas identificados no modelo atual:**
1. **God Aggregate:** Portfolio acumula gestão financeira (Balance), execução (Transaction/TransactionMatch), configuração de estratégia (`strategyId`, `symbol`) e posição calculada.
2. **Asset VO com 3 colunas:** Cada campo Asset persiste `amount`, `currency` e `type` — totalizando 18 colunas apenas para os 6 campos Asset de uma Transaction.
3. **Position 1:1 com Portfolio:** Não suporta múltiplas posições simultâneas (Hedging).
4. **TransactionMatch sem PK próprio:** PK composta (`buyTransactionId`, `sellTransactionId`) dificulta referenciação e não permite múltiplos matches parciais entre o mesmo par.
5. **Sem Fee VO dedicado:** Fee é um Asset genérico sem campos específicos (tipo da fee, ativo original vs convertido).
6. **Sem StrategyRunner:** Toda lógica de lifecycle, policies e reconciliação está acoplada ao Portfolio.

### 3.2 Estado Alvo: Agregado Portfolio

O Portfolio no modelo alvo é o **mestre financeiro** — responsável exclusivamente pelo `GlobalBalance`, alocação de margem, roteamento de eventos e defesa de capital. Campos operacionais (`strategyId`, `symbol`, etc.) migram para o StrategyRunner.

> **Referência:** Blueprint Seção 2.A, 7.A, 9.1, 9.2.C, 11.1.

#### 3.2.1 Portfolio (Aggregate Root)

| Campo                | Tipo                 | Constraint               | Origem  | Notas                                                                                                                              |
|----------------------|----------------------|--------------------------|---------|------------------------------------------------------------------------------------------------------------------------------------|
| `id`                 | `UUID`               | PK                       | KEEP    | —                                                                                                                                  |
| `name`               | `String`             | UNIQUE, NOT NULL         | KEEP    | Nome legível do portfolio                                                                                                          |
| `isActive`           | `boolean`            | NOT NULL, DEFAULT true   | KEEP    | Controle de ativação geral                                                                                                         |
| `createdAt`          | `Instant`            | NOT NULL                 | KEEP    | —                                                                                                                                  |
| `safeModeStatus`     | `SafeModeStatus`     | NOT NULL, DEFAULT NORMAL | NEW     | Nível ativo do Safe Mode (NORMAL, HALT, CANCEL_ALL, PANIC_SELL). Persistido para sobreviver a restarts (Blueprint 11.1.B.1)        |
| `capitalPoolingMode` | `CapitalPoolingMode` | NOT NULL, DEFAULT SHARED | NEW     | Modo de visibilidade de capital: SHARED (todos competem pelo saldo) ou DEDICATED (fatias reservadas por Runner) (Blueprint 11.2.C) |
| `version`            | `Long`               | Optimistic Lock          | KEEP    | —                                                                                                                                  |

**Campos removidos (migram para StrategyRunner):**

| Campo Removido                  | Destino                        | Justificativa                                      |
|---------------------------------|--------------------------------|----------------------------------------------------|
| `strategyId`                    | `StrategyRunner.strategyId`    | A estratégia pertence ao Runner, não ao Portfolio  |
| `strategyName`                  | `StrategyRunner.strategyName`  | Idem                                               |
| `symbol`                        | `StrategyRunner.symbol`        | O ativo operado é configuração do Runner           |
| `orderExecutionExchange`        | `StrategyRunner.exchangeId`    | A exchange de execução é por Runner                |
| `initialCapitalAmount/Currency` | `GlobalBalance.initialCapital` | Migra para sub-entidade financeira                 |
| `allowedMarketDataSources`      | `StrategyRunner` (config)      | Fontes de dados são contexto operacional do Runner |

#### 3.2.2 GlobalBalance (substitui Balance)

> **Estado (T39, 2026-10-16):** Semântica real: `confirmExecution` faz `reserved −= custo`, `available += custo + pnl` e `realized += pnl`. O realizado é um contador **já contido** em `available`; não existe débito de dust.

O `GlobalBalance` é um **atributo do Portfolio** (1:1) que substitui o `Balance` atual. A principal mudança é a decomposição de `invested` em `reservedBalance` (capital congelado para ordens em voo) e o rastreio explícito de fees pagas.

| Campo               | Tipo         | Constraint          | Origem                                        | Notas                                                                       |
|---------------------|--------------|---------------------|-----------------------------------------------|-----------------------------------------------------------------------------|
| `portfolioId`       | `UUID`       | PK, FK → Portfolio  | KEEP                                          | —                                                                           |
| `availableBalance`  | `BigDecimal` | NOT NULL            | RENAME (`availableAmount`)                    | Saldo livre para novas operações. Moeda implícita = moeda base do Portfolio |
| `reservedBalance`   | `BigDecimal` | NOT NULL, DEFAULT 0 | NEW (split de `invested`)                     | Capital congelado para ordens PENDING/SUBMITTED. Antes era `investedAmount` |
| `realizedBalance`   | `BigDecimal` | NOT NULL, DEFAULT 0 | RENAME (`realizedPnL`)                        | PnL acumulado de todas as operações fechadas de todos os Runners            |
| `initialCapital`    | `BigDecimal` | NOT NULL            | MOVE (de `portfolios.initialCapitalAmount`)   | Capital inicial depositado                                                  |
| `baseCurrency`      | `String(20)` | NOT NULL            | MOVE (de `portfolios.initialCapitalCurrency`) | Moeda base do portfolio (ex: USDT)                                          |
| `totalFeesPaid`     | `BigDecimal` | NOT NULL, DEFAULT 0 | NEW                                           | Rastreio de fees acumuladas de todos os Runners (Blueprint 9.1)             |
| `lastExecutionTime` | `Instant`    | NULLABLE            | KEEP                                          | Último trade executado                                                      |
| `updatedAt`         | `Instant`    | NOT NULL            | KEEP                                          | —                                                                           |
| `version`           | `Long`       | Optimistic Lock     | KEEP                                          | —                                                                           |

**Decisão de simplificação — Moeda implícita:** No modelo atual, cada campo Asset persiste `amount + currency + type` (3 colunas). No modelo alvo, o `GlobalBalance` opera em **moeda única** (a `baseCurrency`). Os campos de saldo (`availableBalance`, `reservedBalance`, etc.) são `BigDecimal` puro, eliminando a redundância de currency/type por campo. A moeda é inferida do campo `baseCurrency`.

**Semântica dos campos:**
- `availableBalance`: Poder de compra real — `Total - Reserved - DustDebits`
- `reservedBalance`: Margem "congelada" para ordens em voo. Cresce no `Capital Request`, decresce na confirmação de execução (→ `realizedBalance`) ou no estorno (→ `availableBalance`)
- `realizedBalance`: PnL líquido acumulado. Após cada `TransactionMatch`, a margem correspondente é convertida de Reserved → Realized, com fees deduzidas
- `totalFeesPaid`: Soma incremental de todas as fees (já convertidas para `baseCurrency`) — métrica de eficiência, não afeta o saldo diretamente

#### 3.2.3 MarginAccount (Nova — 1:N com Portfolio)

> **Estado (T39, 2026-10-16):** **Descartada na v1.** A classe existe sem repositório nem uso; a exposição por runner é calculada por consulta (`RunnerExposureService`).

O `MarginAccount` controla o capital reservado por exchange/sub-conta. Permite que um Portfolio opere em múltiplas exchanges simultaneamente, cada uma com sua própria contabilidade de margem.

> **Referência:** Blueprint Seção 3, 7.A.

| Campo             | Tipo         | Constraint               | Notas                                              |
|-------------------|--------------|--------------------------|----------------------------------------------------|
| `id`              | `UUID`       | PK                       | —                                                  |
| `portfolioId`     | `UUID`       | FK → Portfolio, NOT NULL | —                                                  |
| `exchangeId`      | `String(50)` | NOT NULL                 | Identificador da exchange (ex: BINANCE, OKX)       |
| `reservedCapital` | `BigDecimal` | NOT NULL, DEFAULT 0      | Total de margem alocada para ordens desta exchange |
| `totalFeesPaid`   | `BigDecimal` | NOT NULL, DEFAULT 0      | Fees acumuladas nesta exchange                     |
| `isActive`        | `boolean`    | NOT NULL, DEFAULT true   | —                                                  |
| `createdAt`       | `Instant`    | NOT NULL                 | —                                                  |
| `updatedAt`       | `Instant`    | NOT NULL                 | —                                                  |
| `version`         | `Long`       | Optimistic Lock          | —                                                  |

**Constraint de unicidade:** `UNIQUE(portfolioId, exchangeId)` — no máximo uma conta por exchange por portfolio.

#### 3.2.4 DustAccount (Sub-entidade do Portfolio — Nova)

> **Estado (T39, 2026-10-16):** **Descartada na v1.** Sem repositório. O resíduo invendável é baixado como perda no PnL (T46); fee em terceiro ativo é só logada.

A `DustAccount` é o receptor universal de débitos não resolvidos e resíduos operacionais. Seus valores são **excluídos** do `AvailableBalance`, mantendo o capital de giro "limpo".

> **Referência:** Blueprint Seção 9.2.C.

| Campo             | Tipo             | Constraint                    | Notas                                                             |
|-------------------|------------------|-------------------------------|-------------------------------------------------------------------|
| `id`              | `UUID`           | PK                            | —                                                                 |
| `portfolioId`     | `UUID`           | FK → Portfolio, NOT NULL      | —                                                                 |
| `runnerId`        | `UUID`           | FK → StrategyRunner, NULLABLE | Runner que gerou o resíduo (nullable para dust do Portfolio)      |
| `sourceType`      | `DustSourceType` | NOT NULL                      | ROUNDING_DUST ou TECHNICAL_DEBT                                   |
| `originalAsset`   | `String(20)`     | NOT NULL                      | Ativo original do débito (ex: BNB, BTC)                           |
| `originalAmount`  | `BigDecimal`     | NOT NULL                      | Valor no ativo original                                           |
| `convertedAmount` | `BigDecimal`     | NULLABLE                      | Valor convertido para baseCurrency (null = pendente de conversão) |
| `transactionId`   | `UUID`           | FK → Transaction, NULLABLE    | Transação que originou o resíduo                                  |
| `isResolved`      | `boolean`        | NOT NULL, DEFAULT false       | Se já foi processado pelo Worker de Sweep                         |
| `resolvedAt`      | `Instant`        | NULLABLE                      | —                                                                 |
| `createdAt`       | `Instant`        | NOT NULL                      | Timestamp da transação original (para conversão histórica)        |

**Fontes de entrada (DustSourceType):**
1. **ROUNDING_DUST:** Resíduo de arredondamento quando a quantidade restante de um lote < `minQty` da exchange
2. **TECHNICAL_DEBT:** Falha na conversão de Fee cross-currency (Fallback Crítico — Blueprint 9.1)

#### 3.2.5 DeadLetterEntry (DLQ — Nova)

Captura execuções órfãs, IDs inválidos ou transações irreconciliáveis para intervenção manual.

> **Referência:** Blueprint Seção 4.D, 10.3.D.

| Campo             | Tipo          | Constraint               | Notas                                                                   |
|-------------------|---------------|--------------------------|-------------------------------------------------------------------------|
| `id`              | `UUID`        | PK                       | —                                                                       |
| `portfolioId`     | `UUID`        | FK → Portfolio, NOT NULL | —                                                                       |
| `clientOrderId`   | `String(255)` | NULLABLE                 | ID da ordem (pode ser malformado)                                       |
| `exchangeOrderId` | `String(255)` | NULLABLE                 | ID retornado pela exchange                                              |
| `rawPayload`      | `TEXT`        | NOT NULL                 | JSON do callback original da exchange                                   |
| `reason`          | `DlqReason`   | NOT NULL                 | UNKNOWN_RUNNER, INVALID_FORMAT, RECONCILIATION_CONFLICT, UNKNOWN_SYMBOL |
| `isResolved`      | `boolean`     | NOT NULL, DEFAULT false  | —                                                                       |
| `resolvedBy`      | `String`      | NULLABLE                 | Operador que resolveu                                                   |
| `resolvedAt`      | `Instant`     | NULLABLE                 | —                                                                       |
| `createdAt`       | `Instant`     | NOT NULL                 | —                                                                       |

### 3.3 Estado Alvo: Agregado StrategyRunner

O StrategyRunner é o **mestre operacional** de uma estratégia específica. Ele gerencia o ciclo de vida das ordens, a contabilidade de posições e o matching de transações. Cada Runner opera um único símbolo em uma única exchange.

> **Referência:** Blueprint Seção 2.B, 7.B, 12, 13.

#### 3.3.1 StrategyRunner (Aggregate Root — NOVO)

| Campo                      | Tipo                   | Constraint                | Notas                                                                                                   |
|----------------------------|------------------------|---------------------------|---------------------------------------------------------------------------------------------------------|
| `id`                       | `UUID`                 | PK                        | —                                                                                                       |
| `portfolioId`              | `UUID`                 | FK → Portfolio, NOT NULL  | Portfolio proprietário                                                                                  |
| `shortCode`                | `String(4)`            | NOT NULL, UNIQUE          | Código curto para `clientOrderId` (ex: "01f", "a2b"). Usado no prefixo `{r}{shortCode}` para roteamento |
| `strategyId`               | `UUID`                 | NOT NULL                  | MOVE (de `portfolios.strategyId`)                                                                       |
| `strategyName`             | `String(255)`          | NOT NULL                  | MOVE (de `portfolios.strategyName`)                                                                     |
| `symbol`                   | `String(50)`           | NOT NULL                  | MOVE (de `portfolios.symbol`). Par de trading (ex: BTCUSDT)                                             |
| `exchangeId`               | `String(50)`           | NOT NULL                  | MOVE (de `portfolios.orderExecutionExchange`). Exchange para execução                                   |
| `status`                   | `RunnerStatus`         | NOT NULL, DEFAULT CREATED | Lifecycle: CREATED → INITIALIZING → ACTIVE → HALTED → TERMINATING → ARCHIVED                            |
| `executionPolicy`          | `ExecutionPolicy`      | NOT NULL                  | Regra de entrada: SINGLE, HEDGING, NETTING (Blueprint 2.B.1)                                            |
| `accountingPolicyType`     | `AccountingPolicyType` | NOT NULL, DEFAULT FIFO    | Regra contábil: FIFO, LIFO, SPECIFIC_MATCH (Blueprint 2.B.2)                                            |
| `maxAllocationPercent`     | `BigDecimal`           | NOT NULL                  | Teto máximo de capital como % do GlobalBalance (Blueprint 11.2.B)                                       |
| `maxOpenPositions`         | `Integer`              | NOT NULL, DEFAULT 1       | Número máximo de posições abertas simultâneas                                                           |
| `maxPendingOrders`         | `Integer`              | NOT NULL, DEFAULT 1       | Número máximo de ordens SUBMITTED simultâneas                                                           |
| `dedicatedBudget`          | `BigDecimal`           | NULLABLE                  | NEW | Fatia fixa do saldo reservada para este Runner em modo DEDICATED. Null em modo SHARED (Blueprint 11.2.C) |
| `isReconciling`            | `boolean`              | NOT NULL, DEFAULT false   | Flag de Boot Sequence — rejeita sinais enquanto true (Blueprint 6.D)                                    |
| `allowedMarketDataSources` | `Set<String>`          | —                         | MOVE (de `portfolio_market_data_sources`)                                                               |
| `createdAt`                | `Instant`              | NOT NULL                  | —                                                                                                       |
| `lastReconciliationAt`     | `Instant`              | NULLABLE                  | Timestamp da última reconciliação bem-sucedida — cutoff para Boot Sequence (Seção 10.5)                 |
| `archivedAt`               | `Instant`              | NULLABLE                  | Soft delete — nunca remover fisicamente (Blueprint 13.C, Nota #27)                                      |
| `version`                  | `Long`                 | Optimistic Lock           | —                                                                                                       |

**Constraint de unicidade:** `UNIQUE(strategyId, symbol, exchangeId, portfolioId)` onde `status NOT IN (ARCHIVED, TERMINATING)` — impede Runners duplicados ativos para a mesma combinação (Blueprint 13.B).

#### 3.3.2 Position (Refatorada)

A Position deixa de ser 1:1 com Portfolio e passa a ser 1:N com StrategyRunner. Ganha campo `status` para suportar lifecycle explícito e `realizedPnl` para rastreio de PnL por posição.

> **Referência:** Blueprint Seção 3, 7.B, 9.3.

| Campo          | Tipo             | Constraint                    | Origem                                       | Notas                                                           |
|----------------|------------------|-------------------------------|----------------------------------------------|-----------------------------------------------------------------|
| `id`           | `UUID`           | PK                            | NEW                                          | Antes a PK era `portfolioId` (1:1); agora é PK próprio (1:N)    |
| `runnerId`     | `UUID`           | FK → StrategyRunner, NOT NULL | CHANGE (era `portfolioId`)                   | Posição pertence ao Runner, não ao Portfolio                    |
| `symbol`       | `String(50)`     | NOT NULL                      | KEEP                                         | Par de trading                                                  |
| `status`       | `PositionStatus` | NOT NULL, DEFAULT OPEN        | NEW                                          | OPEN, CLOSING, CLOSED. Permite controle de lifecycle            |
| `quantity`     | `BigDecimal`     | NOT NULL                      | SIMPLIFY (era `quantityAmount` + 2 cols)     | Quantidade total detida. Moeda inferida do symbol               |
| `averagePrice` | `BigDecimal`     | NOT NULL                      | SIMPLIFY (era `averagePriceAmount` + 2 cols) | Preço médio ponderado (WAP). Campo persistido (Blueprint 9.3.C) |
| `currentPrice` | `BigDecimal`     | NULLABLE                      | SIMPLIFY (era `currentPriceAmount` + 2 cols) | Último preço de mercado                                         |
| `realizedPnl`  | `BigDecimal`     | NOT NULL, DEFAULT 0           | NEW                                          | PnL realizado acumulado desta posição (líquido de fees)         |
| `openedAt`     | `Instant`        | NOT NULL                      | KEEP                                         | —                                                               |
| `closedAt`     | `Instant`        | NULLABLE                      | NEW                                          | Timestamp de fechamento (quando quantity → 0)                   |
| `lockedByTransactionId` | `UUID` | NULLABLE, FK → Transaction    | NEW                                          | FK para transação de venda que reivindica este lote. NULL = disponível (Seção 9.3) |
| `lockedQuantity` | `BigDecimal`   | NULLABLE                      | NEW                                          | Quantidade reservada para a venda. ≤ `quantity` disponível (Seção 9.3) |
| `lockedAt`     | `Instant`        | NULLABLE                      | NEW                                          | Timestamp do lock — observabilidade/debug (Seção 9.3)           |
| `updatedAt`    | `Instant`        | NOT NULL                      | KEEP                                         | —                                                               |
| `version`      | `Long`           | Optimistic Lock               | KEEP                                         | —                                                               |

**Simplificação Asset → BigDecimal:** Os campos que antes usavam o padrão `amount + currency + type` (3 colunas) passam a ser `BigDecimal` simples. A moeda é inferida do `symbol` do Runner: para BTCUSDT, `quantity` é em BTC e `averagePrice`/`currentPrice` são em USDT.

#### 3.3.3 Transaction (Refatorada)

A Transaction migra do Portfolio para o StrategyRunner. Principais mudanças: FK → Runner, novos campos de exchange, e simplificação de Asset (3 colunas → 1 coluna BigDecimal).

> **Referência:** Blueprint Seção 4, 6.A, 10.1.

| Campo              | Tipo                | Constraint                    | Origem                     | Notas                                                                            |
|--------------------|---------------------|-------------------------------|----------------------------|----------------------------------------------------------------------------------|
| `id`               | `UUID`              | PK                            | KEEP                       | —                                                                                |
| `runnerId`         | `UUID`              | FK → StrategyRunner, NOT NULL | CHANGE (era `portfolioId`) | —                                                                                |
| `clientOrderId`    | `String(255)`       | UNIQUE, NOT NULL              | KEEP                       | Chave de idempotência. Formato: `v1r{shortCode}t{ts}s{seq}{type}_{uuid}`         |
| `exchangeOrderId`  | `String(255)`       | NULLABLE                      | NEW                        | ID atribuído pela exchange após aceite da ordem. Null se crash antes do dispatch |
| `status`           | `TransactionStatus` | NOT NULL                      | KEEP                       | PENDING, SUBMITTED, PARTIAL, FILLED, CANCELED, EXPIRED, REJECTED                 |
| `type`             | `TransactionType`   | NOT NULL                      | KEEP                       | BUY, SELL                                                                        |
| `symbol`           | `String(50)`        | NOT NULL                      | KEEP                       | —                                                                                |
| `quantity`         | `BigDecimal`        | NOT NULL                      | SIMPLIFY (era 3 cols)      | Quantidade solicitada                                                            |
| `executedQuantity` | `BigDecimal`        | NULLABLE                      | SIMPLIFY (era 3 cols)      | Quantidade efetivamente executada                                                |
| `price`            | `BigDecimal`        | NOT NULL                      | SIMPLIFY (era 3 cols)      | Preço solicitado/estimado                                                        |
| `executedPrice`    | `BigDecimal`        | NULLABLE                      | SIMPLIFY (era 3 cols)      | Preço real de execução                                                           |
| `total`            | `BigDecimal`        | NOT NULL                      | SIMPLIFY (era 3 cols)      | Valor total estimado (quantity × price)                                          |
| `confidence`       | `BigDecimal`        | NULLABLE                      | NEW                        | Confiança do sinal da estratégia (0.0-1.0). Para auditoria (Blueprint 4.A)       |
| `reasoning`        | `TEXT`              | NULLABLE                      | NEW                        | Motivação da estratégia. Para auditoria (Blueprint 4.A)                          |
| `targetLotId`      | `UUID`              | FK → Position, NULLABLE       | KEEP                       | Para SELL: posição específica a fechar. Null = AccountingPolicy decide           |
| `requestedAt`      | `Instant`           | NOT NULL                      | KEEP                       | —                                                                                |
| `executedAt`       | `Instant`           | NULLABLE                      | KEEP                       | —                                                                                |
| `rejectReason`     | `TEXT`              | NULLABLE                      | KEEP                       | —                                                                                |
| `version`          | `Long`              | Optimistic Lock               | KEEP                       | —                                                                                |

**Campos removidos:**
- `fee` (3 colunas: `feeAmount`, `feeCurrency`, `feeAssetType`): Migra para o VO `Fee` embutido no `TransactionMatch`. Fees são registradas apenas onde há execução real.
- `portfolioId`: Substituído por `runnerId`

#### 3.3.4 TransactionMatch (Enriquecida)

> **Estado (T39, 2026-10-16):** A unicidade `(buyTransactionId, sellTransactionId)` foi removida na V5: cada fill gera seu próprio match.

O TransactionMatch ganha PK próprio, Fee VO embutido, PnL realizado e preços denormalizados para permitir cálculos rápidos sem JOINs.

> **Referência:** Blueprint Seção 4.D, 9.1, 9.3.

| Campo                | Tipo         | Constraint                    | Origem             | Notas                                                                              |
|----------------------|--------------|-------------------------------|--------------------|------------------------------------------------------------------------------------|
| `id`                 | `UUID`       | PK                            | NEW                | Substitui a PK composta. Permite referenciação direta                              |
| `runnerId`           | `UUID`       | FK → StrategyRunner, NOT NULL | NEW                | Para queries eficientes por Runner                                                 |
| `buyTransactionId`   | `UUID`       | FK → Transaction, NOT NULL    | KEEP               | —                                                                                  |
| `sellTransactionId`  | `UUID`       | FK → Transaction, NOT NULL    | KEEP               | —                                                                                  |
| `matchedQuantity`    | `BigDecimal` | NOT NULL, CHECK > 0           | KEEP               | Quantidade casada neste match                                                      |
| `buyPrice`           | `BigDecimal` | NOT NULL                      | NEW (denormalized) | Preço de execução da compra no momento do match                                    |
| `sellPrice`          | `BigDecimal` | NOT NULL                      | NEW (denormalized) | Preço de execução da venda no momento do match                                     |
| `feeAmount`          | `BigDecimal` | NOT NULL, DEFAULT 0           | NEW (VO Fee)       | Valor da fee neste match                                                           |
| `feeAsset`           | `String(20)` | NOT NULL                      | NEW (VO Fee)       | Ativo da fee (pode diferir do par — ex: BNB)                                       |
| `feeType`            | `FeeType`    | NOT NULL                      | NEW (VO Fee)       | MAKER, TAKER, UNKNOWN                                                              |
| `feeConvertedAmount` | `BigDecimal` | NULLABLE                      | NEW (VO Fee)       | Fee convertida para baseCurrency do Portfolio. Null = mesma moeda                  |
| `pnlRealized`        | `BigDecimal` | NOT NULL                      | NEW                | PnL líquido deste match: `(sellPrice - buyPrice) × matchedQuantity - feeConverted` |
| `createdAt`          | `Instant`    | NOT NULL                      | KEEP               | —                                                                                  |

**Constraint de unicidade:** `UNIQUE(buyTransactionId, sellTransactionId)` — mantém a integridade de matching, mas sem ser PK.

### 3.4 Value Objects Compartilhados

#### 3.4.1 ClientOrderId

Chave única de roteamento e idempotência, gerada pelo StrategyRunner antes do dispatch.

> **Referência:** Blueprint Seção 4.C, Nota de Implementação #2.

**Formato:** `v{version}r{runner_short}t{timestamp}s{sequence}{type}_{transaction_uuid}`

| Componente            | Tamanho   | Exemplo             | Descrição                                                                                   |
|-----------------------|-----------|---------------------|---------------------------------------------------------------------------------------------|
| `v{version}`          | 2 chars   | `v1`                | Prefixo de versão do layout. Permite evolução do formato sem quebrar parsing de IDs antigos |
| `r{runner_short}`     | 3-5 chars | `r01f`              | ShortCode do StrategyRunner. Permite roteamento sem consulta ao DB                          |
| `t{timestamp}`        | 14 chars  | `t1700000000000`    | Timestamp em millis (epoch). Para ordenação temporal                                        |
| `s{sequence}`         | 4 chars   | `s001`              | Sequência dentro do mesmo milissegundo. Evita colisão                                       |
| `{type}`              | 1 char    | `N`                 | Tipo: `N` (New/Buy), `S` (Sell), `C` (Cancel)                                               |
| `_{transaction_uuid}` | 17 chars  | `_8da233214f11b12a` | UUID truncado da Transaction. Elo imutável entre DB e Exchange                              |

**Tamanho total:** ~36 caracteres (compatível com o limite de 36 chars da maioria das exchanges).

**Métodos do VO:**
- `generate(shortCode, transactionId, type)` → `ClientOrderId`
- `parse(rawString)` → extrai `version`, `runnerShort`, `timestamp`, `sequence`, `type`, `transactionUuid`
- `getRunnerShort()` → usado pelo Portfolio para roteamento de callbacks (Blueprint 4.C)
- `getTransactionUuid()` → usado para reconciliação com o DB

**Regra de validação no parsing (Blueprint 4.C):** Antes de interpretar qualquer campo, o parser deve extrair e validar o prefixo de versão. Se a versão for desconhecida, abortar o processamento e encaminhar para a DLQ.

#### 3.4.2 Fee (Value Object embutido no TransactionMatch)

> **Estado (T39, 2026-10-16):** **Conversão cross-currency descartada na v1** (T46): fee em terceiro ativo não entra no PnL e gera log ERROR; o runbook exige BNB desligado.

Representa o custo operacional imutável de uma execução.

> **Referência:** Blueprint Seção 9.1.

| Campo             | Tipo                    | Descrição                                                           |
|-------------------|-------------------------|---------------------------------------------------------------------|
| `amount`          | `BigDecimal`            | Valor cobrado pela exchange                                         |
| `asset`           | `String`                | Ativo da cobrança (ex: BNB, USDT, BTC)                              |
| `type`            | `FeeType`               | MAKER, TAKER, UNKNOWN                                               |
| `convertedAmount` | `BigDecimal` (nullable) | Valor convertido para baseCurrency. Null se `asset == baseCurrency` |

**Regra de conversão cross-currency (Blueprint 9.1):**
1. Se `fee.asset == portfolio.baseCurrency`: `convertedAmount = amount` (conversão trivial)
2. Se `fee.asset != portfolio.baseCurrency`: Portfolio consulta o Mark Price do ExchangeAdapter e converte no momento do TransactionMatch
3. Em caso de falha total na precificação: débito registrado na `DustAccount` como TECHNICAL_DEBT com o valor original

#### 3.4.3 PositionContext (Read-Only — Contrato da Strategy)

Objeto imutável injetado na Strategy pelo Runner, contendo o estado operacional necessário para decisão.

> **Referência:** Blueprint Seção 9.3.D.

| Campo            | Tipo                    | Descrição                                         |
|------------------|-------------------------|---------------------------------------------------|
| `positionId`     | `UUID`                  | ID da posição (nullable se não há posição aberta) |
| `symbol`         | `String`                | Par de trading                                    |
| `quantity`       | `BigDecimal`            | Quantidade detida                                 |
| `averagePrice`   | `BigDecimal`            | Preço médio ponderado (WAP)                       |
| `currentPrice`   | `BigDecimal`            | Último preço de mercado                           |
| `unrealizedPnl`  | `BigDecimal`            | PnL não realizado (calculado)                     |
| `realizedPnl`    | `BigDecimal`            | PnL realizado acumulado                           |
| `openBuyEntries` | `List<OpenBuyEntryDto>` | Lotes de compra disponíveis (para targeting)      |

### 3.5 Definição de Enums

#### Enums Existentes (Mantidos)

| Enum                | Valores                                                                        | Package Atual      | Notas                                                                                    |
|---------------------|--------------------------------------------------------------------------------|--------------------|------------------------------------------------------------------------------------------|
| `TransactionStatus` | `PENDING`, `SUBMITTED`, `PARTIAL`, `FILLED`, `CANCELED`, `EXPIRED`, `REJECTED` | `domain.portfolio` | Sem alteração. `PARTIALLY_FILLED` renomeado para `PARTIAL` conforme Blueprint 6.A        |
| `TransactionType`   | `BUY`, `SELL`                                                                  | `domain.portfolio` | Sem alteração                                                                            |
| `TradingAction`     | `SHOULD_BUY`, `SHOULD_SELL`, `SHOULD_HOLD`                                     | `domain.portfolio` | Sem alteração                                                                            |
| `AssetType`         | `FIAT`, `STABLECOIN`, `CRYPTO`                                                 | `domain.portfolio` | Mantido para classificação em `TradingAssetsConfig`. Removido das colunas de Transaction |

#### Enums Novos

| Enum                   | Valores                                                                         | Propósito                                                                | Referência (Blueprint)   |
|------------------------|---------------------------------------------------------------------------------|--------------------------------------------------------------------------|--------------------------|
| `RunnerStatus`         | `CREATED`, `INITIALIZING`, `ACTIVE`, `HALTED`, `TERMINATING`, `ARCHIVED`        | Ciclo de vida do StrategyRunner                                          | Seção 13.A               |
| `PositionStatus`       | `OPEN`, `CLOSING`, `CLOSED`                                                     | Estado da posição. CLOSING = há ordens de venda em voo para esta posição | Seção 3, 7.B             |
| `ExecutionPolicy`      | `SINGLE`, `HEDGING`, `NETTING`                                                  | Regra de entrada — como o Runner reage a novos sinais                    | Seção 2.B.1              |
| `AccountingPolicyType` | `FIFO`, `LIFO`, `SPECIFIC_MATCH`                                                | Regra contábil — como vendas são casadas com compras                     | Seção 2.B.2              |
| `SafeModeStatus`       | `NORMAL`, `HALT`, `CANCEL_ALL`, `PANIC_SELL`                                    | Níveis escaláveis do Safe Mode do Portfolio                              | Seção 11.1.B.1           |
| `CapitalPoolingMode`   | `SHARED`, `DEDICATED`                                                           | Modo de visibilidade de capital entre Runners                            | Seção 11.2.C             |
| `FeeType`              | `MAKER`, `TAKER`, `UNKNOWN`                                                     | Tipo da fee cobrada pela exchange                                        | Seção 9.1                |
| `DustSourceType`       | `ROUNDING_DUST`, `TECHNICAL_DEBT`                                               | Origem do resíduo na DustAccount                                         | Seção 9.2.C              |
| `DlqReason`            | `UNKNOWN_RUNNER`, `INVALID_FORMAT`, `RECONCILIATION_CONFLICT`, `UNKNOWN_SYMBOL` | Motivo do encaminhamento para DLQ                                        | Seção 4.D, 10.3.D        |

### 3.6 Schema Alvo (Conceitual)

Tabelas conceituais com campos, tipos e constraints para cada entidade alvo. Formato markdown — sem DDL SQL — para manter flexibilidade durante a implementação.

#### Tabela: `portfolios`

| Coluna                 | Tipo                       | Constraint                 |
|------------------------|----------------------------|----------------------------|
| `id`                   | `UUID`                     | PK                         |
| `name`                 | `VARCHAR(255)`             | UNIQUE, NOT NULL           |
| `is_active`            | `BOOLEAN`                  | NOT NULL, DEFAULT TRUE     |
| `safe_mode_status`     | `VARCHAR(20)`              | NOT NULL, DEFAULT 'NORMAL' |
| `capital_pooling_mode` | `VARCHAR(20)`              | NOT NULL, DEFAULT 'SHARED' |
| `created_at`           | `TIMESTAMP WITH TIME ZONE` | NOT NULL                   |
| `version`              | `BIGINT`                   | DEFAULT 0                  |

**Indexes:** `idx_portfolios_is_active(is_active)`

#### Tabela: `global_balances` (renomeia `portfolio_balances`)

| Coluna                | Tipo                       | Constraint                |
|-----------------------|----------------------------|---------------------------|
| `portfolio_id`        | `UUID`                     | PK, FK → `portfolios(id)` |
| `available_balance`   | `NUMERIC(30,8)`            | NOT NULL                  |
| `reserved_balance`    | `NUMERIC(30,8)`            | NOT NULL, DEFAULT 0       |
| `realized_balance`    | `NUMERIC(30,8)`            | NOT NULL, DEFAULT 0       |
| `initial_capital`     | `NUMERIC(30,8)`            | NOT NULL                  |
| `base_currency`       | `VARCHAR(20)`              | NOT NULL                  |
| `total_fees_paid`     | `NUMERIC(30,8)`            | NOT NULL, DEFAULT 0       |
| `last_execution_time` | `TIMESTAMP WITH TIME ZONE` | NULLABLE                  |
| `updated_at`          | `TIMESTAMP WITH TIME ZONE` | NOT NULL                  |
| `version`             | `BIGINT`                   | DEFAULT 0                 |

#### Tabela: `margin_accounts`

| Coluna             | Tipo                       | Constraint                      |
|--------------------|----------------------------|---------------------------------|
| `id`               | `UUID`                     | PK                              |
| `portfolio_id`     | `UUID`                     | FK → `portfolios(id)`, NOT NULL |
| `exchange_id`      | `VARCHAR(50)`              | NOT NULL                        |
| `reserved_capital` | `NUMERIC(30,8)`            | NOT NULL, DEFAULT 0             |
| `total_fees_paid`  | `NUMERIC(30,8)`            | NOT NULL, DEFAULT 0             |
| `is_active`        | `BOOLEAN`                  | NOT NULL, DEFAULT TRUE          |
| `created_at`       | `TIMESTAMP WITH TIME ZONE` | NOT NULL                        |
| `updated_at`       | `TIMESTAMP WITH TIME ZONE` | NOT NULL                        |
| `version`          | `BIGINT`                   | DEFAULT 0                       |

**Indexes:** `UNIQUE(portfolio_id, exchange_id)`

#### Tabela: `dust_accounts`

| Coluna             | Tipo                       | Constraint                            |
|--------------------|----------------------------|---------------------------------------|
| `id`               | `UUID`                     | PK                                    |
| `portfolio_id`     | `UUID`                     | FK → `portfolios(id)`, NOT NULL       |
| `runner_id`        | `UUID`                     | FK → `strategy_runners(id)`, NULLABLE |
| `source_type`      | `VARCHAR(30)`              | NOT NULL                              |
| `original_asset`   | `VARCHAR(20)`              | NOT NULL                              |
| `original_amount`  | `NUMERIC(30,8)`            | NOT NULL                              |
| `converted_amount` | `NUMERIC(30,8)`            | NULLABLE                              |
| `transaction_id`   | `UUID`                     | FK → `transactions(id)`, NULLABLE     |
| `is_resolved`      | `BOOLEAN`                  | NOT NULL, DEFAULT FALSE               |
| `resolved_at`      | `TIMESTAMP WITH TIME ZONE` | NULLABLE                              |
| `created_at`       | `TIMESTAMP WITH TIME ZONE` | NOT NULL                              |

**Indexes:** `idx_dust_portfolio_unresolved(portfolio_id, is_resolved)`, `idx_dust_runner(runner_id)`

#### Tabela: `dead_letter_entries`

| Coluna              | Tipo                       | Constraint                      |
|---------------------|----------------------------|---------------------------------|
| `id`                | `UUID`                     | PK                              |
| `portfolio_id`      | `UUID`                     | FK → `portfolios(id)`, NOT NULL |
| `client_order_id`   | `VARCHAR(255)`             | NULLABLE                        |
| `exchange_order_id` | `VARCHAR(255)`             | NULLABLE                        |
| `raw_payload`       | `TEXT`                     | NOT NULL                        |
| `reason`            | `VARCHAR(50)`              | NOT NULL                        |
| `is_resolved`       | `BOOLEAN`                  | NOT NULL, DEFAULT FALSE         |
| `resolved_by`       | `VARCHAR(255)`             | NULLABLE                        |
| `resolved_at`       | `TIMESTAMP WITH TIME ZONE` | NULLABLE                        |
| `created_at`        | `TIMESTAMP WITH TIME ZONE` | NOT NULL                        |

**Indexes:** `idx_dlq_portfolio_unresolved(portfolio_id, is_resolved)`

#### Tabela: `strategy_runners`

| Coluna                   | Tipo                       | Constraint                      |
|--------------------------|----------------------------|---------------------------------|
| `id`                     | `UUID`                     | PK                              |
| `portfolio_id`           | `UUID`                     | FK → `portfolios(id)`, NOT NULL |
| `short_code`             | `VARCHAR(4)`               | UNIQUE, NOT NULL                |
| `strategy_id`            | `UUID`                     | NOT NULL                        |
| `strategy_name`          | `VARCHAR(255)`             | NOT NULL                        |
| `symbol`                 | `VARCHAR(50)`              | NOT NULL                        |
| `exchange_id`            | `VARCHAR(50)`              | NOT NULL                        |
| `status`                 | `VARCHAR(20)`              | NOT NULL, DEFAULT 'CREATED'     |
| `execution_policy`       | `VARCHAR(20)`              | NOT NULL                        |
| `accounting_policy_type` | `VARCHAR(20)`              | NOT NULL, DEFAULT 'FIFO'        |
| `max_allocation_percent` | `NUMERIC(5,4)`             | NOT NULL                        |
| `max_open_positions`     | `INTEGER`                  | NOT NULL, DEFAULT 1             |
| `max_pending_orders`     | `INTEGER`                  | NOT NULL, DEFAULT 1             |
| `dedicated_budget`       | `NUMERIC(30,8)`            | NULLABLE                        |
| `is_reconciling`         | `BOOLEAN`                  | NOT NULL, DEFAULT FALSE         |
| `created_at`             | `TIMESTAMP WITH TIME ZONE` | NOT NULL                        |
| `last_reconciliation_at` | `TIMESTAMP WITH TIME ZONE` | NULLABLE                        |
| `archived_at`            | `TIMESTAMP WITH TIME ZONE` | NULLABLE                        |
| `version`                | `BIGINT`                   | DEFAULT 0                       |

**Indexes:** `idx_runners_portfolio(portfolio_id)`, `idx_runners_status(status)`, `idx_runners_active_unique` = partial unique on `(strategy_id, symbol, exchange_id, portfolio_id)` WHERE `status NOT IN ('ARCHIVED', 'TERMINATING')`

#### Tabela: `runner_market_data_sources`

| Coluna      | Tipo          | Constraint                            |
|-------------|---------------|---------------------------------------|
| `runner_id` | `UUID`        | FK → `strategy_runners(id)`, NOT NULL |
| `source`    | `VARCHAR(50)` | NOT NULL                              |

**PK:** `(runner_id, source)`

#### Tabela: `positions` (refatorada)

| Coluna          | Tipo                       | Constraint                            |
|-----------------|----------------------------|---------------------------------------|
| `id`            | `UUID`                     | PK                                    |
| `runner_id`     | `UUID`                     | FK → `strategy_runners(id)`, NOT NULL |
| `symbol`        | `VARCHAR(50)`              | NOT NULL                              |
| `status`        | `VARCHAR(20)`              | NOT NULL, DEFAULT 'OPEN'              |
| `quantity`      | `NUMERIC(30,8)`            | NOT NULL                              |
| `average_price` | `NUMERIC(30,8)`            | NOT NULL                              |
| `current_price` | `NUMERIC(30,8)`            | NULLABLE                              |
| `realized_pnl`  | `NUMERIC(30,8)`            | NOT NULL, DEFAULT 0                   |
| `opened_at`     | `TIMESTAMP WITH TIME ZONE` | NOT NULL                              |
| `closed_at`     | `TIMESTAMP WITH TIME ZONE` | NULLABLE                              |
| `locked_by_transaction_id` | `UUID`          | NULLABLE, FK → `transactions(id)`    |
| `locked_quantity` | `NUMERIC(30,8)`          | NULLABLE                              |
| `locked_at`     | `TIMESTAMP WITH TIME ZONE` | NULLABLE                              |
| `updated_at`    | `TIMESTAMP WITH TIME ZONE` | NOT NULL                              |
| `version`       | `BIGINT`                   | DEFAULT 0                             |

**Indexes:** `idx_positions_runner(runner_id)`, `idx_positions_status(status)`, `idx_positions_runner_open(runner_id, status)` WHERE `status = 'OPEN'`, `idx_positions_locked_tx(locked_by_transaction_id)` WHERE `locked_by_transaction_id IS NOT NULL`

#### Tabela: `transactions` (refatorada)

| Coluna              | Tipo                       | Constraint                            |
|---------------------|----------------------------|---------------------------------------|
| `id`                | `UUID`                     | PK                                    |
| `runner_id`         | `UUID`                     | FK → `strategy_runners(id)`, NOT NULL |
| `client_order_id`   | `VARCHAR(255)`             | UNIQUE, NOT NULL                      |
| `exchange_order_id` | `VARCHAR(255)`             | NULLABLE                              |
| `status`            | `VARCHAR(50)`              | NOT NULL                              |
| `type`              | `VARCHAR(20)`              | NOT NULL                              |
| `symbol`            | `VARCHAR(50)`              | NOT NULL                              |
| `quantity`          | `NUMERIC(30,8)`            | NOT NULL                              |
| `executed_quantity` | `NUMERIC(30,8)`            | NULLABLE                              |
| `price`             | `NUMERIC(30,8)`            | NOT NULL                              |
| `executed_price`    | `NUMERIC(30,8)`            | NULLABLE                              |
| `total`             | `NUMERIC(30,8)`            | NOT NULL                              |
| `confidence`        | `NUMERIC(3,2)`             | NULLABLE                              |
| `reasoning`         | `TEXT`                     | NULLABLE                              |
| `target_lot_id`     | `UUID`                     | FK → `positions(id)`, NULLABLE        |
| `requested_at`      | `TIMESTAMP WITH TIME ZONE` | NOT NULL                              |
| `executed_at`       | `TIMESTAMP WITH TIME ZONE` | NULLABLE                              |
| `reject_reason`     | `TEXT`                     | NULLABLE                              |
| `version`           | `BIGINT`                   | DEFAULT 0                             |

**Indexes:** `idx_transactions_runner(runner_id)`, `idx_transactions_client_order_id(client_order_id)`, `idx_transactions_status(status)`, `idx_transactions_type(type)`, `idx_transactions_requested_at(requested_at)`, `idx_transactions_inflight(runner_id, status)` WHERE `status IN ('PENDING', 'SUBMITTED', 'PARTIAL')`

#### Tabela: `transaction_matches` (enriquecida)

| Coluna                 | Tipo                       | Constraint                            |
|------------------------|----------------------------|---------------------------------------|
| `id`                   | `UUID`                     | PK                                    |
| `runner_id`            | `UUID`                     | FK → `strategy_runners(id)`, NOT NULL |
| `buy_transaction_id`   | `UUID`                     | FK → `transactions(id)`, NOT NULL     |
| `sell_transaction_id`  | `UUID`                     | FK → `transactions(id)`, NOT NULL     |
| `matched_quantity`     | `NUMERIC(30,8)`            | NOT NULL, CHECK > 0                   |
| `buy_price`            | `NUMERIC(30,8)`            | NOT NULL                              |
| `sell_price`           | `NUMERIC(30,8)`            | NOT NULL                              |
| `fee_amount`           | `NUMERIC(30,8)`            | NOT NULL, DEFAULT 0                   |
| `fee_asset`            | `VARCHAR(20)`              | NOT NULL                              |
| `fee_type`             | `VARCHAR(20)`              | NOT NULL                              |
| `fee_converted_amount` | `NUMERIC(30,8)`            | NULLABLE                              |
| `pnl_realized`         | `NUMERIC(30,8)`            | NOT NULL                              |
| `created_at`           | `TIMESTAMP WITH TIME ZONE` | NOT NULL                              |

**Indexes:** `UNIQUE(buy_transaction_id, sell_transaction_id)`, `idx_matches_runner(runner_id)`, `idx_matches_buy(buy_transaction_id)`, `idx_matches_sell(sell_transaction_id)`

### 3.7 Matriz de Migração (Delta)

Mapeamento coluna-a-coluna de cada tabela atual para o estado alvo.

**Legenda de ações:**
- **KEEP** — Coluna mantida sem alteração
- **RENAME** — Coluna renomeada (mesmos dados)
- **MOVE** — Coluna migra para outra tabela
- **DROP** — Coluna removida (dados descartados ou absorvidos por outra coluna)
- **SIMPLIFY** — Coluna mantida com redução de colunas auxiliares (remove currency/type)
- **NEW** — Coluna nova (sem correspondência no schema atual)

#### `portfolios` → `portfolios`

| Coluna Atual               | Ação  | Coluna Alvo                         | Notas            |
|----------------------------|-------|-------------------------------------|------------------|
| `id`                       | KEEP  | `id`                                | —                |
| `name`                     | KEEP  | `name`                              | —                |
| `strategy_id`              | MOVE  | → `strategy_runners.strategy_id`    | —                |
| `strategy_name`            | MOVE  | → `strategy_runners.strategy_name`  | —                |
| `symbol`                   | MOVE  | → `strategy_runners.symbol`         | —                |
| `initial_capital_amount`   | MOVE  | → `global_balances.initial_capital` | —                |
| `initial_capital_currency` | MOVE  | → `global_balances.base_currency`   | —                |
| `order_execution_exchange` | MOVE  | → `strategy_runners.exchange_id`    | —                |
| `is_active`                | KEEP  | `is_active`                         | —                |
| `created_at`               | KEEP  | `created_at`                        | —                |
| `version`                  | KEEP  | `version`                           | —                |
| —                          | NEW   | `safe_mode_status`                  | DEFAULT 'NORMAL' |
| —                          | NEW   | `capital_pooling_mode`              | DEFAULT 'SHARED' |

#### `portfolio_market_data_sources` → `runner_market_data_sources`

| Coluna Atual   | Ação  | Coluna Alvo                              | Notas                            |
|----------------|-------|------------------------------------------|----------------------------------|
| `portfolio_id` | MOVE  | → `runner_market_data_sources.runner_id` | FK muda de Portfolio para Runner |
| `source`       | KEEP  | `source`                                 | —                                |

#### `portfolio_balances` → `global_balances`

| Coluna Atual          | Ação   | Coluna Alvo           | Notas                                                        |
|-----------------------|--------|-----------------------|--------------------------------------------------------------|
| `portfolio_id`        | KEEP   | `portfolio_id`        | —                                                            |
| `available_amount`    | RENAME | `available_balance`   | —                                                            |
| `available_currency`  | DROP   | —                     | Absorvido por `base_currency`                                |
| `invested_amount`     | RENAME | `reserved_balance`    | Semântica muda: "investido" → "reservado para ordens em voo" |
| `invested_currency`   | DROP   | —                     | Absorvido por `base_currency`                                |
| `realized_pnl`        | RENAME | `realized_balance`    | —                                                            |
| `last_execution_time` | KEEP   | `last_execution_time` | —                                                            |
| `updated_at`          | KEEP   | `updated_at`          | —                                                            |
| `version`             | KEEP   | `version`             | —                                                            |
| —                     | NEW    | `initial_capital`     | Migrado de `portfolios.initial_capital_amount`               |
| —                     | NEW    | `base_currency`       | Migrado de `portfolios.initial_capital_currency`             |
| —                     | NEW    | `total_fees_paid`     | DEFAULT 0                                                    |

#### `positions` → `positions`

| Coluna Atual             | Ação     | Coluna Alvo     | Notas                                             |
|--------------------------|----------|-----------------|---------------------------------------------------|
| `portfolio_id` (PK)      | DROP     | —               | Substituído por `id` (novo PK) e `runner_id` (FK) |
| `symbol`                 | KEEP     | `symbol`        | —                                                 |
| `quantity_amount`        | SIMPLIFY | `quantity`      | Remove colunas auxiliares                         |
| `quantity_currency`      | DROP     | —               | Inferido do `symbol`                              |
| `average_price_amount`   | SIMPLIFY | `average_price` | —                                                 |
| `average_price_currency` | DROP     | —               | Inferido do `symbol`                              |
| `current_price_amount`   | SIMPLIFY | `current_price` | —                                                 |
| `current_price_currency` | DROP     | —               | Inferido do `symbol`                              |
| `opened_at`              | KEEP     | `opened_at`     | —                                                 |
| `updated_at`             | KEEP     | `updated_at`    | —                                                 |
| `version`                | KEEP     | `version`       | —                                                 |
| —                        | NEW      | `id`            | Novo PK (UUID)                                    |
| —                        | NEW      | `runner_id`     | FK → `strategy_runners(id)`                       |
| —                        | NEW      | `status`        | DEFAULT 'OPEN'                                    |
| —                        | NEW      | `realized_pnl`  | DEFAULT 0                                         |
| —                        | NEW      | `closed_at`     | NULLABLE                                          |
| —                        | NEW      | `locked_by_transaction_id` | NULLABLE, FK → `transactions(id)` (Seção 9.3) |
| —                        | NEW      | `locked_quantity` | NULLABLE (Seção 9.3)                            |
| —                        | NEW      | `locked_at`     | NULLABLE (Seção 9.3)                              |

#### `transactions` → `transactions`

| Coluna Atual                 | Ação     | Coluna Alvo         | Notas                                                  |
|------------------------------|----------|---------------------|--------------------------------------------------------|
| `id`                         | KEEP     | `id`                | —                                                      |
| `portfolio_id`               | CHANGE   | `runner_id`         | FK muda de Portfolio para Runner                       |
| `client_order_id`            | KEEP     | `client_order_id`   | Adiciona UNIQUE constraint                             |
| `status`                     | KEEP     | `status`            | —                                                      |
| `type`                       | KEEP     | `type`              | —                                                      |
| `symbol`                     | KEEP     | `symbol`            | —                                                      |
| `quantity_amount`            | SIMPLIFY | `quantity`          | —                                                      |
| `quantity_currency`          | DROP     | —                   | Inferido do `symbol`                                   |
| `quantity_type`              | DROP     | —                   | Removido                                               |
| `executed_quantity_amount`   | SIMPLIFY | `executed_quantity` | —                                                      |
| `executed_quantity_currency` | DROP     | —                   | —                                                      |
| `executed_quantity_type`     | DROP     | —                   | —                                                      |
| `price_amount`               | SIMPLIFY | `price`             | —                                                      |
| `price_currency`             | DROP     | —                   | —                                                      |
| `price_type`                 | DROP     | —                   | —                                                      |
| `executed_price_amount`      | SIMPLIFY | `executed_price`    | —                                                      |
| `executed_price_currency`    | DROP     | —                   | —                                                      |
| `executed_price_type`        | DROP     | —                   | —                                                      |
| `total_amount`               | SIMPLIFY | `total`             | —                                                      |
| `total_currency`             | DROP     | —                   | —                                                      |
| `total_type`                 | DROP     | —                   | —                                                      |
| `fee_amount`                 | DROP     | —                   | Migra para `transaction_matches.fee_amount`            |
| `fee_currency`               | DROP     | —                   | Migra para `transaction_matches.fee_asset`             |
| `fee_type`                   | DROP     | —                   | Migra para `transaction_matches.fee_type`              |
| `requested_at`               | KEEP     | `requested_at`      | —                                                      |
| `executed_at`                | KEEP     | `executed_at`       | —                                                      |
| `reject_reason`              | KEEP     | `reject_reason`     | —                                                      |
| `target_lot_id`              | KEEP     | `target_lot_id`     | FK muda de → `transactions(id)` para → `positions(id)` |
| `version`                    | KEEP     | `version`           | —                                                      |
| —                            | NEW      | `exchange_order_id` | NULLABLE                                               |
| —                            | NEW      | `confidence`        | NULLABLE                                               |
| —                            | NEW      | `reasoning`         | NULLABLE                                               |

#### `transaction_matches` → `transaction_matches`

| Coluna Atual               | Ação  | Coluna Alvo            | Notas                                      |
|----------------------------|-------|------------------------|--------------------------------------------|
| `buy_transaction_id` (PK)  | KEEP  | `buy_transaction_id`   | Deixa de ser PK, passa a UNIQUE constraint |
| `sell_transaction_id` (PK) | KEEP  | `sell_transaction_id`  | Idem                                       |
| `matched_quantity`         | KEEP  | `matched_quantity`     | —                                          |
| `created_at`               | KEEP  | `created_at`           | —                                          |
| —                          | NEW   | `id`                   | Novo PK (UUID)                             |
| —                          | NEW   | `runner_id`            | FK → `strategy_runners(id)`                |
| —                          | NEW   | `buy_price`            | Denormalized                               |
| —                          | NEW   | `sell_price`           | Denormalized                               |
| —                          | NEW   | `fee_amount`           | DEFAULT 0                                  |
| —                          | NEW   | `fee_asset`            | —                                          |
| —                          | NEW   | `fee_type`             | —                                          |
| —                          | NEW   | `fee_converted_amount` | NULLABLE                                   |
| —                          | NEW   | `pnl_realized`         | —                                          |

### 3.8 Estratégia de Migração Flyway

Sequência incremental de migrations para transformar o schema atual (V3) no alvo sem perda de dados. Cada migration é atômica e revertível.

> **Princípio:** Todas as migrations utilizam a abordagem **additive-first** — primeiro adiciona colunas/tabelas novas, depois migra dados, por último remove colunas obsoletas. Isso permite rollback seguro em caso de falha.

#### V4 — Criar tabela `strategy_runners` e `runner_market_data_sources`

**Objetivo:** Criar a infraestrutura do novo agregado antes de qualquer migração de dados.

**Ações:**
1. Criar tabela `strategy_runners` com todos os campos (ver Seção 3.6)
2. Criar tabela `runner_market_data_sources`
3. Popular `strategy_runners` a partir de `portfolios` — para cada portfolio ativo, criar um Runner com:
   - `shortCode`: gerado sequencialmente (ex: "001", "002")
   - `strategyId/Name`: copiados do portfolio
   - `symbol`: copiado do portfolio
   - `exchangeId`: copiado de `orderExecutionExchange`
   - `status`: ACTIVE (para portfolios ativos) ou ARCHIVED (para inativos)
   - `executionPolicy`: SINGLE (default conservador)
   - `accountingPolicyType`: FIFO
   - `maxAllocationPercent`: 1.0 (sem limite inicial)
4. Migrar `portfolio_market_data_sources` → `runner_market_data_sources` (apontando para o Runner correspondente)

**Validação:** `COUNT(strategy_runners) == COUNT(portfolios)` e `COUNT(runner_market_data_sources) == COUNT(portfolio_market_data_sources)`

#### V5 — Refatorar `positions` e `transactions`

**Objetivo:** Adicionar FK para Runner e novos campos antes de remover as colunas antigas.

**Ações:**
1. **Positions:**
   - Adicionar colunas: `id` (UUID, gerado), `runner_id`, `status` (DEFAULT 'OPEN'), `realized_pnl` (DEFAULT 0), `closed_at`
   - Popular `runner_id` a partir do mapeamento `portfolio_id → runner_id` criado na V4
   - Popular `id` com UUID gerado
   - Alterar PK: remover `portfolio_id` como PK, definir `id` como nova PK
   - Adicionar FK: `runner_id → strategy_runners(id)`
2. **Transactions:**
   - Adicionar colunas: `runner_id`, `exchange_order_id`, `confidence`, `reasoning`
   - Popular `runner_id` a partir do mapeamento `portfolio_id → runner_id`
   - Adicionar constraint: UNIQUE(`client_order_id`)
   - Adicionar FK: `runner_id → strategy_runners(id)`

**Validação:** Todos os registros de `positions` e `transactions` possuem `runner_id` NOT NULL

#### V6 — Enriquecer `transaction_matches` e criar novas tabelas do Portfolio

**Objetivo:** Criar tabelas novas do agregado Portfolio e enriquecer TransactionMatch.

**Ações:**
1. **Transaction Matches:**
   - Adicionar colunas: `id` (UUID), `runner_id`, `buy_price`, `sell_price`, `fee_amount`, `fee_asset`, `fee_type`, `fee_converted_amount`, `pnl_realized`
   - Popular `id` com UUID gerado
   - Popular `runner_id` a partir do mapeamento via `buy_transaction_id → transaction.runner_id`
   - Popular `buy_price`/`sell_price` a partir das transações vinculadas (`executedPrice`)
   - Popular `fee_amount`/`fee_asset`/`fee_type` a partir da transaction de venda correspondente (migração dos dados de fee)
   - Calcular e popular `pnl_realized` = `(sell_price - buy_price) × matched_quantity - fee_converted`
   - Remover PK composta, definir `id` como nova PK
   - Adicionar UNIQUE(`buy_transaction_id`, `sell_transaction_id`)
2. **Criar tabela** `global_balances` (ver Seção 3.6)
3. **Criar tabela** `margin_accounts` (ver Seção 3.6)
4. **Criar tabela** `dust_accounts` (ver Seção 3.6)
5. **Criar tabela** `dead_letter_entries` (ver Seção 3.6)
6. **Popular** `global_balances` a partir de `portfolio_balances`:
   - `available_balance` ← `available_amount`
   - `reserved_balance` ← `invested_amount`
   - `realized_balance` ← `realized_pnl`
   - `initial_capital` ← `portfolios.initial_capital_amount` (JOIN)
   - `base_currency` ← `portfolios.initial_capital_currency` (JOIN)
   - `total_fees_paid` ← 0 (sem histórico anterior)

**Validação:** `COUNT(global_balances) == COUNT(portfolio_balances)`, todos os `transaction_matches` possuem `id` e `runner_id`

#### V7 — Limpeza: remover colunas e tabelas obsoletas

**Objetivo:** Remover colunas migradas e tabelas substituídas, após confirmação de que os dados foram migrados corretamente.

**Ações:**
1. **Tabela `portfolios`:** Remover colunas `strategy_id`, `strategy_name`, `symbol`, `initial_capital_amount`, `initial_capital_currency`, `order_execution_exchange`
2. **Tabela `portfolios`:** Adicionar colunas `safe_mode_status` (DEFAULT 'NORMAL'), `capital_pooling_mode` (DEFAULT 'SHARED')
3. **Tabela `positions`:** Remover colunas `quantity_currency`, `average_price_currency`, `current_price_currency`, `portfolio_id` (antigo FK). Renomear `quantity_amount` → `quantity`, `average_price_amount` → `average_price`, `current_price_amount` → `current_price`
4. **Tabela `transactions`:** Remover colunas `portfolio_id`, todas as colunas `*_currency`, todas as colunas `*_type` (15 colunas). Renomear `quantity_amount` → `quantity`, `executed_quantity_amount` → `executed_quantity`, `price_amount` → `price`, `executed_price_amount` → `executed_price`, `total_amount` → `total`, `fee_amount` → removido, `fee_currency` → removido, `fee_type` → removido
5. **Tabela `portfolio_balances`:** DROP (substituída por `global_balances`)
6. **Tabela `portfolio_market_data_sources`:** DROP (substituída por `runner_market_data_sources`)
7. Remover indexes obsoletos: `idx_portfolios_symbol`, `idx_portfolios_strategy_id`, `idx_transactions_portfolio_id`

**Validação:** Nenhuma coluna `*_currency` ou `*_type` restante nas tabelas de Transaction/Position. Nenhum FK referenciando `portfolios.strategy_id`.

#### V8 — Criar indexes de performance

**Objetivo:** Adicionar indexes otimizados para os novos padrões de query.

**Ações:**
1. Indexes parciais para queries frequentes:
   - `idx_positions_runner_open(runner_id, status)` WHERE `status = 'OPEN'`
   - `idx_transactions_inflight(runner_id, status)` WHERE `status IN ('PENDING', 'SUBMITTED', 'PARTIAL')`
   - `idx_runners_active_unique` = partial unique on `(strategy_id, symbol, exchange_id, portfolio_id)` WHERE `status NOT IN ('ARCHIVED', 'TERMINATING')`
   - `idx_dust_portfolio_unresolved(portfolio_id, is_resolved)` WHERE `is_resolved = FALSE`
   - `idx_dlq_portfolio_unresolved(portfolio_id, is_resolved)` WHERE `is_resolved = FALSE`
2. Indexes compostos:
   - `idx_matches_runner(runner_id)`
   - `idx_runners_portfolio(portfolio_id)`
   - `idx_runners_status(status)`

---

## 4. Guia de Migração e Decomposição (Refatoração)

> **Origem:** Migrado da Seção 8 do Blueprint. O conteúdo descreve passos práticos de refatoração, que são responsabilidade do Implementation Guide.

Para transformar a arquitetura atual no modelo do Blueprint, as responsabilidades do `Portfolio` legado serão redistribuídas conforme o mapeamento abaixo:

| Responsabilidade Atual do Portfolio      | Novo Dono (Destino)  | Justificativa Técnica                                                                              |
|:-----------------------------------------|:---------------------|:---------------------------------------------------------------------------------------------------|
| **GlobalBalance (Available, Reserved)**  | **Portfolio**        | Autoridade sobre o saldo real e reservas globais.                                                  |
| **Margem Reservada (Shadow Balance)**    | **Portfolio**        | Mantém o saldo "congelado" enquanto a transação está `PENDING` ou `SUBMITTED`.                     |
| **PnL Consolidado**                      | **Portfolio**        | Visão agregada dos resultados de todos os Runners ativos.                                          |
| **Roteamento de Eventos (Parser)**       | **Portfolio**        | Identifica o Runner proprietário via prefixo do ID e despacha a mensagem.                          |
| **Dead Letter Queue (DLQ)**              | **Portfolio**        | Captura execuções órfãs ou com IDs inválidos para intervenção manual.                              |
| **Transactions + Status Lifecycle**      | **StrategyRunner**   | Gere o ciclo de vida (Pending → Submitted → Filled/Partial) das ordens.                            |
| **TransactionMatches (Matching)**        | **StrategyRunner**   | O matching (FIFO/LIFO/Specific) é uma regra contábil da estratégia.                                |
| **Position (Calculated View)**           | **StrategyRunner**   | A exposição líquida por ativo pertence ao contexto operacional do Runner.                          |
| **Exchange Config (Symbol/Keys)**        | **StrategyRunner**   | Conhece as regras específicas (tick size, min qty) do seu ativo.                                   |
| **Geração de clientOrderId**             | **StrategyRunner**   | Garante a inclusão do `runner_short` e do `transaction_uuid` para roteamento.                      |
| **Locking de Lotes (Provisional)**       | **StrategyRunner**   | Impede que um lote em processo de venda seja usado por outro sinal concorrente.                    |
| **Gestão de Partial Fills**              | **StrategyRunner**   | Controla a contabilidade incremental e solicita ajustes parciais de margem.                        |
| **Watchdog de Timeouts**                 | **StrategyRunner**   | O Runner monitora se suas ordens "em voo" estão demorando mais do que o permitido pela estratégia. |

### Notas de Implementação para a Refatoração:

1. **Desacoplamento de Repositórios**: Iniciar pela criação do `StrategyRunnerRepository`, segregando as tabelas de `Positions` e `Transactions` do domínio financeiro do `Portfolio`.
2. **Protocolo de Identificação**: Implementar o Value Object `ClientOrderId` para centralizar a lógica de geração e parsing do ID de 32/36 caracteres.
3. **Atomicidade na Reserva**: A chamada de `Capital Request` deve ser o único ponto de sincronização impeditivo entre os Agregados para garantir integridade de saldo antes do envio à Exchange.

---

## 5. Protocolo de Comunicação entre Agregados

Esta seção define **como** os dois agregados (Portfolio e StrategyRunner) se comunicam, incluindo interfaces, padrões de entrega, modelo de consistência e mecanismos de proteção contra falhas. É a fundação sobre a qual todas as seções seguintes (6-13) se apoiam.

> **Referência:** Blueprint Seção 7.C (Comunicação entre Agregados: Padrão Híbrido).
> **Notas absorvidas:** #21 (Interface de Comunicação), #22 (Idempotência no Portfolio).
> **Questões endereçadas:** "Comunicação entre Agregados — Detalhes Técnicos", "Consistência de Dados entre Agregados" (IMPLEMENTATION_GUIDE_QUESTOES.md).

### 5.1 Princípio Arquitetural

O sistema adota um **Padrão Híbrido**: operações que requerem consistência forte (reserva de capital) são **síncronas**, enquanto operações de liquidação e estorno (que toleram latência) são **assíncronas**. Isso equilibra segurança financeira com throughput operacional.

```
┌───────────────────┐         ┌────────────────────┐
│  StrategyRunner   │         │    Portfolio       │
│                   │         │                    │
│  ┌─────────────┐  │  sync   │  ┌──────────────┐  │
│  │ Transaction │──┼────────►│  │ GlobalBalance│  │
│  │  (PENDING)  │  │ reserve │  │  (Reserved)  │  │
│  └─────────────┘  │         │  └──────────────┘  │
│                   │         │                    │
│  ┌─────────────┐  │  async  │  ┌──────────────┐  │
│  │ Transaction │──┼────────►│  │ GlobalBalance│  │
│  │  (FILLED)   │  │ confirm │  │ (Realized)   │  │
│  └─────────────┘  │         │  └──────────────┘  │
│                   │         │                    │
│  ┌─────────────┐  │  async  │  ┌──────────────┐  │
│  │ Transaction │──┼────────►│  │ GlobalBalance│  │
│  │ (CANCELED)  │  │ release │  │ (Available)  │  │
│  └─────────────┘  │         │  └──────────────┘  │
└───────────────────┘         └────────────────────┘
```

### 5.2 Ports de Comunicação entre Agregados

O Portfolio expõe **três ports de entrada** — `ReserveCapitalPort`, `ConfirmExecutionPort` e `ReleaseMarginPort` — cada um com uma única responsabilidade. O Runner acessa apenas os ports necessários, nunca o Portfolio diretamente.

> **Nota de Implementação #21 absorvida.**

#### Definição dos Ports

```
// ── Síncrono (Request-Response) ──────────────────────────

interface ReserveCapitalPort {
    ReservationResult reserve(CapitalRequest request)
}

// ── Assíncrono (Fire-and-Forget com garantia) ────────────

interface ConfirmExecutionPort {
    void confirmExecution(ExecutionConfirmation confirmation)
}

interface ReleaseMarginPort {
    void release(MarginRelease release)
}
```

#### 5.2.1 Operação `reserve` — Capital Request (Síncrono)

**Direção:** Runner → Portfolio
**Natureza:** Síncrona, bloqueante
**Garantia:** Strong Consistency — se retornar OK, a margem está garantida

| Aspecto                         | Decisão                                                                                                                                                                                                                                                                                         |
|---------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Quando ocorre**               | Após criação da Transaction PENDING, antes do Order Dispatch                                                                                                                                                                                                                                    |
| **Pré-condições**               | Transaction persistida no DB; `clientOrderId` gerado                                                                                                                                                                                                                                            |
| **Entrada (`CapitalRequest`)**  | `transactionId` (UUID), `runnerId` (UUID), `runnerShortCode` (String), `symbol` (String), `amount` (BigDecimal), `type` (BUY/SELL)                                                                                                                                                              |
| **Saída (`ReservationResult`)** | `status` (APPROVED / REJECTED), `reservationId` (UUID), `rejectionReason` (nullable)                                                                                                                                                                                                            |
| **Timeout**                     | Configurável (default: 5 segundos). Se expirar, Runner trata como REJECTED e aborta                                                                                                                                                                                                             |
| **Thread-safety**               | O método `reserve` é o **ponto de serialização** do GlobalBalance. Implementar com lock pessimista no DB: `UPDATE global_balances SET available_balance = available_balance - :amount, reserved_balance = reserved_balance + :amount WHERE portfolio_id = :id AND available_balance >= :amount` |
| **Validações internas**         | 1. Circuit Breaker: `if (portfolio.safeModeStatus != NORMAL) reject` (Nota #11)<br>2. Limite por Runner: `if (runnerExposure + amount > maxAllocationPercent * totalBalance) reject`<br>3. Saldo: `if (availableBalance < amount) reject`                                                       |

**Cenários de rejeição:**

| Motivo                    | Código                  | Ação do Runner                          |
|---------------------------|-------------------------|-----------------------------------------|
| Safe Mode ativo           | `RISK_VIOLATION`        | Descarta sinal, loga evento             |
| Limite do Runner excedido | `RUNNER_LIMIT_EXCEEDED` | Descarta sinal, loga evento             |
| Saldo insuficiente        | `INSUFFICIENT_FUNDS`    | Descarta sinal, loga evento             |
| Timeout na comunicação    | `TIMEOUT`               | Aborta, marca Transaction como REJECTED |
| Runner desconhecido       | `UNKNOWN_RUNNER`        | Aborta, encaminha para DLQ              |

#### 5.2.2 Operação `confirmExecution` — Confirmação de Execução (Assíncrono)

**Direção:** Runner → Portfolio
**Natureza:** Assíncrona
**Garantia:** At-least-once com idempotência no receptor

| Aspecto                               | Decisão                                                                                                                                                                                                                          |
|---------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Quando ocorre**                     | Após cada `TransactionMatch` ser persistido (PARTIAL ou FILLED)                                                                                                                                                                  |
| **Entrada (`ExecutionConfirmation`)** | `transactionId` (UUID), `runnerId` (UUID), `matchId` (UUID), `executedQuantity` (BigDecimal), `executedPrice` (BigDecimal), `fee` (Fee VO — amount, asset, type), `totalCost` (BigDecimal), `isFinal` (boolean — true se FILLED) |
| **Efeito no Portfolio**               | Converte margem de Reserved → Realized: `reserved -= totalCost`, `realized += pnlAmount`, `totalFeesPaid += feeConverted`                                                                                                        |
| **Idempotência**                      | Portfolio rastreia `matchId` processados. Se receber duplicate, descarta silenciosamente (Nota #22)                                                                                                                              |
| **Retry**                             | Em caso de falha no processamento, o evento é reenfileirado com backoff exponencial (1s, 2s, 4s, max 30s)                                                                                                                        |
| **Max retries**                       | 5 tentativas. Após esgotar, evento vai para DLQ                                                                                                                                                                                  |

#### 5.2.3 Operação `release` — Rollback de Margem (Assíncrono)

**Direção:** Runner → Portfolio
**Natureza:** Assíncrona com retries garantidos
**Garantia:** At-least-once — capital preso (starvation) é inaceitável

| Aspecto                       | Decisão                                                                                                                                                                                            |
|-------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Quando ocorre**             | Transaction atinge estado terminal REJECTED, CANCELED ou EXPIRED                                                                                                                                   |
| **Entrada (`MarginRelease`)** | `transactionId` (UUID), `runnerId` (UUID), `releaseAmount` (BigDecimal), `reason` (REJECTED / CANCELED / EXPIRED), `executedAmount` (BigDecimal — parcela já executada, para estorno proporcional) |
| **Efeito no Portfolio**       | Devolve margem: `reserved -= releaseAmount`, `available += releaseAmount`                                                                                                                          |
| **Cálculo do estorno**        | `releaseAmount = originalReserved - executedAmount`. Para REJECTED/EXPIRED: `executedAmount = 0` (estorno total)                                                                                   |
| **Idempotência**              | Portfolio rastreia `transactionId` já estornados. Duplicate → descarte silencioso                                                                                                                  |
| **Criticidade**               | Este evento **nunca pode ser perdido**. Se o EventBus falhar, o Runner persiste o evento localmente e reenvia no próximo ciclo                                                                     |
| **Retry**                     | Backoff exponencial sem limite de tentativas. O evento é retentado até sucesso                                                                                                                     |

### 5.3 Mecanismo de Entrega

#### 5.3.1 Arquitetura V1: EventBus em Memória (Monólito)

Na V1 (monólito Spring Boot), a comunicação assíncrona utiliza um **EventBus em memória** (Spring Application Events), sem dependência de broker externo.

```
Runner                          Portfolio
  │                               │
  │── persist Transaction ───────►│ (DB)
  │                               │
  │── reserve(CapitalRequest) ───►│ (método direto, síncrono)
  │◄── ReservationResult ─────────│
  │                               │
  │── dispatch order ────────────►│ (Exchange)
  │                               │
  │── publish(ExecutionConfirmed)►│ (Spring ApplicationEvent, async)
  │                               │
  │── publish(MarginRelease) ────►│ (Spring ApplicationEvent, async)
```

**Implementação Spring:**

| Componente           | Padrão                                                            | Notas                                                                                          |
|----------------------|-------------------------------------------------------------------|------------------------------------------------------------------------------------------------|
| `reserve()`          | Chamada direta de método via `ReserveCapitalPort`                 | Injetada via Spring DI. O Runner nunca referencia `Portfolio` diretamente — apenas o port      |
| `confirmExecution()` | `ApplicationEventPublisher.publishEvent(ExecutionConfirmedEvent)` | Listener no Portfolio com `@TransactionalEventListener(phase = AFTER_COMMIT)`                  |
| `release()`          | `ApplicationEventPublisher.publishEvent(MarginReleaseEvent)`      | Idem. Listener garante que o evento só é processado após o commit do Runner                    |
| Retry                | `@Retryable` (Spring Retry) no listener                           | Com backoff exponencial configurável                                                           |
| Fallback (DLQ)       | `@Recover` no listener                                            | Após max retries, persiste na tabela `dead_letter_entries`                                     |

**Decisão: `@TransactionalEventListener(phase = AFTER_COMMIT)`**

O uso de `AFTER_COMMIT` garante que o evento só é disparado **após** o Runner ter commitado sua transação de banco. Isso evita o cenário onde:
1. Runner publica evento de execução
2. Portfolio tenta processar, mas a Transaction ainda não está commitada no DB
3. Portfolio não encontra os dados e falha

**Trade-off:** Se o sistema crashar entre o commit do Runner e o dispatch do evento, o evento é perdido. Isso é aceitável porque o **Boot Sequence** (Blueprint Seção 6.D; Seção 10) reconcilia esses gaps.

#### 5.3.2 Evolução para Microserviços (V2+)

Para evolução futura, o padrão muda para **Outbox Pattern** com broker durável:

| Aspecto          | V1 (Monólito)                           | V2+ (Microserviços)                                               |
|------------------|-----------------------------------------|-------------------------------------------------------------------|
| **Transporte**   | Spring ApplicationEvents (in-memory)    | RabbitMQ / Kafka                                                  |
| **Durabilidade** | Perdido em crash (reconciliado no boot) | Persistido no broker                                              |
| **Atomicidade**  | `@TransactionalEventListener`           | Outbox Pattern: evento salvo na mesma tx do Runner                |
| **Ports**        | `ReserveCapitalPort`, `ConfirmExecutionPort`, `ReleaseMarginPort` (sem alteração) | Idem — os ports isolam o transporte |

Os ports `ReserveCapitalPort`, `ConfirmExecutionPort` e `ReleaseMarginPort` abstraem o transporte — mudar de EventBus para Outbox+Kafka exige apenas novas implementações, sem alterar o domínio.

### 5.4 Modelo de Consistência

> **Questão endereçada:** "Como garantir consistência entre os dois agregados?" (IMPLEMENTATION_GUIDE_QUESTOES.md)

#### 5.4.1 Classificação por Operação

| Operação                      | Modelo                   | Window de Inconsistência                                | Impacto se Desatualizado                                                                                                                 |
|-------------------------------|--------------------------|---------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| **Reserva de Margem**         | **Strong Consistency**   | 0 ms (síncrono)                                         | N/A — bloqueante                                                                                                                         |
| **Confirmação de Execução**   | **Eventual Consistency** | 0-500 ms (normal), até 30s (com retries)                | GlobalBalance mostra Reserved quando já deveria ser Realized. Impacto: conservador (não libera capital para novos trades prematuramente) |
| **Estorno de Margem**         | **Eventual Consistency** | 0-500 ms (normal), ilimitado (com retries persistentes) | Capital fica "preso" em Reserved. Impacto: Runner não pode fazer novos trades com esse capital                                           |
| **Saldo visível para Runner** | **Eventual**             | Não aplicável — Runner nunca lê o saldo diretamente     | Runner não toma decisões baseadas no saldo (a Strategy decide quanto, o Runner apenas solicita)                                          |

#### 5.4.2 Garantias de Segurança

**Invariante 1 — Saldo nunca fica negativo:**
A operação `reserve` é atômica e síncrona. O Portfolio rejeita qualquer reserva que tornaria `availableBalance < 0`. Como é o único ponto de entrada para reservas, não há race condition.

**Invariante 2 — Capital nunca é perdido:**
Toda margem reservada é rastreada por `transactionId`. O ciclo é fechado:
- `reserve` → cria reserva vinculada ao `transactionId`
- `confirmExecution` → converte reserva em realizado (parcial ou total)
- `release` → devolve reserva ao disponível

Se nenhum dos três eventos chegar (crash total), o **Boot Sequence** (Blueprint Seção 6.D; Seção 10) reconcilia.

**Invariante 3 — Nenhum evento duplicado altera o saldo:**
A idempotência por `matchId` (confirmação) e `transactionId` (release) garante que reprocessamento não duplica movimentações.

#### 5.4.3 Decisão: Runner NÃO lê o GlobalBalance

O StrategyRunner **nunca consulta** o saldo do Portfolio diretamente. O fluxo é:

1. A **Strategy** decide quanto comprar/vender (baseada em regras próprias)
2. O **Runner** solicita reserva via `ReserveCapitalPort.reserve()`
3. O **Portfolio** aprova ou rejeita

Isso elimina a questão de "decisão com saldo desatualizado" — o Runner não precisa de uma visão atualizada do saldo para operar. A validação de saldo ocorre atomicamente no momento da reserva.

### 5.5 Idempotência e Proteção contra Duplicatas

> **Nota de Implementação #22 absorvida.**

#### 5.5.1 Mecanismo no Portfolio

O Portfolio mantém um registro de operações processadas para cada tipo de evento:

| Evento             | Chave de Idempotência  | Armazenamento                                                                   | Ação em Duplicata                                    |
|--------------------|------------------------|---------------------------------------------------------------------------------|------------------------------------------------------|
| `confirmExecution` | `matchId` (UUID)       | Coluna `id` da tabela `transaction_matches` — se o match já existe, é duplicata | Descarte silencioso + log INFO                       |
| `release`          | `transactionId` (UUID) | Campo de status no registro de reserva — se já foi estornado, é duplicata       | Descarte silencioso + log INFO                       |
| `reserve`          | `transactionId` (UUID) | Se já existe reserva para este `transactionId`, é duplicata (cenário de retry)  | Retorna o `ReservationResult` original (idempotente) |

**Decisão: Sem tabela dedicada de idempotência.** A própria estrutura de dados do domínio serve como store de idempotência:
- A existência de um `TransactionMatch` com o `matchId` indica que a execução já foi confirmada
- O status da reserva (já estornada ou não) indica se o release já foi processado
- Isso evita a necessidade de um Redis ou tabela auxiliar de "processed events"

#### 5.5.2 Cenários de Duplicata e Resolução

| Cenário                                              | Causa                                                               | Detecção                                                                                                                 | Resolução                            |
|------------------------------------------------------|---------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------|--------------------------------------|
| Retry após timeout de rede                           | Runner não recebeu ACK do reserve                                   | Portfolio encontra reserva existente para o `transactionId`                                                              | Retorna resultado original           |
| Reprocessamento de evento                            | EventBus reentrega após falha do listener                           | Portfolio verifica se `matchId` já existe                                                                                | Descarta silenciosamente             |
| Boot Sequence reconcilia transação já processada     | Runner reenvia confirmação para tx FILLED que Portfolio já liquidou | Portfolio verifica status da reserva                                                                                     | Descarta, responde com status atual  |
| Dois Runners enviam reserve para mesmo transactionId | Bug ou ataque                                                       | `transactionId` é UUID gerado pelo Runner — colisão estatisticamente impossível. Se ocorrer, segundo request é rejeitado | Rejeição com `DUPLICATE_TRANSACTION` |

### 5.6 Roteamento de Eventos da Exchange (Inbound)

Quando a Exchange envia callbacks de execução (via WebSocket), o fluxo de roteamento é:

```
Exchange (WebSocket)
       │
       ▼
  ExchangeAdapter (Listener)
       │
       │ parse clientOrderId
       ▼
  Portfolio (Router)
       │
       │ extract runnerShortCode
       │ lookup Runner by shortCode
       ▼
  StrategyRunner (Processamento)
       │
       │ atualiza Transaction, cria Match
       │ publica ExecutionConfirmedEvent
       ▼
  Portfolio (Listener assíncrono)
       │
       │ converte Reserved → Realized
       ▼
  GlobalBalance atualizado
```

**Responsabilidades no roteamento:**

| Passo                        | Responsável        | Ação                                              | Falha →                                |
|------------------------------|--------------------|---------------------------------------------------|----------------------------------------|
| 1. Receber callback          | ExchangeAdapter    | Deserializa payload da exchange                   | Log ERROR, descarta (exchange reenvia) |
| 2. Extrair `clientOrderId`   | ExchangeAdapter    | Parse do campo no payload                         | DLQ (payload inválido)                 |
| 3. Validar formato           | Portfolio (Router) | `ClientOrderId.parse()` — valida versão e formato | DLQ com reason `INVALID_FORMAT`        |
| 4. Extrair `runnerShortCode` | Portfolio (Router) | `clientOrderId.getRunnerShort()`                  | DLQ com reason `INVALID_FORMAT`        |
| 5. Lookup Runner             | Portfolio (Router) | Busca Runner por `shortCode`                      | DLQ com reason `UNKNOWN_RUNNER`        |
| 6. Validar propriedade       | Portfolio (Router) | Confirma que o Runner pertence a este Portfolio   | DLQ com reason `UNKNOWN_RUNNER`        |
| 7. Despachar para Runner     | Portfolio (Router) | Chamada direta (síncrona) ao Runner               | Log ERROR, retry, DLQ após max retries |

**Decisão: Roteamento síncrono, processamento assíncrono.**
O Portfolio roteia o callback para o Runner de forma síncrona (chamada de método). O Runner processa, persiste o Match, e então publica o evento assíncrono de volta ao Portfolio para liquidação financeira. Isso garante que:
- O roteamento é imediato (sem fila intermediária)
- O processamento contábil do Runner é atômico
- A liquidação no Portfolio é desacoplada

### 5.7 Diagrama de Sequência Consolidado

O fluxo completo de um trade (happy path) em termos de comunicação entre agregados:

```
Strategy    Runner                Portfolio            Exchange
   │          │                       │                    │
   │─signal──►│                       │                    │
   │          │                       │                    │
   │          │── persist PENDING ───►│ (DB)               │
   │          │                       │                    │
   │          │── reserve() ─────────►│                    │
   │          │   [SYNC]              │── lock balance ───►│ (DB)
   │          │◄── APPROVED ──────────│                    │
   │          │                       │                    │
   │          │── dispatch order ─────┼───────────────────►│
   │          │◄── orderAccepted ─────┼────────────────────│
   │          │── update SUBMITTED ──►│ (DB)               │
   │          │                       │                    │
   │          │                       │◄── execution ──────│ (WebSocket)
   │          │                       │                    │
   │          │◄── route callback ────│                    │
   │          │   [SYNC routing]      │                    │
   │          │                       │                    │
   │          │── persist Match ─────►│ (DB)               │
   │          │── update FILLED ─────►│ (DB)               │
   │          │                       │                    │
   │          │── confirmExecution()─►│                    │
   │          │   [ASYNC event]       │── update balance──►│ (DB)
   │          │                       │   Reserved→Realized|
```

### 5.8 Configuração e Timeouts

| Parâmetro                            | Default   | Descrição                                            |
|--------------------------------------|-----------|------------------------------------------------------|
| `capital.request.timeout-ms`         | 5000      | Timeout do `reserve()`. Se expirar, Runner aborta    |
| `event.retry.max-attempts`           | 5         | Máximo de retries para `confirmExecution`            |
| `event.retry.initial-interval-ms`    | 1000      | Intervalo inicial do backoff exponencial             |
| `event.retry.max-interval-ms`        | 30000     | Intervalo máximo do backoff                          |
| `event.retry.multiplier`             | 2.0       | Multiplicador do backoff                             |
| `margin.release.max-attempts`        | unlimited | Release nunca desiste — capital preso é inaceitável  |
| `margin.release.initial-interval-ms` | 1000      | Intervalo inicial do backoff para release            |
| `routing.unknown-runner.action`      | DLQ       | Ação quando Runner não é encontrado: DLQ ou LOG_ONLY |

### 5.9 Implicações Arquiteturais

#### Mapeamento de Consistência por Operação

> **Referência direta:** Blueprint Seção 7.C, tabela de implicações arquiteturais.

| Operação                                    | Acoplamento        | Garantia de Consistência                                                           | Impacto de Falha                                        |
|---------------------------------------------|--------------------|------------------------------------------------------------------------------------|---------------------------------------------------------|
| **Reserva de Margem** (`reserve`)           | Forte (Síncrono)   | **Atômica**: Saldo bloqueado antes da persistência do `SUBMITTED`                  | Bloqueio imediato do sinal por falta de fundos          |
| **Liquidação de Fees** (`confirmExecution`) | Fraco (Assíncrono) | **Eventual** (0-500ms): O `GlobalBalance` atualiza após o processamento do match   | Discrepância temporária resolvida no próximo checkpoint |
| **Estorno (Refund)** (`release`)            | Fraco (Assíncrono) | **Eventual** (sem limite): O capital retorna ao `Available` após o evento de falha | Capital retido temporariamente até ao sucesso do retry  |

#### Decisões de Design

| Decisão                                    | Justificativa                                                             | Trade-off                                                              |
|--------------------------------------------|---------------------------------------------------------------------------|------------------------------------------------------------------------|
| `reserve()` síncrono                       | Capital não pode ser duplo-gasto. O Runner DEVE bloquear até ter garantia | Latência adicional no fluxo de trade (~1-5ms em monólito)              |
| `confirmExecution()` assíncrono            | A liquidação financeira pode atrasar sem afetar a execução do trade       | Window de eventual consistency (~500ms normal)                         |
| `release()` assíncrono sem limite de retry | Capital preso é pior que latência de liberação                            | Em cenário extremo, retries podem acumular                             |
| Runner nunca lê GlobalBalance              | Elimina stale reads e simplifica consistência                             | Runner não sabe "quanto sobra" — mas não precisa                       |
| EventBus in-memory (V1)                    | Sem dependência externa, simplicidade operacional                         | Eventos perdidos em crash (reconciliados no boot)                      |
| Ports de comunicação como abstração (`ReserveCapitalPort`, `ConfirmExecutionPort`, `ReleaseMarginPort`) | Permite migrar de monólito para microserviços sem alterar domínio | Indireção adicional |
| Idempotência via estrutura de domínio      | Sem Redis/tabela auxiliar; menos infraestrutura                           | Queries de verificação em cada evento (impacto negligível com indexes) |

---

## 6. Ciclo de Vida da Transação e Idempotência

Esta seção detalha **como implementar** a máquina de estados da Transaction, o protocolo de persistência "Persist-First", a estratégia de retry/timeout, e o tratamento de execuções parciais e cancelamentos. Cobre o ciclo completo desde a materialização do sinal até o estado terminal.

> **Referência:** Blueprint Seções 4 (Fluxo de Execução), 6 (Ciclo de Vida), 10.1 (Idempotência), 10.2 (Cancelamento Parcial).
> **Notas absorvidas:** #1 (Persistência Atômica Pre-Flight), #3 (Retry e Timeout), #4 (Boot Sequence Safe Mode), #6 (Transição Parcial→Cancelado).
> **Nota #2** (ClientOrderId) foi absorvida na Seção 3.4.1.

### 6.1 Máquina de Estados da Transaction

A Transaction segue uma máquina de estados linear e determinística. Cada transição é unidirecional — não existe rollback de estado.

```
                    ┌─────────────────────────────────────────────┐
                    │                                             │
                    │          ┌──── REJECTED                     │
                    │          │                                  │
  PENDING ──► SUBMITTED ──► PARTIAL ──► FILLED                    │
     │              │          │                                  │
     │              │          └──── CANCELED                     │
     │              │                                             │
     │              └──── CANCELED                                │
     │              └──── EXPIRED                                 │
     │                                                            │
     └──── REJECTED (Portfolio rejeita Capital Request)           │
     └──── EXPIRED (crash antes do dispatch — Boot Sequence)      │
                                                                  │
  Estados terminais: FILLED, CANCELED, EXPIRED, REJECTED ─────────┘
```

#### 6.1.1 Definição de Estados

> **Estado (T39, 2026-10-16):** Na BUY não há `confirmExecution` por parcial: o custo executado fica em `reserved` até a venda do lote (T36). A coluna "Ação de Margem" vale para a SELL.

| Estado        | Significado                                                                                                              | Quem Transiciona                   | Ação de Margem (via Seção 5)                                                               |
|---------------|--------------------------------------------------------------------------------------------------------------------------|------------------------------------|--------------------------------------------------------------------------------------------|
| **PENDING**   | Intenção criada e persistida no DB. Lotes de venda travados (se SELL). Capital Request ainda não enviado ou em andamento | Runner (criação)                   | `ReserveCapitalPort.reserve()` → Reserved                                                      |
| **SUBMITTED** | Ordem aceita pela Exchange (ExchangeOrderId recebido). Ordem "em voo"                                                    | Runner (após ACK da exchange)      | Mantém Reserved                                                                            |
| **PARTIAL**   | Execução parcial recebida. Pelo menos um `TransactionMatch` existe                                                       | Runner (via callback da exchange)  | `ConfirmExecutionPort.confirmExecution()` → converte fatia proporcional de Reserved → Realized   |
| **FILLED**    | Execução 100% concluída                                                                                                  | Runner (via callback)              | `ConfirmExecutionPort.confirmExecution(isFinal=true)` → converte restante de Reserved → Realized |
| **CANCELED**  | Ordem cancelada (pelo Runner, pelo operador, ou pela exchange)                                                           | Runner (via callback ou Watchdog)  | `ReleaseMarginPort.release()` → estorna Reserved remanescente → Available                     |
| **EXPIRED**   | Ordem expirou (timeout do Watchdog) ou intenção não materializada (crash recovery)                                       | Runner (Watchdog ou Boot Sequence) | `ReleaseMarginPort.release()` → estorno total → Available                                     |
| **REJECTED**  | Portfolio negou Capital Request, ou exchange rejeitou a ordem                                                            | Runner (imediato)                  | Se já reservado: `ReleaseMarginPort.release()`. Se antes da reserva: nenhuma ação             |

#### 6.1.2 Transições Válidas

| De        | Para      | Gatilho                                                                    | Condição                                              |
|-----------|-----------|----------------------------------------------------------------------------|-------------------------------------------------------|
| PENDING   | SUBMITTED | Exchange aceita ordem (retorna ExchangeOrderId)                            | `exchangeOrderId != null`                             |
| PENDING   | REJECTED  | Portfolio rejeita Capital Request ou exchange rejeita ordem                | —                                                     |
| PENDING   | EXPIRED   | Boot Sequence: Transaction sem `exchangeOrderId` (crash antes do dispatch) | `exchangeOrderId == null` no boot                     |
| SUBMITTED | PARTIAL   | Exchange envia primeira execução parcial                                   | `executedQuantity > 0 && executedQuantity < quantity` |
| SUBMITTED | FILLED    | Exchange envia execução completa                                           | `executedQuantity == quantity`                        |
| SUBMITTED | CANCELED  | Watchdog cancela, operador cancela, ou exchange cancela                    | —                                                     |
| SUBMITTED | EXPIRED   | Watchdog timeout sem nenhuma execução                                      | —                                                     |
| PARTIAL   | FILLED    | Exchange envia execução final                                              | `totalExecuted == quantity`                           |
| PARTIAL   | CANCELED  | Restante cancelado após execução parcial                                   | `totalExecuted < quantity && cancelEvent`             |

**Transições inválidas (nunca devem ocorrer):**
- Qualquer estado terminal → qualquer outro estado
- SUBMITTED → PENDING (rollback)
- PARTIAL → SUBMITTED (rollback)
- PARTIAL → REJECTED (já teve execução)
- PARTIAL → EXPIRED (já teve execução — usa CANCELED). Quando a exchange informa `EXPIRED` para uma
  ordem com execução parcial (ex.: MARKET que esgota o livro), a conciliação aplica `CANCELED` (T67).

#### 6.1.3 Implementação da Máquina de Estados

A transição de estados deve ser implementada como um **método de domínio** na entidade Transaction, com validação explícita das transições permitidas:

```
Regras de transição (pseudocódigo):

Transaction.transitionTo(newStatus):
    if (currentStatus.isFinal())
        throw IllegalStateTransition("Cannot transition from terminal state")

    if (!VALID_TRANSITIONS[currentStatus].contains(newStatus))
        throw IllegalStateTransition("Invalid: ${currentStatus} → ${newStatus}")

    if (newStatus == PARTIAL && executedQuantity == null)
        throw IllegalStateTransition("PARTIAL requires executedQuantity")

    this.status = newStatus
    this.updatedAt = Instant.now()
```

**Mapa de transições válidas (constante):**

| Estado Atual | Transições Permitidas              |
|--------------|------------------------------------|
| PENDING      | SUBMITTED, REJECTED, EXPIRED       |
| SUBMITTED    | PARTIAL, FILLED, CANCELED, EXPIRED |
| PARTIAL      | FILLED, CANCELED                   |
| FILLED       | — (terminal)                       |
| CANCELED     | — (terminal)                       |
| EXPIRED      | — (terminal)                       |
| REJECTED     | — (terminal)                       |

### 6.2 Protocolo "Persist-First" (Materialização)

> **Nota de Implementação #1 absorvida.**

O risco de crash entre a geração do ID e a persistência é eliminado pela ordem rigorosa de operações. A regra fundamental: **nenhuma chamada de rede ocorre antes da persistência no banco de dados**.

#### 6.2.1 Sequência de Materialização

```
1. Runner recebe TradeSignal da Strategy
       │
2. Runner valida contra ExecutionPolicy
       │ (se Single e já tem posição → descarta)
       │
3. Runner valida TradeParams (confidence, quantity, minNotional)
       │
4. Runner gera ClientOrderId (Seção 3.4.1)
       │
5. Runner cria Transaction com status PENDING
       │
6. ── INÍCIO DA TRANSAÇÃO DE BANCO ──
       │
       ├─ 6a. Persistir Transaction (status=PENDING)
       │
       ├─ 6b. Se SELL: criar locks nos lotes alvo
       │       (pessimistic lock nas linhas da Position)
       │
       └─ 6c. ReserveCapitalPort.reserve() [síncrono]
       │      │
       │      ├─ APPROVED → commit tx de banco
       │      │
       │      └─ REJECTED → rollback tx, descarta sinal
       │
7. ── FIM DA TRANSAÇÃO DE BANCO ──
       │
8. Runner envia ordem à Exchange via ExchangeAdapter
       │ (fora da tx de banco — não bloqueia o DB)
       │
9. Exchange retorna ACK:
       ├─ ACK recebido → Transaction → SUBMITTED
       ├─ Timeout      → ver Seção 6.3
       └─ Rejeição     → Transaction → REJECTED
                         ReleaseMarginPort.release()
```

**Decisões críticas:**

| Decisão                                        | Justificativa                                                                       |
|------------------------------------------------|-------------------------------------------------------------------------------------|
| Passos 6a-6c em uma única tx de banco          | Garante atomicidade: se o reserve falhar, Transaction e locks nunca são persistidos |
| Dispatch (passo 8) **fora** da tx de banco     | Chamada de rede dentro de tx de banco = risco de timeout e lock prolongado no DB    |
| ClientOrderId gerado **antes** da persistência | O ID é campo obrigatório da Transaction; precisa existir antes do INSERT            |

#### 6.2.2 Cenários de Crash e Recuperação

| Ponto de Crash                    | Estado no DB                   | Estado na Exchange | Recuperação (Boot Sequence)                             |
|-----------------------------------|--------------------------------|--------------------|---------------------------------------------------------|
| Entre passos 5 e 6                | Nada persistido                | Nada enviado       | Sem impacto — sinal perdido                             |
| Durante passo 6 (tx rollback)     | Nada persistido                | Nada enviado       | Sem impacto                                             |
| Entre passos 7 e 8                | PENDING, sem `exchangeOrderId` | Nada enviado       | Boot: marca EXPIRED, estorna margem ("Zumbi")           |
| Durante passo 8 (timeout de rede) | PENDING ou SUBMITTED           | Desconhecido       | Boot: consulta exchange via `clientOrderId` (Seção 6.3) |
| Após passo 9 (antes de SUBMITTED) | PENDING com `exchangeOrderId`  | Ordem aceita       | Boot: atualiza para SUBMITTED, fluxo normal             |

### 6.3 Estratégia de Retry e Timeout

> **Estado (T39, 2026-10-16):** Implementado de outra forma: falha no dispatch deixa a transação `PENDING`; o `RunnerTransactionRecoveryWatchdog` (`runner.recovery.transaction.*`, ciclo 60s, carência de PENDING 10 min) consulta a exchange antes de expirar. Não há marcação otimista nem backoff 5/10/20/60s; as chaves `runner.watchdog.*`/`runner.dispatch.*` não existem e SUBMITTED antigo **não** é cancelado (ordens LIMIT podem descansar por design).

> **Nota de Implementação #3 absorvida.**

#### 6.3.1 Regra Fundamental: Nunca Reenviar Ordem Imediatamente

Se a chamada à Exchange retornar Timeout ou Connection Closed, o sistema **não reenvia** a ordem. A razão: a ordem pode ter sido aceita pela exchange antes da conexão cair — reenviar com novo `clientOrderId` causaria duplicação; reenviar com o mesmo `clientOrderId` pode ser rejeitado como duplicata pela exchange (comportamento desejado).

#### 6.3.2 Protocolo de Recuperação Pós-Timeout

```
Runner envia ordem → Timeout/ConnectionClosed
       │
1. Marcar Transaction como SUBMITTED (otimista — assume que pode ter chegado)
       │
2. Disparar consulta GET Order via ExchangeAdapter usando clientOrderId
       │
       ├─ Exchange retorna: "Order Found, status=FILLED"
       │     └─ Atualiza local para FILLED, processa matches
       │
       ├─ Exchange retorna: "Order Found, status=NEW/PARTIAL"
       │     └─ Atualiza local para SUBMITTED/PARTIAL, aguarda callbacks
       │
       ├─ Exchange retorna: "Order Not Found"
       │     └─ Marca local como EXPIRED, ReleaseMarginPort.release()
       │
       └─ Consulta também falha (exchange inacessível)
              └─ Mantém SUBMITTED, agenda retry da consulta
                 (backoff: 5s, 10s, 20s, max 60s)
                 Watchdog monitora timeout global
```

#### 6.3.3 Watchdog de Timeout

O Runner mantém um Watchdog que monitora todas as Transactions em estados não-terminais:

| Parâmetro                              | Default  | Descrição                                           |
|----------------------------------------|----------|-----------------------------------------------------|
| `runner.watchdog.pending-timeout-ms`   | 30000    | Tempo máximo em PENDING (inclui reserve + dispatch) |
| `runner.watchdog.submitted-timeout-ms` | 300000   | Tempo máximo em SUBMITTED sem execução (5 min)      |
| `runner.watchdog.check-interval-ms`    | 5000     | Frequência de verificação do Watchdog               |

**Ações do Watchdog:**

| Estado                          | Timeout Excedido       | Ação                                                                 |
|---------------------------------|------------------------|----------------------------------------------------------------------|
| PENDING (sem `exchangeOrderId`) | `pending-timeout-ms`   | Marca EXPIRED, release margem                                        |
| PENDING (com `exchangeOrderId`) | `pending-timeout-ms`   | Consulta exchange para resolver                                      |
| SUBMITTED                       | `submitted-timeout-ms` | Envia cancel à exchange, aguarda callback                            |
| PARTIAL                         | `submitted-timeout-ms` | Envia cancel à exchange (restante), marca CANCELED quando confirmado |

**Regra:** O Watchdog **nunca cancela unilateralmente** uma ordem que já foi aceita pela exchange. Ele sempre envia um comando de cancelamento via ExchangeAdapter e aguarda a confirmação. Somente após a confirmação de cancelamento (ou verificação de que a ordem não existe) a Transaction muda de estado.

### 6.4 Tratamento de Execuções Parciais (Partial Fills)

> **Referência:** Blueprint Seção 4.D, 10.2.

#### 6.4.1 Contabilidade Incremental

Cada evento de execução parcial gera um `TransactionMatch` independente. O PnL e a posição são atualizados **incrementalmente** — não é necessário aguardar a execução completa.

```
Ordem: SELL 1.0 BTC @ $50,000

Evento 1: Execução 0.3 BTC @ $50,010
  → Match #1: qty=0.3, sellPrice=50010, pnlRealized=...
  → confirmExecution(executedQty=0.3, isFinal=false)
  → Reserved parcial → Realized

Evento 2: Execução 0.5 BTC @ $49,990
  → Match #2: qty=0.5, sellPrice=49990, pnlRealized=...
  → confirmExecution(executedQty=0.5, isFinal=false)
  → Reserved parcial → Realized

Evento 3: Execução 0.2 BTC @ $50,000
  → Match #3: qty=0.2, sellPrice=50000, pnlRealized=...
  → confirmExecution(executedQty=0.2, isFinal=true)
  → Reserved restante → Realized
  → Transaction → FILLED
```

**Campos atualizados a cada partial:**

| Campo                          | Atualização                                                                      |
|--------------------------------|----------------------------------------------------------------------------------|
| `Transaction.executedQuantity` | Incrementa: `executedQuantity += partialQty`                                     |
| `Transaction.executedPrice`    | Recalcula WAP: `Σ(price × qty) / Σ(qty)`                                         |
| `Transaction.status`           | → PARTIAL (se `executedQty < quantity`), → FILLED (se `executedQty == quantity`) |
| `Position.quantity`            | Ajusta (+ para BUY, - para SELL)                                                 |
| `Position.averagePrice`        | Recalcula WAP (apenas para BUY — SELL não altera avgPrice)                       |
| `Position.realizedPnl`         | Incrementa com PnL do match (apenas para SELL)                                   |

#### 6.4.2 Cancelamento Pós-Parcial (PARTIAL → CANCELED)

> **Nota de Implementação #6 absorvida.**

Quando uma ordem parcialmente executada é cancelada (pelo Watchdog, operador ou exchange):

**Estado terminal:** `CANCELED` — o sistema identifica a execução parcial pela presença de `TransactionMatch` registros. Para observabilidade (logs, dashboards), pode ser apresentado como `PARTIALLY_FILLED_CANCELED`, embora não constitua um estado formal da máquina de estados.

**Protocolo de cancelamento parcial:**

```
1. Runner recebe evento CANCELED da exchange
       │
2. ── INÍCIO DA TRANSAÇÃO DE BANCO ──
       │
       ├─ 2a. Transaction.transitionTo(CANCELED)
       │
       ├─ 2b. Calcular estorno proporcional:
       │       releaseAmount = originalReserved - Σ(matchedQty × executedPrice)
       │
       ├─ 2c. Se SELL: desbloquear lotes não consumidos
       │       (lotes que estavam locked para a fatia não executada)
       │
       └─ 2d. Commit
       │
3. ── FIM DA TRANSAÇÃO DE BANCO ──
       │
4. ReleaseMarginPort.release(releaseAmount) [assíncrono]
```

**Regras contábeis:**

| Aspecto                | Regra                                                                                     |
|------------------------|-------------------------------------------------------------------------------------------|
| **Matches existentes** | Permanecem íntegros e imutáveis — representam execuções reais                             |
| **Preço médio**        | Calculado exclusivamente sobre `TransactionMatch` efetivados; volume cancelado é ignorado |
| **Fees**               | Existem apenas onde há execução. Fatia cancelada não gera fee                             |
| **Estorno**            | `Estorno = Margem_Reservada_Original - Σ(Margem_Já_Convertida_Em_Realized)`               |
| **Locks**              | Lotes travados para a fatia não executada são desbloqueados atomicamente na mesma tx      |
| **Position**           | Quantidade atualizada apenas pelo volume efetivamente executado                           |

### 6.5 Protocolo de Idempotência no Envio

> **Referência:** Blueprint Seção 10.1.

#### 6.5.1 O `clientOrderId` como Chave de Idempotência

O `clientOrderId` (Seção 3.4.1) é a chave primária de idempotência perante a Exchange:

| Aspecto                       | Decisão                                                                                                            |
|-------------------------------|--------------------------------------------------------------------------------------------------------------------|
| **Premissa**                  | Todas as exchanges suportadas implementam `clientOrderId`. Exchanges sem este recurso não são compatíveis com a V1 |
| **Unicidade**                 | Contém o `transaction_uuid` gerado localmente — vínculo permanente entre DB e Exchange                             |
| **Comportamento da Exchange** | Envio com `clientOrderId` já existente → rejeição automática (previne duplicidade)                                 |
| **Idempotency Store**         | O `StrategyRunnerRepository` é a store de idempotência — elimina necessidade de Redis externo                      |

#### 6.5.2 Cenários de Duplicação e Proteção

| Cenário                                    | Causa                                     | Proteção                                                            |
|--------------------------------------------|-------------------------------------------|---------------------------------------------------------------------|
| Crash entre persistência e dispatch        | Sistema caiu após step 7, antes do step 8 | Boot: encontra PENDING sem `exchangeOrderId` → EXPIRED              |
| Retry manual pelo operador                 | Operador tenta reenviar ordem manualmente | Mesmo `clientOrderId` → exchange rejeita como duplicata             |
| Ordem fantasma (aceita mas não confirmada) | Timeout na rede — ACK perdido             | Boot: consulta exchange via `clientOrderId`, reconcilia estado      |
| Duas ordens para mesmo sinal               | Bug no Router ou race condition           | `clientOrderId` único por `transactionId` → segunda ordem rejeitada |

### 6.6 Reconciliação no Boot Sequence (Safe Mode)

> **Estado (T39, 2026-10-16):** PENDING sem `exchangeOrderId` não expira direto: o boot consulta a exchange antes (T31), respeitando `runner.boot.phase3.pending-grace-ms`.

> **Nota de Implementação #4 absorvida.**
> **Referência:** Blueprint Seção 6.D.

Quando o sistema reinicia, cada StrategyRunner executa uma sequência de reconciliação antes de aceitar novos sinais.

#### 6.6.1 Pré-condição

O Runner entra no boot com `isReconciling = true` e `status = INITIALIZING`. Neste estado:
- **Rejeita** todos os novos `TradeSignal` da Strategy
- **Aceita** callbacks da Exchange (para reconciliar ordens em voo)

#### 6.6.2 Sequência de Reconciliação

```
Boot do Runner (INITIALIZING, isReconciling=true)
       │
Step 1: Saneamento de Zumbis
       │ Busca: Transactions PENDING sem exchangeOrderId
       │ Ação: marca EXPIRED, ReleaseMarginPort.release()
       │ Justificativa: crash entre persist e dispatch
       │
Step 2: Identificação do Limbo
       │ Busca: Transactions em PENDING (com exchangeOrderId),
       │        SUBMITTED ou PARTIAL
       │
Step 3: Consulta à Exchange (por clientOrderId)
       │
       │ Para cada Transaction no Limbo:
       │
       ├─ Exchange: "Ordem existe, FILLED"
       │     → Transitar local para FILLED
       │     → Processar TransactionMatches
       │     → confirmExecution() ao Portfolio
       │
       ├─ Exchange: "Ordem existe, PARTIAL"
       │     → Transitar local para PARTIAL
       │     → Processar matches parciais
       │     → confirmExecution() parcial ao Portfolio
       │     → Watchdog assume monitoramento
       │
       ├─ Exchange: "Ordem existe, CANCELED"
       │     → Transitar local para CANCELED
       │     → release() ao Portfolio
       │
       ├─ Exchange: "Ordem NÃO existe"
       │     → Marcar local como EXPIRED
       │     → release() ao Portfolio
       │
       └─ Exchange: inacessível
       │      → Manter estado atual
       │      → Retry com backoff
       │      → NÃO liberar isReconciling até sucesso
       │
Step 4: Destravamento de Locks
       │ Para cada Transaction reconciliada:
       │ Se EXPIRED/CANCELED → desbloquear lotes associados
       │
Step 5: Validação de Integridade
       │ Verificar que Σ(Transactions em voo) = 0
       │
       │
Step 6: isReconciling = false, status = ACTIVE
       │ Runner pronto para novos sinais
```

#### 6.6.3 Tabela de Resolução de Divergências

| Estado Local                  | Estado na Exchange  | Resolução                                 | Ação de Margem              |
|-------------------------------|---------------------|-------------------------------------------|-----------------------------|
| PENDING (sem exchangeOrderId) | — (não consultável) | **Zumbi:** EXPIRED                        | release() total             |
| PENDING (com exchangeOrderId) | FILLED              | Confia na exchange: → FILLED              | confirmExecution()          |
| PENDING (com exchangeOrderId) | NEW (aceita)        | → SUBMITTED                               | Mantém Reserved             |
| PENDING (com exchangeOrderId) | Não existe          | → EXPIRED                                 | release() total             |
| SUBMITTED                     | FILLED              | Confia na exchange: → FILLED              | confirmExecution()          |
| SUBMITTED                     | PARTIALLY_FILLED    | → PARTIAL                                 | confirmExecution() parcial  |
| SUBMITTED                     | CANCELED            | → CANCELED                                | release() total             |
| SUBMITTED                     | Não existe          | → EXPIRED                                 | release() total             |
| PARTIAL                       | FILLED              | → FILLED                                  | confirmExecution() restante |
| PARTIAL                       | CANCELED            | → CANCELED                                | release() fatia restante    |
| PARTIAL                       | Não existe          | → CANCELED (matches existentes são reais) | release() fatia restante    |

**Hierarquia de confiança (Blueprint 10.3):**
1. **Exchange = Autoridade financeira:** Se a exchange confirma execução, o sistema aceita e ajusta
2. **DB local = Autoridade de intenção:** Se o DB tem PENDING mas a exchange não conhece o ID, a intenção é descartada

### 6.7 Topologia de Acesso à Exchange

> **Estado (T39, 2026-10-16):** Key pooling, fila de prioridade e failover **descartados na v1** (conta única). Rate limit por peso registrado na T24.

> **Referência:** Blueprint Seção 10.1.D, 10.4.

O StrategyRunner **nunca** acessa a Exchange diretamente. Todo acesso é centralizado no `ExchangeAdapter`:

```
Runner ──► ExchangeAdapter ──► Exchange API
           (centralizado)
              │
              ├─ Key Pooling (múltiplas API Keys)
              ├─ Rate Limiting (weight-aware)
              ├─ Priority Queue (ordens > market data)
              └─ Failover automático entre chaves
```

| Aspecto             | Decisão                                                                              |
|---------------------|--------------------------------------------------------------------------------------|
| **Leitura**         | Runner não lê ordens diretamente — recebe callbacks via Portfolio Router (Seção 5.6) |
| **Escrita**         | Runner envia ordens via `ExchangeAdapter.submitOrder()`                              |
| **Cancelamento**    | Runner envia cancel via `ExchangeAdapter.cancelOrder()`                              |
| **Consulta (Boot)** | Runner consulta status via `ExchangeAdapter.getOrder(clientOrderId)`                 |
| **Prioridade**      | Ordens e cancelamentos: prioridade máxima. Consultas: throttled se rate limit > 80%  |

### 6.8 Configuração

| Parâmetro                                 | Default  | Descrição                                       |
|-------------------------------------------|----------|-------------------------------------------------|
| `runner.watchdog.pending-timeout-ms`      | 30000    | Timeout para Transaction em PENDING             |
| `runner.watchdog.submitted-timeout-ms`    | 300000   | Timeout para Transaction em SUBMITTED (5 min)   |
| `runner.watchdog.check-interval-ms`       | 5000     | Frequência de verificação                       |
| `runner.dispatch.timeout-ms`              | 10000    | Timeout da chamada ao ExchangeAdapter           |
| `runner.dispatch.query-retry-interval-ms` | 5000     | Intervalo entre retries de consulta pós-timeout |
| `runner.dispatch.query-max-retries`       | 10       | Máximo de tentativas de consulta pós-timeout    |
| `runner.boot.reconciliation-timeout-ms`   | 60000    | Timeout total do Boot Sequence                  |
| `runner.boot.exchange-query-timeout-ms`   | 10000    | Timeout por consulta individual no boot         |

### 6.9 Implicações Arquiteturais

| Decisão                                         | Justificativa                                                                             | Trade-off                                                                                                               |
|-------------------------------------------------|-------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| Persist-First (DB antes de rede)                | Elimina ordens fantasmas — se crashar antes do dispatch, o DB tem o registro para cleanup | Latência de ~5-10ms para persistência antes do envio                                                                    |
| Watchdog no Runner (não no Portfolio)           | O Runner é o dono do ciclo de vida da Transaction; ele conhece os timeouts da strategy    | Cada Runner precisa de seu próprio timer                                                                                |
| Nunca reenviar ordem em timeout                 | Previne duplicação — a exchange pode ter aceito antes da conexão cair                     | Pode perder oportunidade de trade (custo de oportunidade aceitável)                                                     |
| CANCELED como estado terminal para parciais     | Simplicidade: uma máquina de estados linear, sem sub-estados                              | Observabilidade requer verificar se existem matches para distinguir "cancelado sem execução" de "cancelado com parcial" |
| Boot Sequence consulta exchange individualmente | Reconciliação precisa por Transaction                                                     | Lento se muitas Transactions no limbo (aceitável — cenário raro)                                                        |
| isReconciling bloqueia novos sinais             | Previne Double Spending durante boot                                                      | Latência de retomada após restart                                                                                       |
| ExchangeAdapter centralizado                    | Key Pooling, Rate Limiting, Priority Queue em um único ponto                              | Single Point of Failure (mitigado por redundância do Adapter)                                                           |

---

## 7. Gestão de Capital e Margem

Esta seção detalha **como implementar** a alocação de capital, a validação de limites por Runner, o cálculo de margem com safety buffer, e os modos de visibilidade de capital (Shared Pool vs Dedicated Buckets). Cobre toda a cadeia de validação desde a decisão da Strategy até a reserva efetiva no GlobalBalance.

> **Referência:** Blueprint Seção 11.2 (Alocação de Capital e Governança de Concorrência), Seção 4.C (Capital Request).
> **Notas absorvidas:** #14 (Safety Buffer / Arredondamento de Capital), #17 (Validação minNotional), #20 (Monitoramento de Rejection Rate).
> **Dependência:** Seção 5 (`ReserveCapitalPort`) define o contrato de comunicação. Esta seção detalha a **lógica interna** do Portfolio ao processar um `reserve()`.

### 7.1 Cadeia de Validação do Capital Request

> **Estado (T39, 2026-10-16):** Passo 2 implementado como releitura de `canAcceptSignals` na transação; passo 3 descartado (reserva na mesma transação do persist); passo 4: `maxAllocationPercent` na reserva; `maxOpenPositions`/`maxPendingOrders` aplicados ao BUY antes do persist pelo `PreTradeRiskPolicy` (**T50**, 2026-10-19).

Quando o Runner invoca `ReserveCapitalPort.reserve()`, o Portfolio executa uma cadeia de validações ordenada. A falha em qualquer passo resulta em rejeição imediata — não há "continue apesar do erro".

```
ReserveCapitalPort.reserve(CapitalRequest)
       │
Step 1: Circuit Breaker Check
       │ if (safeModeStatus != NORMAL) → reject RISK_VIOLATION
       │
Step 2: Runner Validation
       │ if (runner não pertence a este Portfolio) → reject UNKNOWN_RUNNER
       │ if (runner.status != ACTIVE) → reject RUNNER_NOT_ACTIVE
       │
Step 3: Duplicate Check (Idempotência)
       │ if (reserva já existe para transactionId) → return resultado original
       │
Step 4: Limites do Runner (Hard Limits)
       │ if (runnerExposure + amount > maxAllocationLimit) → reject RUNNER_LIMIT_EXCEEDED
       │ if (runnerOpenPositions >= maxOpenPositions) → reject MAX_POSITIONS_REACHED
       │ if (runnerInflightOrders >= maxPendingOrders) → reject MAX_PENDING_REACHED
       │   (inflightOrders = transactions com status IN (PENDING, SUBMITTED, PARTIAL))
       │
Step 5: Cálculo de Margem (sem buffer para LIMIT — ver §7.2, decisão T36)
       │ requiredAmount = amount   (MARKET, quando existir: amount × bufferMarket)
       │
Step 6: Validação de Saldo
       │ if (availableBalance < requiredAmount) → reject INSUFFICIENT_FUNDS
       │
Step 7: Reserva Atômica
       │ availableBalance -= requiredAmount
       │ reservedBalance  += requiredAmount
       │ persistir reserva vinculada ao transactionId
       │
       └─ return ReservationResult(APPROVED, reservationId)
```

**Ordem dos steps é intencional:**
- Steps 1-2: Validações "baratas" (in-memory, sem I/O) — falham rápido
- Step 3: Idempotência — evita reprocessamento
- Step 4: Limites de negócio — protegem contra abuso
- Steps 5-7: Operações financeiras — executam apenas se tudo anterior passou

### 7.2 Cálculo de Margem e Safety Buffer

> **Nota de Implementação #14 absorvida.**
>
> **Decisão (T36, 2026-10-16): sem safety buffer enquanto o sistema só opera `LIMIT`.** Uma BUY
> `LIMIT` nunca executa acima do limite e quantidade/preço são normalizados antes da reserva, então a
> reserva nominal sempre cobre o custo; a fee de BUY na Binance é cobrada, por padrão, no ativo base
> (não consome o quote reservado). Com a devolução do excedente (§7.2.3), um buffer apenas travaria
> capital durante a ordem, reduzindo o disponível e antecipando `RUNNER_LIMIT_EXCEEDED`.
> **Regra:** a task que introduzir `MARKET` (ou qualquer ordem com preço de execução desconhecido na
> reserva) **deve** entregar o buffer de MARKET de §7.2.2 junto. Custo executado acima da reserva
> gera WARN em `MarginReleaseBuilder.buildFilledExcess`.

#### 7.2.1 Fórmula de Cálculo (Modo Spot)

O capital requerido para uma ordem spot `LIMIT` (único tipo hoje) é:

```
Capital_Requerido = Quantidade × Preço_Estimado
```

| Componente                 | Fonte                      | Notas                                  |
|----------------------------|----------------------------|----------------------------------------|
| `Quantidade`               | `StrategyOutputDto.quantity` | Quantidade sugerida pela Strategy, normalizada ao `stepSize` |
| `Preço_Estimado`           | `StrategyOutputDto.limitPrice` (ou preço de mercado do tick quando `null`) | Preço-limite da estratégia (T34) ou último preço de mercado, normalizado ao `tickSize` |

Implementado em `TradeIntentFactory` (`total = quantity × price`), usado como `CapitalRequest.amount`.

#### 7.2.2 Buffer por Tipo de Ordem

| Tipo de Ordem | Buffer         | Estado                                                                 |
|---------------|----------------|------------------------------------------------------------------------|
| **LIMIT**     | nenhum (`1.0`) | Vigente. Preço fixo; o excedente por melhora de preço volta em §7.2.3  |
| **MARKET**    | `1.005` (0.5%) sugerido | **Não existe hoje.** Obrigatório na task que introduzir `MARKET` — slippage pode levar o custo acima do nominal |

**Decisão (vale para quando houver buffer):** o buffer é aplicado apenas no valor reservado. O
Runner envia a quantidade e o preço **originais** à exchange; o buffer é margem contábil, não afeta
a ordem enviada.

#### 7.2.3 Reconciliação do Buffer Pós-Execução

Após a execução (FILLED ou PARTIAL+CANCELED), haverá uma diferença entre o capital reservado (com buffer) e o capital efetivamente utilizado:

```
Buffer_Excedente = Capital_Reservado - Capital_Efetivo
```

Este excedente é devolvido ao `AvailableBalance` como parte do `ConfirmExecutionPort.confirmExecution()` (Seção 5.2.2) ou `ReleaseMarginPort.release()` (Seção 5.2.3). O cálculo:

| Cenário            | Cálculo do Estorno                                                                    |
|--------------------|---------------------------------------------------------------------------------------|
| FILLED (total)     | `reserved - (executedQty × executedPrice + fees)` → devolver ao Available             |
| PARTIAL + CANCELED | Fatia executada: `confirmExecution()`. Fatia cancelada + buffer restante: `release()` |
| REJECTED / EXPIRED | `release(totalReserved)` → devolução integral                                         |

> **Estado de implementação (T36, 2026-10-16):** o cenário FILLED está implementado como
> `release(reserved − executedQty × executedPrice)` via `MarginReleaseEvent` (`ReleaseReason.FILLED_EXCESS`),
> publicado pelo `BuyFillHandler` no fill final. **As fees não são descontadas**: a venda baixa
> `reserved` pelo custo executado sem fee (`confirmExecution`), então descontá-las aqui deixaria a
> reserva menor que o custo. Sem buffer para `LIMIT` (decisão T36), o excedente vem só da melhora de preço.

### 7.3 Validação Pre-Reserve (Runner-Side)

> **Estado (T39, 2026-10-16):** V2 (confiança mínima) descartada; V3/V4 acontecem como normalização ao `stepSize` no core e `OrderFilterValidator` no adapter Binance; V5 (sanidade de preço) aplicada pelo `PreTradeRiskPolicy` (**T50**): `limitPrice` a mais de 10% do tick é descartado em BUY e SELL. O gate de utilização global (BLUEPRINT §11.2.D) foi descartado.

Antes de invocar `ReserveCapitalPort.reserve()`, o Runner executa validações locais para evitar chamadas desnecessárias ao Portfolio.

> **Nota de Implementação #17 absorvida.**

#### 7.3.1 Cadeia de Validação no Runner

```
Runner recebe TradingDecision
       │
V1: ExecutionPolicy Check
       │ if (SINGLE && hasOpenPosition) → descarta sinal
       │ if (SINGLE && hasPendingOrder) → descarta sinal
       │
V2: Confidence Threshold
       │ if (confidence < minConfidenceThreshold) → descarta sinal
       │
V3: Quantity Validation
       │ if (quantity <= 0) → descarta sinal
       │ if (quantity < exchange.minQty) → descarta sinal
       │
V4: minNotional Validation
       │ notional = quantity × price
       │ if (notional < exchange.minNotional) → descarta sinal
       │ Exemplo: Binance exige minNotional = 5 USDT para BTCUSDT
       │
V5: Price Sanity Check
       │ if (price desvio > 10% do lastPrice) → log WARNING, descarta sinal
       │
       └─ Todas validações passaram → ReserveCapitalPort.reserve()
```

**Decisão: Duas camadas de validação (Runner + Portfolio).**
O Runner valida regras de negócio da strategy e da exchange. O Portfolio valida regras financeiras (saldo, limites). Essa separação permite que cada agregado valide apenas o que é sua responsabilidade.

| Validação            | Responsável  | Justificativa                                        |
|----------------------|--------------|------------------------------------------------------|
| ExecutionPolicy      | Runner       | Regra da strategy (Single/Hedging/Netting)           |
| minQty, minNotional  | Runner       | Regras da exchange — Runner conhece o `exchangeInfo` |
| Confidence threshold | Runner       | Parâmetro da strategy                                |
| Saldo disponível     | Portfolio    | Autoridade financeira                                |
| Limites por Runner   | Portfolio    | Governança global                                    |
| Circuit Breaker      | Portfolio    | Safe Mode é decisão do Portfolio                     |

### 7.4 Limites de Exposição por Runner

> **Estado (T39, 2026-10-16):** `maxAllocationPercent` na reserva; `maxOpenPositions`/`maxPendingOrders` no BUY (**T50**); cancel rate: alerta Grafana `ctrade-cancel-rate-high` sobre `signal.evaluated.total{decision=CANCEL}` (> 10/min, **T51**); alteração em runtime: `PATCH /api/admin/runners/{id}` (T49). A exposição é calculada por consulta, não por `MarginAccount`.

> **Referência:** Blueprint Seção 11.2.B.

O Portfolio mantém e valida limites de exposição para cada Runner, impedindo que um único Runner monopolize o capital.

#### 7.4.1 Tipos de Limites

| Limite                 | Campo no StrategyRunner | Tipo  | Validação                                                                                |
|------------------------|-------------------------|-------|------------------------------------------------------------------------------------------|
| **Max Allocation**     | `maxAllocationPercent`  | Hard  | `runnerExposure + requestAmount > maxAllocationPercent × totalBalance` → reject          |
| **Max Open Positions** | `maxOpenPositions`      | Hard  | `COUNT(positions WHERE status=OPEN AND runnerId=X) >= max` → reject                      |
| **Max Pending Orders** | `maxPendingOrders`      | Hard  | `COUNT(transactions WHERE status IN (PENDING,SUBMITTED) AND runnerId=X) >= max` → reject |
| **Max Cancel Rate**    | Configuração global     | Soft  | `cancelCount(runnerId, last 1 min) > threshold` → log WARNING + alerta                   |

#### 7.4.2 Cálculo de Exposição do Runner

A exposição de um Runner é a soma de todo o capital reservado para suas operações ativas:

```
runnerExposure = Σ(reservedAmount) para todas as Transactions do Runner
                 em estados PENDING, SUBMITTED ou PARTIAL
```

**Implementação eficiente:** Em vez de recalcular a cada request, o Portfolio pode manter um campo `currentExposure` no `MarginAccount` (por exchange) e incrementar/decrementar atomicamente:

```
-- No reserve():
UPDATE margin_accounts
SET reserved_capital = reserved_capital + :amount
WHERE portfolio_id = :pid AND exchange_id = :eid

-- No confirmExecution() / release():
UPDATE margin_accounts
SET reserved_capital = reserved_capital - :amount
WHERE portfolio_id = :pid AND exchange_id = :eid
```

#### 7.4.3 Hard Limits vs Soft Limits

| Tipo           | Comportamento                                                   | Quando Usar                                                 |
|----------------|-----------------------------------------------------------------|-------------------------------------------------------------|
| **Hard Limit** | Rejeição imediata do Capital Request. O Runner descarta o sinal | Proteção crítica: saldo, posições abertas, ordens pendentes |
| **Soft Limit** | Log WARNING + métricas + alerta ao operador. Operação permitida | Monitoramento: cancel rate, frequência de rejeições         |

**Decisão:** Na V1, todos os limites configurados no StrategyRunner (maxAllocationPercent, maxOpenPositions, maxPendingOrders) são **Hard Limits**. Soft Limits são implementados como métricas de monitoramento sem bloqueio.

#### 7.4.4 Mutabilidade de Limites

Os limites podem ser alterados em runtime via API administrativa sem reiniciar o Runner:

```
Regras de alteração:
- maxAllocationPercent: alterável a qualquer momento (efeito no próximo reserve())
- maxOpenPositions: alterável; se reduzido abaixo do atual, NÃO fecha posições existentes
  (apenas bloqueia novas aberturas até que a contagem diminua naturalmente)
- maxPendingOrders: idem
```

### 7.5 Modos de Capital Pooling

> **Estado (T39, 2026-10-16):** **DEDICATED descartado na v1**: só SHARED tem comportamento.

> **Referência:** Blueprint Seção 11.2.C.

O Portfolio suporta dois modos de visibilidade de capital, configuráveis via campo `capitalPoolingMode`.

#### 7.5.1 Shared Pool (Default)

Todos os Runners competem pelo mesmo `AvailableBalance`. A alocação segue FIFO (First-Come, First-Served) por ordem de chegada dos Capital Requests.

```
Portfolio (Shared Pool)
┌─────────────────────────────────────────────────────┐
│  AvailableBalance: 10,000 USDT                      │
│                                                     │
│  Runner A ──reserve(3000)──► Available=7000         │
│  Runner B ──reserve(5000)──► Available=2000         │
│  Runner C ──reserve(4000)──► REJECTED (2000 < 4000) │
└─────────────────────────────────────────────────────┘
```

**Características:**
- Máxima eficiência de capital — todo o saldo está disponível para qualquer Runner
- Risco de starvation: Runner de alta frequência pode consumir tudo
- Mitigação: `maxAllocationPercent` limita a exposição individual

#### 7.5.2 Dedicated Buckets

O Portfolio reserva fatias fixas do saldo para Runners específicos. Cada Runner só pode usar seu bucket.

```
Portfolio (Dedicated Buckets)
┌────────────────────────────────────────────────────────────────┐
│  Total Balance: 10,000 USDT                                    │
│                                                                │
│  Runner A [bucket: 4000] ──reserve(3000)──► OK (1000 restante) │
│  Runner B [bucket: 4000] ──reserve(5000)──► REJECTED           │
│  Runner C [bucket: 2000] ──reserve(1500)──► OK (500 restante)  │
│                                                                │
│  Capital não alocado a buckets: 0 USDT                         │
└────────────────────────────────────────────────────────────────┘
```

**Características:**
- Isolamento total: falha de um Runner não afeta outros
- Ineficiência: capital ocioso em um bucket não é acessível por outros Runners
- Melhor para: diversificação de risco entre strategies com perfis muito diferentes

#### 7.5.3 Implementação dos Modos

| Aspecto                  | Shared Pool                                               | Dedicated Buckets                                      |
|--------------------------|-----------------------------------------------------------|--------------------------------------------------------|
| **Validação de saldo**   | `globalBalance.availableBalance >= requiredAmount`        | `runnerBucket.availableAmount >= requiredAmount`       |
| **Persistência**         | Sem campo adicional                                       | Campo `dedicatedBudget` no StrategyRunner              |
| **Reserva**              | Debita do GlobalBalance                                   | Debita do bucket do Runner                             |
| **Estorno**              | Credita no GlobalBalance                                  | Credita no bucket do Runner                            |
| **Mudança de modo**      | Requer redistribuição: soma de buckets → AvailableBalance | Requer alocação: AvailableBalance ÷ Runners → buckets  |
| **Alteração em runtime** | Sim (efeito imediato)                                     | Sim, mas exige que nenhum Runner tenha operação em voo |

**Regra de troca de modo:** A mudança de `SHARED` para `DEDICATED` (ou vice-versa) é uma operação administrativa que requer:
1. Todos os Runners em `ACTIVE` sem operações em voo (PENDING/SUBMITTED = 0)
2. Ou: todos os Runners em `HALTED`
3. A operação é atômica — persiste o novo modo e os buckets na mesma tx

### 7.6 Política de Alocação FIFO

> **Referência:** Blueprint Seção 11.2.A.

O Portfolio processa Capital Requests na **ordem cronológica de recebimento**. Não há fila de espera — cada request é imediatamente aprovado ou rejeitado.

#### 7.6.1 Regras de Processamento

| Regra                            | Decisão                                                     | Justificativa                                            |
|----------------------------------|-------------------------------------------------------------|----------------------------------------------------------|
| **Sem fila de espera**           | Se saldo insuficiente, rejeita imediatamente                | Fila causaria Stale Orders — preço muda enquanto aguarda |
| **Sem prioridade entre Runners** | Primeiro a chegar, primeiro a ser servido                   | Simplicidade; sem favoritismo entre strategies           |
| **Serialização**                 | Requests são processados um por vez (lock no GlobalBalance) | Previne double-spending por race condition               |
| **Latência mínima**              | O lock deve ser o mais curto possível (~1-5ms)              | Portfolio não pode ser gargalo dos Runners               |

#### 7.6.2 Concorrência no `reserve()`

> **Estado (T39, 2026-10-16):** Implementado como `UPDATE global_balances … WHERE available_balance >= :amount` atômico (`GlobalBalanceJdbcRepository.reserveAtomic`), sem `SELECT … FOR UPDATE` nem tabela de reservas.

**Problema:** Se 10 Runners chamam `reserve()` simultaneamente, como garantir atomicidade?

**Solução:** Lock pessimista no nível do banco de dados. O `reserve()` executa um `SELECT ... FOR UPDATE` na linha do `GlobalBalance`, garantindo serialização sem deadlock:

```
-- Pseudocódigo SQL dentro de reserve()
BEGIN TRANSACTION;

  SELECT available_balance, reserved_balance
  FROM global_balances
  WHERE portfolio_id = :pid
  FOR UPDATE;  -- lock exclusivo na linha

  -- validações (steps 1-6 da cadeia)

  IF available_balance >= required_amount THEN
    UPDATE global_balances
    SET available_balance = available_balance - :required_amount,
        reserved_balance = reserved_balance + :required_amount,
        updated_at = NOW()
    WHERE portfolio_id = :pid;

    INSERT INTO reservations (...) VALUES (...);

    COMMIT;  -- libera o lock
    RETURN APPROVED;
  ELSE
    ROLLBACK;  -- libera o lock imediatamente
    RETURN REJECTED(INSUFFICIENT_FUNDS);
  END IF;
```

**Características do lock:**
- Escopo: uma única linha da tabela `global_balances`
- Duração: tempo das validações + UPDATE (~1-5ms)
- Contenção: apenas em cenários de alta concorrência entre Runners
- Deadlock-free: o lock é sempre na mesma tabela/linha, sem dependências cruzadas

### 7.7 Monitoramento e Métricas de Capital

> **Estado (T51, 2026-10-19):** `capital.utilization.runner` (exposição em voo ÷ limite, mesma conta da reserva), `capital.utilization.global`, `capital.reservation.oldest.age.seconds` (idade da ordem em voo mais antiga, no lugar da duração média) por amostra a cada 15s; rejection rate e cancel rate derivados de `signal.evaluated.total` em PromQL, com alertas no Grafana.

> **Nota de Implementação #20 absorvida.**

O Portfolio deve registrar métricas de capital para detectar anomalias e calibrar limites.

#### 7.7.1 Métricas por Runner

| Métrica                      | Cálculo                                                                   | Alerta                                               |
|------------------------------|---------------------------------------------------------------------------|------------------------------------------------------|
| **Rejection Rate**           | `rejectCount(runnerId, last N min) / totalRequests(runnerId, last N min)` | > 50% → Runner mal calibrado para o tamanho da conta |
| **Capital Utilization**      | `currentExposure / maxAllocationLimit`                                    | > 90% → próximo do limite, considerar aumentar       |
| **Average Reserve Duration** | Tempo médio entre `reserve()` e `confirmExecution()` / `release()`        | > 5 min → ordens travadas, possível problema         |
| **Cancel Rate**              | `cancelCount(runnerId, last 1 min)`                                       | > threshold → possível loop de order/cancel          |

#### 7.7.2 Métricas Globais do Portfolio

| Métrica                   | Cálculo                                                | Alerta                               |
|---------------------------|--------------------------------------------------------|--------------------------------------|
| **Global Utilization**    | `totalReserved / (availableBalance + reservedBalance)` | > 80% → risco de rejeição em cascata |
| **Reserve Throughput**    | `reserveCount(last 1 min)`                             | Baseline para capacity planning      |
| **Average Lock Duration** | Tempo médio que o lock do GlobalBalance fica ativo     | > 10ms → possível gargalo            |
| **DLQ Depth**             | `COUNT(dead_letter_entries WHERE is_resolved = false)` | > 0 → requer atenção                 |

#### 7.7.3 Ação em Alta Rejection Rate

Se um Runner acumula alta taxa de rejeição, as causas possíveis são:

| Causa                             | Diagnóstico                                                   | Ação                                                |
|-----------------------------------|---------------------------------------------------------------|-----------------------------------------------------|
| Limite muito baixo                | `maxAllocationPercent` insuficiente para o tamanho das ordens | Aumentar `maxAllocationPercent` via API admin       |
| Saldo insuficiente                | AvailableBalance esgotado por outros Runners                  | Considerar `DEDICATED` pooling ou aumentar capital  |
| Strategy agressiva                | Frequência de sinais > capacidade de capital                  | Ajustar parâmetros da Strategy (cooldown, quantity) |
| Runner calibrado para conta maior | Quantidades calculadas para um saldo que não existe           | Recalibrar Strategy com `PortfolioContext`          |

### 7.8 Fluxo Completo: Do Sinal à Reserva

Diagrama consolidado integrando Runner (validação) e Portfolio (reserva):

```
Strategy
   │ TradingDecision(BUY, BTCUSDT, 0.05, $50000, confidence=0.85)
   ▼
Runner ─────────────────────────────────────────────────
   │
   ├─ V1: ExecutionPolicy(SINGLE) → sem posição aberta ✓
   ├─ V2: confidence(0.85) >= minThreshold(0.7) ✓
   ├─ V3: quantity(0.05) >= minQty(0.00001) ✓
   ├─ V4: notional(0.05 × 50000 = 2500) >= minNotional(5) ✓
   ├─ V5: price sanity check ✓
   │
   │── persist Transaction(PENDING, clientOrderId=v1r01ft...)
   │
   │── ReserveCapitalPort.reserve(amount=2500)   (LIMIT, sem buffer — §7.2)
   │                          ──────────────────────────────
Portfolio ──────────────────────────────────────────────
   │
   ├─ Step 1: safeModeStatus == NORMAL ✓
   ├─ Step 2: runner pertence ao portfolio ✓
   ├─ Step 3: sem reserva duplicada ✓
   ├─ Step 4: runnerExposure(0) + 2500 <= 20% × 50000 = 10000 ✓
   ├─ Step 5: requiredAmount = 2500
   ├─ Step 6: availableBalance(10000) >= 2500 ✓
   ├─ Step 7: available=7500, reserved=2500
   │
   └─ return APPROVED(reservationId=uuid)
       ──────────────────────────────────────────────
Runner (continuação)
   │
   └─ dispatch order à Exchange via ExchangeAdapter
```

### 7.9 Configuração

> **Estado (T39, 2026-10-16):** Nenhuma chave desta tabela existe hoje; as de limites e métricas nascem na T50/T51.

| Parâmetro                              | Default  | Descrição                                         |
|----------------------------------------|----------|---------------------------------------------------|
| `capital.safety-buffer.market-order`   | 1.005    | **Não implementado** — entra com a task de `MARKET` (§7.2.2) |
| `capital.pooling-mode`                 | SHARED   | Modo de visibilidade: SHARED ou DEDICATED         |
| `capital.global-utilization-alert`     | 0.80     | Threshold de alerta de utilização global (80%)    |
| `capital.lock-timeout-ms`              | 5000     | Timeout do lock pessimista no GlobalBalance       |
| `runner.min-confidence-threshold`      | 0.5      | Confidence mínima para aceitar sinal              |
| `runner.price-sanity-deviation`        | 0.10     | Desvio máximo do preço vs lastPrice (10%)         |
| `runner.cancel-rate-threshold`         | 10       | Cancelamentos/min para alerta (Soft Limit)        |
| `monitoring.rejection-rate-window-min` | 5        | Janela de tempo para cálculo de rejection rate    |
| `monitoring.rejection-rate-alert`      | 0.50     | Threshold de alerta de rejection rate (50%)       |

### 7.10 Implicações Arquiteturais

| Decisão                                        | Justificativa                                                                      | Trade-off                                                          |
|------------------------------------------------|------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| Sem buffer para LIMIT; buffer só para MARKET (futuro), no reserve e não na ordem | LIMIT não executa acima do limite; buffer seria só capital travado (T36) | Introduzir MARKET exige entregar o buffer junto; excedente sempre estornado (§7.2.3) |
| Validação em duas camadas (Runner + Portfolio) | Separação de responsabilidades: Runner valida negócio, Portfolio valida financeiro | Overhead de duas validações por request (negligível)               |
| Lock pessimista SELECT FOR UPDATE              | Garante atomicidade sem race condition. Lock de escopo mínimo (~1-5ms)             | Serializa requests concorrentes (aceitável para monólito V1)       |
| Sem fila de espera                             | Previne Stale Orders — preços mudam enquanto aguarda                               | Runner perde sinal se capital insuficiente (custo de oportunidade) |
| Hard Limits como default                       | Proteção determinística — sem margem para erro                                     | Menos flexível que Soft Limits (operador pode ajustar via API)     |
| Métricas sem tabela dedicada                   | Métricas derivadas dos dados existentes (Transactions, GlobalBalance)              | Queries mais complexas; considerar cache se performance degradar   |
| Dedicated Buckets como opção                   | Isolamento total entre Runners para perfis de risco distintos                      | Capital ocioso não é compartilhável                                |

---

## 8. Contabilidade: Fees, Precisão e Arredondamento

Esta seção detalha **como implementar** a política de taxas (captura, conversão cross-currency, fallback), a precisão decimal com BigDecimal, a política de arredondamento na borda com a Exchange, o cálculo de preço médio ponderado (WAP) e a gestão de resíduos contábeis (DustAccount).

> **Referência:** Blueprint Seção 9 (Contabilidade e Precisão Financeira).
> **Notas absorvidas:** #5 (Gestão de Fees Cross-Currency), #15 (RoundingPolicy Centralizada), #16 (Conversão de Tipos I/O), #29 (Precisão WAP), #30 (Sincronização de Cache), #31 (Reset de Posição).

### 8.1 Política de Taxas (Fees)

> **Referência:** Blueprint Seção 9.1.
> **Nota de Implementação #5 absorvida.**

#### 8.1.1 Captura de Fees

As taxas são **extraídas do callback da exchange**, nunca estimadas ou calculadas localmente.

| Aspecto              | Decisão                                                            |
|----------------------|--------------------------------------------------------------------|
| **Fonte**            | Payload do evento de execução (PARTIAL ou FILLED) da exchange      |
| **Campos extraídos** | `feeAmount` (BigDecimal), `feeAsset` (String — ex: BNB, USDT, BTC) |
| **Momento**          | No processamento de cada `TransactionMatch` pelo Runner            |
| **Persistência**     | Fee VO embutido no `TransactionMatch` (Seção 3.3.4)                |
| **Imutabilidade**    | Uma vez registrada, a Fee é imutável — sem ajustes posteriores     |

**Fluxo de captura:**

```
Exchange callback (execution event)
       │
Runner: extrair fee do payload
       │
       ├─ feeAmount = payload.fee.amount
       ├─ feeAsset  = payload.fee.asset
       ├─ feeType   = inferir MAKER/TAKER do payload (ou UNKNOWN)
       │
Runner: criar TransactionMatch com Fee embutido
       │
       ├─ match.feeAmount = feeAmount
       ├─ match.feeAsset  = feeAsset
       ├─ match.feeType   = feeType
       │
Runner: publicar ExecutionConfirmation ao Portfolio
       │ (contém fee para dedução do GlobalBalance)
```

#### 8.1.2 Impacto Dual das Fees

As fees afetam **simultaneamente** dois contextos:

| Contexto                                          | Impacto                        | Cálculo                                                           |
|---------------------------------------------------|--------------------------------|-------------------------------------------------------------------|
| **PnL do Runner** (visão estratégica)             | Reduz o PnL realizado do match | `pnlRealized = (sellPrice - buyPrice) × qty - feeConverted`       |
| **GlobalBalance do Portfolio** (visão financeira) | Deduz do Available             | `availableBalance -= feeConverted; totalFeesPaid += feeConverted` |

**Timing de dedução:** A liquidação financeira das Fees segue rigorosamente a máquina de estados (Seção 6.1):
- `PARTIAL`: Fee proporcional à fatia executada, deduzida incrementalmente
- `FILLED`: Fee final, completa a dedução

#### 8.1.3 Conversão Cross-Currency

Quando a exchange cobra a fee em um ativo diferente do par operado (ex: fee em BNB para trade de BTCUSDT), o Portfolio realiza uma **conversão sintética imediata**.

> **Resposta às questões da Nota #5:**
> - **Quando converter?** No instante do `TransactionMatch` — nunca adiado
> - **Quem converte?** O Portfolio (autoridade financeira)
> - **Com qual preço?** Mark Price do momento do match (via ExchangeAdapter)
> - **E se não houver saldo do ativo da fee?** Conversão sintética — debita o equivalente em baseCurrency do AvailableBalance

**Protocolo de conversão:**

```
Portfolio recebe ExecutionConfirmation com fee(0.001 BNB)
       │
Step 1: Verificar se feeAsset == baseCurrency
       │ BNB != USDT → conversão necessária
       │
Step 2: Obter Mark Price
       │ ── Fonte primária: ExchangeAdapter.getMarkPrice("BNBUSDT")
       │
Step 3: Converter
       │ feeConverted = feeAmount × markPrice
       │ ex: 0.001 BNB × 600 USDT/BNB = 0.60 USDT
       │
Step 4: Deduzir do GlobalBalance
       │ availableBalance -= 0.60 USDT
       │ totalFeesPaid   += 0.60 USDT
       │
Step 5: Registrar no TransactionMatch
       │ match.feeConvertedAmount = 0.60
```

#### 8.1.4 Protocolo de Fallback (Falha de Precificação)

O sistema adota uma abordagem **conservadora de 2 níveis**: ou converte com preço real, ou registra como dívida técnica. Não utiliza preços em cache, preços da execução ou estimativas — qualquer valor que não seja o Mark Price em tempo real pode introduzir divergência silenciosa no GlobalBalance.

| Nível                               | Ação                                                                                              | Condição                                 |
|-------------------------------------|---------------------------------------------------------------------------------------------------|------------------------------------------|
| **1. Mark Price (tempo real)**      | `ExchangeAdapter.getMarkPrice(feeAsset + baseCurrency)` → converte e debita do GlobalBalance      | API disponível, preço obtido com sucesso |
| **2. DustAccount (dívida técnica)** | Registra débito no ativo original na `DustAccount` com timestamp. **Não** debita do GlobalBalance | Qualquer falha na obtenção do Mark Price |

**Justificativa da simplificação:** Preços em cache (stale) ou preços derivados da execução podem comprometer a integridade do GlobalBalance. O custo de registrar uma dívida técnica (reconciliação posterior pelo Worker de Sweep) é menor que o risco de um saldo incorreto que só seria detectado na próxima reconciliação.

**Fallback — DustAccount (Technical Debt):**

```
Se Mark Price falhar:
       │
       ├─ NÃO interromper a execução
       ├─ Registrar na DustAccount:
       │     sourceType = TECHNICAL_DEBT
       │     originalAsset = "BNB"
       │     originalAmount = 0.001
       │     convertedAmount = null (pendente)
       │     transactionId = match.transactionId
       │     createdAt = match.createdAt (timestamp original)
       │
       ├─ Não deduzir do GlobalBalance (conversão pendente)
       │
       └─ Log WARNING: "Fee conversion failed, debt registered"
```

**Reconciliação histórica determinística:** O Worker de Sweep da DustAccount (Seção 8.5) é obrigado a usar o **preço histórico do timestamp original**, não o preço atual. Isso garante que o PnL seja idêntico ao que seria com o sistema 100% estável.

**Irreversibilidade:** Uma vez calculada e debitada a fee convertida, o valor é **final**. Sem ajustes posteriores por oscilação de câmbio. PnL líquido é imutável após efetivação.

#### 8.1.5 Auditoria de Fees

O Portfolio mantém rastro de fees por múltiplas dimensões:

| Dimensão         | Campo                                              | Uso                                  |
|------------------|----------------------------------------------------|--------------------------------------|
| **Global**       | `GlobalBalance.totalFeesPaid`                      | Custo total de operação do portfolio |
| **Por Exchange** | `MarginAccount.totalFeesPaid`                      | Eficiência por exchange              |
| **Por Match**    | `TransactionMatch.feeAmount/feeAsset/feeType`      | Auditoria granular                   |
| **Por Runner**   | `SUM(match.feeConvertedAmount) WHERE runnerId = X` | Eficiência por estratégia            |

### 8.2 Precisão Decimal e BigDecimal

> **Referência:** Blueprint Seção 9.2.A.
> **Notas de Implementação #15 e #16 absorvidas.**

#### 8.2.1 Regra Fundamental: Banimento de Double/Float

Para cálculos financeiros, o sistema **proíbe** tipos de ponto flutuante nativos (`double`, `float`). Todo o domínio utiliza `BigDecimal` (Java).

| Camada                     | Tipo Obrigatório        | Justificativa                                               |
|----------------------------|-------------------------|-------------------------------------------------------------|
| **Domain** (entities, VOs) | `BigDecimal`            | Precisão arbitrária, sem erros de representação             |
| **Persistence** (DB)       | `NUMERIC(30,8)`         | Armazenamento exato com escala fixa                         |
| **API/WebSocket input**    | `String` → `BigDecimal` | Conversão imediata no recebimento (Nota #16)                |
| **API/WebSocket output**   | `BigDecimal` → `String` | Evita notação científica e erros de serialização (Nota #16) |

#### 8.2.2 Precisão Interna vs Precisão de Borda

O sistema opera com **duas escalas de precisão**:

| Contexto                         | Escala                                              | Modo de Arredondamento                             | Quando                           |
|----------------------------------|-----------------------------------------------------|----------------------------------------------------|----------------------------------|
| **Interno** (cálculos, PnL, WAP) | 12 casas decimais                                   | Nenhum — manter precisão máxima                    | Durante todo o processamento     |
| **Borda** (envio à exchange)     | Variável por ativo (`lotStepSize`, `priceTickSize`) | Floor/Down para quantidade; conservador para preço | Último momento antes do dispatch |

**Constantes de precisão:**

```
INTERNAL_SCALE      = 12     // Casas decimais para cálculos internos
INTERNAL_ROUNDING   = UNNECESSARY  // Erro se precisão insuficiente
DISPLAY_SCALE       = 8      // Casas decimais para APIs e UI
DISPLAY_ROUNDING    = HALF_UP     // Arredondamento para exibição
```

#### 8.2.3 Conversão de Tipos na I/O

> **Nota de Implementação #16 absorvida.**

**Entrada (Exchange → Sistema):**

```
// No ExchangeAdapter / WebSocket Listener:
String rawPrice = payload.get("price");       // "50123.45000000"
BigDecimal price = new BigDecimal(rawPrice);  // Conversão imediata
// NUNCA: Double.parseDouble(rawPrice)
```

**Saída (Sistema → Exchange):**

```
// No ExchangeAdapter, antes do envio:
BigDecimal quantity = new BigDecimal("0.05123456789012");
String formatted = quantity.toPlainString();  // "0.05123456789012"
// NUNCA: String.valueOf(quantity) — pode gerar notação científica
```

**Regras de serialização JSON:**

| Direção            | Formato                                             | Exemplo                               |
|--------------------|-----------------------------------------------------|---------------------------------------|
| Exchange → Sistema | `String` → `BigDecimal` (via `new BigDecimal(str)`) | `"50123.45"` → `BigDecimal(50123.45)` |
| Sistema → Exchange | `BigDecimal` → `String` (via `toPlainString()`)     | `BigDecimal(0.00012)` → `"0.00012"`   |
| Sistema → REST API | `BigDecimal` serializado como `String` no JSON      | `{"price": "50123.45000000"}`         |

### 8.3 Política de Arredondamento na Borda (Exchange)

> **Referência:** Blueprint Seção 9.2.B.
> **Nota de Implementação #15 absorvida.**

O arredondamento ocorre no **último momento possível** — dentro do Runner, imediatamente antes do Order Dispatch.

#### 8.3.1 Serviço `AssetFormat`

O Runner deve carregar as propriedades de precisão do ativo da exchange e encapsulá-las em um serviço/VO:

```
AssetFormat {
    symbol: String              // ex: "BTCUSDT"
    lotStepSize: BigDecimal     // ex: 0.00001 (5 decimais de quantity)
    priceTickSize: BigDecimal   // ex: 0.01 (2 decimais de price)
    minQty: BigDecimal          // ex: 0.00001
    maxQty: BigDecimal          // ex: 9000.0
    minNotional: BigDecimal     // ex: 5.0 USDT

    formatQuantity(raw: BigDecimal): BigDecimal
    formatPrice(raw: BigDecimal, side: BUY|SELL): BigDecimal
    validate(quantity: BigDecimal, price: BigDecimal): ValidationResult
}
```

#### 8.3.2 Direção do Arredondamento

| Campo                 | Direção            | Modo (`RoundingMode`) | Justificativa                                                             |
|-----------------------|--------------------|-----------------------|---------------------------------------------------------------------------|
| **Quantity (sempre)** | Floor (para baixo) | `FLOOR`               | Comprar 0.0001 a menos é preferível a rejeição por "insufficient balance" |
| **Price (BUY)**       | Floor (para baixo) | `FLOOR`               | Mais conservador — paga menos                                             |
| **Price (SELL)**      | Ceil (para cima)   | `CEILING`             | Mais conservador — recebe mais                                            |

**Implementação do truncamento por stepSize:**

```
formatQuantity(raw):
    // Truncar para o stepSize mais próximo (para baixo)
    steps = raw.divideToIntegralValue(lotStepSize)
    return steps.multiply(lotStepSize)
    // Ex: raw=0.05123, stepSize=0.0001 → 512 steps → 0.0512

formatPrice(raw, BUY):
    steps = raw.divideToIntegralValue(priceTickSize)
    return steps.multiply(priceTickSize)
    // Ex: raw=50123.456, tickSize=0.01 → 5012345 steps → 50123.45

formatPrice(raw, SELL):
    steps = raw.divide(priceTickSize, 0, CEILING)
    return steps.multiply(priceTickSize)
    // Ex: raw=50123.451, tickSize=0.01 → 5012346 steps → 50123.46
```

#### 8.3.3 Validação Pós-Arredondamento

Após formatar, o Runner valida que os valores arredondados ainda são operáveis:

```
Validação pós-formato:
    if (formattedQuantity < minQty) → descarta sinal ("quantity below minimum")
    if (formattedQuantity > maxQty) → descarta sinal ("quantity above maximum")
    if (formattedQuantity × formattedPrice < minNotional) → descarta sinal
    if (formattedQuantity == 0) → descarta sinal
```

### 8.4 Preço Médio Ponderado (WAP)

> **Referência:** Blueprint Seção 9.3.
> **Notas de Implementação #29, #30, #31 absorvidas.**

#### 8.4.1 Fórmula de Cálculo

O preço médio é calculado exclusivamente sobre execuções realizadas (TransactionMatches de compra):

```
averagePrice = Σ(executionPrice × executionQuantity) / Σ(executionQuantity)
```

**Regras:**
- Fees **não** são incorporadas ao preço médio — são dedução separada no PnL
- Cada `TransactionMatch` de compra contribui para o cálculo
- Vendas parciais **não alteram** o preço médio da posição restante

#### 8.4.2 Precisão no Cálculo

> **Nota de Implementação #29 absorvida.**

O cálculo intermediário deve usar **precisão estendida** para evitar perda de centavos em posições massivas:

```
// Cálculo com precisão estendida
BigDecimal totalCost = BigDecimal.ZERO;
BigDecimal totalQty  = BigDecimal.ZERO;

for (match : buyMatches) {
    // Intermediário com 18 casas decimais
    BigDecimal cost = match.price.multiply(match.quantity,
        new MathContext(18, UNNECESSARY));
    totalCost = totalCost.add(cost);
    totalQty  = totalQty.add(match.quantity);
}

// Resultado final com escala interna (12 casas)
BigDecimal avgPrice = totalCost.divide(totalQty, 12, HALF_UP);
```

**Constantes:**

| Etapa                            | Escala      | RoundingMode |
|----------------------------------|-------------|--------------|
| Intermediário (`price × qty`)    | 18 decimais | UNNECESSARY  |
| Soma acumulada                   | 18 decimais | UNNECESSARY  |
| Resultado final (`averagePrice`) | 12 decimais | HALF_UP      |

#### 8.4.3 Dinâmica de Atualização

| Evento                               | Impacto no averagePrice                     | Fórmula                                                     |
|--------------------------------------|---------------------------------------------|-------------------------------------------------------------|
| **Nova compra (BUY FILLED/PARTIAL)** | Recalcula WAP com o novo match              | `(oldAvg × oldQty + newPrice × newQty) / (oldQty + newQty)` |
| **Scaling (Netting mode)**           | Idem — novas compras incorporadas           | Mesmo cálculo                                               |
| **Venda parcial**                    | **Sem alteração** — apenas reduz `quantity` | `averagePrice` permanece                                    |
| **Posição zerada**                   | Reset (ver 8.4.5)                           | `averagePrice = 0` ou `null`                                |

#### 8.4.4 Persistência vs Cálculo sob Demanda

| Aspecto           | Decisão                                                                                     |
|-------------------|---------------------------------------------------------------------------------------------|
| **Persistência**  | `averagePrice` é **campo persistido** na entidade `Position`                                |
| **Justificativa** | Calcular sob demanda via milhares de TransactionMatches seria lento para o motor de decisão |
| **Atualização**   | Atômica, disparada pelo evento de `TransactionMatch` de compra                              |
| **Auditoria**     | O cálculo pode ser reconstruído a partir dos TransactionMatches a qualquer momento          |

#### 8.4.5 Reset de Posição (Quantidade Zero)

> **Nota de Implementação #31 absorvida.**

Quando a quantidade da posição chega a zero:

```
if (position.quantity == 0 after sell match):
    position.averagePrice = BigDecimal.ZERO
    position.status = CLOSED
    position.closedAt = Instant.now()
    // Histórico preservado nos TransactionMatches — não precisa de TradeHistory separado
```

**Decisão: Sem entidade TradeHistory separada.** A Nota #31 sugere mover o histórico para uma entidade `TradeHistory`. No modelo alvo, os `TransactionMatches` já preservam todo o histórico necessário (preços de compra/venda, fees, PnL por match). A Position com `status=CLOSED` permanece no banco para consulta, e os Matches vinculados fornecem a auditoria completa.

#### 8.4.6 Sincronização de Cache

> **Nota de Implementação #30 absorvida.**

Se o Runner mantiver estado em memória (cache local para o `PositionContext` injetado na Strategy):

| Regra                    | Implementação                                                                                                                  |
|--------------------------|--------------------------------------------------------------------------------------------------------------------------------|
| **Invalidação imediata** | Após cada `TransactionMatch`, o cache de `averagePrice` é invalidado                                                           |
| **Write-through**        | O Runner atualiza DB **e** cache na mesma operação                                                                             |
| **Fallback**             | Se o cache estiver stale (flag de invalidação), o Runner lê do DB antes de montar o `PositionContext`                          |
| **Single-thread**        | O modelo de concorrência do Runner (Seção 12) garante que apenas uma thread acessa o cache por Runner — sem race condition |

### 8.5 Gestão de Resíduos Contábeis (DustAccount)

> **Referência:** Blueprint Seção 9.2.C.

A DustAccount (Seção 3.2.4) acumula resíduos que precisam ser processados periodicamente.

#### 8.5.1 Fontes de Entrada

| Fonte              | Quando Ocorre                                                             | Exemplo                                                 |
|--------------------|---------------------------------------------------------------------------|---------------------------------------------------------|
| **Rounding Dust**  | No `TransactionMatch`: quantidade restante do lote < `minQty` da exchange | Lote de 0.00000003 BTC restante após venda — inoperável |
| **Technical Debt** | Fallback crítico na conversão de Fee (Seção 8.1.4)                        | Fee de 0.001 BNB sem cotação para conversão             |

**Protocolo de registro de Rounding Dust:**

```
Após TransactionMatch de SELL:
    remainingQty = buyLot.quantity - totalMatchedFromLot
    if (remainingQty > 0 && remainingQty < exchange.minQty):
        // Lote inoperável — registrar como dust
        dustAccount.register(
            sourceType = ROUNDING_DUST,
            originalAsset = symbol.baseAsset,  // ex: BTC
            originalAmount = remainingQty,
            runnerId = runner.id,
            transactionId = sellMatch.sellTransactionId
        )
        // Marcar lote como CLOSED (mesmo com qty > 0)
        position.closeDustLot(remainingQty)
```

#### 8.5.2 Worker de Sweep (Reconciliação Periódica)

Um Worker periódico do Portfolio converte os resíduos acumulados na DustAccount para a moeda base:

```
DustSweepWorker (executa a cada N minutos):
       │
Step 1: Buscar entries não resolvidas
       │ SELECT * FROM dust_accounts WHERE is_resolved = false
       │
Step 2: Para cada entry:
       │
       ├─ Se sourceType == ROUNDING_DUST:
       │     price = ExchangeAdapter.getMarkPrice(originalAsset + baseCurrency)
       │     convertedAmount = originalAmount × price (preço ATUAL)
       │
       ├─ Se sourceType == TECHNICAL_DEBT:
       │     price = getHistoricalPrice(originalAsset, entry.createdAt)
       │     convertedAmount = originalAmount × price (preço HISTÓRICO)
       │     ⚠ OBRIGATÓRIO usar timestamp original para determinismo
       │
Step 3: Atualizar entry
       │ entry.convertedAmount = convertedAmount
       │ entry.isResolved = true
       │ entry.resolvedAt = now()
       │
Step 4: Reintegrar ao GlobalBalance
       │ globalBalance.availableBalance += convertedAmount
       │
       └─ Log: "Dust swept: {amount} {asset} → {converted} {baseCurrency}"
```

**Regra de preço por tipo:**

| Tipo               | Preço de Conversão                       | Justificativa                                         |
|--------------------|------------------------------------------|-------------------------------------------------------|
| **Rounding Dust**  | Preço **atual** (Mark Price)             | Resíduo operacional sem timestamp significativo       |
| **Technical Debt** | Preço **histórico** (timestamp original) | Garantir determinismo — PnL idêntico ao cenário ideal |

#### 8.5.3 Impacto no PnL e Equity

| Componente                      | Tratamento                                                                                   |
|---------------------------------|----------------------------------------------------------------------------------------------|
| **AvailableBalance**            | Exclui DustAccount pendente — capital "limpo"                                                |
| **Equity (patrimônio líquido)** | Exibe separadamente: `Equity = Available + Reserved + DustPendente`                          |
| **PnL dos Runners**             | Micro-diferenças de rounding dust → perda operacional irrelevante                            |
| **Observabilidade**             | Dashboard mostra acúmulo por Runner, permitindo identificar strategies com resíduo excessivo |

### 8.6 Fórmulas de PnL

O sistema opera exclusivamente com **PnL Líquido** — fees são deduzidas imediatamente.

#### 8.6.1 PnL por TransactionMatch

```
pnlRealized = (sellPrice - buyPrice) × matchedQuantity - feeConverted
```

Onde:
- `sellPrice`: preço de execução da venda (denormalized no match)
- `buyPrice`: preço de execução da compra (denormalized no match)
- `matchedQuantity`: quantidade casada neste match
- `feeConverted`: fee convertida para baseCurrency (proporcional a este match)

#### 8.6.2 PnL por Position

```
realizedPnl = Σ(match.pnlRealized) para todos os matches da posição
```

#### 8.6.3 PnL Não Realizado (para Context Injection)

```
unrealizedPnl = (currentPrice - averagePrice) × quantity
```

**Nota:** O PnL não realizado **não** deduz fees futuras (impossível estimar). Ele representa a variação bruta da posição aberta.

#### 8.6.4 PnL Global do Portfolio

```
totalRealizedPnl = Σ(position.realizedPnl) para todos os Runners
totalUnrealizedPnl = Σ(position.unrealizedPnl) para posições OPEN
totalPnl = totalRealizedPnl + totalUnrealizedPnl
```

### 8.7 Configuração

| Parâmetro                                   | Default           | Descrição                                         |
|---------------------------------------------|-------------------|---------------------------------------------------|
| `precision.internal-scale`                  | 12                | Casas decimais para cálculos internos             |
| `precision.intermediate-scale`              | 18                | Casas decimais para cálculos intermediários (WAP) |
| `precision.display-scale`                   | 8                 | Casas decimais para APIs e UI                     |
| `fee.conversion.cache-ttl-seconds`          | 60                | Validade do cache de preço para conversão de fees |
| `fee.conversion.fallback-price-ttl-seconds` | 300               | Validade máxima do preço de fallback              |
| `dust.sweep.interval-minutes`               | 15                | Frequência de execução do DustSweepWorker         |
| `dust.sweep.batch-size`                     | 100               | Máximo de entries processadas por execução        |
| `dust.rounding.threshold`                   | Exchange `minQty` | Threshold para considerar quantidade como dust    |

### 8.8 Implicações Arquiteturais

| Decisão                                           | Justificativa                                               | Trade-off                                                      |
|---------------------------------------------------|-------------------------------------------------------------|----------------------------------------------------------------|
| BigDecimal obrigatório (ban de double/float)      | Elimina erros de representação em cálculos financeiros      | Performance ~10x menor que double (negligível para o volume)   |
| Arredondamento no último momento (borda)          | Máxima precisão interna; exchange recebe valores formatados | Runner precisa carregar `exchangeInfo` (lotStepSize, tickSize) |
| Fee capturada do callback (nunca estimada)        | Valor exato cobrado pela exchange — sem divergência         | Depende da qualidade do payload da exchange                    |
| Conversão cross-currency imediata                 | PnL reflete custo real no instante da execução              | Requer acesso ao Mark Price em tempo real                      |
| DustAccount com sweep periódico                   | Isola resíduos do capital de giro; reconciliação assíncrona | Pequeno delay na reintegração do dust ao Available             |
| averagePrice persistido (não calculado on-demand) | Acesso instantâneo para a Strategy via PositionContext      | Exige atualização atômica a cada match de compra               |
| Sem entidade TradeHistory separada                | TransactionMatches preservam todo o histórico necessário    | Queries de histórico requerem JOINs (mitigado por indexes)     |
| Preço histórico para Technical Debt               | Determinismo — PnL idêntico ao cenário ideal                | Requer API de preço histórico ou armazenamento local           |

---

## 9. Governança de Locks e Concorrência

Esta seção detalha **como implementar** o sistema de locks provisórios que garantem que dois sinais concorrentes nunca disputem o mesmo lote de compra. O lock é o mecanismo que protege a integridade contábil entre a intenção de venda e a execução efetiva na exchange.

> **Referência:** Blueprint Seção 8 (Governança de Locks e Concorrência), Seção 4.B (Locking Provisório), Seção 6.D (Saneamento de Locks Órfãos).
> **Nota de Implementação absorvida:** #7 (Governança de Locks em Cancelamentos).

### 9.1 Conceito Fundamental: Lock como Estado Provisório

O lock **não é uma entidade separada** — é um estado temporário aplicado a um lote de compra (Position/Buy Lot) enquanto uma transação de venda está em curso. O lock existe como um vínculo entre:

| Componente             | Papel                                                          |
|------------------------|----------------------------------------------------------------|
| **Position (Buy Lot)** | O lote que será potencialmente fechado                         |
| **Transaction (Sell)** | A transação de venda que reivindica o lote                     |
| **TransactionId**      | O elo imutável — todo lock referencia exatamente uma transação |

**Regra invariante:** Não existem locks órfãos. Todo lock está obrigatoriamente vinculado a uma `TransactionId` ativa (PENDING, SUBMITTED ou PARTIAL). Se a transação atingir um estado terminal, o lock é resolvido.

### 9.2 Ciclo de Vida do Lock

O lock herda o destino da transação à qual está vinculado — não possui timer independente.

```
                    ┌──────────────┐
                    │  Sinal SELL  │
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
                    │ Identificar  │  targetLotId ou FIFO
                    │   Lotes      │  (AccountingPolicy)
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
                    │ SELECT ...   │  Pessimistic Lock (FOR UPDATE)
                    │ FOR UPDATE   │  na linha do lote
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
                    │ LOCK ATIVO   │─────── lockedByTransactionId = TX.id
                    └──────┬───────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
       ┌──────▼──────┐ ┌──▼───────┐ ┌───▼──────────┐
       │   FILLED    │ │CANCELED  │ │  EXPIRED /   │
       │             │ │          │ │  REJECTED    │
       └──────┬──────┘ └───┬──────┘ └───┬──────────┘
              │            │            │
       ┌──────▼──────┐ ┌───▼─────┐ ┌────▼─────────┐
       │ Lock →      │ │ UNLOCK  │ │   UNLOCK     │
       │ Match       │ │ (free)  │ │   (free)     │
       │ definitivo  │ │         │ │              │
       └─────────────┘ └─────────┘ └──────────────┘
```

#### 9.2.1 Resolução por Sucesso (FILLED)

Quando a transação atinge `FILLED`:

```
Ao processar TransactionMatch (Seção 6):
    // O lock provisório se converte em match definitivo
    for each matchedLot in lockedLots:
        transactionMatch = createMatch(
            buyTransaction = matchedLot.buyTransaction,
            sellTransaction = currentTransaction,
            matchedQuantity = matchedLot.lockedQuantity
        )
        // Lock desaparece — o TransactionMatch é o registro permanente
        matchedLot.clearLock()
        matchedLot.reduceQuantity(matchedQuantity)
```

**Ponto chave:** O `TransactionMatch` é a materialização definitiva do lock. Após a criação do match, o lock deixa de existir — o match **é** o resultado.

#### 9.2.2 Resolução por Falha (CANCELED / EXPIRED / REJECTED)

Quando a transação atinge qualquer estado terminal de falha:

```
Ao processar cancelamento/expiração/rejeição:
    // Dentro da MESMA transação de banco que muda o status
    lockedLots = positionRepository.findByLockedByTransactionId(transaction.id)
    for each lot in lockedLots:
        lot.clearLock()  // lockedByTransactionId = null
        lot.status = OPEN  // Lote volta a estar disponível
    // Atomicidade: status da TX + unlock na mesma transação DB
```

> **Nota #7 absorvida:** "O processo de mudar a transação para `CANCELED` e desbloquear os lotes deve ocorrer dentro da mesma transação de banco de dados para evitar lotes fantasmas."

#### 9.2.3 Resolução por Partial Fill + Cancelamento

Cenário: ordem de venda parcialmente executada, depois cancelada (PARTIAL → CANCELED).

```
Ao processar PARTIAL → CANCELED:
    executedQty = Σ(transactionMatches.matchedQuantity)
    remainingQty = transaction.quantity - executedQty

    // Matches já criados (fatias executadas) → PERMANECEM
    // Locks da fatia NÃO executada → UNLOCK

    for each lot in lockedLots:
        alreadyMatched = Σ(matches vinculados a este lote)
        unexecutedLock = lot.lockedQuantity - alreadyMatched
        if (unexecutedLock > 0):
            lot.reduceLockedQuantity(unexecutedLock)
            if (lot.lockedQuantity == 0):
                lot.clearLock()
                lot.status = OPEN
```

**Regra:** Apenas a fatia não executada é desbloqueada. Os matches já efetivados para as fatias executadas são definitivos.

#### 9.2.4 Resolução pelo Watchdog (Timeout)

O Watchdog (Seção 6.3) detecta ordens travadas e dispara cancelamento:

```
Watchdog detecta TX em SUBMITTED há mais que timeoutMs:
    1. Envia cancelamento para Exchange
    2. Exchange confirma cancelamento
    3. Fluxo de "Resolução por Falha" (9.2.2) é ativado
    // Resultado: locks liberados via o mesmo mecanismo de cancelamento
```

**Garantia:** O Watchdog nunca libera locks diretamente — ele cancela a transação, e o fluxo de cancelamento (9.2.2) cuida dos locks. Isso mantém um **único ponto de resolução** para cada tipo de terminal state.

### 9.3 Implementação do Lock no Modelo de Dados

O lock é implementado como campos na entidade `Position` (lote de compra):

| Campo                   | Tipo                    | Descrição                                                                              |
|-------------------------|-------------------------|----------------------------------------------------------------------------------------|
| `lockedByTransactionId` | `UUID` (nullable)       | FK para a Transaction de venda que reivindica este lote. `NULL` = disponível           |
| `lockedQuantity`        | `BigDecimal` (nullable) | Quantidade do lote reservada para a venda. Pode ser menor que `quantity` total do lote |
| `lockedAt`              | `Instant` (nullable)    | Timestamp do lock — usado apenas para observabilidade/debug                            |

**Regras de integridade:**

```
INVARIANTE: Se lockedByTransactionId IS NOT NULL:
    - lockedQuantity > 0
    - lockedQuantity <= quantity (disponível no lote)
    - A Transaction referenciada está em (PENDING, SUBMITTED, PARTIAL)

INVARIANTE: Se lockedByTransactionId IS NULL:
    - lockedQuantity IS NULL ou 0
    - lockedAt IS NULL
```

**Índice recomendado:**

```sql
CREATE INDEX idx_positions_locked_tx ON positions(locked_by_transaction_id)
    WHERE locked_by_transaction_id IS NOT NULL;
-- Partial index: apenas lotes com lock ativo são indexados
```

### 9.4 Processo de Aquisição do Lock

#### 9.4.1 Seleção de Lotes (Roteamento)

O Runner seleciona quais lotes serão travados com base no sinal recebido:

| Cenário                   | Seleção                                                                    |
|---------------------------|----------------------------------------------------------------------------|
| `targetLotId` preenchido  | Lote específico identificado pelo UUID                                     |
| `targetLotId` nulo + FIFO | AccountingPolicy seleciona o lote mais antigo (`ORDER BY createdAt ASC`)   |
| `targetLotId` nulo + LIFO | AccountingPolicy seleciona o lote mais recente (`ORDER BY createdAt DESC`) |

#### 9.4.2 Protocolo de Lock Atômico

```
@Transactional(isolation = READ_COMMITTED)
fun acquireLock(runnerId, sellTransaction, targetLotId):

    Step 1: Selecionar lote candidato com lock pessimista
        lot = SELECT * FROM positions
              WHERE runner_id = :runnerId
                AND status = 'OPEN'
                AND locked_by_transaction_id IS NULL
                AND (id = :targetLotId OR :targetLotId IS NULL)
              ORDER BY created_at ASC  -- FIFO
              LIMIT 1
              FOR UPDATE SKIP LOCKED
              -- SKIP LOCKED: se outro sinal já travou a linha, pular

    Step 2: Validar disponibilidade
        if (lot == null):
            throw NoAvailableLotException
            // Sinal é rejeitado — não há lotes para vender

    Step 3: Validar quantidade
        availableQty = lot.quantity - lot.totalMatchedQuantity
        requestedQty = sellTransaction.quantity
        if (requestedQty > availableQty):
            // Ajustar para quantidade disponível ou rejeitar
            requestedQty = min(requestedQty, availableQty)

    Step 4: Aplicar lock
        lot.lockedByTransactionId = sellTransaction.id
        lot.lockedQuantity = requestedQty
        lot.lockedAt = Instant.now()
        positionRepository.save(lot)

    Step 5: Retornar lote travado
        return LockedLot(lot.id, requestedQty)
```

**Decisões técnicas:**

| Decisão                         | Justificativa                                                                                                                                           |
|---------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `FOR UPDATE SKIP LOCKED`        | Evita bloqueio entre sinais concorrentes — se um lote já está sendo travado por outra transação DB, o próximo sinal seleciona o próximo lote disponível |
| Isolation `READ_COMMITTED`      | Suficiente com `FOR UPDATE`; não requer `SERIALIZABLE`                                                                                                  |
| Lock dentro de `@Transactional` | Garante que a seleção + marcação são atômicas                                                                                                           |

### 9.5 Prevenção de Deadlocks

#### 9.5.1 Fila Sequencial por Runner

Cada `StrategyRunner` processa sinais de forma **sequencial** (modelo de concorrência definido na Seção 12). Isso elimina a possibilidade de dois sinais do **mesmo Runner** disputarem lotes simultaneamente.

**Cenário eliminado:** Runner A recebe SELL para Lote 1, e simultaneamente Runner A recebe SELL para Lote 2 que também tenta Lote 1 → impossível com processamento sequencial.

#### 9.5.2 Fail-Fast em Lotes Presos

Se um sinal solicita um `targetLotId` específico que já possui lock ativo de outra transação:

```
fun validateLotAvailability(targetLotId):
    lot = positionRepository.findById(targetLotId)

    if (lot.lockedByTransactionId != null):
        // Lote já está reservado por outra transação
        throw LotAlreadyLockedException(
            lotId = targetLotId,
            lockedBy = lot.lockedByTransactionId,
            lockedSince = lot.lockedAt
        )
        // Sinal rejeitado imediatamente — sem espera
```

**Regra:** Nunca esperar pela liberação de um lote. A rejeição imediata (fail-fast) previne esperas circulares e simplifica o modelo de concorrência.

#### 9.5.3 Cross-Runner: Isolamento Natural

Runners diferentes operam sobre **lotes diferentes** (cada Position pertence a um único Runner via `runner_id`). Não há possibilidade de cross-runner deadlock no nível de lotes.

| Cenário                              | Risco de Deadlock  | Motivo                                                                   |
|--------------------------------------|--------------------|--------------------------------------------------------------------------|
| Dois sinais do mesmo Runner          | Nenhum             | Processamento sequencial (Seção 12)                                      |
| Sinais de Runners diferentes         | Nenhum             | Lotes isolados por `runner_id`                                           |
| Runner + Portfolio (Capital Request) | Nenhum             | Lock de lote (Runner) e lock de saldo (Portfolio) são recursos distintos |

### 9.6 Saneamento de Locks Órfãos (Boot Sequence)

Durante o Boot Sequence (Seção 6.6), o Runner executa o saneamento de locks que sobreviveram a um crash:

```
Boot Sequence — Passo 2: Saneamento de Locks Órfãos

    Step 1: Identificar transações zumbi (crash entre persistência e envio)
        zombies = SELECT * FROM transactions
                  WHERE runner_id = :runnerId
                    AND status = 'PENDING'
                    AND exchange_order_id IS NULL
                    -- Crash ocorreu ANTES do envio à Exchange

    Step 2: Para cada zumbi — rollback completo
        for each zombie in zombies:
            // Liberar locks de lotes
            lockedLots = positionRepository.findByLockedByTransactionId(zombie.id)
            for each lot in lockedLots:
                lot.clearLock()
                lot.status = OPEN

            // Liberar margem reservada
            capitalManager.release(CapitalReleaseCommand(
                transactionId = zombie.id,
                amount = zombie.reservedAmount,
                reason = FAILED_ON_CRASH
            ))

            // Marcar transação como falha de crash
            zombie.status = FAILED_ON_CRASH
            transactionRepository.save(zombie)

    Step 3: Identificar locks vinculados a transações em limbo
        // Transações que FORAM enviadas mas não sabemos o resultado
        limboTxs = SELECT * FROM transactions
                   WHERE runner_id = :runnerId
                     AND status IN ('SUBMITTED', 'PARTIAL')

        // Locks destes lotes SÃO MANTIDOS até reconciliação com Exchange
        // (o lock é válido — a ordem pode estar ativa na Exchange)

    Step 4: Após reconciliação com Exchange (Seção 6.6)
        // Se a Exchange confirmar CANCELED/EXPIRED → fluxo 9.2.2
        // Se a Exchange confirmar FILLED → fluxo 9.2.1
        // Locks são resolvidos pelo fluxo normal
```

**Regras do saneamento:**

| Estado da Transação  | `exchange_order_id`  | Ação sobre Lock                                       |
|----------------------|----------------------|-------------------------------------------------------|
| PENDING              | NULL                 | **UNLOCK** imediato (zumbi — nunca chegou à Exchange) |
| PENDING              | Preenchido           | Reconciliar com Exchange primeiro                     |
| SUBMITTED            | Preenchido           | **MANTER** lock até reconciliação                     |
| PARTIAL              | Preenchido           | **MANTER** lock até reconciliação                     |

### 9.7 Observabilidade

Métricas para monitoramento de locks:

| Métrica                             | Tipo    | Descrição                                                   |
|-------------------------------------|---------|-------------------------------------------------------------|
| `locks.active.count`                | Gauge   | Número de lotes com lock ativo (por Runner)                 |
| `locks.active.duration.max`         | Gauge   | Duração do lock mais antigo ativo (ms)                      |
| `locks.resolved.total`              | Counter | Total de locks resolvidos (por tipo: success/failure/crash) |
| `locks.orphan.cleaned.total`        | Counter | Locks órfãos limpos no boot                                 |
| `locks.rejected.lot_already_locked` | Counter | Sinais rejeitados por lote já travado                       |

**Alerta recomendado:**

| Condição                                           | Severidade  | Ação                                                            |
|----------------------------------------------------|-------------|-----------------------------------------------------------------|
| `locks.active.duration.max > 2 × watchdog.timeout` | CRITICAL    | Lock sobreviveu ao Watchdog — investigar transação travada      |
| `locks.orphan.cleaned.total > 0` (no boot)         | WARNING     | Crash anterior deixou locks — verificar integridade             |
| `locks.rejected.lot_already_locked` > 5/min        | INFO        | Sinais concorrentes frequentes — revisar frequência da strategy |

### 9.8 Configuração

| Parâmetro                               | Default  | Descrição                                                          |
|-----------------------------------------|----------|--------------------------------------------------------------------|
| `lock.acquisition.timeout-ms`           | 5000     | Timeout para adquirir o pessimistic lock no DB (`FOR UPDATE` wait) |
| `lock.orphan.cleanup-on-boot`           | `true`   | Habilitar saneamento automático de locks órfãos no Boot Sequence   |
| `lock.monitoring.max-duration-alert-ms` | 120000   | Threshold para alerta de lock com duração excessiva                |

### 9.9 Implicações Arquiteturais

| Decisão                                             | Justificativa                                                                      | Trade-off                                                                      |
|-----------------------------------------------------|------------------------------------------------------------------------------------|--------------------------------------------------------------------------------|
| Lock como campo na Position (não entidade separada) | Elimina JOIN adicional; lock é estado transitório do lote, não entidade de domínio | Lote carrega campos nullable (`lockedByTransactionId`, `lockedQuantity`)       |
| `FOR UPDATE SKIP LOCKED`                            | Evita bloqueio entre sinais concorrentes sem recorrer a filas externas             | Depende de suporte do PostgreSQL (disponível desde v9.5)                       |
| Unlock atômico com mudança de status                | Garante que não existem lotes fantasmas (travados sem transação ativa)             | Transação DB levemente maior (inclui updates de lotes)                         |
| Saneamento no Boot (não periódico)                  | Locks órfãos só existem após crash — não há necessidade de worker periódico        | Se o boot não ocorrer (hot-deploy), locks órfãos persistem até próximo restart |
| Watchdog delega ao fluxo de cancelamento            | Único ponto de resolução para cada estado terminal — sem duplicação de lógica      | Latência adicional: Watchdog → Cancel → Exchange → Callback → Unlock           |
| Processamento sequencial por Runner                 | Elimina deadlocks intra-Runner sem locks complexos                                 | Throughput limitado a 1 sinal por vez por Runner                               |
| Fail-fast em lotes presos                           | Rejeição imediata previne esperas e simplifica recovery                            | Strategy pode perder oportunidades se insistir em lote específico              |

---

## 10. Reconciliação e Boot Sequence

Esta seção detalha **como implementar** a reconciliação holística do sistema ao reiniciar, indo além do fluxo individual por Transaction (Seção 6.6) para cobrir: validação de integridade financeira (Sanity Check), detecção de ordens externas (Zumbis da Exchange), corte temporal, saneamento de reservas órfãs (PENDING) via query-before-expire e o protocolo de intervenção manual via DLQ.

> **Referência:** Blueprint Seção 6.D (Boot Sequence), Seção 10.3 (Filosofia de Reconciliação e Fonte da Verdade).
> **Notas de Implementação absorvidas:** #8 (Sanity Check no Boot), #9 (Identificação de Zumbis da Exchange), #10 (Timestamp de Corte).
> **Dependência:** Seção 6.6 (reconciliação individual por Transaction), Seção 9.6 (saneamento de locks órfãos).

### 10.1 Filosofia de Reconciliação

O sistema adota **Sincronismo Autoritário** — duas fontes de verdade complementares:

| Autoridade   | Escopo                 | Regra                                                                                                        |
|--------------|------------------------|--------------------------------------------------------------------------------------------------------------|
| **Exchange** | Financeira (execução)  | Se a Exchange confirma execução, o sistema local **aceita e ajusta** — mesmo que o estado local seja PENDING |
| **DB Local** | Intenção (estratégica) | Se o DB tem PENDING mas a Exchange não reconhece o ID, a intenção é descartada para proteger o saldo         |

**Princípio de segurança:** É mais seguro perder um sinal de trade (custo de oportunidade) do que manter margem bloqueada para uma ordem que nunca será preenchida (custo de capital).

**Terminologia:** "Ordens Fantasmas" (existem no DB local, não na Exchange) são tratadas na Seção 6.6.2 Step 1. "Zumbis da Exchange" (existem na Exchange, não no DB local) são tratados na Seção 10.4. Para a tabela completa de resolução de divergências entre estados local e Exchange, consulte a Seção 6.6.3.

### 10.2 Orquestração do Boot Sequence (Visão Global)

O Boot Sequence segue uma hierarquia **bottom-up** (Infra → Portfolio → Runners) e é **descentralizado** — cada Runner reconcilia suas próprias operações.

```
System Restart
       │
Phase 1: INFRAESTRUTURA
       │ ├─ Conexão com Exchange APIs (REST + WebSocket)
       │ ├─ Verificação de conectividade e autenticação
       │ └─ Se falhar: sistema não inicia (fail-fast)
       │
Phase 2: PORTFOLIO — Sincronização de Caixa
       │ ├─ Consultar saldo real na Exchange (Available + Total)
       │ ├─ Carregar GlobalBalance do DB local
       │ ├─ Executar Sanity Check (Seção 10.3)
       │ ├─ Identificação de Zumbis da Exchange (Seção 10.4)
       │ ├─ Iniciar ReservationTTL Worker (Seção 10.6)
       │ └─ Sinalizar: Portfolio pronto para receber requests
       │
Phase 3: RUNNERS — Reconciliação Individual (em paralelo)
       │ Para cada Runner com status != ARCHIVED:
       │   ├─ Status → INITIALIZING, isReconciling = true
       │   ├─ Saneamento de Locks Órfãos (Seção 9.6)
       │   ├─ Reconciliação de Transactions (Seção 6.6)
       │   ├─ Validação de Integridade Local (Seção 10.5)
       │   └─ isReconciling = false, status → ACTIVE
       │
Phase 4: SISTEMA OPERACIONAL
       └─ Todos os Runners reconciliados → sistema aceita sinais
```

**Regra de bloqueio:** Um Runner permanece em `isReconciling = true` até que **todos** os seus passos de reconciliação completem com sucesso. Se qualquer passo falhar irrecuperavelmente (ex: Exchange inacessível após todos os retries), o Runner entra em `HALTED` e requer intervenção manual.

### 10.3 Sanity Check de Saldo (Portfolio)

> **Nota #8 absorvida:** "O Runner deve comparar o GlobalBalance reportado pela Exchange com o Available + Reserved do Portfolio local."

O Sanity Check é executado pelo **Portfolio** (não pelo Runner) durante a Phase 2 do boot, **antes** de qualquer Runner iniciar a reconciliação.

#### 10.3.1 Algoritmo

```
SanityCheck (Portfolio, durante Phase 2):

    Step 1: Obter saldo real da Exchange
        exchangeBalance = ExchangeAdapter.getAccountBalance()
        // Retorna map: { "USDT": 10500.00, "BTC": 0.15, ... }

    Step 2: Calcular saldo esperado local
        localBalance = globalBalance.availableBalance
                     + globalBalance.reservedBalance
        // Nota: reservedBalance inclui margem de ordens em voo

    Step 3: Comparar (moeda base apenas)
        baseCurrency = globalBalance.baseCurrency  // ex: USDT
        exchangeBase = exchangeBalance.get(baseCurrency)
        deviation = abs(exchangeBase - localBalance)

    Step 4: Avaliar desvio
        if (deviation <= SANITY_THRESHOLD):
            // Desvio desprezível (arredondamento)
            log.info("Sanity check PASSED: deviation={}", deviation)
            return PASS

        if (exchangeBase > localBalance):
            // Exchange tem MAIS que o local
            // Possível: execução processada na exchange mas não no local
            log.warn("Exchange surplus: exchange={}, local={}", exchangeBase, localBalance)
            return WARN_SURPLUS
            // Não bloqueia — a reconciliação dos Runners deve resolver

        if (exchangeBase < localBalance):
            // Exchange tem MENOS que o local — CRÍTICO
            // Possível: saque externo, execução manual, ou erro de contabilidade
            log.error("Exchange deficit: exchange={}, local={}", exchangeBase, localBalance)
            return FAIL_DEFICIT
```

#### 10.3.2 Ações por Resultado

| Resultado      | Severidade  | Ação                                                                                                                                       |
|----------------|-------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| `PASS`         | OK          | Boot continua normalmente                                                                                                                  |
| `WARN_SURPLUS` | WARNING     | Boot continua; log para auditoria. Runners devem reconciliar a diferença                                                                   |
| `FAIL_DEFICIT` | CRITICAL    | **Circuit Breaker ativado** (Seção 11.1). Portfolio entra em `HALTED`. Nenhum Runner pode operar até intervenção manual |

**Threshold configurável:**

```
SANITY_THRESHOLD = 1e-8  // default: margem para arredondamento
```

Se a exchange tem menos saldo do que o local (sem ordens em voo que justifiquem), o cenário é irrecuperável automaticamente — indica saque externo, execução manual via app da corretora, ou bug de contabilidade.

#### 10.3.3 Ativos Não-Base (Crypto Holdings)

O Sanity Check da moeda base é obrigatório. Para ativos crypto (posições abertas), a verificação é **complementar**:

```
Para cada Runner com Position OPEN:
    expectedQty = Σ(position.quantity) para o ativo
    exchangeQty = exchangeBalance.get(position.symbol.baseAsset)
    if (abs(exchangeQty - expectedQty) > SANITY_THRESHOLD):
        log.warn("Asset mismatch: {} expected={} exchange={}",
                 asset, expectedQty, exchangeQty)
        // Registrar para auditoria — NÃO bloquear boot
        // Runners reconciliarão durante Phase 3
```

**Decisão:** Divergências em ativos crypto geram WARNING (não CRITICAL), pois a reconciliação dos Runners (Phase 3) tipicamente resolve a diferença ao processar execuções perdidas.

### 10.4 Identificação de Zumbis da Exchange

> **Nota #9 absorvida:** "O Portfolio deve listar todas as ordens abertas na Exchange durante o boot. Qualquer ordem cujo clientOrderId não siga o padrão ou não conste no banco deve ser enviada para DLQ."

"Zumbis da Exchange" são ordens que existem **na Exchange** mas **não no sistema local** — o inverso das "Ordens Fantasmas" (que existem no local mas não na Exchange).

#### 10.4.1 Fontes de Zumbis

| Origem          | Cenário                                                                          | Risco                                 |
|-----------------|----------------------------------------------------------------------------------|---------------------------------------|
| Ordem manual    | Operador colocou ordem via app/web da corretora                                  | Consome margem "invisível" ao sistema |
| Outra aplicação | Outro bot ou sistema usando a mesma conta                                        | Conflito de capital                   |
| Versão antiga   | Ordem de uma versão anterior do sistema com formato de `clientOrderId` diferente | Não roteável pelo parser              |

#### 10.4.2 Protocolo de Detecção

```
ZombieDetection (Portfolio, durante Phase 2 — após Sanity Check):

    Step 1: Listar todas as ordens abertas na Exchange
        openOrders = ExchangeAdapter.getAllOpenOrders()
        // Retorna todas as ordens OPEN/NEW/PARTIALLY_FILLED

    Step 2: Para cada ordem aberta
        for each order in openOrders:

            Step 2a: Validar formato do clientOrderId
                parsed = ClientOrderId.tryParse(order.clientOrderId)
                if (parsed == null):
                    // ID não segue o padrão do sistema
                    sendToDLQ(order, reason = "UNKNOWN_FORMAT")
                    continue

            Step 2b: Validar versão do ID
                if (parsed.version != CURRENT_VERSION):
                    // Versão incompatível — possível ordem de sistema antigo
                    sendToDLQ(order, reason = "VERSION_MISMATCH")
                    continue

            Step 2c: Verificar existência no DB local
                localTx = transactionRepository
                    .findByClientOrderId(order.clientOrderId)
                if (localTx == null):
                    // Ordem na Exchange sem correspondência local
                    sendToDLQ(order, reason = "NO_LOCAL_MATCH")
                    continue

            Step 2d: Verificar Runner proprietário
                runner = runnerRepository.findById(parsed.runnerShortCode)
                if (runner == null || runner.status == ARCHIVED):
                    // Runner não existe ou foi arquivado
                    sendToDLQ(order, reason = "RUNNER_NOT_FOUND")
                    continue

            // Ordem válida — será reconciliada pelo Runner na Phase 3
```

#### 10.4.3 Tratamento na DLQ

Ordens enviadas à DLQ são registradas como `DeadLetterEntry` (Seção 3.2.5):

```
DeadLetterEntry:
    sourceType = EXCHANGE_ZOMBIE
    payload = order.toJson()  // Snapshot completo da ordem
    reason = <motivo da rejeição>
    createdAt = Instant.now()
    isResolved = false
```

**Regra:** O sistema **nunca cancela automaticamente** zumbis da Exchange. Ordens desconhecidas podem ter sido colocadas intencionalmente pelo operador. A decisão de cancelar é exclusivamente humana.

### 10.5 Timestamp de Corte (Cutoff)

> **Nota #10 absorvida:** "O sistema deve ignorar ordens da Exchange criadas antes do início do histórico local do Runner para evitar processar trades antigos de outras sessões."

#### 10.5.1 Problema

Sem timestamp de corte, o Boot Sequence poderia processar ordens de:
- Sessões anteriores do sistema (antes de uma reinstalação/migração)
- Outros sistemas que usaram a mesma conta
- Períodos em que o Runner estava `ARCHIVED`

#### 10.5.2 Implementação

Cada Runner mantém um **timestamp de referência** que delimita o horizonte de reconciliação:

```
cutoffTimestamp = runner.createdAt
// Alternativa mais conservadora:
cutoffTimestamp = max(runner.createdAt, runner.lastReconciliationAt)
```

**Aplicação durante o Boot:**

```
Step 3 da Reconciliação (Seção 6.6.2):
    // Antes de consultar a Exchange por ordens no limbo
    limboTxs = SELECT * FROM transactions
               WHERE runner_id = :runnerId
                 AND status IN ('PENDING', 'SUBMITTED', 'PARTIAL')
                 AND created_at >= :cutoffTimestamp
                 -- Ignora transações anteriores ao horizonte
```

**Aplicação na detecção de zumbis (Seção 10.4):**

```
Step 2c aprimorado:
    localTx = transactionRepository.findByClientOrderId(order.clientOrderId)
    if (localTx != null && localTx.createdAt < cutoffTimestamp):
        // Transação existe mas é de uma sessão anterior
        sendToDLQ(order, reason = "BEFORE_CUTOFF")
        continue
```

#### 10.5.3 Persistência do Cutoff

| Campo                  | Local          | Descrição                                                               |
|------------------------|----------------|-------------------------------------------------------------------------|
| `createdAt`            | StrategyRunner | Data de criação do Runner — cutoff inicial                              |
| `lastReconciliationAt` | StrategyRunner | Timestamp da última reconciliação bem-sucedida — atualizado a cada boot |

Após cada Boot Sequence concluído com sucesso:

```
runner.lastReconciliationAt = Instant.now()
runnerRepository.save(runner)
```

### 10.6 TTL de Reservas (Defesa em Profundidade)

O saneamento de locks (Seção 9.6) e a reconciliação de transações (Seção 6.6) dependem do **reboot do Runner**. Para cobrir o cenário em que um Runner permanece inativo por tempo prolongado, o Portfolio implementa uma camada de proteção independente.

#### 10.6.1 Mecanismo

```
ReservationTTLWorker (Portfolio, periódico):

    Step 1: Buscar reservas expiradas
        staleReservations = SELECT t.id, t.runner_id, t.reserved_amount
                            FROM transactions t
                            WHERE t.status = 'PENDING'
                              AND t.exchange_order_id IS NULL
                              AND t.created_at < (now() - :reservationTTL)
                            -- Apenas PENDING sem envio à Exchange

    Step 2: Para cada reserva expirada
        for each tx in staleReservations:
            // Liberar margem
            globalBalance.availableBalance += tx.reservedAmount
            globalBalance.reservedBalance -= tx.reservedAmount

            // Marcar transação
            tx.status = EXPIRED
            tx.expiredReason = "RESERVATION_TTL_EXCEEDED"

            // Liberar locks de lotes (se houver)
            lockedLots = positionRepository
                .findByLockedByTransactionId(tx.id)
            for each lot in lockedLots:
                lot.clearLock()

            log.warn("Reservation TTL expired: txId={}, runnerId={}, amount={}",
                     tx.id, tx.runnerId, tx.reservedAmount)
```

#### 10.6.2 Escopo do TTL

| Estado da Transação  | `exchange_order_id`  | Coberto pelo TTL?   | Motivo                                                     |
|----------------------|----------------------|---------------------|------------------------------------------------------------|
| PENDING              | NULL                 | **SIM**             | Crash entre persist e dispatch — nenhuma ordem foi enviada |
| PENDING              | Preenchido           | **NÃO**             | Ordem pode ter sido aceita pela Exchange                   |
| SUBMITTED            | Preenchido           | **NÃO**             | Ordem está ativa — reconciliação normal                    |
| PARTIAL              | Preenchido           | **NÃO**             | Execução em andamento                                      |

**Regra crítica:** O TTL aplica-se **exclusivamente** a transações PENDING sem `exchangeOrderId`. Qualquer outra combinação é gerida pelo Boot Sequence ou Watchdog.

#### 10.6.3 Independência

| Aspecto                   | Detalhe                                                    |
|---------------------------|------------------------------------------------------------|
| **Responsável**           | Portfolio (não Runner)                                     |
| **Dependência do Runner** | Nenhuma — funciona mesmo se o Runner nunca reiniciar       |
| **Frequência**            | Worker periódico (configurável, default: a cada 60s)       |
| **Atomicidade**           | Cada reserva expirada é processada em transação DB isolada |

### 10.7 Protocolo de DLQ e Intervenção Manual

Quando a reconciliação automática encontra situações irreconciliáveis, o sistema desvia para a DLQ.

#### 10.7.1 Cenários que Geram DLQ

| Cenário                                            | Origem                  | Motivo                                                  |
|----------------------------------------------------|-------------------------|---------------------------------------------------------|
| Ordem na Exchange sem `clientOrderId` reconhecível | Zombie Detection (10.4) | Formato desconhecido ou versão incompatível             |
| Ordem na Exchange sem correspondência local        | Zombie Detection (10.4) | Possível ordem manual ou de outro sistema               |
| Quantidade executada divergente do local           | Reconciliação (6.6)     | Exchange executou quantidade diferente da registrada    |
| Símbolo divergente                                 | Reconciliação (6.6)     | Exchange retornou símbolo que não corresponde ao Runner |
| Exchange inacessível após todos os retries         | Boot Phase 3            | Impossível reconciliar — estado indeterminado           |

#### 10.7.2 Impacto no Runner

Quando uma transação é enviada à DLQ durante a reconciliação:

```
if (dlqEntryCreated):
    runner.isReconciling = true  // PERMANECE bloqueado
    runner.status = HALTED
    runner.haltReason = "DLQ_PENDING_REVIEW"
    // Runner NÃO aceita novos sinais até intervenção manual
```

**Regra:** O Runner **não** conclui o boot se houver DLQ pendente. Isso previne operação com estado potencialmente inconsistente.

#### 10.7.3 Fluxo de Resolução Manual

```
Operador identifica DLQ entry (via dashboard/API):
       │
       ├─ Se ordem é legítima (colocada manualmente):
       │     → Operador cancela na Exchange (se desejado)
       │     → Marca DLQ entry como resolved
       │     → Runner pode retomar (isReconciling → false)
       │
       ├─ Se ordem é de outro sistema:
       │     → Operador cancela na Exchange
       │     → Marca DLQ entry como resolved
       │     → Investiga como evitar conflito futuro
       │
       └─ Se divergência de quantidade/símbolo:
             → Operador audita manualmente
             → Ajusta GlobalBalance se necessário
             → Marca DLQ entry como resolved
             → Runner pode retomar
```

### 10.8 Reconciliação de Ativos (Cross-Check)

Após todos os Runners completarem a Phase 3, o Portfolio executa um cross-check final entre as posições locais e os ativos na Exchange.

```
AssetReconciliation (Portfolio, após Phase 3):

    Step 1: Calcular posições esperadas
        expectedAssets = {}
        for each runner where status == ACTIVE:
            for each position where status == OPEN:
                asset = position.symbol.baseAsset
                expectedAssets[asset] += position.quantity

    Step 2: Consultar Exchange
        exchangeAssets = ExchangeAdapter.getAccountBalance()
        // Filtrar apenas ativos crypto (excluir moeda base)

    Step 3: Comparar
        for each (asset, expectedQty) in expectedAssets:
            exchangeQty = exchangeAssets.getOrDefault(asset, 0)
            deviation = abs(exchangeQty - expectedQty)
            if (deviation > SANITY_THRESHOLD):
                log.warn("Post-reconciliation mismatch: {} expected={} exchange={}",
                         asset, expectedQty, exchangeQty)
                // Registrar métrica — NÃO bloquear
                // Divergência pode ser dust ou posição de outro sistema
```

**Decisão:** O cross-check é informativo (WARNING), não bloqueante. Divergências pequenas são esperadas (dust, arredondamento). Divergências grandes são investigadas pelo operador.

### 10.9 Configuração

| Parâmetro                                     | Default      | Descrição                                                               |
|-----------------------------------------------|--------------|-------------------------------------------------------------------------|
| `portfolio.sanity-check.threshold`            | `1e-8`       | Margem de desvio aceitável no Sanity Check                              |
| `portfolio.sanity-check.fail-on-deficit`      | `true`       | Ativar Circuit Breaker se Exchange tem menos saldo que o local          |
| `portfolio.zombie-detection.enabled`          | `true`       | Habilitar detecção de zumbis da Exchange no boot                        |
| `portfolio.asset-reconciliation.enabled`      | `true`       | Habilitar cross-check de ativos pós-reconciliação                       |
| `runner.reconciliation.cutoff-strategy`       | `CREATED_AT` | Estratégia de timestamp de corte: `CREATED_AT` ou `LAST_RECONCILIATION` |

### 10.10 Implicações Arquiteturais

| Decisão                                                   | Justificativa                                                                      | Trade-off                                                                                                      |
|-----------------------------------------------------------|------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------|
| Sanity Check no Portfolio (não no Runner)                 | Portfolio é o dono do GlobalBalance — ele deve validar antes de permitir operações | Runner depende do Portfolio estar pronto antes de iniciar Phase 3                                              |
| Exchange = autoridade financeira                          | Execuções na exchange são fatos consumados — o sistema local se adapta             | Se a exchange tiver bug, o sistema importa o erro                                                              |
| DB local = autoridade de intenção                         | Ordens PENDING sem correspondência na exchange são descartadas                     | Perde oportunidade de trade (custo aceitável vs custo de capital bloqueado)                                    |
| Zumbis da Exchange → DLQ (nunca cancelar automaticamente) | Ordens desconhecidas podem ser intencionais — decisão humana obrigatória           | Requer monitoramento manual da DLQ                                                                             |
| Timestamp de corte por Runner                             | Evita processar ordens de sessões anteriores ou outros sistemas                    | Ordens legítimas anteriores ao cutoff são ignoradas (mitigado pelo cutoff conservador)                         |
| Reserva órfã (PENDING) resolvida por query-before-expire  | Boot (phase3) e watchdog de runtime consultam a exchange antes de expirar          | Exige capacidade de order query; sem ela, o PENDING segue o mesmo halt do limbo (T31)                          |
| Cross-check informativo (não bloqueante)                  | Divergências pequenas são comuns (dust) — bloquear seria excessivo                 | Divergências grandes passam despercebidas se não houver monitoramento                                          |
| Boot descentralizado (cada Runner se reconcilia)          | Paralelismo natural; um Runner travado não impede os outros                        | Portfolio precisa aguardar todos os Runners para confirmar sistema operacional                                 |
| DLQ bloqueia o Runner (não o Portfolio)                   | Isola o impacto — outros Runners continuam operando                                | Runner afetado fica inoperante até intervenção manual                                                          |

---

## 11. Circuit Breaker e Defesa de Capital

Esta seção detalha **como implementar** o sistema de proteção multicamada que interrompe operações quando anomalias são detectadas. O Circuit Breaker opera em três níveis de severidade (Halt → Cancel All → Panic Sell), com acionamento automático para o nível 1 e escalação exclusivamente manual para os níveis superiores.

> **Referência:** Blueprint Seção 11.1 (Circuit Breaker Global e Defesa de Capital).
> **Notas de Implementação absorvidas:** #11 (Ponto de Injeção de Parada / Gatekeeper), #12 (Persistência do Estado de Alerta), #13 (Kill-Switch Externo).
> **Dependência:** Seção 7.1 (cadeia de validação — Step 1 já referencia Circuit Breaker), Seção 10.3 (Sanity Check ativa Circuit Breaker em FAIL_DEFICIT).

### 11.1 Gatilhos de Ativação (Métricas)

O Circuit Breaker é acionado automaticamente pelo **Portfolio** ao detectar anomalias. Cada gatilho é avaliado independentemente.

#### 11.1.1 Tabela de Gatilhos

| Gatilho                          | Fórmula / Condição                                                        | Escopo     | Nível Acionado                            |
|----------------------------------|---------------------------------------------------------------------------|------------|-------------------------------------------|
| **Max Daily Drawdown**           | `(peakBalance24h - currentBalance) / peakBalance24h > maxDrawdownPercent` | Global     | Nível 1 (Halt)                            |
| **Rejeições Consecutivas**       | Runner acumula `N` estados REJECTED em `T` segundos                       | Por Runner | Nível 1 (Halt) — apenas do Runner afetado |
| **Divergência Crítica de Saldo** | Sanity Check retorna `FAIL_DEFICIT` (Seção 10.3)                          | Global     | Nível 1 (Halt)                            |
| **Anomalia de Latência**         | Watchdog detecta `N` timeouts em `T` segundos                             | Global     | Nível 1 (Halt)                            |

#### 11.1.2 Implementação dos Gatilhos

```
DrawdownMonitor (Portfolio, periódico):

    // Executado a cada check-interval (ex: 10s)
    // T43: realizedBalance NAO entra — ja esta dentro de availableBalance (confirmExecution).
    currentBalance = globalBalance.availableBalance
                   + globalBalance.reservedBalance
                   + calculateUnrealizedPnl()      // preco x quantidade - cost_basis, ultimo preco

    peakBalance24h = metricsStore.getPeakBalance(Duration.ofHours(24))

    drawdownPercent = (peakBalance24h - currentBalance) / peakBalance24h

    if (drawdownPercent > config.maxDrawdownPercent):
        activateCircuitBreaker(
            level = HALT,
            reason = "MAX_DRAWDOWN_EXCEEDED",
            details = "drawdown={drawdownPercent}, peak={peakBalance24h}, current={currentBalance}"
        )
```

```
RejectionMonitor (Portfolio, event-driven):

    // Acionado a cada evento REJECTED recebido
    onRejection(runnerId, rejectionEvent):
        windowStart = Instant.now().minus(config.rejectionWindowMs)
        recentRejections = rejectionCounter.countSince(runnerId, windowStart)

        if (recentRejections >= config.maxConsecutiveRejections):
            // Circuit Breaker POR RUNNER (não global)
            suspendRunner(runnerId, reason = "EXCESSIVE_REJECTIONS")
            // Runner entra em HALTED; outros Runners continuam
```

```
LatencyMonitor (integrado ao Watchdog):

    // Acionado a cada timeout detectado
    onTimeout(transactionId):
        windowStart = Instant.now().minus(config.latencyWindowMs)
        recentTimeouts = timeoutCounter.countSince(windowStart)

        if (recentTimeouts >= config.maxTimeoutsBeforeHalt):
            activateCircuitBreaker(
                level = HALT,
                reason = "LATENCY_ANOMALY",
                details = "timeouts={recentTimeouts} in {latencyWindowMs}ms"
            )
```

### 11.2 Níveis do Safe Mode

> **Estado (T39, 2026-10-16):** Implementado na T45 com diferenças: **HALT ainda permite SELL da estratégia** (bloqueia só a reserva de BUY); CANCEL_ALL cancela ordens LIMIT em voo (inclui PENDING); a estratégia deixa de ser consultada a partir de CANCEL_ALL.

O Safe Mode opera em **três níveis escaláveis** onde cada nível inclui todas as ações do anterior.

#### 11.2.1 Definição dos Níveis

| Nível              | Enum         | Ação                                                                       | Efeito nas Ordens em Voo                 | Proteção contra Acionamento Acidental                      |
|--------------------|--------------|----------------------------------------------------------------------------|------------------------------------------|------------------------------------------------------------|
| **0 — Normal**     | `NORMAL`     | Sistema operacional                                                        | N/A                                      | N/A                                                        |
| **1 — Halt**       | `HALT`       | Rejeita novos `TradeSignal` e `Capital Requests`                           | SUBMITTED/PARTIAL continuam ciclo normal | Sem confirmação adicional (baixo risco)                    |
| **2 — Cancel All** | `CANCEL_ALL` | Halt + envia cancelamento para todas as ordens SUBMITTED                   | Cancelamento de todas as ordens em voo   | Requer confirmação explícita (`--force` ou double-confirm) |
| **3 — Panic Sell** | `PANIC_SELL` | Cancel All + gera Market Orders de fechamento para todas as Positions OPEN | Liquidação total de posições             | Requer confirmação + motivo registrado em auditoria        |

**Regra de escalação:**
- **Acionamento automático:** Gatilhos da Seção 11.1.1 acionam apenas **Nível 1 (Halt)**
- **Escalação manual:** Níveis 2 e 3 são **exclusivamente** acionados por comando administrativo
- **Retomada:** O operador reduz os níveis inversamente (3→2→1→Normal), com validação em cada etapa

#### 11.2.2 Ponto de Injeção (Gatekeeper)

> **Nota #11 absorvida:** "A verificação do estado do Circuit Breaker deve ser a primeira instrução dentro do método requestCapital no Portfolio."

```java
// Portfolio — método reserve() (Seção 5.2)
public ReservationResult reserve(CapitalReservationCommand cmd) {
    // PRIMEIRO: Circuit Breaker check — antes de qualquer validação
    if (this.safeModeStatus != SafeModeStatus.NORMAL) {
        throw new RiskViolationException(
            "Portfolio in Safe Mode: " + this.safeModeStatus,
            this.safeModeStatus
        );
    }

    // ... restante da cadeia de validação (Seção 7.1 Steps 2-7)
}
```

**Posição na cadeia:** O Circuit Breaker check é o **Step 1** da cadeia de validação (Seção 7.1), executado antes do check de limite por Runner, saldo disponível, ou qualquer outro cálculo. Isso garante que nenhuma operação passe quando o sistema está em Safe Mode.

#### 11.2.3 Persistência do Estado

> **Nota #12 absorvida:** "O estado de Halted do Portfolio deve ser persistido no banco de dados para que um restart não esqueça que o circuit breaker foi atingido."

O campo `safeModeStatus` é persistido na tabela `portfolios` (Seção 3.2.1, 3.6):

```
portfolios.safe_mode_status VARCHAR(20) NOT NULL DEFAULT 'NORMAL'
// Valores: NORMAL, HALT, CANCEL_ALL, PANIC_SELL
```

**Comportamento no Boot:**

```
Portfolio Boot (Phase 2, Seção 10.2):
    safeModeStatus = portfolioRepository.findById(id).safeModeStatus

    if (safeModeStatus != NORMAL):
        log.warn("Portfolio booted in Safe Mode: {}", safeModeStatus)
        // NÃO resetar automaticamente
        // Operador deve executar reset manual explícito
        // Runners receberão RiskViolationException em qualquer Capital Request
```

**Regra:** O Safe Mode **nunca** é resetado automaticamente no boot. Se o sistema crashou durante um Circuit Breaker, o motivo que causou o break provavelmente ainda existe. O operador deve investigar e resetar manualmente.

### 11.3 Execução dos Níveis

> **Estado (T39, 2026-10-16):** Implementado de outra forma (T45): sem tarefa assíncrona própria; o `SafeModeHandler` reaplica o nível a cada tick dos runners operacionais e a liquidação passa pelo pipeline normal de SELL com `OrderType.MARKET`. Runners halted pelo kill switch não são conduzidos.

#### 11.3.1 Nível 1 — Halt

```
activateHalt():
    portfolio.safeModeStatus = HALT
    portfolioRepository.save(portfolio)
    log.error("CIRCUIT BREAKER ACTIVATED: HALT, reason={}", reason)

    // Efeito: reject automático via Gatekeeper (11.2.2)
    // Nada mais a fazer — ordens em voo continuam normalmente
    // Watchdog continua monitorando timeouts
```

#### 11.3.2 Nível 2 — Cancel All

```
activateCancelAll(confirmationToken):
    // Validar confirmação explícita
    if (!isValidConfirmation(confirmationToken)):
        throw new UnauthorizedEscalationException("Cancel All requires explicit confirmation")

    portfolio.safeModeStatus = CANCEL_ALL
    portfolioRepository.save(portfolio)

    // Disparar cancelamento assíncrono
    cancelAllTask = async:
        runners = runnerRepository.findByPortfolioIdAndStatusIn(
            portfolioId, [ACTIVE, HALTED, INITIALIZING]
        )
        totalOrders = 0
        canceledOrders = 0

        for each runner in runners:
            submittedTxs = transactionRepository.findByRunnerIdAndStatusIn(
                runner.id, [SUBMITTED, PARTIAL]
            )
            totalOrders += submittedTxs.size()

            for each tx in submittedTxs:
                try:
                    exchangeAdapter.cancelOrder(tx.exchangeOrderId, tx.symbol)
                    canceledOrders++
                    publishProgress("Cancel All: {canceledOrders}/{totalOrders}")
                catch (OrderNotFoundOnExchange):
                    // Já foi executada ou cancelada — ignorar
                    canceledOrders++
                catch (Exception e):
                    log.error("Failed to cancel order: txId={}", tx.id, e)
                    // Continuar com as próximas — best effort

        publishProgress("Cancel All complete: {canceledOrders}/{totalOrders}")
```

**Execução assíncrona:** O comando retorna imediatamente ao operador. O progresso é acompanhado via status endpoint (ex: "3/5 ordens canceladas").

#### 11.3.3 Nível 3 — Panic Sell

```
activatePanicSell(confirmationToken, auditReason):
    // Validar confirmação e motivo obrigatório
    if (!isValidConfirmation(confirmationToken)):
        throw new UnauthorizedEscalationException()
    if (auditReason == null || auditReason.isBlank()):
        throw new AuditRequiredException("Panic Sell requires a documented reason")

    // Registrar motivo em auditoria
    auditLog.record("PANIC_SELL", operatorId, auditReason, Instant.now())

    portfolio.safeModeStatus = PANIC_SELL
    portfolioRepository.save(portfolio)

    // Step 1: Cancel All (se não executado antes)
    if (previousStatus != CANCEL_ALL):
        executeCancelAll()

    // Step 2: Liquidação de posições — assíncrono
    panicSellTask = async:
        runners = runnerRepository.findByPortfolioIdAndStatusIn(
            portfolioId, [ACTIVE, HALTED, INITIALIZING]
        )
        totalPositions = 0
        closedPositions = 0

        for each runner in runners:
            openPositions = positionRepository.findByRunnerIdAndStatus(
                runner.id, OPEN
            )
            totalPositions += openPositions.size()

            for each position in openPositions:
                // Gerar ordem MARKET SELL para fechar
                closeOrder = Transaction.createMarketClose(
                    runnerId = runner.id,
                    symbol = position.symbol,
                    quantity = position.quantity,
                    type = SELL,
                    reason = "PANIC_SELL"
                )
                transactionRepository.save(closeOrder)
                exchangeAdapter.submitOrder(closeOrder.toExchangeOrder())
                closedPositions++
                publishProgress("Panic Sell: {closedPositions}/{totalPositions}")

        publishProgress("Panic Sell complete: {closedPositions}/{totalPositions}")
```

**Nota sobre Panic Sell:** As ordens de liquidação são Market Orders (execução imediata ao melhor preço). Não passam pela cadeia de validação normal (Seção 7.1) — são ordens de emergência que bypassam o Gatekeeper.

### 11.4 Kill-Switch Externo

> **Estado (T39, 2026-10-16):** Endpoint real: `GET/PUT /api/admin/portfolios/{id}/safe-mode` (por portfolio, PUT → 202). A regra de escalação é a do Blueprint 11.1.C: **subida pode pular níveis** (com `confirm`), **descida é um nível por vez**. Health com Safe Mode: componente `safeMode` do `/actuator/health` (**T51**), status UP com o nível por portfolio.

> **Nota #13 absorvida:** "O sistema deve possuir um endpoint que, se sinalizado, aciona o cancelamento em massa (Panic Sell / Cancel All) em caso de emergência externa."

#### 11.4.1 Endpoint REST

```
POST /api/admin/safe-mode
Content-Type: application/json

{
    "level": "HALT" | "CANCEL_ALL" | "PANIC_SELL",
    "confirmation": "<token>",      // Obrigatório para CANCEL_ALL e PANIC_SELL
    "reason": "<motivo de auditoria>" // Obrigatório para PANIC_SELL
}

Responses:
    200 OK - { "previousLevel": "NORMAL", "currentLevel": "HALT", "taskId": null }
    200 OK - { "previousLevel": "HALT", "currentLevel": "CANCEL_ALL", "taskId": "uuid-task" }
    400 Bad Request - { "error": "Confirmation required for Cancel All" }
    403 Forbidden - { "error": "Escalation not allowed: current=NORMAL, requested=PANIC_SELL" }
```

**Regras de escalação:**
- Pode escalar: NORMAL → HALT → CANCEL_ALL → PANIC_SELL
- Pode desescalar: PANIC_SELL → CANCEL_ALL → HALT → NORMAL
- **Proibido pular níveis na escalação:** NORMAL → PANIC_SELL é rejeitado (deve passar por HALT e CANCEL_ALL primeiro)
- **Permitido pular níveis na desescalação:** PANIC_SELL → NORMAL é permitido (após investigação completa)

#### 11.4.2 Endpoint de Status

```
GET /api/admin/safe-mode

Response:
    200 OK - {
        "level": "CANCEL_ALL",
        "activatedAt": "2026-02-15T10:30:00Z",
        "reason": "MAX_DRAWDOWN_EXCEEDED",
        "taskId": "uuid-task",
        "taskProgress": "3/5 ordens canceladas"
    }
```

#### 11.4.3 Integração com Monitoramento Externo

Para cenários onde o sistema não consegue acionar o kill-switch internamente (ex: JVM travada, deadlock):

| Mecanismo                 | Implementação                                                                          | Cenário                                                                       |
|---------------------------|----------------------------------------------------------------------------------------|-------------------------------------------------------------------------------|
| **Health Check endpoint** | `GET /actuator/health` inclui status do Safe Mode                                      | Monitoramento externo (Prometheus, Grafana) detecta anomalia e aciona via API |
| **Webhook de emergência** | Sistema externo chama `POST /api/admin/safe-mode`                                      | Operador aciona via dashboard, Slack bot, ou automação externa                |
| **Heartbeat invertido**   | Se o sistema não enviar heartbeat por `N` segundos, monitoramento externo assume crash | Cloud watchdog (ex: AWS CloudWatch Alarm) dispara ação de emergência          |

### 11.5 Protocolo de Retomada

> **Estado (T39, 2026-10-16):** Reset sem pré-condição de ordens em voo (a descida passo a passo e o status cobrem). Retomada automática por rejeições/latência: **T25**.

#### 11.5.1 Regras de Retomada

| Tipo de Gatilho        | Retomada Automática?  | Requisito para Reset                                         |
|------------------------|-----------------------|--------------------------------------------------------------|
| Max Daily Drawdown     | **NÃO**               | Auditoria manual + comando de Reset de Risco                 |
| Divergência de Saldo   | **NÃO**               | Investigação + correção do GlobalBalance + Reset             |
| Rejeições Consecutivas | **SIM** (por Runner)  | Após cooldown de `N` minutos sem novas rejeições             |
| Anomalia de Latência   | **SIM** (parcial)     | Após `N` minutos de estabilidade, retomada gradual (Warm-up) |

#### 11.5.2 Reset Manual (Desescalação)

```
POST /api/admin/safe-mode
{
    "level": "NORMAL",
    "confirmation": "<token>",
    "reason": "Auditoria concluída. Drawdown causado por flash crash BTC. Posições reconciliadas."
}
```

**Fluxo de desescalação:**

```
resetSafeMode(targetLevel, confirmationToken, reason):

    if (targetLevel.ordinal() >= currentLevel.ordinal()):
        throw new InvalidEscalationException("Use escalation endpoint, not reset")

    // Validar pré-condições de reset
    if (currentLevel == PANIC_SELL || currentLevel == CANCEL_ALL):
        // Verificar que não há ordens em voo pendentes
        pendingOrders = transactionRepository.countByStatusIn([SUBMITTED, PARTIAL])
        if (pendingOrders > 0):
            throw new SafeModeResetBlockedException(
                "Cannot reset: {pendingOrders} orders still in flight"
            )

    if (currentLevel == CANCEL_ALL && targetLevel == NORMAL):
        // Verificar que DLQ está limpa
        unresolvedDLQ = deadLetterRepository.countByIsResolved(false)
        if (unresolvedDLQ > 0):
            log.warn("Resetting with {} unresolved DLQ entries", unresolvedDLQ)
            // Permitir mas com warning — operador assume responsabilidade

    // Executar reset
    auditLog.record("SAFE_MODE_RESET", operatorId, reason, Instant.now())
    portfolio.safeModeStatus = targetLevel
    portfolioRepository.save(portfolio)
    log.info("Safe Mode reset: {} → {}, reason={}", currentLevel, targetLevel, reason)
```

#### 11.5.3 Warm-up (Retomada Gradual por Latência)

Para bloqueios causados por anomalia de latência, o sistema pode tentar retomada automática:

```
LatencyWarmup (Portfolio, periódico — apenas se reason == LATENCY_ANOMALY):

    // Verificar estabilidade
    windowStart = Instant.now().minus(config.warmupStabilityWindowMs)
    recentTimeouts = timeoutCounter.countSince(windowStart)

    if (recentTimeouts == 0 && portfolio.safeModeStatus == HALT):
        // Estável por tempo suficiente — tentar retomada
        portfolio.safeModeStatus = NORMAL
        portfolioRepository.save(portfolio)
        log.info("Latency warm-up: Safe Mode reset to NORMAL after {} ms stability",
                 config.warmupStabilityWindowMs)
        auditLog.record("SAFE_MODE_AUTO_RESET", "SYSTEM", "Latency warm-up", Instant.now())
```

**Restrição:** Warm-up automático só se aplica a HALT causado por latência. Drawdown e divergência **nunca** resetam automaticamente.

### 11.6 Circuit Breaker por Runner (Isolamento)

> **Estado (T39, 2026-10-16):** Pendente na **T25** (escopo ampliado pela T39).

Além do Circuit Breaker global (Portfolio), o Portfolio implementa isolamento por Runner para evitar que um Runner defeituoso afete os demais.

```
RunnerCircuitBreaker (Portfolio, event-driven):

    // Mantém contadores por Runner
    Map<UUID, SlidingWindowCounter> runnerErrorCounters

    onRunnerError(runnerId, errorType):
        counter = runnerErrorCounters.computeIfAbsent(runnerId, SlidingWindowCounter::new)
        counter.increment()

        if (counter.countInWindow(config.runnerCBWindowMs) >= config.runnerCBThreshold):
            suspendRunner(runnerId)

    suspendRunner(runnerId, reason):
        runner = runnerRepository.findById(runnerId)
        runner.status = HALTED
        runner.haltReason = reason
        runnerRepository.save(runner)
        log.warn("Runner suspended: id={}, reason={}", runnerId, reason)
        // Outros Runners NÃO são afetados

    // Retomada automática por Runner (após cooldown)
    @Scheduled(fixedRate = runnerCBCheckIntervalMs)
    checkRunnerRecovery():
        haltedRunners = runnerRepository.findByStatus(HALTED)
        for each runner in haltedRunners:
            if (runner.haltReason IN [EXCESSIVE_REJECTIONS, LATENCY]):
                lastError = runnerErrorCounters.get(runner.id).lastErrorAt()
                if (lastError.isBefore(Instant.now().minus(config.runnerCooldownMs))):
                    runner.status = ACTIVE
                    runner.haltReason = null
                    runnerRepository.save(runner)
                    log.info("Runner recovered: id={}", runner.id)
```

| Aspecto          | Circuit Breaker Global          | Circuit Breaker por Runner         |
|------------------|---------------------------------|------------------------------------|
| **Escopo**       | Todos os Runners                | Runner individual                  |
| **Acionado por** | Drawdown, Divergência, Latência | Rejeições consecutivas do Runner   |
| **Efeito**       | Nenhum Runner pode operar       | Apenas o Runner afetado é suspenso |
| **Retomada**     | Manual (exceto latência)        | Automática após cooldown           |
| **Persistência** | `portfolios.safe_mode_status`   | `strategy_runners.status = HALTED` |

### 11.7 Observabilidade

> **Estado (T39, 2026-10-16):** Existem `circuit_breaker.drawdown.current`, `.drawdown.peak_24h`, `circuit_breaker.equity.current` e `circuit_breaker.activations.total` (T43). T51: `circuit_breaker.current_level`, `circuit_breaker.duration.active.seconds` (gauge, tempo desde a última mudança de nível) e `circuit_breaker.runner.suspended.count` por portfolio. Progresso de CANCEL_ALL/PANIC_SELL fica no `GET .../safe-mode`.

| Métrica                                  | Tipo    | Descrição                                                      |
|------------------------------------------|---------|----------------------------------------------------------------|
| `circuit_breaker.activations.total`      | Counter | Total de ativações (por nível e motivo)                        |
| `circuit_breaker.current_level`          | Gauge   | Nível atual do Safe Mode (0=Normal, 1=Halt, 2=Cancel, 3=Panic) |
| `circuit_breaker.duration.active`        | Timer   | Tempo total em Safe Mode (por nível)                           |
| `circuit_breaker.drawdown.current`       | Gauge   | Drawdown atual (%)                                             |
| `circuit_breaker.drawdown.peak_24h`      | Gauge   | Peak balance nas últimas 24h                                   |
| `circuit_breaker.runner.suspended.count` | Gauge   | Número de Runners em HALTED                                    |
| `circuit_breaker.cancel_all.progress`    | Gauge   | Progresso do Cancel All (ordens canceladas / total)            |
| `circuit_breaker.panic_sell.progress`    | Gauge   | Progresso do Panic Sell (posições liquidadas / total)          |
| `circuit_breaker.rejections.per_runner`  | Counter | Rejeições por Runner (sliding window)                          |

**Alertas recomendados:**

| Condição                                                      | Severidade  | Ação                                                     |
|---------------------------------------------------------------|-------------|----------------------------------------------------------|
| `circuit_breaker.current_level > 0`                           | CRITICAL    | Safe Mode ativado — investigação imediata                |
| `circuit_breaker.drawdown.current > 0.5 * maxDrawdownPercent` | WARNING     | Drawdown se aproximando do limite                        |
| `circuit_breaker.runner.suspended.count > 0`                  | WARNING     | Runner suspenso — verificar configuração                 |
| `circuit_breaker.cancel_all.progress` estagnado por > 60s     | CRITICAL    | Cancelamento travou — possível problema de conectividade |

### 11.8 Configuração

| Parâmetro                                              | Default  | Descrição                                             |
|--------------------------------------------------------|----------|-------------------------------------------------------|
| `portfolio.circuit-breaker.max-drawdown-percent`       | `0.10`   | Percentual máximo de drawdown em 24h (10%)            |
| `portfolio.circuit-breaker.drawdown-check-interval-ms` | `10000`  | Frequência de verificação do drawdown                 |
| `portfolio.circuit-breaker.max-consecutive-rejections` | `5`      | Rejeições consecutivas para suspender Runner          |
| `portfolio.circuit-breaker.rejection-window-ms`        | `60000`  | Janela de tempo para contagem de rejeições            |
| `portfolio.circuit-breaker.max-timeouts-before-halt`   | `3`      | Timeouts para ativar Halt por latência                |
| `portfolio.circuit-breaker.latency-window-ms`          | `120000` | Janela de tempo para contagem de timeouts             |
| `portfolio.circuit-breaker.warmup-stability-window-ms` | `300000` | Tempo de estabilidade para warm-up automático (5 min) |
| `portfolio.circuit-breaker.runner-cooldown-ms`         | `300000` | Cooldown para retomada automática de Runner (5 min)   |
| `portfolio.circuit-breaker.runner-cb-threshold`        | `5`      | Erros para suspender um Runner individual             |
| `portfolio.circuit-breaker.runner-cb-window-ms`        | `60000`  | Janela do circuit breaker por Runner                  |

### 11.9 Implicações Arquiteturais

| Decisão                                 | Justificativa                                                                        | Trade-off                                                                                       |
|-----------------------------------------|--------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------|
| Gatekeeper como Step 1 do `reserve()`   | Impede qualquer operação financeira em Safe Mode — ponto único de bloqueio           | Latência de ~1ms por check (negligível)                                                         |
| Acionamento automático apenas para Halt | Limita ações destrutivas (Cancel, Panic Sell) a decisão humana                       | Reação mais lenta em cenários extremos — operador precisa estar disponível                      |
| Proibido pular níveis na escalação      | Cada nível é validado antes de escalar — previne acionamento acidental de Panic Sell | Mais passos manuais em emergências reais                                                        |
| Safe Mode persistido no DB              | Sobrevive a restarts — não "esquece" o Circuit Breaker                               | Restart não resolve o problema automaticamente                                                  |
| Nunca resetar automaticamente no boot   | Motivo do break provavelmente ainda existe após crash                                | Requer intervenção manual mesmo após problemas transitórios (mitigado pelo warm-up de latência) |
| Circuit Breaker por Runner isolado      | Um Runner defeituoso não paralisa os demais                                          | Mais complexidade de monitoramento (N circuit breakers vs 1 global)                             |
| Cancel All / Panic Sell assíncronos     | Não bloqueia a API administrativa; feedback progressivo                              | Operador não sabe o resultado imediato — precisa monitorar progresso                            |
| Kill-Switch via REST API                | Integração com qualquer sistema externo (Grafana, Slack, scripts)                    | Requer autenticação e segurança do endpoint (TLS + auth token)                                  |

---

## 12. Modelo de Concorrência e Processamento do Runner

Esta seção detalha **como implementar** o modelo de concorrência de cada StrategyRunner — a fila de sinais, a política de descarte, o isolamento de threads, e os mecanismos de proteção contra Runners mal comportados. O modelo garante que o estado de posição nunca seja corrompido por processamento paralelo.

> **Referência:** Blueprint Seção 12 (Modelo de Concorrência e Processamento do Runner).
> **Notas de Implementação absorvidas:** #23 (Implementação da Mailbox), #24 (Monitoramento de Backpressure), #25 (Lock de Interface / Prioridade de Comandos Admin).
> **Dependência:** Seção 9.5 (processamento sequencial previne deadlocks de locks), Seção 11.6 (Circuit Breaker por Runner).

### 12.1 Modelo de Processamento: Fila Sequencial com Drop Policy

> **Estado (T42, 2026-10-19):** Implementado com diferenças. Mailbox por runner no core (`RunnerMailbox`: capacidade 1, latest-wins, sequencial, descarte fora de ordem) drenada por um pool compartilhado (`runnerSignalExecutor`), não uma thread virtual por runner; market data e user data em executores separados. Stale check medido desde o recebimento do tick (`runner.signal.stale-tick-threshold-ms`). Timeouts por fase (§12.7) viram alerta (`runner.signal.processing.time` + WARN), não aborto. Backpressure: `runner.ticks.discarded{reason}`. Comandos admin (§12.5) são endpoints síncronos (T49), sem fila de prioridade. Em SINGLE, só o BUY é barrado com ordem em voo; SELL concorrente é barrada pelo lock de posição.

Cada Runner opera sob o padrão **Actor/Mailbox** — uma fila de entrada com capacidade limitada e processamento estritamente sequencial.

```
                                 ┌──────────────────────────────────┐
                                 │        StrategyRunner            │
                                 │                                  │
   TradeSignal ──► [Mailbox]──►  │  processSignal() — single thread │
   (capacity=1)    │             │                                  │
                   │ FULL?       │  ┌─ Step 1: Stale Check          │
                   │ → DISCARD   │  ├─ Step 2: Execution Policy     │
                   │             │  ├─ Step 3: Lock Acquisition     │
                   │             │  ├─ Step 4: Capital Request      │
                   │             │  ├─ Step 5: Order Dispatch       │
                   │             │  └─ Step 6: Await Confirmation   │
                   │             │                                  │
   AdminCommand ──►[Priority]──► │  processCommand() — interrupts   │
   (Force Cancel)                │                                  │
                                 └──────────────────────────────────┘
```

**Justificativa do processamento sequencial:** Operações de trading dependem do estado imediatamente anterior (saldo atualizado, lotes disponíveis, posição corrente). Processamento paralelo exigiria locks complexos que aumentariam latência e risco de deadlocks — o custo não justifica o benefício.

### 12.2 Implementação da Mailbox

> **Nota #23 absorvida:** "Utilizar um padrão de Actor ou fila Channel com limite de 1 elemento. O processador deve usar TryEnqueue. Se falhar (fila cheia), logar como SignalDiscardedByCongestion."

#### 12.2.1 Estrutura em Java (Spring Boot)

```java
@Component
@Scope("prototype")  // Um bean por Runner
public class RunnerSignalProcessor {

    // Mailbox com capacidade 1 — sinais excedentes são descartados
    private final BlockingQueue<TradeSignal> mailbox =
        new ArrayBlockingQueue<>(1);

    // Fila de comandos admin — sem limite, prioridade máxima
    private final BlockingQueue<AdminCommand> commandQueue =
        new LinkedBlockingQueue<>();

    private volatile boolean running = true;

    /**
     * Tenta enfileirar um sinal. Se a mailbox estiver cheia, descarta.
     * @return true se aceito, false se descartado
     */
    public boolean offer(TradeSignal signal) {
        boolean accepted = mailbox.offer(signal);  // Non-blocking
        if (!accepted) {
            log.debug("Signal discarded by congestion: runnerId={}, signal={}",
                      runnerId, signal.id());
            metrics.counter("runner.signals.discarded",
                           "reason", "congestion",
                           "runner", runnerId).increment();
        }
        return accepted;
    }

    /**
     * Enfileira comando admin — sempre aceito, prioridade máxima.
     */
    public void submitCommand(AdminCommand command) {
        commandQueue.add(command);
        // Se a thread estiver bloqueada esperando sinal, interrompe
        processingThread.interrupt();
    }

    /**
     * Loop principal — executa em thread dedicada.
     */
    public void run() {
        while (running) {
            // Prioridade 1: Comandos admin
            AdminCommand cmd = commandQueue.poll();
            if (cmd != null) {
                processCommand(cmd);
                continue;
            }

            // Prioridade 2: Sinais de trading
            try {
                TradeSignal signal = mailbox.poll(
                    pollTimeoutMs, TimeUnit.MILLISECONDS
                );
                if (signal != null) {
                    processSignal(signal);
                }
            } catch (InterruptedException e) {
                // Interrompido por comando admin — voltar ao topo do loop
                Thread.currentThread().interrupt();
            }
        }
    }
}
```

#### 12.2.2 Decisões Técnicas

| Decisão                   | Justificativa                                                                                                                           |
|---------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| `ArrayBlockingQueue(1)`   | Capacidade 1 garante que no máximo 1 sinal aguarda. Sinais mais novos são descartados — o sinal em espera já é o "mais fresco" possível |
| `offer()` (non-blocking)  | O thread que envia sinais (WebSocket listener) não deve bloquear esperando o Runner                                                     |
| `commandQueue` sem limite | Comandos admin são raros e críticos — nunca descartar                                                                                   |
| `poll()` com timeout      | Permite check periódico de shutdown e comandos admin                                                                                    |

### 12.3 Stale Signal Check

Antes de processar, o Runner verifica se o sinal ainda é relevante:

```java
private void processSignal(TradeSignal signal) {
    long signalAge = Duration.between(signal.createdAt(), Instant.now()).toMillis();

    if (signalAge > config.staleSignalThresholdMs()) {
        log.info("Stale signal discarded: runnerId={}, age={}ms, threshold={}ms",
                 runnerId, signalAge, config.staleSignalThresholdMs());
        metrics.counter("runner.signals.discarded",
                       "reason", "stale",
                       "runner", runnerId).increment();
        return;
    }

    // Sinal válido — prosseguir com materialização (Seção 6.1)
    materializeSignal(signal);
}
```

**Motivo:** Entre a geração do sinal pela Strategy e o processamento pelo Runner, o mercado pode ter mudado. Um sinal com preço de 5 segundos atrás pode resultar em slippage inaceitável ou ordem rejeitada.

### 12.4 Bloqueio por Status (Execution Policy)

O Runner aplica filtros adicionais baseados no seu estado atual e na Execution Policy configurada:

#### 12.4.1 Matriz de Aceitação

| Estado do Runner                      | Execution Policy  | Sinal SHOULD_BUY               | Sinal SHOULD_SELL              |
|---------------------------------------|-------------------|--------------------------------|--------------------------------|
| Sem posição, sem ordens em voo        | Single / Netting  | **ACEITA**                     | **REJEITA** (nada para vender) |
| Com posição OPEN, sem ordens em voo   | Single            | **REJEITA** (já tem posição)   | **ACEITA**                     |
| Com posição OPEN, sem ordens em voo   | Netting           | **ACEITA** (scaling)           | **ACEITA**                     |
| Com ordens em voo (SUBMITTED/PARTIAL) | Single            | **REJEITA**                    | **REJEITA**                    |
| Com ordens em voo (SUBMITTED/PARTIAL) | Netting           | **FILA** (aguarda finalização) | **FILA** (aguarda finalização) |

> **Nota sobre FILA em Netting:** O sinal permanece na mailbox (capacity=1) enquanto a ordem em voo não finaliza. O processamento sequencial garante que o sinal será processado após a finalização. Se um **novo** sinal chegar enquanto já há um na mailbox, o novo é descartado pela Drop Policy (capacity=1) — o sinal em espera já é o mais recente possível.
| `isReconciling = true` | Qualquer | **REJEITA** | **REJEITA** |
| `status = HALTED` | Qualquer | **REJEITA** | **REJEITA** |

#### 12.4.2 Implementação

```java
private boolean shouldAcceptSignal(TradeSignal signal) {
    // Circuit Breaker / Reconciliação
    if (this.isReconciling || this.status == HALTED) {
        metrics.counter("runner.signals.rejected", "reason", "not_ready").increment();
        return false;
    }

    // Execution Policy check
    boolean hasInflightOrders = transactionRepository
        .existsByRunnerIdAndStatusIn(runnerId, List.of(SUBMITTED, PARTIAL));

    if (executionPolicy == SINGLE) {
        if (hasInflightOrders) {
            metrics.counter("runner.signals.rejected", "reason", "inflight_order").increment();
            return false;
        }
        if (signal.decision() == SHOULD_BUY && hasOpenPosition()) {
            metrics.counter("runner.signals.rejected", "reason", "position_open").increment();
            return false;
        }
    }

    return true;
}
```

### 12.5 Prioridade de Comandos Admin

> **Estado (T39, 2026-10-16):** Implementados na T49 como endpoints síncronos (sem fila de comandos, que depende da mailbox da T42): `POST /api/admin/runners/{id}/orders/{tx}/cancel`, `POST /api/admin/runners/{id}/positions/{pos}/close` (SELL MARKET pelo pipeline normal) e `POST /api/admin/runners/{id}/resume`.

> **Nota #25 absorvida:** "Comandos manuais via Portfolio/Admin (ex: Force Cancel) têm prioridade máxima e devem furar a fila ou interromper o processamento do sinal atual."

#### 12.5.1 Tipos de Comandos Admin

| Comando                | Ação                                           | Prioridade  | Interruptível?                               |
|------------------------|------------------------------------------------|-------------|----------------------------------------------|
| `FORCE_CANCEL`         | Cancela uma Transaction específica na Exchange | MÁXIMA      | Sim — interrompe sinal em processamento      |
| `FORCE_CLOSE_POSITION` | Gera Market Order de venda para fechar posição | MÁXIMA      | Sim                                          |
| `HALT_RUNNER`          | Muda status para HALTED                        | MÁXIMA      | Sim                                          |
| `RESUME_RUNNER`        | Muda status para ACTIVE (após validação)       | ALTA        | Não — aguarda término do processamento atual |

#### 12.5.2 Mecanismo de Interrupção

```java
private void processCommand(AdminCommand cmd) {
    log.info("Processing admin command: runnerId={}, type={}", runnerId, cmd.type());

    switch (cmd.type()) {
        case FORCE_CANCEL:
            Transaction tx = transactionRepository.findById(cmd.transactionId());
            if (tx.status() == SUBMITTED || tx.status() == PARTIAL) {
                exchangeAdapter.cancelOrder(tx.exchangeOrderId(), tx.symbol());
                // O callback de cancelamento processará o unlock e estorno
            }
            break;

        case HALT_RUNNER:
            this.status = HALTED;
            this.haltReason = cmd.reason();
            runnerRepository.save(this);
            break;

        case FORCE_CLOSE_POSITION:
            // Bypass da cadeia normal — ordem de emergência
            Position pos = positionRepository.findById(cmd.positionId());
            Transaction closeOrder = Transaction.createMarketClose(
                runnerId, pos.symbol(), pos.quantity(), SELL, "ADMIN_FORCE_CLOSE"
            );
            transactionRepository.save(closeOrder);
            exchangeAdapter.submitOrder(closeOrder.toExchangeOrder());
            break;

        case RESUME_RUNNER:
            if (!hasInflightOrders() && !hasPendingDLQ()) {
                this.status = ACTIVE;
                this.haltReason = null;
                runnerRepository.save(this);
            }
            break;
    }
}
```

**Garantia:** Comandos admin são processados **antes** de qualquer sinal no loop principal (Seção 12.2.1). Se o Runner estiver bloqueado aguardando um sinal (`mailbox.poll()`), o `submitCommand()` interrompe a thread via `interrupt()`.

### 12.6 Isolamento de Threads

#### 12.6.1 Modelo de Execução

Cada Runner executa em uma **Virtual Thread** dedicada (Java 21+):

```java
@Service
public class RunnerExecutorService {

    private final Map<UUID, Thread> runnerThreads = new ConcurrentHashMap<>();

    public void startRunner(UUID runnerId, RunnerSignalProcessor processor) {
        Thread vThread = Thread.ofVirtual()
            .name("runner-" + runnerId.toString().substring(0, 8))
            .start(processor::run);

        runnerThreads.put(runnerId, vThread);
        log.info("Runner started on virtual thread: runnerId={}", runnerId);
    }

    public void stopRunner(UUID runnerId) {
        Thread vThread = runnerThreads.remove(runnerId);
        if (vThread != null) {
            vThread.interrupt();
            // Aguardar finalização com timeout
            try {
                vThread.join(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                log.warn("Runner thread did not stop gracefully: runnerId={}", runnerId);
            }
        }
    }
}
```

#### 12.6.2 Justificativa: Virtual Threads vs Platform Threads

| Aspecto                 | Platform Threads               | Virtual Threads (Java 21+)                          |
|-------------------------|--------------------------------|-----------------------------------------------------|
| **Overhead por Runner** | ~1MB stack cada                | ~KBs (gerenciado pela JVM)                          |
| **Escalabilidade**      | Limitado a dezenas de Runners  | Centenas/milhares de Runners                        |
| **Blocking I/O**        | Bloqueia thread do OS          | Bloqueia apenas a virtual thread (carrier liberada) |
| **Adequação**           | Runners com trabalho CPU-bound | Runners com I/O-bound (exchange calls, DB queries)  |

**Decisão:** Virtual Threads são a escolha natural para Runners que passam a maior parte do tempo aguardando I/O (chamadas REST, queries DB, confirmações da Exchange). Cada Runner pode bloquear sua virtual thread sem impactar os demais.

**Fallback Java < 21:** Usar `ExecutorService` com pool de platform threads dimensionado para o número esperado de Runners + margem.

### 12.7 Timeout de Operações

Cada fase do processamento de sinal possui um timeout máximo para evitar que o Runner fique travado:

| Operação                      | Timeout                              | Ação em Timeout                                          |
|-------------------------------|--------------------------------------|----------------------------------------------------------|
| Processamento total do sinal  | `signal-processing-timeout-ms` (60s) | Abortar processamento, marcar TX como FAILED se criada   |
| Capital Request (`reserve()`) | `capital-request-timeout-ms` (10s)   | Abortar, descartar sinal                                 |
| Order Dispatch (Exchange)     | `dispatch-timeout-ms` (10s)          | Marcar como SUBMITTED, iniciar reconciliação (Seção 6.3) |
| Lock Acquisition (DB)         | `lock-acquisition-timeout-ms` (5s)   | Abortar, descartar sinal                                 |

```java
private void materializeSignal(TradeSignal signal) {
    try {
        CompletableFuture.runAsync(() -> {
            // Step 1-6 do processamento
            executeSignalPipeline(signal);
        }).get(config.signalProcessingTimeoutMs(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
        log.error("Signal processing timeout: runnerId={}, signalId={}",
                  runnerId, signal.id());
        metrics.counter("runner.signals.timeout", "runner", runnerId).increment();
        // Se Transaction foi criada em PENDING, Watchdog cuida do cleanup
    }
}
```

### 12.8 Monitoramento de Backpressure

> **Nota #24 absorvida:** "Registrar o tempo de processamento de cada sinal. Se o tempo médio exceder o intervalo de geração de sinais da estratégia, o Circuit Breaker deve sugerir revisão."

#### 12.8.1 Métricas de Backpressure

| Métrica                         | Tipo    | Descrição                                                                                              |
|---------------------------------|---------|--------------------------------------------------------------------------------------------------------|
| `runner.signal.processing.time` | Timer   | Tempo do recebimento à persistência do SUBMITTED (por Runner)                                          |
| `runner.signals.discarded`      | Counter | Sinais descartados (por motivo: `congestion`, `stale`, `not_ready`, `inflight_order`, `position_open`) |
| `runner.signals.accepted`       | Counter | Sinais aceitos e processados                                                                           |
| `runner.signal.discard.ratio`   | Gauge   | Taxa de descarte: `discarded / (accepted + discarded)`                                                 |
| `runner.mailbox.utilization`    | Gauge   | 0 (vazia) ou 1 (cheia) — indica se há sinal aguardando                                                 |
| `runner.command.queue.size`     | Gauge   | Tamanho da fila de comandos admin                                                                      |
| `runner.locks.active`           | Gauge   | Locks ativos não resolvidos (por Runner) — acúmulo anormal indica bug                                  |
| `runner.thread.state`           | Gauge   | Estado atual: PROCESSING, WAITING, HALTED, RECONCILING                                                 |

#### 12.8.2 Detecção de Backpressure

```
BackpressureDetector (por Runner, periódico):

    avgProcessingTime = metrics.timer("runner.signal.processing.time")
                              .mean(Duration.ofMinutes(5))

    signalGenerationInterval = strategy.expectedSignalIntervalMs()
    // Ex: strategy que gera sinal a cada 5s → interval = 5000ms

    if (avgProcessingTime > signalGenerationInterval):
        log.warn("Backpressure detected: runnerId={}, avgProcessing={}ms, signalInterval={}ms",
                 runnerId, avgProcessingTime, signalGenerationInterval)
        metrics.counter("runner.backpressure.detected", "runner", runnerId).increment()

        // Ação: alerta para o operador — NÃO ativa Circuit Breaker automaticamente
        // Motivo: backpressure pode ser transitória (pico de mercado)
        // Recomendação: revisar lógica da strategy ou infraestrutura
```

**Decisão:** Backpressure gera alerta, não suspensão automática. A causa pode ser transitória (flash crash com alta volatilidade), e suspender o Runner perderia oportunidades. O operador decide.

### 12.9 Proteção de Componentes Compartilhados

#### 12.9.1 Quota no ExchangeAdapter

```java
@Component
public class ExchangeAdapterWithQuota implements ExchangeAdapter {

    // Quota de requests por Runner (sliding window)
    private final Map<UUID, RateLimiter> runnerQuotas = new ConcurrentHashMap<>();

    @Override
    public OrderResult submitOrder(UUID runnerId, ExchangeOrder order) {
        RateLimiter limiter = runnerQuotas.computeIfAbsent(
            runnerId, id -> RateLimiter.create(config.maxRequestsPerRunnerPerMinute())
        );

        if (!limiter.tryAcquire()) {
            log.warn("Runner rate limited: runnerId={}", runnerId);
            metrics.counter("exchange.requests.throttled", "runner", runnerId).increment();
            throw new RunnerThrottledException(runnerId);
        }

        return delegate.submitOrder(order);
    }
}
```

#### 12.9.2 Tabela de Limites por Componente

| Componente Compartilhado    | Mecanismo de Proteção                   | Efeito no Runner                                 |
|-----------------------------|-----------------------------------------|--------------------------------------------------|
| **Portfolio** (`reserve()`) | Circuit Breaker por Runner (Seção 11.6) | Runner suspenso após N erros                     |
| **ExchangeAdapter**         | Rate Limiter por Runner                 | Request rejeitado com `RunnerThrottledException` |
| **DB Connection Pool**      | Timeout na aquisição de conexão         | Operação falha — Watchdog cuida do cleanup       |

### 12.10 Configuração

| Parâmetro                                      | Default   | Descrição                                                                  |
|------------------------------------------------|-----------|----------------------------------------------------------------------------|
| `runner.mailbox.capacity`                      | `1`       | Capacidade da fila de sinais (0 = sem buffer, apenas processamento direto) |
| `runner.stale-signal-threshold-ms`             | `5000`    | Idade máxima de um sinal antes de ser descartado como obsoleto             |
| `runner.signal-processing-timeout-ms`          | `60000`   | Timeout total do processamento de sinal                                    |
| `runner.capital-request-timeout-ms`            | `10000`   | Timeout para `ReserveCapitalPort.reserve()`                                |
| `runner.lock-acquisition-timeout-ms`           | `5000`    | Timeout para aquisição de lock pessimista                                  |
| `runner.poll-timeout-ms`                       | `1000`    | Timeout do `mailbox.poll()` antes de verificar comandos admin              |
| `runner.thread.type`                           | `VIRTUAL` | Tipo de thread: `VIRTUAL` (Java 21+) ou `PLATFORM`                         |
| `exchange.max-requests-per-runner-per-minute`  | `60`      | Quota de requests no ExchangeAdapter por Runner                            |
| `runner.backpressure.detection-window-minutes` | `5`       | Janela para cálculo de média de processamento                              |

### 12.11 Implicações Arquiteturais

| Decisão                                    | Justificativa                                                                | Trade-off                                                                                    |
|--------------------------------------------|------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| Processamento sequencial (Actor/Mailbox)   | Evita corrupção de estado — cada operação vê o estado atualizado da anterior | Throughput limitado a 1 sinal por vez por Runner (aceitável — trading não é bulk processing) |
| Drop Policy (capacity=1)                   | Sinais descartados são stale — melhor operar com informação fresca           | Pode perder oportunidades em bursts (mitigado: se mercado é forte, próximo sinal captura)    |
| Stale Signal Check                         | Previne ordens com preço defasado que causariam slippage ou rejeição         | Threshold muito baixo pode descartar sinais válidos em momentos de alta latência             |
| Comandos admin com prioridade máxima       | Segurança do capital > oportunidade de trade                                 | Interrupção pode deixar sinal parcialmente processado (mitigado pelo Watchdog)               |
| Virtual Threads (Java 21+)                 | Escalabilidade natural para I/O-bound — centenas de Runners sem overhead     | Requer Java 21+; debugging de virtual threads menos maduro                                   |
| Rate Limiter por Runner no ExchangeAdapter | Um Runner saturado não consome a quota de API dos demais                     | Adiciona latência (~1ms) e possíveis rejeições de request legítimos                          |
| Backpressure → alerta (não suspensão)      | Causas podem ser transitórias — suspensão automática perderia oportunidades  | Requer monitoramento ativo do operador para agir                                             |
| Timeout em cada fase do pipeline           | Impede que o Runner fique travado em qualquer etapa                          | Timeout muito agressivo pode abortar operações legítimas em condições de alta latência       |

---

## 13. Orquestração do Ciclo de Vida do Runner

Esta seção detalha **como implementar** a máquina de estados do StrategyRunner — desde a criação via API administrativa até o arquivamento definitivo — incluindo o protocolo de provisionamento (Factory), as transições válidas, o Graceful Shutdown com posições abertas, e o Health Check de ativação.

> **Referência:** Blueprint Seção 13 (Orquestração do Ciclo de Vida do Runner).
> **Notas de Implementação absorvidas:** #26 (Factory de Runners), #27 (Soft Delete vs Archive — já absorvida na Seção 3.3.1), #28 (Health Check de Ativação).
> **Dependência:** Seção 3.3.1 (campos do StrategyRunner), Seção 10.2 (Boot Sequence — Phase 3), Seção 12 (modelo de concorrência).

### 13.1 Máquina de Estados do Runner

> **Estado (T39, 2026-10-16):** Implementado na T49. Diferenças: TERMINATING continua no pipeline só para vender (`canAcceptSignals` × `canOpenPositions`); o kill switch também para um runner TERMINATING; start/resume reconciliam pelo mesmo caminho do boot.

```
                              API Create
                                  │
                           ┌──────▼──────┐
                           │   CREATED   │
                           └──────┬──────┘
                                  │ start()
                           ┌──────▼──────┐
                           │INITIALIZING │──── Boot Sequence (Seção 10.2 Phase 3)
                           └──────┬──────┘
                                  │ reconciliação OK
                           ┌──────▼──────┐
              ┌────────────│   ACTIVE    │◄───────────┐
              │            └──────┬──────┘            │
              │ Circuit Breaker   │ terminate()       │ resume()
              │ / erro            │                   │
         ┌────▼─────┐     ┌──────▼──────┐        ┌────┴─────┐
         │  HALTED  │     │ TERMINATING │        │  HALTED  │
         └──────────┘     └──────┬──────┘        └──────────┘
                                 │ margem = 0, posições = 0
                          ┌──────▼──────┐
                          │  ARCHIVED   │  (terminal — irreversível)
                          └─────────────┘
```

#### 13.1.1 Definição dos Estados

| Estado         | Descrição                                         | Aceita Sinais?                            | Ordens em Voo?               | Thread Ativa?    |
|----------------|---------------------------------------------------|-------------------------------------------|------------------------------|------------------|
| `CREATED`      | Persistido no DB, sem recursos alocados           | Não                                       | Não                          | Não              |
| `INITIALIZING` | Executando Boot Sequence (reconciliação)          | Não (`isReconciling=true`)                | Possível (do crash anterior) | Sim              |
| `ACTIVE`       | Operacional — processando sinais                  | Sim                                       | Sim                          | Sim              |
| `HALTED`       | Suspenso por violação de risco ou DLQ             | Não                                       | Monitoradas (não canceladas) | Sim (aguardando) |
| `TERMINATING`  | Encerrando — fechando posições e liberando margem | Não (novos BUY), Sim (SELL de fechamento) | Sim (ordens de liquidação)   | Sim              |
| `ARCHIVED`     | Terminal — histórico preservado para auditoria    | Não                                       | Não                          | Não              |

#### 13.1.2 Transições Válidas

| De                         | Para                   | Gatilho                                                               | Validação  |
|----------------------------|------------------------|-----------------------------------------------------------------------|------------|
| `CREATED` → `INITIALIZING` | `start()`              | Health Check (Seção 13.3) deve passar                                 |
| `INITIALIZING` → `ACTIVE`  | Boot Sequence completo | `isReconciling = false`, sem DLQ pendente                             |
| `INITIALIZING` → `HALTED`  | Boot Sequence falha    | DLQ pendente ou Exchange inacessível                                  |
| `ACTIVE` → `HALTED`        | Circuit Breaker / erro | Seção 11.6 (por Runner) ou Seção 11.2 (global)                        |
| `ACTIVE` → `TERMINATING`   | `terminate()`          | Comando administrativo                                                |
| `HALTED` → `ACTIVE`        | `resume()`             | Sem DLQ pendente + Health Check passa                                 |
| `HALTED` → `TERMINATING`   | `terminate()`          | Comando administrativo                                                |
| `TERMINATING` → `ARCHIVED` | Automático             | `reservedMargin = 0` AND `openPositions = 0` AND `inflightOrders = 0` |

**Transições inválidas (rejeição explícita):**
- `ARCHIVED` → qualquer estado (irreversível)
- `CREATED` → `ACTIVE` (deve passar por INITIALIZING)
- `TERMINATING` → `ACTIVE` (deve criar novo Runner)
- `ACTIVE` → `CREATED` (sem sentido — já inicializado)

### 13.2 Provisionamento: RunnerFactory

> **Nota #26 absorvida:** "Implementar um RunnerFactory que valide as permissões do Portfolio antes de instanciar o Runner. O Runner deve receber suas políticas via construtor para garantir imutabilidade durante a execução."

#### 13.2.1 Responsabilidades do Factory

```java
@Service
public class RunnerFactory {

    /**
     * Cria e persiste um novo StrategyRunner no estado CREATED.
     * NÃO inicia a execução — apenas provisiona.
     */
    public StrategyRunner create(CreateRunnerCommand cmd) {

        // Step 1: Validar que o Portfolio existe e está ativo
        Portfolio portfolio = portfolioRepository.findById(cmd.portfolioId())
            .orElseThrow(() -> new PortfolioNotFoundException(cmd.portfolioId()));
        if (!portfolio.isActive()) {
            throw new PortfolioNotActiveException(cmd.portfolioId());
        }

        // Step 2: Validar constraint de unicidade
        boolean duplicate = runnerRepository.existsByStrategyIdAndSymbolAndExchangeIdAndPortfolioIdAndStatusNotIn(
            cmd.strategyId(), cmd.symbol(), cmd.exchangeId(), cmd.portfolioId(),
            List.of(ARCHIVED, TERMINATING)
        );
        if (duplicate) {
            throw new DuplicateRunnerException(cmd.strategyId(), cmd.symbol(), cmd.exchangeId());
        }

        // Step 3: Validar limite de Runners por Portfolio
        int activeRunners = runnerRepository.countByPortfolioIdAndStatusIn(
            cmd.portfolioId(), List.of(CREATED, INITIALIZING, ACTIVE, HALTED)
        );
        if (activeRunners >= portfolio.maxRunners()) {
            throw new MaxRunnersExceededException(cmd.portfolioId(), portfolio.maxRunners());
        }

        // Step 4: Resolver políticas (imutáveis após criação)
        ExecutionPolicy executionPolicy = policyResolver.resolveExecutionPolicy(cmd.executionPolicyType());
        AccountingPolicy accountingPolicy = policyResolver.resolveAccountingPolicy(cmd.accountingPolicyType());

        // Step 5: Construir e persistir
        StrategyRunner runner = StrategyRunner.builder()
            .id(UUID.randomUUID())
            .portfolioId(cmd.portfolioId())
            .strategyId(cmd.strategyId())
            .strategyName(cmd.strategyName())
            .symbol(cmd.symbol())
            .exchangeId(cmd.exchangeId())
            .shortCode(generateUniqueShortCode())
            .executionPolicy(executionPolicy)
            .accountingPolicy(accountingPolicy)
            .maxAllocationPercent(cmd.maxAllocationPercent())
            .maxOpenPositions(cmd.maxOpenPositions())
            .maxPendingOrders(cmd.maxPendingOrders())
            .dedicatedBudget(cmd.dedicatedBudget())  // null para SHARED
            .status(CREATED)
            .isReconciling(false)
            .createdAt(Instant.now())
            .build();

        runnerRepository.save(runner);
        log.info("Runner created: id={}, strategy={}, symbol={}", runner.id(), cmd.strategyId(), cmd.symbol());
        return runner;
    }
}
```

#### 13.2.2 Imutabilidade de Políticas

| Campo                  | Mutável em Runtime?  | Motivo                                                               |
|------------------------|----------------------|----------------------------------------------------------------------|
| `executionPolicy`      | **NÃO**              | Mudar de Single para Netting com posição aberta corromperia o estado |
| `accountingPolicy`     | **NÃO**              | Mudar de FIFO para LIFO com lotes parciais quebraria a contabilidade |
| `strategyId`           | **NÃO**              | Identifica o algoritmo — mudar requer novo Runner                    |
| `symbol`               | **NÃO**              | Posições são vinculadas ao símbolo                                   |
| `maxAllocationPercent` | **SIM**              | Pode ser ajustado sem impacto no estado existente                    |
| `maxOpenPositions`     | **SIM**              | Novo limite aplica-se a futuras aberturas                            |
| `maxPendingOrders`     | **SIM**              | Novo limite aplica-se a futuras ordens                               |
| `dedicatedBudget`      | **SIM**              | Requer recalcular alocação, mas não afeta posições abertas           |

**Regra:** Para alterar campos imutáveis, o operador deve criar um novo Runner e encerrar (TERMINATING → ARCHIVED) o anterior.

### 13.3 Health Check de Ativação

> **Estado (T49, 2026-10-16):** Implementado de forma mínima: adapter registrado e envio de ordens liberado (`isDispatchBlocked`).

> **Nota #28 absorvida:** "Ao passar para ACTIVE, o Runner deve obrigatoriamente realizar um ping na API da Exchange e validar o tickSize do ativo. Se falhar, o status deve retroceder para HALTED."

#### 13.3.1 Protocolo

O Health Check é executado em duas transições:
- `CREATED → INITIALIZING` (antes de iniciar o Boot Sequence)
- `HALTED → ACTIVE` (antes de retomar operações)

```java
public HealthCheckResult performHealthCheck(StrategyRunner runner) {

    // Check 1: Conectividade com a Exchange
    try {
        exchangeAdapter.ping(runner.exchangeId());
    } catch (Exception e) {
        return HealthCheckResult.fail("EXCHANGE_UNREACHABLE", e.getMessage());
    }

    // Check 2: Validar que o símbolo existe e está ativo na Exchange
    ExchangeSymbolInfo symbolInfo = exchangeAdapter.getSymbolInfo(
        runner.exchangeId(), runner.symbol()
    );
    if (symbolInfo == null) {
        return HealthCheckResult.fail("SYMBOL_NOT_FOUND", runner.symbol());
    }
    if (!symbolInfo.isTrading()) {
        return HealthCheckResult.fail("SYMBOL_NOT_TRADING", runner.symbol());
    }

    // Check 3: Validar tickSize e stepSize (necessários para arredondamento — Seção 8.3)
    if (symbolInfo.tickSize() == null || symbolInfo.stepSize() == null) {
        return HealthCheckResult.fail("MISSING_ASSET_FORMAT",
            "tickSize or stepSize not available for " + runner.symbol());
    }

    // Check 4: Validar que a Strategy está registrada e disponível
    TradeStrategy strategy = strategyRegistry.find(runner.strategyId());
    if (strategy == null) {
        return HealthCheckResult.fail("STRATEGY_NOT_FOUND", runner.strategyId());
    }

    // Check 5: Validar que o Portfolio está operacional
    Portfolio portfolio = portfolioRepository.findById(runner.portfolioId());
    if (portfolio.safeModeStatus() != SafeModeStatus.NORMAL) {
        return HealthCheckResult.fail("PORTFOLIO_IN_SAFE_MODE",
            portfolio.safeModeStatus().name());
    }

    return HealthCheckResult.pass(symbolInfo);
}
```

#### 13.3.2 Ações por Resultado

```java
public void startRunner(UUID runnerId) {
    StrategyRunner runner = runnerRepository.findById(runnerId);

    if (runner.status() != CREATED && runner.status() != HALTED) {
        throw new InvalidStateTransitionException(runner.status(), INITIALIZING);
    }

    HealthCheckResult result = performHealthCheck(runner);

    if (result.passed()) {
        // Cachear AssetFormat para uso em arredondamento (Seção 8.3)
        assetFormatCache.put(runner.symbol(), result.symbolInfo().toAssetFormat());

        if (runner.status() == CREATED) {
            runner.setStatus(INITIALIZING);
            // Iniciar Boot Sequence (Seção 10.2 Phase 3)
            runnerExecutorService.startRunner(runnerId, createProcessor(runner));
        } else {  // HALTED → ACTIVE
            runner.setStatus(ACTIVE);
            runner.setHaltReason(null);
        }
        runnerRepository.save(runner);
    } else {
        runner.setStatus(HALTED);
        runner.setHaltReason("HEALTH_CHECK_FAILED: " + result.reason());
        runnerRepository.save(runner);
        log.error("Health check failed for runner {}: {}", runnerId, result.reason());
    }
}
```

### 13.4 Graceful Shutdown (Encerramento com Posições Abertas)

> **Estado (T49, 2026-10-16):** Implementado sem liquidação automática nem "detach" (decisão do mantenedor): o runner para de comprar, a estratégia vende e o `RunnerArchiveScheduler` arquiva quando não há posição nem ordem em voo; o operador pode fechar a posição a mercado pelo comando.

Quando o operador solicita o encerramento de um Runner ativo:

#### 13.4.1 Protocolo de Encerramento

```
terminate(runnerId):
       │
Step 1: Stop New Signals
       │ runner.status = TERMINATING
       │ // Drop Policy rejeita novos sinais de abertura (BUY)
       │ // Sinais de fechamento (SELL) ainda são aceitos
       │
Step 2: Aguardar ordens em voo
       │ while (hasInflightOrders(runnerId)):
       │     wait(checkIntervalMs)
       │     if (timeout exceeded):
       │         // Forçar cancelamento das ordens em voo
       │         forceCancel(runnerId)
       │
Step 3: Liquidar ou transferir posições
       │
       ├─ Opção A (Close All — default):
       │     openPositions = positionRepository.findByRunnerIdAndStatus(runnerId, OPEN)
       │     for each position:
       │         // Gerar Market Order de fechamento
       │         closeOrder = Transaction.createMarketClose(...)
       │         transactionRepository.save(closeOrder)
       │         exchangeAdapter.submitOrder(closeOrder.toExchangeOrder())
       │     // Aguardar execução de todas as ordens de fechamento
       │
       ├─ Opção B (Detach — emergência):
       │     openPositions = positionRepository.findByRunnerIdAndStatus(runnerId, OPEN)
       │     for each position:
       │         sendToDLQ(position, reason = "RUNNER_DETACH")
       │         position.status = CLOSED  // Libera para o Runner ser arquivado
       │     // Posições ficam na DLQ para resolução manual
       │
Step 4: Verificar pré-condições de arquivamento
       │ reservedMargin = calculateReservedMarginForRunner(runnerId)
       │ openPositions = positionRepository.countByRunnerIdAndStatus(runnerId, OPEN)
       │ inflightOrders = transactionRepository.countByRunnerIdAndStatusIn(
       │     runnerId, [PENDING, SUBMITTED, PARTIAL])
       │
       │ if (reservedMargin == 0 && openPositions == 0 && inflightOrders == 0):
       │     runner.status = ARCHIVED
       │     runner.archivedAt = Instant.now()
       │     runnerRepository.save(runner)
       │     runnerExecutorService.stopRunner(runnerId)
       │     log.info("Runner archived: id={}", runnerId)
       │ else:
       │     log.warn("Runner cannot be archived yet: margin={}, positions={}, orders={}",
       │              reservedMargin, openPositions, inflightOrders)
       │     // Permanece em TERMINATING — worker periódico verificará novamente
```

#### 13.4.2 Worker de Finalização

Para Runners que permanecem em `TERMINATING` aguardando a execução das ordens de fechamento:

```java
@Scheduled(fixedRate = terminationCheckIntervalMs)
public void checkTerminatingRunners() {
    List<StrategyRunner> terminating = runnerRepository.findByStatus(TERMINATING);

    for (StrategyRunner runner : terminating) {
        long reservedMargin = calculateReservedMarginForRunner(runner.id());
        int openPositions = positionRepository.countByRunnerIdAndStatus(runner.id(), OPEN);
        int inflightOrders = transactionRepository.countByRunnerIdAndStatusIn(
            runner.id(), List.of(PENDING, SUBMITTED, PARTIAL)
        );

        if (reservedMargin == 0 && openPositions == 0 && inflightOrders == 0) {
            runner.setStatus(ARCHIVED);
            runner.setArchivedAt(Instant.now());
            runnerRepository.save(runner);
            runnerExecutorService.stopRunner(runner.id());
            log.info("Runner auto-archived after termination: id={}", runner.id());
        } else {
            // Verificar timeout de terminação
            Duration termDuration = Duration.between(runner.terminatingStartedAt(), Instant.now());
            if (termDuration.toMillis() > terminationTimeoutMs) {
                log.error("Runner stuck in TERMINATING: id={}, duration={}ms, margin={}, positions={}, orders={}",
                          runner.id(), termDuration.toMillis(), reservedMargin, openPositions, inflightOrders);
                // Não forçar ARCHIVED — margem/posições precisam ser resolvidas
                // Alerta para o operador
            }
        }
    }
}
```

### 13.5 Soft Delete e Preservação de Histórico

> **Nota #27 (já absorvida na Seção 3.3.1):** "Nunca deletar um registro de StrategyRunner. Usar campo `archived_at`."

**Regras de preservação:**

| Entidade            | Ação no Arquivamento                      | Motivo                                |
|---------------------|-------------------------------------------|---------------------------------------|
| `StrategyRunner`    | `status = ARCHIVED`, `archivedAt = now()` | Histórico de configuração — auditoria |
| `Position` (CLOSED) | Mantida integralmente                     | PnL histórico — relatórios fiscais    |
| `Transaction`       | Mantida integralmente                     | Trilha de auditoria completa          |
| `TransactionMatch`  | Mantido integralmente                     | Detalhes de execução e fees           |

**Política de retenção:** Dados de Runners ARCHIVED são preservados indefinidamente. Caso necessário (compliance com LGPD ou limitações de storage), uma política de data archival para cold storage pode ser implementada em versão futura, mas **nunca DELETE**.

**Query pattern para exclusão de ARCHIVED:**

```sql
-- Runners operacionais (exclui ARCHIVED)
SELECT * FROM strategy_runners WHERE status != 'ARCHIVED'

-- O partial unique index já exclui ARCHIVED:
-- idx_runners_active_unique WHERE status NOT IN ('ARCHIVED', 'TERMINATING')
```

### 13.6 API Administrativa

> **Estado (T39, 2026-10-16):** Endpoints reais (T49): criar `POST /api/portfolios/{id}/runners`; `POST /api/admin/runners/{id}/start|resume|terminate|halt`, `POST /api/admin/runners/halt`, `PATCH /api/admin/runners/{id}`, `GET /api/admin/runners/status`. `GET /api/admin/runners/{id}` e filtros de listagem não existem. `portfolio.max-runners` descartado na v1.

#### 13.6.1 Endpoints

| Método  | Path                                | Ação                       | Transição                                         |
|---------|-------------------------------------|----------------------------|---------------------------------------------------|
| `POST`  | `/api/admin/runners`                | Criar Runner               | → `CREATED`                                       |
| `POST`  | `/api/admin/runners/{id}/start`     | Iniciar Runner             | `CREATED` → `INITIALIZING` ou `HALTED` → `ACTIVE` |
| `POST`  | `/api/admin/runners/{id}/halt`      | Suspender Runner           | `ACTIVE` → `HALTED`                               |
| `POST`  | `/api/admin/runners/{id}/resume`    | Retomar Runner             | `HALTED` → `ACTIVE`                               |
| `POST`  | `/api/admin/runners/{id}/terminate` | Encerrar Runner            | `ACTIVE/HALTED` → `TERMINATING`                   |
| `GET`   | `/api/admin/runners/{id}`           | Status do Runner           | —                                                 |
| `GET`   | `/api/admin/runners`                | Listar Runners             | — (filtros: portfolioId, status)                  |
| `PATCH` | `/api/admin/runners/{id}`           | Atualizar configs mutáveis | Apenas campos mutáveis (Seção 13.2.2)             |

#### 13.6.2 Validação de Transição

```java
@RestController
@RequestMapping("/api/admin/runners")
public class RunnerAdminController {

    @PostMapping("/{id}/start")
    public ResponseEntity<RunnerDto> start(@PathVariable UUID id) {
        StrategyRunner runner = runnerRepository.findById(id)
            .orElseThrow(() -> new RunnerNotFoundException(id));

        // Validar transição
        if (runner.status() != CREATED && runner.status() != HALTED) {
            throw new InvalidStateTransitionException(
                runner.status(), INITIALIZING,
                "Runner must be in CREATED or HALTED to start"
            );
        }

        runnerLifecycleService.startRunner(id);
        return ResponseEntity.ok(RunnerDto.from(runner));
    }

    @PostMapping("/{id}/terminate")
    public ResponseEntity<RunnerDto> terminate(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "CLOSE_ALL") TerminationMode mode) {

        StrategyRunner runner = runnerRepository.findById(id)
            .orElseThrow(() -> new RunnerNotFoundException(id));

        if (runner.status() != ACTIVE && runner.status() != HALTED) {
            throw new InvalidStateTransitionException(
                runner.status(), TERMINATING,
                "Runner must be in ACTIVE or HALTED to terminate"
            );
        }

        runnerLifecycleService.terminate(id, mode);  // CLOSE_ALL ou DETACH
        return ResponseEntity.ok(RunnerDto.from(runner));
    }
}
```

### 13.7 Observabilidade

> **Estado (T51, 2026-10-19):** existem `runner.lifecycle.transitions.total{from,to}` (criação tem `from=NONE`), `runner.lifecycle.current_state{status}` (contagem por status), `runner.lifecycle.terminating.duration` e `runner.lifecycle.termination.stuck` (timeout `ctrade.observability.termination-timeout-ms`, 10 min). `created.total`/`archived.total` saem de `transitions.total`; health check do lifecycle não existe.

| Métrica                                  | Tipo    | Descrição                                          |
|------------------------------------------|---------|----------------------------------------------------|
| `runner.lifecycle.transitions.total`     | Counter | Total de transições de estado (por `from` → `to`)  |
| `runner.lifecycle.current_state`         | Gauge   | Estado atual de cada Runner (para dashboard)       |
| `runner.lifecycle.created.total`         | Counter | Total de Runners criados                           |
| `runner.lifecycle.archived.total`        | Counter | Total de Runners arquivados                        |
| `runner.lifecycle.terminating.duration`  | Timer   | Tempo gasto em TERMINATING antes de ARCHIVED       |
| `runner.lifecycle.health_check.failures` | Counter | Health Checks falhados (por motivo)                |
| `runner.lifecycle.termination.stuck`     | Gauge   | Runners em TERMINATING há mais tempo que o timeout |

### 13.8 Configuração

| Parâmetro                                        | Default  | Descrição                                                   |
|--------------------------------------------------|----------|-------------------------------------------------------------|
| `runner.lifecycle.health-check-timeout-ms`       | `10000`  | Timeout para cada step do Health Check                      |
| `runner.lifecycle.termination-timeout-ms`        | `600000` | Timeout total para fase TERMINATING (10 min)                |
| `runner.lifecycle.termination-check-interval-ms` | `10000`  | Frequência de verificação de Runners em TERMINATING         |
| `runner.lifecycle.inflight-wait-timeout-ms`      | `120000` | Tempo máximo para aguardar ordens em voo durante terminação |
| `portfolio.max-runners`                          | `10`     | Número máximo de Runners por Portfolio                      |

### 13.9 Implicações Arquiteturais

| Decisão                                     | Justificativa                                                          | Trade-off                                                  |
|---------------------------------------------|------------------------------------------------------------------------|------------------------------------------------------------|
| Factory valida antes de criar               | Previne Runners duplicados e excesso de alocação                       | Latência de criação (~50ms para queries de validação)      |
| Políticas imutáveis após criação            | Evita corrupção de estado por mudança de regras mid-flight             | Menos flexibilidade — requer novo Runner para mudar policy |
| Health Check antes de INITIALIZING e ACTIVE | Previne Runner que opera com Exchange inacessível ou símbolo inválido  | Latência de ativação (~100-500ms para ping + symbolInfo)   |
| TERMINATING → ARCHIVED automático           | Sem intervenção manual quando todas as pré-condições são atendidas     | Worker periódico consome recursos (negligível)             |
| Detach como opção de emergência             | Permite arquivar Runner sem liquidar posições — DLQ cuida da resolução | Posições ficam "orphaned" até resolução manual             |
| Soft Delete com `archivedAt`                | Preserva histórico completo para auditoria e compliance fiscal         | Storage crescente (mitigado: cold storage futuro)          |
| ARCHIVED irreversível                       | Previne reativação acidental de Runner com estado stale                | Para reativar a mesma estratégia, deve criar novo Runner   |
| API admin com validação de transição        | Previne transições inválidas (ex: ARCHIVED → ACTIVE)                   | Requer conhecimento do diagrama de estados para operar     |

---

## 14. Context Injection e Contrato da Strategy

Esta seção detalha **como implementar** o contrato entre o StrategyRunner e a TradeStrategy — o que o Runner injeta como contexto, o que a Strategy retorna como decisão, e as regras de acoplamento e isolamento que garantem que a Strategy permaneça stateless.

> **Referência:** Blueprint Seção 2.C (TradeStrategy — Motor de Sinais), Seção 4.A (Geração do Sinal), Seção 9.3.D (Exposição via Context Injection).
> **Questão aberta respondida:** "O que exatamente compõe o contexto injetado no motor de sinais?" (IMPLEMENTATION_GUIDE_QUESTOES.md — Context Injection para Strategy).
> **Dependência:** Seção 8 (precisão decimal — contexto deve respeitar arredondamento), Seção 12.3 (Stale Signal Check consome o output da Strategy).

### 14.1 Visão Geral do Fluxo

```
Market Data Event (WebSocket)
         │
         ▼
┌──────────────────┐
│ StrategyInputDto │  Dados de mercado (preços, volume, timestamp)
└────────┬─────────┘
         │
         ▼
┌────────────────────────────────────────────────────┐
│ TradingStrategy.executeStrategy(input, context)    │
│                                                    │
│   input:   StrategyInputDto  (mercado)             │
│   context: StrategyContextDto (estado do Runner)   │
│                                                    │
│   return:  StrategyOutputDto (decisão)             │
└────────────────────────────────────────────────────┘
         │
         ▼
┌───────────────────┐
│ StrategyOutputDto │  Decisão + quantidade + confidence + reasoning
└────────┬──────────┘
         │
         ▼
┌───────────────────┐
│  Stale Check      │  Seção 12.3 — descarta se timestamp > threshold
└────────┬──────────┘
         │
         ▼
┌───────────────────┐
│ Materialização    │  Seção 6 — Transaction PENDING → Capital Request → Dispatch
└───────────────────┘
```

### 14.2 Interface TradingStrategy (Contrato Principal)

A interface `TradingStrategy` (código existente em `core/ports/outbound/strategy/TradingStrategy.java`) define o contrato:

```java
public interface TradingStrategy {
    UUID getStrategyId();
    String getStrategyName();
    String getStrategyVersion();
    boolean isEnabled();

    /**
     * Executa a estrategia com dados de mercado e contexto operacional do Runner.
     *
     * @param inputData dados de mercado (precos, volume, timestamp)
     * @param strategyContext contexto operacional consolidado do Runner
     * @return decisao da estrategia com quantity absoluta
     */
    StrategyOutputDto executeStrategy(StrategyInputDto inputData,
                                      StrategyContextDto strategyContext);
}
```
**Assinatura ativa:** o contrato atual usa `StrategyContextDto` como contexto operacional da Strategy:

```java
StrategyOutputDto executeStrategy(StrategyInputDto inputData,
                                  StrategyContextDto context);
```

### 14.3 Contrato de Entrada: StrategyInputDto (Dados de Mercado)

O `StrategyInputDto` contém **exclusivamente dados de mercado** — sem estado do Runner ou Portfolio.

#### 14.3.1 Estado Atual (Código Existente)

```java
public record StrategyInputDto(
    Symbol symbol,                     // Par de trading (ex: BTCUSDT)
    BigDecimal currentPrice,           // Preço atual de mercado
    BigDecimal previousPrice,          // Preço anterior (detecção de tendência)
    BigDecimal bidPrice,               // Melhor oferta de compra
    BigDecimal askPrice,               // Melhor oferta de venda
    BigDecimal volume,                 // Volume de trading
    BigDecimal high24h,                // Máxima 24h
    BigDecimal low24h,                 // Mínima 24h
    Instant timestamp,                 // Timestamp dos dados
    Map<String, Object> additionalData // Dados customizáveis por strategy
)
```

**Validações embutidas:**
- `isValid()` — Valida preços, símbolo, timestamp e volume
- `isPriceWithinSpread()` — Verifica se preço está entre bid/ask
- `isPriceWithinDailyRange()` — Verifica se preço está dentro do range 24h
- `isValidWithPriceConsistency()` — Validação completa com consistência de preço

#### 14.3.2 Evolução Necessária

| Campo Atual      | Ação                          | Justificativa                                                     |
|------------------|-------------------------------|-------------------------------------------------------------------|
| Todos os campos  | **MANTER**                    | Contrato de mercado está correto                                  |
| `additionalData` | **MANTER**                    | Extensão genérica para indicadores custom (RSI, MACD, etc.)       |
| —                | **Avaliar:** `orderBookDepth` | Profundidade do livro de ofertas (V2+, para strategies avançadas) |

**Regra:** O `StrategyInputDto` **não deve conter** informações de posição, saldo ou estado do Runner. Essa separação garante que a Strategy possa ser testada isoladamente com dados de mercado puros.

### 14.4 Contrato de Contexto: StrategyContextDto (Ativo)

O contexto do Runner e injetado como segundo parametro da Strategy por meio de `StrategyContextDto`. Esse contrato ja esta ativo no core e nas estrategias implementadas.

#### 14.4.1 Contrato Ativo (Implementado)

O contrato legado `PortfolioContextDto` foi removido do fluxo principal. O contrato ativo da Strategy e `StrategyContextDto`.

#### 14.4.2 Estrutura do StrategyContextDto

Estrutura do contrato ativo, refletindo a separacao de agregados Portfolio e Runner:

```java
public record StrategyContextDto(
    // --- Identificacao ---
    UUID runnerId,
    Symbol symbol,

    // --- Posicao Atual (PositionContext - read-only) ---
    PositionContext positionContext,

    // --- Lotes Abertos ---
    List<OpenLotDto> openLots,

    // --- Ordens em Transito ---
    List<PendingOrderDto> pendingOrders,

    // --- Capital Disponivel ---
    BigDecimal availableCapital,
    BigDecimal maxOperationAmount,

    // --- Limites Operacionais ---
    BigDecimal minOperationAmount,
    int maxOpenPositions,
    int currentOpenPositions,

    // --- Performance ---
    BigDecimal realizedPnl,
    BigDecimal unrealizedPnl
)
```

#### 14.4.3 PositionContext (Value Object)

Expõe o estado da posição de forma read-only e com precisão formatada (Seção 8.3):

```java
public record PositionContext(
    boolean hasPosition,                    // Se existe posição aberta
    BigDecimal quantity,                    // Quantidade total detida
    BigDecimal averagePrice,               // WAP — preço médio ponderado (Seção 8.4)
    BigDecimal currentPrice,               // Último preço de mercado
    BigDecimal unrealizedPnl,              // (currentPrice - averagePrice) * quantity
    BigDecimal unrealizedPnlPercent,       // unrealizedPnl / (averagePrice * quantity) * 100
    Instant openedAt                       // Timestamp da abertura da posição
) {
    public static PositionContext empty() {
        return new PositionContext(false, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
    }

    public static PositionContext from(Position position, BigDecimal currentPrice) {
        BigDecimal unrealized = currentPrice.subtract(position.averagePrice())
            .multiply(position.quantity());
        BigDecimal cost = position.averagePrice().multiply(position.quantity());
        BigDecimal percent = cost.compareTo(BigDecimal.ZERO) > 0
            ? unrealized.divide(cost, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
            : BigDecimal.ZERO;
        return new PositionContext(true, position.quantity(), position.averagePrice(),
            currentPrice, unrealized, percent, position.openedAt());
    }
}
```

#### 14.4.4 OpenLotDto (Lote de Compra)

Substitui `OpenBuyEntryDto` com nomenclatura alinhada ao modelo alvo:

```java
public record OpenLotDto(
    UUID lotId,                             // ID do lote (Position.id)
    BigDecimal quantity,                    // Quantidade total do lote
    BigDecimal availableQuantity,           // Quantidade livre (não locked)
    BigDecimal entryPrice,                  // Preço de entrada do lote
    BigDecimal currentPnlPercent,           // PnL % atual do lote
    Instant openedAt                        // Data de abertura
)
```

**Regra:** A Strategy recebe **apenas `availableQuantity`** (quantidade livre para venda), não a quantidade total. Lotes com lock ativo (Seção 9) aparecem com `availableQuantity` reduzida.

#### 14.4.5 PendingOrderDto

Substitui `PendingSellEntryDto` para cobrir tanto compras quanto vendas em trânsito:

```java
public record PendingOrderDto(
    UUID transactionId,
    TradingAction type,                     // SHOULD_BUY ou SHOULD_SELL
    BigDecimal quantity,
    BigDecimal price,
    TransactionStatus status,               // PENDING, SUBMITTED ou PARTIAL
    Instant createdAt
)
```

### 14.5 Contrato de Saída: StrategyOutputDto

O `StrategyOutputDto` é o "contrato de intenção" retornado pela Strategy.

#### 14.5.1 Estado Atual (Código Existente — sem alteração necessária)

```java
public record StrategyOutputDto(
    String strategyName,
    TradingAction decision,                 // SHOULD_BUY, SHOULD_SELL, SHOULD_HOLD
    BigDecimal confidence,                  // 0.0 a 1.0
    BigDecimal quantity,                    // Quantidade absoluta (ex: 0.05 BTC)
    UUID targetLotId,                       // null = FIFO, UUID = lote específico
    String reasoning,                       // Motivação da decisão
    Instant timestamp,                      // Timestamp da decisão
    Map<String, Object> metadata            // Dados adicionais
)
```

**Factory methods existentes** (corretos para o modelo atual):
- `hold(strategyName, reasoning)` → SHOULD_HOLD
- `buy(strategyName, confidence, quantity, reasoning)` → SHOULD_BUY
- `sell(strategyName, confidence, quantity, reasoning)` → SHOULD_SELL
- `sellLot(strategyName, confidence, quantity, targetLotId, reasoning)` → SHOULD_SELL com lote específico

#### 14.5.2 Campos para Avaliação Futura (V2+)

> **Estado (T39, 2026-10-16):** Desatualizado: LIMIT é o tipo padrão (T34 `limitPrice`) e MARKET existe só na liquidação do PANIC_SELL (T45). Proteção LIMIT configurável: **T48**.

| Campo            | Propósito                 | Quando Implementar                             |
|------------------|---------------------------|------------------------------------------------|
| `orderType`      | MARKET vs LIMIT           | Quando suporte a LIMIT orders for implementado |
| `suggestedPrice` | Preço sugerido para LIMIT | Junto com `orderType`                          |
| `timeInForce`    | GTC, IOC, FOK             | Junto com LIMIT orders                         |

### 14.6 Montagem do Contexto pelo Runner

O Runner é responsável por montar o `StrategyContextDto` antes de cada invocação da Strategy:

```java
@Component
public class RunnerContextAssembler {

    public StrategyContextDto assemble(StrategyRunner runner) {

        // 1. Posição atual
        Position position = positionRepository
            .findByRunnerIdAndStatus(runner.id(), PositionStatus.OPEN)
            .orElse(null);

        PositionContext posCtx = position != null
            ? PositionContext.from(position, getCurrentPrice(runner.symbol()))
            : PositionContext.empty();

        // 2. Lotes abertos (para strategies que usam targetLotId)
        List<OpenLotDto> openLots = positionRepository
            .findAllByRunnerIdAndStatus(runner.id(), PositionStatus.OPEN)
            .stream()
            .map(lot -> new OpenLotDto(
                lot.id(),
                lot.quantity(),
                lot.availableQuantity(),   // quantity - lockedQuantity
                lot.averagePrice(),
                calculatePnlPercent(lot, getCurrentPrice(runner.symbol())),
                lot.openedAt()
            ))
            .toList();

        // 3. Ordens em trânsito
        List<PendingOrderDto> pendingOrders = transactionRepository
            .findByRunnerIdAndStatusIn(runner.id(),
                List.of(TransactionStatus.PENDING, TransactionStatus.SUBMITTED,
                         TransactionStatus.PARTIAL))
            .stream()
            .map(tx -> new PendingOrderDto(
                tx.id(), tx.type(), tx.quantity(), tx.price(),
                tx.status(), tx.createdAt()
            ))
            .toList();

        // 4. Capital disponível (consulta ao Portfolio via ReserveCapitalPort)
        BigDecimal availableCapital = capitalManager
            .getAvailableForRunner(runner.id());
        BigDecimal maxOperation = calculateMaxOperation(runner, availableCapital);

        // 5. Performance
        BigDecimal realizedPnl = positionRepository
            .sumRealizedPnlByRunnerId(runner.id());
        BigDecimal unrealizedPnl = posCtx.unrealizedPnl();

        return new StrategyContextDto(
            runner.id(),
            Symbol.of(runner.symbol()),
            posCtx,
            openLots,
            pendingOrders,
            availableCapital,
            maxOperation,
            runner.minOperationAmount(),
            runner.maxOpenPositions(),
            openLots.size(),
            realizedPnl,
            unrealizedPnl
        );
    }
}
```

### 14.7 Regras de Isolamento da Strategy

> **Estado (T40, 2026-10-19):** A estratégia **vê** `totalCapital` (`available + reserved` do portfolio) para dimensionar a ordem — o SMA aloca um percentual dele. Não é limite: o isolamento entre runners é garantido pelo ctrade na reserva (`maxAllocationPercent` e saldo disponível). A linha "Sem acesso ao GlobalBalance" e o DROP de `totalCapital` no §14.8 estão superados.

A TradeStrategy é **stateless** — não mantém estado entre invocações:

| Regra                           | Descrição                                                         | Motivo                                                              |
|---------------------------------|-------------------------------------------------------------------|---------------------------------------------------------------------|
| **Sem acesso ao DB**            | Strategy não injeta repositórios ou services de persistência      | Evita side effects e acoplamento com infraestrutura                 |
| **Sem acesso à Exchange**       | Strategy não chama APIs da Exchange diretamente                   | Responsabilidade do Runner/Portfolio                                |
| **Read-only context**           | Todos os DTOs são records (imutáveis)                             | Previne que a Strategy altere estado do Runner                      |
| **Sem acesso ao GlobalBalance** | Strategy vê `availableCapital` mas não o saldo total do Portfolio | Isolamento entre Runners — cada Strategy conhece apenas "sua fatia" |
| **Sem acesso a outros Runners** | Contexto é limitado ao Runner atual                               | Previne estratégias correlacionadas (escopo V2+)                    |
| **BigDecimal obrigatório**      | Todos os valores numéricos são BigDecimal, nunca double/float     | Precisão financeira (Seção 8.2)                                     |

### 14.8 Mapa de Migracao (Concluido): PortfolioContextDto -> StrategyContextDto

| Campo Atual (PortfolioContextDto)  | Campo Alvo (StrategyContextDto)  | Ação                                                      |
|------------------------------------|----------------------------------|-----------------------------------------------------------|
| `portfolioId`                      | `runnerId`                       | **CHANGE** — Contexto é do Runner, não do Portfolio       |
| `portfolioName`                    | —                                | **DROP** — Não relevante para a Strategy                  |
| `symbol`                           | `symbol`                         | **KEEP**                                                  |
| `totalCapital`                     | —                                | **DROP** — Strategy não deve ver capital total            |
| `availableBalance`                 | `availableCapital`               | **RENAME** — Simplificar para BigDecimal (moeda inferida) |
| `allocatedBalance`                 | —                                | **DROP** — Representado por `positionContext`             |
| `position`                         | `positionContext`                | **CHANGE** — VO read-only com PnL calculado               |
| `openTransactions`                 | `openLots`                       | **RENAME** + reestruturar para `OpenLotDto`               |
| `pendingSellOrders`                | `pendingOrders`                  | **RENAME** — Inclui BUY e SELL em trânsito                |
| `realizedPnL`                      | `realizedPnl`                    | **KEEP**                                                  |
| `minimumOperationAmount`           | `minOperationAmount`             | **RENAME**                                                |
| `maxExposurePerSymbol`             | `maxOperationAmount`             | **CHANGE** — Valor absoluto, não percentual               |
| —                                  | `maxOpenPositions`               | **NEW**                                                   |
| —                                  | `currentOpenPositions`           | **NEW**                                                   |
| —                                  | `unrealizedPnl`                  | **NEW**                                                   |

### 14.9 Cooldown (Frequência de Sinais)

> **Estado (T39, 2026-10-16):** Só o cooldown implícito existe; `runner.cooldown.explicit-ms` e `runner.context.*` (§14.10) descartados na v1.

> **Questão aberta respondida:** "Como funciona exatamente o cooldown entre operações?" (IMPLEMENTATION_GUIDE_QUESTOES.md — Cooldown Implementation).

O cooldown é gerido pela **Execution Policy** do Runner (não pela Strategy):

| Aspecto           | Decisão                                                             | Justificativa                                                                                                                                |
|-------------------|---------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| **Granularidade** | Por Runner (global para o Runner)                                   | Cada Runner opera um único símbolo — cooldown por símbolo é redundante                                                                       |
| **Direção**       | Único cooldown independente de BUY/SELL                             | Simplicidade. Cooldowns distintos por direção adicionam complexidade sem benefício claro para V1                                             |
| **Reset**         | Resetado após cada Transaction chegar a estado terminal             | Uma nova operação só pode iniciar após a anterior completar                                                                                  |
| **Implementação** | Implícito via Execution Policy (Single mode) + Mailbox (capacity=1) | Em Single mode, o Runner já bloqueia novos sinais enquanto há posição/ordem aberta. O cooldown é um efeito emergente, não um timer explícito |

**Para V2+ (se necessário timer explícito):**

```java
// Execution Policy com cooldown explícito
if (lastTerminalStateAt != null) {
    Duration elapsed = Duration.between(lastTerminalStateAt, Instant.now());
    if (elapsed.toMillis() < config.cooldownMs()) {
        metrics.counter("runner.signals.rejected", "reason", "cooldown").increment();
        return false;
    }
}
```

### 14.10 Configuração

| Parâmetro                               | Default  | Descrição                                                                                            |
|-----------------------------------------|----------|------------------------------------------------------------------------------------------------------|
| `runner.context.include-open-lots`      | `true`   | Incluir lotes abertos no contexto (pode ser desabilitado para strategies que não usam `targetLotId`) |
| `runner.context.include-pending-orders` | `true`   | Incluir ordens em trânsito no contexto                                                               |
| `runner.context.max-lots-in-context`    | `100`    | Limite de lotes no contexto (previne payloads excessivos)                                            |
| `runner.cooldown.explicit-ms`           | `0`      | Cooldown explícito entre operações (0 = desabilitado, usa implícito via Execution Policy)            |

### 14.11 Implicações Arquiteturais

> **Estado (T40, 2026-10-19):** "Strategy stateless" vale para a decisão; o warm-up é estado admitido, chaveado por `(strategyRef, runnerId)` no contrato out-of-process. O SMA in-process ainda compartilha o histórico entre runners — correção na T41.

| Decisão                                         | Justificativa                                                                                       | Trade-off                                                                                                     |
|-------------------------------------------------|-----------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| Strategy stateless                              | Testabilidade — strategy pode ser testada com dados de mercado puros, sem infraestrutura            | Strategy não pode manter estado entre invocações (acumuladores, médias móveis devem vir via `additionalData`) |
| Separação StrategyInputDto / StrategyContextDto | Input = mercado (puro), Context = estado (Runner). Permite trocar a Strategy sem alterar o contexto | Dois objetos em vez de um — levemente mais verboso                                                            |
| PortfolioContextDto -> StrategyContextDto        | Reflete a separação de agregados (Portfolio vs Runner). Strategy vê o Runner, não o Portfolio       | Implementado no core e strategy                                                                                |
| `availableCapital` como BigDecimal (não Asset)  | Moeda inferida do símbolo. Simplifica o contrato                                                    | Se multi-currency for suportado, precisará voltar a Asset                                                     |
| Strategy não vê GlobalBalance                   | Isolamento — uma Strategy não deve otimizar baseada no capital de outros Runners                    | Limita estratégias que precisam de visão global (escopo V2+)                                                  |
| Cooldown implícito (Single mode + Mailbox)      | Zero configuração adicional — o modelo de concorrência já fornece o comportamento                   | Não oferece cooldown time-based configurável (mitigado: config `cooldown.explicit-ms` para V2+)               |
| OpenLotDto com `availableQuantity`              | Strategy vê apenas o que pode vender, não o que está locked                                         | Se Strategy precisar saber sobre locks para reasoning, informação não disponível                              |
| Records imutáveis para DTOs                     | Garante que Strategy não modifica estado por acidente                                               | Pode requerer cópias defensivas em cenários edge                                                              |

---

## 15. Restrições de Integração com Exchange

> **Notas de Implementação absorvidas:** Questão #3 (Rate Limiting da Exchange), Questão — Sincronia de Relógio (Clock Drift).
>
> **Referências Blueprint:** §10.1.D (Topologia e Governança), §10.4 (Escalabilidade de Conectividade), §12.D.2 (Proteção de Componentes Compartilhados), §12.D.3 (Detecção de Runner Mal Comportado).

### 15.1 Visão Geral

O **ExchangeAdapter** é o único ponto de contato entre o sistema e as APIs das Exchanges. Nenhum Runner, Strategy ou serviço acessa a Exchange diretamente — toda comunicação passa obrigatoriamente por este gateway centralizado.

O ExchangeAdapter tem **duas responsabilidades principais**:

1. **Tradução de Protocolo** — Converte entre formatos específicos da Exchange (JSON Binance, etc.) e DTOs de domínio agnósticos (`MarketDataDto`, `OrderDataDto`, `SendOrderRequest`). Esta é a responsabilidade central que permite trocar de Exchange sem impactar o domínio.
2. **Governança de Conectividade** — Gerencia Key Pooling, Rate Limiting, Quota por Runner e Isolamento de Erros.

**Princípio fundamental (Blueprint §10.1.D):**

> "Os Runners não possuem conexão direta de escrita ou leitura com a API da Exchange; eles utilizam obrigatoriamente o ExchangeAdapter. Esta centralização é o que permite a gestão do Key Pooling e do Rate Limiting, garantindo que o protocolo de idempotência sobreviva a falhas de conectividade ou saturação de quota da API Key."

**Arquitetura WebSocket-first:** O codebase atual utiliza **WebSocket como canal primário** para envio de ordens (`order.place`, `order.cancel`) e recebimento de execution reports + market data. REST API é usado apenas como **fallback** para operações pontuais (reconciliação no boot, `getAccountBalance`, `getServerTime`).

```
┌───────────────────────────────────────────────────────────────┐
│                          Sistema                              │
│                                                               │
│  ┌──────────┐  ┌──────────┐   ┌───────────┐                   │
│  │ Runner A │  │ Runner B │   │  Runner C │                   │
│  └────┬─────┘  └─────┬────┘   └─────┬─────┘                   │
│       │              │              │                         │
│       │   SendOrderRequest / MarketDataDto (DTOs de domínio)  │
│       │              │              │                         │
│       ▼              ▼              ▼                         │
│  ┌─────────────────────────────────────────────────────┐      │
│  │            ExchangeAdapter (Gateway)                │      │
│  │                                                     │      │
│  │  ┌───────────────────────┐  ┌───────────────────┐   │      │
│  │  │ SenderMessageProcessor│  │ ReceivedMessage   │   │      │
│  │  │ Domain → Exchange JSON│  │ Processor         │   │      │
│  │  └───────────────────────┘  │ Exchange JSON →   │   │      │
│  │  ┌───────────────────────┐  │ Domain DTOs       │   │      │
│  │  │ Rate Limiter / Quota  │  └───────────────────┘   │      │
│  │  └───────────────────────┘  ┌───────────────────┐   │      │
│  │  ┌───────────────────────┐  │ Key Pool Manager  │   │      │
│  │  │ Error Isolation       │  └───────────────────┘   │      │
│  │  └───────────────────────┘                          │      │
│  └──────────────┬──────────────────────┬───────────────┘      │
│                 │                      │                      │
└─────────────────┼──────────────────────┼──────────────────────┘
                  │                      │
        ┌─────────┘                      └──────────┐
        ▼ (primário)                                ▼ (fallback)
┌───────────────────┐                    ┌──────────────────┐
│ Exchange WebSocket│                    │ Exchange REST API│
│ (ordens + market  │                    │ (reconciliação,  │
│  data + exec      │                    │  balance, server │
│  reports)         │                    │  time)           │
└───────────────────┘                    └──────────────────┘
```

### 15.2 Tradução de Protocolo (Anti-Corruption Layer)

O ExchangeAdapter atua como **Anti-Corruption Layer** (DDD) — o domínio nunca conhece formatos específicos de Exchange. A tradução é feita por processadores especializados:

#### 15.2.1 Arquitetura de Processadores (Estado Atual do Codebase)

O `ExchangeAdapterPort` expõe quatro componentes:

```java
public interface ExchangeAdapterPort {
    String getExchangeName();
    boolean requiresPostConnection();
    WebSocketPort getWebSocketPort();                          // Conexão raw
    ReceivedMessageProcessorPort getReceivedMessageProcessor();// Exchange → Domain
    SenderMessageProcessorPort getSenderMessageProcessor();    // Domain → Exchange
    ExchangeUrlBuilderPort getUrlBuilder();                    // Constrói URLs WS
}
```

| Componente                     | Direção           | Input                                                           | Output                                                                        |
|--------------------------------|-------------------|-----------------------------------------------------------------|-------------------------------------------------------------------------------|
| `ReceivedMessageProcessorPort` | Exchange → Domain | JSON raw da Exchange (`TickerEvent`, etc.)                      | `ProcessingResult<ProcessorResponse>` (`MarketDataDto`, `OrderDataDto`, etc.) |
| `SenderMessageProcessorPort`   | Domain → Exchange | `MessageRequest` (`SendOrderRequest`, `SendCancelOrderRequest`) | JSON string no formato da Exchange                                            |
| `WebSocketPort`                | Bidirecional      | URL + mensagens                                                 | Conexão WebSocket raw                                                         |
| `ExchangeUrlBuilderPort`       | Configuração      | `StreamSubscriptionRequest`                                     | URL WebSocket com streams                                                     |

#### 15.2.2 DTOs de Domínio (Exchange-Agnostic)

**Incoming (Exchange → Sistema):**

```java
public sealed interface ProcessorResponse
    permits MarketDataDto, OrderDataDto, AccountDataDto, TradeDataDto, ErrorDataDto {}
```

| DTO              | Conteúdo                                       | Origem típica              |
|------------------|------------------------------------------------|----------------------------|
| `MarketDataDto`  | symbol, price, bid/ask, volume, high/low 24h   | WebSocket ticker stream    |
| `OrderDataDto`   | clientOrderId, status, executedQty, price, fee | WebSocket execution report |
| `AccountDataDto` | Saldo da conta                                 | WebSocket account update   |
| `TradeDataDto`   | Confirmação de trade                           | WebSocket trade stream     |
| `ErrorDataDto`   | Erro da Exchange                               | Qualquer canal             |

**Outgoing (Sistema → Exchange):**

| Request                     | Conteúdo                                                     | Destino típico                      |
|-----------------------------|--------------------------------------------------------------|-------------------------------------|
| `SendOrderRequest`          | symbol, quantity, price, orderType, orderSide, clientOrderId | WebSocket `order.place`             |
| `SendCancelOrderRequest`    | orderId, symbol                                              | WebSocket `order.cancel`            |
| `StreamSubscriptionRequest` | currencyPairs, streamAction                                  | WebSocket `SUBSCRIBE`/`UNSUBSCRIBE` |

#### 15.2.3 Processadores por Exchange

Cada Exchange implementa seus próprios processadores dentro de um módulo adapter isolado:

| Módulo            | Processadores                                                                                                                                                                           | Responsabilidade                            |
|-------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------|
| `adapter-binance` | `BinanceReceivedMessageProcessor` (com `TickerProcessor`, etc.), `BinanceSenderMessageProcessor` (com `OrderProcessor`, `CancelOrderProcessor`, `StreamProcessor`), `BinanceUrlBuilder` | Tradução Binance JSON ↔ DTOs de domínio     |
| `adapter-mock`    | `MockReceivedMessageProcessor`, `MockSenderMessageProcessor` (com `MockOrderExecutionSimulator`), `NoOpUrlBuilder`                                                                      | Simulação local para desenvolvimento/testes |

> **Padrão de extensibilidade:** Para adicionar uma nova Exchange (ex: Kraken), basta criar um novo módulo `adapter-kraken` com implementações de `ReceivedMessageProcessorPort`, `SenderMessageProcessorPort` e `ExchangeUrlBuilderPort`. O domínio permanece inalterado.

#### 15.2.4 Fluxo Completo de Envio de Ordem

```
Runner (decisão de trade)
       │
       ▼ SendOrderRequest (DTO de domínio)
┌───────────────────────────────────────────────────────────────────────────────────┐
│ ExchangeAdapter                                                                   │
│                                                                                   │
│ 1. Per-Runner Quota Check                                                         │
│ 2. Rate Limit Check                                                               │
│ 3. Key Selection (Key Pool)                                                       │
│ 4. SenderMessageProcessor.execute(SendOrderRequest) → JSON Binance format         │
│ 5. WebSocketPort.sendMessage()                                                    │
└──────────────┬────────────────────────────────────────────────────────────────────┘
               │ JSON: {"method":"order.place","params":{...}}
               ▼
         Exchange WebSocket
```

**Fluxo Completo de Recebimento:**

```
Exchange WebSocket
       │ JSON: {"e":"executionReport","s":"BTCUSDT",...}
       ▼
┌───────────────────────────────────────────────────────────────────────────────────┐
│ ExchangeAdapter                                                                   │
│                                                                                   │
│ 1. ReceivedMessageProcessor.processMessage(rawJson) → OrderDataDto (domínio)      │
│ 2. EventRouter                                                                    │
│    (clientOrderId → runnerId)                                                     │
└──────────────┬────────────────────────────────────────────────────────────────────┘
               │ OrderDataDto
               ▼
          Runner Mailbox
```

#### 15.2.5 Mock Exchange Simulator (Test Harness)

O Mock Adapter deve simular o **ciclo completo** de uma ordem (e nao apenas o ACK), com modos de falha controlados. O objetivo e validar resiliencia, idempotencia, PnL e reconciliacao **antes** de usar Binance real.

**Escopo ideal (V1):**
- **Fluxo normal**: NEW -> PARTIALLY_FILLED -> FILLED, com latencia configuravel
- **Idempotencia**: duplicatas de NEW/PARTIAL/FILLED
- **Eventos fora de ordem**: FILLED antes de NEW, PARTIAL fora de sequencia
- **Falhas de ordem**: REJECTED, CANCELED, EXPIRED e "sem ACK" (ordem fantasma)
- **Fees**: fee no par, fee cross-currency e conversao falha (convertedAmount=null -> DustAccount)
- **Reconciliação**: eventos que disparam Boot Sequence e DLQ (runner inexistente, clientOrderId invalido)
- **Market Data (opcional, recomendado)**: ticks controlados (spikes, gaps) para exercitar estrategia e PnL nao realizado

**Configuracao sugerida (exemplo):**
- `partialFillRatio`, `cancelRatio`, `rejectRatio`, `duplicateRatio`, `outOfOrderRatio`
- `feeMode`: implementado `NONE` | `RECEIVED_ASSET` (fee no ativo recebido, como a Binance; T46/T47).
  Fee em terceiro ativo e falha de conversao nao estao implementadas no mock.
- `feeRate`, `latencyMinMs`, `latencyMaxMs`

**Pontos de integracao:**
- `MockOrderExecutionSimulator`: gera o roteiro de eventos conforme o scenario
- `MockSenderMessageProcessor`: publica os eventos no mesmo canal do WebSocket real

**Observabilidade:**
- logar o scenario aplicado por ordem (clientOrderId, status, motivo)
- garantir que cada caso produza sinais suficientes para acionar Boot Sequence, DLQ e Watchdog

### 15.3 Key Pooling (Gestão de Múltiplas API Keys)

O ExchangeAdapter gerencia um pool de API Keys para distribuir a carga e contornar limites por chave.

#### 15.3.1 Estrutura do Key Pool

```java
public class ApiKeyEntry {
    private final String apiKey;
    private final String secretKey;
    private final AtomicInteger currentWeight;   // peso consumido no intervalo atual
    private final AtomicBoolean healthy;          // false = chave bloqueada/com erro
    private final Instant windowStart;            // início do intervalo de rate limit
    private final int maxWeight;                  // peso máximo permitido no intervalo
}
```

**Regras de seleção de chave:**

| Critério                 | Descrição                                                                                                                                     |
|--------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| **Healthy first**        | Apenas chaves com `healthy = true` são candidatas                                                                                             |
| **Least weight**         | Entre as saudáveis, seleciona a chave com menor `currentWeight`                                                                               |
| **Affinity**             | Se um Runner já usou uma chave para uma ordem aberta, prefere a mesma chave para operações subsequentes nessa ordem (simplifica rastreamento) |
| **Round-robin fallback** | Se todas as chaves estiverem com peso similar, round-robin                                                                                    |

#### 15.3.2 Ciclo de Vida da Chave

```
                   ┌──────────┐
         ┌─────────│ HEALTHY  │◄──────── Startup
         │         └────┬─────┘
         │              │ Erro de auth / ban
         │              ▼
         │         ┌──────────┐
         │         │ DEGRADED │──── N erros consecutivos
         │         └────┬─────┘
         │              │ Timeout / HTTP 418 / IP ban
         │              ▼
         │         ┌──────────┐
         │         │ DISABLED │
         │         └────┬─────┘
         │              │ Health check periódico OK
         └──────────────┘
```

**Health check de chave:**

```java
// Periodicamente (a cada 60s) para chaves DEGRADED/DISABLED
CompletableFuture<Void> checkKeyHealth(ApiKeyEntry key) {
    return exchange.getServerTime(key)
        .thenAccept(serverTime -> {
            key.healthy().set(true);
            metrics.counter("exchange.key.recovered", "key", key.id()).increment();
            log.info("API key {} recovered", key.id());
        })
        .exceptionally(ex -> {
            // Mantém estado atual
            log.debug("Key {} still unhealthy: {}", key.id(), ex.getMessage());
            return null;
        });
}
```

#### 15.3.3 Configuração do Key Pool

| Parâmetro                                         | Default  | Descrição                                             |
|---------------------------------------------------|----------|-------------------------------------------------------|
| `exchange.keys[N].api-key`                        | —        | API key N (obrigatório)                               |
| `exchange.keys[N].secret-key`                     | —        | Secret key N (obrigatório, encriptado)                |
| `exchange.keys[N].max-weight`                     | `1200`   | Peso máximo por intervalo (Binance default: 1200/min) |
| `exchange.key-pool.health-check-interval-s`       | `60`     | Intervalo de health check para chaves degradadas      |
| `exchange.key-pool.consecutive-errors-to-degrade` | `3`      | Erros consecutivos para marcar como DEGRADED          |
| `exchange.key-pool.consecutive-errors-to-disable` | `10`     | Erros consecutivos para marcar como DISABLED          |

### 15.4 Rate Limiting e Weight Control

> **Questão aberta respondida:** "Como o sistema gerencia os limites de requisição (rate limits) impostos pela exchange quando múltiplos StrategyRunners compartilham a mesma API key?" (IMPLEMENTATION_GUIDE_QUESTOES.md — Questão #3).

#### 15.4.1 Modelo de Peso (Weight-Based)

Exchanges como Binance usam um modelo de **peso por requisição** em vez de um simples "N requests/minuto". Cada endpoint REST tem um peso específico:

> **Nota importante:** O acompanhamento de ordens (execution reports — FILL, PARTIAL_FILL, CANCELED) é feito via **WebSocket** (`userDataStream`), que é uma conexão persistente e **não consome peso REST**. O rate limiting desta seção aplica-se exclusivamente a chamadas REST API. As operações REST de execução (`POST /order`, `DELETE /order`) são usadas apenas para **submissão e cancelamento** de ordens. O `GET /order` (status) é usado apenas como fallback em cenários de reconciliação (Seção 10) ou quando o WebSocket não confirma a execução dentro do timeout esperado.

| Operação                 | Peso Típico (Binance) | Categoria                      |
|--------------------------|-----------------------|--------------------------------|
| `POST /order`            | 1                     | Execução (prioridade alta)     |
| `DELETE /order`          | 1                     | Execução (prioridade alta)     |
| `GET /order` (status)    | 2                     | Execução (prioridade alta)     |
| `GET /ticker/price`      | 1                     | Market Data (prioridade baixa) |
| `GET /depth` (orderbook) | 5-50                  | Market Data (prioridade baixa) |
| `GET /klines`            | 5                     | Market Data (prioridade baixa) |
| `GET /account`           | 10                    | Conta (prioridade média)       |

#### 15.4.2 Priorização por Categoria

O Blueprint define que **requisições de execução têm prioridade absoluta** sobre market data:

```java
public enum RequestPriority {
    EXECUTION(1),    // Ordens, cancelamentos — nunca throttled
    ACCOUNT(2),      // Saldo, posições — throttled acima de 90%
    MARKET_DATA(3);  // Preços, orderbook — throttled acima de 80%

    private final int level;
}
```

**Algoritmo de admissão:**

```java
boolean shouldAdmit(ExchangeRequest request, ApiKeyEntry key) {
    double utilizationRatio = (double) key.currentWeight() / key.maxWeight();

    return switch (request.priority()) {
        case EXECUTION   -> true;  // Sempre admitido
        case ACCOUNT     -> utilizationRatio < 0.90;
        case MARKET_DATA -> utilizationRatio < 0.80;
    };
}
```

> **Decisão de implementação:** Requisições EXECUTION são **sempre admitidas** mesmo se o peso estiver próximo do limite. O risco de rejeição pela Exchange (HTTP 429) é preferível a não executar uma ordem que já possui capital reservado.

#### 15.4.3 Sliding Window para Peso

```java
public class WeightTracker {
    private final int maxWeight;
    private final Duration windowDuration;  // tipicamente 1 minuto
    private final Deque<WeightEntry> entries = new ConcurrentLinkedDeque<>();

    public int currentWeight() {
        Instant cutoff = Instant.now().minus(windowDuration);
        entries.removeIf(e -> e.timestamp().isBefore(cutoff));
        return entries.stream().mapToInt(WeightEntry::weight).sum();
    }

    public void record(int weight) {
        entries.add(new WeightEntry(Instant.now(), weight));
    }

    record WeightEntry(Instant timestamp, int weight) {}
}
```

#### 15.4.4 Handling de HTTP 429 (Rate Limit Exceeded)

Quando a Exchange retorna HTTP 429, o sistema:

```java
void handleRateLimitExceeded(ApiKeyEntry key, HttpResponse response) {
    // 1. Extrair Retry-After header
    int retryAfterSeconds = parseRetryAfter(response);

    // 2. Marcar chave como temporariamente indisponível
    key.pauseUntil(Instant.now().plusSeconds(retryAfterSeconds));
    metrics.counter("exchange.rate_limit.hit", "key", key.id()).increment();

    // 3. Failover para próxima chave saudável no pool
    ApiKeyEntry fallbackKey = keyPool.selectHealthyKey(key);
    if (fallbackKey != null) {
        log.warn("Key {} rate limited. Failover to key {}", key.id(), fallbackKey.id());
        // Re-submeter request com nova chave
    } else {
        log.error("ALL keys rate limited. Request queued for retry after {}s", retryAfterSeconds);
        // Enfileirar para retry — requests EXECUTION têm prioridade no retry
    }
}
```

### 15.5 Quota por Runner

> **Referência Blueprint §12.D.2:** "O adaptador deve limitar o número de requests simultâneos por Runner, evitando que um Runner saturado consuma todo o pool de conexões ou o rate limit da Exchange em detrimento dos outros."

#### 15.5.1 Mecanismo de Quota

Cada Runner recebe uma quota máxima de requests por intervalo de tempo:

```java
public class PerRunnerQuota {
    private final Map<UUID, Semaphore> runnerSemaphores;
    private final int maxConcurrentPerRunner;     // default: 3
    private final int maxRequestsPerMinutePerRunner; // default: 30

    public boolean tryAcquire(UUID runnerId) {
        Semaphore sem = runnerSemaphores.computeIfAbsent(
            runnerId,
            id -> new Semaphore(maxConcurrentPerRunner)
        );
        return sem.tryAcquire();
    }

    public void release(UUID runnerId) {
        Semaphore sem = runnerSemaphores.get(runnerId);
        if (sem != null) sem.release();
    }
}
```

#### 15.5.2 Interação com Throttling Global

A quota por Runner opera **antes** do rate limiter global:

```
Request do Runner
       │
       ▼
┌──────────────┐    REJECT (quota)
│ Per-Runner   ├──────────────────► metrics + log
│ Quota Check  │
└──────┬───────┘
       │ OK
       ▼
┌──────────────┐    DEFER (throttled)
│ Weight-Based ├──────────────────► enfileirar para retry
│ Rate Limiter │
└──────┬───────┘
       │ OK
       ▼
┌──────────────┐    FAILOVER
│ Key Pool     ├──────────────────► tentar próxima chave
│ Selection    │
└──────┬───────┘
       │ OK
       ▼
  Exchange API
```

#### 15.5.3 Detecção de Runner Mal Comportado

O ExchangeAdapter monitora métricas por Runner para detectar comportamentos anômalos (Blueprint §12.D.3):

| Métrica                | Limite                                   | Ação                                                   |
|------------------------|------------------------------------------|--------------------------------------------------------|
| **Requests/min**       | Acima de `maxRequestsPerMinutePerRunner` | Throttling individual                                  |
| **Taxa de erros**      | N erros em T segundos                    | Alerta + notificação ao Portfolio para Circuit Breaker |
| **Requests em fila**   | Acima de 5 pendentes                     | Alerta de backpressure                                 |
| **Peso consumido/min** | Acima de 20% do peso total da chave      | Alerta — Runner monopolizando recursos                 |

#### 15.5.4 Configuração de Quota

| Parâmetro                                           | Default  | Descrição                                   |
|-----------------------------------------------------|----------|---------------------------------------------|
| `exchange.quota.max-concurrent-per-runner`          | `3`      | Máximo de requests simultâneos por Runner   |
| `exchange.quota.max-requests-per-minute-per-runner` | `30`     | Máximo de requests/minuto por Runner        |
| `exchange.quota.error-threshold`                    | `5`      | Erros consecutivos para notificar Portfolio |
| `exchange.quota.error-window-seconds`               | `60`     | Janela de tempo para contagem de erros      |

### 15.6 Sincronia de Relógio (TimeService)

> **Questão aberta respondida:** "Como o sistema garante a ordem cronológica de eventos quando o servidor e a Exchange apresentam desvios de milissegundos?" (IMPLEMENTATION_GUIDE_QUESTOES.md — Sincronia de Relógio).

#### 15.6.1 O Problema

O `clientOrderId` (Seção 6) embute um timestamp do sistema. Se o relógio local estiver dessincronizado com a Exchange, podem ocorrer:

| Cenário                     | Impacto                                                                                          |
|-----------------------------|--------------------------------------------------------------------------------------------------|
| **Relógio local adiantado** | Exchange pode rejeitar a ordem (timestamp no futuro, ex: Binance rejeita `recvWindow` violation) |
| **Relógio local atrasado**  | Eventos da Exchange parecem ter timestamps "do futuro" no log local — confunde diagnóstico       |
| **Drift gradual**           | Sequenciamento inconsistente — reconciliação pode perder a ordem cronológica de eventos          |

#### 15.6.2 Solução: TimeService com Offset

```java
@Component
public class ExchangeTimeService {
    private volatile long offsetMs = 0;  // local - exchange (em ms)

    /**
     * Sincroniza com o serverTime da Exchange.
     * Chamado periodicamente (a cada 5 min) e no boot.
     */
    @Scheduled(fixedDelayString = "${exchange.time-sync.interval-ms:300000}")
    public void syncTime() {
        long beforeCall = System.currentTimeMillis();
        long exchangeTime = exchangeAdapter.getServerTime();
        long afterCall = System.currentTimeMillis();

        // Estimar latência de rede (round-trip / 2)
        long networkLatency = (afterCall - beforeCall) / 2;
        long localTimeAtExchangeResponse = beforeCall + networkLatency;

        long newOffset = localTimeAtExchangeResponse - exchangeTime;

        if (Math.abs(newOffset) > MAX_ACCEPTABLE_DRIFT_MS) {
            log.warn("Clock drift detected: {}ms (threshold: {}ms)",
                newOffset, MAX_ACCEPTABLE_DRIFT_MS);
            metrics.gauge("exchange.clock.drift_ms", Math.abs(newOffset));
        }

        this.offsetMs = newOffset;
    }

    /**
     * Retorna o timestamp ajustado para uso em clientOrderId e logs.
     */
    public Instant exchangeNow() {
        return Instant.now().minusMillis(offsetMs);
    }
}
```

#### 15.6.3 Uso do TimeService

| Contexto                                                   | Clock usado              | Justificativa                                                                       |
|------------------------------------------------------------|--------------------------|-------------------------------------------------------------------------------------|
| **clientOrderId**                                          | `exchangeNow()`          | Timestamp precisa ser compatível com `recvWindow` da Exchange                       |
| **Logs de aplicação**                                      | `Instant.now()` (local)  | Correlacionar com logs de infraestrutura                                            |
| **Timestamps de domínio** (`createdAt`, `matchedAt`, etc.) | `Instant.now()` (local)  | Consistência interna — Exchange timestamps são referência apenas para reconciliação |
| **Reconciliação**                                          | Ambos (local + Exchange) | Comparar timestamps requer conhecimento do offset                                   |

#### 15.6.4 Configuração do TimeService

| Parâmetro                         | Default          | Descrição                                   |
|-----------------------------------|------------------|---------------------------------------------|
| `exchange.time-sync.interval-ms`  | `300000` (5 min) | Intervalo de sincronização                  |
| `exchange.time-sync.max-drift-ms` | `1000`           | Drift máximo aceitável antes de alertar     |
| `exchange.time-sync.enabled`      | `true`           | Habilitar sincronização automática          |
| `exchange.time-sync.on-boot`      | `true`           | Forçar sincronização durante boot (Phase 1) |

### 15.7 Isolamento de Erros e Failover

O ExchangeAdapter implementa isolamento de erros para que falhas de conectividade não se propaguem para os Runners.

#### 15.7.1 Categorias de Erro

| Erro                     | Causa Típica                | Ação do Adapter                                  | Visível ao Runner?                      |
|--------------------------|-----------------------------|--------------------------------------------------|-----------------------------------------|
| **HTTP 429**             | Rate limit excedido         | Failover para outra chave + retry                | Não (transparente)                      |
| **HTTP 418**             | IP banned                   | Chave → DISABLED, failover                       | Não se há chaves saudáveis              |
| **HTTP 5xx**             | Erro do servidor Exchange   | Retry com backoff exponencial (max 3 tentativas) | Sim se todos os retries falharem        |
| **Timeout**              | Rede lenta / Exchange lenta | Retry 1x com timeout maior, depois fail          | Sim como `ExchangeTimeoutException`     |
| **Connection refused**   | Exchange offline            | Todas as chaves → DISABLED, aguardar recovery    | Sim como `ExchangeUnavailableException` |
| **Invalid API Key**      | Key revogada/expirada       | Chave → DISABLED, failover                       | Não se há chaves saudáveis              |
| **Insufficient balance** | Saldo real < esperado       | Não retry — propagar ao Runner                   | Sim — trigger de reconciliação          |

#### 15.7.2 Retry com Backoff

```java
<T> CompletableFuture<T> executeWithRetry(ExchangeRequest request) {
    return RetryTemplate.builder()
        .maxAttempts(3)
        .exponentialBackoff(Duration.ofMillis(200), 2.0, Duration.ofSeconds(2))
        .retryOn(RetryableExchangeException.class)
        .doNotRetryOn(
            InsufficientBalanceException.class,
            InvalidOrderException.class,
            AuthenticationException.class
        )
        .build()
        .execute(() -> doExecute(request));
}
```

> **Decisão:** Erros de negócio (`InsufficientBalance`, `InvalidOrder`) **nunca** sofrem retry — indicam problema no estado local, não na conectividade.

#### 15.7.3 Failover entre Chaves

Quando uma chave falha, o failover é automático e transparente:

```java
<T> T executeWithFailover(ExchangeRequest request) {
    List<ApiKeyEntry> candidates = keyPool.healthyKeys();

    for (ApiKeyEntry key : candidates) {
        try {
            return doExecute(request, key);
        } catch (RetryableExchangeException ex) {
            key.recordError();
            log.warn("Key {} failed for {}: {}. Trying next key.",
                key.id(), request.type(), ex.getMessage());
        }
    }

    throw new ExchangeUnavailableException(
        "All keys exhausted for request: " + request.type());
}
```

### 15.8 Canais de Comunicação: WebSocket vs REST

O ExchangeAdapter gerencia **ambos os canais** (WebSocket e REST) como um gateway unificado. O codebase atual utiliza WebSocket como canal primário.

#### 15.8.1 Separação de Responsabilidades por Canal

| Canal                    | Responsabilidade                                                                                                           | Quem gerencia                                                        | Rate Limiting                                          |
|--------------------------|----------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------|--------------------------------------------------------|
| **WebSocket** (primário) | Envio de ordens (`order.place`, `order.cancel`), market data (ticker, depth), execution reports, account updates           | `ExchangeAdapterPort` via `WebSocketPort` + `SenderMessageProcessor` | Por mensagens/segundo e limites de conexão da Exchange |
| **REST API** (fallback)  | Reconciliação no boot (`getOpenOrders`, `getAccountBalance`), health check (`getServerTime`), consultas pontuais de status | `ExchangeAdapterPort` (endpoint REST interno)                        | Weight-based (Seção 15.4)                              |

> **Nota arquitetural:** No codebase atual, o `ExchangeAdapterPort` é o façade que encapsula **ambos os canais**. O `WebSocketPort` e os `MessageProcessors` são componentes internos do adapter, não componentes separados visíveis ao domínio. Isto garante que o Runner interage apenas com o ExchangeAdapter — nunca diretamente com WebSocket ou REST.

#### 15.8.2 Implicação para Rate Limiting

Como ordens são enviadas via WebSocket (não REST), o rate limiting por peso (Seção 15.4) aplica-se primariamente ao canal REST (reconciliação, boot). O canal WebSocket tem seus próprios limites:

| Limite WebSocket (Binance)  | Valor típico        | Impacto                                 |
|-----------------------------|---------------------|-----------------------------------------|
| Mensagens por segundo       | 5 msg/s por conexão | Limita throughput de ordens simultâneas |
| Conexões por IP             | 300                 | Limita Key Pooling para WebSocket       |
| Streams por conexão         | 1024                | Limita número de símbolos monitorados   |
| Timeout de inatividade      | 24h (com ping/pong) | Requer keep-alive periódico             |

> **Decisão:** O per-Runner quota (Seção 15.5) opera **antes** do envio via WebSocket, limitando a taxa de mensagens por Runner independentemente do canal. Isto previne que um Runner monopolize a conexão WebSocket compartilhada.

#### 15.8.3 Execution Reports via WebSocket

O acompanhamento de ordens é feito via WebSocket (`userDataStream`), não por polling REST:

| Benefício                     | Descrição                                                   |
|-------------------------------|-------------------------------------------------------------|
| **Detecção imediata de FILL** | Execution report chega em tempo real — sem polling          |
| **Zero peso REST**            | Não consome quota de requests REST                          |
| **Reconciliação passiva**     | Execution reports servem como confirmação contínua          |
| **Latência mínima**           | Não há round-trip HTTP — o dado chega pelo stream já aberto |

**Fluxo de execution report:**

```
Exchange WebSocket (userDataStream)
       │
       ▼ JSON: {"e":"executionReport","s":"BTCUSDT","X":"FILLED",...}
┌─────────────────────────────────────────────────────────────────────────────┐
│ ExchangeAdapter                                                             │
│                                                                             │
│ ReceivedMessageProcessor.processMessage(rawJson) → OrderDataDto (domínio)   │
└──────────────┬──────────────────────────────────────────────────────────────┘
               │ OrderDataDto
               ▼
┌──────────────────────────────┐
│ EventRouter                  │
│ (clientOrderId → runnerId)   │
└──────────────┬───────────────┘
               │
               ▼
         Runner Mailbox
```

### 15.9 Interface do ExchangeAdapter (Portas Outbound)

**Interface existente no codebase** (canal WebSocket — primário):

```java
public interface ExchangeAdapterPort {
    String getExchangeName();
    boolean requiresPostConnection();
    WebSocketPort getWebSocketPort();                           // Conexão raw
    ReceivedMessageProcessorPort getReceivedMessageProcessor(); // Exchange → Domain
    SenderMessageProcessorPort getSenderMessageProcessor();     // Domain → Exchange
    ExchangeUrlBuilderPort getUrlBuilder();                     // URLs de conexão
}
```

**Interface alvo para canal REST** (fallback — a ser implementada):

```java
/**
 * Operações REST usadas apenas em reconciliação, boot e fallback.
 * Não substitui o canal WebSocket para operações de tempo real.
 */
public interface ExchangeRestPort {

    // --- Reconciliação / Boot ---
    CompletableFuture<AccountBalance> getAccountBalance();
    CompletableFuture<List<OpenOrder>> getOpenOrders(String symbol);
    CompletableFuture<OrderStatus> getOrderStatus(String clientOrderId);

    // --- Market Data (fallback quando WS indisponível) ---
    CompletableFuture<BigDecimal> getCurrentPrice(String symbol);
    CompletableFuture<SymbolInfo> getSymbolInfo(String symbol);

    // --- Infraestrutura ---
    CompletableFuture<Long> getServerTime();
    CompletableFuture<ExchangeStatus> getExchangeStatus();
}
```

> **Nota de evolução:** O codebase atual usa `ExchangeAdapterPort` (WebSocket) para tudo, incluindo envio de ordens. A `ExchangeRestPort` será necessária quando o boot sequence (Seção 10) e a reconciliação precisarem de consultas REST pontuais. Ambas as interfaces coexistirão — o `ExchangeAdapterPort` permanece como canal primário de operação.

### 15.10 Observabilidade

#### 15.10.1 Métricas

| Métrica                           | Tipo    | Tags                                       | Descrição                                             |
|-----------------------------------|---------|--------------------------------------------|-------------------------------------------------------|
| `exchange.request.total`          | Counter | `type`, `priority`, `key`                  | Total de requests enviados                            |
| `exchange.request.duration_ms`    | Timer   | `type`, `priority`                         | Latência de requests REST                             |
| `exchange.request.error`          | Counter | `type`, `error_code`, `key`                | Erros por tipo e chave                                |
| `exchange.rate_limit.hit`         | Counter | `key`                                      | Vezes que atingiu rate limit                          |
| `exchange.rate_limit.utilization` | Gauge   | `key`                                      | Percentual de peso consumido                          |
| `exchange.key.status`             | Gauge   | `key`, `status`                            | Status de cada chave (1=healthy, 0=degraded/disabled) |
| `exchange.key.recovered`          | Counter | `key`                                      | Recuperações de chaves degradadas                     |
| `exchange.clock.drift_ms`         | Gauge   | —                                          | Drift de relógio em ms                                |
| `exchange.quota.rejected`         | Counter | `runner_id`                                | Requests rejeitados por quota                         |
| `exchange.failover.count`         | Counter | `from_key`, `to_key`                       | Failovers entre chaves                                |
| `exchange.ws.reconnection`        | Counter | —                                          | Reconexões do WebSocket                               |
| `exchange.ws.messages.sent`       | Counter | `type` (order, cancel, subscribe)          | Mensagens enviadas via WebSocket                      |
| `exchange.ws.messages.received`   | Counter | `type` (ticker, execution_report, account) | Mensagens recebidas via WebSocket                     |
| `exchange.ws.latency_ms`          | Timer   | —                                          | Latência entre envio de ordem e execution report      |
| `exchange.translation.error`      | Counter | `direction` (send, receive), `exchange`    | Erros de tradução de protocolo                        |

#### 15.10.2 Logs Estruturados

```json
{
  "level": "WARN",
  "logger": "ExchangeAdapter",
  "message": "Rate limit approaching threshold",
  "key_id": "key-01",
  "current_weight": 960,
  "max_weight": 1200,
  "utilization_pct": 80.0,
  "throttled_categories": ["MARKET_DATA"]
}
```

```json
{
  "level": "ERROR",
  "logger": "ExchangeAdapter",
  "message": "All keys exhausted",
  "request_type": "PLACE_ORDER",
  "runner_id": "abc-123",
  "keys_attempted": 3,
  "last_error": "HTTP 429 Too Many Requests"
}
```

### 15.11 Configuração Consolidada

| Parâmetro                                           | Default  | Descrição                                        |
|-----------------------------------------------------|----------|--------------------------------------------------|
| `exchange.adapter.type`                             | `MOCK`   | Tipo de adapter (MOCK, BINANCE)                  |
| `exchange.adapter.timeout-ms`                       | `5000`   | Timeout padrão para requests REST                |
| `exchange.adapter.retry.max-attempts`               | `3`      | Tentativas de retry para erros retryable         |
| `exchange.adapter.retry.initial-delay-ms`           | `200`    | Delay inicial do backoff exponencial             |
| `exchange.adapter.retry.max-delay-ms`               | `2000`   | Delay máximo do backoff                          |
| `exchange.keys[N].api-key`                          | —        | API key N                                        |
| `exchange.keys[N].secret-key`                       | —        | Secret key N (encriptado)                        |
| `exchange.keys[N].max-weight`                       | `1200`   | Peso máximo por minuto para a chave N            |
| `exchange.key-pool.health-check-interval-s`         | `60`     | Intervalo de health check para chaves degradadas |
| `exchange.quota.max-concurrent-per-runner`          | `3`      | Requests simultâneos por Runner                  |
| `exchange.quota.max-requests-per-minute-per-runner` | `30`     | Requests/min por Runner                          |
| `exchange.time-sync.interval-ms`                    | `300000` | Intervalo de sincronização de relógio            |
| `exchange.time-sync.max-drift-ms`                   | `1000`   | Drift máximo aceitável                           |

### 15.12 Implicações Arquiteturais

| Decisão                                             | Justificativa                                                                                                                               | Trade-off                                                                                                                                                      |
|-----------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Gateway centralizado (ExchangeAdapter)              | Ponto único para tradução de protocolo, Key Pooling, rate limiting, failover e observabilidade                                              | Single point of failure — mitigado por ser stateless (restartável) e por Circuit Breaker no Portfolio                                                          |
| Anti-Corruption Layer (Tradução de Protocolo)       | Domínio trabalha com DTOs agnósticos (`MarketDataDto`, `OrderDataDto`). Formato da Exchange nunca vaza para o domínio                       | Cada nova Exchange requer implementação de `ReceivedMessageProcessor` e `SenderMessageProcessor` — custo de integração                                         |
| WebSocket-first (ordens via WS, REST como fallback) | Latência mínima para envio de ordens e recebimento de execution reports. Sem polling. Sem consumo de peso REST para operações em tempo real | Dependência de conexão WebSocket estável. Se WS cair, ordens não podem ser enviadas até reconexão (mitigado: Circuit Breaker + ReconnectionStrategy existente) |
| Key Pooling transparente                            | Runners não sabem qual chave é usada — simplifica o contrato                                                                                | Complexidade interna do Adapter aumenta; Key Affinity pode causar desbalanceamento se mal calibrado                                                            |
| Weight-based rate limiting para REST                | Preserva quota de requests REST para operações críticas (reconciliação, boot)                                                               | Rate limiting de WebSocket é por mensagens/segundo, não por peso — modelo diferente requer atenção separada                                                    |
| Per-Runner quota                                    | Impede monopolização de recursos por um Runner ruidoso                                                                                      | Limita throughput de Runners legítimos com alta frequência de operação                                                                                         |
| TimeService com offset                              | Simples e eficaz — não requer NTP customizado                                                                                               | Precisão limitada pela latência de rede. Aceitável para trading spot (não HFT)                                                                                 |
| `ExchangeAdapterPort` unifica WS + REST             | Um único façade para o domínio. Runner não sabe qual canal é usado                                                                          | Complexidade interna maior. REST port precisa ser adicionada ao façade quando implementada                                                                     |
| Retry apenas para erros retryable                   | Erros de negócio indicam problema no estado, não na rede — retry seria inútil                                                               | Se o estado local estiver correto mas a Exchange rejeitar, o erro pode parecer "de negócio" mas ser transitório (ex: timing de saldo)                          |

---

## 16. Registro de Questões em Aberto

Esta seção consolida todas as questões de implementação identificadas durante a elaboração do Implementation Guide. Cada questão é classificada por status de resolução e referenciada à seção que a aborda (quando aplicável).

### 16.1 Legenda de Status

| Status        | Significado                                                                                         |
|---------------|-----------------------------------------------------------------------------------------------------|
| **RESOLVIDA** | Questão respondida em uma seção do IG (decisão documentada — o estado de implementação está na matriz da T39)                                                |
| **PARCIAL**   | Questão parcialmente endereçada — aspectos principais cobertos, detalhes de implementação pendentes |
| **ABERTA**    | Questão ainda não endereçada — requer decisão arquitetural ou investigação durante implementação    |
| **ADIADA**    | Questão explicitamente adiada para V2+ ou escopo futuro                                             |

### 16.2 Notas de Implementação Numeradas (#1–#31)

Todas as 31 notas de implementação do `IMPLEMENTATION_GUIDE_QUESTOES.md` foram absorvidas nas seções do IG:

| Nota  | Título                                                | Seção IG                         | Status    |
|-------|-------------------------------------------------------|----------------------------------|-----------|
| #1    | Persistência Atômica "Pre-Flight"                     | 6.2 (Ciclo de Vida da Transação) | RESOLVIDA |
| #2    | Value Object `ClientOrderId`                          | 3.4.1, 6.3                       | RESOLVIDA |
| #3    | Estratégia de Retry e Timeout                         | 6.4, 15.6                        | RESOLVIDA |
| #4    | Boot Sequence (Safe Mode)                             | 10.4, 11.1                       | RESOLVIDA |
| #5    | Gestão de Fees Proporcionais e Cross-Currency         | 8.2, 8.3                         | RESOLVIDA |
| #6    | Máquina de Estados: Parcial → Cancelado               | 6.5, 6.6                         | RESOLVIDA |
| #7    | Governança de Locks em Cancelamentos                  | 9.3, 9.4                         | RESOLVIDA |
| #8    | Algoritmo de "Sanity Check" no Boot                   | 10.3                             | RESOLVIDA |
| #9    | Identificação de "Zumbis" da Exchange                 | 10.4                             | RESOLVIDA |
| #10   | Timestamp de Corte                                    | 10.5                             | RESOLVIDA |
| #11   | Ponto de Injeção de Parada (Gatekeeper)               | 11.3                             | RESOLVIDA |
| #12   | Persistência do Estado de Alerta                      | 11.4                             | RESOLVIDA |
| #13   | Monitoramento de "Kill-Switch" Externo                | 11.7                             | RESOLVIDA |
| #14   | Arredondamento de Capital (Safety Buffer)             | 7.3                              | RESOLVIDA |
| #15   | Centralização da RoundingPolicy                       | 8.2                              | RESOLVIDA |
| #16   | Conversão de Tipos na I/O                             | 8.4                              | RESOLVIDA |
| #17   | Validação de `minNotional`                            | 8.5                              | RESOLVIDA |
| #18   | Atomicidade no `requestCapital`                       | 7.2, 9.2                         | RESOLVIDA |
| #19   | Ordem de Processamento de Sinais                      | 12.3, 12.4                       | RESOLVIDA |
| #20   | Monitoramento de "Rejection Rate"                     | 7.8                              | RESOLVIDA |
| #21   | Ports de Comunicação (ReserveCapitalPort, ConfirmExecutionPort, ReleaseMarginPort) | 5.2, 5.3 | RESOLVIDA |
| #22   | Idempotência no Portfolio                             | 5.4, 6.3                         | RESOLVIDA |
| #23   | Implementação da Mailbox                              | 12.2                             | RESOLVIDA |
| #24   | Monitoramento de Backpressure                         | 12.8                             | RESOLVIDA |
| #25   | Lock de Interface (UI/Admin) — Prioridade de Comandos | 12.5                             | RESOLVIDA |
| #26   | Factory de Runners                                    | 13.3                             | RESOLVIDA |
| #27   | Soft Delete vs Archive                                | 13.7                             | RESOLVIDA |
| #28   | Health Check de Ativação                              | 13.4                             | RESOLVIDA |
| #29   | Precisão no Cálculo Ponderado                         | 8.2                              | RESOLVIDA |
| #30   | Sincronização de Cache                                | 8.6                              | RESOLVIDA |
| #31   | Tratamento de "Reset" de Posição                      | 8.6                              | RESOLVIDA |

### 16.3 Questões Temáticas — Status de Resolução

#### 16.3.1 Questões Resolvidas

| Questão                            | Origem                         | Seção IG  | Resumo da Resolução                                                                                                                                                                 |
|------------------------------------|--------------------------------|-----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Rate Limiting da Exchange          | BLUEPRINT_QUESTOES #3          | 15.3      | Weight-based rate limiter centralizado no ExchangeAdapter, com priorização EXECUTION > ACCOUNT > MARKET_DATA e throttling automático a 80%                                          |
| Sincronia de Relógio (Clock Drift) | QUESTOES_SEM_CLASSIFICACAO #14 | 15.5      | `ExchangeTimeService` com offset calculado via `getServerTime()`, sincronização periódica (5 min) + no boot                                                                         |
| Context Injection para Strategy    | QUESTOES_PENDENTES #12         | 14.3–14.6 | `StrategyContextDto` com `PositionContext` (VO read-only), `OpenLotDto`, `PendingOrderDto`. Strategy stateless, recebe contexto assembrado pelo `RunnerContextAssembler`            |
| Cooldown Implementation            | QUESTOES_PENDENTES #13         | 14.9      | Cooldown implícito via Execution Policy (Single mode) + Mailbox (capacity=1). Timer explícito reservado para V2+ (`cooldown.explicit-ms`)                                           |
| Comunicação entre Agregados        | QUESTOES_PENDENTES #2          | 5.2–5.5   | Chamadas síncronas in-process (monolito V1). Portfolio expõe três ports: `ReserveCapitalPort` (síncrono), `ConfirmExecutionPort` e `ReleaseMarginPort` (assíncronos). Outbox Pattern reservado para decomposição futura |
| Migração do Tópico 8 do Blueprint  | —                              | 4.x       | Conteúdo migrado para IG Seção 4 (Migração e Decomposição). Blueprint renumerado                                                                                                    |
| Limites de Recursos por Runner     | BLUEPRINT_QUESTOES #6          | 13.3, 7.4 | Validados no `RunnerFactory` (hard limits) e no `reserve()` do Portfolio. Imutáveis durante execução, alteráveis via HALTED→reconfigure→ACTIVE                                      |

#### 16.3.2 Questões Parcialmente Resolvidas

| Questão                                             | Origem                 | Cobertura Atual                                                                                               | O que falta                                                                                                                                                                                                                                                                           |
|-----------------------------------------------------|------------------------|---------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Consistência de Dados entre Agregados**           | QUESTOES_PENDENTES #4  | Seção 5 define chamadas síncronas (strong consistency para `reserve`). Seção 10 define reconciliação no boot  | **Pendente:** Definir o window de eventual consistency para `confirmExecution` (async). Impacto de Runner decidindo com saldo desatualizado entre `reserve` e `confirm` — na prática mitigado pelo modelo Single (um Runner processa uma operação por vez)                            |
| **Validação de Trade Parameters**                   | QUESTOES_PENDENTES #11 | Seção 7 (validação no `reserve`), Seção 8 (`minNotional`, `tickSize`), Seção 13 (RunnerFactory valida config) | **Pendente:** Documentar a cadeia completa de validação em um fluxo único: Strategy → Runner (Execution Policy) → Portfolio (`reserve`) → ExchangeAdapter (Exchange rules). Definir tratamento quando validação falha em cada nível                                                   |
| **Ordem de Processamento Concorrente no Portfolio** | BLUEPRINT_QUESTOES #7  | Seção 9 (locks com `FOR UPDATE SKIP LOCKED`), Seção 12 (per-Runner serialization via Mailbox)                 | **Pendente:** A serialização é por Runner (Mailbox), mas entre Runners o Portfolio usa lock otimista no DB. Documentar explicitamente que não há FIFO global — a ordem é determinada por timing de chegada ao DB lock. Risco de starvation mitigado por per-Runner quota (Seção 15.5) |

#### 16.3.3 Questões Abertas

| #    | Questão                                          | Origem                         | Impacto                                                                    | Prioridade  | Notas                                                                                                                                                                                                                                                                                               |
|------|--------------------------------------------------|--------------------------------|----------------------------------------------------------------------------|-------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Q-01 | **Testing Strategy**                             | QUESTOES_PENDENTES #22         | Define cobertura de testes para os dois agregados e comunicação entre eles | Alta        | Inclui: (a) mocks do Portfolio em testes de Runner, (b) testes de integração inter-agregado, (c) simulação de falhas de rede, (d) testes de carga com múltiplos Runners. O `MockExchangeAdapter` existente precisa de modos de falha configuráveis                                                  |
| Q-02 | **Testabilidade do Protocolo de Reconciliação**  | BLUEPRINT_QUESTOES #13         | Define como testar boot sequence e cenários de crash recovery              | Alta        | Inclui: (a) simular Exchange que "perdeu" uma ordem (ordem fantasma), (b) simular crash entre persistência e dispatch, (c) modos de falha no `MockExchangeAdapter`, (d) cobertura dos cenários da tabela de reconciliação (Seção 10.3)                                                              |
| Q-03 | **Tratamento de Ordens Pós-Mercado (Expiração)** | QUESTOES_SEM_CLASSIFICACAO #12 | Define o mecanismo de detecção e tratamento de ordens expiradas            | Média       | Parcialmente coberta pelo Watchdog (Seção 6.7) e Circuit Breaker (Seção 11). Falta definir: (a) quem detecta expiração — Watchdog local vs evento da Exchange, (b) diferença entre CANCELED e EXPIRED no impacto contábil, (c) tempo máximo configurável em SUBMITTED, (d) se Strategy é notificada |
| Q-04 | **Precisão na Camada de Apresentação**           | QUESTOES_SEM_CLASSIFICACAO #14 | Define formatação de valores em APIs REST e UI                             | Baixa       | Seção 8 cobre precisão na borda com Exchange. Falta definir: (a) regras de arredondamento para exibição (truncar vs round), (b) número de casas por tipo de ativo, (c) tratamento de dust values na UI (ex: "0.00000001 BTC" → esconder ou agregar)                                                 |

### 16.4 Questões Adiadas (Escopo V2+)

| Questão                                        | Razão do Adiamento                                                                                                             | Pré-requisito                                                     |
|------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------|
| **Alavancagem (Leverage)**                     | V1 opera apenas Spot. Detalhes em `LEVERAGE_DESIGN.md`                                                                         | Suporte a Futures/Margin na Exchange                              |
| **Estratégias Correlacionadas** (cross-Runner) | V1 isola completamente cada Runner. Permitir que uma Strategy veja dados de outros Runners adiciona complexidade significativa | Modelo de eventos inter-Runner                                    |
| **Multi-currency Fees**                        | V1 assume fee na moeda do par. Conversão cross-currency (ex: fee em BNB para par BTC/USDT) adiada                              | `FeeConversionService` com acesso a preço de câmbio em tempo real |
| **Decomposição em Microserviços**              | V1 é monolito. Outbox Pattern e event bus preparados mas não implementados                                                     | Volume de Runners que justifique a separação                      |
| **Dashboard / UI**                             | Seção 15 e demais definem REST APIs. UI não é escopo da arquitetura backend                                                    | Definição de stack frontend                                       |
| **Backtesting com dois agregados**             | O engine de backtesting existente opera no modelo antigo (Portfolio único). Migração adiada                                    | Conclusão da refatoração para dois agregados                      |

### 16.5 Matriz de Dependência das Questões Abertas

```
Q-01 (Testing Strategy)
  │
  ├── depende de → Q-02 (Testabilidade de Reconciliação)
  │                  └── modos de falha do MockExchangeAdapter
  │                       são pré-requisito para ambas
  │
  └── informa → Q-03 (Expiração)
                  └── testes de expiração dependem da
                       estratégia de teste definida em Q-01
```

> **Recomendação:** Resolver Q-01 e Q-02 juntas durante a fase de implementação — a definição do framework de testes (mocks, modos de falha, fixtures) serve como fundação para todos os cenários de teste.

### 16.6 Rastreabilidade: Questões → Seções

Mapa reverso para localizar rapidamente onde cada fonte de questões foi absorvida:

| Fonte                                          | Questões                      | Destino no IG                   |
|------------------------------------------------|-------------------------------|---------------------------------|
| `BLUEPRINT_QUESTOES.md` #3                     | Rate Limiting                 | Seção 15.4                      |
| `BLUEPRINT_QUESTOES.md` #6                     | Limites por Runner            | Seções 7.4, 13.3               |
| `BLUEPRINT_QUESTOES.md` #7                     | Concorrência no Portfolio     | Seções 9.2, 12.3 (parcial)     |
| `BLUEPRINT_QUESTOES.md` #13                    | Testabilidade Reconciliação   | Q-02 (aberta)                   |
| `QUESTOES_PENDENTES.md` #2                     | Comunicação entre Agregados   | Seção 5                         |
| `QUESTOES_PENDENTES.md` #4                     | Consistência de Dados         | Seção 5, 10 (parcial)           |
| `QUESTOES_PENDENTES.md` #11                    | Validação de Trade Parameters | Seções 7, 8, 13 (parcial)      |
| `QUESTOES_PENDENTES.md` #12                    | Context Injection             | Seção 14                        |
| `QUESTOES_PENDENTES.md` #13                    | Cooldown                      | Seção 14.9                      |
| `QUESTOES_PENDENTES.md` #22                    | Testing Strategy              | Q-01 (aberta)                   |
| `QUESTOES_SEM_CLASSIFICACAO.md` #12            | Expiração de Ordens           | Q-03 (aberta)                   |
| `QUESTOES_SEM_CLASSIFICACAO.md` #14 (Clock)    | Clock Drift                   | Seção 15.6                      |
| `QUESTOES_SEM_CLASSIFICACAO.md` #14 (Precisão) | Precisão Apresentação         | Q-04 (aberta)                   |
| Notas de Impl. #1–#31                          | Detalhes técnicos             | Seções 5–15 (todas resolvidas) |

### 16.7 Sumário Estatístico

| Categoria                                  | Quantidade                             |
|--------------------------------------------|----------------------------------------|
| Notas de implementação (#1–#31)            | 31 — **todas resolvidas**              |
| Questões temáticas resolvidas              | 7                                      |
| Questões temáticas parcialmente resolvidas | 3                                      |
| Questões temáticas abertas                 | 4 (Q-01 a Q-04)                        |
| Questões adiadas (V2+)                     | 6                                      |
| **Total de questões rastreadas**           | **51**                                 |
| **Taxa de resolução**                      | **80%** (41/51 resolvidas ou parciais) |


