#!/usr/bin/env bash
# Imprime as versoes do ambiente de soak (T63) para o relatorio da rodada (T59):
# commit do ctrade, commit do ctrade-strategies e imagens em uso.
#
# Uso:  ./versions.sh
set -euo pipefail

cd "$(dirname "$0")"

strategies_dir="$(grep -E '^STRATEGIES_DIR=' .env | cut -d= -f2-)"

echo "ctrade:            $(git -C ../.. rev-parse --short HEAD) ($(git -C ../.. log -1 --format=%cs))$(git -C ../.. diff --quiet || echo ' +alteracoes locais')"
echo "ctrade-strategies: $(git -C "$strategies_dir" rev-parse --short HEAD) ($(git -C "$strategies_dir" log -1 --format=%cs))$(git -C "$strategies_dir" diff --quiet || echo ' +alteracoes locais')"
echo
docker compose images
