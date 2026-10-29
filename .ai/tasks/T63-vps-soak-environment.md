# T63 — Ambiente de Soak na VPS

**Complexidade:** Média
**Responsável:** Claude (compose, scripts, runbook); mantenedor (contratar a VPS, chaves SSH e segredos)
**Dependências:** T53 (decisão 2: rodadas acima de 12h numa VPS), T60 (apps de estratégia), T59 (relatório
de rodada). Desbloqueia as fases 2 a 4 da T53.
**Status:** Em andamento — VPS no ar (Oracle, 2026-10-28); compose, scripts e runbook em `deploy/vps`.

---

## Descrição

As rodadas de 24h e 72h da T53 não rodam na máquina do mantenedor (decisão 2 da T53). Falta um
ambiente numa VPS com o ctrade, as cinco apps de estratégia da T60 e a observabilidade. O Claude
precisa ter acesso a esse ambiente para montar a rodada e escrever o relatório de fim de rodada.

O `docker-compose.yml` atual foi feito para desenvolvimento local e não serve numa máquina exposta à
internet:

- publica `8080` (API), `5432` (Postgres), `9090` (Prometheus), `3100` (Loki) e `3000` (Grafana) em
  todas as interfaces;
- deixa senhas fixas no arquivo (Postgres `ctrade123`, Grafana `ctrade123`);
- o `ctrade-app` não ativa o perfil `testnet` e não recebe senha do banco;
- o Prometheus raspa `host.docker.internal:8080`, que só funciona com o ctrade fora do Docker;
- as apps de estratégia sobem num compose separado (`ctrade-strategies/docker-compose.yml`) e são
  alcançadas por `127.0.0.1:809x`, sem token.

## Contexto técnico

- `docker-compose.yml`: serviços `postgres` (perfil `base`), `prometheus`/`loki`/`grafana`
  (`observability`) e `ctrade-app` (`app`, build do `Dockerfile`).
- `Dockerfile`: build multi-stage do `bootJar`. Ele copia os `build.gradle` dos módulos para cachear as
  dependências, mas faltam `adapter-strategy-remote` e `strategy-service-reference`. A imagem funciona,
  só perde o cache.
- `spring-application/src/main/resources/application.yml`:
  - `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_PASSWORD` (padrão `ctrade123`);
  - `ctrade.strategy.remote.timeout-ms: 250`.
- `logback-spring.xml`: envia ao Loki por `LOKI_URL` (padrão `http://loki:3100`).
- `docker/prometheus/prometheus.yml`: alvo `host.docker.internal:8080`.
- `docker/grafana/provisioning`: painéis e alertas (Discord por `DISCORD_WEBHOOK_URL`).
- `scripts/reset-testnet-db.ps1` + `scripts/reset_testnet_database.sql`: limpeza do banco, só em
  PowerShell e só no container local.
- `ctrade-dev/ctrade-strategies`:
  - `Dockerfile`: uma imagem para as cinco apps; o argumento escolhe a estratégia;
  - token por `STRATEGY_TOKEN`, porta por `PORT`.
- Catálogo (T54): `POST /api/admin/strategies` aceita `token` por estratégia, e o ctrade o envia nas
  chamadas.

## Decisões registradas (2026-10-27)

1. **VPS:** o mantenedor contrata. Tamanho recomendado: 4 vCPU, 8 GB de RAM, 80 GB de disco, Ubuntu
   24.04 LTS, região próxima da Binance (Europa ou Japão). Qualquer provedor com IP fixo e firewall serve.
2. **Imagens das apps:** build na própria VPS a partir de um checkout do `ctrade-strategies` numa
   versão fixa (tag ou commit). GHCR fica para quando houver deploy frequente.
3. **Acesso:** só por **túnel SSH com chave**. Nenhuma porta além do SSH fica aberta para a internet.
   API, Grafana, Prometheus e Loki são alcançados pelo túnel, tanto pelo mantenedor quanto pelo Claude.
4. **VPS escolhida (2026-10-28):** Oracle Cloud Always Free, Frankfurt, `VM.Standard.A1.Flex` com
   2 OCPU / 12 GB, **ARM (aarch64)**, Ubuntu 26.04 LTS. Consequências:
   - todas as imagens precisam de build para `arm64` (as escolhidas têm);
   - 12 GB em vez de 8 GB: os limites de memória do compose somam ~5,5 GB;
   - o firewall tem duas camadas, a Security List da VCN e o iptables da imagem, e as duas já liberam
     só a 22;
   - os repositórios do GitHub são privados e a VPS não tem credencial: o código chega por `git push`
     via SSH para repositórios *bare* na VPS, sem deploy key.

## Solução proposta

### 1. Compose da VPS

Um arquivo `deploy/vps/docker-compose.yml`, separado do compose de desenvolvimento, que fica como está.

