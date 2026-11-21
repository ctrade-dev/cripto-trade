# T74 — Plano de staging: ambiente de produção na testnet, depois na mainnet

**Complexidade:** Alta (plano; cada entrega vira PR próprio)
**Responsável:** Claude
**Dependências:** T53 (soak em testnet, concluída), T63 (ambiente da VPS), T21/T22 (alertas), T55 (estado operacional)
**Status:** Em andamento: spec aprovada em 2026-11-20 (desenho de schemas e duas contas, decisões 8 e 9); A1 em PR

---

## Descrição

O soak em testnet terminou (T53, 2026-11-20): o ctrade opera 72h com estratégias fora do processo, sob falhas
provocadas, com o registro igual ao da exchange. O item 10 do `docs/GO_LIVE_CHECKLIST.md` pede agora um **staging com
comportamento próximo ao real** antes de produção, e os itens 8 (segredos) e 9 (backup, restore, migrações) ainda
estão abertos.

O ambiente da VPS hoje é de soak: banco descartável, apps de soak que exercitam ramos, scripts de rodada que limpam
tudo, operação só nas sessões que o mantenedor abre. Ele não serve de staging como está, mas não precisa de um
ambiente novo ao lado: só duas pessoas desenvolvem, não há disputa pelo ambiente. A proposta é **converter o ambiente
da VPS em staging** e rodar o soak, quando preciso, como um segundo ctrade temporário, isolado por schema do banco e
por conta da testnet.

### Decisões do mantenedor (2026-11-20)

1. **Duas fases.** Fase A: ambiente com a configuração de produção, ainda na **testnet**. Fase B: o mesmo ambiente
   na **mainnet**, com capital pequeno.
2. **Mesma VPS Oracle.** O ambiente atual vira o staging; não há segundo stack completo.
3. **Schemas separados no mesmo Postgres:** `staging` e `soak`. Troca-se de ambiente sem perder os dados do outro.
4. **Duas contas na testnet:** uma para o staging, outra para o soak, para que os dois não dividam saldo.
5. **Operação por rotina agendada:** o agente (Claude) verifica o staging sozinho, sem o mantenedor acionar.

Esta spec detalha a fase A e fixa os pré-requisitos da fase B. A fase B ganha spec própria quando a A for aprovada.

### Conta da testnet: chave nova não é conta nova

Pesquisa de 2026-11-20. Na Spot Testnet a conta é o login do GitHub. Várias chaves criadas no mesmo login são da
**mesma conta, com o mesmo saldo**. Um guia de bot que usa a testnet afirma isso explicitamente e avisa que bots com
chaves diferentes da mesma conta interferem entre si. A documentação oficial só diz que o saldo é por usuário
cadastrado e não trata de várias chaves.

