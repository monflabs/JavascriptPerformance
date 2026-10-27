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
package org.monflabs.nashorn.performance.robustness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.monflabs.nashorn.performance.ScriptExecutor.ENGINE;

/**
 * Entry point of the robustness harnesses:
 * {@code java -cp javascript-performance-*-all.jar org.monflabs.nashorn.performance.robustness.Robustness <command> [options]}.
 *
 * <pre>
 *   corpus   run a corpus of test scripts on every engine and compare with the reference
 *            --corpus=jsc-stress|dir      (default: jsc-stress)
 *            --root=path                  (default for jsc-stress: corpora/webkit/JSTests/stress)
 *            --filter=text                only the files whose path contains it
 *   fuzz     generate programs, run them on every engine, compare their output with the
 *            reference, and reduce each difference to a small program
 *            --count=N                    (default: 500) programs
 *            --seed=N                     (default: the time) the first program's seed
 *            --reduce=true|false          (default: true)
 *            --max-reduce=N               (default: 10) reduced programs per engine
 *   generate only write the programs fuzz would run, into --out
 *
 *   common:  --engines=A,B,...            (default: V8_JAVET, RHINO_MONFLABS_COMPILED,
 *                                         RHINO_MONFLABS_INTERPRETED, NASHORN_MONFLABS)
 *            --reference=ENGINE|none      (default: V8_JAVET)
 *            --jobs=N                     (default: half the cores) child JVMs at a time
 *            --shard=N                    (default: 100) files per child JVM
 *            --timeout=MS                 (default: 20000 for a corpus, 10000 for fuzz)
 *            --out=dir                    (default: target/robustness/&lt;corpus&gt;)
 *            --baseline=file              fail on a finding that is not listed in it
 *            --write-baseline=file        list the run's findings in it
 * </pre>
 */
public final class Robustness {

    static final List<ENGINE> DEFAULT_ENGINES = List.of(ENGINE.V8_JAVET,
            ENGINE.RHINO_MONFLABS_COMPILED, ENGINE.RHINO_MONFLABS_INTERPRETED, ENGINE.NASHORN_MONFLABS);

    private Robustness() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: Robustness corpus|fuzz [--option=value ...]");
            System.exit(2);
        }
        String command = args[0];
        // As in the children: a compiler bug that makes the Rhino fork fall back to its
        // interpreter is a crash, also when the reducer reproduces it in this JVM
        System.setProperty("rhino.failOnCompileFallback", "true");
        Map<String, String> options = parseArgs(Arrays.copyOfRange(args, 1, args.length));
        switch (command) {
            case "child" -> Child.run(ENGINE.valueOf(options.get("engine")),
                    Corpus.create(options.get("corpus"), Path.of(options.get("root"))),
                    Path.of(options.get("list")), Integer.parseInt(options.get("from")),
                    Integer.parseInt(options.get("to")), Long.parseLong(options.get("timeout")));
            case "corpus" -> System.exit(corpus(options));
            case "fuzz" -> System.exit(Fuzz.run(options));
            case "generate" -> Fuzz.generate(options);
            default -> {
                System.err.println("unknown command: " + command);
                System.exit(2);
            }
        }
    }

    private static int corpus(Map<String, String> options) throws IOException, InterruptedException {
        String spec = options.getOrDefault("corpus", "jsc-stress");
        String defaultRoot = spec.equals("jsc-stress") ? "corpora/webkit/JSTests/stress" : null;
        String root = options.getOrDefault("root", defaultRoot);
        if (root == null || !Files.isDirectory(Path.of(root))) {
            System.err.println("corpus directory not found: " + root
                    + (spec.equals("jsc-stress") ? " (run tools/robustness/fetch-corpora.sh)" : ""));
            return 2;
        }
        Path out = Path.of(options.getOrDefault("out", "target/robustness/" + spec));
        CorpusRun run = newRun(spec, Path.of(root), options, 20_000, out);
        run.run(options.get("filter"));
        return report(run, options, out);
    }

    static CorpusRun newRun(String spec, Path root, Map<String, String> options, long defaultTimeout,
            Path out) {
        int cores = Runtime.getRuntime().availableProcessors();
        return new CorpusRun(spec, root, engines(options),
                Integer.parseInt(options.getOrDefault("jobs", Integer.toString(Math.max(1, cores / 2)))),
                Integer.parseInt(options.getOrDefault("shard", "100")),
                Long.parseLong(options.getOrDefault("timeout", Long.toString(defaultTimeout))), out);
    }

    static List<ENGINE> engines(Map<String, String> options) {
        String value = options.get("engines");
        if (value == null || value.isBlank()) {
            return DEFAULT_ENGINES;
        }
        List<ENGINE> engines = new ArrayList<>();
        for (String token : value.split(",")) {
            engines.add(ENGINE.valueOf(token.trim()));
        }
        ENGINE reference = reference(options);
        if (reference != null && !engines.contains(reference)) {
            engines.add(0, reference);
        }
        return engines;
    }

    static ENGINE reference(Map<String, String> options) {
        String value = options.getOrDefault("reference", "V8_JAVET");
        return value.equalsIgnoreCase("none") ? null : ENGINE.valueOf(value);
    }

    /** Writes the report; with a baseline, returns 1 when a counted finding is not in it. */
    static int report(CorpusRun run, Map<String, String> options, Path out) throws IOException {
        Analysis analysis = new Analysis(run, engines(options), reference(options));
        Set<String> baseline = null;
        if (options.containsKey("baseline")) {
            Path file = Path.of(options.get("baseline"));
            baseline = new HashSet<>(Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of());
        }
        analysis.write(out, baseline);
        if (options.containsKey("write-baseline")) {
            Path file = Path.of(options.get("write-baseline"));
            if (file.toAbsolutePath().getParent() != null) {
                Files.createDirectories(file.toAbsolutePath().getParent());
            }
            Files.write(file, analysis.baselineKeys(), StandardCharsets.UTF_8);
        }
        System.out.println();
        System.out.print(analysis.consoleSummary());
        System.out.println("Report written to " + out.resolve("report.md").toAbsolutePath());
        if (baseline != null) {
            Set<String> known = baseline;
            long fresh = analysis.findings().stream().filter(analysis::counts)
                    .filter(f -> !known.contains(f.key())).count();
            System.out.println(fresh + " findings not in the baseline");
            return fresh == 0 ? 0 : 1;
        }
        return 0;
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unrecognized argument: " + arg);
            }
            int eq = arg.indexOf('=');
            if (eq < 0) {
                options.put(arg.substring(2), "true");
            } else {
                options.put(arg.substring(2, eq), arg.substring(eq + 1));
            }
        }
        return options;
    }
}
