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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.monflabs.nashorn.performance.ScriptExecutor.ENGINE;

/**
 * Runs a corpus on several engines, each file in a fresh realm, the files split into shards and
 * each shard run in a child JVM ({@link Child}), several at a time. A child that halts on a
 * timeout or dies is replaced by a new one that carries on after the file that stopped it, so a
 * hang or a JVM crash costs one file, not the run.
 */
final class CorpusRun {

    private final String corpusSpec;
    private final Corpus corpus;
    private final List<ENGINE> engines;
    private final int jobs;
    private final int shardSize;
    private final long timeoutMs;
    private final Path outDir;

    /** file index -> engine -> outcome. */
    private final Map<Integer, Map<ENGINE, Outcome>> outcomes = new ConcurrentHashMap<>();
    private List<String> files;

    CorpusRun(String corpusSpec, Path root, List<ENGINE> engines, int jobs, int shardSize,
            long timeoutMs, Path outDir) {
        this.corpusSpec = corpusSpec;
        this.corpus = Corpus.create(corpusSpec, root);
        this.engines = List.copyOf(engines);
        this.jobs = jobs;
        this.shardSize = shardSize;
        this.timeoutMs = timeoutMs;
        this.outDir = outDir;
    }

    Corpus corpus() {
        return corpus;
    }

    List<String> files() {
        return files;
    }

    Map<Integer, Map<ENGINE, Outcome>> outcomes() {
        return outcomes;
    }

    /** Runs every engine over the corpus, or over the files whose path contains {@code filter}. */
    void run(String filter) throws IOException, InterruptedException {
        files = corpus.files().stream().filter(f -> filter == null || f.contains(filter)).toList();
        Files.createDirectories(outDir);
        Path listFile = outDir.resolve("files.txt");
        Files.write(listFile, files, StandardCharsets.UTF_8);

        List<int[]> shards = new ArrayList<>();
        for (int from = 0; from < files.size(); from += shardSize) {
            shards.add(new int[] {from, Math.min(files.size(), from + shardSize)});
        }
        int total = shards.size() * engines.size();
        System.out.printf("%s: %d files, %d engines, %d shards of %d, %d at a time, %d ms per file%n",
                corpus.name(), files.size(), engines.size(), shards.size(), shardSize, jobs, timeoutMs);

        AtomicInteger done = new AtomicInteger();
        long start = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(jobs);
        List<Future<?>> futures = new ArrayList<>();
        // Shard by shard, every engine: the reference results arrive as early as the others
        for (int[] shard : shards) {
            for (ENGINE engine : engines) {
                futures.add(pool.submit(() -> {
                    runShard(engine, listFile, shard[0], shard[1]);
                    int n = done.incrementAndGet();
                    long elapsed = (System.nanoTime() - start) / 1_000_000_000L;
                    System.out.printf("  [%d/%d] %s files %d-%d (%ds)%n", n, total, engine,
                            shard[0], shard[1] - 1, elapsed);
                    return null;
                }));
            }
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IOException(e.getCause());
            }
        }
        pool.shutdown();
    }

    private void runShard(ENGINE engine, Path listFile, int from, int to) throws IOException {
        int next = from;
        while (next < to) {
            int first = next;
            int[] started = {-1};
            Deque<String> tail = new ArrayDeque<>();
            Process process = new ProcessBuilder(command(engine, listFile, next, to))
                    .redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith(Child.START)) {
                        started[0] = Integer.parseInt(line.substring(Child.START.length()));
                    } else if (line.startsWith(Child.OUTCOME)) {
                        String[] parts = line.substring(Child.OUTCOME.length()).split("\t", -1);
                        int index = Integer.parseInt(parts[0]);
                        record(index, engine, Outcome.decode(parts[1], parts[2], parts[3]));
                        next = index + 1;
                    } else {
                        tail.addLast(line);
                        if (tail.size() > 40) {
                            tail.removeFirst();
                        }
                    }
                }
            }
            int exit;
            try {
                exit = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return;
            }
            if (next < to && started[0] >= next) {
                // Died in a script: a JVM crash, an OutOfMemoryError outside the probe, ...
                record(started[0], engine, new Outcome(Outcome.Kind.CRASH, "",
                        "JVM exited with " + exit + "\n" + String.join("\n", tail)));
                next = started[0] + 1;
            } else if (next == first) {
                // Died before running a script: an engine that cannot start at all
                for (int i = next; i < to; i++) {
                    record(i, engine, new Outcome(Outcome.Kind.CRASH, "",
                            "child exited with " + exit + " before running\n" + String.join("\n", tail)));
                }
                return;
            }
        }
    }

    private void record(int index, ENGINE engine, Outcome outcome) {
        outcomes.computeIfAbsent(index, k -> new ConcurrentHashMap<>()).put(engine, outcome);
    }

    private List<String> command(ENGINE engine, Path listFile, int from, int to) {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + "/bin/java");
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.startsWith("-Xms") || arg.startsWith("-Xmx") || arg.endsWith("GC")
                    || arg.startsWith("-agentlib:jdwp")) {
                continue;
            }
            command.add(arg);
        }
        command.addAll(List.of("-Xms256m", "-Xmx2g", "-XX:+UseG1GC",
                "--enable-native-access=ALL-UNNAMED",
                // A compiler bug that makes the Rhino fork fall back to its interpreter is a crash
                "-Drhino.failOnCompileFallback=true"));
        if (Boolean.getBoolean("robustness.includeHeavy")) {
            command.add("-Drobustness.includeHeavy=true");
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Robustness.class.getName());
        command.add("child");
        command.add("--engine=" + engine.name());
        command.add("--corpus=" + corpusSpec);
        command.add("--root=" + corpus.root.toAbsolutePath());
        command.add("--list=" + listFile.toAbsolutePath());
        command.add("--from=" + from);
        command.add("--to=" + to);
        command.add("--timeout=" + timeoutMs);
        return command;
    }

    /** The outcomes of one file, engines in run order, missing ones absent. */
    Map<ENGINE, Outcome> outcomesOf(int index) {
        Map<ENGINE, Outcome> byEngine = new EnumMap<>(ENGINE.class);
        Map<ENGINE, Outcome> recorded = outcomes.get(index);
        if (recorded != null) {
            byEngine.putAll(recorded);
        }
        return byEngine;
    }
}
