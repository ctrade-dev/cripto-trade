# T69 — Apps de estratégia stateless (contrato 2.0)

**Complexidade:** Alta
**Responsável:** Claude
**Dependências:** T40 (contrato 1.1), T41 (estratégia remota), T60 (apps de soak), T68 (envio bloqueado)
**Status:** Em andamento (falta a rodada de 72h)

---

## Descrição

Regra definida pelo mantenedor em 2026-11-05:

> Apps de estratégia são stateless. Cada interação usa apenas as informações da própria interação. Se o
> contrato não bastar, expande-se o contrato.
>
> O caminho é unilateral: a estratégia nunca chama o ctrade, ela só abre portas para o ctrade chamar.

Consequências:

- **Uma decisão vale só para o tick dela.** Se não for executada, por qualquer motivo, é descartada e a estratégia é
  consultada de novo no tick seguinte. O ctrade não guarda decisão para depois e a estratégia não presume que o sinal
  virou ordem.
- **Todo dado chega por chamada do ctrade.** Se um dia for preciso mais de uma chamada na mesma decisão, o ctrade é
  quem inicia todas. Isso substitui a ideia inicial de endpoints de apoio no ctrade.
- **Identidade imutável.** Mudou metadado da estratégia (nome, versão ou requisitos), é outra estratégia: novo
  `strategyId` e novo runner. O contrato já define "uma versão = um `strategyId`", e o runner já guarda `strategyId`
  imutável.
- **Uma configuração, uma app.** Os parâmetros são da app, fixados ao subir o processo, e o ctrade não os conhece.
  Outra configuração é outra app, com outro `strategyId`, para outro runner. Decisão do mantenedor: parâmetros no
  runner levariam regra do ctrade para dentro da app. Isso não fere a regra stateless, porque a configuração não muda
  entre interações.

## Problema

O contrato 1.1 permite estado de warm-up na app, e as apps de soak guardam mais do que isso:

| App | Estado entre interações | Efeito |
|---|---|---|
| `SoakBook` | `cancel_requested` | Cancelamento emitido com o envio bloqueado nunca é repetido. A ordem fica aberta além do prazo, com capital reservado, e o runner não cria ordens novas |
| `SoakBook` | Ticks de cada ordem | O prazo da ordem depende de quantos ticks a app viu |
| `SoakTrend`, `SoakReversion` | Janela de preços | Reiniciar a app exige novo warm-up |
| `SoakBreakout` | Janela de preços e pico do lote | Reiniciar a app perde o pico e exige novo warm-up |

Do lado do ctrade, `SHOULD_CANCEL` com o envio bloqueado é contado como `CANCEL`, embora nada tenha sido enviado,
e o contrato não avisa que uma decisão pode não ser executada.

## Solução proposta

### Contrato 2.0 (`docs/strategy-service/`)

O contrato define que mudança incompatível muda o caminho, então as rotas passam de `/v1` para `/v2`.

- **Regra stateless e caminho unilateral:** a decisão é função só do request, e a app não faz chamadas de saída. Sai
  a seção de warm-up por `(strategyRef, runnerId)`. Contadores de telemetria fora do contrato (`/stats`) continuam
  permitidos, porque não influenciam a decisão.
- **Decisão por tick:** escrever a regra acima e listar os motivos de descarte conhecidos, inclusive envio
  bloqueado.
- **`GET /v2/meta`:** novo bloco `requirements`, hoje com um item:
  `{"requirements": {"priceHistory": {"ticks": 20}}}`, com `ticks` de 0 a 1000.
- **`input.priceHistory`:** `[{price, timestamp}]`, do mais antigo para o mais novo, terminando no tick atual, sempre
  com exatamente os `ticks` declarados. O ctrade não chama a estratégia enquanto não tiver esse histórico.
- **`OpenLot.peakPrice`:** maior preço visto desde a abertura do lote.
- **`sequence` sai do request.** Sem estado não há o que proteger. `tickId` fica para rastreio, e a app deixa de
  guardar decisões por `tickId`.
- **`/v2/health`:** `ready` deixa de depender de warm-up. Conferir na implementação quem consome esse campo.
- **Major do contrato:** o catálogo passa a aceitar só 2.x, sem caminho paralelo para 1.x (manual 1.3). Testnet
  e apps sobem juntas.

**Por que `requirements` em `/meta`:** o ctrade já chama essa rota ao verificar a estratégia, antes de ela operar.
Não precisa de endpoint novo, e o `/evaluate` não gasta seus 250 ms com idas e voltas.

### ctrade

- **`core`:** port de histórico de preços; o use case do tick registra cada tick recebido e monta `priceHistory`
  conforme os requisitos da estratégia. `RunnerContextAssembler` preenche `peakPrice`.
- **Espera por histórico:** com menos ticks que o declarado, a estratégia não é chamada e o tick conta em
  `signal.evaluated.total` como `WAITING_HISTORY`. A proteção do lote e o Safe Mode rodam antes e não dependem disso.
- **`spring-application`:** buffer circular em memória por `(exchange, symbol)`, da fonte do tick, porque um runner pode
  receber market data de outra exchange autorizada. O registro roda na thread de market data, antes da mailbox, e não
  pode bloquear (T42).
- **`adapter-strategy-remote`:** tipos de wire, mapper, rotas `/v2`, leitura de `requirements`.
- **Catálogo:**
  - grava os requisitos no cadastro e os compara a cada verificação;
  - requisito que mudou sem novo `strategyId` é divergência: a verificação falha e a estratégia fica desabilitada,
    como já acontece com nome e versão;
  - requisito desconhecido ou acima do teto também falha a verificação, com o motivo no evento.
