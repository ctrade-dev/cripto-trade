# Testing Roadmap (Macro)

## Objetivo

Definir uma trilha macro para evoluir a confiança do sistema, começando por testes de integração com `MOCK`, sem perder aderência ao **Blueprint** e ao **Implementation Guide (IG)**.

## Princípio de Priorização

Antes de ampliar cobertura, validar os invariantes que mais impactam risco financeiro e consistência:

1. Idempotência de eventos
2. Fronteiras transacionais (atomicidade)

## Fases do Roadmap

### Fase 0 — Baseline de Invariantes

**Meta:** explicitar os contratos que os testes vão proteger.

- Listar invariantes críticas de domínio (capital, posição, transação, conciliação).
- Definir resultados esperados por cenário (estado final de banco e domínio).
- Padronizar critério de sucesso/falha dos testes.

**Saída esperada:** documento curto de invariantes + matriz cenário x expectativa.

#### Escopo detalhado da Fase 0

**Objetivo prático:** transformar regras de domínio em contratos testáveis, antes de escalar a suíte de integração.

O que já pode entrar em teste nessa fase:

1. Invariantes de capital
- `availableBalance >= 0` e `reservedBalance >= 0`.
- Reserva de BUY aplicada uma única vez por intenção válida.
- Liberação de margem em estados terminais sem efeito duplicado.

2. Invariantes de transação
- Transições de status válidas e monotônicas.
- Estado terminal não retorna para estado transitório.
- Evento duplicado não reaplica efeito financeiro.

3. Invariantes de posição/lote
- `openedByTransactionId` obrigatório para estados ativos (`OPEN`/`CLOSING`).
- SELL não pode exceder `availableQuantity`.
- Lock de SELL deve impedir duplo lock simultâneo no mesmo lote.

4. Invariantes de `transaction_match`
- Match não pode gerar dupla aplicação econômica.
- Soma conciliada não pode exceder execução da transação.
- `pnlRealized` coerente com regra de cálculo e fee.

5. Invariantes de idempotência (prioridade alta)
- Reprocessar o mesmo update não altera estado financeiro duas vezes.
- Fluxo `PARTIAL -> FILLED` aplica apenas delta incremental.

6. Invariantes mínimas de boot
- `WARN_ONLY` não deve bloquear runner por DETECTED.
- `FAIL_FAST` deve preservar fase real da falha.

Tipos de teste recomendados na Fase 0:
- Testes unitários de domínio (entidades e value objects).
- Testes unitários de use case com fake ports.
- Testes de contrato para transições de estado (matriz de casos).

Critério de pronto da Fase 0:
- Invariantes documentadas de forma explícita.
- Casos críticos codificados e estáveis.
- Base preparada para iniciar Fase 1 (integração com MOCK) sem ambiguidade.

---

### Fase 1 — Integração Core com MOCK

**Meta:** validar os fluxos fundamentais de ponta a ponta.

- Cenários mínimos:
  - BUY -> FILLED
  - BUY -> PARTIAL -> FILLED
  - SELL -> PARTIAL -> FILLED
  - REJECTED (BUY e SELL quando aplicável)
  - Boot recovery básico (runner com pendências simples)
- Em cada cenário, validar:
  - `transactions`
  - `transaction_matches`
  - `positions`
  - `global_balance`
  - `dead_letter_entries` (quando aplicável)

**Saída esperada:** suíte estável dos fluxos core.

#### Subdivisão recomendada da Fase 1

**Fase 1A — Integração com capacidade atual do MOCK**
- Executar primeiro os cenários que o mock já suporta sem alteração estrutural.
- Consolidar setup/reset de banco e critérios de validação por cenário.

Escopo detalhado da Fase 1A:

1. Preparar baseline de teste
- Subir a aplicação com `MOCK` habilitado.
- Resetar banco por teste/suíte com script padronizado.
- Fixar configurações de execução para reduzir variação (seed/delay/fee/slippage estáveis).

2. Criar infraestrutura de teste de integração
- Helpers de setup para `portfolio`, `runner`, `strategy` e estado inicial.
- Helpers de espera assíncrona com timeout/polling.
- Helpers de leitura e validação do estado persistido:
  - `transactions`
  - `transaction_matches`
  - `positions`
  - `global_balance`
  - `dead_letter_entries`

