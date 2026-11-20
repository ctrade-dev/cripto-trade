# T34 — Preço de Ordem Dirigido pela Estratégia (`limitPrice` no contrato)

**Complexidade:** Média
**Responsável:** Claude
**Dependências:** nenhuma (fundação; habilita a T35)
**Status:** Concluído

---

## Descrição

Hoje a estratégia decide apenas **ação** (`SHOULD_BUY`/`SHOULD_SELL`) e **quantidade**. O **preço** da ordem não é escolhido pela estratégia: `ProcessTradeSignalUseCase.processTradeSignal()` usa sempre o `currentPrice` do tick, normaliza-o e materializa a transação com esse valor (`OrderNormalizer.normalize(exchange, symbol, signal.quantity(), currentPrice)` → `TradeIntentFactory.buildTransaction(..., normalized.price())`). A ordem sai sempre como `LIMIT` no preço de mercado corrente.

Isso é suficiente para a `SimpleMovingAverageStrategy`, mas impede qualquer estratégia de **posicionar uma ordem longe do mercado de forma intencional** — que é exatamente o que estratégias de cenário determinísticas precisam:

- uma ordem que **descansa sem preencher** (para exercitar o cancelamento de forma determinística);
- uma ordem **marketable** (atravessa o spread) para **garantir o fill** e testar o caminho feliz de ponta a ponta.

Sem controle de preço, uma ordem colocada no preço de mercado pode ou não preencher (depende do book), tornando qualquer teste de cancelamento/round-trip **não determinístico**.

Esta task adiciona ao contrato de saída da estratégia um **preço-limite opcional**. Quando ausente (`null`), o comportamento atual é preservado byte-a-byte (usa o `currentPrice`). Quando presente, o preço da estratégia atravessa normalização, reserva e dispatch, mantendo a invariante "preço persistido/reservado = preço enviado à exchange".

### Alinhamento com o design já documentado (não é capacidade nova)

O blueprint **já prevê** a decisão da estratégia carregando um preço. O `StrategyOutputDto` implementado hoje é uma **redução** do `TradingDecision` descrito no design — ficou só com `quantity`, sem `price`. Referências:

- `docs/IMPLEMENTATION_GUIDE.md` §7.2.1 (tabela "Fórmula de Cálculo"): `Preço_Estimado` ← **`TradingDecision.price`**, definido como *"Último preço de mercado **ou preço limit**"*.
- `docs/IMPLEMENTATION_GUIDE.md` §7.2.2: distingue **`MARKET` vs `LIMIT`** como tipo de ordem, com *safety buffers* distintos (`1.005` / `1.001`).
- `docs/BLUEPRINT.md` §C: trata explicitamente "ordens enviadas mas não executadas (Limit Orders)" e sua liberação de margem.

Ou seja, esta task **reconcilia o código com o design existente** (realiza o `.price` do `TradingDecision`), não introduz um conceito novo. O `OrderType` (`MARKET`/`LIMIT`) do §7.2.2 fica **fora de escopo** desta task por escolha de simplicidade (mantemos `LIMIT`; fills garantidos via *limit marketable*), mas é o próximo passo natural de alinhamento pleno ao blueprint — registrar como follow-up se/quando um cenário exigir `MARKET`.

---

## Contexto técnico

### Caminho atual do preço (sem controle da estratégia)

```
tick.currentPrice
  → OrderNormalizer.normalize(exchange, symbol, quantity, currentPrice)   // alinha a tickSize
  → TradeIntentFactory.buildTransaction(..., normalized.price())          // total = quantity × price (reserva)
  → OrderDispatchCommand(..., transaction.getPrice())                     // LIMIT enviado à exchange
```

Pontos relevantes já verificados:

- `StrategyOutputDto` **não tem** campo de preço; as factories `buy(...)`/`sell(...)`/`sellLot(...)` não o recebem.
- `TradeIntentFactory.buildTransaction(runner, signal, quantity, price)` **já recebe `price` como parâmetro** — a reserva de capital de BUY é `quantity × price` e o dispatch usa `transaction.getPrice()`.
- Ou seja, o encanamento a jusante já é parametrizado por preço; falta apenas **a estratégia poder emiti-lo** e o orquestrador **resolvê-lo** antes da normalização.