- **`SHOULD_CANCEL` com envio bloqueado:** descartado e contado como `DISPATCH_BLOCKED`, como BUY e SELL. Os ramos
  de Safe Mode e proteção do lote seguem tentando no tick seguinte.
- **Pós-restart do ctrade:** buffer e picos começam vazios.
  - O histórico se refaz com os ticks seguintes, e o runner fica em `WAITING_HISTORY` até ter o que precisa.
  - O pico recomeça em `max(entryPrice, preço atual)`. O trailing stop fica menos protetor até o preço subir de
    novo; a proteção do lote (T44) não muda.
  - **Antes de produção:** decidir se o pico deve ser persistido. Registrar no backlog.

### `ctrade-strategies`

- Sincronizar o contrato 2.0 e remover do servidor o estado por runner: `RunnerSlot`, cache por `tickId`,
  `sequence`, `new_state`, `accept` e `warmed_ticks`.
- `SoakTrend`, `SoakReversion`, `SoakBreakout`: janela a partir de `priceHistory`, `requirements` em `/v2/meta`.
  `SoakBreakout` usa `peakPrice` para o trailing stop.
- `SoakBook`: sem memória. O prazo da ordem vem de `input.timestamp − requestedAt`, com
  `BOOK_MAX_ORDER_SECONDS` no lugar de `BOOK_MAX_ORDER_TICKS` (padrão 120 s, equivalente aos 80 ticks de ~1,5 s).
  Passado o prazo, repete `SHOULD_CANCEL` enquanto a ordem estiver em `pendingOrders`; o ctrade suprime repetições
  por 30 s (`OrderDispatchAdapter`).
- `SoakChaos`: só atualiza o contrato. O sorteio não é memória da operação.
- Teste de conformidade: mesmo request em app recém-criada e em app com tráfego anterior dá a mesma decisão
  (exceto `SoakChaos`).

## Fora de escopo

- **Dados de mercado de longo prazo (candles) e carga de histórico da Binance.** Abrir task quando surgir estratégia
  que precise de mais de 1000 ticks (~25 min) ou de candles. Caminho previsto: ela declara em `requirements`, o ctrade
  busca, mantém e envia.
- Persistir `peakPrice`.
- Dublês de teste in-process do ctrade: continuam podendo contar ciclos, porque não são apps de estratégia. O manual
  (seção 8) passa a distinguir os dois casos.

## Riscos

- **Tamanho do request:** cresce com `priceHistory` em um caminho que roda a cada tick. Medir
  `strategy.remote.latency` e `strategy.remote.failures` na rodada curta.
- **Mudança de série de preços:** hoje a janela da app só contém os ticks avaliados pelo runner, porque a mailbox
  descarta os intermediários. O buffer contém todos os ticks, então o mesmo tamanho de janela pode cobrir menos tempo
  e mudar a frequência de sinais. Conferir na rodada curta se os limiares da T60 continuam gerando operações.
- **Simultaneidade:** ctrade e apps precisam subir juntos, porque o major muda. Em testnet isso é aceitável, e o
  `round-start.sh` já recria o ambiente.
- **Dado incoerente no `peakPrice`:** o cálculo fica no ctrade, então precisa de teste de abertura, novo pico e lote
  encerrado (entrada removida).

## Critérios de aceite

- [x] `CONTRACT.md` e `openapi.yaml` na versão 2.0, com a regra stateless e a decisão por tick.
- [x] `DISPATCH_BLOCKED` documentado em `.ai/flows/runner-signal-processing.md`.
- [x] Manual, seção 8, atualizado com a regra.
- [x] Nenhuma app guarda estado que influencie a decisão nem faz chamada de saída, com teste de conformidade.
- [x] Requisito alterado sem novo `strategyId`, desconhecido ou acima do teto falha a verificação com o motivo.
- [x] Runner sem histórico suficiente não chama a estratégia, e `priceHistory` sempre chega completo.
- [x] Reiniciar uma app com lote aberto não muda a decisão seguinte e não exige warm-up.
- [x] E2E: `SHOULD_CANCEL` com envio bloqueado não envia nada, conta `DISPATCH_BLOCKED` e sai quando a conexão volta.
- [x] Testnet: queda provocada com cancelamento vencido do `SoakBook`; o pedido sai depois da reconexão (rodada curta 70:
  `USER_DATA` fora por 49 s, atravessando o prazo de 120 s. A ordem seguiu `SUBMITTED`, `CANCEL` não contou durante a queda
  e a ordem terminou `CANCELED` logo depois da reconexão).
- [ ] Testnet: latência e falhas do `/evaluate` sem piora relevante com `priceHistory`.
- [ ] Rodada de 72h aprovada, com checkpoints (T53, decisão de 2026-11-05).

## Validação

`./gradlew test` no ctrade; suíte e conformidade em `ctrade-strategies`; rodada curta na VPS com queda provocada;
rodada de 72h com checkpoints.

## Ordem de entrega

1. PR do ctrade (contrato 2.0, histórico, `peakPrice`, cancelamento bloqueado, checkpoints da rodada).
2. PR do `ctrade-strategies` (depende do contrato do passo 1).
3. Deploy conjunto na VPS e rodada curta com queda provocada.
4. Rodada de 72h com checkpoints em 3h, 8h e 24h.
