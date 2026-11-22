# Ambiente da VPS: staging e soak (T63, T74)

Um Compose com dois ambientes do ctrade, ambos na **testnet**, cada um com a própria conta:

| | Staging (T74) | Soak (T63) |
|---|---|---|
| Serviço | `ctrade-app` + apps do staging (`channel`, `channel-xrp`, `following`, `following-xrp`) | `ctrade-soak` + apps de soak (perfil Compose `soak`) |
| Quando | Sempre no ar (`./up.sh`) | Só nas sessões de teste (`./soak-up.sh`, `./soak-down.sh`, scripts de rodada) |
| Perfis | `testnet,prod` | `testnet` |
| API | `127.0.0.1:8080` | `127.0.0.1:8180` |
| Banco | schema `staging`, usuário `ctrade_stg` | schema `soak`, usuário `ctrade_soak` |
| Conta da testnet | `BINANCE_API_KEY_STG` / `_SECRET_STG` | `BINANCE_API_KEY` / `_SECRET` |
| Chave admin | `ADMIN_API_KEY_STG` | `ADMIN_API_KEY` |
| Discord | `DISCORD_WEBHOOK_URL_STG` (obrigatório; também recebe os alertas do Grafana) | `DISCORD_WEBHOOK_URL` (opcional) |
| Rótulo `env` (Loki, Prometheus) | `staging` | `soak` |
| Candles das estratégias | Binance real (`BINANCE_CANDLES_BASE_URL`, só leitura pública) | testnet |
| Cadastro das apps | `./register-strategies.sh staging` | `./register-strategies.sh` |

Postgres, Prometheus, Loki e Grafana são compartilhados. Cada usuário do Postgres só enxerga o próprio schema
(`./db-environments.sh`): os scripts de soak, que limpam o banco, não alcançam o staging. Os alertas do Grafana
ignoram `env="soak"`.

**Somente testnet.** Nenhuma chave ou dado de produção entra neste ambiente. Dados do staging são tratados como
de produção: não se limpam.

## Máquina atual

| Item | Valor |
|---|---|
| Provedor | Oracle Cloud, Always Free (conta em Free Trial), região Frankfurt (`eu-frankfurt-1`, AD-1) |
| Instância | `ctrade-soak`, `VM.Standard.A1.Flex`, 2 OCPU / 12 GB, **ARM (aarch64)** |
| Sistema | Ubuntu 26.04 LTS, boot volume de 46 GB |
| Acesso | `ubuntu@129.159.24.66` (IP efêmero: muda se a instância for recriada) |
| Firewall | Security List da VCN com entrada só na 22 + iptables da imagem (`REJECT` em tudo além da 22) |
| Docker | `docker.io` 29 e `docker-compose-v2` 2.40, dos pacotes do Ubuntu |

As imagens usadas são multi-arquitetura (ARM incluso). O `Dockerfile` do ctrade usa `eclipse-temurin`
e o das apps usa `python:3.13-slim`; os dois têm build para aarch64.

## Acesso: só por túnel SSH com chave

Nenhuma porta além da 22 fica aberta para a internet. API, Grafana, Prometheus e Loki escutam só em
`127.0.0.1` da VPS e são alcançados por túnel:

```bash
ssh -N -i ~/.ssh/ctrade_vps \
  -L 8080:127.0.0.1:8080 -L 8180:127.0.0.1:8180 -L 3000:127.0.0.1:3000 -L 9090:127.0.0.1:9090   -L 3100:127.0.0.1:3100 \
  ubuntu@129.159.24.66
```

Com o túnel aberto, `http://localhost:8080` (API do staging), `:8180` (API do soak), `http://localhost:3000`
(Grafana), `:9090` e `:3100`
funcionam como no ambiente local. Feche o ctrade local antes, ou troque a porta da esquerda
(`-L 18080:127.0.0.1:8080`).

- Cada pessoa ou agente tem a própria chave em `~/.ssh/authorized_keys` da VPS: `mantenedor@ctrade-vps`
  e `claude@ctrade-vps`. Para revogar um acesso, apague a linha dele.
- As chaves admin da API (`X-Admin-Key`) são próprias de cada ambiente e ficam só no `.env` da VPS:
  `ADMIN_API_KEY_STG` (staging) e `ADMIN_API_KEY` (soak). Ler com `grep ^ADMIN_API_KEY_STG= .env | cut -d= -f2-`,
  sem imprimir.
