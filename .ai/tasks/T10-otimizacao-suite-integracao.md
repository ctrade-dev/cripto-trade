# T10 — Otimização da suíte de integração

**Complexidade:** Média  
**Responsável:** Codex  
**Dependências:** nenhuma  
**Status:** Concluído (#117)

---

## Descrição

Os testes de integração levam 4+ minutos localmente e 7+ minutos no CI. A análise identificou três causas independentes que podem ser corrigidas sem alterar a lógica de negócio validada pelos testes.

**Meta:** reduzir o tempo total da suíte para menos de 90 segundos localmente.

---

## Diagnóstico

### Problema 1 — `awaitStableFilledState` / `awaitStableSellState` rodam até o deadline completo

`MockOrderOverrideIntegrationTestSupport.java:358–407`

Ambos os métodos verificam estabilidade de estado (ausência de drift) após convergência, mas continuam executando até o deadline inteiro mesmo quando o estado já está estável:

```java
while (Instant.now().isBefore(deadline)) {   // nunca sai cedo
    BuyStateSnapshot current = readBuyState(...);
    if (converged == null) {
        if (isExpectedFilledState(current)) converged = current;
    } else if (!isExpectedFilledState(current)) {
        throw new AssertionError("drift detected");
    }
    sleep(50L);
}
```

O fill chega em ~120ms (`INITIAL_CALLBACK_DELAY_MS`). Com `WAIT_TIMEOUT = 10s`, o método continua por mais ~9.88s verificando drift. `OrderLifecycleMockIntegrationTest` usa `WAIT_TIMEOUT = 25s` — garantindo 25s por chamada independente de quando o fill chegou.

**Impacto estimado:** ~50–60% do tempo total da suíte.

---

### Problema 2 — `@DirtiesContext(AFTER_EACH_TEST_METHOD)` em todas as classes de integração

Presente em:
- `MockOrderOverrideIntegrationTestSupport.java:54`
- `RunnerBootRecoveryIntegrationTest.java:56`
- `OrderLifecycleMockIntegrationTest.java:46`
- `ProcessTradeSignalMockIntegrationTest.java:48`
- `OrderTerminationConciliationIntegrationTest.java:50`
- `ConcurrentSellLockIntegrationTest.java:52`
- `CapitalDeadLetterReplayIntegrationTest.java:59`

O contexto Spring é reconstruído após cada método de teste. O container Postgres permanece (é `static final`), mas todos os beans são reinicializados: Flyway re-executa, Hikari reconecta o pool, todos os listeners são recriados.

O motivo original do `@DirtiesContext` é resetar o estado interno do `MockExchangeAdapter` (overrides registrados, cenários). O banco já é limpo pelo `@BeforeEach cleanDatabase()`. A recriação de contexto está sendo usada como atalho para reset do mock.

**Impacto estimado:** ~47 recriações × ~2.5s = ~117s (~30% do tempo total).

---

### Problema 3 — Container Postgres independente por classe de teste

`MockOrderOverrideIntegrationTestSupport.java:82–87` e `RunnerBootRecoveryIntegrationTest.java:67–71` declaram cada um seu próprio `static final PostgreSQLContainer`. São containers distintos iniciados em sequência.

**Impacto estimado:** N classes × ~5–10s de startup = ~20–40s.

---

## Escopo técnico

### Correção 1 — Limitar janela de estabilidade nos métodos `awaitStable*`

Substituir o loop de "roda até deadline" por "converge + verifica estabilidade por janela fixa":

```java
// MockOrderOverrideIntegrationTestSupport.java
protected static final long STABILITY_WINDOW_MS = 500L;

protected BuyStateSnapshot awaitStableFilledState(UUID openedByTransactionId,
                                                  Duration timeout,
                                                  BigDecimal expectedQuantity) {
    Instant deadline = Instant.now().plus(timeout);
    BuyStateSnapshot converged = null;
    Instant stabilityDeadline = null;

    while (Instant.now().isBefore(deadline)) {
        BuyStateSnapshot current = readBuyState(openedByTransactionId);
        if (converged == null) {
            if (isExpectedFilledState(current, expectedQuantity)) {
                converged = current;
                stabilityDeadline = Instant.now().plusMillis(STABILITY_WINDOW_MS);
            }
        } else if (!isExpectedFilledState(current, expectedQuantity)) {
            throw new AssertionError("Filled buy state drift detected after convergence for "
                    + "openedByTransactionId=" + openedByTransactionId + " current=" + current);
        } else if (Instant.now().isAfter(stabilityDeadline)) {
            return converged;
        }
        sleep(FILLED_STABILITY_POLL_INTERVAL_MS);
    }
    if (converged == null) {
        throw new AssertionError("Timeout waiting FILLED buy convergence for "
                + "openedByTransactionId=" + openedByTransactionId);
    }
    return converged;
}
```

Aplicar o mesmo padrão em `awaitStableSellState`.

**Resultado esperado:** cada await converge em ~120ms + 500ms de estabilidade = ~620ms ao invés de 10–25s.

---

### Correção 2 — Remover `@DirtiesContext` e adicionar reset explícito do mock

Adicionar método `reset()` ao `MockExchangeAdapter` que limpa todos os overrides e estado interno. Chamar no `@BeforeEach` junto com o TRUNCATE do banco.

```java
// MockExchangeAdapter.java — novo método
public void reset() {
    // limpar overrides registrados, contadores, estado interno
}

// MockOrderOverrideIntegrationTestSupport.java
@BeforeEach
protected void cleanDatabase() {
    jdbcTemplate.execute("TRUNCATE TABLE portfolios CASCADE");
    jdbcTemplate.execute("TRUNCATE TABLE capital_event_ledger");
    getMockExchangeAdapter().reset();
}
```

Remover `@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)` de todas as classes listadas no diagnóstico.

**Atenção:** antes de remover o `@DirtiesContext` de uma classe, verificar se há algum bean com estado mutável além do `MockExchangeAdapter` que precise ser resetado entre testes. Se houver, adicionar reset explícito para esse bean também no `@BeforeEach`.

---

### Correção 3 — Compartilhar o container Postgres entre todas as classes

Criar uma classe base `AbstractIntegrationTest` com o container declarado uma única vez:

```java
// AbstractIntegrationTest.java — novo arquivo
@Testcontainers
@SpringBootTest(
        classes = CTradeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
abstract class AbstractIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ctrade")
            .withUsername("ctrade")
            .withPassword("ctrade123");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("runner.boot.orchestrator-enabled", () -> "false");
    }
}
```

Cada classe de teste herda de `AbstractIntegrationTest` e remove sua própria declaração de container e `@DynamicPropertySource`.

Classes que precisam de propriedades adicionais (como `RunnerBootRecoveryIntegrationTest` que define `runner.boot.phase2.portfolio.reservation-ttl.ttl-ms`) podem sobrescrever com um segundo `@DynamicPropertySource` na própria classe.

**Atenção:** com o container compartilhado, o banco não é recriado entre classes. Garantir que o `@BeforeEach` de cada classe limpe adequadamente os dados para não haver interferência entre testes de classes diferentes.

---

## Arquivos a modificar

- `MockOrderOverrideIntegrationTestSupport.java` — correções 1, 2 e 3
- `RunnerBootRecoveryIntegrationTest.java` — correções 2 e 3
- `OrderLifecycleMockIntegrationTest.java` — correções 2 e 3
- `ProcessTradeSignalMockIntegrationTest.java` — correções 2 e 3
- `OrderTerminationConciliationIntegrationTest.java` — correções 2 e 3
- `ConcurrentSellLockIntegrationTest.java` — correções 2 e 3
- `CapitalDeadLetterReplayIntegrationTest.java` — correções 2 e 3
- `MockExchangeAdapter.java` (ou classe relevante do adapter mock) — adicionar `reset()`
- `AbstractIntegrationTest.java` — novo arquivo base

---

## Critérios de aceitação

1. O tempo total da suíte de integração é inferior a 90 segundos localmente (medido via `./gradlew :spring-application:test`).
2. Todos os 47 testes de integração existentes continuam passando sem alteração de lógica de asserção.
3. `awaitStableFilledState` e `awaitStableSellState` retornam dentro de `INITIAL_CALLBACK_DELAY_MS + STABILITY_WINDOW_MS + margem` (≤ 800ms) quando o estado converge normalmente.
4. Nenhum teste falha por interferência de estado entre métodos de teste da mesma classe (validar que o `reset()` do mock cobre todo o estado mutável relevante).
5. Nenhum teste falha por interferência de estado entre classes de teste diferentes (validar que o `@BeforeEach` de cada classe limpa suficientemente o banco).
6. O container Postgres é iniciado uma única vez por execução da suíte (verificável nos logs do Testcontainers).
7. Nenhum `@DirtiesContext` permanece nas classes de integração listadas no diagnóstico.

## Testes de integração obrigatórios

A própria suíte existente é o critério de validação desta task. Executar `./gradlew :spring-application:test` com e sem as mudanças e comparar:
- Tempo total de execução
- Número de testes passando
- Ausência de flakiness em 3 execuções consecutivas
