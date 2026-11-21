#!/usr/bin/env bash
# Para o soak da VPS (T74): ctrade-soak e apps de soak. O schema soak fica intacto para a proxima sessao; o
# staging continua no ar.
#
# Uso:  ./soak-down.sh
set -euo pipefail

cd "$(dirname "$0")"

docker compose --profile soak stop ctrade-soak trend reversion book breakout chaos
echo "soak parado; staging segue no ar."
