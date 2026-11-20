# T74 — Plano de staging: ambiente de produção na testnet, depois na mainnet

**Complexidade:** Alta (plano; cada entrega vira PR próprio)
**Responsável:** Claude
**Dependências:** T53 (soak em testnet, concluída), T63 (ambiente da VPS), T21/T22 (alertas), T55 (estado operacional)
**Status:** Spec para revisão do mantenedor (2026-11-20)

---

## Descrição

O soak em testnet terminou (T53, 2026-11-20): o ctrade opera 72h com estratégias fora do processo, sob falhas
provocadas, com o registro igual ao da exchange. O item 10 do `docs/GO_LIVE_CHECKLIST.md` pede agora um **staging com
comportamento próximo ao real** antes de produção, e os itens 8 (segredos) e 9 (backup, restore, migrações) ainda
estão abertos.

O ambiente de soak não serve de staging. Ele é de teste: banco descartável, apps de soak que exercitam ramos, scripts
de rodada que limpam tudo, operação só nas sessões que o mantenedor abre.

### Decisões do mantenedor (2026-11-20)

1. **Duas fases.** Fase A: ambiente com a configuração de produção, ainda na **testnet**. Fase B: o mesmo ambiente
   na **mainnet**, com capital pequeno.
2. **Mesma VPS Oracle**, num segundo stack, separado do de soak.
3. **Operação por rotina agendada:** o agente (Claude) verifica o staging sozinho, sem o mantenedor acionar.

Esta spec detalha a fase A e fixa os pré-requisitos da fase B. A fase B ganha spec própria quando a A for aprovada.

## Estado atual (verificado em 2026-11-20)

| Item | Estado |
|---|---|
| VPS | Oracle ARM, 2 vCPUs, 11 GB de RAM, 45 GB de disco (39% usado). Stack de soak usa ~0,8 GB em repouso |
| Rede | Nenhuma porta pública: API, Grafana, Prometheus e Loki em `127.0.0.1`, por túnel SSH |
| Segredos | `deploy/vps/.env` com permissão 600, fora do git: chaves da Binance testnet, `ADMIN_API_KEY`, senha do banco e do Grafana, webhook do Discord, tokens das apps |
| Perfis | `application.yml` + `application-testnet.yml` (URLs da testnet). Não há perfil de produção |
| Banco | Postgres 16 num volume Docker. **Sem backup.** Migrações Flyway só para frente (V1 a V23) |
| Alertas | 14 regras no Grafana (`docker/grafana/provisioning/alerting/rules.yml`) com contato no Discord, e notificação direta do ctrade (T21). Os alertas de ausência M7 a M10 do `.ai/monitoring-spec.md` esperam "calibração em staging" |
| Implantação | `git push` por SSH para o repositório na VPS + `up.sh` (build do commit em checkout) |
| Operação | Só nas sessões abertas pelo mantenedor. Nenhuma rotina agendada |
| Estratégias | Só as apps de soak e a `DonchianBreakout` (candidata real, com backtest em andamento no `ctrade-strategies`) |
| Scripts perigosos | `reset-testnet-db.sh` apaga o banco; hoje só a VPS de soak existe, então não há o que confundir |

## Objetivo da fase A

Rodar o ctrade **como se fosse produção**, com dinheiro de teste, por uma janela longa e contínua:

- configuração de produção (logs, modo de boot, alertas, segredos) e não a de teste;
- dados tratados como de produção: não se limpa o banco, há backup e restore testado;
- uma estratégia candidata de verdade, não as de soak;
- operação diária pelo agente, por rotina agendada, com o mantenedor só nas decisões.

A fase A termina quando a janela mínima passa com os critérios de promoção (seção "Promoção para a fase B").

## Solução proposta (fase A)

### A1. Perfis: exchange e operação separados

Dois eixos que hoje estão misturados no perfil `testnet`:

- **qual exchange:** `testnet` (já existe) e `mainnet` (fase B), só URLs da Binance;
- **como operar:** perfil novo `prod`. Log `INFO` (sem o `DEBUG` do `core` usado no soak), boot `FAIL_FAST`,
  notificação no Discord obrigatória (sobe com erro sem o webhook), retenção de logs e métricas maior.

O staging da fase A roda `SPRING_PROFILES_ACTIVE=testnet,prod`; a fase B, `mainnet,prod`. Uma configuração de
operação só, sem um terceiro perfil "staging" quase igual ao `prod`.

### A2. Segundo stack na mesma VPS

- Projeto Compose `ctrade-stg` em `deploy/staging/`, com **banco, volumes, rede e `.env` próprios**. Portas em
  `127.0.0.1` deslocadas: API 8180, Grafana 3001, Prometheus 9091, Loki 3101.
