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
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.monflabs.nashorn.performance.BenchmarkCollector.Status;
import org.monflabs.nashorn.performance.ScriptExecutor.ENGINE;
import org.monflabs.nashorn.performance.galtajs.GaltaJSCompiledExecutor;
import org.monflabs.nashorn.performance.galtajs.GaltaJSExecutor;
import org.monflabs.nashorn.performance.graaljs.GraalJSExecutor;
import org.monflabs.nashorn.performance.javet.JavetExecutor;
import org.monflabs.nashorn.performance.nashorn.MonflabsNashornExecutor;
import org.monflabs.nashorn.performance.nashorn.OpenjdkNashornExecutor;
import org.monflabs.nashorn.performance.rhino.RhinoExecutor;

/**
 * Walks a vendored benchmark suite (a folder of scripts under {@code /benchmarks} on the
 * classpath) and runs each file, once per engine, timing execution only - {@code base.js}/
 * {@code run.js}/multi-part companions are concatenated with the benchmark and compiled once,
 * outside the timed loop.
 */
public class BenchmarkRunner {

    private static final int DEFAULT_WARMUP = 2;
    private static final int DEFAULT_ITERATIONS = 5;

    public static final ENGINE[] ALL_ENGINES = ENGINE.values();

    /**
     * Every engine that produces real JVM bytecode (or a native binary, for V8) rather than
     * walking an AST - what the CLI's {@code COMPILED} pseudo-option, and its default when
     * {@code --engines} is unset, resolve to.
     */
    public static final ENGINE[] COMPILED_ENGINES = {
            ENGINE.NASHORN_MONFLABS, ENGINE.NASHORN_OPENJDK, ENGINE.GALTAJS_COMPILED,
            ENGINE.RHINO_COMPILED, ENGINE.GRAALJS_COMPILED, ENGINE.V8_JAVET};

    /** Every interpreted-mode engine, paired after {@link #COMPILED_ENGINES} for the CLI's {@code ALL}
     * pseudo-option. */
    public static final ENGINE[] INTERPRETED_ENGINES = {
            ENGINE.RHINO_INTERPRETED, ENGINE.GRAALJS_INTERPRETED, ENGINE.GALTAJS_INTERPRETED};

    /** What the CLI's {@code NASHORN} pseudo-option resolves to. */
    public static final ENGINE[] NASHORN_ENGINES = {ENGINE.NASHORN_MONFLABS, ENGINE.NASHORN_OPENJDK};

    private static final Map<String, FileSystem> JAR_FILESYSTEMS = new ConcurrentHashMap<>();

    private final BenchmarkCollector collector = new BenchmarkCollector();
    private final ENGINE[] engines;

    private int warmupIterations = DEFAULT_WARMUP;
    private int runIterations = DEFAULT_ITERATIONS;

    public BenchmarkRunner() {
        this(ALL_ENGINES);
    }

    public BenchmarkRunner(ENGINE... engines) {
        this.engines = engines;
    }

    public ENGINE[] getEngines() {
        return engines;
    }

    public BenchmarkCollector getCollector() {
        return collector;
    }

    public int getWarmupIterations() {
        return warmupIterations;
    }

    public void setWarmupIterations(int warmupIterations) {
        this.warmupIterations = warmupIterations;
    }

    public int getRunIterations() {
        return runIterations;
    }

    public void setRunIterations(int runIterations) {
        this.runIterations = runIterations;
    }

    public static ScriptExecutor createEngine(ENGINE engine) {
        return switch (engine) {
            case NASHORN_MONFLABS -> new MonflabsNashornExecutor();
            case NASHORN_OPENJDK -> new OpenjdkNashornExecutor();
            case RHINO_INTERPRETED -> new RhinoExecutor(false);
            case RHINO_COMPILED -> new RhinoExecutor(true);
            case GRAALJS_INTERPRETED -> new GraalJSExecutor(false);
            case GRAALJS_COMPILED -> new GraalJSExecutor(true);
            case V8_JAVET -> new JavetExecutor();
            case GALTAJS_INTERPRETED -> new GaltaJSExecutor();
            case GALTAJS_COMPILED -> new GaltaJSCompiledExecutor();
        };
    }

