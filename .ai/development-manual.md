# Manual de Desenvolvimento

Este e o manual operacional de desenvolvimento do ctrade. Ele traduz a arquitetura
aplicada no codigo em regras verificaveis e e **obrigatorio para toda demanda de
implementacao, refactor, review ou proposta arquitetural**.

Ele nao substitui os documentos base; ele os torna concretos:

- `architecture.md` diz **quem e dono** de cada coisa (principio).
- `coding-standards.md` diz **como escrever** (diretriz).
- este manual diz **onde cada peca vai, com qual forma, seguindo qual padrao ja
  existente**, com exemplos reais do codigo e anti-padroes ja rejeitados.

Se este manual divergir do codigo atual, o codigo prevalece como fato e o manual deve
ser corrigido no mesmo PR. Se divergir de `architecture.md`, `architecture.md`
prevalece.

---

## 1. Regra-mestra: disposicao de responsabilidades

A regra mais importante do projeto e **cada responsabilidade morar no modulo dono**.
Conveniencia local nunca justifica mover uma decisao para fora do seu dono. Antes de
escrever qualquer linha, responda:

1. **Isto e uma decisao de negocio?** (o que fazer, quando, quanto, se e permitido)
   -> `core`.
2. **Isto e calculo de decisao de trade a partir de dados explicitos?** -> fora do ctrade: uma app
   de estrategia pelo contrato `docs/strategy-service/` (T65; o ctrade nao embute estrategia).
3. **Isto e conhecimento de um provider especifico?** (payload, assinatura, endpoint,
   transporte preferido, filtros da exchange) -> `adapter-<provider>`.
4. **Isto e mecanica tecnica generica?** (thread, retry, backoff, scheduler, HTTP,
   JDBC, transacao de banco, Micrometer, Discord, Spring) -> `spring-application`.
5. **Isto e montagem?** (instanciar use case, injetar ports, ligar transacao, ativar
   adapter por config) -> `spring-application/config`.

Se a resposta for "um pouco de cada", a classe esta errada: separe a decisao (core) da
mecanica (spring) e do provider (adapter), ligadas por um port.

### 1.1 Tabela de decisao rapida

| Preciso de... | Dono | Forma | Exemplo real |
|---|---|---|---|
| Regra de quando aceitar/rejeitar um sinal | `core` | policy package-private do use case | `RunnerSignalPolicy`, `CapitalReservationPolicy` |
| Estado e transicoes de uma entidade | `core/domain` | metodo na entidade, lanca excecao em transicao invalida | `StrategyRunner.halt()`, `canAcceptSignals()` |
| Falar com a exchange | `core` define port; `adapter-*` implementa | port outbound em `core/ports/outbound/exchange` | `ExchangeOrderPort` -> `BinanceOrderAdapter` |
| Escolher transporte (WS API, REST, fallback) | `adapter-*` | dentro do adapter que implementa o port | `BinanceOrderAdapter` (T15) |
| Saber se a exchange suporta X | `adapter-*` declara; `core` consulta | `ExchangeAdapterDescriptor.hasX()/x()` | T16, T28 |
| Retry/backoff/scheduling | `spring-application` | implementacao de port do core | `LinearBackoffReconnectionStrategy` implementa `ReconnectionStrategyPort` (T14) |
| Reacao de negocio a falha tecnica | `core` | handler/use case acionado pelo port | `CriticalConnectionFailureHandler`, `ConnectionLostOrderDispatchGuard` (T14) |
| Fronteira transacional | `core` declara o ponto; `spring` implementa | metodo abstrato `transactional*` ou interface executor | `ProcessTradeSignalUseCase`, `ConciliationOrderUpdateExecutor` |
| Metrica/alerta | `core` define port com vocabulario de dominio; `spring` implementa | port em `core/ports/outbound/metrics` ou `notification` | `SignalMetricsPort` -> `MicrometerSignalMetricsAdapter` |
| Valor configuravel | `spring` le; `core` recebe por construtor | `@ConfigurationProperties` -> primitivo/record | `RunnerBootPhase3Properties` -> `RunnerBootRecoveryUseCase` |
| Endpoint HTTP | `spring/controller` delega a port inbound | controller fino + `GlobalExceptionHandler` | `RunnerController` -> `QueryRunnerPort` |
| Persistir agregado | `core` define port; `spring/infrastructure/persistence` implementa | `Jdbc*RepositoryAdapter` + entity + mapper | `JdbcGlobalBalanceRepositoryAdapter` |
| Decidir BUY/SELL/HOLD/CANCEL | app de estrategia, fora do ctrade | contrato `docs/strategy-service/openapi.yaml` | apps de `ctrade-dev/ctrade-strategies` |
| Falar com estrategia fora do processo | `adapter-strategy-remote` (rede) + `core` (falha = HOLD, catalogo) | `StrategyEvaluationPort`, `RegisterRemoteStrategiesPort` | `RemoteStrategyClient` + `RegisterRemoteStrategiesUseCase` |
| Marcar posicao a mercado / equity do portfolio | `core` | `LatestPricePort` (preco), `Position.getUnrealizedPnl` | `PortfolioMarkToMarket` (core) + `InMemoryLatestPriceRepository` (spring) |
| Saida decidida pelo core sem a estrategia (stop do lote, liquidacao, fechar posicao) | `core` | `TradeIntentFactory.liquidationSignal` + ramo SELL normal; cancelamento por `CancelSignalHandler.cancelResting` | `PositionProtection` + `PositionProtectionHandler`, `SafeModeHandler` |
| Normalizar quantidade/preco para filtros | `core` decide **quando**; adapter sabe **como** | `OrderQuantityNormalizerPort` | `OrderNormalizer` (core) + `BinanceOrderNormalizer` (adapter) |