Fontes:
- [HodlerHacks, Test with Binance Testnet](https://hodlerhacks.com/balance-bot/test-with-binance-testnet/)
- [Binance, Testnet General Info](https://developers.binance.com/docs/binance-spot-api-docs/testnet/general-info)

Para ter a segunda conta é preciso um **segundo login no GitHub**. Quem cria é o mantenedor: o agente não cria conta.
Antes de A2, a separação é confirmada na prática: com as duas chaves no `.env`, o agente lê o saldo pelas duas e
confere que são diferentes.

## Estado atual (verificado em 2026-11-20)

| Item | Estado |
|---|---|
| VPS | Oracle ARM, 2 vCPUs, 11 GB de RAM, 45 GB de disco (39% usado). Stack de soak usa ~0,8 GB em repouso |
| Rede | Nenhuma porta pública: API (8080), Grafana, Prometheus e Loki em `127.0.0.1`, por túnel SSH |
| Segredos | `deploy/vps/.env` com permissão 600, fora do git: chaves da Binance testnet, `ADMIN_API_KEY`, senha do banco e do Grafana, webhook do Discord, tokens das apps |
| Perfis | `application.yml` + `application-testnet.yml` (URLs da testnet). Não há perfil de produção |
| Banco | Postgres 16 num volume Docker, um usuário `ctrade`, tudo no schema `public`. **Sem backup.** Migrações Flyway só para frente (V1 a V23), sem nome de schema nas migrações nem no SQL do código |
| Conta testnet | Uma só. O fim de sessão não vende lotes, então o USDT livre cai a cada rodada (~227 USDT disponíveis no portfólio após a curta 118) |
| Alertas | 14 regras no Grafana (`docker/grafana/provisioning/alerting/rules.yml`) com contato no Discord, e notificação direta do ctrade (T21). Os alertas de ausência M7 a M10 do `.ai/monitoring-spec.md` esperam "calibração em staging" |
| Implantação | `git push` por SSH para o repositório na VPS + `up.sh` (build do commit em checkout) |
| Operação | Só nas sessões abertas pelo mantenedor. Nenhuma rotina agendada |
| Estratégias | Só as apps de soak e a `DonchianBreakout` (candidata real, com backtest em andamento no `ctrade-strategies`) |
| Scripts perigosos | `reset-testnet-db.sh` / `scripts/reset_testnet_database.sql` fazem `TRUNCATE` de todas as tabelas, sem nome de schema: valem para o schema do `search_path` de quem conecta |

## Objetivo da fase A

Rodar o ctrade **como se fosse produção**, com dinheiro de teste, por uma janela longa e contínua:

- configuração de produção (logs, modo de boot, alertas, segredos) e não a de teste;
- dados tratados como de produção: não se limpa o banco, há backup e restore testado;
- uma estratégia candidata de verdade, não as de soak;
- operação diária pelo agente, por rotina agendada, com o mantenedor só nas decisões.

A fase A termina quando a janela mínima passa com os critérios de promoção (seção "Promoção para a fase B").

## Desenho

```
VPS (projeto Compose ctrade-vps)
├── postgres ─┬─ schema staging  (usuário ctrade_stg)
│             └─ schema soak     (usuário ctrade_soak)
├── ctrade-app       contínuo   testnet,prod   API 127.0.0.1:8080   conta testnet A
├── ctrade-soak      sob demanda testnet        API 127.0.0.1:8180   conta testnet B   (perfil Compose "soak")
├── apps de estratégia (candidata do staging contínua; apps de soak só nas sessões)
└── prometheus, loki, grafana  compartilhados, com o rótulo env=staging|soak
```

## Solução proposta (fase A)

### A1. Perfis: exchange e operação separados

Dois eixos que hoje estão misturados no perfil `testnet`:

- **qual exchange:** `testnet` (já existe) e `mainnet` (fase B), só URLs da Binance;
- **como operar:** perfil novo `prod`. Log `INFO` (sem o `DEBUG` do `core` usado no soak), boot `FAIL_FAST`,
  consulta de saldo obrigatória no boot (`account-query-policy: FAIL`), notificação no Discord obrigatória (sobe com
  erro sem o webhook). A retenção de logs e métricas é do Prometheus e do Loki, não do ctrade: fica em A8.

O staging roda `SPRING_PROFILES_ACTIVE=testnet,prod`; a fase B, `mainnet,prod`; o soak continua só com `testnet`.
Uma configuração de operação só, sem um terceiro perfil "staging" quase igual ao `prod`.

### A2. Um banco, dois schemas

- O schema vem por variável: `DB_SCHEMA` entra na URL do datasource (`currentSchema`) e no Flyway
  (`spring.flyway.default-schema` e `schemas`). Cada instância migra e lê só o próprio schema. Sem `DB_SCHEMA`, o
  padrão é `public`, e o desenvolvimento local não muda.
- **Um usuário do Postgres por schema:** `ctrade_stg` é dono só de `staging`; `ctrade_soak` é dono só de `soak`.
  Nenhum dos dois tem permissão no schema do outro. Assim, um `TRUNCATE` do soak não alcança o staging nem por engano
  de `search_path`: o banco recusa.
- Senhas dos dois usuários no `.env` (600). O usuário `ctrade` atual fica só para a administração (criar os
  usuários, backup).
- **Migração do estado atual:** os dados de hoje no `public` são de testnet e descartáveis. O `public` fica como
  está até o staging subir; depois é apagado num PR próprio, com aviso antes. O staging começa vazio no schema
  `staging`; o soak, vazio no `soak`.
- A troca de ambiente não apaga nada: parar o `ctrade-soak` deixa o schema `soak` intacto para a próxima sessão.

### A3. Staging contínuo e soak sob demanda

- O serviço `ctrade-app` atual vira o **staging**: sobe sempre, `testnet,prod`, schema `staging`, conta A, porta
  8080. Os containers e volumes atuais são reaproveitados.
- O soak vira o serviço **`ctrade-soak`** no mesmo compose, no perfil Compose `soak`: só sobe com
  `docker compose --profile soak up`. Schema `soak`, conta B, porta `127.0.0.1:8180`. As apps de soak também ficam
  no perfil `soak`.
- **Scripts de soak** (`round-*.sh`, `reset-testnet-db.sh`, `fault.sh`, `drill.sh`, `register-strategies.sh`) passam
  a apontar para o `ctrade-soak` (porta 8180, usuário `ctrade_soak`) e **recusam** qualquer alvo que não seja o soak.
  Em especial, `fault.sh` e `drill.sh` provocam falha só no container do soak, nunca no `ctrade-app`.
- O staging não tem script que apague dados.
- **Recursos:** os dois ctrade juntos cabem na memória (~1,5 GB a mais), mas a CPU é de 2 vCPUs. O soak só roda em
  sessão curta, fora do horário do relatório diário, sem rodada de 72h na fase A (decisão 3).
- **Falhas que atingem os dois:** reiniciar o Postgres, encher o disco ou derrubar a rede afeta o staging também.
  Testes de falha de infraestrutura compartilhada (Postgres, Loki) ficam fora da janela da fase A ou contam como
  incidente provocado no relatório do staging.

### A4. Duas contas na testnet e segredos (checklist item 8)

- O mantenedor cria a **segunda conta** (segundo login no GitHub) e as chaves. O agente nunca vê nem digita chave,
  nem cria conta.
- Conta A (a nova, decisão 8) para o staging; conta B (a atual) para o soak. Cada `ctrade` lê só as próprias
  variáveis no `.env` (`BINANCE_*_STG` e `BINANCE_*_SOAK`, nomes finais no PR).
- Chaves com **restrição de IP** ao IP da VPS, se a testnet oferecer, e **sem permissão de saque**.
- `ADMIN_API_KEY` própria de cada instância.
- **Rotação:** chaves da Binance e `ADMIN_API_KEY` a cada 90 dias e na hora em caso de suspeita. Procedimento
  no runbook (trocar no `.env`, reiniciar, confirmar boot). A rotação é do mantenedor.
- **Limite de requisições por IP:** os dois ctrade saem do mesmo IP e somam peso no limite da Binance por IP. O
  consumo de hoje é baixo, mas o relatório diário acompanha o peso usado pelas duas instâncias.

### A5. Backup e restore (checklist item 9)

- `pg_dump` diário **só do schema `staging`** (`-n staging`), comprimido, guardado na VPS com retenção de 14 dias, e
  uma cópia fora da VPS (decisão 4).
- **Restore testado** como critério de aceite: restaurar o último backup num schema descartável (`staging_restore`),
  subir um ctrade temporário apontando para ele, sem conectar na exchange, e conferir saldo, lotes e histórico contra
  o original. Depois, apagar o schema de teste.
- **Migrações:** Flyway continua só para frente, cada schema com o próprio histórico. Antes de toda implantação no
  staging, backup; migração que falhar volta pelo restore e uma migração nova corrige. Sem scripts de rollback.
- O soak continua sem backup: dado descartável.

### A6. Versão implantada

Decisão 9, decidida em 2026-11-20: **releases**. As opções consideradas:

- **Releases:** o staging roda tag em `main` pelo Git Flow (`release/x.y.z` → `main` + tag); o soak roda o
  `develop`. O staging só recebe o que já passou pelo soak, e a fase B herda o mesmo procedimento.
- **`develop` direto:** o staging recebe cada merge. Menos passos, mas o staging deixa de ser "como produção" e cada
  implantação conta como risco na janela de 14 dias.

Recomendação: **releases**. Com um build só por implantação, o custo de abrir uma release é pequeno, e a janela de
14 dias passa a medir uma versão fechada.

Implantação no staging: backup, checkout da versão, `up.sh` (só o `ctrade-app`), confirmar o boot e o estado
operacional. Uma linha por implantação num registro de versões (o `versions.sh` da T63 serve de base). O soak
implanta o `develop` no `ctrade-soak` sem tocar no staging.

Como os dois serviços saem do mesmo checkout hoje, o `up.sh` passa a montar uma imagem por ambiente, cada uma do seu
commit (por exemplo, um checkout separado para o staging em `~/ctrade-stg`).

### A7. Estratégia e capital

- Estratégia candidata real: **`DonchianBreakout`** (rompimento em candles de 15 min), depois do backtest que o
  mantenedor está fazendo. Outras candidatas entram por cadastro, sem implantação (T54).
- Capital do portfólio do staging: o USDT da conta A. Limites por runner e proteção do lote configurados para o prazo
  da estratégia (runbook, decisão 5 da T72).
- Lotes abertos não são vendidos ao fim de nada: o staging não tem "fim de sessão". O soak continua como hoje, na
  conta B.

### A8. Observabilidade compartilhada com rótulo de ambiente

- Um Prometheus, um Loki e um Grafana para os dois. O Prometheus raspa os dois ctrade com o rótulo `env`
  (`staging`, `soak`); os logs no Loki levam o mesmo rótulo pelo container.
- Os 14 alertas do Grafana passam a filtrar `env="staging"` e notificam o **canal do Discord do staging**
  (`#ctrade-staging`). Os alertas do soak, se mantidos, vão para outro canal.
- A notificação direta do ctrade (T21) usa o webhook de cada instância no `.env`.
- Os scripts de rodada (`round-close.sh` e o relatório) filtram o Loki por `env="soak"`.
- Os alertas de ausência M7 a M10 (sem fill, sem ordem, sem sinal por tempo) são calibrados com o volume real da
  estratégia candidata nas duas primeiras semanas e ligados depois.
- Cada alerta tem a seção do runbook com a ação, como já é o padrão do estado operacional.
- Retenção: pelo menos 30 dias de métricas e de logs, para cobrir a janela de 14 dias e a análise depois dela.
- Risco aceito: uma sessão de soak que derrube o Loki ou o Prometheus derruba também a observação do staging.
  Mitigação em A3 (falhas de infraestrutura compartilhada fora da janela ou registradas).

### A9. Rotina agendada do agente

O que a rotina faz em cada execução:

1. lê `GET /api/admin/operational-state` (com a comparação contra a exchange uma vez por dia);
2. lê `GET /api/admin/events` desde a execução anterior e os `ERROR` do Loki (`env="staging"`) no mesmo intervalo;
3. classifica cada achado pelo runbook: nada a fazer, ação permitida ao agente, ou escalar ao mantenedor;
4. age dentro dos limites de autonomia (abaixo) e confere o efeito;
5. deixa um relatório curto e, se houver algo para o mantenedor, avisa no Discord.

Cadência proposta: **a cada hora** a verificação curta e **uma vez por dia** o relatório com a comparação contra a
exchange.

Onde a rotina roda (decisão 5):

| Opção | Como | A favor | Contra |
|---|---|---|---|
| a) Rotina na nuvem do Claude Code (`/schedule`) | Agente na nuvem, acesso por SSH com uma chave **só desse uso** | Roda com o computador do mantenedor desligado | A chave de acesso à VPS fica no ambiente da nuvem |
| b) Agendador do Windows no computador do mantenedor | `claude -p` com a chave que já existe | Nada sai da máquina do mantenedor | Só roda com o computador ligado |
| c) Script na VPS + agente só quando há anomalia | `cron` na VPS faz as verificações determinísticas e avisa no Discord; o agente entra pela opção a ou b só para o diagnóstico | Verificação contínua barata, sem depender de nada externo | Duas peças para manter |