- Senha do Grafana (usuário `admin`): `GRAFANA_ADMIN_PASSWORD` do mesmo `.env`.

## Layout na VPS

| Caminho | Conteúdo |
|---|---|
| `~/repos/cripto-trade.git`, `~/repos/ctrade-strategies.git` | repositórios *bare* que recebem o `git push` da máquina do operador |
| `~/ctrade` | checkout do ctrade; o Compose fica em `~/ctrade/deploy/vps` |
| `~/ctrade-strategies` | checkout das apps (`STRATEGIES_DIR`) |
| `~/ctrade/deploy/vps/.env` | segredos (permissão `600`, fora do git) |
| volumes `ctrade-vps_*` | dados do Postgres, Prometheus, Loki e Grafana |

Os repositórios do GitHub são privados e a VPS não tem credencial para eles. O código chega por
`git push` via SSH, da máquina de quem já tem o checkout.

## Preparar uma VPS nova

1. Ubuntu LTS com chave SSH; conferir que só a 22 está liberada na Security List / firewall do provedor.
2. Pacotes e relógio:
   ```bash
   sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2 docker-buildx git
   sudo usermod -aG docker ubuntu          # vale no próximo login
   timedatectl show -p NTPSynchronized     # precisa ser yes (assinatura HMAC da Binance)
   ```
   Se o NTP não sincronizar (a máquina ficou sem internet no boot), `sudo systemctl restart chrony`.
3. Repositórios *bare* e checkouts:
   ```bash
   mkdir -p ~/repos && git init --bare ~/repos/cripto-trade.git && git init --bare ~/repos/ctrade-strategies.git
   ```
   Da máquina do operador: `git push ubuntu@<vps>:repos/cripto-trade.git <ref>:refs/heads/<branch>` (e o
   mesmo para o `ctrade-strategies`). Na VPS: `git clone -b <branch> ~/repos/<repo>.git ~/<dir>`.
4. Segredos: `cd ~/ctrade/deploy/vps && cp .env.example .env && chmod 600 .env` e preencher. As senhas e
   tokens são gerados na própria VPS (`openssl rand -hex 24`, `uuidgen`); as chaves da Binance são as da
   testnet, uma conta para o staging e outra para o soak, criadas pelo mantenedor. O `up.sh` recusa as duas
   chaves iguais.

## Subir, atualizar e voltar versão

```bash
cd ~/ctrade/deploy/vps
./up.sh                     # usuarios do banco, build do ctrade (commit do checkout), sobe o staging, espera UP
./soak-up.sh                # sobe o soak (ctrade-soak + apps) com a imagem do up.sh, espera UP na 8180
./register-strategies.sh    # cadastra as cinco apps no soak (idempotente); espera VERIFIED em todas
./register-strategies.sh staging  # idem, as quatro apps do staging (uma vez, ou depois de trocar token ou versão)
./soak-down.sh              # para o soak; o schema soak fica para a proxima sessao
./versions.sh               # commits e imagens em uso, para o relatório da rodada
./backup-staging.sh         # backup do schema staging (o up.sh faz um antes de implantar)
./restore-test.sh           # teste de restore: backup, restaura num banco descartável e compara tabela a tabela
```

O soak usa a imagem que o `up.sh` montou por último: até a A6 da T74 (um checkout por ambiente), staging e soak
rodam o mesmo commit.

**Atualizar:** `git push` do novo commit para o *bare*, depois na VPS
`git -C ~/ctrade fetch && git -C ~/ctrade checkout <commit>` (idem `~/ctrade-strategies`) e `./up.sh`.
O `up.sh` só recria os containers cuja imagem mudou.

**Voltar versão:** o mesmo, com o commit anterior. O banco não volta junto: se a versão nova aplicou
migração do Flyway, a anterior pode não subir. No soak, resolver limpando o schema (`./reset-testnet-db.sh`); no
staging, pelo backup (abaixo), nunca limpando.

## Backup do staging (T74, A5)

