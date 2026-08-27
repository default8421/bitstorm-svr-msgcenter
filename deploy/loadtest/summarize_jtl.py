#!/usr/bin/env python3
"""Summarize a JMeter JTL (CSV) into one JSON line."""
import csv
import json
import sys
from pathlib import Path


def pct(sorted_vals, p):
    if not sorted_vals:
        return 0
    i = min(len(sorted_vals) - 1, max(0, int((p / 100.0) * (len(sorted_vals) - 1))))
    return sorted_vals[i]


def main(path: str, threads: int) -> None:
    rows = list(csv.DictReader(Path(path).open()))
    if not rows:
        print(json.dumps({"threads": threads, "n": 0}))
        return
    els = sorted(int(r["elapsed"]) for r in rows)
    ok = sum(1 for r in rows if r.get("success") == "true")
    ts = [int(r["timeStamp"]) for r in rows]
    dur = (max(ts) - min(ts) + max(els)) / 1000.0
    n = len(rows)
    print(json.dumps({
        "threads": threads,
        "n": n,
        "ok": ok,
        "err_pct": round(100.0 * (n - ok) / n, 2),
        "avg_ms": round(sum(els) / n, 1),
        "p50_ms": pct(els, 50),
        "p90_ms": pct(els, 90),
        "p95_ms": pct(els, 95),
        "p99_ms": pct(els, 99),
        "max_ms": max(els),
        "duration_s": round(dur, 2),
        "qps": round(n / dur, 2) if dur > 0 else 0,
    }))


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]))