3. Cobrir cenários suportados hoje
- BUY happy path até FILLED.
- BUY com PARTIAL evoluindo para FILLED.
- SELL de posição aberta até FILLED.
- REJECTED com validação de status e impacto financeiro esperado.
- Boot recovery básico com pendência simples.

4. Validar invariantes por cenário
- Não duplicar efeito financeiro.
- Garantir transição de status coerente.
- Garantir consistência posição/lote vs execução.
- Garantir coerência de reserva/liberação de capital.

Critério de pronto da Fase 1A:
- Cenários acima estáveis e repetíveis.
- Sem flakiness recorrente.
- Base pronta para iniciar Fase 1B.

Fora de escopo da Fase 1A:
- Injeção explícita de eventos duplicados.
- Corridas concorrentes induzidas.
- Falhas transitórias/permanentes orquestradas.

**Fase 1B — Evolução do MOCK para cobertura completa**
- Introduzir controles determinísticos para testes:
  - forçar sequência de status (`NEW -> PARTIAL -> FILLED`, `REJECTED`);
  - controlar delay/timing entre eventos;
  - injetar eventos duplicados;
  - simular falha transitória/permanente;
  - reset de estado por teste.
- Após evoluir o mock, habilitar os cenários restantes da Fase 1.

Escopo detalhado da Fase 1B:

1. Controles determinísticos de execução
- Permitir configurar, por cenário de teste:
  - sequência de estados da ordem;
  - quantidade executada por etapa (parcial/final);
  - preço executado e fee por etapa;
  - tempo entre eventos.
- Garantir repetibilidade (mesmo input => mesmo resultado).

2. Injeção de anomalias controladas
- Duplicidade de evento (mesma atualização enviada mais de uma vez).
- Reordenação de eventos (quando aplicável ao cenário).
- Falhas transitórias (timeout, indisponibilidade curta, retry).
- Falhas permanentes (reject definitivo, serviço indisponível).

3. Suporte explícito a cenários de concorrência
- Simular chegada quase simultânea de updates relevantes.
- Expor gatilhos para reproduzir corrida em pontos conhecidos
  (ex.: PARTIAL + FILLED em janela curta).

4. Runtime de teste observável e controlável
- Endpoints/hooks para inspeção de estado interno do mock (opcional, mas recomendado).
- Endpoint/ação de reset total do estado interno do mock por teste.
- Logs de teste com identificadores claros para correlação de eventos.

5. Contrato de configuração para testes
- Centralizar configuração de cenário em objeto/DTO único.
- Evitar ajustes ad-hoc espalhados em classes de simulação.
- Facilitar composição de cenários para suíte de integração.

6. Integração com a suíte da Fase 1
- Reexecutar os cenários da 1A com o mock evoluído.
- Adicionar cenários novos:
  - evento duplicado sem dupla aplicação financeira;
  - parcial + filled concorrente sem inconsistência;
  - timeout/retry com reconciliação correta;
  - reject com motivo consistente e efeitos esperados.

Plano incremental de execução (1 PR por item):
1. PR 1: `PARTIAL + FILLED` quase simultâneos (delay zero/curto) com convergência sem drift de quantidade.
2. PR 2: `FILLED` duplicado sem dupla aplicação econômica (`transaction_matches`, `global_balance`, posição).
3. PR 3: `REJECTED/EXPIRED` determinístico com validação de `rejectReason` e liberação de reserva.

Critério de pronto da Fase 1B:
- Mock reproduz cenários críticos de forma determinística e configurável.
- Suíte de integração cobre cenários de duplicidade/concorrência/falha.
- Não há regressão dos cenários da Fase 1A.
- Resultado da suíte é estável em execuções repetidas.

**Critério de pronto da Fase 1**
- Todos os cenários mínimos executando de forma determinística.
- Assertivas cobrindo estado de domínio e persistência (não apenas logs).
- Sem flakiness recorrente na suíte base.

---

### Fase 2 — Concorrência e Duplicidade

**Meta:** garantir robustez contra corridas e eventos repetidos.

- Reprocessar o mesmo evento 2x/3x.
- Simular mensagens quase simultâneas (partial + filled, ou filled duplicado).
- Validar ausência de dupla aplicação financeira.

**Saída esperada:** suíte de resiliência validando idempotência e lock/conciliação.

#### Escopo detalhado da Fase 2

**Objetivo prático:** provar que o sistema se mantém correto sob concorrência realista e mensagens redundantes.

