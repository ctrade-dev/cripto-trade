# Persistencia E Adapters

## Objetivo

Mapear como o estado duravel dos agregados principais sai do core por portas e
chega a infraestrutura Spring Data JDBC, mappers e migrations.

Use este mapa para bugs ou features envolvendo:

- estado de `Portfolio`, `GlobalBalance`, `StrategyRunner`, `Position`,
  `Transaction`, `TransactionMatch` ou DLQ;
- locks, operacoes atomicas, idempotencia e concorrencia;
- alteracoes em schema, migrations, repositories ou mappers;
- investigacao de divergencia entre dominio e banco.

## Fronteira Arquitetural

O core define contratos em `core/.../ports/outbound/repository`. A
infraestrutura Spring implementa esses contratos em
`spring-application/.../infrastructure/persistence`.

O dominio nao deve depender de:

- entidades JDBC;
- annotations de persistencia;
- queries SQL;
- detalhes de migration.

O limite entre dominio e banco e feito por adapters e mappers:

- adapter implementa a porta;
- mapper converte entidade JDBC <-> dominio;
- repository JDBC executa `CrudRepository` e queries anotadas;
- migration define a forma persistida.

## Fluxo Principal

1. Um use case do core chama uma porta outbound.
2. O adapter Spring recebe dominio ou parametros de consulta.
3. O adapter converte dominio para entidade via mapper quando grava.
4. O repository JDBC executa save/query/update.
5. O adapter converte entidade para dominio ao retornar.
6. O caller do core continua operando apenas com tipos de dominio/DTOs do core.

Transacoes ficam na camada Spring quando o limite operacional exige atomicidade,
por exemplo adapters com `@Transactional`, listeners com `REQUIRES_NEW` ou
servicos transacionais que encapsulam portas inbound.

## Agregados Persistidos

### Portfolio

Contrato: `PortfolioRepositoryPort`.

Implementacao:

- `JdbcPortfolioRepositoryAdapter`;
- `PortfolioJdbcRepository`;
- `PortfolioEntityMapper`;
- `PortfolioEntity`;
- tabela `portfolios`.

Responsabilidades principais:

- buscar por id, nome, simbolo ou listar todos;
- registrar portfolio evitando duplicidade por id ou nome;
- manter o core isolado do schema.

### GlobalBalance

Contrato: `GlobalBalanceRepositoryPort`.

Implementacao:

- `JdbcGlobalBalanceRepositoryAdapter`;
- `GlobalBalanceJdbcRepository`;
- `GlobalBalanceEntityMapper`;
- `GlobalBalanceEntity`;
- tabela `global_balances`.

Ponto critico:

- `reserveAtomic(portfolioId, amount)` executa `UPDATE` condicional que move
  saldo de `available_balance` para `reserved_balance` apenas quando existe saldo
  suficiente.
- Saldo insuficiente nao e excecao: o contrato retorna `false`.
- O `UPDATE` incrementa `version`: confirmacao e liberacao leem o saldo e gravam com
  `@Version`, entao uma gravacao baseada em leitura anterior a reserva falha com
  `OptimisticLockingFailureException` e e repetida pelo `CapitalEventListener`, em vez de
  sobrescrever a reserva (F31).
- Concorrencia financeira deve continuar passando por esse ponto de
  serializacao.

### StrategyRunner E Filhos

Contrato: `StrategyRunnerRepositoryPort`.

Implementacao:

- `JdbcStrategyRunnerRepositoryAdapter`;
- `StrategyRunnerJdbcRepository`;
- `RunnerPositionJdbcRepository`;
- `RunnerTransactionJdbcRepository`;
- `RunnerTransactionMatchJdbcRepository`;
- `StrategyRunnerEntityMapper`;
- entidades `StrategyRunnerEntity`, `RunnerPositionEntity`,
  `RunnerTransactionEntity`, `RunnerTransactionMatchEntity` e
  `RunnerMarketDataSourceEntity`;
- tabelas `strategy_runners`, `runner_market_data_sources`, `positions`,
  `transactions` e `transaction_matches`.

Responsabilidades principais:

- salvar runner, position, transaction e match;
- buscar runners por portfolio, simbolo, exchange e short code;
- buscar transacoes por `clientOrderId` para roteamento e idempotencia;
- buscar transacoes em voo para boot/recovery/watchdog;
- aplicar lock pessimista em positions quando o fluxo precisa serializar fills
  ou venda;
- executar operacoes atomicas de transaction+position lock e transaction+match.

Pontos criticos:

- `tryLockPositionForSell` usa update condicional e aceita estado ja lockado pela
  mesma transacao como sucesso idempotente.
- positions `OPEN` ou `CLOSING` precisam de `openedByTransactionId`.
- `equity_snapshots` (V14, T43): amostra por portfolio a cada avaliacao do breaker; PK
  `(portfolio_id, sampled_at)`; o proprio monitor remove amostras com mais de 24h.
- `findActivePositionsByPortfolioId` junta `positions` e `strategy_runners` e inclui `CLOSING`
  (lote travado por venda em voo ainda e do portfolio).
- `transactions.order_type` (V13, T45) `NOT NULL DEFAULT 'LIMIT'`; o dominio sempre informa.
- `portfolios.safe_mode_*` + tabela `safe_mode_changes` (V13, T45): a mudanca de nivel e um
  `UPDATE ... WHERE version = :lida` seguido do INSERT da auditoria na mesma transacao
  (`saveSafeModeChange`); versao divergente vira `SafeModeTransitionException` (409).
