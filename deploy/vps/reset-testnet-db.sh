#!/usr/bin/env bash
# Limpa todos os dados da aplicacao no Postgres do ambiente de soak da VPS (T63) para recomecar uma rodada
# da T53. Mantem schema e historico do Flyway. Equivalente ao scripts/reset-testnet-db.ps1 da maquina local.
#
# Uso:  ./reset-testnet-db.sh            (pede confirmacao)
#       ./reset-testnet-db.sh --force    (sem confirmacao)
#
# So vale para a testnet. Recusa rodar com o ctrade-app no ar: ele guarda estado em memoria que ficaria
# inconsistente com o banco vazio. Pare com `docker compose stop ctrade-app`, rode este script e suba de novo.
set -euo pipefail

cd "$(dirname "$0")"

if ! grep -q 'SPRING_PROFILES_ACTIVE: testnet' docker-compose.yml; then
  echo "O compose deste diretorio nao e o da testnet. Abortado." >&2
  exit 1
fi

if [[ -n "$(docker compose ps --status running -q ctrade-app)" ]]; then
  echo "O ctrade-app esta rodando. Pare antes: docker compose stop ctrade-app" >&2
  exit 1
fi

if [[ -z "$(docker compose ps --status running -q postgres)" ]]; then
  echo "O Postgres deste ambiente nao esta rodando. Suba com: docker compose up -d postgres" >&2
  exit 1
fi

if [[ "${1:-}" != "--force" ]]; then
  read -r -p "Apagar TODOS os dados do banco ctrade da VPS de testnet? Digite 'sim' para confirmar: " answer
  if [[ "$answer" != "sim" ]]; then
    echo "Cancelado."
    exit 0
  fi
fi

docker compose exec -T postgres psql -U ctrade -d ctrade -v ON_ERROR_STOP=1 < ../../scripts/reset_testnet_database.sql

count="$(docker compose exec -T postgres psql -U ctrade -d ctrade -t -A -c 'SELECT COUNT(*) FROM portfolios')"
echo "Banco limpo. Portfolios restantes: $count"