Recomendação: **c com b** para começar. O `cron` na VPS cobre a verificação horária sem depender de nada. O agente
roda no computador do mantenedor uma vez por dia (relatório) e quando o Discord acusar algo. A opção a fica para
quando a fase B exigir operação sem o computador ligado.

**Limites de autonomia do agente no staging** (fase A, dinheiro de teste): os mesmos da operação atual. Pode HALT
de runner, subir o Safe Mode, cancelar ordem parada e retomar o HALT automático por exchange fora do ar (T71). Pede
antes: `PANIC_SELL`, baixar Safe Mode ou HALT automático, fechar posição à mão, encerrar runner, mudar limite de
risco ou política de liquidação.

## Promoção para a fase B

A fase A só promove com **todos** estes itens, numa janela contínua de **14 dias** (decisão 6):

1. Conciliação diária 100% igual à exchange; DLQ vazia ou cada entrada explicada.
2. Nenhum `ERROR` sem causa conhecida; todo `ERROR` explicado no relatório diário.
3. Nenhum `HALT` ou Safe Mode automático sem causa identificada; os que houver, retomados pelo caminho do runbook.
4. Backup diário em todos os dias e um restore testado dentro da janela.
5. Pelo menos uma implantação de versão durante a janela, com backup antes e boot `SUCCESS`.
6. Pelo menos um alerta real ou provocado chegou ao Discord do staging e foi tratado pelo runbook.
7. A rotina do agente rodou todos os dias, e o mantenedor não precisou intervir fora das decisões que são dele.
8. Estabilidade do processo: memória e threads da JVM sem crescimento contínuo, como no critério 7 da T53.
9. As sessões de soak durante a janela não alteraram o staging: nenhum dado, ordem ou alerta do soak no staging.