### 1.2 Anti-padroes ja rejeitados neste projeto

Nao reintroduza. Cada um foi corrigido com custo:

| Anti-padrao | Onde aconteceu | Correcao |
|---|---|---|
| Core com `ScheduledExecutorService`, backoff e loop de retry | `ConnectionFailedHandler` (T14) | Port `ReconnectionStrategyPort` no core, implementacao no spring |
| Port de transporte HTTP no core | `HttpClientPort` (T14) | Detalhe HTTP movido para o adapter |
| Spring decidindo ordem de transporte de um provider | `OrderDispatchAdapter` (T15) | Selecao dentro de `BinanceOrderAdapter` |
| Repositorio organizado por interface com `instanceof` e 8 mapas | `InMemoryExchangeAdapterRepository` (T16) | `ExchangeAdapterDescriptor` por exchange |
| Controller decidindo status HTTP por `message().contains("not found")` | `DeadLetterController` (T27) | Excecao de dominio + `GlobalExceptionHandler` |
| Colapsar use cases em metodos privados de um orquestrador | `RunBootSequenceUseCase` virou God Object (PR #73) | Classes separadas, reposicionadas em `usecase.boot.phase2` |
| Efeito colateral escondido (metrica registrada dentro de handler) | fluxo de sinal (T23) | Handlers retornam `BuyOutcome`/`SellOutcome`; registro centralizado no orquestrador |
| Design documentado como se estivesse implementado | safety buffer (T36), mailbox, drawdown, Safe Mode (T39) | Doc deve marcar o que ainda nao existe |
| Dois caminhos para a mesma operacao | `Position` com construtor por preco + `openAtCost`, `addQuantity` + `addQuantityAtCost`; `OrderDataDto` com construtor legado que preenche `feeAsset = null` (T46) | `Position.open`/`addQuantity` por custo; `OrderDataDto` so com construtor canonico (T47, ver 1.3) |

### 1.3 Um fluxo, um caminho (poucas bifurcacoes)

Segunda regra em importancia, depois da disposicao de responsabilidades. O sistema deve ter um
fluxo bem definido e poucas derivadas. Funcoes que fazem quase a mesma coisa acabam divergindo e
geram comportamentos diferentes onde deveria haver um so.

Antes de criar funcao, metodo, construtor, factory, modo ou flag nova:

1. **Procure o caminho existente.** Se a operacao ja existe, estenda-a ou troque a assinatura.
   Nao crie uma variante ao lado (`fooAtCost`, `fooV2`, `fooWithX`, sobrecarga de conveniencia).
2. **Mudou o modelo, migre os chamadores.** Ao trocar o dado canonico (ex.: preco -> custo),
   atualize todos os usos e remova a forma antiga no mesmo PR. Construtor/sobrecarga "legado"
   que preenche um default silencioso so com aprovacao explicita e com prazo de remocao no backlog.
3. **Bifurcacao so por regra de negocio real.** Cada `if`/`switch`/enum novo precisa corresponder
   a um caso que acontece de fato e estar escrito na spec. Caso hipotetico ("outra exchange um dia
   cobra assim") nao ganha ramo proprio: falha explicita ou log, e a task futura cria o ramo.
4. **Mesmo resultado, mesma funcao.** Dois fluxos que calculam a mesma coisa (custo, quantidade
   vendavel, status) chamam o mesmo colaborador. Nao reimplemente o calculo localmente.
5. **Sem codigo morto.** Metodo sem chamador em producao sai no mesmo PR; teste nao justifica API
   publica que producao nao usa.
6. **Modos e flags de configuracao** so quando os dois valores sao usados de verdade (perfil,
   cenario, exchange). Modo mantido "por compatibilidade" sem consumidor e removido.

Na duvida entre criar e reaproveitar, reaproveite e registre a escolha no PR.

---

## 2. Mapa de modulos e pacotes

Dependencias permitidas (sempre para dentro):

```
spring-application ──> core
spring-application ──> strategy ──> core
spring-application ──> adapter-* ──> core
core ──> (nada do projeto; so JDK, slf4j/lombok e anotacoes minimas)
```

`core` nunca importa `com.marmitt.application`, `com.marmitt.binance`,
`com.marmitt.mock`, `com.marmitt.coinbase` ou `com.marmitt.strategy`. Adapters nunca
importam outro adapter nem o spring-application.

### 2.1 `core` (`com.marmitt.core`)

| Pacote | Conteudo | Regra |
|---|---|---|
| `domain/<agregado>` | Entidades e value objects (`Portfolio`, `GlobalBalance`, `StrategyRunner`, `Transaction`, `Position`, `ClientOrderId`, `Symbol`) | Comportamento dentro da entidade; transicao invalida lanca excecao; sem anotacao de persistencia |
| `application/usecase/<area>` | Use cases que implementam ports inbound | Um use case por intencao de negocio |
| `application/usecase/<area>/<fluxo>` | Colaboradores internos de um use case (policy, handler, factory, assembler, evaluator) | package-private quando so o use case do mesmo pacote usa; ver 3.2 |
| `application/reaction` | Reacoes a eventos de dominio (`ExecutionConfirmedReaction`, `MarginReleasedReaction`) | Consequencia de um fluxo, nao intencao de ator externo; idempotentes |
| `application/handler` | Handlers de mensagem/conexao acionados por ports inbound | Decidem reacao de negocio; nao fazem transporte |
| `application/listener` | Implementacoes de `PriceUpdateListener`/`OrderUpdateListener` | So roteiam para o port do use case |
| `application/exception` | Excecoes de controle de fluxo interno de use case | Ex.: `CapitalReservationRejectedException` |
| `exceptions` | Excecoes de dominio visiveis para fora do core | Mapeadas para HTTP no `GlobalExceptionHandler` |
| `ports/inbound/<area>` | O que o mundo externo pode pedir ao core | Nome `<Verbo><Objeto>Port` |
| `ports/outbound/<area>` | O que o core precisa do mundo externo | Nome pela capacidade de negocio, nao pela tecnologia |
| `dto/<area>` | Contratos estaveis de entrada/saida do core (`request`/`response`, `command`, contextos) | Records imutaveis; validacao no compact constructor |
| `enums` | Vocabulario de dominio | Nunca enum de provider |

### 2.2 Estrategias (fora do ctrade)

O ctrade nao tem modulo de estrategia (T65). Cada estrategia e uma app separada que implementa o
contrato; o ctrade so a cadastra (T54) e a chama. Ver secao 8.

### 2.3 `spring-application` (`com.marmitt.application.spring`)

| Pacote | Conteudo |
|---|---|
| `config/core` | `@Configuration` que instancia use cases do core como `@Bean` e liga transacoes (`RunnerConfig`, `PortfolioConfig`, `BootConfig`, ...) |
| `config/exchange` | Ativacao condicional e montagem de cada adapter (`BinanceAdapterConfiguration`, `MockAdapterConfiguration`) |
| `config/notification`, `config/logback` | Infra transversal |
| `controller`, `controller/mapper` | HTTP fino; mapeamento request HTTP -> DTO do core |
| `web` | `ApiError`, `GlobalExceptionHandler` |
| `handler` | `@EventListener`/`@TransactionalEventListener` que delegam ao core |
| `event` | Eventos Spring de transporte (mensagem raw recebida) |
| `bootstrap` | Orquestracao de boot Spring, watchdogs agendados, `*Properties` |
| `infrastructure/persistence/{adapter,entity,mapper,repository}` | JDBC |
| `infrastructure/exchange`, `infrastructure/connection` | Implementacoes de ports de transporte/dispatch |
| `adapter` | Adapters tecnicos (OkHttp WebSocket, Spring events, Discord) |
| `metrics` | Micrometer implementando ports de metrica do core |
| `repository` | Registros em memoria (`InMemoryStrategyRepository`, `InMemoryExchangeAdapterRepository`) |
| `deadletter` | Codec e reprocessamento de DLQ |
| `service` | Fachadas finas para controllers que nao sao use case do core; **nunca regra de negocio** |

### 2.4 `adapter-*` (`com.marmitt.<provider>`)

| Pacote | Conteudo |
|---|---|
| raiz | Classes que implementam ports do core (`BinanceOrderAdapter`, `BinanceMarketStreamAdapter`) e config do provider |
| `request`, `response`, `event` | Payloads do provider (nunca saem do adapter) |
| `processor/receive`, `processor/send` | Parsing de mensagem recebida e montagem de mensagem enviada |
| `rest`, `auth`, `filters`, `userdata`, `trade`, `boot` | Detalhes do provider por capacidade |

Adapters sao Java puro (sem Spring): recebem dependencias por construtor e sao montados
em `spring-application/config/exchange` (exchanges) ou `config/strategy` (estrategias remotas).

`adapter-strategy-remote` (`com.marmitt.strategyservice`) e o adapter do contrato de estrategias fora
do processo (T41): `wire` (formato de rede, mapeamento nos dois sentidos, validacao da decisao) e o
cliente `RemoteStrategyClient`. Transporte HTTP pela propria porta `StrategyServiceHttpPort`,
implementada com OkHttp no spring. As apps de `ctrade-dev/ctrade-strategies` sao a referencia do
contrato, com testes de conformidade contra a copia do `openapi.yaml`.

---

## 3. Padroes do `core`

### 3.1 Use case

- Implementa exatamente um port inbound (`ProcessTradeSignalUseCase implements ProcessTradeSignalPort`).
- Recebe somente ports outbound e valores por construtor. Sem `@Component`,
  `@Transactional`, `@Value` ou qualquer anotacao Spring.
- Javadoc de classe explica a intencao, a sequencia do fluxo e as decisoes nao obvias,
  e referencia a secao do IG/Blueprint ou a task (`@see IG Secao 5.1`, `(T23 G2)`).
- Falha isolada por unidade de trabalho: um runner com erro nao derruba os demais do
  tick (`ProcessTradeSignalUseCase.execute`).

### 3.2 Colaboradores e encapsulamento por pacote

Use cases grandes sao decompostos em colaboradores com nome de papel:

- `*Policy` — decide se algo e permitido (`RunnerSignalPolicy`, `CapitalReservationPolicy`).
- `*Handler` — executa um ramo do fluxo (`BuySignalHandler`, `SellSignalHandler`, `CancelSignalHandler`).
- `*Factory` — constroi objetos de dominio (`TradeIntentFactory`).
- `*Assembler` — monta contexto/DTO a partir de varias fontes (`RunnerContextAssembler`).
- `*Evaluator`, `*Normalizer`, `*Service` (interno) — calculo/resolucao focada.

Regras:

1. Colaboradores moram num subpacote com nome do fluxo (`usecase/runner/processsignal`,
   `usecase/runner/orderconciliation`, `usecase/boot/phase2`).
2. Quando instanciados pelo proprio use case no construtor, sao **package-private** e
   nao viram beans.
3. Quando o Spring precisa instancia-los (outro modulo), ficam `public`, e o isolamento
   e estrutural: o `@Bean` mora no config do consumidor (ex.: beans de `phase2` em
   `BootConfig`).
4. **Para "esconder" uma classe, mova-a de pacote; nao a colapse em metodos privados
   de outra.** Cada colaborador mantem testes proprios.
5. Handlers que produzem efeito observavel retornam um resultado tipado
   (`BuyOutcome`, `SellOutcome`) em vez de registrar metrica/efeito escondido; o
   orquestrador decide o que registrar.

### 3.3 Fronteira transacional sem framework no core

O core decide **onde** a atomicidade e necessaria; o spring decide **como**. Dois padroes
existentes:

- **Template method:** o use case e `abstract` e declara `transactionalXxx(...)`; o corpo
  transacional fica em `protected xxx(...)`. O config cria uma subclasse anonima que
  envolve o corpo com `TransactionTemplate` (`RunnerConfig.createProcessTradeSignal`).
- **Interface executor:** o core define uma interface (`ConciliationOrderUpdateExecutor`)
  cujos metodos representam unidades atomicas; o config implementa com
  `TransactionTemplate` e, se necessario, lock (`Striped<Lock>` por `clientOrderId`, T19).

Regras: nunca `@Transactional` no core; leituras de estado que fecham corrida com o kill
switch sao refeitas **dentro** da transacao (`persistBuyAndReserve` re-le o runner);
`@Transactional` em adapter de persistencia so para uma operacao atomica isolada.

### 3.4 Ports

- **Inbound:** `<Verbo><Objeto>Port` em `ports/inbound/<area>`, assinatura em DTOs do core.
- **Outbound:** nomeado pela capacidade de negocio (`OrderDispatchPort`,
  `SignalMetricsPort`, `ErrorNotificationPort`, `ReconnectionStrategyPort`), nunca pela
  tecnologia (`HttpClientPort`, `MicrometerPort` sao proibidos).
- Interfaces pequenas, orientadas ao caso de uso. Capacidades opcionais de exchange
  entram no `ExchangeAdapterDescriptor` (`hasX()` + `x()`), nao em ports avulsos.
- "Nao implementado" = `UnsupportedOperationException` (ou `UnsupportedCapabilityException`
  via descriptor). Use cases ja tratam isso graciosamente. Nao criar
  `NotImplementedException`.

### 3.5 DTOs do core

- Records imutaveis em `core/dto/<area>`; `request`/`response` para ports inbound,
  `command` para comandos outbound (`OrderCancelCommand`).
- Invariantes do contrato validadas no compact constructor com
  `IllegalArgumentException` e factories nomeadas por intencao
  (`StrategyOutputDto.hold/buy/buyAt/sellLot/cancel`).
- Nenhum nome, enum ou formato de provider. Traducao acontece no adapter.
- Debito conhecido: `Symbol` usa `@JsonCreator/@JsonValue` (core depende de
  `jackson-annotations`). Nao expandir esse uso.

### 3.6 Eventos e reacoes

- O core publica eventos de dominio por `EventPublisherPort` (records em `core/dto/events`).
- O spring escuta com `@TransactionalEventListener(AFTER_COMMIT)`, aplica retry/DLQ e
  **delega** para uma `*Reaction` do core (`CapitalEventListener` ->
  `ExecutionConfirmedReaction`). O listener nao decide regra de negocio.
- Reacoes sao idempotentes por chave de negocio (`CapitalEventIdempotencyPort`).

### 3.7 Excecoes

- `core/exceptions`: excecoes de dominio que cruzam a borda (`RunnerNotFoundException`,
  `ConcurrentPositionLockException`, `IllegalStateTransitionException`).
- `core/application/exception`: controle de fluxo interno de use case.
- Toda excecao nova que chega a um controller ganha mapeamento no
  `GlobalExceptionHandler`.

---

## 4. Padroes do `spring-application`

### 4.1 Composicao (`config/core`)

- Cada use case do core vira `@Bean` num `@Configuration` da area, retornando o **port**
  (`ProcessTradeSignalPort`) ou o tipo concreto quando precisa ser injetado por mais de
  uma interface (documentar o motivo, como em `RunnerConfig.conciliationOrderUpdate`).
- Properties entram como valores primitivos/records no construtor do core.
- Nada de `if` de negocio em config: config escolhe implementacao e liga dependencias.

### 4.2 Controllers

- Prefixo `/api/...`; delegam imediatamente a um port inbound.
- Sem `try/catch` para decidir status: excecoes sobem e o `GlobalExceptionHandler` monta
  `ApiError(timestamp, status, error, message, path)`.
- Mapeamento HTTP <-> DTO do core em `controller/mapper`.
- Endpoints administrativos em `/api/admin/**`, protegidos por `AdminApiKeyInterceptor`.

### 4.3 Persistencia

- `Jdbc<Agregado>RepositoryAdapter implements <Agregado>RepositoryPort` em
  `infrastructure/persistence/adapter`.
- `<Agregado>Entity` (Spring Data JDBC) + `<Agregado>EntityMapper` com `toDomain`/`toEntity`
  estaticos + `<Agregado>JdbcRepository`.
- Operacoes financeiras concorrentes sao atomicas em SQL (CAS: `reserveAtomic` retorna
  linhas afetadas), nunca read-modify-write em memoria.
- Log `trace` com tempo: `[REPO] <agregado>.<operacao>(args) - Xms` (`RepoTiming`).
- Schema so por migration Flyway nova (`db/migration/V<n>__descricao.sql`); nunca editar
  migration ja aplicada.

### 4.4 Infra tecnica

- Threads, executores, schedulers, retry e clientes HTTP vivem aqui e implementam ports
  do core quando o core precisa acionar a mecanica.
- Metricas Micrometer implementam ports do core; nome de metrica e tags documentados no
  flow map e em `/.ai/monitoring-spec.md`.

---

## 5. Padroes dos adapters de exchange

- Implementam ports outbound do core e traduzem payload na borda: nada de
  `Binance*Response` fora do adapter.
- Conhecimento de provider fica no adapter: selecao de transporte, assinatura HMAC,
  filtros (`stepSize`/`tickSize`/`minNotional`), formato de `clientOrderId`.
- Capacidades expostas via `DefaultExchangeAdapterDescriptor` montado em
  `config/exchange`; capacidade ausente = `has*()` falso + accessor lanca.
- Ativacao por propriedade (`@ConditionalOnExpression` por credenciais, perfil
  `testnet`); MOCK e o default de desenvolvimento.
- Mudanca em adapter compila o adapter e o `spring-application`.
- O `adapter-mock` simula exchange para testes; comportamento novo deve ser
  backward-compatible por construcao (ex.: resting so com preco de referencia semeado, T38).

---

## 6. Regras financeiras inegociaveis

Antes de tocar dominio, conciliacao, portfolio, runner, boot ou ordem, leia
`docs/PHASE0_INVARIANTS.md`. Em codigo:

- `BigDecimal` sempre; `RoundingMode` explicito; nunca `double`/`float`.
- Transacao local persistida **antes** do envio da ordem.
- Valor normalizado antes do persist: o que e persistido, reservado e enviado e identico.
- BUY so despacha apos reserva aceita; SELL so apos lock de posicao.
- Transicoes monotonicas: terminal nunca volta para transitorio.
- Eventos repetidos nao reaplicam saldo, posicao, lote ou match (chave de idempotencia).
- Quantidade/preco arredondados contra o trader sao bug (ver T37).
- Qualquer mudanca de aritmetica de capital exige teste de comportamento, nao so compile.

---

## 7. Observabilidade e logs

- Logs em ingles, prefixo do fluxo + pares `chave=valor`:
  `priceUpdate: HOLD signal for runner={}`, `kill-switch: runner halted - id={}`.
- MDC: `correlationId` no tick; `transactionId` so na conciliacao (T26). Limpar MDC no `finally`.
- Log de criacao de transacao so quando persistida e despachada (evitar "transacao fantasma").
- Nunca logar segredo, assinatura ou payload com credencial.
- Toda decisao com efeito financeiro tem outcome observavel (metrica com tag de resultado
  mutuamente exclusivo).

---

## 8. Estrategias

- O ctrade define o contrato (`docs/strategy-service/CONTRACT.md` e `openapi.yaml`), nunca a
  implementacao. Estrategia nova e app nova (em `ctrade-dev/ctrade-strategies` ou outra), cadastrada
  por `POST /api/admin/strategies` (T54).
- Dentro do ctrade, a estrategia remota e uma `TradingStrategy` (`RemoteTradingStrategy`, T41)
  registrada no `InMemoryStrategyRepository` pelo catalogo. Nenhuma outra entra em producao (T65).
- Falha da estrategia (indisponivel, resposta invalida, excecao) vira HOLD no `StrategySignalEvaluator`.
- Testes de integracao usam dubles in-process em
  `spring-application/src/test/.../testsupport/strategy` (cenarios da T35 e a SMA de teste): cada teste
  registra o seu no repositorio. Dubles seguem as mesmas regras da decisao: funcao de
  `(StrategyInputDto, StrategyContextDto)` + warm-up chaveado por runner, sem I/O.

---

## 9. Testes

| Tipo | Onde | Ferramenta | Quando |
|---|---|---|---|
| Unitario de dominio/colaborador | `core/src/test` no mesmo pacote | JUnit 5 + Mockito (mocks de port) | Toda regra nova do core |
| Invariantes | `core/.../domain/**/*InvariantsTest` | JUnit 5 | Mudanca em entidade financeira |
| Unitario de adapter/infra | modulo do adapter ou `spring-application/src/test` | JUnit 5, MockWebServer | Parsing, mapeamento, mecanica |
| Integracao | `spring-application/.../bootstrap/*IntegrationTest` estendendo `AbstractIntegrationTest` | Spring Boot + Testcontainers Postgres + MOCK | Fluxo com persistencia, conciliacao, boot |
| E2E de cenario | `*E2ETest` + `ScenarioStrategyE2ETestSupport` | idem | Ciclo completo de estrategia |

Regras: teste no mesmo pacote para acessar package-private; sem `sleep` fixo (espera por
convergencia com deadline); isolamento por truncate + reset do MOCK; reusar suportes
existentes (`MockOrderOverrideIntegrationTestSupport`) antes de criar novos.

Comandos (sempre pelo wrapper):

```powershell
./scripts/gradle-run.ps1 -q :core:test
./scripts/gradle-run.ps1 -q :spring-application:test
./scripts/gradle-run.ps1 -q :adapter-binance:test
```

---

## 10. Processo de entrega

1. **Spec antes de codigo.** Toda demanda nao trivial tem `/.ai/tasks/T<n>-<slug>.md`
   (descricao, contexto tecnico com arquivos reais, solucao proposta, riscos, criterios de
   aceitacao, fontes) e linha em `docs/tasks/BACKLOG.md`. Decisoes tomadas durante a
   implementacao sao registradas na propria spec ("Decisao registrada (data)").
2. **Branch** conforme `git-workflow.md` (`feature/`, `bugfix/`, `refactor/`, `docs/`...).
3. **Commits** pequenos, em portugues, no formato `tipo(T<n>): descricao`
   (`feat(T38): mock honra ordens que descansam no book`). Correcoes de review:
   `fix(T<n>): ... (review PR #<n>)`.
4. **Validacao** pelo escopo de `validation.md`; risco financeiro exige teste.
5. **Documentacao** no mesmo PR: flow map, spec da task, backlog, `/.ai` se regra mudou.
   Se nao houver impacto, declarar.
6. **PR draft para `develop`**, com validacao executada. Nunca merge automatico.
7. **Review:** resolver achados procedentes em codigo de producao; em artefato sem
   consumidor, corrigir so o que afeta transacao/contrato e responder o resto como adiado.
8. Excecao: mudanca **somente de documentacao** pode ir direto em `develop`.

---

## 11. Protocolo de trabalho delegado

Quando o trabalho for conduzido por agente com autonomia (o mantenedor delega a execucao):

**O agente decide sozinho:**

- onde cada responsabilidade mora (este manual);
- desenho tecnico, nomes, decomposicao em colaboradores, testes;
- ordem de execucao dentro do backlog priorizado;
- correcao de bug sem mudanca de regra financeira.

**O agente para e pede decisao ao mantenedor quando:**

- a mudanca altera **parametro ou regra financeira/de risco** (limites, buffers,
  defaults de stop, sizing) — registrar como "decisao de negocio pendente" na spec;
- altera **contrato publico** ja consumido (API HTTP, contrato do strategy service apos
  ter consumidor, schema de banco com dado existente de forma destrutiva);
- envolve **credenciais reais, ambiente de producao ou dinheiro real**;
- exige excecao a este manual (registrar em `/.ai/decisions/` se aprovada);
- uma acao e destrutiva ou irreversivel (reescrita de historico, drop de dado).

**Ao fechar cada entrega**, reportar: o que mudou, onde (por modulo dono), validacao
executada, impacto documental, riscos residuais e proxima task recomendada.

---

## 12. Checklist antes de abrir PR

- [ ] Cada classe nova esta no modulo e pacote dono (secao 1 e 2).
- [ ] Nenhum import de framework/provider/strategy no `core`.
- [ ] Nenhuma decisao de negocio em controller, listener, config, adapter ou infra.
- [ ] Ports nomeados por capacidade de negocio; DTOs do core sem formato externo.
- [ ] Fronteira transacional declarada no core e implementada no spring.
- [ ] Invariantes financeiras preservadas e testadas quando tocadas.
- [ ] Efeito observavel com metrica/log no padrao.
- [ ] Testes no nivel certo; validacao executada e reportada.
- [ ] Spec, backlog, flow map e docs atualizados ou "sem impacto" declarado.
- [ ] Nenhum anti-padrao da secao 1.2 reintroduzido.
- [ ] Nenhuma variante paralela de funcao/construtor/factory; caminho antigo migrado e removido (1.3).
- [ ] Cada bifurcacao nova (`if`, enum, modo, flag) corresponde a caso real descrito na spec (1.3).
- [ ] Nenhum metodo publico sem chamador em producao.

---

## 13. Debitos arquiteturais conhecidos

Nao usar como precedente; corrigir quando a task tocar a area:

- `core.domain.Symbol` com anotacoes Jackson.
- Nomes de transporte no core (`dto/websocket`, `WebSocketPort`, eventos `WebSocket*`) —
  roadmap da T14 propoe nomes por dominio (`ExchangeStream*`).
- `adapter-coinbase` incompleto (sem ordem/consulta).
- Design no IG/Blueprint sem implementacao: auditoria em T39.
- Estado de warm-up de estrategia por instancia, nao por runner (T40).
- Rejeicoes sinteticas (`expirePending*`, `OrderDispatchAdapter.toRejectedOrder`) informam
  `OrderType.LIMIT` fixo no `OrderDataDto`, mesmo para a liquidacao MARKET (T45).