    /** Resolves a vendored suite folder (e.g. {@code "octane-master"}) on the classpath, whether
     *  exploded (tests, IDE) or packed inside the shaded jar. */
    static Path resolveSuiteDir(String suite) throws IOException {
        URL url = BenchmarkRunner.class.getResource("/benchmarks/" + suite);
        if (url == null) {
            throw new IllegalArgumentException("Unknown benchmark suite: " + suite);
        }
        try {
            URI uri = url.toURI();
            if (!"jar".equals(uri.getScheme())) {
                return Path.of(uri);
            }
            String jarUri = uri.toString();
            int bang = jarUri.indexOf("!/");
            String fsKey = jarUri.substring(0, bang);
            String innerPath = jarUri.substring(bang + 1);
            FileSystem fs = JAR_FILESYSTEMS.computeIfAbsent(fsKey, key -> {
                try {
                    return FileSystems.newFileSystem(URI.create(key), Map.of());
                } catch (FileSystemAlreadyExistsException already) {
                    return FileSystems.getFileSystem(URI.create(key));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            return fs.getPath(innerPath);
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

    public void runSuite(String suite) throws IOException {
        runSuite(suite, null);
    }

    public void runSuite(String suite, Predicate<Path> filter) throws IOException {
        Path folder = resolveSuiteDir(suite);
        List<Path> allFiles;
        try (Stream<Path> stream = Files.walk(folder, 1)) {
            allFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".js"))
                    .filter(f -> !"base.js".equals(f.getFileName().toString()))
                    .filter(f -> !"run.js".equals(f.getFileName().toString()))
                    .sorted()
                    .collect(Collectors.toList());
        }

        Map<Path, List<Path>> groups = groupMultiPartFiles(allFiles);
        for (Map.Entry<Path, List<Path>> entry : groups.entrySet()) {
            Path mainFile = entry.getKey();
            if (filter == null || filter.test(mainFile)) {
                runFile(suite, folder, mainFile, entry.getValue());
            }
        }
    }

    private static String getBaseName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    /**
     * ubench's loop-empty.js, loop-empty-resolve.js and loop-sum.js are three independent
     * micro-benchmarks that happen to share a "loop-" prefix - unlike octane's genuine
     * multi-part companions (typescript.js/typescript-compiler.js/typescript-input.js,
     * zlib.js/zlib-data.js), where the prefix match below is exactly what is needed. Without
     * this exclusion, loop-empty-resolve.js gets merged into loop-empty.js's run (matching
     * "loop-empty" + "-resolve"), and once that merge is suppressed, loop-empty.js and
     * loop-sum.js would instead merge with each other (both reduce to prefix "loop" in the
     * second pass below) - so all three names are excluded from both passes.
     *
     * Every SunSpider file is independent too - its hyphens are just category separators
     * ("3d-cube"/"3d-morph"/"3d-raytrace", "access-fannkuch"/"access-nbody"/"access-nsieve",
     * "crypto-aes"/"crypto-md5"/"crypto-sha1", "date-format-tofte"/"date-format-xparb",
     * "string-base64"/"string-fasta"/"string-tagcloud"), never a multi-part split - so the
     * whole suite is excluded from both passes rather than picking out the specific names that
     * happen to collide today.
     */
    private static final Set<String> NEVER_GROUPED = Set.of(
            "loop-empty", "loop-empty-resolve", "loop-sum",
            "3d-cube", "3d-morph", "3d-raytrace",
            "access-binary-trees", "access-fannkuch", "access-nbody", "access-nsieve",
            "bitops-3bit-bits-in-byte", "bitops-bits-in-byte", "bitops-bitwise-and", "bitops-nsieve-bits",
            "controlflow-recursive",
            "crypto-aes", "crypto-md5", "crypto-sha1",
            "date-format-tofte", "date-format-xparb",
            "math-cordic", "math-partial-sums", "math-spectral-norm",
            "regexp-dna",
            "string-base64", "string-fasta", "string-tagcloud", "string-unpack-code", "string-validate-input");

    /** Groups a multi-part benchmark (e.g. {@code gbemu-part1.js}/{@code gbemu-part2.js}) under
     *  its first file, so the parts are concatenated and run as a single benchmark. */
    static Map<Path, List<Path>> groupMultiPartFiles(List<Path> files) {
        Map<Path, List<Path>> groups = new LinkedHashMap<>();
        List<Path> consumed = new ArrayList<>();

        for (Path file : files) {
            if (consumed.contains(file)) continue;
            String name = getBaseName(file);

            List<Path> companions = new ArrayList<>();
            for (Path other : files) {
                if (other.equals(file) || consumed.contains(other)) continue;
                String otherName = getBaseName(other);
                if (NEVER_GROUPED.contains(otherName)) continue;
                if (otherName.startsWith(name + "-")) {
                    companions.add(other);
                }
            }
            if (!companions.isEmpty()) {
                groups.put(file, companions);
                consumed.addAll(companions);
            }
        }

        List<Path> remaining = new ArrayList<>();
        for (Path file : files) {
            if (!groups.containsKey(file) && !consumed.contains(file)) {
                remaining.add(file);
            }
        }
        for (int i = 0; i < remaining.size(); i++) {
            Path file = remaining.get(i);
            if (consumed.contains(file)) continue;
            String name = getBaseName(file);
            int dash = name.lastIndexOf('-');
            if (dash > 0 && !NEVER_GROUPED.contains(name)) {
                String prefix = name.substring(0, dash);
                List<Path> peers = new ArrayList<>();
                peers.add(file);
                for (int j = i + 1; j < remaining.size(); j++) {
                    Path other = remaining.get(j);
                    if (consumed.contains(other)) continue;
                    String otherName = getBaseName(other);
                    if (NEVER_GROUPED.contains(otherName)) continue;
                    int otherDash = otherName.lastIndexOf('-');
                    if (otherDash > 0 && otherName.substring(0, otherDash).equals(prefix)) {
                        peers.add(other);
                    }
                }
                if (peers.size() > 1) {
                    Path main = peers.get(0);
                    List<Path> companionPeers = new ArrayList<>(peers.subList(1, peers.size()));
                    groups.put(main, companionPeers);
                    consumed.addAll(companionPeers);
                    continue;
                }
            }
            groups.put(file, List.of());
        }

        return groups;
    }

    private static String readString(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private void runFile(String suite, Path folder, Path file, List<Path> companionFiles) throws IOException {
        String fileName = file.getFileName().toString();
        System.out.println("-----------------------------------------------------");
        System.out.println(suite + " :: " + fileName);

        for (ENGINE engine : engines) {
            System.out.println("START " + engine.name());
            ScriptExecutor ex = createEngine(engine);
            if (!ex.isSupported()) {
                collector.addResult(suite, fileName, engine, Status.NOT_AVAILABLE, 0, 0);
                System.out.println("    " + engine.name() + " *** N/A (not available on this JVM) ***");
                System.out.println("END " + engine.name());
                continue;
            }

            try {
                StringBuilder sb = new StringBuilder();
                Path baseJs = folder.resolve("base.js");
                boolean hasBaseJs = Files.exists(baseJs);
                if (hasBaseJs) {
                    sb.append(readString(baseJs)).append('\n');
                }

                StringBuilder body = new StringBuilder();
                for (Path companion : companionFiles) {
                    body.append(readString(companion)).append('\n');
                }
                body.append(readString(file));

                Path runJs = folder.resolve("run.js");
                boolean hasRunJs = Files.exists(runJs);
                if (!hasBaseJs && hasRunJs) {
                    // A suite with a run.js but no base.js (e.g. SunSpider) has no shared
                    // Octane/v8-benchmarks-v6-style framework of its own to drive repeated
                    // execution, so the benchmark body is wrapped in a callable function instead
                    // of running inline, letting run.js call it in its own repeat loop. Each call
                    // gets fresh local scope, so repeating it is safe even for a benchmark that
                    // mutates what would otherwise be shared top-level state. __benchmarkFile__
                    // lets such a run.js look up a per-file repeat count (see
                    // sunspider-1.0.2/run.js) - individual files vary too widely in per-run cost
                    // for one repeat count to suit all of them.
                    sb.append("function __benchmarkBody__() {\n").append(body).append("\n}\n");
                    sb.append("var __benchmarkFile__ = \"").append(fileName).append("\";\n");
                } else {
                    sb.append(body);
                }

                if (hasRunJs) {
                    sb.append('\n').append(readString(runJs));
                }

                String script = sb.toString();
                ex.init(script, file.toString());
                try {
                    PerformanceWatch watch = new PerformanceWatch(fileName);
                    watch.runWithException(ex::run, runIterations, warmupIterations);
                    long wallMs = watch.getTotalWallTime() / PerformanceWatch.NANOSECONDS_PER_MILLI;
                    long cpuMs = watch.getTotalCpuTime() / PerformanceWatch.NANOSECONDS_PER_MILLI;
                    collector.addResult(suite, fileName, engine, Status.OK, wallMs, cpuMs);
                    System.out.println("    " + engine.name() + ", " + wallMs + "ms");
                } finally {
                    ex.terminate();
                }
            } catch (Throwable t) {
                collector.addResult(suite, fileName, engine, Status.FAILED, 0, 0);
                System.out.println("    " + engine.name() + " *** FAILED ***, " + t);
            } finally {
                System.out.println("END " + engine.name());
            }
        }
        System.out.println("-----------------------------------------------------");
    }
}