## Fase B (mainnet): pré-requisitos, não escopo

Ficam para a spec da fase B, mas precisam estar decididos antes dela:

- **Chave da mainnet** criada pelo mantenedor: restrição de IP, **sem saque**, só spot. O agente nunca vê a chave.
- **Capital:** valor máximo na conta da mainnet (ex.: 100 USDT) e no portfólio; nada além disso fica na conta.
- **Quem move dinheiro real:** na mainnet o agente **não** executa nada que gere ordem com dinheiro real (start ou
  resume de runner, `PANIC_SELL`, venda forçada de posição). Essas ações são do mantenedor. O agente observa,
  diagnostica, avisa e pode reduzir risco sem gerar ordem (HALT de runner, Safe Mode `HALT`). As decisões da
  estratégia no ctrade continuam automáticas, porque é o sistema do mantenedor operando com a configuração que ele
  aprovou.
- **Banco da mainnet:** schema próprio (`prod`), com usuário próprio e backup; os dados de staging não migram para
  ele. Se o soak na testnet continuar na mesma VPS com dinheiro real ao lado, revisar se a VPS compartilhada ainda
  serve.
- **Rotina sem o computador ligado** (opção a da decisão 5), se a operação da mainnet exigir.

## Decisões para a revisão

| # | Decisão | Recomendação | Motivo |
|---|---|---|---|
| 1 | Perfis | `testnet`/`mainnet` (exchange) + `prod` (operação), combinados | Um caminho de configuração de operação para a fase A e a B |
| 2 | Observabilidade | Compartilhada, com o rótulo `env` (decidido) | Menos memória e um Grafana só; risco aceito em A8 |
| 3 | Soak durante a fase A | Só sessões curtas, fora dos horários do relatório diário; sem rodada de 72h | 2 vCPUs para os dois ambientes |
| 4 | Cópia do backup fora da VPS | No computador do mantenedor, puxada pela rotina diária | Sem serviço novo nem segredo novo |
| 5 | Onde roda a rotina | `cron` na VPS (horário) + agente no computador do mantenedor (diário e sob alerta) | Começa sem chave na nuvem; a opção a fica para a fase B |
| 6 | Janela mínima da fase A | 14 dias contínuos | Duas semanas cobrem a calibração dos alertas e uma implantação |
| 7 | Estratégia da fase A | `DonchianBreakout` depois do backtest | Candidata real em vez das apps de soak |
| 8 | Qual conta fica com o staging | A conta **nova** no staging; a atual fica com o soak (decidido em 2026-11-20) | O staging começa com saldo cheio e sem os lotes deixados pelas rodadas; o soak segue com o histórico que já tem |
| 9 | Versão no staging | Releases (tag em `main`); soak com `develop` (decidido em 2026-11-20) | O staging mede uma versão fechada; ver A6 |

