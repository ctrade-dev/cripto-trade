# Contexto do Projeto

Este arquivo e um bootstrap curto para novas sessoes de IA. Ele nao substitui o codigo,
o Blueprint, o Implementation Guide, nem os documentos de arquitetura em `/.ai`.
Use-o para se orientar rapidamente e depois consulte a fonte profunda indicada.

## Proposito

CTrade e uma aplicacao modular de trading de criptomoedas. O sistema conecta em
exchanges, recebe market data, executa estrategias, materializa sinais em ordens,
concilia atualizacoes de execucao, controla capital/portfolio e recupera estado em
boot.

**Quem opera:** o ctrade e operado em producao por um agente de IA, nao por um humano; as
estrategias sao apps externas que so implementam um contrato. Isso orienta API, logs, metricas e
runbook. Leia `/.ai/product-direction.md` antes de especificar task ou desenhar API.

O desenho esperado segue clean architecture e arquitetura hexagonal: regra de
negocio no `core`, calculo de estrategia nas apps externas (contrato), provider schemas nos
`adapter-*`, e composicao/infraestrutura no `spring-application`.

## Mapa de Modulos

- `core/`: dominio, ports, use cases, portfolio/capital, runner, transacoes,
  posicoes/lotes, conciliacao, boot/recovery e regras de negocio.
- `strategy/`: estrategias deterministicas de decisao/calculo. A estrategia SMA
  existente implementa `TradingStrategy`.
- `spring-application/`: Spring Boot, controllers, configuracao, wiring, eventos,
  adapters de infraestrutura, persistencia JDBC, boot orchestration e composicao
  dos adapters.
- `adapter-binance/`: payloads, mappers, assinatura, REST, WebSocket e User Data
  Stream especificos da Binance.
- `adapter-coinbase/`: payloads e processors especificos da Coinbase.
- `adapter-mock/`: runtime local de exchange, market data, simulacao de ordens,
  slippage, fees e cenarios para desenvolvimento/testes.

## Fluxos Centrais

- Market data: adapters recebem mensagens externas, traduzem payloads e publicam
  dados internos para listeners/use cases.
- Runner e estrategia: o runner monta contexto, chama a estrategia e decide se um
  sinal vira intencao de trade conforme politicas do dominio.
- Ordem e conciliacao: intencoes persistem/geram comandos de ordem; updates de
  exchange reconciliam status, fills parciais, fills finais, cancelamentos e
  rejeicoes.
- Portfolio e capital: reservas, liberacoes, execucoes confirmadas, margem,
  saldos globais e dead letters protegem consistencia financeira.
- Boot/recovery: o boot executa fases de sanity check, TTL/zombie detection e
  conciliacao de transacoes/ordens em estado limbo.
- Resiliencia operacional: eventos duplicados, falhas transitorias, DLQ e recovery
  precisam convergir estado sem reaplicar efeito financeiro.

## Invariantes Criticas

Antes de alterar dominio, conciliacao, portfolio, runner, boot ou adapters de
ordem, consulte `docs/PHASE0_INVARIANTS.md`.

Invariantes de alto nivel:

- saldos `available` e `reserved` nao podem ficar negativos;
- reserva de BUY e liberacao de margem nao podem duplicar efeito financeiro;
- transicoes de transacao devem ser monotonicas, sem retorno de terminal para
  transitorio;
- eventos repetidos nao podem reaplicar saldo, posicao, lot ou match;
- SELL nao pode exceder quantidade disponivel do lote;
- lock de SELL deve impedir concorrencia no mesmo alvo;
- `transaction_match` e PnL nao podem duplicar efeito economico;
- boot `WARN_ONLY` nao deve bloquear indevidamente e `FAIL_FAST` deve registrar a
  fase real da falha.

## Fontes de Verdade

- Regras para agentes: `/.ai/README.md`.
- Proposito, operador (IA) e direcao do produto: `/.ai/product-direction.md`.
- Fronteiras de modulo: `/.ai/architecture.md`.
- Padroes de implementacao: `/.ai/coding-standards.md`.
- Validacao minima: `/.ai/validation.md`.
- Git workflow: `/.ai/git-workflow.md`.
- Flow maps: `/.ai/flows/README.md`.
- Seguranca de refactor/mudanca de responsabilidade: `/.ai/change-safety.md`.
- Heuristicas de review: `/.ai/review.md` e `/.ai/agents/code-review-agent.md`.
- Resolver comentarios de PR: `/.ai/skills/pr-comment-resolver/SKILL.md`.
- Criacao de PR: `/.ai/skills/pr-creator/SKILL.md`.
- Visao profunda de capital/execucao/resiliencia: `docs/BLUEPRINT.md`.
- Guia detalhado de implementacao: `docs/IMPLEMENTATION_GUIDE.md`.
- Invariantes e matriz de cenarios: `docs/PHASE0_INVARIANTS.md`.
- Roadmap de testes: `docs/TESTING_ROADMAP.md`.
- Backlog operacional/testnet: `docs/tasks/BACKLOG.md`.
- Runbook operacional: `docs/OPERATIONS_RUNBOOK.md`.
- Referencia de configuracao: `docs/CONFIGURATION_REFERENCE.md`.

## Estado Atual Conhecido

- O projeto e Gradle multi-modulo com Java 21.
- A aplicacao Spring usa Spring Boot 3.5.4.
- O runtime atual esta configurado para PostgreSQL via Spring Data JDBC, Flyway e
  driver Postgres. O `docker-compose.yml` sobe `ctrade-postgres`.
- O README historico ainda pode citar H2; para persistencia atual, prefira
  `spring-application/src/main/resources/application.yml` e
  `spring-application/build.gradle`.
- O cache Gradle local padrao para agentes e `GRADLE_USER_HOME=.gradle-local`,
  via `./scripts/gradle-run.ps1`.
- A trilha Binance testnet tem itens concluidos e pendentes em `docs/tasks/BACKLOG.md`;
  nao copie esse estado para ca, consulte o backlog.

## Como Comecar uma Tarefa

- Implementacao: leia `/.ai/README.md`, este arquivo, `architecture.md`,
  `coding-standards.md`, `validation.md`, o flow map aplicavel em `flows/` e
  depois o doc profundo do fluxo afetado.
- Review: leia `review.md`, `agents/code-review-agent.md`, o diff e os docs do
  fluxo tocado; publique achados priorizando bug, regressao e fronteira
  arquitetural.
- Resolver comentarios de PR: leia `skills/pr-comment-resolver/SKILL.md`, busque
  threads com contexto completo, implemente fixes rastreaveis e prepare respostas
  para cada thread enderecada.
- Criacao de PR: leia `git-workflow.md` e `skills/pr-creator/SKILL.md`, valide o
  escopo, use branch Git Flow criada a partir de `develop`, commite apenas
  arquivos relacionados, faca rebase sobre `develop`, suba a branch e abra PR
  draft para `develop`.
- Refactor ou mudanca de modulo: leia `change-safety.md`, identifique o dono da
  responsabilidade antes de editar e valide todos os consumidores afetados.
- Mudanca em adapter de exchange: mantenha payload/schema no adapter, traduza na
  borda e compile o adapter mais consumidores diretamente afetados.
- Mudanca em core/strategy: trate como area de risco alto; prefira teste de
  comportamento quando houver efeito financeiro, idempotencia ou transicao de
  estado.
- Documentacao: atualize `/.ai` quando mudar regra canonica; atualize `docs/`
  quando mudar blueprint, operacao, roadmap ou guias profundos.
