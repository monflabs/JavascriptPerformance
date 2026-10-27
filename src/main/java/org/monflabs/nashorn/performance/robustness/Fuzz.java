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
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.monflabs.nashorn.performance.ScriptExecutor.ENGINE;

/**
 * Differential fuzzing: generates programs ({@link ProgramGenerator}), runs them on every engine
 * as a corpus judged {@link Corpus.Judging#EXACT}, and reduces each difference with the
 * reference to a small program ({@link Reducer}).
 */
final class Fuzz {

    /** Makes a reduced program runnable as it is in a shell that has no {@code print}, like Node. */
    private static final String PRINT_FALLBACK =
            "var print = typeof print === 'function' ? print : console.log.bind(console);\n";

    private Fuzz() {
    }

    static int run(Map<String, String> options) throws IOException, InterruptedException {
        int count = Integer.parseInt(options.getOrDefault("count", "500"));
        long seed = Long.parseLong(options.getOrDefault("seed",
                Long.toString(System.currentTimeMillis() % 1_000_000_000L)));
        Path out = Path.of(options.getOrDefault("out", "target/robustness/fuzz"));
        Path programs = out.resolve("programs");
        Path reducedDir = out.resolve("reduced");
        for (Path dir : List.of(programs, reducedDir)) {
            clean(dir);
        }
        for (int i = 0; i < count; i++) {
            long s = seed + i;
            Files.writeString(programs.resolve(String.format("seed-%010d.js", s)),
                    new ProgramGenerator(s).generate(), StandardCharsets.UTF_8);
        }
        System.out.println("Generated " + count + " programs from seed " + seed + " in " + programs);

        long timeout = Long.parseLong(options.getOrDefault("timeout", "10000"));
        CorpusRun run = Robustness.newRun("fuzz", programs, options, timeout, out);
        run.run(null);
        int status = Robustness.report(run, options, out);

        if (Boolean.parseBoolean(options.getOrDefault("reduce", "true"))) {
            reduceAll(run, options, out, reducedDir, timeout);
        }
        return status;
    }

    /** Writes the programs of a fuzz run without running them. */
    static void generate(Map<String, String> options) throws IOException {
        int count = Integer.parseInt(options.getOrDefault("count", "500"));
        long seed = Long.parseLong(options.getOrDefault("seed", "0"));
        Path out = Path.of(options.getOrDefault("out", "target/robustness/programs"));
        clean(out);
        for (int i = 0; i < count; i++) {
            Files.writeString(out.resolve(String.format("seed-%010d.js", seed + i)),
                    new ProgramGenerator(seed + i).generate(), StandardCharsets.UTF_8);
        }
    }

    private static void clean(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        Files.createDirectories(dir);
    }

    private static void reduceAll(CorpusRun run, Map<String, String> options, Path out, Path reducedDir,
            long timeout) throws IOException, InterruptedException {
        ENGINE reference = Robustness.reference(options);
        Analysis analysis = new Analysis(run, Robustness.engines(options), reference);
        int perEngine = Integer.parseInt(options.getOrDefault("max-reduce", "10"));
        Map<ENGINE, Integer> reducedPerEngine = new HashMap<>();
        Set<String> seen = new HashSet<>();
        List<String> sections = new ArrayList<>();
        Map<String, Reducer> reducers = new HashMap<>();
        for (Analysis.Finding f : analysis.findings()) {
            // A JVM that died cannot be reproduced in this one
            if (!analysis.counts(f) || f.detail().startsWith("JVM exited")
                    || reducedPerEngine.getOrDefault(f.engine(), 0) >= perEngine) {
                continue;
            }
            boolean backends = f.category() == Analysis.Category.BACKENDS;
            ENGINE other = backends ? ENGINE.RHINO_MONFLABS_INTERPRETED : reference;
            if (other == null) {
                continue;
            }
            Reducer reducer = reducers.computeIfAbsent(f.engine() + "/" + other + "/" + backends,
                    k -> new Reducer(f.engine(), other, backends, timeout));
            String program = Files.readString(run.corpus().root.resolve(f.file()), StandardCharsets.UTF_8);
            int before = reducer.runs();
            String reduced = reducer.reduce(program, f.category());
            Outcome o = reducer.runEngine(reduced);
            Outcome ref = reducer.runOther(reduced);
            String body = reduced.substring(reduced.indexOf(ProgramGenerator.HELPERS)
                    + ProgramGenerator.HELPERS.length());
            // Two findings that reduce to the same program are the same bug
            if (!seen.add(f.engine() + "\n" + body)) {
                continue;
            }
            reducedPerEngine.merge(f.engine(), 1, Integer::sum);
            String name = f.engine().name().toLowerCase(Locale.ROOT) + "-"
                    + f.category().name().toLowerCase(Locale.ROOT) + "-" + f.file();
            String header = "// " + f.category() + " on " + f.engine() + " against " + other + ", reduced from "
                    + f.file() + " in " + (reducer.runs() - before) + " runs\n"
                    + "// " + other + ": " + describe(ref).replace("\n", "\n//   ") + "\n"
                    + "// " + f.engine() + ": " + describe(o).replace("\n", "\n//   ") + "\n";
            Files.writeString(reducedDir.resolve(name), header + PRINT_FALLBACK + reduced, StandardCharsets.UTF_8);
            sections.add("### " + f.category() + " · " + f.engine() + " · `reduced/" + name + "`\n\n"
                    + "```js\n" + body.replace(ProgramGenerator.CHUNK + "\n", "").trim() + "\n```\n\n"
                    + "- " + other + ": `" + oneLine(describe(ref)) + "`\n"
                    + "- " + f.engine() + ": `" + oneLine(describe(o)) + "`\n");
            System.out.println("  reduced " + f.file() + " for " + f.engine() + " (" + f.category() + ")");
        }
        reducers.values().forEach(Reducer::close);
        if (!sections.isEmpty()) {
            String md = "\n## Reduced programs\n\nEach one is a generated program cut down to the chunks and"
                    + " declarations the difference needs. The helpers (`__p`, `__show`, ...) are in the file,"
                    + " not shown here.\n\n" + String.join("\n", sections);
            Files.writeString(out.resolve("report.md"), md, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
        System.out.println(sections.size() + " reduced programs in " + reducedDir.toAbsolutePath());
    }

    private static String describe(Outcome o) {
        String output = o.output().length() > 600 ? o.output().substring(0, 600) + "..." : o.output();
        return switch (o.kind()) {
            case OK -> "prints " + output;
            case THROW -> "throws " + Analysis.firstLine(o.error())
                    + (output.isEmpty() ? "" : " after printing " + output);
            default -> o.kind() + " " + Analysis.firstLine(o.error());
        };
    }

    private static String oneLine(String s) {
        return s.replace("\n", " ⏎ ").replace("`", "'");
    }
}
