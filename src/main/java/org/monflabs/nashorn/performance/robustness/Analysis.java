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
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.monflabs.nashorn.performance.ScriptExecutor.ENGINE;

/**
 * Compares each engine's outcomes with the reference engine's and with each other's, and writes
 * the report.
 */
final class Analysis {

    /** What a difference means. The first five are findings; the others are reported only. */
    enum Category {
        /** A Java exception escaped the engine, or its JVM died. Whatever the reference did. */
        CRASH(true, "Java exception or JVM death in the engine"),
        /**
         * A stack or heap limit reached as a Java {@code StackOverflowError} or
         * {@code OutOfMemoryError}, which a script cannot catch (and which can kill the JVM),
         * where the reference throws a catchable {@code RangeError} or completes.
         */
        LIMIT(true, "stack or heap exhausted as a Java error, not a catchable JavaScript one"),
        /** The reference ran the script; the engine threw. */
        FAILS(true, "throws where the reference completes"),
        /** The Rhino fork's class compiler and interpreter disagree. */
        BACKENDS(true, "compiled and interpreted Rhino disagree"),
        /** Both completed and printed different things (a finding when judging EXACT). */
        OUTPUT(true, "prints something else than the reference"),
        /** Both threw, different errors (a finding when judging EXACT). */
        OTHER_ERROR(true, "throws another error than the reference"),
        /** The reference threw, the engine completed (a finding when judging EXACT). */
        ACCEPTS(true, "completes where the reference throws"),
        /**
         * The engine threw where the reference completed, but what failed is a check of an error
         * message's wording (the message names a second error, as in {@code "bad error: TypeError:
         * ..."}): engines word their messages as they like, and the reference only happens to word
         * this one as the test's author did.
         */
        MESSAGE(false, "fails a check of an error message's wording"),
        /** The engine did not finish in time where the reference did. Often just slowness. */
        TIMEOUT(false, "times out where the reference completes");

        final boolean finding;
        final String description;

        Category(boolean finding, String description) {
            this.finding = finding;
            this.description = description;
        }
    }

    record Finding(Category category, ENGINE engine, String file, String signature, String detail) {
        String key() {
            return category + "\t" + engine + "\t" + file;
        }
    }

    /** An error message quoting another error: {@code Error: bad error: RangeError: ...}. */
    private static final java.util.regex.Pattern NESTED_ERROR =
            java.util.regex.Pattern.compile("Error\\b.*\\b[A-Z][A-Za-z]*Error(:|\\b.*\\bmessage)");

    private final CorpusRun run;
    private final ENGINE reference;
    private final List<ENGINE> engines;
    private final List<Finding> findings = new ArrayList<>();
    private final Map<ENGINE, Map<Outcome.Kind, Integer>> counts = new EnumMap<>(ENGINE.class);
    private final Map<String, Integer> skipReasons = new TreeMap<>();
    private int skipped;

    Analysis(CorpusRun run, List<ENGINE> engines, ENGINE reference) {
        this.run = run;
        this.engines = engines;
        this.reference = reference;
        analyze();
    }

    List<Finding> findings() {
        return findings;
    }

    private boolean exact() {
        return run.corpus().judging() == Corpus.Judging.EXACT;
    }

    private void analyze() {
        List<String> files = run.files();
        for (int i = 0; i < files.size(); i++) {
            String file = files.get(i);
            Map<ENGINE, Outcome> byEngine = run.outcomesOf(i);
            Outcome any = byEngine.values().stream().findFirst().orElse(null);
            if (any != null && any.kind() == Outcome.Kind.SKIPPED) {
                skipped++;
                skipReasons.merge(any.error(), 1, Integer::sum);
                continue;
            }
            for (Map.Entry<ENGINE, Outcome> e : byEngine.entrySet()) {
                counts.computeIfAbsent(e.getKey(), k -> new EnumMap<>(Outcome.Kind.class))
                        .merge(e.getValue().kind(), 1, Integer::sum);
            }
            Outcome ref = byEngine.get(reference);
            for (ENGINE engine : engines) {
                if (engine == reference) {
                    continue;
                }
                Outcome o = byEngine.get(engine);
                if (o != null) {
                    compare(engine, file, o, ref);
                }
            }
            Outcome compiled = byEngine.get(ENGINE.RHINO_MONFLABS_COMPILED);
            Outcome interpreted = byEngine.get(ENGINE.RHINO_MONFLABS_INTERPRETED);
            if (compiled != null && interpreted != null && !sameResult(compiled, interpreted)
                    && compiled.kind() != Outcome.Kind.TIMEOUT && interpreted.kind() != Outcome.Kind.TIMEOUT
                    && compiled.kind() != Outcome.Kind.CRASH && interpreted.kind() != Outcome.Kind.CRASH
                    && (compiled.kind() != Outcome.Kind.THROW || interpreted.kind() != Outcome.Kind.THROW
                        || !compiled.signature().equals(interpreted.signature()))) {
                findings.add(new Finding(Category.BACKENDS, ENGINE.RHINO_MONFLABS_COMPILED, file,
                        summary(compiled) + " / interpreted " + summary(interpreted),
                        "compiled:\n" + detail(compiled) + "\ninterpreted:\n" + detail(interpreted)));
            }
        }
    }