- **Rede interna** `ctrade`. Nenhum serviço publica porta em `0.0.0.0`.
  - `ctrade-app`: `127.0.0.1:8080`.
  - `grafana`: `127.0.0.1:3000`.
  - `prometheus`: `127.0.0.1:9090`.
  - `loki`: `127.0.0.1:3100`.
  - `postgres`: sem porta; o acesso é por `docker exec`.
- **`ctrade-app`:**
  - `SPRING_PROFILES_ACTIVE=testnet`, `DB_HOST=postgres`, `DB_PASSWORD`, `LOKI_URL=http://loki:3100`,
    `ADMIN_API_KEY`, `BINANCE_API_KEY`/`SECRET`, `DISCORD_WEBHOOK_URL`;
  - limite de memória do container e `-XX:MaxRAMPercentage`, para o critério 7 da T53 (memória
    estável) ser medido contra um teto conhecido.
- **Cinco apps de estratégia** (`trend`, `reversion`, `book`, `breakout`, `chaos`):
  - build de `${STRATEGIES_DIR}` (checkout do `ctrade-strategies`);
  - cada uma com `STRATEGY_TOKEN` próprio;
  - sem porta; o ctrade as alcança por `http://<serviço>:8080/v1`, o que também elimina o problema de
    IPv6 do `localhost` visto na T60.
- **Prometheus:** alvo `ctrade-app:8080`, em arquivo de configuração próprio da VPS. Retenção de 15 dias.
- **Loki:** retenção de 15 dias, cobrindo uma rodada de 72h com folga. *(Substituído na T66: essa
  configuração perdia os logs gravados; a VPS passou ao Loki 3.4.2 com a configuração padrão da imagem,
  sem retenção.)*
- **Grafana:** senha do admin vinda do `.env`, provisionamento reaproveitado de `docker/grafana`.
- **Volumes nomeados** para Postgres, Prometheus, Loki e Grafana.
- **Imagens de terceiros com versão fixa** (hoje `grafana/grafana:latest` e `prom/prometheus:latest`),
  para que um `pull` no meio da trilha não mude o ambiente.

### 2. Segredos

- `deploy/vps/.env.example` no git, com todas as variáveis e sem valores. O `.env` real fica só na VPS
  (permissão `600`); o `.gitignore` já ignora `.env` e `.env.*` e libera `.env.example`.
- Variáveis: `BINANCE_API_KEY`, `BINANCE_API_SECRET`, `ADMIN_API_KEY`, `DB_PASSWORD`,
  `GRAFANA_ADMIN_PASSWORD`, `DISCORD_WEBHOOK_URL`, `<APP>_TOKEN` para cada uma das cinco apps,
  `STRATEGIES_DIR`.
- Chaves só da testnet. Nada de produção entra neste ambiente.

### 3. Scripts (bash, porque a VPS é Linux)

- `deploy/vps/up.sh`: confere o `.env`, faz o build do ctrade (commit atual) e das apps (`STRATEGIES_DIR`)
  e sobe tudo; ao final, espera o `/actuator/health` responder `UP`.
- `deploy/vps/register-strategies.sh`: cadastra as cinco apps no catálogo com `baseUrl` interno e
  token. É idempotente (T54).
- `deploy/vps/reset-testnet-db.sh`: equivalente ao `.ps1`. Recusa rodar com o `ctrade-app` no ar e
  confere que o alvo é o container da VPS de testnet.
- `deploy/vps/versions.sh`: imprime o commit do ctrade, o do `ctrade-strategies` e as imagens em uso,
  para entrar no relatório da rodada.

### 4. Acesso (túnel SSH)

- Usuário sem senha, só chave. Firewall: entrada só na 22.
- Túnel documentado: `ssh -N -L 8080:127.0.0.1:8080 -L 3000:127.0.0.1:3000 -L 9090:127.0.0.1:9090
  -L 3100:127.0.0.1:3100 <usuario>@<vps>`. Com o túnel aberto, os scripts e comandos do Claude que
  usam `localhost:8080` e `localhost:3100` funcionam iguais aos de hoje.
- O Claude usa uma chave própria cadastrada pelo mantenedor, para que o acesso possa ser revogado
  separado do dele.

### 5. Runbook

`deploy/vps/README.md`, com link no `docs/OPERATIONS_RUNBOOK.md`:

- preparar a VPS: Docker, usuário, firewall, chaves;
- primeira subida, atualização de versão e volta para uma versão anterior;
- abrir o túnel e conferir o acesso;
- começar uma rodada da T53 (reset, cadastro, portfolio, runners) e fechar com o relatório (T59);
- onde ficam os dados (volumes) e como limpar o disco.

O roteiro da T53 passa a apontar para esse procedimento nas fases 2 a 4.

### 6. Correções pequenas no que já existe

- `Dockerfile`: copiar os `build.gradle` de `adapter-strategy-remote` e `strategy-service-reference`
  (cache de dependências).

## Validação na VPS (2026-10-28)

Oracle A1 2 OCPU / 12 GB, Ubuntu 26.04, ctrade `efae6d5` (este branch), `ctrade-strategies` `23009ca`.