## Ordem de entrega (fase A)

Cada item é um PR, com teste na VPS quando couber:

1. A1: perfil `prod` (sem mudança de comportamento no soak). Entregue: `application-prod.yml` (log `INFO`, boot fase 2
   `FAIL_FAST` + `account-query-policy: FAIL`) e `notification.discord.required`.
2. A2: schema por variável (`DB_SCHEMA`) no datasource e no Flyway, com teste de integração em schema não `public`.
3. A3 + A4 + A8: compose com `ctrade-app` (staging) e `ctrade-soak` (perfil `soak`), usuários do Postgres por
   schema, scripts de soak apontando para o soak e recusando o staging, rótulo `env` no Prometheus e no Loki, alertas
   filtrados, runbook de segredos. Antes: o mantenedor cria a segunda conta da testnet, as chaves e o canal do
   Discord, e o agente confirma que as duas contas têm saldos diferentes.
4. A5: backup diário, cópia e o procedimento de restore, com o restore testado.
5. A6: primeira release (`release/1.0.0`) e implantação no staging; um checkout por ambiente no `up.sh`.
6. A9: verificação horária na VPS e a rotina diária do agente.
7. A7: cadastro da estratégia candidata e início da janela de 14 dias.
8. Limpeza: apagar o schema `public` antigo, com aviso antes.

