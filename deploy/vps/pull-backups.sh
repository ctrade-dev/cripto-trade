#!/usr/bin/env bash
# Copia fora da VPS dos backups do staging (T74, A5, decisao 4): roda no computador do mantenedor (pela rotina
# diaria do agente), traz os backups que faltam e apaga os locais com mais de 14 dias.
#
# Uso:  ./pull-backups.sh <usuario@host> [chave-ssh]
#       DEST=<pasta> ./pull-backups.sh ...   (padrao: ~/ctrade-backups/staging)
set -euo pipefail

host="${1:?uso: ./pull-backups.sh <usuario@host> [chave-ssh]}"
ssh_opts=(-o BatchMode=yes)
[[ -n "${2:-}" ]] && ssh_opts+=(-i "$2")
DEST="${DEST:-$HOME/ctrade-backups/staging}"
RETENTION_DAYS=14

mkdir -p "$DEST"

pulled=0
for remote in $(ssh "${ssh_opts[@]}" "$host" 'ls -1 ~/backups/staging/staging-*.dump 2>/dev/null'); do
  name="$(basename "$remote")"
  [[ "$name" =~ ^staging-[0-9]{8}T[0-9]{6}Z\.dump$ ]] || continue
  [[ -f "$DEST/$name" ]] && continue
  scp -q -p "${ssh_opts[@]}" "$host:$remote" "$DEST/$name.partial"
  mv "$DEST/$name.partial" "$DEST/$name"
  pulled=$((pulled + 1))
done

find "$DEST" -maxdepth 1 -name 'staging-*.dump' -mtime +"$((RETENTION_DAYS - 1))" -delete
latest="$(ls -1 "$DEST"/staging-*.dump 2>/dev/null | tail -1)"
echo "copiados: $pulled; mais recente: ${latest:-nenhum}"
