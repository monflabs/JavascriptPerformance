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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A set of scripts to run, and how to adapt each one to a plain ECMAScript host.
 */
abstract class Corpus {

    /** How a script's outcome is compared with the reference engine's. */
    enum Judging {
        /**
         * The script checks itself and throws when something is wrong, so completing is passing.
         * A different output is reported but is not a finding: tests print diagnostics freely.
         */
        COMPLETION,
        /** The script computes its result in what it prints: output and outcome must match. */
        EXACT
    }

    /** A script ready to run: the corpus's shim and the script's source, or why it is skipped. */
    record Prepared(String shim, String source, String skipReason) {
        static Prepared skip(String reason) {
            return new Prepared("", "", reason);
        }
    }

    final Path root;

    Corpus(Path root) {
        this.root = root;
    }

    abstract String name();

    abstract Judging judging();

    /** The scripts, as paths relative to {@link #root}, in a stable order. */
    abstract List<String> files();

    abstract Prepared prepare(String file);

    String read(String file) {
        try {
            return Files.readString(root.resolve(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Corpus create(String spec, Path root) {
        return switch (spec) {
            case "jsc-stress" -> new JscStress(root);
            case "fuzz" -> new Directory(root, Judging.EXACT, "fuzz");
            default -> new Directory(root, Judging.COMPLETION, "dir");
        };
    }

    static List<String> listJs(Path dir, boolean recursive) {
        try (Stream<Path> paths = recursive ? Files.walk(dir) : Files.list(dir)) {
            return paths.filter(p -> p.toString().endsWith(".js") && Files.isRegularFile(p))
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every {@code .js} file under a directory, run as it is. */
    static final class Directory extends Corpus {
        private final Judging judging;
        private final String name;

        Directory(Path root, Judging judging, String name) {
            super(root);
            this.judging = judging;
            this.name = name;
        }

        @Override
        String name() {
            return name;
        }

        @Override
        Judging judging() {
            return judging;
        }

        @Override
        List<String> files() {
            return listJs(root, true);
        }

        @Override
        Prepared prepare(String file) {
            return new Prepared("", read(file), null);
        }
    }

    /**
     * JavaScriptCore's {@code JSTests/stress}: about 6,000 regression tests, many of them reduced
     * from crashes, that throw when they go wrong. They call a few functions of the {@code jsc}
     * shell; those that only steer its JIT tiers are shimmed as no-ops, and a test that relies on
     * one with real semantics (a second global object, a module loader, {@code $vm}) is skipped.
     */
    static final class JscStress extends Corpus {

        /** The JIT hints and the few shell functions with a portable meaning. */
        static final String SHIM = """
                var testLoopCount = 10000;
                var noInline = function (f) { return f; }, noDFG = noInline, noFTL = noInline,
                    noOSRExitFuzzing = noInline, neverInlineFunction = noInline,
                    ensureArrayStorage = function () {}, optimizeNextInvocation = function () {},
                    OSRExit = function () {}, fiatInt52 = function (x) { return x; };
                var gc = function () {}, fullGC = gc, edenGC = gc, debug = gc;
                var detachArrayBuffer = function (b) { b.transfer(); }, transferArrayBuffer = detachArrayBuffer;
                """;

        /** Shell functions whose semantics cannot be shimmed in portable JavaScript. */
        private static final Pattern UNSUPPORTED = Pattern.compile(
                "\\$vm\\b|\\$262\\b|\\$\\.agent|\\b(createGlobalObject|numberOfDFGCompiles|isFinalTier"
                + "|reoptimizationRetryCount|callerIsBBQOrOMGCompiled|isInt32|describe|createDOMJIT"
                + "|load|loadString|runString|readFile|checkModuleSyntax|WebAssembly|Worker|setTimeout"
                + "|drainMicrotasks|abort|preciseTime|gcHeapSize|jscOptions|enableException|getHiddenValue"
                + "|releaseWeakRefs|finalizeUnconditionally|callerSourceOrigin|forceGCSlowPaths|isPureNaN"
                + "|generateHeapSnapshot\\w*|startSamplingProfiler|samplingProfilerStackTraces|heapCapacity"
                + "|totalCompileTime|setRandomSeed|gcAndSweep|serialize|globalObjectForObject"
                + "|makeMasquerader|isRope|createBuiltin|readline|quit|setUnhandledRejectionCallback"
                + "|isHavingABadTime|haveABadTime|createCustomGetterObject|addressOf|dumpCell|flashHeapAccess"
                + "|evalScript|getTimeZoneOverride|setTimeZone|platformSupportsSamplingProfiler"
                + "|runnerRunsAsync|callWithStackSize)\\s*\\(");

        /** Static {@code import} or {@code export}: a module, which this runner does not load. */
        private static final Pattern MODULE = Pattern.compile("(?m)^\\s*(import\\s+[\\w{*'\"]|export\\s)");

        /**
         * {@code f.caller} and {@code f.arguments}: a legacy extension that the specification
         * leaves to the host for sloppy functions, which engines provide or not.
         */
        private static final Pattern LEGACY_CALLER = Pattern.compile("\\w\\.(caller|arguments)\\b");

        private static final Pattern DIRECTIVE = Pattern.compile("(?m)^//@(.*)$");

        private final boolean includeHeavy;

        JscStress(Path root) {
            super(root);
            this.includeHeavy = Boolean.getBoolean("robustness.includeHeavy");
        }

        @Override
        String name() {
            return "jsc-stress";
        }

        @Override
        Judging judging() {
            return Judging.COMPLETION;
        }

        @Override
        List<String> files() {
            return listJs(root, false);
        }

        @Override
        Prepared prepare(String file) {
            String source = read(file);
            Matcher directives = DIRECTIVE.matcher(source);
            while (directives.find()) {
                String d = directives.group(1).trim();
                if (d.equals("skip")) {
                    return Prepared.skip("skipped by JSC");
                }
                if (d.contains("--use")) {
                    return Prepared.skip("needs a JSC feature flag");
                }
                if (!includeHeavy && (d.startsWith("memoryHog") || d.startsWith("slow!"))) {
                    return Prepared.skip("memory hog or slow");
                }
            }
            Matcher unsupported = UNSUPPORTED.matcher(source);
            if (unsupported.find()) {
                return Prepared.skip("calls the jsc shell's " + unsupported.group().replace("(", "").trim());
            }
            if (LEGACY_CALLER.matcher(source).find()) {
                return Prepared.skip("uses the legacy Function caller or arguments");
            }
            if (MODULE.matcher(source).find()) {
                return Prepared.skip("a module");
            }
            return new Prepared(SHIM, source, null);
        }
    }
}
