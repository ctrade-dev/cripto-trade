#!/usr/bin/env bash
# Backup do staging (T74, A5): pg_dump so do schema staging, formato custom (comprimido), em
# $BACKUP_DIR/staging-<UTC>.dump com permissao 600; apaga os backups com mais de 14 dias. O soak nao tem backup.
# Roda pelo cron diario da VPS e pelo ./up.sh antes de toda implantacao. Imprime o caminho do backup criado.
#
# Uso:  ./backup-staging.sh
#       SNAPSHOT=<id> ./backup-staging.sh   (pg_dump --snapshot; usado pelo ./restore-test.sh)
set -euo pipefail

cd "$(dirname "$0")"

BACKUP_DIR="${BACKUP_DIR:-$HOME/backups/staging}"
RETENTION_DAYS=14

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

target="$BACKUP_DIR/staging-$(date -u +%Y%m%dT%H%M%SZ).dump"
partial="$target.partial"
trap 'rm -f "$partial"' EXIT

snapshot=()
[[ -n "${SNAPSHOT:-}" ]] && snapshot=(--snapshot="$SNAPSHOT")

umask 077
docker compose exec -T postgres pg_dump -U ctrade -d ctrade -n staging -Fc "${snapshot[@]}" > "$partial"

# Um dump legivel tem o schema e o historico do Flyway; arquivo truncado ou vazio nao vira backup.
if ! docker compose exec -T postgres pg_restore -l < "$partial" | grep -q 'TABLE DATA staging flyway_schema_history'; then
  echo "backup invalido: pg_restore -l nao achou staging.flyway_schema_history em $partial" >&2
  exit 1
fi
mv "$partial" "$target"

find "$BACKUP_DIR" -maxdepth 1 -name 'staging-*.dump' -mtime +"$((RETENTION_DAYS - 1))" -delete

echo "$target"
