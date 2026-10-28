#!/usr/bin/env python3
"""Coleta do relatorio de fim de rodada do soak (T64, procedimento da T59).

Junta num rascunho Markdown o que ``docs/monitoring/round-report.md`` pede: linha do tempo de eventos,
estado operacional com a comparacao contra a exchange, ERROR/WARN do Loki agrupados, estabilidade da JVM
no Prometheus e ordens por runner. So leitura: nenhuma chamada muda estado.

A classificacao (achado do ctrade ou ruido da testnet) e o veredito de cada criterio continuam com quem
escreve o relatorio; o script entrega as evidencias e marca o que precisa de explicacao.

Uso (na VPS, com o ctrade ja reiniciado no fim da rodada):
    python3 scripts/round_report.py --start 2026-10-28T14:41:43Z --env-file deploy/vps/.env \\
        --portfolio <portfolioId> > round.md

Enderecos padrao: ctrade em http://127.0.0.1:8080, Loki em :3100, Prometheus em :9090. A chave admin vem
de --env-file (ADMIN_API_KEY) ou da variavel ADMIN_API_KEY; nunca e impressa.
"""

import argparse
import collections
import json
import math
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

EVENT_PAGE_LIMIT = 1000
MAX_EXCHANGE_WINDOW_HOURS = 168
LOKI_LIMIT = 5000
# Eventos que o relatorio precisa explicar um a um (round-report.md, passo 1).
EXPLAIN_TYPES = ("SAFE_MODE_CHANGED", "RUNNER_STATUS_CHANGED", "PROTECTIVE_EXIT", "DLQ_ENTRY", "BOOT_FAILED")
NUMBERS = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|\d+(\.\d+)?")


def parse_instant(text):
    return datetime.fromisoformat(text.replace("Z", "+00:00")).astimezone(timezone.utc)


def iso(instant):
    return instant.strftime("%Y-%m-%dT%H:%M:%SZ")


class Sources:
    def __init__(self, ctrade, loki, prometheus, admin_key):
        self.ctrade, self.loki, self.prometheus, self.admin_key = ctrade, loki, prometheus, admin_key

    def _get(self, url, params=None, admin=False):
        if params:
            url += "?" + urllib.parse.urlencode(params)
        request = urllib.request.Request(url, headers={"X-Admin-Key": self.admin_key} if admin else {})
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)

    def api(self, path, **params):
        return self._get(self.ctrade + path, params, admin=True)

    def loki_range(self, query, start, end):
        data = self._get(self.loki + "/loki/api/v1/query_range", {
            "query": query, "start": int(start.timestamp() * 1e9), "end": int(end.timestamp() * 1e9),
            "limit": LOKI_LIMIT, "direction": "forward"})
        return [(labels["stream"], line) for labels in data["data"]["result"] for _, line in labels["values"]]

    def prom(self, query, at):
        data = self._get(self.prometheus + "/api/v1/query", {"query": query, "time": at.timestamp()})
        return data["data"]["result"]


def events(src, start, end):
    seen, result, since = set(), [], start
    while True:
        page = src.api("/api/admin/events", since=iso(since), until=iso(end), limit=EVENT_PAGE_LIMIT)
        for event in page["events"]:
            if event["id"] not in seen:
                seen.add(event["id"])
                result.append(event)
        if not page["truncated"]:
            return result
        since = parse_instant(page["nextSince"])


def section_events(src, start, end, out):
    timeline = events(src, start, end)
    out.append("## 1. Linha do tempo\n")
    counts = collections.Counter((e["type"], e.get("origin") or "-") for e in timeline)
    out.append("| Tipo | Origem | Quantidade |\n|---|---|---|")
    out.extend(f"| {kind} | {origin} | {n} |" for (kind, origin), n in sorted(counts.items()))
    to_explain = [e for e in timeline if e["type"] in EXPLAIN_TYPES
                  and (e["type"] != "RUNNER_STATUS_CHANGED" or e.get("origin") == "AUTOMATIC")]
    to_explain += [e for e in timeline if e["type"] == "OPERATOR_ACTION"]
    out.append("\n**Eventos para explicar** (automáticos, DLQ, boot com falha e ações de operador):\n")
    if not to_explain:
        out.append("Nenhum.")
    for e in sorted(to_explain, key=lambda e: e["occurredAt"]):
        transition = f"{e.get('from')} → {e.get('to')} " if e.get("from") or e.get("to") else ""
        subject = e.get("runnerId") or e.get("portfolioId") or e.get("transactionId") or ""
        out.append(f"- `{e['occurredAt'][:19]}Z` {e['type']} ({e.get('origin') or '-'}) {subject} "
                   f"{transition}— {e.get('reason') or e.get('detail') or 'sem motivo'}")
    out.append("")


