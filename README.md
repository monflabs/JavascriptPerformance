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

This runs all four suites across all seven engine modes with the default
`--warmup=2 --iterations=5`, prints a console table, and writes
`target/performance-report.csv` and `target/performance-report.html`. A full run
(particularly Octane) can take a while.

Narrow it down with:

```bash
java -jar target/javascript-performance-1.0.0-SNAPSHOT-all.jar \
  --suites=ubench,sunspider \
  --engines=NASHORN_MONFLABS,NASHORN_OPENJDK,RHINO_INTERPRETED,RHINO_COMPILED,GRAALJS_INTERPRETED,GRAALJS_COMPILED,V8_JAVET,GALTAJS_INTERPRETED,GALTAJS_COMPILED \
  --warmup=1 --iterations=2 \
  --report=/tmp/report.csv
```

| Option | Default | Notes |
| --- | --- | --- |
| `--suites=` | `octane,sunspider,ubench,v8-benchmarks` | comma-separated |
| `--engines=` | all seven `ScriptExecutor.ENGINE` values | comma-separated |
| `--octane-benchmarks=` | an 11-file subset | comma-separated; `code-load`, `typescript*`, `zlib*` excluded by default |
| `--warmup=N` | `2` | untimed iterations before the timed run |
| `--iterations=N` | `5` | timed iterations, wall/cpu time summed (see Methodology) |
| `--report=<path>` | `target/performance-report.csv` | CSV output path |
| `--html-report=<path>` | `target/performance-report.html` | self-contained HTML report path (see below) |

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
`target/performance-report.html`) — a data table with the same wall-time and Score columns as
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
   *before* any timing starts, per engine per file — compilation time is never measured.
3. Runs `warmupIterations` (`--warmup`, default **2**) untimed calls to `run()` to let the
   engine JIT-warm/stabilize, discarding their timings entirely.
4. Runs `iterations` (`--iterations`, default **5**) timed calls to `run()`, each wrapped in
   `System.nanoTime()` (wall time) and `ThreadMXBean.getCurrentThreadCpuTime()` (CPU time on
   the calling thread) — `PerformanceWatch.runWithException` (`PerformanceWatch.java`).
5. Reports the **sum** across those `iterations` timed runs, in milliseconds — not an average
   per run. To compare per-execution cost between two rows, divide by `--iterations`.
6. Calls `ScriptExecutor.terminate()` once the timed loop finishes.

A `Throwable` from any of the above is caught per (engine, file) — one engine failing a
benchmark doesn't abort the run or affect any other engine/file — and recorded as `FAILED`,
distinct from `NOT_AVAILABLE`.

Each engine gets its own fresh `ScriptExecutor` instance per file (a new JS realm/context),
so no benchmark's state or warmup leaks into another file or another engine.

### Wall/cpu time is not a valid cross-engine metric for Octane and v8-benchmarks-v6

Octane and the V8 Benchmark Suite each self-calibrate their own internal timing loop to run
for a fixed wall-clock window rather than a fixed amount of work: Octane's `RunStep`/`Measure`
loop in `base.js` keeps calling `benchmark.run()` while `elapsed < 1000` (ms); the V8 Benchmark
Suite's equivalent loop uses `MIN_TIME = 10000`. So `run()`'s own wall/cpu time reported by
this harness for those two suites converges to a near-constant multiple of that window on
every engine, fast or slow — it measures the calibration window, not engine speed, and the
`WallTime(ms)`/`CpuTime(ms)` columns for those two suites specifically are **not** meaningful
for comparing engines (they still are for SunSpider and ubench, which have no such loop).

The suite's own internally computed benchmark score - a geometric mean across its component
benchmarks, higher is better, the same number that suite would print as
`Score (version N): <score>` - is the metric that actually reflects engine speed for these two
suites. Both vendored `run.js` files (under `src/main/resources/benchmarks/`) end on a
deliberate trailing `lastScore;` expression exposing that score as the script's own execution
result, which `ScriptExecutor.getLastScore()` retrieves per run and the CSV/HTML report surface
as a **Score** column, one per engine, alongside the existing wall/cpu time columns. A suite
with no such convention (SunSpider, ubench) always reports a blank Score - `BenchmarkRunner`
appends a trailing `undefined;` to their concatenated script precisely so an incidental numeric
last-statement value in one of their benchmark files can never leak through as a spurious
score - and their wall/cpu time remains the valid metric, as before.

`GALTAJS_COMPILED` always reports a blank Score too, for a different reason: GaltaJS's
transpiled/compiled runtime only surfaces a script's completion value through an explicit
`return`, never from a bare top-level expression statement - so `run.js`'s trailing `lastScore;`
is never captured in that mode. `GALTAJS_INTERPRETED` has no such limitation and reports Score
normally; both modes' wall/cpu time are unaffected and remain valid.

## History

Extracted from the `performance` module of [monflabs/nashorn](https://github.com/monflabs/nashorn)
into its own project, since it compares several engines and isn't specific to that fork.
