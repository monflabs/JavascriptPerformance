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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.monflabs.nashorn.performance.ScriptExecutor.ENGINE;

/**
 * Shrinks a generated program that two engines disagree on to the smallest one they still
 * disagree on the same way: removes its top-level chunks, then its declarations, one at a time,
 * keeping each removal after which the difference remains, until none can go.
 *
 * <p>Runs in the parent JVM, each script on a thread of its own with a time limit. A script that
 * hangs is abandoned (its thread is a daemon) and counts as a timeout.
 */
final class Reducer implements AutoCloseable {

    private final Probe engine;
    private final Probe other;
    private final boolean backends;
    private final long timeoutMs;
    private int runs;
    /** The reference's outcome on the unreduced program. */
    private Outcome original;

    /**
     * @param engine   the engine with the finding
     * @param other    the engine it is compared with: the reference, or for a
     *                 {@link Analysis.Category#BACKENDS} finding the interpreted fork
     * @param backends whether the finding is a disagreement of the fork's two backends
     */
    Reducer(ENGINE engine, ENGINE other, boolean backends, long timeoutMs) {
        this.engine = Probe.create(engine);
        this.other = Probe.create(other);
        this.backends = backends;
        this.timeoutMs = timeoutMs;
    }

    int runs() {
        return runs;
    }

    /** The reduced program, or the program itself when the difference does not reproduce. */
    String reduce(String program, Analysis.Category category) throws InterruptedException {
        int helpersEnd = program.indexOf(ProgramGenerator.HELPERS) + ProgramGenerator.HELPERS.length();
        String prefix = program.substring(0, helpersEnd);
        String rest = program.substring(helpersEnd);
        String[] parts = rest.split(ProgramGenerator.CHUNK + "\n", -1);
        List<String> declarations = new ArrayList<>(List.of(parts[0].split("\n")));
        declarations.removeIf(String::isBlank);
        List<String> chunks = new ArrayList<>(List.of(parts).subList(1, parts.length));

        original = run(other, assemble(prefix, declarations, chunks));
        if (!differs(assemble(prefix, declarations, chunks), category)) {
            return program;
        }
        boolean changed = true;
        while (changed) {
            changed = shrink(prefix, declarations, chunks, chunks, category);
            changed |= shrink(prefix, declarations, chunks, declarations, category);
        }
        return assemble(prefix, declarations, chunks);
    }

    private boolean shrink(String prefix, List<String> declarations, List<String> chunks,
            List<String> list, Analysis.Category category) throws InterruptedException {
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            String removed = list.remove(i);
            if (differs(assemble(prefix, declarations, chunks), category)) {
                changed = true;
            } else {
                list.add(i, removed);
            }
        }
        return changed;
    }

    private static String assemble(String prefix, List<String> declarations, List<String> chunks) {
        StringBuilder sb = new StringBuilder(prefix);
        for (String d : declarations) {
            sb.append(d).append('\n');
        }
        for (String c : chunks) {
            sb.append(ProgramGenerator.CHUNK).append('\n').append(c);
        }
        return sb.toString();
    }

    private boolean differs(String program, Analysis.Category category) throws InterruptedException {
        Outcome ref = run(other, program);
        if (drifted(ref)) {
            return false;
        }
        Outcome o = run(engine, program);
        if (backends) {
            return o.kind() != Outcome.Kind.TIMEOUT && ref.kind() != Outcome.Kind.TIMEOUT
                    && !Analysis.sameResult(o, ref);
        }
        return Analysis.classify(o, ref) == category;
    }

    /**
     * Whether removing a declaration has turned the program into another one: the reference no
     * longer ends the same way, or reports a {@code ReferenceError} that it did not, which is what
     * a chunk using a removed declaration does. Reducing towards that would end on a program
     * about a missing variable rather than about the difference.
     */
    private boolean drifted(Outcome ref) {
        return ref.kind() != original.kind()
                || count(ref.output(), "throw ReferenceError") > count(original.output(), "throw ReferenceError");
    }

    private static int count(String text, String line) {
        int n = 0;
        for (String l : text.split("\n")) {
            if (l.equals(line)) {
                n++;
            }
        }
        return n;
    }

    /** Runs a program on the engine with the finding. */
    Outcome runEngine(String program) throws InterruptedException {
        return run(engine, program);
    }

    /** Runs a program on the engine it is compared with. */
    Outcome runOther(String program) throws InterruptedException {
        return run(other, program);
    }

    private Outcome run(Probe probe, String program) throws InterruptedException {
        runs++;
        ArrayBlockingQueue<Outcome> result = new ArrayBlockingQueue<>(1);
        Thread worker = new Thread(null, () -> {
            Outcome outcome;
            try {
                outcome = probe.run(Probe.PRELUDE, program, "reduced.js");
            } catch (Throwable t) {
                outcome = Probe.crash(t);
            }
            result.offer(outcome);
        }, "reduce", 16L << 20);
        worker.setDaemon(true);
        worker.start();
        Outcome outcome = result.poll(timeoutMs, TimeUnit.MILLISECONDS);
        return outcome != null ? outcome : new Outcome(Outcome.Kind.TIMEOUT, "", "over " + timeoutMs + " ms");
    }

    @Override
    public void close() {
        engine.close();
        other.close();
    }
}