- **Observabilidade:** ver decisão 2. Recomendação: **Prometheus, Loki e Grafana próprios** do staging, ainda que
  menores. Compartilhar economiza ~0,7 GB, mas mistura dados e alertas dos dois ambientes, e uma rodada de soak que
  derruba o Loki derrubaria a observação do staging.
- **Recursos:** o soak e o staging juntos cabem na memória (~4 GB de 11), mas a CPU é de 2 vCPUs. O soak só roda
  quando há sessão; durante a fase A, rodadas longas de soak ficam fora da janela de observação do staging, ou o
  soak fica parado (decisão 3).
- **Proteção contra erro de alvo:** os scripts de soak (`round-*.sh`, `reset-testnet-db.sh`, `fault.sh`,
  `drill.sh`) recusam rodar se o projeto Compose não for `ctrade-vps`. O staging não tem script que apague dados.

### A3. Segredos e chaves (checklist item 8)

- `.env` próprio do staging, permissão 600, nunca impresso (como hoje).
- **Chave da Binance só para o staging**, separada da do soak, com **restrição de IP** ao IP da VPS e **sem
  permissão de saque**. A chave é criada pelo mantenedor; o agente nunca vê nem digita a chave.
- `ADMIN_API_KEY` própria do staging.
- **Rotação:** chaves da Binance e `ADMIN_API_KEY` a cada 90 dias e na hora em caso de suspeita. Procedimento
  no runbook (trocar no `.env`, reiniciar, confirmar boot). A rotação é do mantenedor.

### A4. Backup e restore (checklist item 9)

- `pg_dump` diário do banco do staging, comprimido, guardado na VPS com retenção de 14 dias, e uma cópia fora da VPS
  (decisão 4: no computador do mantenedor ou num bucket).
- **Restore testado** como critério de aceite: restaurar o último backup num banco descartável, subir um ctrade
  apontando para ele e conferir saldo, lotes e histórico contra o original.
- **Migrações:** Flyway continua só para frente. Antes de toda implantação no staging, backup; migração que falhar
  volta pelo restore e uma migração nova corrige. Sem scripts de rollback.

### A5. Versão implantada

- O staging roda **releases**, não o `develop`: tag em `main` pelo Git Flow (`release/x.y.z` → `main` + tag). O soak
  continua com o `develop`.
- Implantação no staging: backup, checkout da tag, `up.sh` do staging, confirmar o boot e o estado operacional. Uma
  linha por implantação num registro de versões (o `versions.sh` da T63 serve de base).

### A6. Estratégia e capital

- Estratégia candidata real: **`DonchianBreakout`** (rompimento em candles de 15 min), depois do backtest que o
  mantenedor está fazendo. Outras candidatas entram por cadastro, sem implantação (T54).
- Capital do portfólio do staging: o USDT da conta testnet própria do staging. Limites por runner e proteção do lote
  configurados para o prazo da estratégia (runbook, decisão 5 da T72).
- Lotes abertos não são vendidos ao fim de nada: o staging não tem "fim de sessão".

### A7. Alertas calibrados

- Os 14 alertas do Grafana valem para o staging, com **canal do Discord próprio** (`#ctrade-staging`), para não
  misturar com o soak.
- Os alertas de ausência M7 a M10 (sem fill, sem ordem, sem sinal por tempo) são calibrados com o volume real da
  estratégia candidata nas duas primeiras semanas e ligados depois.
- Cada alerta tem a seção do runbook com a ação, como já é o padrão do estado operacional.

### A8. Rotina agendada do agente

O que a rotina faz em cada execução:

1. lê `GET /api/admin/operational-state` (com a comparação contra a exchange uma vez por dia);
2. lê `GET /api/admin/events` desde a execução anterior e os `ERROR` do Loki no mesmo intervalo;
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
5. Pelo menos uma implantação de release durante a janela, com backup antes e boot `SUCCESS`.
6. Pelo menos um alerta real ou provocado chegou ao Discord do staging e foi tratado pelo runbook.
7. A rotina do agente rodou todos os dias, e o mantenedor não precisou intervir fora das decisões que são dele.
8. Estabilidade do processo: memória e threads da JVM sem crescimento contínuo, como no critério 7 da T53.

## Fase B (mainnet): pré-requisitos, não escopo

Ficam para a spec da fase B, mas precisam estar decididos antes dela:

- **Chave da mainnet** criada pelo mantenedor: restrição de IP, **sem saque**, só spot. O agente nunca vê a chave.
- **Capital:** valor máximo na conta da mainnet (ex.: 100 USDT) e no portfólio; nada além disso fica na conta.
- **Quem move dinheiro real:** na mainnet o agente **não** executa nada que gere ordem com dinheiro real (start ou
  resume de runner, `PANIC_SELL`, venda forçada de posição). Essas ações são do mantenedor. O agente observa,
  diagnostica, avisa e pode reduzir risco sem gerar ordem (HALT de runner, Safe Mode `HALT`). As decisões da
  estratégia no ctrade continuam automáticas, porque é o sistema do mantenedor operando com a configuração que ele
  aprovou.
