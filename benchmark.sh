#!/usr/bin/env bash
# Copyright 2026 Atomikos
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# How much does XA cost? Runs OrderServiceBenchmark once per mode and round,
# each in its own JVM with fresh containers (Docker needed, not the demo's
# compose.yaml: the demo data stays untouched), then prints the averages.
#
#   ./benchmark.sh                     3 rounds of naive, local-tx and xa
#   ROUNDS=1 ./benchmark.sh            a quicker look
#   ./benchmark.sh -Dbenchmark.threads=1,8,32 -Dbenchmark.seconds=60
#                                      anything else goes to Maven
set -euo pipefail
cd "$(dirname "$0")"

MODES=(${MODES:-naive local-tx xa})
ROUNDS=${ROUNDS:-3}
RESULTS=target/benchmark/results.csv

rm -f "$RESULTS"
for round in $(seq 1 "$ROUNDS"); do
  # rotate the order each round, so no mode always runs first (or last)
  for i in "${!MODES[@]}"; do
    mode=${MODES[$(( (i + round - 1) % ${#MODES[@]} ))]}
    echo "=== round $round/$ROUNDS: $mode"
    ./mvnw -q test -Dtest=OrderServiceBenchmark -Dsurefire.failIfNoSpecifiedTests=false \
           -Dbenchmark.mode="$mode" "$@" \
      | grep -E '^(mode|Atomikos pools|naive|local-tx|xa |checked)' || { echo "benchmark failed: see target/surefire-reports"; exit 1; }
  done
done

echo
echo "=== average of $ROUNDS round(s), $(uname -sm) - raw numbers in $RESULTS"
awk -F, -v modes="${MODES[*]}" '
  NR > 1 { key = $2 SUBSEP $3; n[key]++; tps[key] += $7; p50[key] += $8; p99[key] += $10; threads[$3] = 1 }
  END {
    split(modes, m, " ")
    printf "%-9s %7s %10s %8s %8s %9s\n", "mode", "threads", "orders/s", "p50 ms", "p99 ms", "vs naive"
    count = 0
    for (t in threads) ts[++count] = t + 0
    for (i = 2; i <= count; i++) for (j = i; j > 1 && ts[j - 1] > ts[j]; j--) { x = ts[j]; ts[j] = ts[j - 1]; ts[j - 1] = x }
    for (i = 1; i <= count; i++) for (j = 1; j in m; j++) {
      key = m[j] SUBSEP ts[i]; naive = "naive" SUBSEP ts[i]
      if (!(key in n)) continue
      vs = (naive in n) ? sprintf("%+.0f%%", 100 * (tps[key] / n[key]) / (tps[naive] / n[naive]) - 100) : "-"
      printf "%-9s %7d %10.1f %8.2f %8.2f %9s\n", m[j], ts[i], tps[key] / n[key], p50[key] / n[key], p99[key] / n[key], vs
    }
  }' "$RESULTS"
