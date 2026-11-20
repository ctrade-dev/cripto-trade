# T70 — Sequência de boot por fases (fechamento do item "refatorar o BootOrchestrator")

**Complexidade:** Média
**Responsável:** Claude
**Dependências:** T7 (boot readiness), T31 (recovery unificado), T59 (histórico do boot)
**Status:** Concluída — implementada no #194 e testada na VPS na curta 117 (2026-11-20)

---

## Descrição

O "Backlog Pós-Testes" do `docs/TESTING_ROADMAP.md` pede refatorar o `BootOrchestrator` para o fluxo de boot seguir
o padrão *use case first*. Os objetivos eram:

- manter o adapter Spring restrito a ciclo de vida, evento, configuração e observabilidade;
- extrair a orquestração do boot para um use case do `core`;
- reduzir o acoplamento entre o framework e o sequenciamento das fases.

O item foi escrito antes da fase 2 do boot (PR #73). Esta spec registra o que já foi entregue e trata o que sobrou.

## Estado atual

**O objetivo principal já foi atingido.** A sequência está no `core` e o Spring só traduz:

| Peça | Módulo | O que faz hoje |
|---|---|---|
| `BootOrchestrator` (133 linhas) | `spring-application/.../bootstrap` | No `ApplicationReadyEvent`, monta o `BootExecutionCommand` a partir de 6 classes de properties e chama `RunBootSequencePort`. O observer anônimo repassa os eventos para o `BootStatusTracker`, o `BootMetricsRecorder`, o log e o `BootFailFastEvent` |
| `RunBootSequenceUseCase` (411 linhas) | `core/.../usecase/boot` | Ordem das fases, liga/desliga, falha e histórico (`boot_runs`), **e também o corpo de cada fase** |
| `PortfolioZombieDetectionUseCase` | `core/.../usecase/boot/phase2` | Detecção de ordens zumbi por portfólio |

O Spring não decide nada de negócio. O que sobrou é organização interna do `core` e duplicações entre as duas
camadas.

## Problema (o que sobrou)

1. **Um use case com cinco responsabilidades.** `RunBootSequenceUseCase` faz a sequência e o corpo das quatro fases:
   - prontidão da infraestrutura (fase 1);
   - conferência de saldo com a exchange (fase 2, sanity);
   - detecção de zumbis, inclusive gravar amostras na DLQ e descobrir o runner de cada amostra (fase 2, zombie);
   - recuperação dos runners (fase 3).

   Mudar uma fase obriga a mexer no arquivo da sequência, e os testes de uma fase passam pela sequência inteira.
2. **Log duplicado.** O início e o fim do boot são logados nas duas camadas (`bootSequence: start ...` e
   `bootOrchestrator: start ...`), com o mesmo conteúdo. O status de cada fase é logado só no Spring.
3. **Falha tratada duas vezes.** Na falha, `onPhaseFailed` já marca o boot como falho. Depois disso,
   `onRunFailed` confere de novo e recebe `failurePhase = null`, embora o `core` saiba qual fase falhou.

## Solução proposta

### `core`: uma classe por fase, a sequência só ordena

- Interface `BootPhase` em `usecase/boot`, com:
  - `name()`, por exemplo `phase2.sanity`;
  - `enabled(BootExecutionCommand)`;
  - `run(BootContext)`. O `BootContext` leva o `runId`, o comando, o observer, os portfólios e os runners elegíveis,
    carregados uma vez.
- Uma classe por fase, cada uma no pacote da sua fase, seguindo a regra de esconder por pacote sem colapsar:
  - `phase1/InfrastructureReadinessPhase`;
  - `phase2/BalanceSanityPhase`;
  - `phase2/ZombieDetectionPhase`, que passa a ser dona da gravação das amostras na DLQ
    (`persistZombieSamplesToDlq`, `resolveRunnerIdForZombieSample`);
  - `phase3/RunnerRecoveryPhase`.
- `RunBootSequenceUseCase` recebe a lista ordenada de fases e mantém só:
  - o loop único que liga/desliga, cronometra e trata a falha (o `executePhase` de hoje);
  - o `triggerFailFast`;
  - o histórico em `boot_runs`.
- `BootConfig` monta as fases e a lista na ordem atual. Nada muda na ordem nem na regra de cada fase.

### Log e falha num lugar só

- O `core` loga o início, o fim e o status de cada fase, com as mesmas chaves de hoje
  (`phase=... status=... durationMs=... runId=...`). O observer do Spring deixa de logar e fica só com o tracker,
  as métricas e o evento de fail-fast.
- `onRunFailed` passa a receber a fase que falhou. O `BootStatusTracker` marca a falha só ali, e `onPhaseFailed`
  registra só a fase.

### O que não muda

- Fases, ordem, modos `WARN_ONLY`/`FAIL_FAST`, fail-fast por código, histórico `boot_runs`.
- Nomes das métricas, `GET` do boot e check `BOOT_NOT_READY`.
- Chaves de configuração (`runner.boot.*`, `portfolio.*`).
- O prefixo dos logs muda de `bootOrchestrator:` para `bootSequence:`. Nenhum script, regra de alerta ou documento
  usa o prefixo antigo; conferir de novo antes do PR.

## Fora de escopo

- Juntar as 6 classes de properties numa só: mudaria chaves de configuração já usadas na VPS.
- Trocar o `BootStatusTracker` em memória pelo histórico `boot_runs`: o tracker dá o progresso ao vivo durante o boot.
- Qualquer mudança de regra de alguma fase.

## Riscos

- **O boot é caminho crítico.** Um erro na extração pode pular uma fase ou mudar o tratamento de falha. Mitigação:
  - os testes atuais do boot (`BootOrchestratorBootMinimumTest`, `BootOrchestratorDlqOperationalTest`,
    `BootOrchestratorObservabilityTest` e os de recuperação de runner) passam sem mudar as asserções;
  - teste novo da sequência com fases falsas, para ordem, fase desligada, falha e fail-fast;
  - reinício na VPS com ordens e lotes abertos.
- **Mudança de log.** Leitura humana e do agente operador. O conteúdo fica igual; só o prefixo muda.

## Critérios de aceite

1. `RunBootSequenceUseCase` sem corpo de fase: só a sequência, a falha e o histórico.
2. Uma classe por fase no pacote da fase; a gravação de amostras zumbi na DLQ fica na fase de zumbis.
3. Início, fim e status de fase logados uma vez, pelo `core`.
4. `onRunFailed` com a fase que falhou; o tracker marca a falha num ponto só.
5. Testes de boot atuais verdes sem mudar asserções, e teste novo da sequência com fases falsas.
6. Reinício na VPS com ordens e lotes abertos: boot `SUCCESS`, `phase2.sanity` `OK`, zumbis 0, runners recuperados
   e 0 `ERROR`, igual ao reinício da implantação da F30/F31. **OK na curta 117:** implantação às 17:16Z com 2 lotes
   abertos e 2 BUY no livro; boot `SUCCESS` em 7,9 s, as quatro fases `SUCCESS`, `phase2.sanity` `OK`, zumbis 0,
   20 runners recuperados; log só com `bootSequence:`; 0 `ERROR` no reinício e na sessão.
7. `.ai/flows/boot-recovery.md` atualizado. O item sai do "Backlog Pós-Testes" do `docs/TESTING_ROADMAP.md`.

## Validação

- `./gradlew test` completo.
- Na VPS, depois do merge, cerca de 30 min: implantar e reiniciar com ordens e lotes abertos, e conferir o boot pela
  API e pelo log.

## Ordem de entrega

1. Teste da sequência com fases falsas, ainda sobre o código atual, para fixar o comportamento.
2. Extração das fases e a sequência por lista.
3. Log e falha num lugar só.
4. Docs, PR em rascunho, CI e espera por comentários; merge pelo mantenedor; teste na VPS.

## Decisões registradas na implementação (2026-11-16)

- **Fail-fast pelo contexto.** `BootContext.failFast(phase, code, message)` avisa o observer e devolve a exceção que
  a fase lança. Fica um caminho só para as três fases que pedem fail-fast.
- **Testes de boot pela montagem de produção.** Os três `BootOrchestrator*Test` montavam o use case pelo construtor
  antigo; agora chamam `new BootConfig().runBootSequence(...)` com os mesmos argumentos. Asserções sem mudança.
- **Ordem de entrega.** O teste com fases falsas (`RunBootSequenceUseCaseTest`) precisa da interface `BootPhase`, então
  veio junto com a extração. O comportamento atual ficou fixado pelos testes de boot existentes, que rodaram sem
  mudar asserções.
- **Logs.** Status `FAILED` da fase em `WARN` e a falha do boot uma vez em `ERROR`, com stack, como antes no Spring:
  a contagem de `ERROR` de um boot que falha não muda. O `completed` da sequência passa a contar os runners elegíveis;
  a fase 3 loga a própria contagem (`bootSequence.phase3: completed runId={} runners={}`).
- **Falha antes das fases** (carga de portfólios e runners): `onRunFailed` recebe fase `null`. O `GET` do boot
  continua mostrando `boot`, como antes, e o histórico `boot_runs` continua com fase vazia.

## Fontes

- `docs/TESTING_ROADMAP.md` ("Backlog Pós-Testes")
- `.ai/development-manual.md` (disposição de responsabilidades, um fluxo um caminho, seção 10)
- `.ai/flows/boot-recovery.md`
- `spring-application/.../bootstrap/BootOrchestrator.java`, `core/.../usecase/boot/RunBootSequenceUseCase.java`
