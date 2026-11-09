#!/usr/bin/env bash
# Drills de operacao da fase 4 da T53 (F4.8 a F4.11) pela API, so na VPS de testnet e com uma sessao de
# ./round-start.sh no ar. Cada comando grava o passo a passo e os retratos em $ROUND_DIR/drill-<comando>-<hora>.log.
#
# Acoes que o runbook reserva ao mantenedor (PANIC_SELL, mudar limite de risco ou politica de liquidacao) exigem a
# flag --autorizado, para nao sairem sem a autorizacao dele.
#
# Uso:
#   ./drill.sh status                               retrato da sessao
#   ./drill.sh kill-switch                          F4.9   POST /admin/runners/halt (todos os runners)
#   ./drill.sh resume [estrategia...]               retoma os runners da sessao (todos, ou os das estrategias dadas)
#   ./drill.sh safe-mode <NIVEL> [--autorizado]     F4.10  NORMAL, HALT, CANCEL_ALL ou PANIC_SELL (este exige a flag)
#   ./drill.sh liquidation-limit --autorizado       F4.11  liquidacao LIMIT nos runners da sessao (T48)
#   ./drill.sh holding <estrategia> <ISO> --autorizado  F4.8  maxHoldingDuration do runner (ex.: PT5M)
set -uo pipefail
source "$(dirname "$0")/round-lib.sh"
require_round

authorized() { [[ " $* " == *" --autorizado "* ]] || { echo "Exige --autorizado (autorizacao do mantenedor)." >&2; exit 1; }; }

safe_mode_status() {
  log "safe mode: $(get "/admin/portfolios/$PORTFOLIO/safe-mode" | tr -d '\n' | cut -c1-400)"
}

case ${1:-} in
  status)
    snap agora ;;
  kill-switch)
    open_log drill-kill-switch; REASON="T53 F4.9: kill switch"
    snap antes; log "halt de todos: $(cmd POST /admin/runners/halt)"
    sleep 20; snap depois ;;
  resume)
    shift; open_log drill-resume "$@"; REASON="T53 fase 4: retomada depois do teste"
    names=${*:-SoakTrend SoakReversion SoakBook SoakBreakout SoakChaos}
    for n in $names; do
      for r in $(q "select id from strategy_runners where strategy_name='$n' and portfolio_id='$PORTFOLIO'"); do
        log "resume $n ${r:0:8}: $(cmd POST "/admin/runners/$r/resume")"
      done
    done
    sleep 20; snap depois ;;
  safe-mode)
    L=${2:?nivel}; open_log "drill-safe-mode-$L"; REASON="T53 F4.10/F4.11: Safe Mode $L"
    case $L in
      NORMAL|HALT|CANCEL_ALL) ;;
      PANIC_SELL) authorized "$@" ;;
      *) echo "nivel: NORMAL, HALT, CANCEL_ALL ou PANIC_SELL" >&2; exit 1 ;;
    esac
    snap antes; safe_mode_status
    log "mudanca: $(cmd PUT "/admin/portfolios/$PORTFOLIO/safe-mode" "{\"level\":\"$L\",\"confirm\":true}")"
    for _ in 1 2 3 4 5 6; do sleep 10; safe_mode_status; done
    snap depois ;;
  liquidation-limit)
    authorized "$@"; open_log drill-liquidation-limit; REASON="T53 F4.11: liquidacao LIMIT (T48)"
    for r in $(q "select id from strategy_runners where portfolio_id='$PORTFOLIO'"); do
      log "${r:0:8}: $(cmd PATCH "/admin/runners/$r" '{"liquidationOrderType":"LIMIT","liquidationMaxSlippagePercent":0.01,"liquidationEscalationTicks":3}')"
    done ;;
  holding)
    N=${2:?estrategia}; D=${3:?ISO}; authorized "$@"; open_log "drill-holding-$N"
    REASON="T53 F4.8: maxHoldingDuration $D em $N"
    r=$(runner_of "$N"); [[ -n $r ]] || { log "runner de $N nao encontrado"; exit 1; }
    snap antes; log "${r:0:8}: $(cmd PATCH "/admin/runners/$r" "{\"maxHoldingDuration\":\"$D\"}")" ;;
  *)
    sed -n '2,15p' "$0"; exit 1 ;;
esac
log "fim"
