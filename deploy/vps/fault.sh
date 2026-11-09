#!/usr/bin/env bash
# Falhas controladas da fase 4 da T53 (F4.1 a F4.7), so na VPS de testnet e com uma sessao de ./round-start.sh no ar.
#
# As regras de firewall valem so no namespace de rede do container do ctrade e saem no fim, mesmo com erro ou
# Ctrl-C (trap). Cada execucao grava o passo a passo e os retratos antes e depois em $ROUND_DIR/fault-<teste>-<hora>.log.
#
# Uso:
#   ./fault.sh restart <s> [lote]      F4.1  para o ctrade por <s> s com ordem do SoakBook em voo (ou lote aberto)
#   ./fault.sh ws <s> [all|market|user] F4.2/F4.3  bloqueia os WebSockets por <s> s; o REST segue livre
#   ./fault.sh exchange <s>             F4.4  bloqueia WebSockets e REST por <s> s
#   ./fault.sh lost-ack <s>             F4.5  depois de um cancelamento do SoakBook, descarta por <s> s o que chega do ws-api
#   ./fault.sh lost-order <s>           F4.6  idem, descartando o que sai para o ws-api: a ordem nao chega a exchange
#   ./fault.sh strategy <app> <s>       F4.7  para o container da app (trend, reversion, book, breakout, chaos) por <s> s
set -uo pipefail
source "$(dirname "$0")/round-lib.sh"
require_round

WS_MARKET=stream.testnet.binance.vision
WS_USER=ws-api.testnet.binance.vision
REST=testnet.binance.vision
FOLLOW_S=${FOLLOW_S:-900}   # quanto acompanhar as ordens afetadas (carencia do PENDING e de 10 min)

ips() { for h in "$@"; do getent ahostsv4 "$h" | awk '{print $1}'; done | sort -u; }
app_pid() { docker inspect -f '{{.State.Pid}}' "$APP"; }
ns() { sudo nsenter -t "$(app_pid)" -n "$@"; }
fw() { ns "$(command -v iptables)" "$@"; }

RULES=()
add_rule() { fw -I "$@" && RULES+=("$*") && log "regra: $*"; }
clear_rules() {
  local r
  for r in "${RULES[@]}"; do fw -D $r 2>/dev/null && log "regra removida: $r"; done
  RULES=()
}
trap clear_rules EXIT
trap 'exit 130' INT TERM

kill_conns() {
  local ip
  for ip in "$@"; do
    ns ss -K -tn dst "[::ffff:$ip]" dport = :443 >/dev/null
    ns ss -K -tn dst "$ip" dport = :443 >/dev/null
  done
  log "conexoes derrubadas"
}

# Conexoes abertas na 443, para o log mostrar o que foi derrubado.
conns() { ns ss -tn state established '( dport = :443 )' | awk 'NR>1{print $4}' | sed -E 's/^\[::ffff:([0-9.]+)\]:443$//; s/:443$//' | sort -u | paste -sd' '; }

# Bloqueia os hosts por IP e pelo nome no TLS (SNI): uma reconexao que resolva outro IP tambem e recusada.
block_out() {
  local h ip
  for h in "$@"; do
    for ip in $(ips "$h"); do add_rule OUTPUT -d "$ip" -p tcp --dport 443 -j REJECT --reject-with tcp-reset; done
    add_rule OUTPUT -p tcp --dport 443 -m string --string "$h" --algo bm -j REJECT --reject-with tcp-reset
  done
  log "conexoes antes: $(conns)"
  kill_conns $(ips "$@")
  log "conexoes depois: $(conns)"
}

# Espera passiva, com o estado a cada 15 s.
hold() {
  local until=$(( $(date +%s) + $1 ))
  while (( $(date +%s) < until )); do
    sleep 15
    log "estado: $(get /admin/operational-state | python3 -c "
import json,sys
d=json.load(sys.stdin); print(d['status'],' '.join(c['id'] for c in d['checks'] if c['status']!='OK'))" 2>/dev/null || echo INDISPONIVEL)"
  done
}

# Ordens pedidas desde $1 (timestamp), uma linha por ordem.
orders_since() { q "select left(id::text,8)||' '||type||' '||status from transactions where requested_at >= '$1' order by requested_at"; }

# Acompanha as ordens pedidas desde $1 ate nenhuma ficar PENDING, ou FOLLOW_S.
follow() {
  local since=$1 until=$(( $(date +%s) + FOLLOW_S ))
  while (( $(date +%s) < until )); do
    log "ordens desde $since: $(orders_since "$since" | paste -sd',')"
    [[ -z $(q "select 1 from transactions where requested_at >= '$since' and status='PENDING' limit 1") ]] && return 0
    sleep 30
  done
  log "ainda PENDING depois de ${FOLLOW_S}s"
}

