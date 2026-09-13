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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Times a repeated action, wall-clock and CPU time, with an untimed warmup phase run first.
 * Callers are expected to have already compiled/parsed whatever they are about to run - only
 * the action passed to {@link #runWithException} is ever inside the timed loop.
 */
public final class PerformanceWatch {

    public static final long NANOSECONDS_PER_MILLI = 1_000_000L;

    @FunctionalInterface
    public interface RunnableWithException {
        void run() throws Exception;
    }

    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();

    private final String name;
    private long totalWallTime;
    private long totalCpuTime;
    private long minWallTime;
    private long minCpuTime;

    public PerformanceWatch(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public long getTotalWallTime() {
        return totalWallTime;
    }

    public long getTotalCpuTime() {
        return totalCpuTime;
    }

    /**
     * The fastest iteration's wall time, and the CPU time of that same iteration.
     *
     * Inside one JVM every disturbance - a GC pause, a JIT recompile landing
     * mid-run, the OS scheduling something else - costs time, so the fastest
     * sample is the truest measure of what the engine can do. It is the statistic
     * {@link IsolatedRunner} records from each forked JVM, where the noise it
     * discards would otherwise be attributed to whichever engine ran while the
     * machine happened to be busy.
     *
     * @return the minimum wall time of the timed iterations, in nanoseconds
     */
    public long getMinWallTime() {
        return minWallTime;
    }

    /**
     * @return the CPU time of the iteration {@link #getMinWallTime()} reports
     */
    public long getMinCpuTime() {
        return minCpuTime;
    }

    public void runWithException(RunnableWithException task, int iterations, int warmupIterations) throws Exception {
        for (int i = 0; i < warmupIterations; i++) {
            task.run();
        }

        long[] wallTimes = new long[iterations];
        long[] cpuTimes = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long wallStart = System.nanoTime();
            long cpuStart = THREAD_MX_BEAN.getCurrentThreadCpuTime();
            task.run();
            wallTimes[i] = System.nanoTime() - wallStart;
            long cpuNow = THREAD_MX_BEAN.getCurrentThreadCpuTime();
            cpuTimes[i] = (cpuStart >= 0 && cpuNow >= 0) ? cpuNow - cpuStart : 0;
        }

        // A single iteration can be thrown off by a GC pause, a JIT recompile kicking in
        // mid-run, or OS scheduling noise - in either direction. With enough iterations to
        // still have a meaningful sample left afterwards, drop the fastest and the slowest
        // (ranked by wall time) before summing, rather than let one outlier skew the result.
        int excludeMin = -1;
        int excludeMax = -1;
        if (iterations >= 3) {
            excludeMin = 0;
            excludeMax = 0;
            for (int i = 1; i < iterations; i++) {
                if (wallTimes[i] < wallTimes[excludeMin]) excludeMin = i;
                if (wallTimes[i] > wallTimes[excludeMax]) excludeMax = i;
            }
        }

        totalWallTime = 0;
        totalCpuTime = 0;
        for (int i = 0; i < iterations; i++) {
            if (i == excludeMin || i == excludeMax) continue;
            totalWallTime += wallTimes[i];
            totalCpuTime += cpuTimes[i];
        }

        // The fastest iteration is what isolated mode reports (see getMinWallTime). The
        // trimming above already found it, when it ran at all.
        int fastest = excludeMin;
        if (fastest < 0) {
            fastest = 0;
            for (int i = 1; i < iterations; i++) {
                if (wallTimes[i] < wallTimes[fastest]) fastest = i;
            }
        }
        minWallTime = iterations > 0 ? wallTimes[fastest] : 0;
        minCpuTime = iterations > 0 ? cpuTimes[fastest] : 0;
    }
}
