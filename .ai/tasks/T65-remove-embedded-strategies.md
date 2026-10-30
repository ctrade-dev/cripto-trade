# T65 — Remover as Estratégias Embutidas no ctrade

**Complexidade:** Média
**Responsável:** Claude
**Dependências:** T41 (estratégia fora do processo), T54 (catálogo por API), T60 (apps do soak).
**Status:** Concluído — PR #171.

---

## Descrição

O ctrade tem dois jeitos de rodar estratégia: classes Java no módulo `strategy`, registradas no boot
pelo `InMemoryStrategyRepository`, e apps remotas cadastradas por `/api/admin/strategies`. A direção
do produto é que o ctrade defina só o contrato (`product-direction.md`), e a regra de um fluxo, um
caminho (manual 1.3) pede que o caminho antigo saia.

O que existe no módulo `strategy`:

- `SimpleMovingAverageStrategy`: registrada sempre, com configuração de teste (2 períodos, limiar de
  0,0001%). Nenhum uso real; a app `trend` do `ctrade-strategies` já é a estratégia de média móvel.
- As 4 estratégias de cenário da T35, registradas só com `ctrade.scenario-strategies.enabled=true` e
  usadas pelos testes E2E (T38) e pela fase 1 da T53 (já aprovada).

O `strategy-service-reference` serve a SMA pelo contrato e tem o teste de paridade local × remoto da
T41. Sem a SMA, perde a função: o caminho remoto é testado no `RemoteStrategyE2ETest`, e as apps do
`ctrade-strategies` são a referência real do contrato.

## Decisões registradas (2026-10-28, mantenedor)

1. **SMA:** só sai; não vira app. A `trend` cobre a média móvel.
2. **Cenários da T35:** viram dublês dos testes de integração do `spring-application`. A flag sai e o
   ctrade de produção não embute estratégia. Repetir a fase 1 da T53 exigiria portá-los para apps.
3. **`strategy-service-reference`:** sai também.

Decisão técnica (Claude): onze testes de integração criam runners com o id da SMA e dependem das suas
decisões com poucos ticks. A SMA continua como dublê de teste, registrada por
`SmaTestStrategyConfiguration` (importada por `AbstractIntegrationTest` e pelo teste de user data). Reescrever esses
testes com outra estratégia muda o que eles provam sem ganho.

O `core` não muda: `TradingStrategy` continua sendo o tipo do repositório (o `RemoteTradingStrategy`
a implementa, e os dublês também). As guardas de colisão do catálogo com estratégia não remota ficam:
em produção não disparam, e nos testes protegem os dublês.

## Solução

- Módulos `strategy` e `strategy-service-reference` removidos (`settings.gradle`, `Dockerfile`,
  dependência do `spring-application`, CI).
- Cenários, seus testes unitários e a SMA movidos para
  `spring-application/src/test/java/com/marmitt/application/spring/testsupport/strategy/{scenario,sma}`.
- `InMemoryStrategyRepository` sem registro no boot; `ctrade.scenario-strategies` sai do `application.yml`.
- Documentação: manual (pergunta 2, tabela de decisão, 2.2, 8), `architecture.md`, `project-context.md`,
  `validation.md`, `product-direction.md`, `CONTRACT.md`, roadmap, `AGENTS.md`, `CLAUDE.md`, T53.

## Riscos

- **Contagem de rodadas da T53:** a mudança altera o boot. O merge esperaria as 2 rodadas limpas de
  24h, mas a rodada 7 foi reprovada e a contagem voltou a zero. Decisão registrada (2026-10-29): merge
  antes da próxima rodada, que já valida o boot sem as estratégias embutidas.
- **Banco com runner apontando para a SMA** (`a1b2c3d4-e5f6-7890-abcd-ef1234567890`): o runner fica sem
  estratégia (check `RUNNER_ON_HOLD_STRATEGY_UNUSABLE`). Na testnet o banco é limpo a cada rodada; não há
  produção.

## Critérios de aceitação

- [ ] Nenhuma referência a `com.marmitt.strategy` ou aos módulos removidos fora do histórico das specs.
- [ ] `:core:test`, `:spring-application:test` e `:adapter-strategy-remote:test` verdes.
- [ ] Boot sem estratégia registrada; apps remotas seguem cadastrando e rodando.
