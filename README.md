# JGit Ref-Update Benchmark

Empirical comparison of `RefUpdate.update()` against `BatchRefUpdate.execute()` with a single `ReceiveCommand` (N=1), on the `RefDirectory` ref-database backend, at four ref-count scales (1K, 10K, 1M, 2M) up to two million refs — plus native-git (canonical-git + libgit2/pygit2) reference numbers so JGit's results can be sanity-checked against the C implementation.

*By **David Ostrovsky** — Gerrit & JGit maintainer.*

> **→ [Read the full analysis with charts](https://davido.github.io/jgit-ref-update-benchmark/)**

---

## TL;DR

**On `RefDirectory`, `BatchRefUpdate(N=1)` is as fast as — or faster than — `RefUpdate.update()` at every reliably-measured scale, for CREATE and for both packed- and loose-start UPDATE. The performance fear that motivated the pull-replication bifurcation is not borne out.**

Headline from direct measurement on `RefDirectory` (JMH, 2 forks × 3×5 s iterations):

| Scenario | Scale | BRU/RU |
|---|---|---|
| CREATE | 1M / 2M | 0.87 / 0.93 (BRU faster) |
| UPDATE, packed-start | 1M / 2M | 0.63 / 0.54 (BRU much faster) |
| UPDATE, loose-start | 10K | 0.71 (BRU faster) |

At 1K/10K the CREATE error bars are as large as the values themselves, so small-scale ratios aren't rankable — but nowhere does BRU(N=1) show a real regression. An earlier throwaway run hinted at a CREATE "inversion" (BRU ~1.67× slower at 1M); it **did not reproduce** once the benchmark's ref-selection bug was fixed (unique ref per invocation) and the run was given real forks and iterations.

**Implication for the pull-replication bifurcation (which motivated this benchmark).** The bifurcation kept single-ref updates on `RefUpdate` out of a fear that routing N=1 through `BatchRefUpdate` would be slower. Measurement does not support that fear: at N=1, BRU is as fast or faster than RU across CREATE and packed/loose UPDATE. The architectural-cleanliness case for bifurcating may stand on its own; the *performance* case does not.

[Root cause](#root-cause-from-jgit-source): `PackedBatchRefUpdate.execute` short-circuits at `pending.size() == 1` to the base `BatchRefUpdate.execute`, which skips the packed-refs locking/rewrite and the reflog dispatch that a direct `RefUpdate` pays — so BRU(N=1) is, if anything, the lighter path.

---

## Repo layout

```
jgit-ref-update-benchmark/
├── README.md                          # this file
├── index.html                         # GitHub Pages site (charts, narrative)
├── jgit-patch/
│   ├── RefCreateBenchmark.java         # CREATE sweep (1K/10K/1M/2M)
│   ├── PackedRefUpdateBenchmark.java   # UPDATE of a packed-start ref (1M/2M)
│   ├── LooseRefUpdateBenchmark.java    # UPDATE of a loose-start ref (10K)
│   └── RefBenchmarkSupport.java        # shared @Setup / repo-population helper
└── tools/bench/
    ├── create-bench-repo.sh           # populate a bare repo with N refs
    ├── native-git-bench.sh            # canonical-git baseline (fork + bulk --stdin)
    └── native-git-bench.py            # in-process baseline via libgit2/pygit2
```

---

## How to run

### 1. JGit JMH benchmark (the canonical one)

Lives in `jgit-patch/` — `RefCreateBenchmark.java` (CREATE), `PackedRefUpdateBenchmark.java` and `LooseRefUpdateBenchmark.java` (UPDATE of a packed- vs loose-start ref), and the shared `RefBenchmarkSupport.java`. Targets the `org.eclipse.jgit.benchmarks` Maven module of upstream JGit. Until pushed upstream, drop them into a local JGit clone:

```bash
# 1a. Clone JGit upstream
git clone https://github.com/eclipse-jgit/jgit.git
cd jgit

# 1b. Copy the benchmark files in
cp ../jgit-ref-update-benchmark/jgit-patch/Ref*.java \
   org.eclipse.jgit.benchmarks/src/org/eclipse/jgit/benchmarks/

# 1c. Build with JDK 21 (later JDKs trip the gmavenplus-plugin)
JAVA_HOME=/path/to/jdk-21 \
  mvn -DskipTests -pl org.eclipse.jgit.benchmarks -am install

# 1d. Run all three benchmarks (CREATE sweep + packed/loose UPDATE)
JAVA_HOME=/path/to/jdk-21 \
  java -jar org.eclipse.jgit.benchmarks/target/benchmarks.jar \
       '(RefCreate|PackedRefUpdate|LooseRefUpdate)Benchmark' \
       -f 2 -wi 2 -w 100ms -i 3 -r 5s -rf csv \
       -rff /tmp/refupdate-bench-results.csv
```

Override `numBranches` to reach 1M / 2M:

```bash
java -jar org.eclipse.jgit.benchmarks/target/benchmarks.jar PackedRefUpdateBenchmark \
  -p numBranches=2000000 -f 2 -wi 2 -w 100ms -i 3 -r 5s
```

Wall-clock per `numBranches`:
- 1K: ~1 min
- 10K: ~2 min
- 100K: ~3 min
- 1M: ~6 min
- 2M: ~10 min

The fast `@Setup` (direct `packed-refs` write) is what makes 1M/2M practical; without it each trial's setup would dominate at 5-10 min.

SDKMAN-friendly invocation:
```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.7-zulu \
  mvn -DskipTests -pl org.eclipse.jgit.benchmarks -am install
```

### 2. Populate the native-git bench repo

```bash
./tools/bench/create-bench-repo.sh /tmp/bench-100k-refs.git 100000
./tools/bench/create-bench-repo.sh /tmp/bench-1m-refs.git 1000000
./tools/bench/create-bench-repo.sh /tmp/bench-2m-refs.git 2000000
```

Uses `git update-ref --stdin` for population. ~5-10s for 100K, ~30-60s for 1M, ~60-120s for 2M. (Python is used for generating the input stream — BSD awk's `%d` loses precision at ~10⁶+ and produces duplicate ref names that abort the atomic stdin batch.)

### 3. Native-git shell benchmark (canonical git, C)

```bash
BENCH_REPO=/tmp/bench-100k-refs.git ./tools/bench/native-git-bench.sh
```

Two scenarios:
- **Per-call** (`git update-ref` invoked per ref, fork dominates) — *not* a fair head-to-head with JGit; useful only as "what does shelling out cost?"
- **Bulk** (`git update-ref --stdin` with N ops batched, single fork) — fair amortised comparison.

Knobs: `BULK_ITERS` (default 10000), `PER_CALL_ITERS` (default 100).

### 4. Native-git in-process benchmark (libgit2 via pygit2)

```bash
python3 -m venv /tmp/bench-venv
/tmp/bench-venv/bin/pip install pygit2
BENCH_REPO=/tmp/bench-100k-refs.git /tmp/bench-venv/bin/python ./tools/bench/native-git-bench.py
```

Two scenarios mirroring the JMH benchmark: CREATE new, UPDATE existing packed.

Knobs: `WARMUP` (200), `ITERS` (1000).

---

## Methodology notes

### Why a custom `@Setup` (deviates from `GetRefsBenchmark`'s pattern)

`GetRefsBenchmark` (Luca Milanesio, 2021) uses `BatchRefUpdate` to populate the prepopulated branches. That's idiomatic for JMH and works fine up to ~100K refs. At 1M and especially 2M, `PackedBatchRefUpdate`'s atomic packed-refs serialization makes each `@Setup` take **5-10 minutes** — multiply by the benchmark methods × ref counts and the run becomes ~50+ minutes of setup wall time.

This benchmark's `@Setup` writes the `packed-refs` file directly via `BufferedWriter`. **~6 seconds for 2M refs vs ~10 min.** End-state on disk is identical (JGit reads `packed-refs` on next `Repository` open); only the path to get there differs.

This is the *only* reason `numBranches=1_000_000` and `numBranches=2_000_000` are practical to run.

### Why the ratio is not constant across scales

Early intuition was "BRU/RU ratio is a function of fixed wrapper overhead, so it's constant across ref counts." Measurement disproves that: the BRU advantage for packed-start UPDATE *grows* with scale (0.63 at 1M → 0.54 at 2M), while CREATE sits near parity-to-BRU-favoured (0.87 / 0.93 at 1M / 2M). And at 1K/10K the per-op error bars are as large as the values themselves, so small-scale ratios can't be ranked at all — direct measurement at the deployment scale is the only honest answer.

That's why this repo invests in making 1M/2M runs cheap enough to actually do.

---

## Results

> Full JMH matrix below, all on RefDirectory: CREATE at 1K/10K/1M/2M, packed-start UPDATE at 1M/2M, loose-start UPDATE at 10K. (100K JMH row, and loose-start at larger scales, are gaps in the current matrix — deferred follow-up.)

### JGit JMH (the canonical benchmark)

JMH 1.37, JDK 21, `AverageTime` µs/op, single thread, 2 forks × (2×100 ms warmup + 3×5 s measurement); `±` is the 99.9 % CI. All on `RefDirectory`.

**CREATE a brand-new ref** (`RefCreateBenchmark`):

| Refs | createRU (µs/op) | createBRU (µs/op) | BRU/RU |
|---|---|---|---|
| 1,000 | 1,804 ±843 | 2,272 ±1,294 | 1.26 — *noise* |
| 10,000 | 1,701 ±694 | 1,716 ±1,170 | 1.01 — *noise* |
| **1,000,000** | **1,672 ±276** | **1,459 ±328** | **0.87** |
| **2,000,000** | **1,656 ±316** | **1,543 ±256** | **0.93** |

**UPDATE a packed-start ref** (`PackedRefUpdateBenchmark`):

| Refs | updateRU (µs/op) | updateBRU (µs/op) | BRU/RU |
|---|---|---|---|
| **1,000,000** | **1,754 ±514** | **1,107 ±303** | **0.63** |
| **2,000,000** | **1,966 ±928** | **1,059 ±269** | **0.54** |

**UPDATE a loose-start ref** (`LooseRefUpdateBenchmark`):

| Refs | updateRU (µs/op) | updateBRU (µs/op) | BRU/RU |
|---|---|---|---|
| 10,000 | 1,875 ±315 | 1,328 ±356 | **0.71** |

> Loose-start at 1K is intentionally not reported. Each invocation consumes one unique ref, and a 5 s JMH iteration runs ~2,700 ops — more than a 1K pool holds — so it exhausts. `LooseRefUpdateBenchmark` rebuilds its repo per iteration (`@Setup(Level.Iteration)`), which makes 10K stable at any iteration count, but a 1K pool can't survive even a single 5 s iteration.

**Patterns across the reliably-measured points (RefDirectory):**

- **BRU(N=1) is faster than RU for every UPDATE**, packed or loose: 0.54–0.71. The packed-start advantage *grows* with scale (0.63 → 0.54 from 1M → 2M).
- **CREATE is parity-to-BRU-favoured at 1M/2M** (0.87 / 0.93). At 1K/10K the error bars swamp the signal — no reliable ranking, and in particular **no reproducible "BRU slower" inversion** (the earlier 1.67 at 1M was a ref-selection-bug + single-fork artifact).

### Native git reference

Darwin x86_64, git 2.54.0, pygit2 1.19.3 / libgit2 1.9.4:

| Tool | Scenario | 100K µs/op | 1M µs/op | 2M µs/op | Notes |
|---|---|---|---|---|---|
| canonical git | per-call CREATE (100 iters) | **20,942** | **24,096** | _pending_ | fork+exec dominates |
| canonical git | per-call UPDATE (100 iters) | **22,955** | **25,857** | _pending_ | fork+exec dominates |
| canonical git | bulk CREATE (--stdin, 10K ops) | **1,192** | **1,130** | _pending_ | amortised; one fork |
| canonical git | bulk UPDATE (--stdin, 10K ops) | **861** | **557** | _pending_ | amortised; one fork |
| pygit2 (libgit2) | CREATE new (1000 iters) | **6,264** | **69,402** | _pending_ | **scales ~linearly with ref count** |
| pygit2 (libgit2) | UPDATE existing (1000 iters) | **6,685** | **71,971** | _pending_ | **scales ~linearly with ref count** |

**Important caveat on pygit2.** Going 100K → 1M increased pygit2 per-op cost by ~11× (10× the refs). libgit2 appears to scan `packed-refs` on every call when accessed through pygit2; the FFI overhead is the wrong story. Canonical git's bulk path stays flat across scales (it parses `packed-refs` once per fork). **pygit2 is NOT a fair baseline at scale** — only canonical-git bulk is.

### Cross-implementation ranking at 1M refs (fair scale match)

All numbers from runs against a 1,000,000-ref bare repo on the same Darwin x86_64 laptop:

| Rank | Path | µs/op | vs fastest |
|---|---|---|---|
| 🥇 | canonical git bulk UPDATE (`--stdin`, 10K ops/fork) | **557** | 1.0× |
| 🥈 | JGit `BatchRefUpdate` N=1 UPDATE-packed (RefDirectory) | **1,107** | 2.0× |
| 🥉 | canonical git bulk CREATE (`--stdin`, 10K ops/fork) | 1,130 | 2.0× |
| | JGit `BatchRefUpdate` N=1 CREATE (RefDirectory) | 1,459 | 2.6× |
| | JGit `RefUpdate.update()` CREATE (RefDirectory) | 1,672 | 3.0× |
| | JGit `RefUpdate.update()` UPDATE-packed (RefDirectory) | 1,754 | 3.1× |
| | pygit2 (libgit2) CREATE | 69,402 | 124× |
| | pygit2 (libgit2) UPDATE | 71,971 | 129× |
| 🐢 | canonical git per-call (fork-dominated) | ~25,000 | 45× |

**Reading this:**

- JGit's best path (`updateBRU` packed on RefDirectory, 1,107 µs) is **2.0× slower** than native git's bulk UPDATE; its `createBRU` (1,459 µs) is **1.3× slower** than native bulk CREATE. ~1.5-2× is the typical JVM-vs-C tax for IO-heavy work.
- **pygit2/libgit2 via Python FFI scales catastrophically** — 124-129× slower than native git bulk at 1M. Going 100K → 1M cost ~11× more (linear scan of `packed-refs` on each call through the FFI boundary). pygit2 is **not** a fair in-process baseline at scale; use canonical git bulk instead.
- Canonical git per-call (`git update-ref` once per ref) is dominated by `fork()` + `execve()` (~20-25 ms each). Confirms "don't shell out per ref from anything performance-sensitive."

---

## Root cause (from JGit source)

[`PackedBatchRefUpdate.execute`](https://github.com/eclipse-jgit/jgit/blob/master/org.eclipse.jgit/src/org/eclipse/jgit/internal/storage/file/PackedBatchRefUpdate.java#L114) short-circuits at `pending.size() == 1`:

```java
if (pending.size() == 1) {
    // Single-ref updates are always atomic, no need for packed-refs.
    super.execute(walk, monitor, options);
    return;
}
```

So a 1-command `BatchRefUpdate` on `RefDirectory` skips packed-refs locking/rewriting and falls through to the base `BatchRefUpdate.execute`, which loops once and creates a single `RefUpdate` via `newUpdate(cmd)`. Both paths therefore bottom out in the same `RefDirectoryUpdate.doUpdate` for the write itself — which is what makes the N=1 comparison meaningful: any difference is wrapper, not the write.

**What we measured:** that wrapper difference favours `BatchRefUpdate(N=1)` at every reliably-measured point, and the gap is larger for UPDATE than CREATE. *Why* the wrapper costs differ is not established here — candidates (e.g. the constructed `RefUpdate`'s reflog settings vs a direct `RefUpdate`'s `refLogMessage = ""` default) are left to a profiling/debug session.

---

## Why this benchmark exists

The Gerrit `pull-replication` plugin had a half-apply bug in its multi-ref `batch-apply-object` endpoint ([Issue 456926963](https://issues.gerritcodereview.com/issues/456926963)). The structural fix is to route the multi-ref endpoint through one atomic `BatchRefUpdate`.

An earlier proposal routed *both* single-ref and multi-ref endpoints through `BatchRefUpdate`. A brain-storming session worried about *performance regression* and pushed for a bifurcation. The bifurcation shipped (changes [1239927](https://review.gerrithub.io/c/GerritForge/pull-replication/+/1239927)–[1239930](https://review.gerrithub.io/c/GerritForge/pull-replication/+/1239930)) — but nobody had measured whether the performance concern was real.

This benchmark answers that question. So far, at every measured scale, `BatchRefUpdate(N=1)` matches or beats `RefUpdate.update()` on `RefDirectory`. The architectural-cleanliness justification for the bifurcation stands; the *performance* justification has not been corroborated by measurement.

---

> **→ [Full analysis with charts and source-code references](https://davido.github.io/jgit-ref-update-benchmark/)**
