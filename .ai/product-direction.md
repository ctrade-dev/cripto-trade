# Direcao do Produto

Para que o ctrade existe, quem o opera e como isso orienta cada decisao de design. Leia antes de
especificar uma task, sugerir arquitetura ou desenhar API. Regras de modulo e implementacao continuam
em `architecture.md` e `development-manual.md`; este documento diz **para quem** elas servem.

## Proposito

O ctrade e um **sistema de registro de transacoes e de gerenciamento de estrategias, operado por uma
IA**. O mantenedor tem outro trabalho e nao vai operar o sistema em producao: o operador do dia a dia
e um agente (Claude), que cadastra e acompanha estrategias, verifica consistencia, reage a alertas,
diagnostica falhas e mantem o codigo.

Consequencia direta: **o principal consumidor da API admin, dos logs, das metricas e do runbook e um
agente**. Uma funcionalidade so esta pronta quando um agente, sem humano por perto, consegue entender o
estado, agir com seguranca e confirmar o efeito.

## Divisao de responsabilidades

| Quem | Responde por |
|---|---|
| **ctrade (core)** | Verdade financeira: ordens, conciliacao, capital, posicoes, PnL, recovery. Todas as protecoes: protecao do lote (T44), Safe Mode e seus gatilhos (T45, T25, T43), limites pre-trade (T50), liquidacao do PANIC_SELL (T48). |
| **Estrategia (app externa)** | Uma unica pergunta por tick: comprar, vender, cancelar ou esperar (`BUY`/`SELL`/`CANCEL`/`HOLD`). Nada alem disso. |
| **Agente operador** | Ciclo de vida de estrategias e runners, verificacao periodica, resposta a alertas, manutencao. |
| **Mantenedor** | Decisoes de negocio, revisao e merge de PR. |

## Estrategias externas: so contrato

- Cada estrategia e uma app independente, em **qualquer linguagem e arquitetura**, stateful ou
  stateless. O ctrade define **apenas o contrato e o protocolo** (`docs/strategy-service/CONTRACT.md`,
  `openapi.yaml`); nunca como a app e construida. O ctrade nao embute nenhuma estrategia (T65); as
  apps de `ctrade-dev/ctrade-strategies` sao a referencia de conformidade.
- A estrategia recebe o que precisa para decidir e responde a decisao. Protecoes **nao** passam pela
  estrategia: o core decide sozinho, inclusive com a estrategia fora do ar.
- Capacidade nova para estrategias entra como **revisao do contrato** (como a T40), nunca como
  exigencia de implementacao.

### Visao e estado atual

| Visao | Estado |
|---|---|
| Cada estrategia num processo proprio | Minimo entregue (T40/T41): contrato, `RemoteTradingStrategy`, HOLD quando a estrategia falha. |
| Estrategias cadastradas por API | Entregue (T54): catalogo persistido em `/api/admin/strategies`, token por estrategia, historico. |
| Estrategia nova sem deploy nem restart do ctrade | Entregue (T54). |
| IA regulando as estrategias ativas | Sem task. Roadmap em `agentic-strategy-roadmap.md` (marcos M0-M3). |
| IA propondo estrategias novas | Sem task. Roadmap em `agentic-strategy-roadmap.md` (marcos M4-M6). |

## Principios de operabilidade por IA

Valem para toda API, log, metrica e procedimento novo ou alterado:

1. **Estado autoexplicativo.** Respostas trazem estado, motivo, horarios e vinculos (ex.: quais
   runners usam uma estrategia). Toda mudanca devolve o estado resultante, nunca so `200`.
2. **Erros com codigo estavel.** Alem da mensagem, cada recusa traz um codigo legivel por maquina no
   envelope `ApiError`, para o agente reagir sem interpretar texto.
3. **Idempotencia.** Repetir uma operacao ja aplicada nao falha nem duplica efeito; conflito real e
   recusado com codigo proprio.
4. **`reason` obrigatorio e historico.** Toda mudanca de estado registra o motivo e fica consultavel,
   para o agente (ou o mantenedor) reconstruir o que aconteceu e por que.
5. **Contrato da API publicado.** Endpoints descritos no OpenAPI do springdoc, com campos, estados e
   codigos de erro.
6. **Runbook executavel.** `docs/OPERATIONS_RUNBOOK.md` descreve procedimentos como passos com a
   resposta esperada em cada um, nao como explicacao para humano.
7. **Verificavel de fora.** Inconsistencias (capital, transacoes presas, DLQ, divergencia com a
   exchange) devem ser detectaveis por consulta, sem acesso ao banco nem leitura de codigo.

Desde a T55: toda a API exige `X-Admin-Key`; todo comando exige `X-Reason` e fica em
`/api/admin/operator-actions`; toda recusa traz `code`; e `GET /api/admin/operational-state` e a
consulta unica de consistencia. Ao criar algo novo, seguir esse padrao.

## Sequencia de maturidade

1. **Testnet primeiro e por muito tempo.** Soak, falhas controladas e estrategias remotas na Binance
   testnet (T53), validadas no fim de cada rodada pela linha do tempo de eventos (T59).
2. **Producao so depois.** Perfil de producao (`application-prod.yml`) e gestao de segredos entram
   apenas depois de estabilidade comprovada em testnet. Nao priorizar antes.
3. Dados de testnet sao descartaveis: inconsistencia historica de testnet nao e remediada; limpa-se e
   recomeca. Producao nao entra nessa regra.

## Ideias em espera

- **MOCK como app externo**, no mesmo papel do app de estrategia. Adiado para depois do soak: os E2E
  controlam o MOCK dentro do processo e um container a mais na CI traz instabilidade. Ver
  `docs/tasks/BACKLOG.md`. Absorvida pela exchange de replay (bloco 14 de
  `agentic-strategy-roadmap.md`).