    private void compare(ENGINE engine, String file, Outcome o, Outcome ref) {
        Category category = classify(o, ref);
        if (category == null) {
            return;
        }
        if (category == Category.CRASH || category == Category.LIMIT) {
            findings.add(new Finding(category, engine, file, o.signature(), o.error()));
            return;
        }
        String signature = switch (category) {
            case OUTPUT -> "output";
            case ACCEPTS -> "reference: " + ref.signature();
            case OTHER_ERROR -> o.signature() + " (reference " + ref.signature() + ")";
            default -> o.signature();
        };
        String detail = switch (category) {
            case OUTPUT -> firstDifference(o.output(), ref.output());
            case ACCEPTS -> "reference: " + firstLine(ref.error());
            default -> o.error();
        };
        findings.add(new Finding(category, engine, file, signature, detail));
    }

    /**
     * How an engine's outcome differs from the reference's, or {@code null} when it does not, or
     * when the reference has no result to compare with.
     */
    static Category classify(Outcome o, Outcome ref) {
        if (o.kind() == Outcome.Kind.CRASH) {
            String sig = o.signature();
            return sig.contains("StackOverflowError") || sig.contains("OutOfMemoryError")
                    ? Category.LIMIT : Category.CRASH;
        }
        if (ref == null || ref.kind() == Outcome.Kind.CRASH || ref.kind() == Outcome.Kind.TIMEOUT) {
            return null;
        }
        return switch (o.kind()) {
            case TIMEOUT -> Category.TIMEOUT;
            case THROW -> ref.kind() == Outcome.Kind.OK
                    ? NESTED_ERROR.matcher(o.error()).find() ? Category.MESSAGE : Category.FAILS
                    : o.signature().equals(ref.signature()) ? null : Category.OTHER_ERROR;
            case OK -> ref.kind() == Outcome.Kind.THROW ? Category.ACCEPTS
                    : normalize(o.output()).equals(normalize(ref.output())) ? null : Category.OUTPUT;
            default -> null;
        };
    }

    /** A finding that counts: always for crashes and failures, and for every difference when EXACT. */
    boolean counts(Finding f) {
        return switch (f.category()) {
            case CRASH, LIMIT, FAILS, BACKENDS -> true;
            case OUTPUT, OTHER_ERROR, ACCEPTS -> exact();
            case MESSAGE, TIMEOUT -> false;
        };
    }

    static boolean sameResult(Outcome a, Outcome b) {
        return a.kind() == b.kind() && a.signature().equals(b.signature())
                && normalize(a.output()).equals(normalize(b.output()));
    }

    /** The source text of a built-in function, which the specification lets engines lay out. */
    private static final java.util.regex.Pattern NATIVE_CODE =
            java.util.regex.Pattern.compile("\\{\\s*\\[native code\\]\\s*\\}");

    /**
     * An output with what engines may legitimately print differently made uniform: the white
     * space of {@code function f() { [native code] }}, which a program prints when it converts a
     * built-in function to a string.
     */
    static String normalize(String output) {
        return output.indexOf("[native code]") < 0 ? output
                : NATIVE_CODE.matcher(output).replaceAll("{ [native code] }");
    }

    private static String summary(Outcome o) {
        return o.kind() == Outcome.Kind.OK ? "OK" : o.kind() + " " + o.signature();
    }

    private static String detail(Outcome o) {
        return o.kind() == Outcome.Kind.OK ? "output: " + abbreviate(o.output(), 400) : o.error();
    }

    static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String firstDifference(String actual, String expected) {
        String[] a = actual.split("\n", -1);
        String[] e = expected.split("\n", -1);
        for (int i = 0; i < Math.max(a.length, e.length); i++) {
            String al = i < a.length ? a[i] : "<none>";
            String el = i < e.length ? e[i] : "<none>";
            if (!al.equals(el)) {
                return "line " + (i + 1) + ": got " + abbreviate(al, 300) + " · expected " + abbreviate(el, 300);
            }
        }
        return "";
    }