def section_state(src, start, end, out):
    hours = min(MAX_EXCHANGE_WINDOW_HOURS, max(1, math.ceil((end - start).total_seconds() / 3600)))
    report = src.api("/api/admin/operational-state", includeExchange="true", exchangeWindowHours=hours)
    boot = src.api("/api/boot/status")
    out.append("## 2. Estado no fim\n")
    out.append(f"Estado operacional **{report['status']}** em `{report['generatedAt'][:19]}Z` "
               f"(comparação com a exchange nas últimas {hours}h).\n")
    out.append("| Check | Status | Resumo |\n|---|---|---|")
    for check in report["checks"]:
        out.append(f"| {check['id']} | {check['status']} | {(check.get('summary') or '').replace('|', '/')} |")
    phases = ", ".join(f"{p['phase']}={p['status']}" for p in boot.get("phases", []))
    out.append(f"\nÚltimo boot: **{boot['status']}** (`{boot.get('startedAt', '')[:19]}Z`, modo "
               f"{boot.get('mode')}): {phases}. {boot.get('failureMessage') or ''}\n")


def section_orders(src, portfolios, out):
    if not portfolios:
        return
    out.append("## 3. Ordens por runner\n")
    out.append("| Portfolio | Runner | Estratégia | Status | Ordens por lado e status |\n|---|---|---|---|---|")
    for portfolio in portfolios:
        for runner in src.api(f"/api/portfolios/{portfolio}/runners"):
            txs = src.api(f"/api/runners/{runner['id']}/transactions")
            by_status = collections.Counter(f"{t['type']}:{t['status']}" for t in txs)
            detail = ", ".join(f"{k} {n}" for k, n in sorted(by_status.items())) or "nenhuma"
            out.append(f"| {portfolio[:8]} | {runner['id'][:8]} | {runner['strategyName']} | {runner['status']} "
                       f"| {detail} |")
        balance = src.api(f"/api/portfolios/{portfolio}/balance")
        out.append(f"\nPortfolio `{portfolio}`: disponível {balance['availableBalance']}, reservado "
                   f"{balance['reservedBalance']}, realizado {balance['realizedBalance']}.\n")


def log_pattern(line):
    """Logger curto + mensagem, com ids e numeros trocados por '#'. Linha que nao e JSON vai inteira."""
    try:
        entry = json.loads(line)
        line = f"{entry.get('logger_name', '').rsplit('.', 1)[-1]}: {entry.get('message', '')}"
    except (ValueError, AttributeError):
        pass
    return NUMBERS.sub("#", " ".join(line.split()))[:200]


def section_logs(src, start, end, out):
    out.append("## 4. Logs (Loki)\n")
    for level in ("ERROR", "WARN"):
        lines = src.loki_range(f'{{app=~".+", level="{level}"}}', start, end)
        groups = collections.Counter(log_pattern(line) for _, line in lines)
        capped = " (limite da consulta atingido: há mais linhas)" if len(lines) >= LOKI_LIMIT else ""
        out.append(f"**{level}: {len(lines)} linhas, {len(groups)} padrões{capped}.**\n")
        out.extend(f"- {n}× `{pattern}`" for pattern, n in groups.most_common(15))
        out.append("")


def first_value(result):
    return float(result[0]["value"][1]) if result else None