- **Diário:** `pg_dump` só do schema `staging`, formato custom (comprimido), em `~/backups/staging/staging-<UTC>.dump`
  (pasta `700`, arquivo `600`), com retenção de 14 dias. O `up.sh` também faz um antes de toda implantação. O soak
  não tem backup: dado descartável. Cron da VPS (`crontab -e`), fora do horário das rodadas:

  ```
  15 3 * * * cd ~/ctrade/deploy/vps && ./backup-staging.sh >> ~/backups/staging/backup.log 2>&1
  ```

- **Cópia fora da VPS:** no computador do mantenedor, `deploy/vps/pull-backups.sh ubuntu@<ip-da-vps>
  ~/.ssh/<chave>` traz os backups que faltam para `~/ctrade-backups/staging` (`DEST=` muda a pasta) e apaga os
  locais com mais de 14 dias. Roda pela rotina diária do agente (A9).
- **Teste de restore:** `./restore-test.sh` faz um backup dentro de um snapshot do banco, restaura no banco
  descartável `ctrade_restore` e compara, tabela a tabela, número de linhas e md5 do conteúdo contra o staging no
  mesmo snapshot; qualquer diferença falha. O staging só é lido e o banco descartável é apagado no fim. Rodar
  depois de mudar o backup, de atualizar o Postgres ou quando o runbook pedir.
- **Restore de verdade:** procedimento no runbook (`docs/OPERATIONS_RUNBOOK.md`, "Restore do staging").

## Migração do soak antigo para staging + soak (T74, uma vez)

Antes da T74 o `ctrade-app` era o soak, no schema `public` com a conta atual. A migração:

1. O mantenedor põe no `.env` as variáveis do staging: `BINANCE_API_KEY_STG`, `BINANCE_API_SECRET_STG` (conta
   nova) e `DISCORD_WEBHOOK_URL_STG`. As variáveis sem sufixo continuam sendo as da conta atual, agora do soak.
2. Gerar na VPS, sem imprimir: `ADMIN_API_KEY_STG` (`uuidgen`), `DB_PASSWORD_STG` e `DB_PASSWORD_SOAK`
   (`openssl rand -hex 24`).
3. `docker compose stop ctrade-app trend reversion book breakout chaos`, checkout do commit novo e `./up.sh`: cria
   os usuários e schemas e sobe o staging vazio no schema `staging`.
4. O schema `public` fica com os dados antigos do soak, sem uso, até ser apagado (com aviso antes).

## Rodada da T53 na VPS

A rodada é largada e fechada por scripts, sem depender da sessão de quem opera. Estado da rodada em
`~/round/` (`ROUND_DIR`): `round.env` (número, início, fim, portfolio, símbolos, runners), `watch.log`, `ANOMALY`, `close.log`, `report.md`,
`stats-<app>.json` e `CLOSED` quando terminou.

1. **Largada:** `./round-start.sh <número> <duração>`, em horas (`72`) ou minutos (`45m`); `SYMBOLS="BTCUSDT ETHUSDT BNBUSDT SOLUSDT"`
   cria um runner por app em cada símbolo, com a alocação dividida entre eles (teste de carga, F4.14); o padrão é
   `BTCUSDT`. O ctrade permite um só runner ativo por estratégia, símbolo e portfolio. Recusa se houver rodada sem `CLOSED`, saldo
   `locked` na exchange (ordem no livro de antes da limpeza vira trade só na exchange) ou USDT livre abaixo do
   capital (o portfolio diria ter mais que a conta, e a conferência de saldo o poria em `HALT`). Para o ctrade,
   limpa o schema soak (`./reset-testnet-db.sh`, só o soak), sobe o soak (`./soak-up.sh`), cadastra as apps, cria o portfolio de
   `CAPITAL` USDT (padrão 1000; ex.: `CAPITAL=500 ./round-start.sh 15 6`) e um runner por app em cada símbolo com os limites da rodada 7 (40% cada, caos 20%, 1 posição,
   1 ordem pendente, perda máxima de 5% por lote) e liga os runners. Os limites e o roteiro completo
   estão na T53 (`.ai/tasks/T53-testnet-soak.md`, "Roteiro — Apps de estratégia da T60").
2. **Durante:** a largada deixa `./round-watch.sh` rodando até o fim, desacoplado. A cada 5 min ele grava em
   `watch.log` o estado operacional, `ERROR` desde a largada, fills e status dos runners. Cada mudança nas
   anomalias (estado fora de `OK`, algum `ERROR`, runner fora de `ACTIVE`) vai para o arquivo `ANOMALY` e para o
   Discord (`DISCORD_WEBHOOK_URL`). Para conferir: `tail ~/round/watch.log; cat ~/round/ANOMALY`.
