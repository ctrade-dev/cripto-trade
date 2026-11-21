#!/usr/bin/env bash
# Teste de restore do staging (T74, A5): faz um backup pelo ./backup-staging.sh dentro de um snapshot do banco,
# restaura esse backup num banco descartavel (ctrade_restore) e compara, tabela a tabela, numero de linhas e md5 do
# conteudo contra o schema staging no mesmo snapshot. Qualquer diferenca falha o teste. O staging so e lido; o
# banco descartavel e apagado no fim, passe ou falhe.
#
# Uso:  ./restore-test.sh
set -euo pipefail

cd "$(dirname "$0")"

RESTORE_DB=ctrade_restore
psql_admin() { docker compose exec -T postgres psql -U ctrade -v ON_ERROR_STOP=1 -qAt "$@"; }

# Uma linha por tabela do schema staging: nome|linhas|md5 das linhas ordenadas.
checksum_sql="SELECT format('SELECT %L || ''|'' || count(*) || ''|'' || coalesce(md5(string_agg(t::text, ''\n'' ORDER BY t::text)), ''-'') FROM %I.%I t', tablename, schemaname, tablename) FROM pg_tables WHERE schemaname = 'staging' ORDER BY tablename \gexec"

work="$(mktemp -d)"
cleanup() {
  [[ -n "${SNAP_PID:-}" ]] && kill "$SNAP_PID" 2>/dev/null || true
  psql_admin -d postgres -c "DROP DATABASE IF EXISTS $RESTORE_DB" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

# Sessao que segura o snapshot: o backup e a conferencia do original enxergam o mesmo estado do staging.
coproc SNAP { docker compose exec -T postgres psql -U ctrade -d ctrade -v ON_ERROR_STOP=1 -qAt; }
echo "BEGIN ISOLATION LEVEL REPEATABLE READ; SELECT pg_export_snapshot();" >&"${SNAP[1]}"
read -r -t 30 snapshot <&"${SNAP[0]}"
[[ "$snapshot" =~ ^[0-9A-F-]+$ ]] || { echo "nao consegui exportar o snapshot do banco" >&2; exit 1; }

backup="$(SNAPSHOT="$snapshot" ./backup-staging.sh)"
echo "backup: $backup"

printf '%s\nSELECT %s;\n' "$checksum_sql" "'__fim__'" >&"${SNAP[1]}"
while read -r -t 120 line <&"${SNAP[0]}"; do
  [[ "$line" == "__fim__" ]] && break
  echo "$line" >> "$work/original"
done
echo "ROLLBACK;" >&"${SNAP[1]}"

psql_admin -d postgres -c "DROP DATABASE IF EXISTS $RESTORE_DB" -c "CREATE DATABASE $RESTORE_DB" >/dev/null
docker compose exec -T postgres pg_restore -U ctrade -d "$RESTORE_DB" --exit-on-error < "$backup"
echo "$checksum_sql" | psql_admin -d "$RESTORE_DB" > "$work/restaurado"

tables="$(wc -l < "$work/original")"
rows="$(awk -F'|' '{s += $2} END {print s + 0}' "$work/original")"
if (( tables == 0 )); then
  echo "FALHOU: o schema staging nao tem tabelas" >&2
  exit 1
fi
if ! diff "$work/original" "$work/restaurado" > "$work/diff"; then
  echo "FALHOU: o restore difere do original (tabela|linhas|md5; < original, > restaurado):" >&2
  cat "$work/diff" >&2
  exit 1
fi
echo "OK: $tables tabelas e $rows linhas restauradas iguais ao original."