Pré-requisitos:
- Fase 1A e 1B estáveis.
- Controles determinísticos do mock disponíveis (duplicidade, timing, reorder e reset).
- Assertivas de banco consolidadas (`transactions`, `transaction_matches`, `positions`, `global_balance`, `dead_letter_entries`).

1. Cenários de duplicidade direta
- Mesmo evento `NEW`, `PARTIAL` e `FILLED` processado 2x/3x.
- Mesmo evento terminal chegando após já ter sido aplicado.
- Verificar que status final, saldo e posição não sofrem reaplicação.

2. Cenários de concorrência temporal
- `PARTIAL` e `FILLED` chegando na mesma janela de tempo.
- Duas mensagens `FILLED` quase simultâneas para a mesma transação.
- SELL concorrente tentando lock na mesma posição/lote.
- Verificar lock, idempotência e ausência de inconsistência.

3. Cenários de reorder controlado
- `FILLED` chegando antes de `PARTIAL` (quando o mock permitir).
- `CANCELED`/`EXPIRED` chegando junto de atualizações pendentes.
- Garantir convergência para estado final correto sem corromper histórico financeiro.

4. Cenários com retry/falha transitória
- Timeout em query/submit com retry subsequente.
- Primeira tentativa falha, segunda confirma.
- Verificar ausência de dupla criação de efeitos financeiros após retry.

5. Invariantes obrigatórias da Fase 2
- No máximo um efeito financeiro por execução econômica real.
- `reserved_balance` e `available_balance` sempre coerentes.
- `transaction_matches` sem duplicação econômica.
- Lock de posição impede disputa simultânea inválida.
- Estado final monotônico (sem regressão de terminal para transitório).

6. Estratégia de validação
- Assert por snapshot de estado final.
- Assert por delta (antes/depois) para detectar duplicidade.
- Repetição do mesmo teste múltiplas vezes para capturar flakiness.
- Execução paralela seletiva dos cenários mais críticos.

Critério de pronto da Fase 2:
- Cenários de duplicidade/concorrência passam de forma estável.
- Não há dupla escrita financeira nos cenários induzidos.
- Locks e conciliação convergem para estado final consistente.
- Flakiness residual abaixo do limite definido pelo time.

Fora de escopo da Fase 2:
- Cobertura completa de boot em estado degradado profundo (Fase 3).
- Observabilidade/alerta operacional avançado como foco principal (Fase 4).

---

### Fase 3 — Boot Recovery Completo

**Meta:** validar cenários de recuperação em estado degradado.

- Zombies (PENDING sem `exchangeOrderId`, com e sem TTL vencido).
- Limbo (SUBMITTED/PARTIAL sem confirmação local terminal).
- Comportamento por modo:
  - `WARN_ONLY`
  - `FAIL_FAST`
- Verificar impacto em status do runner (`ACTIVE`, `HALTED`, `RECONCILING`).

**Saída esperada:** suíte cobrindo fases 2/3 do boot com assertivas operacionais.

#### Escopo detalhado da Fase 3

**Objetivo prático:** validar que o sistema recupera estado com segurança após reinício em condições degradadas, sem operar com inconsistência.

Pré-requisitos:
- Fases 1 e 2 estáveis.
- Modo de boot habilitado no ambiente de teste.
- Mock com capacidade de simular ordens abertas, não encontradas e respostas de query variadas.

1. Cenários de zombie (PENDING sem `exchangeOrderId`)
- Zombie dentro do TTL: permanece pendente sem expirar indevidamente.
- Zombie fora do TTL: expiração e liberação coerente de reserva.
- Verificar que não há reaplicação financeira ao reexecutar boot.

2. Cenários de limbo (SUBMITTED/PARTIAL)
- Ordem encontrada na exchange: conciliação para estado correto.
- Ordem não encontrada: fallback para estado terminal sintético esperado.
- Ordem com resposta parcial na query: convergência sem perda de consistência.

3. Capacidade de query por exchange
- Exchange com query suportada.
- Exchange sem query (unsupported): comportamento previsto e anotação de erro controlado.
- Falha transitória em query com retry/backoff: convergência sem duplicidade.

4. Modos de execução do boot
- `WARN_ONLY`: sinaliza problema sem bloquear indevidamente operação.
- `FAIL_FAST`: interrompe boot com fase/código corretos.
- Verificar preservação da fase real de falha no status do boot.

