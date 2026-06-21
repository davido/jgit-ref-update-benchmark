#!/usr/bin/env bash
#
# Native git (C implementation) ref-update benchmark — comparison baseline
# for the JGit JMH benchmark (RefUpdateBenchmark).
#
# Two scenarios per run:
#   1. Per-call `git update-ref` (one fork per ref) — dominated by
#      fork+exec cost, NOT a fair RefUpdate comparison. Useful only as
#      "what does shelling out cost from a parent process?"
#   2. Bulk `git update-ref --stdin` (one fork, N ops batched) — closest
#      fair comparison to JGit's BatchRefUpdate with N=large commands.
#      Divide total by N for amortised per-op cost.
#
# For a true in-process per-N=1 comparison see native-git-bench.py
# (libgit2 via pygit2).
#
# Usage:
#   BENCH_REPO=/tmp/bench-2m-refs.git ./native-git-bench.sh
#   BENCH_REPO=/tmp/bench-2m-refs.git BULK_ITERS=10000 PER_CALL_ITERS=100 ./native-git-bench.sh

set -euo pipefail

REPO=${BENCH_REPO:-/tmp/bench-2m-refs.git}
BULK_ITERS=${BULK_ITERS:-10000}
PER_CALL_ITERS=${PER_CALL_ITERS:-100}

if [ ! -d "$REPO" ]; then
  echo "ERROR: $REPO does not exist." >&2
  echo "Create it first with: ./create-bench-repo.sh $REPO 2000000" >&2
  exit 1
fi

cd "$REPO"

TOTAL_REFS=$(git for-each-ref | wc -l | tr -d ' ')
GIT_VERSION=$(git --version)

echo ""
echo "==== native-git-bench.sh ===="
echo "  Repo:        $REPO"
echo "  Total refs:  $TOTAL_REFS"
echo "  git:         $GIT_VERSION"
echo "  Per-call:    $PER_CALL_ITERS iterations (one fork per)"
echo "  Bulk:        $BULK_ITERS iterations (one fork total)"
echo ""

now_ns() {
  python3 -c 'import time; print(time.monotonic_ns())'
}

# Helper: print elapsed total + per-op
report() {
  local label="$1"; local elapsed_ns="$2"; local n="$3"
  python3 -c "
elapsed_ns = $elapsed_ns
n = $n
total_ms = elapsed_ns / 1_000_000
per_op_ms = total_ms / n
per_op_us = per_op_ms * 1000
print(f'  {\"$label\":<40s}  total {total_ms:>8.1f} ms  ({per_op_ms:>8.4f} ms/op  =  {per_op_us:>8.2f} us/op)')
"
}

SEED=$$

# ---- Scenario 1: per-call fork (CREATE) ----
echo "Scenario 1a: per-call CREATE (fork per ref)"
START=$(now_ns)
for i in $(seq 1 "$PER_CALL_ITERS"); do
  git update-ref "refs/measure-per-call-create/$SEED/$i" refs/heads/main >/dev/null
done
END=$(now_ns)
report "git update-ref CREATE per-call" "$((END - START))" "$PER_CALL_ITERS"

# ---- Scenario 1b: per-call UPDATE on existing ref ----
echo ""
echo "Scenario 1b: per-call UPDATE (fork per ref, picks from refs/bench/)"
# Use the prepopulated refs/bench/0000000001..N. UPDATE to current oid = no-op
# but the per-call dispatch + lockfile dance still happens.
CURRENT_OID=$(git rev-parse refs/heads/main)
START=$(now_ns)
for i in $(seq 1 "$PER_CALL_ITERS"); do
  PADDED=$(printf "%010d" "$i")
  git update-ref "refs/bench/$PADDED" "$CURRENT_OID" >/dev/null
done
END=$(now_ns)
report "git update-ref UPDATE per-call" "$((END - START))" "$PER_CALL_ITERS"

# Generators use python (not awk) for reliable large-integer formatting --
# BSD awk's %d loses precision at ~1e6+ which can produce duplicate ref names
# and crash the atomic stdin batch. Python ints are arbitrary-precision.
gen_create_bulk() {
  python3 -c "
import sys
c, s, n = sys.argv[1], sys.argv[2], int(sys.argv[3])
for i in range(1, n + 1):
    sys.stdout.write(f'create refs/measure-bulk-create/{s}/{i:07d} {c}\n')
" "$CURRENT_OID" "$SEED" "$BULK_ITERS"
}

gen_update_bulk() {
  python3 -c "
import sys
c, n = sys.argv[1], int(sys.argv[2])
for i in range(1, n + 1):
    sys.stdout.write(f'update refs/bench/{i:010d} {c}\n')
" "$CURRENT_OID" "$BULK_ITERS"
}

# ---- Scenario 2a: bulk --stdin CREATE ----
echo ""
echo "Scenario 2a: bulk CREATE via 'git update-ref --stdin' (single fork)"
START=$(now_ns)
gen_create_bulk | git update-ref --stdin
END=$(now_ns)
report "git update-ref --stdin CREATE bulk" "$((END - START))" "$BULK_ITERS"

# ---- Scenario 2b: bulk --stdin UPDATE ----
echo ""
echo "Scenario 2b: bulk UPDATE via 'git update-ref --stdin' (single fork)"
START=$(now_ns)
gen_update_bulk | git update-ref --stdin
END=$(now_ns)
report "git update-ref --stdin UPDATE bulk" "$((END - START))" "$BULK_ITERS"

echo ""
echo "==== caveats ===="
echo "  - Per-call numbers are dominated by fork+exec (~10-50 ms on macOS)."
echo "    They are NOT a fair head-to-head against JGit's in-process RefUpdate."
echo "  - Bulk numbers amortise the fork over N ops. They are the closest fair"
echo "    comparison to JGit's BatchRefUpdate with N=large commands."
echo "  - For a true per-N=1 in-process comparison see native-git-bench.py"
echo "    (libgit2 via pygit2)."
