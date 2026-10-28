#!/usr/bin/env bash
# Sobe o ambiente de soak da VPS (T63): build do ctrade (commit do checkout atual) e das apps
# (STRATEGIES_DIR), sobe tudo e espera o /actuator/health responder UP.
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

required=(BINANCE_API_KEY BINANCE_API_SECRET ADMIN_API_KEY DB_PASSWORD GRAFANA_ADMIN_PASSWORD STRATEGIES_DIR
          TREND_TOKEN REVERSION_TOKEN BOOK_TOKEN BREAKOUT_TOKEN CHAOS_TOKEN)
missing=()
for var in "${required[@]}"; do
  grep -Eq "^${var}=.+" .env || missing+=("$var")
done
if (( ${#missing[@]} )); then
  echo "Variaveis vazias no .env: ${missing[*]}" >&2
  exit 1
fi

strategies_dir="$(grep -E '^STRATEGIES_DIR=' .env | cut -d= -f2-)"
if [[ ! -f "$strategies_dir/Dockerfile" ]]; then
  echo "STRATEGIES_DIR=$strategies_dir nao tem o checkout do ctrade-strategies." >&2
  exit 1
fi

docker compose up -d --build --remove-orphans

echo "Esperando o ctrade responder UP em 127.0.0.1:8080..."
for _ in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
    echo "ctrade UP."
    docker compose ps --format 'table {{.Service}}\t{{.Status}}'
    exit 0
  fi
  sleep 5
done

echo "ctrade nao respondeu UP em 5 minutos. Logs: docker compose logs --tail 200 ctrade-app" >&2
exit 1