5. Efeito no ciclo de vida do runner
- Entrada em reconciliação no início do fluxo.
- Finalização correta quando sem erros.
- `ACTIVE -> HALTED` quando houver erro crítico pendente.
- Sem regressão indevida de estado após reprocessamento.

6. DLQ durante recovery
- Registro de pendências quando aplicável.
- Não duplicar entrada de DLQ para o mesmo problema.
- Escopo correto por `portfolio`/`runner` (sem contaminação cruzada).

7. Reexecução idempotente do boot
- Executar boot 2x/3x sobre mesma base degradada.
- Confirmar que o resultado final converge e permanece estável.

Critério de pronto da Fase 3:
- Zombies e limbo tratados conforme regra de negócio.
- Modos `WARN_ONLY` e `FAIL_FAST` com comportamento consistente.
- Transição de estado do runner previsível e auditável.
- Reexecução do boot sem efeitos financeiros duplicados.

Fora de escopo da Fase 3:
- Carga/performance pesada e testes de longa duração (Fase 4).
- Validação de observabilidade avançada como foco principal (Fase 4).

---

### Fase 4 — Hardening Operacional via Teste

**Meta:** garantir previsibilidade em runtime.

- Validar geração de métricas e eventos de fail-fast.
- Exercitar caminhos de DLQ (abertura, não duplicação, visibilidade).
- Testar comportamento com falhas transitórias (timeout/query retry).

**Saída esperada:** suíte de confiança operacional para deploy contínuo.

#### Escopo detalhado da Fase 4

**Objetivo prático:** validar confiabilidade operacional sob falha, carga controlada e condições próximas de produção.

Pré-requisitos:
- Fases 1, 2 e 3 estáveis.
- Métricas e eventos de boot/recovery disponíveis no ambiente de teste.
- Runbook básico de incidentes já definido.

1. Observabilidade validada por teste
- Confirmar emissão de métricas por fase/resultado.
- Confirmar eventos de fail-fast com `phase`/`code` corretos.
- Validar logs com contexto operacional mínimo (correlationId, runnerId, exchange, fase).

2. DLQ operacional
- Validar abertura de DLQ em anomalias reais.
- Validar não duplicação de DLQ para a mesma identidade do problema.
- Validar caminhos de resolução/reprocessamento e fechamento de pendência.

3. Falhas transitórias e resiliência
- Simular timeout, indisponibilidade curta e recuperação.
- Validar retry/backoff e convergência para estado consistente.
- Confirmar ausência de efeito financeiro duplicado após recuperação.

4. Testes de soak e estabilidade temporal
- Executado na Binance testnet pela T53 (concluída em 2026-11-20): rodadas de 24h e 72h na VPS, falhas controladas e
  validação dos achados (`.ai/tasks/T53-testnet-soak.md`, "Encerramento").
- Rodar cenários contínuos por janela maior (ex.: 30-60 min) em ambiente de teste.
- Monitorar crescimento de pendências (`PENDING`, DLQ, reconciling) ao longo do tempo.
- Verificar ausência de degradação progressiva de estado.

5. Testes de carga controlada
- Aumentar volume de eventos gradualmente.
- Medir latência de conciliação e tempo de convergência.
- Validar comportamento sob pressão sem perda de consistência.

6. Exercícios de operação (runbook drills)
- Simular incidentes operacionais e executar procedimento de resposta.
- Validar tempo de diagnóstico e recuperação com as ferramentas disponíveis.
- Registrar lacunas de operação e retroalimentar documentação.

7. Critérios de confiabilidade para release
- Definir SLO/SLA internos mínimos para boot/recovery/conciliação.
- Definir thresholds de alerta e condições de bloqueio de deploy.
- Consolidar “go/no-go” com evidências da suíte operacional.

Critério de pronto da Fase 4:
- Métricas, eventos e logs permitem diagnóstico rápido e confiável.
- DLQ possui ciclo operacional validado ponta a ponta.
- Sistema mantém consistência sob falha transitória e carga controlada.
- Time possui critério claro de liberação para ambiente produtivo.

Fora de escopo da Fase 4:
- Otimização avançada de performance sem impacto funcional comprovado.
- Expansão de escopo de negócio (novas estratégias/produtos) fora do objetivo de confiabilidade operacional.

## Ordem Recomendada de Execução

1. Fase 0
2. Fase 1
3. Fase 2
4. Fase 3
5. Fase 4

## Critério de Pronto (Macro)

