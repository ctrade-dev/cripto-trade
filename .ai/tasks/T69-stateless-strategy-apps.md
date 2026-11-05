# T69 — Apps de estratégia stateless (contrato 2.0)

**Complexidade:** Alta
**Responsável:** Claude
**Dependências:** T40 (contrato 1.1), T41 (estratégia remota), T60 (apps de soak), T68 (envio bloqueado)
**Status:** Spec para revisão do mantenedor

---

## Descrição

Regra definida pelo mantenedor em 2026-11-05:

> Apps de estratégia são stateless. Cada interação usa apenas as informações da própria interação. Se o
> contrato não bastar, expande-se o contrato ou criam-se endpoints de apoio no ctrade, mesmo que isso gere
> um ping-pong de requests entre a estratégia e o ctrade.

A consequência operacional: **uma decisão vale só para o tick dela**. Se não for executada, por qualquer motivo,
é descartada e a estratégia é consultada de novo no tick seguinte. O ctrade não guarda decisão para depois e a
estratégia não presume que o sinal virou ordem.

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

- **Regra stateless:** a decisão é função só do request. Sai a seção de warm-up por `(strategyRef, runnerId)`.
  Contadores de telemetria fora do contrato (`/stats`) continuam permitidos, porque não influenciam a decisão.
- **Decisão por tick:** escrever a regra acima e listar os motivos de descarte conhecidos, inclusive envio
  bloqueado.
- **`GET /v1/meta`:** novo campo `historyTicks` (inteiro, de 0 a 1000): quantos ticks a estratégia precisa.
- **`input.priceHistory`:** `[{price, timestamp}]`, do mais antigo para o mais novo, terminando no tick atual, com
  até `historyTicks` itens. Pode vir mais curto logo depois de um boot; com histórico insuficiente, a estratégia
  responde `SHOULD_HOLD`.
- **`OpenLot.peakPrice`:** maior preço visto desde a abertura do lote.
- **`sequence` sai do request.** Sem estado não há o que proteger. `tickId` fica para rastreio, e a app deixa de
  guardar decisões por `tickId`.
- **`/v1/health`:** `ready` deixa de depender de warm-up. Conferir na implementação quem consome esse campo.
- **Major do contrato:** o catálogo passa a aceitar só 2.x, sem caminho paralelo para 1.x (manual 1.3). Testnet
  e apps sobem juntas.

**Por que no request e não por endpoint de apoio:** o `/evaluate` tem timeout de 250 ms. Enviar o que a estratégia
declarou evita idas e voltas no caminho de cada tick. Endpoints de apoio continuam permitidos pela regra e entram
quando surgir dado grande ou raro; nenhum é necessário nesta task.

### ctrade

- **`core`:** port de histórico de preços; o use case do tick registra cada tick recebido e monta `priceHistory`
  conforme o `historyTicks` da estratégia. `RunnerContextAssembler` preenche `peakPrice`.
- **`spring-application`:** buffer circular em memória por `(exchange, symbol)`. O registro roda na thread de market
  data, antes da mailbox, e não pode bloquear (T42).
- **`adapter-strategy-remote`:** tipos de wire, mapper, leitura de `historyTicks` em `/v1/meta`.
- **Catálogo:** guarda `historyTicks` da verificação da estratégia.
- **`SHOULD_CANCEL` com envio bloqueado:** descartado e contado como `DISPATCH_BLOCKED`, como BUY e SELL. Os ramos
  de Safe Mode e proteção do lote seguem tentando no tick seguinte.
- **Pós-restart do ctrade:** buffer e picos começam vazios.
  - O histórico se refaz com os ticks seguintes, e a estratégia fica em `SHOULD_HOLD` até ter o que precisa.
  - O pico recomeça em `max(entryPrice, preço atual)`. O trailing stop fica menos protetor até o preço subir de
    novo; a proteção do lote (T44) não muda.
  - **Antes de produção:** decidir se o pico deve ser persistido. Registrar no backlog.

