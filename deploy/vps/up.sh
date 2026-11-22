#!/usr/bin/env bash
# Sobe o staging da VPS (T63, T74): Postgres com os usuarios por ambiente (./db-environments.sh), backup do
# staging antes de implantar (./backup-staging.sh), build do ctrade (commit do checkout atual), observabilidade e o
# ctrade-app; espera o /actuator/health responder UP.
# O soak (perfil Compose "soak") nao sobe aqui: ./soak-up.sh.
#
# Uso:  ./up.sh
# Nunca imprime o conteudo do .env.
set -euo pipefail

cd "$(dirname "$0")"

if [[ ! -f .env ]]; then
  echo "Falta o .env. Copie o modelo: cp .env.example .env && chmod 600 .env" >&2
  exit 1
fi
if [[ "$(stat -c %a .env)" != "600" ]]; then
  echo "O .env precisa de permissao 600 (chmod 600 .env)." >&2
  exit 1
fi

# O compose interpola o arquivo inteiro: as variaveis do soak tambem precisam existir.
required=(BINANCE_API_KEY_STG BINANCE_API_SECRET_STG ADMIN_API_KEY_STG DB_PASSWORD_STG DISCORD_WEBHOOK_URL_STG
          CHANNEL_TOKEN FOLLOWING_TOKEN
          BINANCE_API_KEY BINANCE_API_SECRET ADMIN_API_KEY DB_PASSWORD_SOAK
          DB_PASSWORD GRAFANA_ADMIN_PASSWORD STRATEGIES_DIR
          TREND_TOKEN REVERSION_TOKEN BOOK_TOKEN BREAKOUT_TOKEN CHAOS_TOKEN)
missing=()
for var in "${required[@]}"; do
  grep -Eq "^${var}=.+" .env || missing+=("$var")
done
if (( ${#missing[@]} )); then
  echo "Variaveis vazias no .env: ${missing[*]}" >&2
  exit 1
fi

env_value() { grep -E "^$1=" .env | cut -d= -f2-; }
if [[ "$(env_value BINANCE_API_KEY_STG)" == "$(env_value BINANCE_API_KEY)" ]]; then
  echo "BINANCE_API_KEY_STG igual a BINANCE_API_KEY: staging e soak precisam de contas diferentes." >&2
  exit 1
fi
if [[ "$(env_value ADMIN_API_KEY_STG)" == "$(env_value ADMIN_API_KEY)" ]]; then
  echo "ADMIN_API_KEY_STG igual a ADMIN_API_KEY: cada ambiente tem a sua." >&2
  exit 1
fi

strategies_dir="$(env_value STRATEGIES_DIR)"
if [[ ! -f "$strategies_dir/Dockerfile" ]]; then
  echo "STRATEGIES_DIR=$strategies_dir nao tem o checkout do ctrade-strategies." >&2
  exit 1
fi

docker compose up -d postgres
./db-environments.sh
# Toda implantacao no staging passa por um backup: migracao que falhar volta pelo restore (runbook, T74 A5).
echo "backup do staging antes de implantar: $(./backup-staging.sh)"
docker compose up -d --build --remove-orphans

echo "Esperando o staging responder UP em 127.0.0.1:8080..."
for _ in $(seq 60); do
  if curl -fsS http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
    echo "staging UP."
    docker compose ps --format 'table {{.Service}}\t{{.Status}}'
    exit 0
  fi
  sleep 5
done

echo "staging nao respondeu UP em 5 minutos. Logs: docker compose logs --tail 200 ctrade-app" >&2
exit 1