## Fora de escopo

- A fase B (mainnet), que ganha spec própria.
- Soak além de 72h e teste de carga controlada: seguem no `docs/TESTING_ROADMAP.md`.
- Alta disponibilidade, segunda VPS, réplica de banco.
- Mudança de regra de negócio do ctrade.

## Riscos

- **Mesma VPS e mesmo Postgres:** disco cheio, falta de memória, Postgres fora do ar ou chave SSH comprometida
  atingem os dois ambientes. Mitigação: limites de memória por container (já existem), alerta de disco, backup fora
  da VPS, falhas de infraestrutura compartilhada fora da janela (A3).
- **Erro de alvo:** um script de soak contra o staging apagaria dados. Mitigação em duas camadas: os scripts recusam
  outro alvo que não o `ctrade-soak` (A3), e o usuário do soak não tem permissão no schema `staging` (A2).
- **Mesma conta por engano:** se as duas chaves forem da mesma conta, o soak consome o saldo do staging e a
  conciliação acusa lotes alheios. Mitigação: conferência dos saldos antes de A3; a conciliação diária detecta.
- **Limite de requisições por IP:** as duas instâncias somam peso no mesmo IP. Mitigação: acompanhamento no relatório
  diário (A4).
- **Reset da testnet pela Binance:** apaga saldos e ordens das duas contas no meio da janela da fase A. Mitigação: o
  reset reinicia a janela; o relatório diário detecta pela divergência de saldo.
- **Oracle recupera VPS ociosa (free tier):** com o staging contínuo, o uso não fica ocioso.
- **Rotina dependente do computador do mantenedor:** a verificação horária na VPS cobre os dias com o computador
  desligado; só o relatório diário atrasa.

## Critérios de aceite da fase A

1. Staging no ar com `testnet,prod`, schema `staging`, conta própria, sem porta pública.
2. Soak sobe e desce pelo perfil `soak` sem tocar no staging; os dados dos dois schemas sobrevivem à troca.
3. Scripts de soak recusam o staging, e o usuário do soak não tem permissão no schema `staging`.
4. Saldos das duas contas da testnet conferidos como diferentes.
5. Backup diário rodando, cópia fora da VPS e um restore testado e registrado.
6. Versão implantada pelo procedimento (backup, checkout, boot `SUCCESS`).
7. Alertas do staging filtrados por `env` no canal próprio; um alerta provocado chega e é tratado pelo runbook.
8. Rotina horária na VPS e rotina diária do agente rodando, com relatório.
9. Janela de 14 dias com os critérios de promoção.

## Fontes

- `docs/GO_LIVE_CHECKLIST.md` (itens 8, 9 e 10), `docs/TESTING_ROADMAP.md` ("Backlog Pós-Testes")
- `.ai/tasks/T53-testnet-soak.md` ("Encerramento"), `.ai/tasks/T63-vps-soak-environment.md`
- `.ai/monitoring-spec.md` (M7 a M10), `docker/grafana/provisioning/alerting/rules.yml`
- `deploy/vps/` (compose, `up.sh`, scripts de rodada), `scripts/reset_testnet_database.sql`,
  `docs/OPERATIONS_RUNBOOK.md` (limites de autonomia)
- `.ai/product-direction.md` (sistema operado por IA), `.ai/development-manual.md` (seção 10)
- Contas da testnet: [Testnet General Info](https://developers.binance.com/docs/binance-spot-api-docs/testnet/general-info),
  [HodlerHacks, Test with Binance Testnet](https://hodlerhacks.com/balance-bot/test-with-binance-testnet/)
