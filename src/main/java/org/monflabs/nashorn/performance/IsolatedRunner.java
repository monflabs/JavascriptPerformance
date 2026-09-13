/*
 * Copyright (c) 2026, Philippe Riand.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.monflabs.nashorn.performance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.monflabs.nashorn.performance.BenchmarkCollector.Status;
import org.monflabs.nashorn.performance.ScriptExecutor.ENGINE;

/**
 * Runs the selected benchmarks with one JVM per engine, interleaved round by round - the shape
 * of the engine's own {@code buildtools/perf-gate.sh}, for the same reasons:
 *
 * <ul>
 *   <li><b>One engine per JVM.</b> Two engines in one process share a heap, a code cache and a
 *       set of profiles, and whichever runs second inherits the first's state. Measured the
 *       single-JVM way, a benchmark read +11% where separate JVMs showed +1.6%.</li>
 *   <li><b>Interleaved rounds.</b> All of engine A then all of engine B lets a machine that
 *       warms up, throttles or picks up unrelated load bias one side wholesale. Alternating
 *       spreads any drift evenly over both.</li>
 *   <li><b>Minimum within a JVM, median across JVMs.</b> Inside one process every disturbance
 *       costs time, so the fastest iteration is the truest; across processes a sample can be
 *       spuriously <em>fast</em> (the first JVM after a build runs on a boosted CPU), which a
 *       minimum would enshrine and a median discards.</li>
 *   <li><b>A pinned heap</b> ({@code -Xms1g -Xmx1g -XX:+UseG1GC}) in every child, because left
 *       to ergonomics G1 sizes itself differently per process and allocation-heavy benchmarks
 *       move 15% on that alone.</li>
 * </ul>
 *
 * A child is this same class's {@link Performance} main with {@code --child-engine=<ENGINE>},
 * which runs that one engine over the same suites and prints each measurement as a
 * {@value #RESULT_PREFIX} line. Everything else a child prints is passed through as progress.
 */
public final class IsolatedRunner {

    /** Marks a measurement line on a child's stdout; the rest of the line is tab-separated. */
    public static final String RESULT_PREFIX = "##RESULT\t";

    /** Fixed so two children of the same run cannot be sized differently by ergonomics. */
    private static final List<String> HEAP_ARGS = List.of("-Xms1g", "-Xmx1g", "-XX:+UseG1GC");

    private final List<ENGINE> engines;
    private final int rounds;
    private final List<String> childArgs;

    /** (suite::file::engine) -> one measurement per round that produced one. */
    private final Map<String, List<long[]>> samples = new LinkedHashMap<>();
    private final Map<String, Status> statuses = new LinkedHashMap<>();
    private final Map<String, String[]> keyParts = new LinkedHashMap<>();

    /**
     * @param engines   the engines to compare, run in this order within every round
     * @param rounds    how many JVMs per engine; odd, so the median is a measurement rather
     *                  than the average of two
     * @param childArgs the CLI arguments to hand each child, minus the engine and isolation
     *                  options this class supplies itself
     */
    public IsolatedRunner(List<ENGINE> engines, int rounds, List<String> childArgs) {
        if (rounds % 2 == 0) {
            throw new IllegalArgumentException("--rounds must be odd, so the median is a "
                    + "measurement rather than an average of two: " + rounds);
        }
        this.engines = List.copyOf(engines);
        this.rounds = rounds;
        this.childArgs = List.copyOf(childArgs);
    }

    /** Runs every round and returns the medians, ready to report. */
    public BenchmarkCollector run() throws IOException {
        for (int round = 1; round <= rounds; round++) {
            for (ENGINE engine : engines) {
                System.out.println();
                System.out.println("=== round " + round + "/" + rounds + " :: " + engine.name() + " ===");
                runChild(engine);
            }
        }
        return summarize();
    }

    private void runChild(ENGINE engine) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + "/bin/java");
        // The parent's own JVM arguments (module opens, agents, -D properties a benchmark reads)
        // minus anything that would fight the pinned heap below.
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.startsWith("-Xms") || arg.startsWith("-Xmx") || arg.endsWith("GC")) {
                continue;
            }
            command.add(arg);
        }
        command.addAll(HEAP_ARGS);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Performance.class.getName());
        command.addAll(childArgs);
        command.add("--child-engine=" + engine.name());

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(RESULT_PREFIX)) {
                    accept(line.substring(RESULT_PREFIX.length()));
                } else {
                    System.out.println(line);
                }
            }
        }
        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("interrupted waiting for " + engine.name(), e);
        }
        if (exit != 0) {
            // Not fatal on its own: the child prints and records a per-benchmark failure itself,
            // so a non-zero exit means it died outside a benchmark. Say so and keep going - the
            // missing rows will show as blanks rather than as silently good numbers.
            System.out.println("*** " + engine.name() + " child exited with " + exit + " ***");
        }
    }

    /** Formats one measurement for a child to print. */
    static String resultLine(String suite, String file, ENGINE engine, Status status,
            long wallMs, long cpuMs) {
        return RESULT_PREFIX + String.join("\t", suite, file, engine.name(), status.name(),
                Long.toString(wallMs), Long.toString(cpuMs));
    }

    private void accept(String payload) {
        String[] parts = payload.split("\t");
        if (parts.length != 6) {
            System.out.println("*** unparseable result line: " + payload + " ***");
            return;
        }
        String suite = parts[0];
        String file = parts[1];
        ENGINE engine = ENGINE.valueOf(parts[2]);
        Status status = Status.valueOf(parts[3]);
        String key = suite + "::" + file + "::" + engine.name();
        keyParts.putIfAbsent(key, new String[] {suite, file, engine.name()});
        // A benchmark that failed or was unavailable in any round is reported that way: a
        // median over the rounds that did work would hide a flaky engine behind a number.
        Status seen = statuses.get(key);
        if (seen != Status.FAILED && (status != Status.OK || seen == null)) {
            statuses.put(key, status);
        }
        if (status == Status.OK) {
            samples.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new long[] {Long.parseLong(parts[4]), Long.parseLong(parts[5])});
        }
    }

    private BenchmarkCollector summarize() {
        BenchmarkCollector collector = new BenchmarkCollector();
        for (Map.Entry<String, String[]> entry : keyParts.entrySet()) {
            String key = entry.getKey();
            String[] parts = entry.getValue();
            ENGINE engine = ENGINE.valueOf(parts[2]);
            Status status = statuses.get(key);
            List<long[]> rows = samples.get(key);
            if (status != Status.OK || rows == null || rows.isEmpty()) {
                collector.addResult(parts[0], parts[1], engine,
                        status == null ? Status.FAILED : status, 0, 0);
            } else {
                collector.addResult(parts[0], parts[1], engine, Status.OK,
                        median(rows, 0), median(rows, 1));
            }
        }
        return collector;
    }

    private static long median(List<long[]> rows, int column) {
        List<Long> values = new ArrayList<>(rows.size());
        for (long[] row : rows) {
            values.add(row[column]);
        }
        Collections.sort(values);
        return values.get(values.size() / 2);
    }
}