3. **Fechamento:** a largada deixa `./round-close.sh` dormindo até o fim, desacoplado (`setsid nohup`).
   Ele para os runners, cancela as ordens dos runners da rodada que ainda estão em voo (o `halt` não
   cancela), confere `locked` e a conciliação de cada símbolo, lê o `/stats` das apps e gera o rascunho da T59 com
   `scripts/round_report.py` (T64). Classificar e assinar a rodada fica com quem opera, com `./versions.sh`
   no cabeçalho. Rodado à mão depois do fim, fecha na hora.
4. Derrubar uma app para o teste de indisponibilidade: `docker compose stop chaos`.
5. Fim da sessão: `./soak-down.sh` para o soak e devolve a CPU ao staging.

Todos os scripts de rodada (`round-*.sh`, `fault.sh`, `drill.sh`, `register-strategies.sh` sem argumento,
`reset-testnet-db.sh`) falam só com o soak: API na 8180, banco como `ctrade_soak`, métricas e logs com
`env="soak"`. Falhas de infraestrutura compartilhada (Postgres, Loki, rede da VPS) atingem também o staging e
ficam fora desses scripts.

### Testes curtos da fase 4

Roteiro, esperado e autorizações na T53 ("Roteiro — Fase 4"). Rodam sobre uma sessão curta de `round-start.sh`;
cada execução grava o passo a passo em `~/round/fault-*.log` ou `~/round/drill-*.log`.

- `./fault.sh`: provoca as falhas (restart do ctrade, queda de WebSocket, queda da exchange, confirmação perdida,
  ordem perdida, app fora do ar). O firewall vale só para o container do ctrade e as regras saem no fim, mesmo com
  erro ou Ctrl-C. Sem argumentos, mostra o uso.
- `./drill.sh`: kill switch, retomada, níveis de Safe Mode e ajustes de runner pela API. `PANIC_SELL`, limite de risco
  e política de liquidação só saem com `--autorizado`, depois da autorização do mantenedor.
- `round-lib.sh`: funções comuns dos dois (API, banco, retrato da sessão).

## Memória e disco

| Serviço | Limite |
|---|---|
| `ctrade-app` (staging), `ctrade-soak` | 2 GB cada (heap em 75%: `-XX:MaxRAMPercentage=75`) |
| `postgres` | 1 GB |
| `prometheus`, `loki` | 512 MB cada |
| `grafana` | 256 MB |
| cada app de estratégia | 256 MB |

Total ~4 GB só com o staging e ~7,5 GB com o soak no ar, de 11 GB. Retenção de 15 dias no Prometheus. O Loki usa a configuração padrão da imagem,
sem retenção automática (T66; detalhes no `loki.yml`). Logs dos containers em rotação: 3 × 20 MB por
serviço, e 10 × 100 MB em cada ctrade (`ctrade-app`, `ctrade-soak`), a reserva do Loki
(`docker compose logs --since <START> ctrade-soak`).

```bash
docker stats --no-stream                 # memória por container
docker system df && df -h /              # disco
docker image prune -f                    # imagens antigas depois de várias atualizações
```

Os volumes só somem com `docker compose down -v`, que apaga banco, métricas, logs e o Grafana.

## Oracle Always Free: cuidados

- Ficar em até 2 OCPU / 12 GB de A1 e 200 GB de disco somados. A documentação da Oracle fala em
  2/12 grátis; o aviso do console da conta ainda mostra 4/24.
- A Oracle recolhe instâncias Always Free ociosas: CPU, rede e memória abaixo de 20% por 7 dias. A pilha
  no ar usa só ~1,5 GB dos 12 GB (medido no smoke da T63), então **uma semana sem rodada pode levar à
  recolha**. Evitar: rodar ao menos uma rodada por semana ou converter a conta para Pay As You Go
  (mantendo 2/12, segue sem custo); as instâncias PAYG não entram nessa regra.
- O IP é efêmero: continua o mesmo em stop/start, mas muda se a instância for recriada. Atualizar este
  README nesse caso.
