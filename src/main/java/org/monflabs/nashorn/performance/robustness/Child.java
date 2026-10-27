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
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import org.monflabs.nashorn.performance.ScriptExecutor.ENGINE;

/**
 * The child half of {@link CorpusRun}: one engine, in its own JVM, over a range of a corpus.
 *
 * <p>For each file it prints {@value #START} with the file's index, then {@value #OUTCOME} with
 * the result. A script that runs past the time limit cannot be stopped portably (the engines do
 * not all check for interruption, and {@code Thread.stop} is gone), so the child reports a
 * {@link Outcome.Kind#TIMEOUT} and halts; the parent starts a new child after that file. A JVM
 * that dies in a script leaves a {@value #START} without its outcome, which the parent records
 * as a {@link Outcome.Kind#CRASH}.
 */
final class Child {

    static final String START = "##START\t";
    static final String OUTCOME = "##OUTCOME\t";

    /** Scripts run on a thread of this stack size, so that every engine gets the same room. */
    private static final long STACK_SIZE = 16L << 20;

    private Child() {
    }

    /**
     * @param engine    the engine to run
     * @param corpus    the corpus, which prepares each file
     * @param listFile  the corpus files to run, one per line, as the parent numbered them
     * @param from      the first index to run
     * @param to        the index after the last one to run
     * @param timeoutMs the limit for each script
     */
    static void run(ENGINE engine, Corpus corpus, Path listFile, int from, int to, long timeoutMs)
            throws IOException, InterruptedException {
        List<String> files = Files.readAllLines(listFile, StandardCharsets.UTF_8);
        // Engines print warnings of their own; keep the protocol on a stream nobody else writes to
        PrintStream protocol = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),
                true, StandardCharsets.UTF_8);
        System.setOut(System.err);

        SynchronousQueue<Outcome> result = new SynchronousQueue<>();
        Probe probe = Probe.create(engine);
        for (int i = from; i < Math.min(to, files.size()); i++) {
            String file = files.get(i);
            Corpus.Prepared prepared = corpus.prepare(file);
            protocol.println(START + i);
            if (prepared.skipReason() != null) {
                protocol.println(OUTCOME + i + "\t"
                        + new Outcome(Outcome.Kind.SKIPPED, "", prepared.skipReason()).encode());
                continue;
            }
            String prelude = Probe.PRELUDE + prepared.shim();
            Thread worker = new Thread(null, () -> {
                Outcome outcome;
                try {
                    outcome = probe.run(prelude, prepared.source(), file);
                } catch (Throwable t) {
                    outcome = Probe.crash(t);
                }
                try {
                    result.put(outcome);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "probe-" + i, STACK_SIZE);
            worker.setDaemon(true);
            worker.start();
            Outcome outcome = result.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (outcome == null) {
                protocol.println(OUTCOME + i + "\t"
                        + new Outcome(Outcome.Kind.TIMEOUT, "", "over " + timeoutMs + " ms").encode());
                protocol.flush();
                Runtime.getRuntime().halt(0);
            }
            protocol.println(OUTCOME + i + "\t" + outcome.encode());
        }
        protocol.flush();
        probe.close();
        Runtime.getRuntime().halt(0);
    }
}
