#!/usr/bin/env bash
# Largada de uma rodada da T53 na VPS (T63): banco limpo, apps cadastradas, portfolio de 1000 USDT e um
# runner por app e por simbolo com os limites da rodada 7. Grava a rodada em $ROUND_DIR/round.env e deixa
# ./round-close.sh dormindo ate o fim, desacoplado da sessao que chamou.
#
# Uso:  ./round-start.sh <numero> <duracao>      duracao em horas (72) ou minutos (45m); ROUND_DIR padrao: ~/round
#       SYMBOLS="BTCUSDT ETHUSDT BNBUSDT SOLUSDT" ./round-start.sh ...
#                                                um runner por app em cada simbolo (padrao: BTCUSDT), com a alocacao
#                                                da rodada 7 dividida entre eles: teste de carga (F4.14). O ctrade
#                                                permite um so runner ativo por estrategia, simbolo e portfolio.
#
# So vale para a testnet: apaga o banco (./reset-testnet-db.sh). Recusa comecar com saldo `locked` na
# exchange: uma ordem que descansa no livro de antes da limpeza vira trade so na exchange.
set -euo pipefail

cd "$(dirname "$0")"
N=$1; DUR=$2
case $DUR in
  *m) MINUTES=${DUR%m} ;;
  *)  MINUTES=$(( DUR * 60 )) ;;
esac
SYMBOLS=${SYMBOLS:-BTCUSDT}
PER=$(wc -w <<< "$SYMBOLS")
ASSETS="$(for s in $SYMBOLS; do printf '%s ' "${s%USDT}"; done)USDT"
ROUND_DIR=${ROUND_DIR:-$HOME/round}
K=$(grep ^ADMIN_API_KEY .env | cut -d= -f2-); B=http://127.0.0.1:8080/api
api() { curl -sf -H "X-Admin-Key: $K" -H "Content-Type: application/json" -H "X-Reason: T53 rodada $N ($DUR, VPS): $1" -X "$2" "$B$3" ${4:+-d "$4"}; }

if [[ -e "$ROUND_DIR/round.env" && ! -e "$ROUND_DIR/CLOSED" ]]; then
  echo "Rodada em andamento em $ROUND_DIR (sem CLOSED). Abortado." >&2
  exit 1
fi
for a in $ASSETS; do
  locked=$(curl -sf -H "X-Admin-Key: $K" "$B/exchanges/BINANCE/balances?asset=$a" | python3 -c "import json,sys;print(json.load(sys.stdin)['balances'][0]['locked'])")
  python3 -c "import sys;sys.exit(0 if float('$locked')==0 else 1)" || { echo "locked $a=$locked: cancelar as ordens antes" >&2; exit 1; }
done

docker compose stop ctrade-app
./reset-testnet-db.sh --force
./up.sh >/dev/null && ./register-strategies.sh >/dev/null
for _ in $(seq 60); do curl -sf -H "X-Admin-Key: $K" $B/admin/operational-state >/dev/null && break; sleep 5; done

START=$(date -u +%Y-%m-%dT%H:%M:%SZ)
END=$(date -u -d "$START + $MINUTES minutes" +%Y-%m-%dT%H:%M:%SZ)
PID=$(api inicio POST /portfolios "{\"name\":\"soak-t53-r$N\",\"initialCapitalAmount\":1000,\"currency\":\"USDT\"}" | python3 -c "import json,sys;print(json.load(sys.stdin)['portfolioId'])")
RUNNERS=""
for n in 1 2 3 4 5; do
  ref=c7a1b2d3-000$n-4000-8000-00000000000$n
  alloc=0.4; [[ $n == 5 ]] && alloc=0.2   # caos com capital pequeno
  alloc=$(python3 -c "print(round($alloc / $PER, 6))")
  for sym in $SYMBOLS; do
    rid=$(api "runner por app" POST /portfolios/$PID/runners "{\"strategyId\":\"$ref\",\"symbol\":\"$sym\",\"exchangeName\":\"BINANCE\",\"allowedMarketDataSources\":[\"BINANCE\"]}" | python3 -c "import json,sys;print(json.load(sys.stdin)['runnerId'])")
    api "limites da rodada 7" PATCH /admin/runners/$rid "{\"maxAllocationPercent\":$alloc,\"maxOpenPositions\":1,\"maxPendingOrders\":1,\"maxLossPercentPerLot\":0.05}" >/dev/null
    api inicio POST /admin/runners/$rid/start >/dev/null
    RUNNERS="$RUNNERS $rid"
  done
done

rm -rf "$ROUND_DIR"; mkdir -p "$ROUND_DIR"
cat > "$ROUND_DIR/round.env" <<ENV
N=$N
START=$START
END=$END
PORTFOLIO=$PID
SYMBOLS="$SYMBOLS"
RUNNERS="${RUNNERS# }"
ENV
ROUND_DIR=$ROUND_DIR setsid nohup ./round-close.sh > "$ROUND_DIR/close.log" 2>&1 < /dev/null &
ROUND_DIR=$ROUND_DIR setsid nohup ./round-watch.sh > "$ROUND_DIR/watch.log" 2>&1 < /dev/null &
cat "$ROUND_DIR/round.env"
