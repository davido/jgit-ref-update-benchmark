#!/usr/bin/env bash
#
# Create a bare git repo with N refs all pointing at one base commit.
# Used as the substrate for the native-git ref-update benchmarks
# (native-git-bench.sh, native-git-bench.py).
#
# Default: 2,000,000 refs at /tmp/bench-2m-refs.git.
#
# Usage:
#   ./create-bench-repo.sh [REPO_PATH] [REF_COUNT]
#
# Examples:
#   ./create-bench-repo.sh                                # /tmp/bench-2m-refs.git, 2M refs
#   ./create-bench-repo.sh /tmp/small.git 10000           # 10k refs (smoke test)
#   ./create-bench-repo.sh /tmp/huge.git 5000000          # 5M refs

set -euo pipefail

REPO=${1:-/tmp/bench-2m-refs.git}
COUNT=${2:-2000000}

if [ -d "$REPO" ]; then
  echo "ERROR: $REPO already exists. Remove it first, or pick a different path." >&2
  exit 1
fi

echo "Creating bare repo at $REPO ..."
mkdir -p "$REPO"
cd "$REPO"
git init --bare --quiet

echo "Creating base commit ..."
EMPTY_TREE=$(git mktree </dev/null)
COMMIT=$(git commit-tree "$EMPTY_TREE" -m "bench base")
git update-ref refs/heads/main "$COMMIT"

echo "Generating $COUNT refs via 'git update-ref --stdin' ..."
SECONDS=0
# Use python for reliable large-integer formatting; BSD awk's %d can lose
# precision at ~1e6+ which produced duplicate ref names (and aborted the
# atomic stdin batch). Python ints are arbitrary-precision.
python3 -c "
import sys
c, n = sys.argv[1], int(sys.argv[2])
for i in range(1, n + 1):
    sys.stdout.write(f'create refs/bench/{i:010d} {c}\n')
" "$COMMIT" "$COUNT" | git update-ref --stdin
echo "  ... created $COUNT refs in ${SECONDS}s"

echo "Packing refs into packed-refs ..."
SECONDS=0
git pack-refs --all
echo "  ... packed in ${SECONDS}s"

REF_TOTAL=$(git for-each-ref | wc -l | tr -d ' ')
PACKED_HUMAN=$(du -h packed-refs 2>/dev/null | awk '{print $1}' || echo "?")
REPO_SIZE=$(du -sh . | awk '{print $1}')

echo ""
echo "==== bench repo ready ===="
echo "  Path:          $REPO"
echo "  Total refs:    $REF_TOTAL"
echo "  packed-refs:   $PACKED_HUMAN"
echo "  Total repo:    $REPO_SIZE"
echo ""
echo "Next:"
echo "  BENCH_REPO=$REPO tools/bench/native-git-bench.sh"
echo "  BENCH_REPO=$REPO tools/bench/native-git-bench.py"
