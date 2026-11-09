#!/usr/bin/env bash
# Funcoes comuns dos testes curtos da fase 4 da T53 (fault.sh e drill.sh). Uso: source ./round-lib.sh
#
# Le a sessao em $ROUND_DIR/round.env (gravada por ./round-start.sh) e a chave admin do .env, sem imprimi-la.
# Respostas da API sao lidas inteiras antes de qualquer recorte: fechar a leitura no meio vira ERROR no ctrade.

cd "$(dirname "${BASH_SOURCE[0]}")"
ROUND_DIR=${ROUND_DIR:-$HOME/round}
K=$(grep ^ADMIN_API_KEY= .env | cut -d= -f2-)
B=http://127.0.0.1:8080/api
APP=ctrade-vps-ctrade-app-1
LOG=/dev/null

log() { echo "$(date -u +%H:%M:%SZ) $*" | tee -a "$LOG"; }
q() { docker compose exec -T postgres psql -U ctrade -d ctrade -tAc "$1" </dev/null; }

# Leitura: corpo inteiro no stdout.
get() { curl -s -m 30 -H "X-Admin-Key: $K" "$B$1"; }

# Comando com motivo: imprime o codigo HTTP e o corpo, para o log do teste.
cmd() {
  local method=$1 path=$2 body=${3:-} out code
  out=$(mktemp)
  code=$(curl -s -m 30 -o "$out" -w '%{http_code}' -X "$method" -H "X-Admin-Key: $K" \
    -H "Content-Type: application/json" -H "X-Reason: $REASON" ${body:+-d "$body"} "$B$path")
  echo "HTTP $code $(tr -d '\n' < "$out" | cut -c1-400)"
  rm -f "$out"
}

require_round() {
  [[ -f "$ROUND_DIR/round.env" ]] || { echo "Sem sessao em $ROUND_DIR (./round-start.sh antes)." >&2; exit 1; }
  [[ -e "$ROUND_DIR/CLOSED" ]] && { echo "Sessao em $ROUND_DIR ja fechada." >&2; exit 1; }
  source "$ROUND_DIR/round.env"
}

# Abre o log do teste em $ROUND_DIR/<prefixo>-<nome>-<hora>.log.
open_log() { LOG="$ROUND_DIR/$1-$(date -u +%Y%m%dT%H%M%SZ).log"; : > "$LOG"; log "inicio: $*"; }

runner_of() { q "select id from strategy_runners where strategy_name='$1' and portfolio_id='$PORTFOLIO' order by created_at limit 1"; }

wait_api() {
  for _ in $(seq 120); do curl -sf -m 5 -H "X-Admin-Key: $K" "$B/admin/operational-state" >/dev/null && return 0; sleep 5; done
  return 1
}

# Retrato da sessao: estado operacional, runners, ordens, decisoes e ERROR desde a largada.
snap() {
  local op dec
  op=$(get /admin/operational-state | python3 -c "
import json,sys
d=json.load(sys.stdin); bad=[c['id']+'='+c['status'] for c in d['checks'] if c['status']!='OK']
print(d['status'], ' '.join(bad))" 2>/dev/null || echo INDISPONIVEL)
  dec=$(curl -s -m 20 -G http://127.0.0.1:9090/api/v1/query \
    --data-urlencode 'query=sum by (decision)(signal_evaluated_total)' | python3 -c "
import json,sys
r=json.load(sys.stdin)['data']['result']
print(' '.join(f\"{x['metric'].get('decision')}={int(float(x['value'][1]))}\" for x in sorted(r,key=lambda x:x['metric'].get('decision',''))))" 2>/dev/null)
  log "--- $1"
  log "estado: $op"
  log "runners: $(q "select string_agg(strategy_name||':'||status, ' ' order by strategy_name) from strategy_runners where portfolio_id='$PORTFOLIO'")"
  log "ordens: $(q "select string_agg(status||'='||n, ' ' order by status) from (select status,count(*) n from transactions group by 1) t")"
  log "em voo: $(q "select string_agg(left(id::text,8)||':'||type||':'||status||':'||extract(epoch from now()-requested_at)::int||'s', ' ') from transactions where status in ('PENDING','SUBMITTED','PARTIAL')")"
  log "decisoes: $dec"
  log "ERROR desde a largada: $(docker logs --since "$START" "$APP" 2>&1 | grep -c ' ERROR ')"
}
