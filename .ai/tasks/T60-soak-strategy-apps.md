# T60 — Apps de Estratégia para o Soak

**Complexidade:** Média
**Responsável:** Claude (num repositório próprio, fora do ctrade)
**Dependências:** T41/T40 (contrato 1.1, `docs/strategy-service/CONTRACT.md`), T54 (catálogo por API). Desbloqueia
a fase 3 da T53; serve de portfólio inicial para o M3 do roadmap (`.ai/agentic-strategy-roadmap.md`).
**Status:** Concluído — as cinco apps entregues (`ctrade-dev/ctrade-strategies`, PRs #1, #2 e #3) e validadas na testnet em 2026-10-27; todos os critérios cumpridos.

---

## Descrição

A fase 3 da T53 (decisão 4) pede **várias estratégias remotas ao mesmo tempo, cada uma num processo**,
para estressar o ctrade: mailbox por runner, chamadas remotas concorrentes, capital disputado entre
runners e muitas idas e voltas por hora. Hoje só existe o `strategy-service-reference`, que serve o SMA
de parâmetros fixos e é exemplo de conformidade, não estratégia de soak.

Esta task define **que estratégias o soak precisa e o que cada uma deve provocar no ctrade**. Não
padroniza como uma app de estratégia é construída (`product-direction.md`, "Estratégias externas: só
contrato"): as apps vivem fora do ctrade, falam só o contrato e respondem só `SHOULD_BUY`, `SHOULD_SELL`,
`SHOULD_CANCEL` ou `SHOULD_HOLD`. O ctrade não muda.

## O que o soak precisa provocar

| Caminho do ctrade | Quem provoca |
|---|---|
| BUY e SELL a mercado, várias por hora | tendência e reversão |
| LIMIT que descansa no livro, cancelamento da estratégia, reserva devolvida | estratégia de livro |
| SELL de lote específico (`targetLotId`) e FIFO | reversão (lote) e tendência (FIFO) |
| Vários runners disputando o capital do mesmo portfolio | todas juntas no mesmo portfolio |
| Recusas do ctrade sem efeito colateral (desvio de preço, quantidade acima do lote, cancelamento de ordem inexistente) | estratégia de caos |
| Estratégia lenta, fora do ar, com `5xx` ou resposta inválida → HOLD implícito | estratégia de caos |
| Proteção do lote (T44) com a estratégia muda | caos em modo "segura a posição" |

## As estratégias

Cinco perfis, cada um num processo, cada um com `strategyRef` próprio no catálogo. Os parâmetros ficam
em configuração de cada processo (ex.: variáveis de ambiente), para ajustar a frequência sem mudar código.

### 1. Tendência rápida (cruzamento de médias)

- Compra quando a média curta cruza acima da longa por mais de um limiar; vende o lote (FIFO) no
  cruzamento para baixo.
- Limiar apertado (≈ 0,2%, decisão 4 da T53) para várias idas e voltas por hora.
- Ordens a mercado (`limitPrice` nulo).
- **Exercita:** ciclo básico, warm-up por runner, FIFO.

### 2. Reversão à média

- Compra quando o preço cai X% abaixo da média da janela; vende **o lote específico** (`targetLotId`)
  quando o preço volta à média ou sobe Y% sobre a entrada daquele lote.
- Até N lotes abertos (respeita `maxOpenPositions` do contexto).
- **Exercita:** vários lotes por runner, SELL por lote, PnL por lote no contexto (`openLots[]`).

### 3. Livro (ordens LIMIT que descansam)

- Coloca BUY LIMIT abaixo do mercado (ex.: −0,1%); cancela (`SHOULD_CANCEL`) a ordem que não preencher
  em K ticks e recoloca no novo preço.
- Com lote aberto, coloca SELL LIMIT acima da entrada; cancela e recoloca se o mercado se afastar.
- **Exercita:** ordens em voo (`pendingOrders`), reserva e devolução de capital, cancelamento x fill na
  Binance real, lote travado por SELL em voo.

### 4. Rompimento

- Compra quando o preço supera a máxima da janela; vende quando cai Z% da máxima desde a entrada
  (trailing).
- Menos frequente que as outras: entra em movimentos maiores.
- **Exercita:** posição mais longa, convivência com a proteção do lote por tempo máximo.

### 5. Caos

Uma estratégia que se comporta mal de propósito, com taxas configuráveis por tipo de falha:

| Falha | Efeito esperado no ctrade |
|---|---|
| Latência acima do timeout (250 ms) | HOLD implícito, métrica de timeout |
| Resposta acima de `max-decision-age` | Decisão descartada como velha |
| `5xx` / conexão recusada | HOLD implícito (`STRATEGY_UNAVAILABLE`) |
| JSON malformado ou decisão incoerente (BUY sem `quantity`) | Recusada na fronteira, HOLD |
| `limitPrice` a mais de 10% do tick | `REJECTED_PRICE_DEVIATION` (T50) |
| SELL acima do disponível do lote | Recusa sem ordem |
| CANCEL de transação inexistente | Recusa sem efeito |
| Modo "segura a posição": compra e nunca vende | A proteção do lote (T44) fecha por perda ou tempo |

Fora das falhas, ela responde HOLD. Ela também serve à fase 4 da T53 ("estratégia remota fora do ar"):
basta derrubar o processo ou ligar 100% de `5xx`.

## Requisitos comuns (o contrato, não a implementação)

Todas as apps cumprem o contrato 1.1 — é o mesmo que o ctrade já confere:

- `/v1/evaluate`, `/v1/health`, `/v1/health/{strategyRef}`, `/v1/meta`; `contractVersion` `1.1.0`.
- Decimal sempre como string; datas ISO-8601 UTC.
- Warm-up por `(strategyRef, runnerId)`; `tickId` repetida devolve a decisão em cache; `sequence` menor
  ou igual não mexe no warm-up.
- Quantidade calculada a partir do contexto (`maxOperationAmount`, `availableCapital`), acima do mínimo
  da exchange (`minOperationAmount`).
- Token (`Authorization: Bearer`) quando cadastrado no catálogo.
- `reasoning` explicando a decisão (aparece nas transações e no relatório da rodada).

A conformidade é provada **contra o `openapi.yaml`** do ctrade, num teste do próprio repositório das apps,
e pelo cadastro na T54 (o ctrade confere `/v1/meta` antes de usar a estratégia).

## Como entra no soak

1. As apps sobem como containers ao lado do ctrade no Compose da VPS (rede interna, sem porta exposta).
2. Cadastro de cada uma por `POST /api/admin/strategies` (URL interna e token).
3. Um portfolio de soak com um runner por estratégia em BTCUSDT; `maxAllocationPercent` somando mais que
   100% de propósito, para haver disputa de capital (o ctrade recusa o que passar do disponível).
4. A estratégia de caos fica num runner próprio, com capital pequeno.
5. O relatório da rodada (T59) separa os eventos por runner; a linha do tempo mostra os halts por
   rejeição e as recusas.

## Decisões registradas (2026-10-21)

1. **Repositório separado**, criado pelo mantenedor: `https://github.com/ctrade-dev/ctrade-strategies`. Prova o "qualquer
   linguagem" do contrato, mantém o ctrade sem código de estratégia e fora da CI dele.
2. **Python** para as cinco, para testar decimal como string e o contrato fora do JVM.
3. **Um processo por estratégia** (decisão 4 da T53), mesmo com código compartilhado.
4. **Ordem de entrega:** tendência e caos primeiro; depois livro, reversão e rompimento.

## Entrega 1 — tendência e caos (2026-10-21)

- Repositório `ctrade-dev/ctrade-strategies` (privado), PR #1 para `develop`. Python 3.13 sem
  dependências de runtime; uma imagem Docker, a estratégia é o argumento (`trend`, `chaos`).
- Regras do contrato num servidor comum (cache por `tickId`, `sequence`, readiness por runner, token,
  erro interno vira HOLD). Cada estratégia só decide.
- Conformidade: os testes validam requisição e resposta contra uma cópia do `openapi.yaml`
  (`contract/SOURCE` guarda o commit de origem; `scripts/sync_contract.py` atualiza).
- Caos: as oito falhas com taxa por tipo (padrão 1%) e o modo "segura a posição".
- **Decisão:** "resposta acima de `max-decision-age`" não é provocável pela app. A idade conta desde o
  tick, e uma resposta lenta a esse ponto já passou do timeout de 250 ms. Para esse caminho, baixar
  `ctrade.strategy.remote.max-decision-age-ms`.
- Fora do contrato, `GET /stats` conta decisões e falhas para o relatório da rodada.
- Passo a passo de subir e cadastrar: T53, "Roteiro — Apps de estratégia da T60".

### Rodada curta na testnet (2026-10-21/22)

Portfolio `soak-t60` (1.000 USDT), runners `SoakTrend` (`maxAllocationPercent` 0.8) e `SoakChaos` (0.4)
em BTCUSDT. Duas janelas: 2026-10-21 23:04–23:40Z (até o ctrade cair) e 2026-10-22 11:45–12:13Z
(retomada pelo boot recovery, runners voltaram `ACTIVE` sozinhos).

- **Cadastro (critério 1):** as duas apps `VERIFIED`, `usable=true`, contrato `1.1.0`.
- **Tendência:** com o limiar de 0,2% não houve sinal em 10 minutos (janela longa de ~30 s, BTC na
  testnet variando ~0,07%/min). Com **0,02%**, 5 idas e voltas completas e uma compra aberta no fim,
  todas `FILLED`. O padrão da app passou para 0,02%.
- **Caos (critério 2):** cada falha apareceu e teve o efeito previsto: `INVALID_RESPONSE` (malformado e
  incoerente), `ERROR_RESPONSE` (500), `UNREACHABLE` (timeout e desconexão), `REJECTED_PRICE_DEVIATION`,
  SELL descartada sem posição, CANCEL de transação inexistente ignorado. Nenhuma virou ordem; o runner
  do caos não tem transação. A guarda de rejeições consecutivas não disparou (as recusas são antes da
  exchange).
- **App fora do ar (critério 3):** caos parado por 3 min. Só o runner dele somou `STRATEGY_UNAVAILABLE`
  (+115); a tendência seguiu avaliando e comprou nesse intervalo.
- **ctrade:** nenhum `ERROR` no Loki; todos os `WARN` vêm do caos. Sem halt automático nem Safe Mode;
  sem divergência de trades ou saldo com a exchange; `reserved` = custo do lote aberto da tendência.
- **Lacuna:** o SELL acima do disponível **de um lote** não foi exercitado, porque o caos sem
  `CHAOS_HOLD_POSITION` nunca tem lote (a recusa vista foi "sem posição"). Ligar o modo na próxima
  rodada.

### Rodadas com as cinco apps (2026-10-27)

**Rodada 1** (portfolio `soak-t60-5`, 13:14–13:56Z; tetos 0,4 × 4 e caos 0,2; caos com
`CHAOS_HOLD_POSITION=true`):

- Cadastro: as cinco `VERIFIED`.
- Achados:
  - **T61:** o `OVERSELL` do caos foi à exchange, a SELL ficou `SUBMITTED`, a conciliação deu `ERROR`
    a cada minuto e o watchdog pôs o portfolio em Safe Mode HALT. SELL com `targetLotId` de outro
    runner também passava.
  - **T62:** `maxOperationAmount` ignorava o custo do lote aberto. As ~1.230 `REJECTED_CAPITAL` da
    rodada, porém, eram do Safe Mode (`RISK_VIOLATION`), não do teto.
  - **`baseUrl` com `localhost`:** o Java resolve para `::1`, o Docker publica só em `127.0.0.1`, e a
    conexão nova trava no Windows além do timeout de 250 ms. O caos ficou 12 minutos inalcançável
    depois de um `TIMEOUT` derrubar a conexão keep-alive. Usar `127.0.0.1`.

**Rodada 2** (portfolio `val-t61-t62`, 16:23–16:41Z, banco limpo, mesmos tetos), validação de T61 e T62:

- `OVERSELL` × 4 viraram `REJECTED_QUANTITY`, sem ordem. Utilização do caos com lote aberto = 0,197.
- Reversão: 2 idas e voltas `FILLED`. Rompimento: BUY `FILLED`, sem saída na janela. Tendência: BUY
  parada no livro. Livro: 13 BUY canceladas e recolocadas, sem preenchimento.
- Caos: todas as falhas apareceram com o efeito previsto (`TIMEOUT`, `DISCONNECT`, `SERVER_ERROR`,
  `MALFORMED`, `INCOHERENT`, `PRICE_DEVIATION`, `CANCEL_UNKNOWN`, `OVERSELL`).
- ctrade: zero `ERROR`, estado operacional `OK` do início ao fim, sem halt nem Safe Mode.
- A rodada seguiu até 18:18Z (~1h55 no total) e terminou com os cinco runners em `HALT` manual:
  - idas e voltas `FILLED`: tendência 9, reversão 17, rompimento 4;
  - caos: 28 `OVERSELL` → `REJECTED_QUANTITY`, sem ordem;
  - zero `ERROR` no Loki, estado operacional `OK` o tempo todo, sem halt automático nem Safe Mode;
  - realizado do portfolio −0,08 USDT.
- **Livro não preencheu nenhuma vez** (66 BUY canceladas e recolocadas). A causa é de calibração da
  app, não do ctrade. A BUY descansa 0,1% abaixo do tick (`BOOK_OFFSET`, ~80 USDT) e é cancelada após
  40 ticks (~90 s), e na testnet o BTC raramente cai 0,1% em 90 s. É o mesmo caso do limiar da
  tendência na rodada de 2026-10-21.
- **Livro recalibrado** (`ctrade-strategies` PR #3): `BOOK_OFFSET` 0,02%, `BOOK_TAKE_PROFIT` 0,03% e
  `BOOK_MAX_ORDER_TICKS` 80. O runner do livro foi retomado sozinho às 18:27Z com a imagem do branch e
  fechou uma ida e volta em ~9 min (BUY 0.00148 @ 80589,75, SELL @ 80613,93, ambas `FILLED`), sem
  `ERROR`. Critério 2 cumprido pelas cinco.

## Fora do escopo

- Estratégias para ganhar dinheiro: o objetivo é exercitar o ctrade; o resultado financeiro do soak não
  é critério.
- Mudança no contrato ou no ctrade. Se uma estratégia precisar de algo que o contrato não dá, vira
  revisão do contrato em task própria.
- Classificação por regime e regulação pelo agente (M2/M3 do roadmap). Os cinco perfis são, porém, um
  bom portfólio inicial para isso: tendência, reversão e rompimento respondem a regimes diferentes.

## Riscos

- **Testnet rasa:** LIMIT pode não preencher ou preencher em saltos; a estratégia de livro precisa
  tolerar ordem que fica parada (cancela e recoloca).
- **Saldo da conta testnet:** muitas idas e voltas consomem taxas; conferir saldo antes de cada rodada
  (decisão 3 da T53).
- **Caos demais esconde bug real:** taxas de falha baixas por padrão (ex.: 2–5% por tipo), registradas no
  relatório da rodada para separar o esperado do inesperado.

## Critérios de aceitação

1. Cada app passa no teste de conformidade contra o `openapi.yaml` e é aceita no cadastro da T54
   (`verification=VERIFIED`).
2. Numa rodada local curta (fase 1 da T53) com as cinco no mesmo portfolio: cada uma gera ao menos uma
   ida e volta completa (ou, a de caos, cada tipo de falha configurado), sem `ERROR` do ctrade além dos
   esperados pela estratégia de caos.
3. Derrubar uma app deixa só o runner dela em HOLD; os demais seguem operando.
4. Instruções para subir e cadastrar as apps no runbook da T53 (passo a passo do soak).

## Fontes

- `docs/strategy-service/CONTRACT.md`, `docs/strategy-service/openapi.yaml`
- `.ai/tasks/T53-testnet-soak.md` (decisão 4, fases 3 e 4), `.ai/tasks/T54-dynamic-strategy-catalog.md`
- `.ai/product-direction.md` (estratégias: só contrato), `.ai/agentic-strategy-roadmap.md` (M3)