- **Rotina sem o computador ligado** (opção a da decisão 5), se a operação da mainnet exigir.

## Decisões para a revisão

| # | Decisão | Recomendação | Motivo |
|---|---|---|---|
| 1 | Perfis | `testnet`/`mainnet` (exchange) + `prod` (operação), combinados | Um caminho de configuração de operação para a fase A e a B |
| 2 | Observabilidade do staging | Própria (Prometheus, Loki e Grafana do `ctrade-stg`) | Dados e alertas separados; o soak não derruba a observação do staging |
| 3 | Soak durante a fase A | Só sessões curtas, fora dos horários do relatório diário; sem rodada de 72h | 2 vCPUs para os dois ambientes |
| 4 | Cópia do backup fora da VPS | No computador do mantenedor, puxada pela rotina diária | Sem serviço novo nem segredo novo |
| 5 | Onde roda a rotina | `cron` na VPS (horário) + agente no computador do mantenedor (diário e sob alerta) | Começa sem chave na nuvem; a opção a fica para a fase B |
| 6 | Janela mínima da fase A | 14 dias contínuos | Duas semanas cobrem a calibração dos alertas e uma implantação |
| 7 | Estratégia da fase A | `DonchianBreakout` depois do backtest | Candidata real em vez das apps de soak |

## Ordem de entrega (fase A)

Cada item é um PR, com teste na VPS quando couber:

1. A1: perfil `prod` (sem mudança de comportamento no soak).
2. A2 + A3: `deploy/staging/` (compose, `.env.example`, `up.sh`), proteção dos scripts de soak, runbook de segredos.
   O mantenedor cria a chave da Binance e o canal do Discord do staging.
3. A4: backup diário, cópia e o procedimento de restore, com o restore testado.
4. A5: primeira release (`release/1.0.0`) e implantação no staging.
5. A7: canal do Discord do staging e alertas; calibração de M7 a M10 depois de duas semanas.
6. A8: verificação horária na VPS e a rotina diária do agente.
7. A6: cadastro da estratégia candidata e início da janela de 14 dias.

## Fora de escopo

- A fase B (mainnet), que ganha spec própria.
- Soak além de 72h e teste de carga controlada: seguem no `docs/TESTING_ROADMAP.md`.
- Alta disponibilidade, segunda VPS, réplica de banco.
- Mudança de regra de negócio do ctrade.

## Riscos

- **Mesma VPS:** disco cheio, falta de memória ou chave SSH comprometida atingem os dois ambientes. Mitigação:
  limites de memória por container (já existem), alerta de disco, backup fora da VPS.
- **Erro de alvo:** um script de soak contra o staging apagaria dados. Mitigação: scripts conferem o projeto
  Compose (A2).
- **Reset da testnet pela Binance:** apaga saldos e ordens no meio da janela da fase A. Mitigação: o reset reinicia a
  janela; o relatório diário detecta pela divergência de saldo.
- **Oracle recupera VPS ociosa (free tier):** com dois ambientes rodando, o uso não fica ocioso.
- **Rotina dependente do computador do mantenedor:** a verificação horária na VPS cobre os dias com o computador
  desligado; só o relatório diário atrasa.

## Critérios de aceite da fase A

1. Staging no ar com `testnet,prod`, banco e observabilidade próprios, sem porta pública.
2. Scripts de soak recusam o projeto do staging.
3. Backup diário rodando, cópia fora da VPS e um restore testado e registrado.
4. Release implantada pelo procedimento (backup, tag, boot `SUCCESS`).
5. Alertas do staging no canal próprio; um alerta provocado chega e é tratado pelo runbook.
6. Rotina horária na VPS e rotina diária do agente rodando, com relatório.
7. Janela de 14 dias com os critérios de promoção.

## Fontes

- `docs/GO_LIVE_CHECKLIST.md` (itens 8, 9 e 10), `docs/TESTING_ROADMAP.md` ("Backlog Pós-Testes")
- `.ai/tasks/T53-testnet-soak.md` ("Encerramento"), `.ai/tasks/T63-vps-soak-environment.md`
- `.ai/monitoring-spec.md` (M7 a M10), `docker/grafana/provisioning/alerting/rules.yml`
- `deploy/vps/` (compose, `up.sh`, scripts de rodada), `docs/OPERATIONS_RUNBOOK.md` (limites de autonomia)
- `.ai/product-direction.md` (sistema operado por IA), `.ai/development-manual.md` (seção 10)
