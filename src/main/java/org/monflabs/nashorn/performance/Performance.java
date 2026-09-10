/*
 * Copyright (c) 2026, Philippe Riand. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Philippe Riand designates this
 * particular file as subject to the "Classpath" exception as provided
 * in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.monflabs.nashorn.performance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.monflabs.nashorn.performance.ScriptExecutor.ENGINE;

/**
 * Entry point: {@code java -jar nashorn-performance-*-all.jar [options]}.
 *
 * <pre>
 *   --suites=octane,sunspider,ubench,v8-benchmarks   (default: all four)
 *   --engines=NASHORN_MONFLABS,NASHORN_OPENJDK,...    (default: COMPILED)
 *                                                      also accepts, in place of or mixed with
 *                                                      individual ENGINE names: COMPILED (both
 *                                                      Nashorns, GaltaJS compiled, Rhino compiled,
 *                                                      GraalJS compiled, V8), ALL (every engine,
 *                                                      compiled group first, interpreted group
 *                                                      next), NASHORN (both Nashorns only)
 *   --octane-benchmarks=box2d,crypto,...              (default: an 11-file subset)
 *   --warmup=N                                        (default: 2)
 *   --iterations=N                                    (default: 5)
 *   --report=path/to/report.csv                       (default: target/performance-report.csv)
 *   --html-report=path/to/report.html                 (default: target/performance-report.html)
 * </pre>
 */
public final class Performance {

    private static final Map<String, String> SUITE_RESOURCE_FOLDER = Map.of(
            "octane", "octane-master",
            "sunspider", "sunspider-1.0.2",
            "ubench", "ubench",
            "v8-benchmarks", "v8-benchmarks-v6");

    private static final List<String> DEFAULT_SUITES =
            List.of("octane", "sunspider", "ubench", "v8-benchmarks");

    // Pseudo-values a --engines token may name instead of (or alongside) an individual
    // ScriptExecutor.ENGINE - resolved case-insensitively in resolveEngines(). ALL is compiled
    // engines first, then interpreted engines, rather than ENGINE.values()' declaration order.
    private static final Map<String, ENGINE[]> ENGINE_GROUPS = Map.of(
            "COMPILED", BenchmarkRunner.COMPILED_ENGINES,
            "ALL", concat(BenchmarkRunner.COMPILED_ENGINES, BenchmarkRunner.INTERPRETED_ENGINES),
            "NASHORN", BenchmarkRunner.NASHORN_ENGINES);

    // The default when --engines is absent or blank.
    private static final List<ENGINE> DEFAULT_ENGINES = List.of(BenchmarkRunner.COMPILED_ENGINES);

    // Mirrors core/pom.xml's own octane.benchmarks default: code-load, typescript and zlib are
    // excluded there too (typescript/typescript-compiler/typescript-input and zlib/zlib-data are
    // the largest files in the suite and add little beyond what the rest already exercises).
    private static final List<String> DEFAULT_OCTANE_BENCHMARKS = List.of(
            "box2d", "crypto", "deltablue", "earley-boyer", "gbemu", "navier-stokes",
            "mandreel", "pdfjs", "raytrace", "regexp", "richards", "splay");

    private Performance() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = parseArgs(args);

        List<String> suites = splitOr(options.get("suites"), DEFAULT_SUITES);
        List<ENGINE> engines = resolveEngines(splitOr(options.get("engines"), null));
        List<String> octaneBenchmarks = splitOr(options.get("octane-benchmarks"), DEFAULT_OCTANE_BENCHMARKS);
        int warmup = Integer.parseInt(options.getOrDefault("warmup", "2"));
        int iterations = Integer.parseInt(options.getOrDefault("iterations", "5"));
        Path report = Path.of(options.getOrDefault("report", "target/performance-report.csv"));
        Path htmlReport = Path.of(options.getOrDefault("html-report", "target/performance-report.html"));

        BenchmarkRunner runner = new BenchmarkRunner(engines.toArray(new ENGINE[0]));
        runner.setWarmupIterations(warmup);
        runner.setRunIterations(iterations);

        for (String suite : suites) {
            String folder = SUITE_RESOURCE_FOLDER.get(suite);
            if (folder == null) {
                throw new IllegalArgumentException("Unknown suite: " + suite
                        + " (known: " + SUITE_RESOURCE_FOLDER.keySet() + ")");
            }
            if ("octane".equals(suite)) {
                Set<String> selected = Set.copyOf(octaneBenchmarks);
                // A multi-part benchmark (e.g. gbemu-part1.js/gbemu-part2.js) is grouped and run
                // under its first part's name, so "gbemu" must also match "gbemu-part1".
                runner.runSuite(folder, file -> {
                    String name = baseName(file);
                    return selected.contains(name)
                            || selected.stream().anyMatch(s -> name.startsWith(s + "-"));
                });
            } else {
                runner.runSuite(folder);
            }
        }

        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report, runner.getCollector().csv(), StandardCharsets.UTF_8);
        Files.createDirectories(htmlReport.toAbsolutePath().getParent());
        Files.writeString(htmlReport, runner.getCollector().toHtmlReport(), StandardCharsets.UTF_8);

        System.out.println();
        System.out.println(runner.getCollector().toConsoleTable());
        System.out.println("Report written to " + report.toAbsolutePath());
        System.out.println("HTML report written to " + htmlReport.toAbsolutePath());
    }

    /**
     * Expands a {@code --engines} value's comma-separated tokens, each either an individual
     * {@link ENGINE} name or one of {@link #ENGINE_GROUPS}'s pseudo-group names (matched
     * case-insensitively), into the flat, order-preserving, possibly-repeating list of engines to
     * run. {@code tokens == null} (option absent/blank) resolves to {@link #DEFAULT_ENGINES}.
     */
    private static List<ENGINE> resolveEngines(List<String> tokens) {
        if (tokens == null) {
            return DEFAULT_ENGINES;
        }
        List<ENGINE> engines = new ArrayList<>();
        for (String token : tokens) {
            ENGINE[] group = ENGINE_GROUPS.get(token.toUpperCase());
            if (group != null) {
                engines.addAll(Arrays.asList(group));
            } else {
                engines.add(ENGINE.valueOf(token));
            }
        }
        return engines;
    }

    private static ENGINE[] concat(ENGINE[] first, ENGINE[] second) {
        ENGINE[] result = new ENGINE[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private static List<String> splitOr(String value, List<String> defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unrecognized argument: " + arg);
            }
            int eq = arg.indexOf('=');
            if (eq < 0) {
                options.put(arg.substring(2), "");
            } else {
                options.put(arg.substring(2, eq), arg.substring(eq + 1));
            }
        }
        return options;
    }
}