# Espera o SoakBook ter uma ordem cancelada depois de agora: a proxima ordem dele sai em poucos segundos.
wait_book_cancel() {
  local book t0
  book=$(runner_of SoakBook); t0=$(q "select now()")
  log "esperando um cancelamento do SoakBook (${book:0:8})"
  for _ in $(seq 600); do
    [[ -n $(q "select 1 from transactions where runner_id='$book' and status='CANCELED' and updated_at > '$t0' limit 1") ]] && return 0
    sleep 0.5
  done
  log "nenhum cancelamento em 5 min"; return 1
}

wait_book_order() {
  local book; book=$(runner_of SoakBook)
  log "esperando ordem do SoakBook em voo"
  for _ in $(seq 600); do
    [[ -n $(q "select 1 from transactions where runner_id='$book' and status in ('SUBMITTED','PARTIAL') limit 1") ]] && return 0
    sleep 1
  done
  log "nenhuma ordem do SoakBook em 10 min"; return 1
}

wait_open_lot() {
  log "esperando lote aberto"
  for _ in $(seq 1800); do
    [[ -n $(q "select 1 from positions where status='OPEN' limit 1") ]] && return 0
    sleep 2
  done
  log "nenhum lote aberto em 1h"; return 1
}

after() { log "esperando ${1}s para o sistema se recompor"; sleep "$1"; snap depois; }

case ${1:-} in
  restart)
    S=${2:?segundos}; open_log fault-restart "$@"
    if [[ ${3:-} == lote ]]; then wait_open_lot || exit 1; else wait_book_order || exit 1; fi
    snap antes
    log "em voo ao parar: $(q "select string_agg(left(id::text,8)||':'||status, ' ') from transactions where status in ('PENDING','SUBMITTED','PARTIAL')")"
    docker compose stop ctrade-app </dev/null >/dev/null 2>&1; log "ctrade parado"
    sleep "$S"
    docker compose start ctrade-app </dev/null >/dev/null 2>&1; log "ctrade iniciado"
    wait_api || { log "API nao respondeu em 10 min"; exit 1; }
    log "API de volta"
    docker logs --since 5m "$APP" 2>&1 | grep -E 'phase=|phase2.sanity: portfolios|bootRecovery: completed' \
      | sed -E 's/^[0-9-]+ //' | cut -c1-200 | tee -a "$LOG"
    after 90 ;;
  ws)
    S=${2:?segundos}; open_log fault-ws "$@"
    case ${3:-all} in
      all) HOSTS="$WS_MARKET $WS_USER" ;; market) HOSTS=$WS_MARKET ;; user) HOSTS=$WS_USER ;;
      *) echo "canal: all, market ou user" >&2; exit 1 ;;
    esac
    snap antes; block_out $HOSTS; log "WebSocket fora: $HOSTS"
    hold "$S"; clear_rules; log "rede de volta"
    after 120 ;;
  exchange)
    S=${2:?segundos}; open_log fault-exchange "$@"
    wait_book_order || exit 1
    snap antes; block_out $WS_MARKET $WS_USER $REST; log "exchange fora (WebSocket e REST)"
    hold "$S"; clear_rules; log "rede de volta"
    after 180 ;;
  lost-ack|lost-order)
    S=${2:?segundos}; open_log "fault-$1" "$@"
    USER_IPS=$(ips $WS_USER)
    snap antes
    log "conexoes: $(conns)"
    [[ -n $(comm -12 <(tr ' ' '
' <<< "$(conns)" | sort) <(tr ' ' '
' <<< "$USER_IPS" | sort)) ]]       || { log "nenhuma conexao aberta com $WS_USER ($USER_IPS): abortado"; exit 1; }
    wait_book_cancel || exit 1
    T0=$(q "select now()")
    for ip in $USER_IPS; do
      if [[ $1 == lost-ack ]]; then add_rule INPUT -s "$ip" -p tcp --sport 443 -j DROP
      else add_rule OUTPUT -d "$ip" -p tcp --dport 443 -j DROP; fi
    done
    log "ws-api cortado ($1)"
    for _ in $(seq "$S"); do [[ -n $(orders_since "$T0") ]] && break; sleep 1; done
    log "ordens enviadas no corte: $(orders_since "$T0" | paste -sd',')"
    hold "$(( S > 30 ? S - 30 : 0 ))"
    kill_conns $USER_IPS; clear_rules; log "rede de volta"
    follow "$T0"
    after 60 ;;
  strategy)
    A=${2:?app}; S=${3:?segundos}; open_log "fault-strategy-$A" "$@"
    snap antes
    docker compose stop "$A" </dev/null >/dev/null 2>&1; log "app $A parada"
    hold "$S"
    docker compose start "$A" </dev/null >/dev/null 2>&1; log "app $A iniciada"
    after 120
    log "catalogo: $(get /admin/strategies | python3 -c "
import json,sys
d=json.load(sys.stdin); d=d if isinstance(d,list) else d.get('content',d.get('strategies',[]))
print(' '.join(f\"{s.get('name')}:{s.get('status')}:{s.get('verification')}:{s.get('usable')}\" for s in d))")" ;;
  *)
    sed -n '2,12p' "$0"; exit 1 ;;
esac
log "fim"