- `positions.cost_basis` (V12, T46) e anulavel de proposito: o dominio sempre grava; linha sem valor
  cai no fallback `quantity x average_price` em vez de assumir custo zero.
- `saveAtomicTransactionAndMatch` mantem status de transaction e match no mesmo
  limite transacional.
- `transaction_matches` sao registros de execucao; nao trate como estado
  mutavel de lifecycle.
- `runner_status_changes` (V19, T59): toda transicao de estado do runner exige uma
  `RunnerStatusCause` (origem `MANUAL`/`AUTOMATIC`/`BOOT` + motivo); a entidade guarda as transicoes
  ainda nao gravadas e `save(runner)` as insere na mesma transacao do runner
  (`drainStatusChanges`). Um caminho so: nenhum chamador grava historico a parte.

### DeadLetter

Contrato: `DeadLetterEntryRepositoryPort`.

Implementacao:

- `JdbcDeadLetterEntryRepositoryAdapter`;
- `DeadLetterEntryJdbcRepository`;
- `DeadLetterEntryEntityMapper`;
- `DeadLetterEntryEntity`;
- tabela `dead_letter_entries`.

Responsabilidades principais:

- salvar entrada;
- listar pendencias por portfolio/runner;
- detectar DLQ aberta por portfolio, runner ou identidade;
- suportar boot/recovery e operacao manual de DLQ.

Detalhes operacionais ficam no mapa `dead-letter-replay.md`.

### Capital Event Ledger

Contrato: `CapitalEventIdempotencyPort`.

Implementacao:

- `JdbcCapitalEventIdempotencyRepositoryAdapter`;
- tabela `capital_event_ledger`.

Ponto critico:

- o adapter faz `INSERT ... ON CONFLICT DO NOTHING`;
- retorno `true` significa primeiro processamento;
- retorno `false` significa evento duplicado e deve impedir novo efeito
  financeiro.

### Historico do boot e linha do tempo (T59)

Contratos: `BootRunRepositoryPort` e `EventTimelineRepositoryPort`.

Implementacao:

- `JdbcBootRunRepositoryAdapter`: tabela `boot_runs` (V19), uma linha por boot terminado, gravada
  pelo `RunBootSequenceUseCase`; falha ao gravar so loga (o boot segue).
- `JdbcEventTimelineRepositoryAdapter`: somente leitura; `UNION ALL` ordenado de `safe_mode_changes`,
  `runner_status_changes`, transacoes SELL com `reasoning` iniciando em
  `PositionProtection.EXIT_REASONING_PREFIX`, `dead_letter_entries`, `operator_actions`,
  `remote_strategy_events` e `boot_runs`. Nao copia dado: cada evento e lido da tabela de origem.

## Repositorios Em Memoria

Nem todo `RepositoryPort` e persistencia duravel.

Registries como exchange adapters, strategies, listeners e WebSocket connection
repository podem ser implementados em memoria para runtime. Nao use esses
repositorios como fonte historica de estado de dominio.

Ao investigar estado persistido, priorize os adapters em
`spring-application/.../infrastructure/persistence`.

## Migrations

Schema duravel fica em `spring-application/src/main/resources/db/migration`.

Migrations relevantes:

- `V1__create_initial_schema.sql`: schema inicial dos agregados principais,
  DLQ, indices e FKs circulares resolvidas por `ALTER TABLE`;
- `V2__add_opened_by_transaction_id.sql` a
  `V6__enforce_opened_by_transaction_in_positions.sql`: ajustes de positions e
  matches;
- `V7__create_capital_event_ledger.sql`: ledger de idempotencia de capital;
- `V8__add_runner_id_to_dead_letter_entries.sql`: escopo opcional de runner na
  DLQ e indices para pendencias;
- `V9__add_updated_at_to_transactions.sql`: suporte a consultas por
  `updated_at` em recovery/watchdog.
- `V19__create_runner_status_changes_and_boot_runs.sql` (T59): historico de transicoes do runner,
  historico do boot e indices por instante nas tabelas lidas pela linha do tempo.

## Invariantes

- O core depende de portas, nunca de entidades ou repositories JDBC.
- Toda coluna nova que altera estado de dominio precisa refletir em migration,
  entity, mapper e adapter/repository quando aplicavel.
- Operacoes financeiras concorrentes devem preservar os pontos atomicos
  existentes (`reserveAtomic`, locks de position, ledger de idempotencia).
- Queries hot path devem respeitar os indices existentes ou adicionar migration
  explicita.
- Nao transforme repositorios em memoria em fonte duravel de estado.
- Mudancas em mappers devem preservar reconstrucao completa de agregados.

## Validacao Recomendada

- `./scripts/gradle-run.ps1 -q :core:test`
- `./scripts/gradle-run.ps1 -q :spring-application:test`
- Para mudanca focada em persistencia de runner:
  `./scripts/gradle-run.ps1 -q :spring-application:test --tests "*Runner*IntegrationTest"`
- Para mudanca de capital:
  `./scripts/gradle-run.ps1 -q :spring-application:test --tests "*Capital*"`
- Para mudanca de DLQ:
  `./scripts/gradle-run.ps1 -q :spring-application:test --tests "*DeadLetter*"`

## Fontes

- Ports em `core/src/main/java/com/marmitt/core/ports/outbound/repository`.
- Adapters, repositories, entities e mappers em
  `spring-application/src/main/java/com/marmitt/application/spring/infrastructure/persistence`.
- Migrations em `spring-application/src/main/resources/db/migration`.
