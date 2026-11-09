#!/usr/bin/env bash
# Times the command-line comparisons against PERF_A / PERF_B (created by perf/setup.sh) and records peak memory.
#
#   DBMERGE_PERF_PASSWORD=...  perf/run.sh [OUT_DIR]
#
# Exit codes of the comparisons are expected to be 1 (differences found); anything else fails the run.
set -uo pipefail
: "${DBMERGE_PERF_PASSWORD:?set DBMERGE_PERF_PASSWORD}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
OUT="${1:-$ROOT/reports/perf}"
JAR="$(ls "$ROOT"/target/oracle-db-merge-*.jar | head -1)"
mkdir -p "$OUT"
if /usr/bin/time -l true 2>/dev/null; then TIME="/usr/bin/time -l"; else TIME="/usr/bin/time -v"; fi

run() { # run LABEL EXPECTED_EXIT cli-args...
  local label="$1" expected="$2"; shift 2
  local log="$OUT/$label.log" t0=$SECONDS
  $TIME java -Xmx1g -jar "$JAR" cli "$@" --config "$HERE/perf-config.yml" --out "$OUT" > "$log" 2> "$OUT/$label.time"
  local code=$?
  local rss_mb # macOS time -l reports bytes; GNU time -v reports kilobytes
  rss_mb=$(awk '/maximum resident set size/ {print int($1/1048576)}' "$OUT/$label.time" 2>/dev/null)
  [ -z "$rss_mb" ] && rss_mb=$(awk '/Maximum resident set size/ {print int($NF/1024)}' "$OUT/$label.time" 2>/dev/null)
  printf '%-22s %5ss  peak %6s MB  exit %s%s\n' "$label" "$((SECONDS - t0))" "${rss_mb:-?}" "$code" \
    "$([ "$code" = "$expected" ] || echo "  << expected $expected")"
}

echo "jar: $JAR"
run ddl                 1 ddl PERF_A PERF_B
run ddl-filtered        1 ddl PERF_A PERF_B --exclude 'BKP_*,TMP_*' --ignore GRANT
run refs                1 refs PERF_A --exclude 'BKP_*,TMP_*' --fail-on MEDIUM
run refs-checked        1 refs PERF_A --check-data --exclude 'BKP_*,TMP_*' --fail-on MEDIUM
run snapshot            0 snapshot PERF_A --exclude 'BKP_*,TMP_*' --file "$OUT/perf_a_baseline.json"
run ddl-vs-snapshot     1 ddl PERF_B --snapshot "$OUT/perf_a_baseline.json" --exclude 'BKP_*,TMP_*' --ignore GRANT
run data-hash           1 data PERF_A PERF_B --exclude 'BKP_*,TMP_*'
run data-trade-hash     1 data PERF_A PERF_B --tables TRADE --max-rows 0
echo "logs and reports: $OUT"
