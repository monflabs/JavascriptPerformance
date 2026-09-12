# JavascriptPerformance — cross-engine benchmark comparison

Runs the same benchmark suites — **Octane**, **SunSpider**, **ubench**, and the **V8
benchmarks** — against the [Monflabs Nashorn fork](https://github.com/monflabs/nashorn),
upstream OpenJDK Nashorn, Rhino (interpreted and compiled), GraalJS (interpreted, and
compiled when a real GraalVM compiler is present), real V8 (via the
[Javet](https://github.com/caoccao/Javet) JNI binding), and Monflabs
[GaltaJS](https://github.com/monflabs/galta) (interpreted, and compiled - GaltaJS transpiles the
script to Java source and compiles/loads it as a real class), and reports execution time
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

## Prerequisite: a built GaltaJS (`org.monflabs.galta:js` + `:filesystem`)

Neither is published to Maven Central, not even as a SNAPSHOT. Build and `mvn install` a peer
checkout of the [Galta-Java](https://github.com/monflabs/galta) reactor first:

```bash
cd ../Galta-Java/galta   # peer checkout, next to this project
mvn install -DskipTests
```

That installs every `org.monflabs.galta:*` artifact this project needs
(`js`, `filesystem`, and their own transitive `json`/`javacompiler`/`utilities`) into your local
`~/.m2` repository at whatever version `galtajs.version` in this project's `pom.xml` names.

`GALTAJS_COMPILED` transpiles the benchmark to Java source and compiles/loads it as a real class
(all in-memory - an NIO `MemoryFileSystem`, nothing touches disk); `GALTAJS_INTERPRETED` walks
the AST directly. Both need no native library and are always available once the peer checkout is
installed.

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
`target/performance-report.csv` and `target/performance-report.html`. A full run
(particularly Octane) can take a while. Pass `--engines=ALL` to include the three
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

`--engines=` pseudo-groups (`Performance.ENGINE_GROUPS`):

| Group | Expands to |
| --- | --- |
| `COMPILED` (the default) | `NASHORN_MONFLABS, NASHORN_OPENJDK, GALTAJS_COMPILED, RHINO_COMPILED, GRAALJS_COMPILED, V8_JAVET` |
| `ALL` | every engine — `COMPILED`'s six, then the three interpreted-only engines `RHINO_INTERPRETED, GRAALJS_INTERPRETED, GALTAJS_INTERPRETED` |
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
5. If `iterations` is at least **3**, discards the fastest and the slowest of those runs
   (ranked by wall time) before summing — a single run can be thrown off by a GC pause, a JIT
   recompile, or OS scheduling noise, in either direction. Reports the **sum** of the remaining
   runs, in milliseconds — not an average per run. To compare per-execution cost between two
   rows, divide by `--iterations` (or `--iterations - 2` when trimming applied).
6. Calls `ScriptExecutor.terminate()` once the timed loop finishes.

A `Throwable` from any of the above is caught per (engine, file) — one engine failing a
benchmark doesn't abort the run or affect any other engine/file — and recorded as `FAILED`,
distinct from `NOT_AVAILABLE`.

Each engine gets its own fresh `ScriptExecutor` instance per file (a new JS realm/context),
so no benchmark's state or warmup leaks into another file or another engine.

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
