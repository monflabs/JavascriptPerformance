# JavascriptPerformance — cross-engine benchmark comparison

Runs the same benchmark suites — **Octane**, **SunSpider**, **ubench**, and the **V8
benchmarks** — against the [Monflabs Nashorn fork](https://github.com/monflabs/nashorn),
upstream OpenJDK Nashorn, the [Monflabs Rhino fork](https://github.com/monflabs/rhino) and upstream
Rhino (each interpreted and compiled), Monflabs GaltaJS (interpreted,
and compiled by transpiling to Java), GraalJS (interpreted, and
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

## Prerequisite: a built Monflabs Rhino

`org.monflabs.mozilla:rhino` and `rhino-tools` are not published either. Publish them to your
local `~/.m2` from a peer checkout of [monflabs/rhino](https://github.com/monflabs/rhino):

```bash
cd ../rhino   # peer checkout, next to this project
./gradlew :rhino:publishToMavenLocal :rhino-tools:publishToMavenLocal -x test
```

The version is `rhino.monflabs.version` in this project's `pom.xml` (`2026.0`). Republishing it
to `~/.m2` and rebuilding this project picks up the new engine. The fork lives in the
`org.monflabs.mozilla` packages, so it sits next to upstream Rhino on one classpath. It runs in its
`VERSION_ECMASCRIPT` language version; upstream Rhino runs in `VERSION_ES6`.

## GaltaJS

[`org.monflabs.galta:js`](https://central.sonatype.com/artifact/org.monflabs.galta/js) is on
Maven Central; nothing to install. `GALTAJS_COMPILED` transpiles the script to Java and compiles
it in memory with javac (in `parse`, outside the timed region), so it needs a JDK, not just a JRE.

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
| `--suites=` | `octane,sunspider,ubench,v8-benchmarks` | comma-separated; `intrinsics`, `web-tooling` and `jetstream3` are opt-in (see below) |
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
| `COMPILED` (the default) | `NASHORN_MONFLABS, NASHORN_OPENJDK, RHINO_MONFLABS_COMPILED, RHINO_COMPILED, GRAALJS_COMPILED, V8_JAVET, GALTAJS_COMPILED` |
| `ALL` | every engine — `COMPILED`'s seven, then the four interpreted-only engines `RHINO_MONFLABS_INTERPRETED, RHINO_INTERPRETED, GRAALJS_INTERPRETED, GALTAJS_INTERPRETED` |
| `NASHORN` | `NASHORN_MONFLABS, NASHORN_OPENJDK` |
| `RHINO` | `RHINO_MONFLABS_COMPILED, RHINO_COMPILED` |

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

### A run that fits in an hour

The defaults (3 isolated rounds, 2 warmups, 5 iterations, all engines) take many hours, mostly
because of the interpreted engines. This takes about 40 minutes on the compiled engines:

```bash
java -jar target/javascript-performance-1.0.0-SNAPSHOT-all.jar \
  --suites=octane,sunspider,ubench,intrinsics --engines=COMPILED \
  --rounds=1 --warmup=1 --iterations=1
```

It leaves out `v8-benchmarks` (its programs duplicate the Octane ones, ~10 minutes alone).

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
distinct from `NOT_AVAILABLE`. A benchmark that fails its own result check counts too: Octane and the V8 benchmarks
report such an error (mandreel's checksum, say) and carry on, so their `run.js` throws it at the
end instead, rather than letting wrong work be timed as if it were right.

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

### The `intrinsics` suite: generators and asynchronous processing

`--suites=intrinsics` (opt-in, not in the default set) benchmarks engine features through small
realistic programs rather than empty micro-loops: a tree iterator, a lazy pipeline, N-Queens, a
custom iterable collection and a tokenizer built on generators (`generator_*.js`), plus a request
handler, `Promise` combinators and an async-generator stream (`async_*.js`).

Each file defines `__benchmarkBody__()` returning `{expected, result}`. Synchronous results are
compared immediately; for a promise result the check is deferred to the start of the next run, so
the suite needs at least 2 iterations, and a run whose job queue was not drained fails. A mismatch
shows up as FAILED for that engine, which is also what happens when an engine lacks a feature
(e.g. OpenJDK Nashorn has no generators or `Promise`; Rhino has no `class` or `async`).

To measure asynchronous work, the executors drain the job queue inside the timed region: Rhino
`processMicrotasks()`, Javet `await()`, and the Monflabs Nashorn engine is created with
`--event-loop` (this applies to every suite).

### The `web-tooling` suite: real libraries, checked results

`--suites=web-tooling` (opt-in) runs the 18 workloads of the
[Web Tooling Benchmark](https://github.com/v8/web-tooling-benchmark) v0.5.3: Acorn, Babel,
Babel Minify, Babylon, Bublé, Chai, CoffeeScript, Espree, Esprima, JSHint, Lebab, PostCSS,
Prepack, Prettier, source-map, Terser, TypeScript and UglifyJS, each parsing, transforming or
generating real code (jQuery, Lodash, Vue, React components, CSS frameworks). Unlike the other
suites, these are large modern programs - classes, arrow functions, destructuring, generators,
`let`/`const`, scripts of 1 to 8 MB - so the suite measures both whether an engine runs
real-world code and how fast.

- Each file is one workload bundled with its library and the payloads it reads (a virtual `fs`).
  The bundle is guarded, so it is evaluated once per executor; each timed run calls the
  workload once (`base.js`, `run.js`): a fixed amount of work, where the benchmark's own driver
  (benchmark.js) samples for a fixed time.
- The first run checks the workload's result against a fingerprint computed on V8 (Node) when
  the suite was generated (`chai`, `jshint` and `source-map` return nothing: Chai's assertions
  throw on a wrong result). A workload that throws, does not compile, or computes something else
  is `FAILED`. With `--warmup=0`, the check is part of the first timed run.
- The files are named with underscores (`babel_minify.js`), so that the runner does not group
  `babel-minify` with `babel` as a multi-part benchmark.
- The files are generated, about 43 MB: `tools/web-tooling/build.sh` clones the benchmark at
  v0.5.3, builds one bundle per workload with webpack (Node 16) and writes the suite, its
  `base.js` and its fingerprints.

### The `jetstream3` suite: JetStream 3.0's JavaScript workloads

`--suites=jetstream3` (opt-in) runs the JavaScript workloads of the default list of
[JetStream 3.0](https://github.com/WebKit/JetStream) that the other suites do not already cover: 39 of
its 86. Left out are the 12 WebAssembly workloads, which not every engine here supports, and those of
Octane, SunSpider and the Web Tooling Benchmark (JetStream bundles newer versions of the latter's
libraries). Among the 39: ARES-6 (Air, Basic, Babylon, ML), RexBench, SeaMonster, the class-fields
raytracers, BigInt (noble-ed25519), Proxy (MobX, Vue), generators and async functions, startup of large
bundles (Babylon.js, MobX, jsdom with d3, Prism, React server-side rendering), three.js, TypeScript and
WSL.

- Each file is one workload with the files it preloads (compressed ones inflated). Its sources are
  wrapped in a function called on the first run, so they are loaded once per executor; `base.js` shims
  what JetStream's shell driver provides (`JetStream.getString`/`getBinary`, `console.assert` that throws,
  a seeded `Math.random`, and `setTimeout` on the job queue when the engine has none).
- Each timed run is one run of the workload as JetStream's driver does it - a new `Benchmark`, its
  `init`, its iterations and its `validate` - with a tenth of JetStream's iteration count (at least one),
  since the harness repeats the runs itself. JetStream's own `validate` (or the checks inside the
  workloads) makes a wrong result fail; an asynchronous workload's outcome is checked at the start of
  the next run, as in `intrinsics`.
- The files are generated, about 79 MB: `tools/jetstream3/build.sh` checks JetStream out at its
  `JetStream3.0` branch, reads its benchmark list from `JetStreamDriver.js` in a Node VM and writes the
  suite. Node 16 builds it; its V8 runs every workload, given `RegExp.escape`.
- Slow on the interpreted engines: startup workloads evaluate megabytes of source per iteration (about
  50 s per run on the Rhino fork), WSL about 20 s compiled.

## History

Extracted from the `performance` module of [monflabs/nashorn](https://github.com/monflabs/nashorn)
into its own project, since it compares several engines and isn't specific to that fork.

GaltaJS support was briefly kept on a separate `galtajs` branch while the engine was not public.
It is on Maven Central now and lives on `main`; that branch is obsolete.

## License

This project is licensed under the [Apache License, Version 2.0](LICENSE).

The benchmark suites vendored under `src/main/resources/benchmarks/` (SunSpider, the V8
benchmarks, Octane, ubench, the Web Tooling Benchmark, JetStream and the libraries they bundle) are third-party code and remain under their own original
licenses, as stated in those files.