### Invariante a preservar

Do flow map `runner-signal-processing.md`:

> Quantidade e preço persistidos/reservados devem ser idênticos aos enviados à exchange: a normalização acontece antes do persist, e o `OrderFilterValidator` no dispatch apenas valida (rejeita desalinhamento), sem reajustar valores.

O `limitPrice` da estratégia é normalizado **no mesmo ponto** que o preço de mercado hoje, então a invariante permanece: o valor normalizado é o único que persiste, reserva e é enviado.

---

## Solução proposta

### 1. Campo opcional no contrato

Adicionar a `StrategyOutputDto` um `limitPrice` **opcional** (nullable):

```java
BigDecimal limitPrice;   // null = usar o preço de mercado do tick (comportamento atual)
```

Factories:

- Manter `buy(name, confidence, quantity, reasoning)` e `sell(...)`/`sellLot(...)` atuais com `limitPrice = null` (compatibilidade total).
- Adicionar overloads explícitos, ex.: `buyAt(name, confidence, quantity, limitPrice, reasoning)` e `sellAt(...)`, validando `limitPrice > 0` quando presente.
- `cancel(...)` e `hold(...)` seguem com `limitPrice = null` (não faz sentido).

### 2. Resolução do preço no orquestrador

Em `ProcessTradeSignalUseCase.processTradeSignal()`, resolver o preço efetivo **antes** da normalização:

```java
BigDecimal effectivePrice = signal.limitPrice() != null ? signal.limitPrice() : currentPrice;
OrderNormalizer.NormalizedOrder normalized =
        orderNormalizer.normalize(runner.getExchangeId(), runner.getSymbol(), signal.quantity(), effectivePrice);
```

Nada mais muda: `TradeIntentFactory`, reserva, exposição e dispatch já operam sobre `normalized.price()`.

### 3. Sem mudança de tipo de ordem

O tipo permanece `LIMIT`. Fills garantidos são obtidos com **limit marketable** (BUY com preço acima do ask, SELL com preço abaixo do bid), sem introduzir `MARKET`. Isso evita ampliar a superfície do contrato/adapters nesta fundação; `OrderType` fica fora de escopo.

---

## Arquivos a modificar / criar

| Arquivo | Mudança |
|---|---|
| `core/.../dto/strategy/StrategyOutputDto.java` | Campo `limitPrice` (nullable) + factories `buyAt(...)`/`sellAt(...)` + validação `> 0` |
| `core/.../usecase/runner/processsignal/ProcessTradeSignalUseCase.java` | Resolver `effectivePrice` (limitPrice ?? currentPrice) antes de `orderNormalizer.normalize(...)` |
| `core/.../dto/strategy/` (testes) | Cobrir factories novas e default `null` |
| `core/.../usecase/runner/processsignal/` (testes) | Cobrir: `limitPrice` presente atravessa normalização/reserva/dispatch; ausente preserva `currentPrice` |

Nenhuma mudança em `strategy/`, adapters ou `spring-application` de produção — a `SimpleMovingAverageStrategy` continua usando as factories sem preço.

---

## Impacto documental

A comparação campo a campo entre o `TradingDecision` do blueprint (`docs/IMPLEMENTATION_GUIDE.md` linha 45: `decision`, `symbol`, `quantity`, `price`, `targetLotId`, `reasoning`, `confidence`) e o `StrategyOutputDto` implementado revelou que o **documento está defasado** em relação ao código. Como esta task já mexe nesse contrato, a correção do blueprint é **entregável desta task**. Ao implementar a T34, atualizar `docs/IMPLEMENTATION_GUIDE.md` (e `docs/BLUEPRINT.md` onde referenciar) para refletir a realidade:

