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

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;

/**
 * Measures what one benchmark costs in memory, on one engine, in the calling thread:
 *
 * <ul>
 *   <li><b>compile alloc</b>: bytes allocated by {@link ScriptExecutor#init} (realm creation, parse,
 *       compile).</li>
 *   <li><b>retained compiled</b>: the live heap that init left behind - the realm, the compiled
 *       script and whatever the engine caches for it - measured after a full GC.</li>
 *   <li><b>alloc per run</b>: bytes allocated by one {@link ScriptExecutor#run}, the fewest of the
 *       measured iterations (after the warmup ones), since a run that triggers a recompilation or
 *       fills a cache allocates more than its steady state.</li>
 *   <li><b>retained after run</b>: the live heap after the runs, before {@link ScriptExecutor#terminate}:
 *       the realm plus everything the benchmark keeps reachable from it, in the engine's object
 *       representation.</li>
 *   <li><b>metaspace</b> and <b>classes</b>: what loading the engine's runtime and the script's
 *       generated classes added, a cost compiled modes pay and interpreted modes mostly do not.</li>
 * </ul>
 *
 * Every figure is a difference from a baseline taken after a full GC just before init, so a
 * previous benchmark of the same JVM only counts if it leaked. Allocation is counted per thread
 * ({@code com.sun.management.ThreadMXBean}), which assumes the engine runs the script on the
 * calling thread, as every engine of this harness does. The heap figures are not timing-sensitive,
 * but {@link #settledHeap()} calls {@link System#gc()}, so memory is measured in runs of its own,
 * never together with time.
 */
public final class MemoryWatch {

    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
    private static final ClassLoadingMXBean CLASSES = ManagementFactory.getClassLoadingMXBean();
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final MemoryPoolMXBean METASPACE = ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> "Metaspace".equals(pool.getName())).findFirst().orElse(null);

    /** compile alloc, retained compiled, alloc per run, retained after run, metaspace (bytes), classes. */
    public record Sample(long compileAlloc, long retainedCompiled, long allocPerRun, long retainedAfterRun,
            long metaspace, long classes) {
    }

    private MemoryWatch() {
    }

    static Sample measure(ScriptExecutor executor, String script, String filePath, int warmup,
            int iterations) throws Exception {
        long baseHeap = settledHeap();
        long baseMeta = metaspace();
        long baseClasses = CLASSES.getTotalLoadedClassCount();

        long before = THREADS.getCurrentThreadAllocatedBytes();
        executor.init(script, filePath);
        long compileAlloc = THREADS.getCurrentThreadAllocatedBytes() - before;
        long retainedCompiled = settledHeap() - baseHeap;

        for (int i = 0; i < warmup; i++) {
            executor.run();
        }
        long allocPerRun = Long.MAX_VALUE;
        for (int i = 0; i < Math.max(1, iterations); i++) {
            before = THREADS.getCurrentThreadAllocatedBytes();
            executor.run();
            allocPerRun = Math.min(allocPerRun, THREADS.getCurrentThreadAllocatedBytes() - before);
        }
        long retainedAfterRun = settledHeap() - baseHeap;
        return new Sample(compileAlloc, retainedCompiled, allocPerRun, retainedAfterRun,
                metaspace() - baseMeta, CLASSES.getTotalLoadedClassCount() - baseClasses);
    }

    /**
     * The live heap: used heap after full collections, repeated until it stops shrinking, since
     * one collection can leave behind what a finalizer or a reference queue frees for the next.
     */
    static long settledHeap() {
        long used = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            System.gc();
            long now = MEMORY.getHeapMemoryUsage().getUsed();
            if (now >= used - used / 100) {
                return Math.min(now, used);
            }
            used = now;
        }
        return used;
    }

    private static long metaspace() {
        return METASPACE == null ? 0 : METASPACE.getUsage().getUsed();
    }
}
