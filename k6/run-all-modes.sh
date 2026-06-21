#!/usr/bin/env bash
# Sweeps every concurrency mode and writes the JSON summary per run.
# Requires: the app started fresh for each mode (override via posting.concurrency.mode).
set -euo pipefail

MODES=(NONE PESSIMISTIC OPTIMISTIC STRIPED EXECUTOR)
CONTENTION="${CONTENTION:-medium}"
OUT="${OUT:-results}"
mkdir -p "$OUT"

for mode in "${MODES[@]}"; do
    echo "== Running mode=$mode contention=$CONTENTION =="
    echo "Set posting.concurrency.mode=$mode in application-local.yml and restart the app, then press Enter to continue."
    read -r _
    k6 run -e CONTENTION="$CONTENTION" --summary-export="$OUT/$mode-$CONTENTION.json" k6/postings.js
done

echo "Done. Summaries in $OUT/"