### `ctrade-strategies`

- Sincronizar o contrato 2.0 e remover do servidor o estado por runner: `RunnerSlot`, cache por `tickId`,
  `sequence`, `new_state`, `accept` e `warmed_ticks`.
- `SoakTrend`, `SoakReversion`, `SoakBreakout`: janela a partir de `priceHistory`, `historyTicks` em `/v1/meta`.
  `SoakBreakout` usa `peakPrice` para o trailing stop.
- `SoakBook`: sem memória. O prazo da ordem vem de `input.timestamp − requestedAt`, com
  `BOOK_MAX_ORDER_SECONDS` no lugar de `BOOK_MAX_ORDER_TICKS` (padrão 120 s, equivalente aos 80 ticks de ~1,5 s).
  Passado o prazo, repete `SHOULD_CANCEL` enquanto a ordem estiver em `pendingOrders`; o ctrade suprime repetições
  por 30 s (`OrderDispatchAdapter`).
- `SoakChaos`: só atualiza o contrato. O sorteio não é memória da operação.
- Teste de conformidade: mesmo request em app recém-criada e em app com tráfego anterior dá a mesma decisão
  (exceto `SoakChaos`).

## Fora de escopo

- Endpoints de apoio (nenhum necessário agora).
- Carregar histórico da Binance no boot.
- Persistir `peakPrice`.
- Dublês de teste in-process do ctrade: continuam podendo contar ciclos, porque não são apps de estratégia. O manual
  (seção 8) passa a distinguir os dois casos.

## Riscos

- **Tamanho do request:** cresce com `historyTicks` em um caminho que roda a cada tick. Medir
  `strategy.remote.latency` e `strategy.remote.failures` na rodada curta.
- **Mudança de série de preços:** hoje a janela da app só contém os ticks avaliados pelo runner, porque a mailbox
  descarta os intermediários. O buffer contém todos os ticks, então o mesmo tamanho de janela pode cobrir menos tempo
  e mudar a frequência de sinais. Conferir na rodada curta se os limiares da T60 continuam gerando operações.
- **Simultaneidade:** ctrade e apps precisam subir juntos, porque o major muda. Em testnet isso é aceitável, e o
  `round-start.sh` já recria o ambiente.
- **Dado incoerente no `peakPrice`:** o cálculo fica no ctrade, então precisa de teste de abertura, novo pico e lote
  encerrado (entrada removida).

## Critérios de aceite

- [ ] `CONTRACT.md` e `openapi.yaml` na versão 2.0, com a regra stateless e a decisão por tick.
- [ ] `DISPATCH_BLOCKED` documentado em `.ai/flows/runner-signal-processing.md`.
- [ ] Manual, seção 8, atualizado com a regra.
- [ ] Nenhuma app guarda estado que influencie a decisão, com teste de conformidade.
- [ ] Reiniciar uma app com lote aberto não muda a decisão seguinte e não exige warm-up.
- [ ] E2E: `SHOULD_CANCEL` com envio bloqueado não envia nada, conta `DISPATCH_BLOCKED` e sai quando a conexão volta.
- [ ] Testnet: queda provocada com cancelamento vencido do `SoakBook`; o pedido sai depois da reconexão.
- [ ] Testnet: latência e falhas do `/evaluate` sem piora relevante com `historyTicks` configurado.
- [ ] Rodada de 72h aprovada, com checkpoints (T53, decisão de 2026-11-05).

## Validação

`./gradlew test` no ctrade; suíte e conformidade em `ctrade-strategies`; rodada curta na VPS com queda provocada;
rodada de 72h com checkpoints.

## Ordem de entrega

1. PR do ctrade (contrato 2.0, histórico, `peakPrice`, cancelamento bloqueado, checkpoints da rodada).
2. PR do `ctrade-strategies` (depende do contrato do passo 1).
3. Deploy conjunto na VPS e rodada curta com queda provocada.
4. Rodada de 72h com checkpoints em 3h, 8h e 24h.