def section_jvm(src, start, end, out):
    # Primeira e ultima hora da rodada: minimo do heap (piso depois de GC), threads e tempo de avaliacao.
    window = "1h"
    early = min(end, datetime.fromtimestamp(start.timestamp() + 3600, timezone.utc))
    rows = [
        ("Heap mínimo (MB)", f'min_over_time(sum(jvm_memory_used_bytes{{area="heap"}})[{window}:1m]) / 1e6'),
        ("Threads vivas (máx.)", f"max_over_time(jvm_threads_live_threads[{window}])"),
        ("Avaliação do sinal, média (ms)",
         f"sum(increase(runner_signal_processing_time_seconds_sum[{window}])) / "
         f"sum(increase(runner_signal_processing_time_seconds_count[{window}])) * 1000"),
        ("Avaliação do sinal, máximo (ms)", f"max(max_over_time(runner_signal_processing_time_seconds_max[{window}])) * 1000"),
        ("Uptime do processo (h)", "max(process_uptime_seconds) / 3600"),
    ]
    out.append("## 5. Estabilidade da JVM (Prometheus)\n")
    out.append(f"| Série | 1ª hora (até {iso(early)}) | Última hora (até {iso(end)}) |\n|---|---|---|")
    for name, query in rows:
        values = [first_value(src.prom(query, at)) for at in (early, end)]
        out.append(f"| {name} | " + " | ".join("-" if v is None else f"{v:.1f}" for v in values) + " |")
    changes = src.prom(f"changes(websocket_connection_state[{math.ceil((end - start).total_seconds())}s])", end)
    reconnects = ", ".join(f"{r['metric'].get('channel')}={float(r['value'][1]):.0f}" for r in changes) or "sem série"
    out.append(f"\nMudanças de estado das conexões WebSocket na rodada: {reconnects} "
               "(a Binance corta a conexão a cada 24h; cada corte vale 2 mudanças).\n")


def section_criteria(out):
    out.append("## 6. Critérios da T53 (preencher)\n")
    out.append("| # | Critério | Evidência | Resultado |\n|---|---|---|---|")
    for number, name, where in (
            (1, "Conciliação sem divergência", "2: EXCHANGE_TRADE_DIVERGENCE"),
            (2, "DLQ vazia ou explicada", "1: DLQ_ENTRY; 2: DLQ_UNRESOLVED"),
            (3, "Capital reservado sem ordem", "2: CAPITAL_*"),
            (4, "Transação presa", "2: TRANSACTION_STUCK"),
            (5, "Safe Mode / halt sem causa", "1: eventos automáticos"),
            (6, "ERROR sem explicação", "4: ERROR"),
            (7, "Estabilidade do processo", "5"),
            (8, "Saldo no restart", "2: último boot e EXCHANGE_BALANCE_DIVERGENCE")):
        out.append(f"| {number} | {name} | {where} | |")
    out.append("")


def read_admin_key(env_file):
    if env_file:
        with open(env_file, encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("ADMIN_API_KEY="):
                    return line.split("=", 1)[1].strip()
    return os.environ.get("ADMIN_API_KEY")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--start", required=True, help="início da rodada, ISO-8601 UTC")
    parser.add_argument("--end", help="fim da rodada, ISO-8601 UTC (padrão: agora)")
    parser.add_argument("--portfolio", action="append", default=[], help="portfolio da rodada (repetível)")
    parser.add_argument("--env-file", help="arquivo .env com ADMIN_API_KEY")
    parser.add_argument("--ctrade", default="http://127.0.0.1:8080")
    parser.add_argument("--loki", default="http://127.0.0.1:3100")
    parser.add_argument("--prometheus", default="http://127.0.0.1:9090")
    args = parser.parse_args()

    admin_key = read_admin_key(args.env_file)
    if not admin_key:
        sys.exit("Sem chave admin: passe --env-file ou defina ADMIN_API_KEY.")
    start = parse_instant(args.start)
    end = parse_instant(args.end) if args.end else datetime.now(timezone.utc)
    src = Sources(args.ctrade.rstrip("/"), args.loki.rstrip("/"), args.prometheus.rstrip("/"), admin_key)

    out = [f"# Rodada {iso(start)} → {iso(end)} ({(end - start).total_seconds() / 3600:.1f}h)\n",
           "Rascunho gerado por `scripts/round_report.py`; classificação e veredito ficam com quem assina.\n"]
    try:
        section_events(src, start, end, out)
        section_state(src, start, end, out)
        section_orders(src, args.portfolio, out)
        section_logs(src, start, end, out)
        section_jvm(src, start, end, out)
    except urllib.error.URLError as error:
        sys.exit(f"Falha ao consultar {getattr(error, 'url', '')}: {error}")
    section_criteria(out)
    print("\n".join(out))


if __name__ == "__main__":
    main()
