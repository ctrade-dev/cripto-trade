# Ambiente de soak na VPS (T63)

Roda as rodadas longas da T53 (24h e 72h) fora da máquina do mantenedor: ctrade no perfil `testnet`,
Postgres, Prometheus, Loki, Grafana e as cinco apps de estratégia da T60, num único Compose.

**Somente testnet.** Nenhuma chave ou dado de produção entra neste ambiente.

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
  -L 8080:127.0.0.1:8080 -L 3000:127.0.0.1:3000 -L 9090:127.0.0.1:9090 -L 3100:127.0.0.1:3100 \
  ubuntu@129.159.24.66
```

Com o túnel aberto, `http://localhost:8080` (API), `http://localhost:3000` (Grafana), `:9090` e `:3100`
funcionam como no ambiente local. Feche o ctrade local antes, ou troque a porta da esquerda
(`-L 18080:127.0.0.1:8080`).

- Cada pessoa ou agente tem a própria chave em `~/.ssh/authorized_keys` da VPS: `mantenedor@ctrade-vps`
  e `claude@ctrade-vps`. Para revogar um acesso, apague a linha dele.
- A chave admin da API (`X-Admin-Key`) é própria da VPS e fica só no `.env` de lá:
  `ssh ubuntu@<vps> "grep ^ADMIN_API_KEY= ~/ctrade/deploy/vps/.env | cut -d= -f2"`.
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
   testnet.

## Subir, atualizar e voltar versão

```bash
cd ~/ctrade/deploy/vps
./up.sh                     # build do ctrade (commit do checkout) e das apps, sobe tudo, espera UP
./register-strategies.sh    # cadastra as cinco apps (idempotente); espera VERIFIED em todas
./versions.sh               # commits e imagens em uso, para o relatório da rodada
```

**Atualizar:** `git push` do novo commit para o *bare*, depois na VPS
`git -C ~/ctrade fetch && git -C ~/ctrade checkout <commit>` (idem `~/ctrade-strategies`) e `./up.sh`.
O `up.sh` só recria os containers cuja imagem mudou.

**Voltar versão:** o mesmo, com o commit anterior. O banco não volta junto: se a versão nova aplicou
migração do Flyway, a anterior pode não subir. Na testnet, resolver limpando o banco (abaixo).

## Rodada da T53 na VPS

1. Limpar o banco (dado de testnet é descartável). Antes, **cancelar as ordens que descansam no livro**
   (`GET /api/runners/<id>/transactions` → em voo → `POST .../orders/<tx>/cancel`): o `halt` não as
   cancela, e depois da limpeza o ctrade não as conhece mais. Uma que preencha durante a rodada seguinte
   aparece como trade só na exchange. Conferir com `GET /api/exchanges/BINANCE/balances?asset=BTC`
   (`locked` = 0).
   ```bash
   docker compose stop ctrade-app
   ./reset-testnet-db.sh --force       # recusa com o ctrade no ar
   ./up.sh && ./register-strategies.sh
   ```
2. Com o túnel aberto, seguir o roteiro da T53 a partir do portfolio e dos runners
   (`.ai/tasks/T53-testnet-soak.md`, "Roteiro — Apps de estratégia da T60", passo 3). As apps já estão
   cadastradas com `baseUrl` `http://<serviço>:8080/v1`. O runner é criado com a fonte de preço
   (`"allowedMarketDataSources":["BINANCE"]`); sem ela a criação é recusada (T67).
3. No fim: relatório da T59 com `./versions.sh` no cabeçalho. A coleta sai de
   `python3 ~/ctrade/scripts/round_report.py --start <início> --env-file .env --portfolio <id>` (T64). Estatísticas das apps (`/stats`), que não
   têm porta publicada: `docker compose exec -T chaos python -c "import urllib.request;print(urllib.request.urlopen('http://localhost:8080/stats').read().decode())"`.
4. Derrubar uma app para o teste de indisponibilidade: `docker compose stop chaos`.

## Memória e disco

| Serviço | Limite |
|---|---|
| `ctrade-app` | 2 GB (heap em 75%: `-XX:MaxRAMPercentage=75`) |
| `postgres` | 1 GB |
| `prometheus`, `loki` | 512 MB cada |
| `grafana` | 256 MB |
| cada app de estratégia | 256 MB |

Total ~5,5 GB de 12 GB. Retenção de 15 dias no Prometheus. O Loki usa a configuração padrão da imagem,
sem retenção automática (T66; detalhes no `loki.yml`). Logs dos containers em rotação: 3 × 20 MB por
serviço, e 10 × 100 MB no `ctrade-app`, que guarda uma rodada de 72h e é a reserva do Loki
(`docker compose logs --since <START> ctrade-app`).

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
