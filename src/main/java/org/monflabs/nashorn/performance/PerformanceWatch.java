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
    }
}
