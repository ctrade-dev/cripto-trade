#!/usr/bin/env bash
# Monitor da rodada da T53 em $ROUND_DIR/round.env. Roda na VPS, desacoplado, ate o fim da rodada: a largada
# o inicia e ele nao depende da sessao de quem opera (T68).
#
# A cada 5 min grava em watch.log o estado operacional, ERROR desde a largada (Prometheus), fills e status dos
# runners. Anomalia e estado fora de OK, algum ERROR ou runner fora de ACTIVE: cada mudanca do conjunto de
# anomalias vai para o arquivo ANOMALY e para o Discord (DISCORD_WEBHOOK_URL do .env).
#
# Uso:  ./round-watch.sh       (ROUND_DIR=~/round; INTERVAL=300)
set -uo pipefail

cd "$(dirname "$0")"
ROUND_DIR=${ROUND_DIR:-$HOME/round}
INTERVAL=${INTERVAL:-300}
source "$ROUND_DIR/round.env"
K=$(grep ^ADMIN_API_KEY= .env | cut -d= -f2-); B=http://127.0.0.1:8080/api
WEBHOOK=$(grep ^DISCORD_WEBHOOK_URL= .env | cut -d= -f2-)
END_TS=$(date -u -d "$END" +%s)
START_TS=$(date -u -d "$START" +%s)
psql() { docker compose exec -T postgres psql -U ctrade -d ctrade -tAc "$1" </dev/null; }

notify() {
  [[ -n $WEBHOOK ]] || return 0
  python3 -c 'import json,sys;print(json.dumps({"content":sys.argv[1][:1900]}))' "$1" \
    | curl -s -m 20 -o /dev/null -w 'discord %{http_code}\n' -H 'Content-Type: application/json' -d @- "$WEBHOOK"
}

last=""
while (( $(date +%s) < END_TS )); do
  w=$(( $(date +%s) - START_TS ))
  st=$(curl -s -m 20 -H "X-Admin-Key: $K" $B/admin/operational-state | python3 -c "
import json,sys
j=json.load(sys.stdin); bad=[c['id'] for c in j['checks'] if c['status']!='OK']
print(j['status']+(' '+','.join(bad) if bad else ''))" 2>/dev/null || echo UNREACHABLE)
  err=$(curl -s -m 20 -G http://127.0.0.1:9090/api/v1/query --data-urlencode "query=sum(increase(logback_events_total{level=\"error\"}[${w}s]))" | python3 -c "import json,sys;r=json.load(sys.stdin)['data']['result'];print(round(float(r[0]['value'][1])) if r else 0)" 2>/dev/null)
  runners=$(psql "select string_agg(strategy_name||':'||status, ' ' order by strategy_name) from strategy_runners")
  fills=$(psql "select count(*) from transactions where status='FILLED'")
  echo "$(date -u +%FT%H:%MZ) estado=$st errors=${err:-?} fills=$fills | $runners"

  now=()
  [[ $st == OK* ]] || now+=("estado=$st")
  (( ${err:-0} > 0 )) && now+=("ERROR")
  off=$(tr ' ' '\n' <<< "$runners" | grep -v ':ACTIVE$' | paste -sd' ')
  [[ -n $off ]] && now+=("runners=$off")
  current="${now[*]:-}"
  if [[ $current != "$last" ]]; then
    if [[ -n $current ]]; then
      msg="ctrade rodada $N: ANOMALIA: $current (errors=${err:-?})"
      echo "$(date -u +%FT%H:%M:%SZ) $msg" >> "$ROUND_DIR/ANOMALY"
    else
      msg="ctrade rodada $N: anomalia acabou, estado OK"
      echo "$(date -u +%FT%H:%M:%SZ) $msg" >> "$ROUND_DIR/ANOMALY"
    fi
    echo "$msg"
    notify "$msg"
    last=$current
  fi

  left=$(( END_TS - $(date +%s) ))
  (( left > 0 )) && sleep $(( left < INTERVAL ? left : INTERVAL ))
done
echo "$(date -u +%FT%H:%M:%SZ) fim da rodada"