    /** Writes report.md, findings.tsv and results.tsv into {@code dir}. */
    void write(Path dir, Set<String> baseline) throws IOException {
        Files.createDirectories(dir);
        StringBuilder md = new StringBuilder();
        md.append("# Robustness: ").append(run.corpus().name()).append("\n\n");
        md.append("Corpus `").append(run.corpus().root).append("`, ").append(run.files().size())
                .append(" files, ").append(skipped).append(" skipped. Reference engine: ")
                .append(reference == null ? "none" : reference).append(". Judging: ")
                .append(run.corpus().judging()).append(".\n\n");

        md.append("| Engine | OK | THROW | TIMEOUT | CRASH |");
        for (Category c : Category.values()) {
            md.append(' ').append(c.name().toLowerCase(java.util.Locale.ROOT)).append(" |");
        }
        md.append("\n|---|---:|---:|---:|---:|");
        md.append("---:|".repeat(Category.values().length)).append('\n');
        for (ENGINE engine : engines) {
            Map<Outcome.Kind, Integer> c = counts.getOrDefault(engine, Map.of());
            md.append("| ").append(engine).append(engine == reference ? " (reference)" : "").append(" | ")
                    .append(c.getOrDefault(Outcome.Kind.OK, 0)).append(" | ")
                    .append(c.getOrDefault(Outcome.Kind.THROW, 0)).append(" | ")
                    .append(c.getOrDefault(Outcome.Kind.TIMEOUT, 0)).append(" | ")
                    .append(c.getOrDefault(Outcome.Kind.CRASH, 0)).append(" |");
            for (Category cat : Category.values()) {
                long n = findings.stream().filter(f -> f.engine() == engine && f.category() == cat).count();
                md.append(' ').append(n == 0 ? "" : Long.toString(n)).append(" |");
            }
            md.append('\n');
        }
        md.append("\nThe first four columns count how the scripts ended; the others count the differences"
                + " with the reference, by category:\n\n");
        for (Category c : Category.values()) {
            md.append("- **").append(c).append("**: ").append(c.description)
                    .append(counts(new Finding(c, null, "", "", "")) ? "" : " (reported, not counted)")
                    .append('\n');
        }

        if (baseline != null) {
            List<Finding> fresh = findings.stream().filter(this::counts)
                    .filter(f -> !baseline.contains(f.key())).toList();
            Set<String> current = new HashSet<>();
            findings.forEach(f -> current.add(f.key()));
            long fixed = baseline.stream().filter(k -> !current.contains(k)).count();
            md.append("\n## Against the baseline\n\n").append(fresh.size()).append(" new findings, ")
                    .append(fixed).append(" baseline entries no longer found.\n\n");
            for (Finding f : fresh) {
                md.append("- ").append(f.category()).append(' ').append(f.engine()).append(" `")
                        .append(f.file()).append("`: ").append(firstLine(f.detail())).append('\n');
            }
        }

        for (Category c : Category.values()) {
            List<Finding> inCategory = findings.stream().filter(f -> f.category() == c).toList();
            if (inCategory.isEmpty()) {
                continue;
            }
            md.append("\n## ").append(c).append(": ").append(c.description).append("\n");
            Map<String, List<Finding>> groups = new LinkedHashMap<>();
            for (Finding f : inCategory) {
                groups.computeIfAbsent(f.engine() + " · " + f.signature(), k -> new ArrayList<>()).add(f);
            }
            groups.entrySet().stream()
                    .sorted((a, b) -> b.getValue().size() - a.getValue().size())
                    .forEach(g -> {
                        md.append("\n### ").append(g.getKey()).append(" (").append(g.getValue().size()).append(")\n\n");
                        for (Finding f : g.getValue().subList(0, Math.min(25, g.getValue().size()))) {
                            md.append("- `").append(f.file()).append("`: ")
                                    .append(firstLine(f.detail()).replace("|", "\\|")).append('\n');
                        }
                        if (g.getValue().size() > 25) {
                            md.append("- ... ").append(g.getValue().size() - 25).append(" more in findings.tsv\n");
                        }
                    });
        }
        if (!skipReasons.isEmpty()) {
            md.append("\n## Skipped\n\n");
            skipReasons.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                    .forEach(e -> md.append("- ").append(e.getValue()).append(": ").append(e.getKey()).append('\n'));
        }
        Files.writeString(dir.resolve("report.md"), md, StandardCharsets.UTF_8);

        StringBuilder tsv = new StringBuilder("category\tengine\tfile\tsignature\tdetail\n");
        for (Finding f : findings) {
            tsv.append(f.key()).append('\t').append(f.signature()).append('\t')
                    .append(f.detail().replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")).append('\n');
        }
        Files.writeString(dir.resolve("findings.tsv"), tsv, StandardCharsets.UTF_8);

        StringBuilder results = new StringBuilder("file\tengine\tkind\tsignature\terror\n");
        for (int i = 0; i < run.files().size(); i++) {
            for (Map.Entry<ENGINE, Outcome> e : run.outcomesOf(i).entrySet()) {
                Outcome o = e.getValue();
                results.append(run.files().get(i)).append('\t').append(e.getKey()).append('\t')
                        .append(o.kind()).append('\t').append(o.signature()).append('\t')
                        .append(firstLine(o.error()).replace("\t", " ")).append('\n');
            }
        }
        Files.writeString(dir.resolve("results.tsv"), results, StandardCharsets.UTF_8);
    }

    /** The keys of the counted findings, for {@code --write-baseline}. */
    List<String> baselineKeys() {
        return findings.stream().filter(this::counts).map(Finding::key).sorted().toList();
    }

    String consoleSummary() {
        StringBuilder sb = new StringBuilder();
        for (ENGINE engine : engines) {
            if (engine == reference) {
                continue;
            }
            Map<Category, Long> byCategory = new EnumMap<>(Category.class);
            for (Finding f : findings) {
                if (f.engine() == engine) {
                    byCategory.merge(f.category(), 1L, Long::sum);
                }
            }
            sb.append(String.format("%-28s %s%n", engine, byCategory.isEmpty() ? "no findings" : byCategory));
        }
        return sb.toString();
    }
}
