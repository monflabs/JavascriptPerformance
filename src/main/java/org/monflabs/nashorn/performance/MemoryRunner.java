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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.monflabs.nashorn.performance.BenchmarkCollector.Status;
import org.monflabs.nashorn.performance.ScriptExecutor.ENGINE;

/**
 * Runs the selected benchmarks in memory mode ({@code --memory}): one JVM per engine and
 * benchmark, with the same pinned heap as {@link IsolatedRunner}, running it through
 * {@link MemoryWatch} and printing its figures as a {@value #MEMORY_PREFIX} line. A JVM per
 * benchmark, and not per engine, because a benchmark that failed can leave its realm reachable
 * (from a stack trace, say) into the baseline of the next one, which then reads tens of megabytes
 * below zero. Each engine's first JVM measures an empty script ({@value #ENGINE_FOOTPRINT}),
 * what the engine costs before any benchmark. Memory figures do not drift with the machine's load
 * the way time does, so there is one round and the engines run one after the other.
 */
public final class MemoryRunner {

    /** Marks a measurement line on a child's stdout; the rest of the line is tab-separated. */
    public static final String MEMORY_PREFIX = "##MEMORY\t";

    private static final String[] COLUMNS = {
            "compile alloc KB", "retained compiled KB", "alloc per run KB", "retained after run KB",
            "metaspace KB", "classes"};

    /** The {@code --only-file} that makes a child measure the engine's footprint. */
    public static final String ENGINE_FOOTPRINT = "engine/empty";

    private final List<ENGINE> engines;
    private final List<String> files;
    private final List<String> childArgs;

    /** suite::file -> engine -> the line's fields after the engine (status, then the figures). */
    private final Map<String, Map<ENGINE, String[]>> rows = new LinkedHashMap<>();

    /**
     * @param files the benchmarks, as {@code <suite folder>/<file name>}
     *              ({@link BenchmarkRunner#setListing})
     */
    public MemoryRunner(List<ENGINE> engines, List<String> files, List<String> childArgs) {
        this.engines = List.copyOf(engines);
        this.files = List.copyOf(files);
        this.childArgs = List.copyOf(childArgs);
    }

    /** Formats one measurement for a child to print; {@code sample} is null unless OK. */
    static String memoryLine(String suite, String file, ENGINE engine, Status status,
            MemoryWatch.Sample sample) {
        String figures = sample == null ? "\t\t\t\t\t"
                : String.join("\t", kb(sample.compileAlloc()), kb(sample.retainedCompiled()),
                        kb(sample.allocPerRun()), kb(sample.retainedAfterRun()), kb(sample.metaspace()),
                        Long.toString(sample.classes()));
        return MEMORY_PREFIX + String.join("\t", suite, file, engine.name(), status.name(), figures);
    }

    private static String kb(long bytes) {
        return Long.toString(Math.round(bytes / 1024.0));
    }

    public void run() throws IOException {
        for (ENGINE engine : engines) {
            System.out.println();
            System.out.println("=== memory :: " + engine.name() + " ===");
            runChild(engine, ENGINE_FOOTPRINT);
            for (String file : files) {
                runChild(engine, file);
            }
        }
    }

    private void runChild(ENGINE engine, String file) throws IOException {
        List<String> args = new ArrayList<>(childArgs);
        args.add("--only-file=" + file);
        List<String> command = IsolatedRunner.childCommand(args, "--child-memory=" + engine.name());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(MEMORY_PREFIX)) {
                    accept(line.substring(MEMORY_PREFIX.length()));
                } else {
                    System.out.println(line);
                }
            }
        }
        try {
            int exit = process.waitFor();
            if (exit != 0) {
                System.out.println("*** " + engine.name() + " " + file + " child exited with " + exit + " ***");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("interrupted waiting for " + engine.name(), e);
        }
    }

    private void accept(String payload) {
        String[] parts = payload.split("\t", -1);
        if (parts.length != 4 + COLUMNS.length) {
            System.out.println("*** unparseable memory line: " + payload + " ***");
            return;
        }
        String[] values = new String[1 + COLUMNS.length];
        System.arraycopy(parts, 3, values, 0, values.length);
        rows.computeIfAbsent(parts[0] + "::" + parts[1], k -> new LinkedHashMap<>())
                .put(ENGINE.valueOf(parts[2]), values);
    }

    /** One row per (suite, file, engine). */
    public String csv() {
        StringBuilder sb = new StringBuilder("suite,file,engine,status");
        for (String column : COLUMNS) {
            sb.append(',').append(column);
        }
        sb.append('\n');
        for (Map.Entry<String, Map<ENGINE, String[]>> row : rows.entrySet()) {
            String[] key = row.getKey().split("::", 2);
            for (Map.Entry<ENGINE, String[]> cell : row.getValue().entrySet()) {
                sb.append(key[0]).append(',').append(key[1]).append(',').append(cell.getKey().name());
                for (String value : cell.getValue()) {
                    sb.append(',').append(value);
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** One table per figure, a row per benchmark and a column per engine. */
    public String toConsoleTable() {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < COLUMNS.length; c++) {
            sb.append("\n== ").append(COLUMNS[c]).append(" ==\n");
            sb.append(String.format("%-40s", "benchmark"));
            for (ENGINE engine : engines) {
                sb.append(String.format(" %32s", engine.name()));
            }
            sb.append('\n');
            for (Map.Entry<String, Map<ENGINE, String[]>> row : rows.entrySet()) {
                sb.append(String.format("%-40s", row.getKey()));
                for (ENGINE engine : engines) {
                    String[] values = row.getValue().get(engine);
                    String shown = values == null ? "" : "OK".equals(values[0]) ? values[1 + c] : values[0];
                    sb.append(String.format(" %32s", shown));
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