- Fluxos core estáveis em CI.
- Nenhum cenário de duplicidade gerando dupla escrita financeira.
- Boot recovery validado em cenários representativos (WARN_ONLY e FAIL_FAST).
- DLQ e métricas com comportamento previsível nos cenários críticos.

## Alinhamento com Blueprint e IG

- **Blueprint:** protege invariantes de domínio, consistência de estados e recuperação.
- **IG:** cobre os fluxos e decisões operacionais já mapeadas no projeto.
- Este roadmap deve evoluir junto com mudanças de domínio e novos modos de execução.

## Status de Execucao (2026-04-26)

- Fase 0: CONCLUIDA.
- Fase 1A: CONCLUIDA.
- Fase 1B: CONCLUIDA.
- Fase 2: CONCLUIDA.
- Fase 3: CONCLUIDA.
- Fase 4: CONCLUIDA.

### Evidencias de conclusao da Fase 1B

- PR 1 (concluído): `PARTIAL + FILLED` quase simultâneos com convergência.
- PR 2 (concluído): `FILLED` duplicado sem dupla aplicação econômica.
- PR 3 (concluído): `REJECTED/EXPIRED` determinístico com validação de efeitos financeiros.
- PR 4 (concluído): refinamento de manutenibilidade do teste determinístico da Fase 1B.

### Evidencias de conclusao da Fase 0

- Invariantes de dominio (capital, transicao de status, lifecycle de runner) cobertas no modulo `core`.
- Idempotencia/roteamento basico de conciliacao coberta em `ConciliationOrderUpdate`.
- Invariantes minimas de boot cobertas no `BootOrchestrator`:
  - `WARN_ONLY` sem bloqueio indevido.
  - `FAIL_FAST` com preservacao da fase especifica de falha.

### Evidencias de conclusao da Fase 1A

- Fluxo BUY/PARTIAL/FILLED com MOCK e persistencia:
  - `spring-application/src/test/java/com/marmitt/application/spring/bootstrap/ProcessTradeSignalMockIntegrationTest.java`
- Fluxo ponta a ponta BUY -> SELL com conciliacao e matches:
  - `spring-application/src/test/java/com/marmitt/application/spring/bootstrap/OrderLifecycleMockIntegrationTest.java`
- Fluxos de terminacao e liberacao de margem (REJECTED/EXPIRED/CANCELED):
  - `spring-application/src/test/java/com/marmitt/application/spring/bootstrap/OrderTerminationConciliationIntegrationTest.java`
- Boot recovery basico (zombie, limbo, DLQ -> HALTED):
  - `spring-application/src/test/java/com/marmitt/application/spring/bootstrap/RunnerBootRecoveryIntegrationTest.java`

### Evidencias de conclusao da Fase 1B

- Controles determinísticos e cenarios de duplicidade/concorrencia/rejeicao no MOCK:
  - `spring-application/src/test/java/com/marmitt/application/spring/bootstrap/MockBuyOrderOverrideIntegrationTest.java`
  - `spring-application/src/test/java/com/marmitt/application/spring/bootstrap/MockSellOrderOverrideIntegrationTest.java`
  - `spring-application/src/test/java/com/marmitt/application/spring/bootstrap/MockTerminalOrderOverrideIntegrationTest.java`
- Refinamentos de legibilidade e reuso no teste deterministico ja incorporados na `develop` via PR #46.

### Relacao com Go-Live

- O Testing Roadmap e o Go-Live devem evoluir em paralelo.
- Ao concluir cada fase do roadmap, atualizar o status do Item 1 do Go-Live.
- Go-live completo depende da conclusao das Fases 2, 3 e 4, e da integracao real de exchange.

### Fase 2 concluida

1. PR 1 (concluido): `SELL` concorrente tentando lock no mesmo lote/posicao.
2. PR 2 (concluido): retry idempotente do mesmo `SELL` lock sem permitir tomada por outra transacao.
3. PR 3 (concluido): callbacks duplicados de `SELL FILLED` em janela curta sem dupla aplicacao economica.
4. PR 4 (concluido): reorder controlado com `FILLED` antes de `PARTIAL` para BUY e SELL.
5. PR 5 (concluido): refatorar a suite deterministica do mock por responsabilidade antes dos cenarios de retry/falha transitoria.
6. PR 6 (concluido): redelivery idempotente apos falha transitoria simulada no processamento de `SELL FILLED`.
7. PR 7 (concluido): eventos terminais fora de ordem (`FILLED -> CANCELED` atrasado) sem regressao de estado ou dupla aplicacao economica.
8. PR 8 (concluido): repeticao controlada dos cenarios mais frageis para validar monotonicidade terminal e estabilidade sem flakiness.

