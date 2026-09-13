# JavascriptPerformance — cross-engine benchmark comparison

Runs the same benchmark suites — **Octane**, **SunSpider**, **ubench**, and the **V8
benchmarks** — against the [Monflabs Nashorn fork](https://github.com/monflabs/nashorn),
upstream OpenJDK Nashorn, Rhino (interpreted and compiled), GraalJS (interpreted, and
compiled when a real GraalVM compiler is present), and real V8 (via the
[Javet](https://github.com/caoccao/Javet) JNI binding), and reports execution time
compared across engines. Only `run()` is timed; parsing/compiling a benchmark happens once,
outside the timed loop. The project vendors its own copy of the benchmark scripts under
`src/main/resources/benchmarks/` — nothing is fetched at build time.

## Prerequisite: a built `nashorn-core` (Monflabs fork)

`org.monflabs.nashorn:nashorn-core` is not published to Maven Central. Build and install it
from a peer checkout of [monflabs/nashorn](https://github.com/monflabs/nashorn) before
building this project:

```bash
cd ../nashorn   # peer checkout, next to this project
mvn -pl core -am install -DskipTests
```

That installs `org.monflabs.nashorn:nashorn-core` into your local `~/.m2` repository at
whatever version `nashorn.monflabs.version` in this project's `pom.xml` names. Bump that
property when you rebuild against a newer fork revision.

## Prerequisite: none, for V8 — but check your platform is covered

`V8_JAVET` needs a native V8 library, and Javet ships one per (OS, CPU architecture) as a
separate Maven artifact rather than as classifiers of one jar. The `pom.xml` has one
OS-activated profile per supported platform (macOS/Linux/Windows × x86_64/aarch64) that
pulls in the matching `com.caoccao.javet:javet-v8-<os>-<arch>` artifact automatically —
nothing to configure by hand on a covered platform. On an *uncovered* platform, no profile
activates, there is no native binding on the classpath, and `JavetExecutor.isSupported()`
reports it unavailable: the build still succeeds, and the benchmark run records `N/A` for
every `V8_JAVET` row rather than failing.

## Run the smoke test

`PerformanceSmokeTest` times real (if tiny) benchmark executions across every engine, so
it's too slow to be part of a plain build and is **skipped by default** — a normal
`mvn package` never runs it. Opt in with the `performance-tests` profile:

```bash
mvn package -Pperformance-tests
```

Results:

```bash
cat target/surefire-reports/org.monflabs.nashorn.performance.PerformanceSmokeTest.txt
```

## Run the full benchmark comparison

After the `package` build above has produced the shaded jar:

```bash
java -jar target/javascript-performance-1.0.0-SNAPSHOT-all.jar
```

This runs all four suites across the default `--engines=COMPILED` engine set with the default
`--warmup=2 --iterations=5`, prints a console table, and writes
`target/performance-report.csv` and `target/performance-report.html`. Each engine runs in its
own JVM and the whole set is repeated over `--rounds` interleaved rounds (see *Isolation*
below), so a full run (particularly Octane) can take a while — count on
`rounds × engines` JVMs. Pass `--engines=ALL` to include the three
interpreted-only engines too.

Narrow it down with:

```bash
java -jar target/javascript-performance-1.0.0-SNAPSHOT-all.jar \
  --suites=ubench,sunspider \
  --engines=ALL \
  --warmup=1 --iterations=2 \
  --report=/tmp/report.csv
```

| Option | Default | Notes |
| --- | --- | --- |
| `--suites=` | `octane,sunspider,ubench,v8-benchmarks` | comma-separated |
| `--engines=` | `COMPILED` | comma-separated; each token is an individual `ScriptExecutor.ENGINE` name or one of the pseudo-groups below, matched case-insensitively (and freely mixable, e.g. `--engines=NASHORN,V8_JAVET`) |
| `--octane-benchmarks=` | an 11-file subset | comma-separated; `code-load`, `typescript*`, `zlib*` excluded by default |
| `--warmup=N` | `2` | untimed iterations before the timed run |
| `--iterations=N` | `5` | timed iterations, wall/cpu time summed (see Methodology) |
| `--report=<path>` | `target/performance-report.csv` | CSV output path |
| `--html-report=<path>` | `target/performance-report.html` | self-contained HTML report path (see below) |
| `--isolate=true\|false` | `true` | one JVM per engine, rounds interleaved (see Methodology) |
| `--rounds=N` | `3` | JVMs per engine; must be odd |

`--engines=` pseudo-groups (`Performance.ENGINE_GROUPS`):

| Group | Expands to |
| --- | --- |
| `COMPILED` (the default) | `NASHORN_MONFLABS, NASHORN_OPENJDK, RHINO_COMPILED, GRAALJS_COMPILED, V8_JAVET` |
| `ALL` | every engine — `COMPILED`'s five, then the two interpreted-only engines `RHINO_INTERPRETED, GRAALJS_INTERPRETED` |
| `NASHORN` | `NASHORN_MONFLABS, NASHORN_OPENJDK` |

On a plain JDK with no GraalVM compiler (e.g. a stock Zulu/Temurin build), expect
`GRAALJS_COMPILED` to report `N/A` — that's `ScriptExecutor.isSupported()` correctly
declining rather than a failure. `GRAALJS_INTERPRETED` still runs and reports real
timings on any JDK. Likewise, `V8_JAVET` reports `N/A` on a platform none of the pom's
OS-activated Javet profiles cover (see above) — same "correctly declining" path, not a
failure.

`V8_JAVET` defaults V8's global `--use-strict` flag off (`V8RuntimeOptions.V8_FLAGS`, set once
in `JavetExecutor`'s static initializer, before the first `V8Runtime` is created) — Javet's own
default is *on*, which would otherwise reject the sloppy-mode implicit-global assignments
(`x = 0` on an undeclared identifier) that every other engine here accepts and that a couple of
the vendored benchmark files rely on.

## Visual report

Every run also writes a self-contained HTML report (`--html-report=<path>`, default
`target/performance-report.html`) — a data table with the same wall-time columns as
the CSV (see Methodology), followed by one bar chart per (suite, file), one bar per engine,
comparing wall time. No external
stylesheet, script, image, or network fetch: `BenchmarkCollector.toHtmlReport()` renders plain
`<table>` markup and inline SVG `<rect>`/`<text>` elements directly from the collected results,
so the file opens in any browser as-is, with no build step or dependency beyond what this
project already has. Each chart is scaled to its own row's highest value (not a shared scale
across the whole report), so a two-second and a sixty-second benchmark are both readable on
their own chart; hover a bar for its engine name and exact time. `FAILED` and `N/A` cells are
labeled directly on the chart rather than drawn as a bar.

## Methodology

For each (suite, file, engine) triple, `BenchmarkRunner.runFile` (`BenchmarkRunner.java`):

1. Builds a fresh `ScriptExecutor` for the engine and checks `isSupported()` — an engine/mode
   that can't run at all on this JVM (`GRAALJS_COMPILED` with no Graal compiler) is recorded
   as `NOT_AVAILABLE` ("N/A") and skipped entirely, never counted as a failure.
2. Concatenates the suite's `base.js` (if any) + any multi-part companion files (e.g.
   `gbemu-part1.js`/`gbemu-part2.js`) + the benchmark file + `run.js` (if any), then calls
   `ScriptExecutor.init()` **once** to parse/compile that combined script. This happens
   *before* any timing starts, per engine per file — compilation time is never measured. For a
   suite with a `run.js` but no `base.js` (SunSpider), the benchmark file is wrapped in a
   callable `__benchmarkBody__()` function instead of running inline, and a `__benchmarkFile__`
   global is set to the current filename, letting `run.js` drive repetition itself and look up
   a per-file repeat count (see below).
3. Runs `warmupIterations` (`--warmup`, default **2**) untimed calls to `run()` to let the
   engine JIT-warm/stabilize, discarding their timings entirely.
4. Runs `iterations` (`--iterations`, default **5**) timed calls to `run()`, each wrapped in
   `System.nanoTime()` (wall time) and `ThreadMXBean.getCurrentThreadCpuTime()` (CPU time on
   the calling thread) — `PerformanceWatch.runWithException` (`PerformanceWatch.java`).
5. Reduces those iterations to one number, in milliseconds. Which one depends on the mode:
   under `--isolate` (the default) it is the **fastest** iteration, which the parent then takes
   a median of across rounds — see *Isolation* below. Under `--isolate=false` it is the **sum**
   of the iterations with the fastest and the slowest discarded (when `iterations` is at least
   **3**), since a single run can be thrown off by a GC pause, a JIT recompile or OS scheduling
   noise in either direction. A summed figure is not an average per run: to compare
   per-execution cost between two rows, divide by `--iterations` (or `--iterations - 2` when
   trimming applied).
6. Calls `ScriptExecutor.terminate()` once the timed loop finishes.

A `Throwable` from any of the above is caught per (engine, file) — one engine failing a
benchmark doesn't abort the run or affect any other engine/file — and recorded as `FAILED`,
distinct from `NOT_AVAILABLE`.

Each engine gets its own fresh `ScriptExecutor` instance per file (a new JS realm/context),
so no benchmark's state or warmup leaks into another file or another engine.

### Isolation: one JVM per engine, rounds interleaved

By default (`--isolate`, `IsolatedRunner.java`) the process you launch measures nothing itself.
It forks one child JVM per engine, repeats the whole set over `--rounds` rounds, and reports the
median of the rounds. A round runs every engine once, in the order `--engines` named them, so
the timeline is `A B A B A B` rather than `A A A B B B`. Each child is
`Performance --child-engine=<ENGINE>`: it runs the same suites with that one engine and prints
each measurement as a `##RESULT` line on stdout, which the parent picks out and takes the median
of; every other line the child prints is echoed as progress.

This is the same shape as the engine's own `buildtools/perf-gate.sh`, and each part of it is
there because measuring without it produced a wrong answer:

- **One engine per JVM.** Two engines in one process share a heap, a code cache and a set of
  profiles, and whichever runs second inherits the first's state. Measured the single-JVM way,
  an arithmetic micro-benchmark read **+11%** where separate JVMs showed **+1.6%**.
- **Interleaved rounds.** All of engine A and then all of engine B lets a machine that warms up,
  throttles, or picks up unrelated load bias one side wholesale. Measured one side then the
  other, the first side came out slower on *all seven* of perf-gate's metrics, by up to **17%**.
  Alternating spreads any such drift evenly over both.
- **Minimum within a JVM, median across JVMs.** Inside one process every disturbance costs time,
  so the fastest iteration is the truest. Across processes a sample can be spuriously *fast* —
  the first JVM after a build runs on a boosted CPU — and a minimum would enshrine that outlier,
  where a median discards it. This is why the two modes reduce their iterations differently
  (step 5 above), and why `--rounds` must be **odd**: with an even count the median would be the
  average of the two middle rounds rather than a measurement that actually happened.
- **A pinned heap.** Every child gets `-Xms1g -Xmx1g -XX:+UseG1GC`. Left to ergonomics, G1 sizes
  itself differently from process to process, and the allocation-heavy benchmarks moved **15%**
  on that alone. The parent's own JVM arguments are forwarded to each child, minus any
  `-Xms`/`-Xmx`/collector flag that would fight the pin.

A benchmark that was `FAILED` or `N/A` in *any* round is reported that way rather than as a
median over the rounds that did work, so a flaky engine cannot hide behind a number. A child
that dies outside a benchmark is reported on its own line and the run continues; its missing
rows stay blank rather than becoming good-looking numbers.

`--isolate=false` reverts to the original behaviour: a single JVM, every engine in it, one pass,
no forking. It is faster and fine for a smoke test, but its cross-engine numbers carry the
order bias above — don't compare engines with it.

**Short benchmarks need more iterations, not more rounds.** Below roughly 5ms per iteration the
timer and scheduler noise dominates and no amount of median-taking recovers it: one
micro-benchmark read **+558%** at 8 in-JVM iterations and **+6.3%** at 25. Raise `--iterations`
for those, and check nothing else is loading the machine before trusting a run at all — a Time
Machine backup once turned every metric into a 2x "regression".

### Octane, SunSpider, and v8-benchmarks-v6 run a fixed amount of work, not a fixed window

Octane's `RunStep`/`Measure` loop in `base.js`, the V8 Benchmark Suite's equivalent loop, and
SunSpider's individual tests are all short enough on their own that a single call is too fast
relative to timer/GC/JIT noise to measure reliably, and each of these three suites' own default
driver handles that by self-calibrating: running `benchmark.run()` (or the whole test body)
repeatedly until a fixed wall-clock window has elapsed, rather than a fixed amount of work
(`elapsed < 1000` for Octane, `MIN_TIME = 10000` for v8-benchmarks-v6, `TARGET_MS` for
SunSpider). Run through this harness, that self-calibration is actively harmful: `run()`'s own
wall/cpu time would converge to a near-constant multiple of that window on every engine, fast
or slow — measuring the calibration window, not engine speed.

So this project overrides that behavior for all three suites, replacing the time-boxed loop
with a fixed number of repetitions per benchmark, so wall time reflects a fixed amount of work
instead:
- **Octane** (`octane-master/run.js`) turns on the suite's own built-in `doDeterministic` mode,
  which runs each benchmark's pre-tuned `deterministicIterations` count (set per-file in
  `octane-master/*.js`, e.g. `deltablue.js`/`richards.js`/`splay.js`) instead of time-boxing.
- **v8-benchmarks-v6** (`v8-benchmarks-v6/base.js`) replaces `RunSingleBenchmark`'s time-boxed
  loop with a `FIXED_ITERATIONS` map keyed by benchmark name, each value picked so one measured
  pass takes roughly 300ms on a mid-speed engine.
- **SunSpider** (`sunspider-1.0.2/run.js`) replaces the time-boxed loop with a `REPEATS` map
  keyed by filename (via the harness-injected `__benchmarkFile__` global — see Methodology),
  each value picked so one measured pass takes roughly 250ms on a mid-speed engine.

One consequence: none of the three suites' own reference-relative score is meaningful anymore,
since it's computed from the now-bypassed time-boxed measurement — this harness never reported
that score anyway (see `WallTime(ms)`/`CpuTime(ms)` in Methodology).

## History

Extracted from the `performance` module of [monflabs/nashorn](https://github.com/monflabs/nashorn)
into its own project, since it compares several engines and isn't specific to that fork.

Support for Monflabs **GaltaJS** (interpreted, and compiled - it transpiles the script to Java
source and compiles/loads it as a real class) lives on the **`galtajs` branch**, and is kept off
`main` because that engine is not public yet. The branch is otherwise identical; merge `main` into
it to carry harness changes across.
