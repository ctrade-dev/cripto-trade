#!/usr/bin/env bash
# Sobe o soak da VPS (T74): ctrade-soak (porta 8180, schema soak, conta do soak) e as apps de soak, sem tocar no
# staging. Usa a imagem do ctrade que o ./up.sh montou; as apps sao montadas de STRATEGIES_DIR.
#
# Uso:  ./soak-up.sh          (o staging precisa estar no ar: ./up.sh)
set -euo pipefail

cd "$(dirname "$0")"

SOAK_APPS=(trend reversion book breakout chaos)

if [[ -z "$(docker compose ps --status running -q postgres)" ]]; then
  echo "Postgres fora do ar. Suba o staging antes: ./up.sh" >&2
  exit 1
fi
./db-environments.sh >/dev/null

docker compose --profile soak build "${SOAK_APPS[@]}"
docker compose --profile soak up -d --no-build ctrade-soak "${SOAK_APPS[@]}"

echo "Esperando o soak responder UP em 127.0.0.1:8180..."
for _ in $(seq 60); do
  if curl -fsS http://127.0.0.1:8180/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
    echo "soak UP."
    exit 0
  fi
  sleep 5
done

echo "soak nao respondeu UP em 5 minutos. Logs: docker compose logs --tail 200 ctrade-soak" >&2
exit 1
