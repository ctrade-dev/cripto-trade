#!/usr/bin/env bash
# Limpa todos os dados do soak no Postgres da VPS (T63, T74) para recomecar uma rodada. Mantem schema e historico
# do Flyway. Equivalente ao scripts/reset-testnet-db.ps1 da maquina local.
#
# Uso:  ./reset-testnet-db.sh            (pede confirmacao)
#       ./reset-testnet-db.sh --force    (sem confirmacao)
#
# So vale para o soak: conecta como ctrade_soak, que so enxerga o schema soak, e confere o schema antes de apagar.
# O staging (schema staging, usuario ctrade_stg) fica fora do alcance pela permissao do banco. Recusa rodar com o
# ctrade-soak no ar: ele guarda estado em memoria que ficaria inconsistente com o banco vazio.
set -euo pipefail

cd "$(dirname "$0")"

psql_soak() { docker compose exec -T postgres psql -U ctrade_soak -d ctrade -v ON_ERROR_STOP=1 "$@"; }

if [[ -n "$(docker compose ps --status running -q ctrade-soak)" ]]; then
  echo "O ctrade-soak esta rodando. Pare antes: docker compose stop ctrade-soak" >&2
  exit 1
fi

if [[ -z "$(docker compose ps --status running -q postgres)" ]]; then
  echo "O Postgres deste ambiente nao esta rodando. Suba com: ./up.sh" >&2
  exit 1
fi

schema="$(psql_soak -t -A -c 'SELECT current_schema()' </dev/null)"
if [[ "$schema" != "soak" ]]; then
  echo "ctrade_soak conectou no schema '$schema', nao em 'soak'. Abortado." >&2
  exit 1
fi

if [[ "$(psql_soak -t -A -c "SELECT to_regclass('soak.portfolios') IS NOT NULL" </dev/null)" != "t" ]]; then
  echo "Schema soak ainda sem tabelas (o ctrade-soak migra na primeira subida): nada a limpar."
  exit 0
fi

if [[ "${1:-}" != "--force" ]]; then
  read -r -p "Apagar TODOS os dados do schema soak? Digite 'sim' para confirmar: " answer
  if [[ "$answer" != "sim" ]]; then
    echo "Cancelado."
    exit 0
  fi
fi

psql_soak < ../../scripts/reset_testnet_database.sql

count="$(psql_soak -t -A -c 'SELECT COUNT(*) FROM portfolios' </dev/null)"
echo "Schema soak limpo. Portfolios restantes: $count"