### Fase 3 concluida

1. PR 1 (concluido): zombies `PENDING` sem `exchangeOrderId` dentro do TTL e reexecucao idempotente do recovery para zombies vencidos.
2. PR 2 (concluido): limbo `SUBMITTED/PARTIAL` reconciliado via query positiva do MOCK (`SUBMITTED -> FILLED` e `PARTIAL -> FILLED`) sem drift financeiro.
3. PR 3 (concluido): limbo nao encontrado na exchange com fallback sintetico (`SUBMITTED -> EXPIRED`, `PARTIAL -> CANCELED`) e reexecucao idempotente do recovery.
4. PR 4 (concluido): falha de query no boot recovery cobrindo `query unsupported` e `query transient failure + retry/backoff` com recuperacao sem drift.
5. PR 5 (concluido): falha transitoria esgotada no boot recovery (`retry exhausted`) preservando limbo sem persistencia parcial indevida e levando o runner a `HALTED`.

### Evidencias de conclusao da Fase 3

- Boot recovery completo consolidado em:
  - `spring-application/src/test/java/com/marmitt/application/spring/bootstrap/RunnerBootRecoveryIntegrationTest.java`
- Cobertura funcional consolidada:
  - zombie `PENDING` dentro e fora do TTL
  - limbo `SUBMITTED/PARTIAL` com query positiva
  - limbo `SUBMITTED/PARTIAL` nao encontrado na exchange
  - `query unsupported`
  - `query transient failure + retry/backoff`
  - `query retry exhausted`
  - reexecucao idempotente do recovery nos cenarios relevantes

### Fase 4 concluida

1. PR 1 (concluido): observabilidade do boot validada por teste cobrindo evento de fail-fast e metricas minimas por fase/resultado.
2. PR 2 (concluido): DLQ operacional no boot cobrindo abertura de entry e nao duplicacao da mesma identidade do problema.
3. PR 3 (concluido): gestao manual da DLQ cobrindo listagem e resolucao via use case/controller.
4. PR 4 (concluido): reprocessamento seguro de DLQ para eventos de capital com retries esgotados.
5. PR 5 (concluido): replay ponta a ponta de DLQ de capital, do recover ate o reprocessamento via controller, com validacao de efeito economico e idempotencia.

### Evidencias de conclusao da Fase 4

- Observabilidade de boot consolidada em:
  - `spring-application/src/test/java/com/marmitt/application/spring/bootstrap/BootOrchestratorObservabilityTest.java`
- DLQ operacional de boot consolidada em:
  - `spring-application/src/test/java/com/marmitt/application/spring/bootstrap/BootOrchestratorDlqOperationalTest.java`
- Gestao manual de DLQ consolidada em:
  - `core/src/test/java/com/marmitt/core/application/usecase/portfolio/ManageDeadLetterUseCaseTest.java`
  - `spring-application/src/test/java/com/marmitt/application/spring/controller/DeadLetterControllerTest.java`
- Resiliencia do listener de capital consolidada em:
  - `spring-application/src/test/java/com/marmitt/application/spring/handler/CapitalEventListenerTest.java`
- Reprocessamento seguro e replay ponta a ponta de DLQ de capital consolidados em:
  - `spring-application/src/test/java/com/marmitt/application/spring/deadletter/CapitalDeadLetterReprocessingAdapterTest.java`
  - `spring-application/src/test/java/com/marmitt/application/spring/bootstrap/CapitalDeadLetterReplayIntegrationTest.java`

## Backlog Pos-Testes

- ~~Refatorar o `BootOrchestrator` para aproximar o fluxo de boot do padrao `usecase first`.~~ Concluido na T70:
  sequencia no `core` com uma classe por fase; o `BootOrchestrator` so monta o comando e traduz o observer
  (`.ai/flows/boot-recovery.md`).
- Executar soak tests de maior duracao (alem das 72h da T53) para monitorar acumulacao de pendencias e degradacao
  progressiva.
- Executar testes de carga controlada para medir latencia de conciliacao e convergencia sob pressao.
- Avancar para staging e integracao real de exchange como proxima trilha fora do escopo atual com `MOCK`.