- **Critério 1:** os dez serviços sobem com `up.sh`, todas as imagens em `linux/arm64`. Varredura TCP
  de fora (portas 1–1024 e as do ambiente) com a pilha no ar: só a **22** aberta.
- **Critério 2:** pelo túnel, `operational-state` `OK`; Grafana com os quatro painéis e as fontes Loki e
  Prometheus; Loki recebendo os logs do ctrade (`app=ctrade`); alvo do Prometheus `up`. O mantenedor
  abriu o Grafana pelo túnel com a própria chave.
- **Critério 3:** `register-strategies.sh` deixa as cinco `VERIFIED`; repetido, mesmo resultado.
- **Critério 4:** smoke de 1h (portfolio `vps-smoke-t63`, 12:41–13:42Z, tetos 0,4 × 4 e caos 0,2):
  - idas e voltas `FILLED`: tendência 7, reversão 10, rompimento 4, livro 1 (primeira desde a
    recalibração da T60);
  - zero `ERROR` no Loki, estado operacional `OK` do início ao fim, sem Safe Mode nem halt automático;
  - realizado −1,36 USDT; runners em `HALT` manual no fim.
- **Critério 5:** `reset-testnet-db.sh` recusa com o `ctrade-app` no ar e limpa com ele parado
  (0 portfolios).
- **Critério 7:** `.env` só na VPS (permissão `600`), gerado lá; as chaves da Binance são as da testnet.
- **Recursos no fim:** ctrade 326 MB de 2 GB, Loki 111 MB, Grafana 111 MB, cada app ~14 MB; máquina com
  ~1,5 GB usados e 6,8 GB de disco.

Achados corrigidos no PR:

- `gradlew` sem o bit de execução no git: o `COPY . .` do `Dockerfile` o sobrescrevia com `644` e o build
  falhava em Linux (no Docker Desktop do Windows não aparecia).
- As cinco apps com a mesma tag de imagem falhavam no build em paralelo; agora uma imagem por serviço.

Risco novo: com ~13% de memória usada, a VPS ociosa por 7 dias se enquadra na recolha do Always Free
(runbook, "Oracle Always Free: cuidados").

## Fora do escopo

- Perfil `prod`, segredos de produção e chaves reais. A trilha de produção vem depois da testnet.
- TLS, domínio e exposição pública da API (o túnel cobre o acesso).
- Alta disponibilidade, backup do banco (dado de testnet é descartável) e CI/CD automático para a VPS.
- Mudanças no ctrade além do `Dockerfile`. Se uma lacuna aparecer, vira task própria.

## Riscos

- **Recursos da VPS:** a JVM, o Postgres, o Loki e as cinco apps disputam 8 GB. O limite de memória do
  container e a retenção de logs evitam que a máquina caia por disco ou OOM no meio da rodada.
- **Latência VPS → apps:** com a rede interna, a chamada ao `/evaluate` fica bem abaixo dos 250 ms. A
  latência VPS → Binance depende da região escolhida.
- **Segredo vazado em log ou commit:** o `.env` fica fora do git, e as tokens nunca voltam pela API
  (T54). Conferir que o `up.sh` não imprime variáveis.
- **Relógio:** a assinatura HMAC da Binance exige o relógio sincronizado. Confirmar que a VPS tem NTP
  ativo.

## Critérios de aceitação

1. `deploy/vps/docker-compose.yml` sobe ctrade, Postgres, observabilidade e as cinco apps.
   - Visto da internet, só a porta 22 está aberta (conferido com varredura de portas).
2. Pelo túnel SSH, a partir da máquina do mantenedor:
   - `GET /api/admin/operational-state` responde;
   - o Grafana abre com os painéis provisionados;
   - o Loki recebe os logs do ctrade;
   - o Prometheus raspa o `ctrade-app`.
3. `register-strategies.sh` deixa as cinco apps `VERIFIED`, com token e `baseUrl` internos.
4. Smoke de 1h na VPS com as cinco apps:
   - zero `ERROR` do ctrade além dos explicados;
   - idas e voltas `FILLED` em ao menos três estratégias;
   - estado operacional `OK`.
5. `reset-testnet-db.sh` limpa o banco e recusa rodar com o ctrade no ar.
6. Runbook da VPS escrito, e o roteiro da T53 apontando para ele.
7. Nenhum segredo no git (`.env` ignorado, `.env.example` sem valores).

## Fontes

- `.ai/tasks/T53-testnet-soak.md` (decisões 1, 2 e 4, pré-requisitos, roteiro da fase 3)
- `.ai/tasks/T60-soak-strategy-apps.md` (apps, achado do `localhost`/IPv6)
- `.ai/tasks/T54-dynamic-strategy-catalog.md` (token por estratégia)
- `.ai/tasks/T59-event-timeline.md` (relatório de rodada)
- `docker-compose.yml`, `Dockerfile`, `docker/`, `scripts/reset-testnet-db.ps1`
- `.ai/product-direction.md` (produção só após testnet; sistema operado por IA)
