#!/usr/bin/env bash
# Cadastra as apps de estrategia no catalogo de um ambiente (T54), com baseUrl interno e token: as cinco da T60 no
# ctrade-soak (porta 8180) ou as do staging (T74 A7, porta 8080). O cadastro repetido com a mesma definicao e
# idempotente: pode rodar de novo depois de cada subida. Falha se alguma nao ficar VERIFIED.
#
# Uso:  ./register-strategies.sh [soak|staging]     (padrao: soak)
set -euo pipefail

cd "$(dirname "$0")"

env_value() { grep -E "^$1=" .env | cut -d= -f2-; }

# servico | strategyRef | name | version | variavel do token
case "${1:-soak}" in
  soak)
    admin_key="$(env_value ADMIN_API_KEY)"
    api="http://127.0.0.1:8180/api/admin/strategies"
    apps=(
      "trend|c7a1b2d3-0001-4000-8000-000000000001|SoakTrend|1.0.0|TREND_TOKEN"
      "reversion|c7a1b2d3-0002-4000-8000-000000000002|SoakReversion|1.0.0|REVERSION_TOKEN"
      "book|c7a1b2d3-0003-4000-8000-000000000003|SoakBook|1.0.0|BOOK_TOKEN"
      "breakout|c7a1b2d3-0004-4000-8000-000000000004|SoakBreakout|1.0.0|BREAKOUT_TOKEN"
      "chaos|c7a1b2d3-0005-4000-8000-000000000005|SoakChaos|1.0.0|CHAOS_TOKEN"
    )
    ;;
  staging)
    admin_key="$(env_value ADMIN_API_KEY_STG)"
    api="http://127.0.0.1:8080/api/admin/strategies"
    # name e version iguais aos que a app declara em /v2/meta; o XRP difere so no strategyRef (o STRATEGY_REF do
    # docker-compose.yml).
    apps=(
      "channel|3f6b2a4e-7c1d-4e58-9a0b-5d2c8e1f4a73|ChannelBreakout|1.0.0|CHANNEL_TOKEN"
      "channel-xrp|b5df6e62-0527-447f-bee0-74242bc8f894|ChannelBreakout|1.0.0|CHANNEL_TOKEN"
      "following|89dce8d5-8343-48b1-b899-ad96af683c4b|TrendFollowing|1.0.1|FOLLOWING_TOKEN"
      "following-xrp|f2c6e9af-f380-4ce4-8ee3-81a61d3ee643|TrendFollowing|1.0.1|FOLLOWING_TOKEN"
    )
    ;;
  *)
    echo "uso: ./register-strategies.sh [soak|staging]" >&2
    exit 2
    ;;
esac

failed=0
for app in "${apps[@]}"; do
  IFS='|' read -r service ref name version token_var <<< "$app"
  body="$(printf '{"strategyRef":"%s","name":"%s","version":"%s","baseUrl":"http://%s:8080/v2","token":"%s"}' \
    "$ref" "$name" "$version" "$service" "$(env_value "$token_var")")"
  response="$(curl -sS -X POST "$api" \
    -H 'Content-Type: application/json' \
    -H "X-Admin-Key: $admin_key" \
    -H "X-Reason: cadastro das apps de estrategia (${1:-soak}) na VPS" \
    -H 'X-Operator: register-strategies.sh' \
    -d "$body" -w '\n%{http_code}')"
  status="${response##*$'\n'}"
  verification="$(grep -o '"verification":"[A-Z_]*"' <<< "$response" | head -1 | cut -d'"' -f4 || true)"
  echo "$service: HTTP $status verification=${verification:-?}"
  # Sem verification e porque o ctrade recusou o cadastro: mostra o motivo (o corpo nao leva o token).
  [[ -n "$verification" ]] || echo "  ${response%$'\n'*}" >&2
  [[ "$status" =~ ^2 && "$verification" == "VERIFIED" ]] || failed=1
done

exit "$failed"
