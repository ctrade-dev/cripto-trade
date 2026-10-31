#!/usr/bin/env bash
# Fechamento de uma rodada da T53 na VPS, sem depender da sessao do agente. Dorme ate END de
# $ROUND_DIR/round.env, para os runners, cancela as ordens em voo, confere `locked` e a conciliacao e
# gera o rascunho do relatorio em $ROUND_DIR/report.md e o /stats de cada app em stats-<app>.json. Termina gravando $ROUND_DIR/CLOSED.
#
# Uso:  chamado por ./round-start.sh; rodar a mao fecha na hora se END ja passou.
set -uo pipefail

cd "$(dirname "$0")"
ROUND_DIR=${ROUND_DIR:-$HOME/round}
source "$ROUND_DIR/round.env"
K=$(grep ^ADMIN_API_KEY .env | cut -d= -f2-); B=http://127.0.0.1:8080/api
REASON="X-Reason: fim da rodada $N da T53"
log() { echo "$(date -u +%H:%M:%SZ) $*"; }
psql() { docker compose exec -T postgres psql -U ctrade -d ctrade -tAc "$1"; }

wait_s=$(( $(date -u -d "$END" +%s) - $(date +%s) ))
log "dormindo ${wait_s}s ate $END"
(( wait_s > 0 )) && sleep $wait_s

# O halt nao cancela as LIMIT no livro (README, "Rodada da T53 na VPS").
for r in $RUNNERS; do
  log "halt $r: $(curl -s -o /dev/null -w %{http_code} -X POST -H "X-Admin-Key: $K" -H "$REASON" $B/admin/runners/$r/halt)"
done
sleep 20
LIST=$(printf "'%s'," $RUNNERS)
IN_FLIGHT="status in ('SUBMITTED','PARTIAL') and runner_id in (${LIST%,})"
psql "select runner_id||'/orders/'||id from transactions where $IN_FLIGHT" | while read -r p; do
  [[ -n "$p" ]] && log "cancel $p: $(curl -s -o /dev/null -w %{http_code} -X POST -H "X-Admin-Key: $K" -H "$REASON" $B/admin/runners/$p/cancel)"
done
sleep 30
log "em voo: $(psql "select count(*) from transactions where $IN_FLIGHT")"
for a in BTC USDT; do
  log "saldo $a: $(curl -s -H "X-Admin-Key: $K" "$B/exchanges/BINANCE/balances?asset=$a" | python3 -c "import json,sys;b=json.load(sys.stdin)['balances'][0];print('free',b['free'],'locked',b['locked'])")"
done
log "conciliacao: $(curl -s -H "X-Admin-Key: $K" "$B/reconciliation?symbol=BTCUSDT&from=$START&to=$(date -u +%Y-%m-%dT%H:%M:%SZ)" | python3 -c "import json,sys;print(json.load(sys.stdin)['summary'])")"
# As apps nao tem porta publicada: /stats lido de dentro do container, com o token dele.
STATS_PY='import os,urllib.request as u;print(u.urlopen(u.Request("http://localhost:8080/stats",headers={"Authorization":"Bearer "+os.environ["STRATEGY_TOKEN"]})).read().decode())'
for app in trend reversion book breakout chaos; do
  docker compose exec -T $app python -c "$STATS_PY" > "$ROUND_DIR/stats-$app.json" 2>&1
done
python3 ../../scripts/round_report.py --start "$START" --end "$END" --env-file .env --portfolio "$PORTFOLIO" > "$ROUND_DIR/report.md" 2>&1
log "relatorio: $(wc -l < "$ROUND_DIR/report.md") linhas em $ROUND_DIR/report.md"
date -u +%Y-%m-%dT%H:%M:%SZ > "$ROUND_DIR/CLOSED"
