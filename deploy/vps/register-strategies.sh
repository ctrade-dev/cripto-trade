#!/usr/bin/env bash
# Cadastra as cinco apps da T60 no catalogo do ctrade com baseUrl interno e token (T54).
# O cadastro repetido com a mesma definicao e idempotente: pode rodar de novo depois de cada subida.
#
# Uso:  ./register-strategies.sh
set -euo pipefail

cd "$(dirname "$0")"

env_value() { grep -E "^$1=" .env | cut -d= -f2-; }

admin_key="$(env_value ADMIN_API_KEY)"
api="http://127.0.0.1:8080/api/admin/strategies"

# servico | strategyRef | name | variavel do token
apps=(
  "trend|c7a1b2d3-0001-4000-8000-000000000001|SoakTrend|TREND_TOKEN"
  "reversion|c7a1b2d3-0002-4000-8000-000000000002|SoakReversion|REVERSION_TOKEN"
  "book|c7a1b2d3-0003-4000-8000-000000000003|SoakBook|BOOK_TOKEN"
  "breakout|c7a1b2d3-0004-4000-8000-000000000004|SoakBreakout|BREAKOUT_TOKEN"
  "chaos|c7a1b2d3-0005-4000-8000-000000000005|SoakChaos|CHAOS_TOKEN"
)

failed=0
for app in "${apps[@]}"; do
  IFS='|' read -r service ref name token_var <<< "$app"
  body="$(printf '{"strategyRef":"%s","name":"%s","version":"1.0.0","baseUrl":"http://%s:8080/v1","token":"%s"}' \
    "$ref" "$name" "$service" "$(env_value "$token_var")")"
  response="$(curl -sS -X POST "$api" \
    -H 'Content-Type: application/json' \
    -H "X-Admin-Key: $admin_key" \
    -H 'X-Reason: T63 cadastro das apps de soak na VPS' \
    -H 'X-Operator: register-strategies.sh' \
    -d "$body" -w '\n%{http_code}')"
  status="${response##*$'\n'}"
  verification="$(grep -o '"verification":"[A-Z_]*"' <<< "$response" | head -1 | cut -d'"' -f4)"
  echo "$service: HTTP $status verification=${verification:-?}"
  [[ "$status" =~ ^2 && "$verification" == "VERIFIED" ]] || failed=1
done

exit "$failed"
