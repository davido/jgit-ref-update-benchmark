#!/usr/bin/env python3
"""
Native ref-update benchmark using libgit2 via pygit2 — in-process baseline
for the JGit JMH benchmark (RefUpdateBenchmark).

libgit2 is an in-process C implementation of git's on-disk format,
distinct from canonical git but reading/writing the same files. Per-op
cost should be in the same order of magnitude as canonical git and
comparable to JGit's in-process numbers.

Prereqs:
    pip install pygit2

Usage:
    BENCH_REPO=/tmp/bench-2m-refs.git ./native-git-bench.py
    BENCH_REPO=/tmp/bench-2m-refs.git ITERS=2000 WARMUP=200 ./native-git-bench.py
"""

import os
import sys
import time

try:
    import pygit2
except ImportError:
    sys.stderr.write("ERROR: pygit2 not installed. Run: pip install pygit2\n")
    sys.exit(1)


REPO_PATH = os.environ.get("BENCH_REPO", "/tmp/bench-2m-refs.git")
ITERS = int(os.environ.get("ITERS", "1000"))
WARMUP = int(os.environ.get("WARMUP", "200"))


def report(label: str, elapsed_ns: int, n: int) -> None:
    per_op_us = elapsed_ns / 1000 / n
    per_op_ms = per_op_us / 1000
    print(
        f"  {label:<48s}  total {elapsed_ns/1_000_000:>8.1f} ms  "
        f"({per_op_ms:>8.4f} ms/op  =  {per_op_us:>8.2f} us/op)"
    )


def bench_create(repo: "pygit2.Repository", target_oid: "pygit2.Oid",
                 prefix: str, n: int) -> int:
    names = [f"refs/measure-pygit2-create-{prefix}/{i}" for i in range(n)]
    start = time.monotonic_ns()
    for name in names:
        repo.references.create(name, target_oid)
    return time.monotonic_ns() - start


def bench_update_packed(repo: "pygit2.Repository", target_oid: "pygit2.Oid",
                        n: int, start_idx: int = 1) -> int:
    # Pre-populated refs/bench/0000000001..N from create-bench-repo.sh
    # all currently point at refs/heads/main's commit. UPDATE to same oid
    # exercises the dispatch + lockfile dance even if it's a no-op write.
    names = [f"refs/bench/{i:010d}" for i in range(start_idx, start_idx + n)]
    start = time.monotonic_ns()
    for name in names:
        repo.references.create(name, target_oid, force=True)
    return time.monotonic_ns() - start


def main() -> int:
    if not os.path.isdir(REPO_PATH):
        sys.stderr.write(
            f"ERROR: {REPO_PATH} does not exist.\n"
            "Create it first with: ./create-bench-repo.sh "
            f"{REPO_PATH} 2000000\n"
        )
        return 1

    print()
    print("==== native-git-bench.py (libgit2 via pygit2) ====")
    print(f"  Repo:        {REPO_PATH}")
    print(f"  pygit2:      {pygit2.__version__}")
    print(f"  libgit2:     {pygit2.LIBGIT2_VERSION}")
    print(f"  Warmup:      {WARMUP}")
    print(f"  Measured:    {ITERS}")

    repo = pygit2.Repository(REPO_PATH)
    # Bench repos created by create-bench-repo.sh use refs/heads/main; fall
    # back to the first refs/bench/* if main is missing (e.g. an older repo).
    try:
        head_oid = repo.lookup_reference("refs/heads/main").target
    except KeyError:
        head_oid = repo.lookup_reference("refs/bench/0000000001").target

    # Probe number of refs (matches the JGit benchmark output style)
    refs_count = sum(1 for _ in repo.references)
    print(f"  Refs in repo: {refs_count:,}")
    print()

    # --- Scenario A: CREATE new ref ---
    bench_create(repo, head_oid, "warmup", WARMUP)  # warmup
    elapsed = bench_create(repo, head_oid, "measure", ITERS)
    report("pygit2 CREATE new ref", elapsed, ITERS)

    # --- Scenario B: UPDATE existing packed ref (force=True to skip FF check) ---
    bench_update_packed(repo, head_oid, WARMUP, start_idx=1)  # warmup
    elapsed = bench_update_packed(repo, head_oid, ITERS, start_idx=WARMUP + 1)
    report("pygit2 UPDATE existing ref (force)", elapsed, ITERS)

    print()
    print("==== caveats ====")
    print("  - libgit2 is a separate C implementation of git's on-disk format,")
    print("    not canonical git. Per-op cost should be in the same ballpark.")
    print("  - pygit2 wraps libgit2 with CPython; FFI overhead is real but")
    print("    small per call (microseconds, not milliseconds).")
    print("  - For canonical git (the C code that ships with git itself) the")
    print("    only ways to measure per-op cost are forking the binary per")
    print("    call (slow) or bulk --stdin (amortised). See native-git-bench.sh.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