| Ponto | Blueprint hoje | Realidade / correção |
|---|---|---|
| Nome do tipo | `TradingDecision` | `StrategyOutputDto` (registrar o nome real; renomear a classe seria refactor amplo de baixo valor e **não** faz parte desta task) |
| Classificação/pacote | VO em `domain.portfolio` | `record` (DTO) em `core.dto.strategy` |
| `price` | campo do VO | passa a existir como `limitPrice` (opcional) — **esta task** |
| `symbol` | campo do VO | **não existe e não deve existir** — o runner é vinculado a um único símbolo (`runner.getSymbol()`); documentar a ausência como decisão consciente (evita redundância e risco de símbolo divergente do runner) |
| `targetTransactionId` | ausente | existe (entregue pela T32, alvo de `SHOULD_CANCEL`) |
| `strategyName`, `timestamp`, `metadata` | ausentes | existem como campos operacionais; incluir na descrição do contrato |

Esta seção cobre **apenas** o alinhamento documental de campos/nomenclatura. A divergência **financeira** do *safety buffer* de reserva (§7.2.2 documentado, não implementado no `TradeIntentFactory`) **não** pertence a esta task — é tratada na **T36**.

---

## Riscos / pontos de atenção

- **Regressão silenciosa:** o caminho default (`limitPrice == null`) deve produzir exatamente o valor de hoje. Cobrir com teste que compara o preço persistido/reservado com o `currentPrice` normalizado.
- **Reserva de capital coerente:** para BUY, `total = quantity × limitPrice`. Um `limitPrice` muito baixo reserva pouco capital (correto) — mas isso é intencional para o cenário de ordem descansando. Não há efeito colateral em `GlobalBalance` além do valor menor reservado.
- **Filtros da exchange (`PERCENT_PRICE`):** a Binance rejeita ordens cujo preço se afaste demais do mercado. Um `limitPrice` arbitrariamente distante pode ser recusado no dispatch pelo `OrderFilterValidator`/exchange. O **quão distante** é responsabilidade da estratégia consumidora (T35) e deve caber na banda do filtro; esta task apenas provê o canal.
- **Fronteira arquitetural:** `limitPrice` é uma **decisão de domínio** (preço-alvo), não um detalhe de provider. Nenhum tipo/schema de exchange entra no contrato. A tradução para a ordem real continua no adapter via `OrderDispatchPort`.
- **MOCK sem normalizador:** exchanges sem `OrderQuantityNormalizerPort` (ex.: MOCK) usam o `limitPrice` original, como já ocorre com o preço de mercado hoje.

---

## Critérios de aceitação

1. `StrategyOutputDto` aceita um `limitPrice` opcional; factories sem preço continuam existindo e produzem `limitPrice = null`.
2. Com `limitPrice = null`, o preço persistido/reservado/enviado é idêntico ao comportamento atual (mesmo `currentPrice` normalizado).
3. Com `limitPrice` presente, esse valor (normalizado) é o único que persiste, reserva capital e é enviado à exchange — sem divergência entre as três pontas.
4. A `SimpleMovingAverageStrategy` permanece inalterada e com comportamento idêntico.
5. `SHOULD_CANCEL` e `SHOULD_HOLD` ignoram `limitPrice`.
6. Cobertura de teste para os dois ramos (com e sem `limitPrice`).
7. **Impacto documental:** `docs/IMPLEMENTATION_GUIDE.md` (e `docs/BLUEPRINT.md` onde referenciar) atualizados conforme a tabela da seção "Impacto documental" — nome/pacote reais do contrato, `price`/`limitPrice`, ausência justificada de `symbol` e campos operacionais (`targetTransactionId`, `metadata`).

---

## Fontes

- `docs/IMPLEMENTATION_GUIDE.md` §7.2.1/§7.2.2 (`TradingDecision.price` = "último preço de mercado ou preço limit"; MARKET vs LIMIT) — **design que esta task realiza**.
- `docs/BLUEPRINT.md` §C (limit orders enviadas e não executadas, liberação de margem).
- `.ai/flows/runner-signal-processing.md` (invariante preço persistido = enviado).
- `core/.../usecase/runner/processsignal/ProcessTradeSignalUseCase.java` (linhas ~296-317).
- `core/.../usecase/runner/processsignal/TradeIntentFactory.java` (reserva `quantity × price`, dispatch).
- `docs/PHASE0_INVARIANTS.md` (efeito financeiro, reserva de BUY).
