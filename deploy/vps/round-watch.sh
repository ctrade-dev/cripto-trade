#!/usr/bin/env bash
# Monitor da rodada da T53 em $ROUND_DIR/round.env: a cada 5 min imprime estado operacional, ERROR desde
# a largada (Prometheus), fills e status dos runners. Sai com 2 na primeira anomalia (estado fora de OK,
# algum ERROR, runner fora de ACTIVE) e com 0 ("CHECKPOINT") ao fim do lote ou da rodada.
#
# Uso:  ./round-watch.sh [segundos]       (lote padrao: 14400 = 4h)
set -uo pipefail

cd "$(dirname "$0")"
ROUND_DIR=${ROUND_DIR:-$HOME/round}
source "$ROUND_DIR/round.env"
K=$(grep ^ADMIN_API_KEY .env | cut -d= -f2-); B=http://127.0.0.1:8080/api
END_TS=$(date -u -d "$END" +%s)
UNTIL=$(( $(date +%s) + ${1:-14400} )); (( UNTIL > END_TS )) && UNTIL=$END_TS
psql() { docker compose exec -T postgres psql -U ctrade -d ctrade -tAc "$1"; }

while (( $(date +%s) < UNTIL )); do
  w=$(( $(date +%s) - $(date -u -d "$START" +%s) ))
  st=$(curl -s -m 20 -H "X-Admin-Key: $K" $B/admin/operational-state | python3 -c "
import json,sys
j=json.load(sys.stdin); bad=[c['id']+'='+c['status']+' ('+c['summary']+')' for c in j['checks'] if c['status']!='OK']
print(j['status']+('|'+'; '.join(bad) if bad else ''))" 2>/dev/null || echo UNREACHABLE)
  err=$(curl -s -G http://127.0.0.1:9090/api/v1/query --data-urlencode "query=sum(increase(logback_events_total{level=\"error\"}[${w}s]))" | python3 -c "import json,sys;r=json.load(sys.stdin)['data']['result'];print(round(float(r[0]['value'][1])) if r else 0)" 2>/dev/null)
  runners=$(psql "select string_agg(strategy_name||':'||status, ' ' order by strategy_name) from strategy_runners")
  fills=$(psql "select count(*) from transactions where status='FILLED'")
  echo "$(date -u +%H:%M) estado=$st errors=$err fills=$fills | $runners"
  [[ $st == OK* ]] || { echo "ANOMALIA: estado"; exit 2; }
  (( ${err:-0} > 0 )) && { echo "ANOMALIA: ERROR"; exit 2; }
  tr ' ' '\n' <<< "$runners" | grep -v ':ACTIVE$' | grep -q . && { echo "ANOMALIA: runner"; exit 2; }
  (( $(date +%s) < UNTIL )) && sleep 300
done
echo CHECKPOINT
