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

import org.monflabs.nashorn.performance.PerformanceWatch.RunnableWithException;

/**
 * One engine's binding to the benchmark harness. {@link #init} (or {@link #initEngine} followed
 * by {@link #parse}) does whatever compilation/setup work the engine needs and is never timed;
 * only {@link #run} is timed, and may be called repeatedly on the same executor.
 */
public abstract class ScriptExecutor implements RunnableWithException {

    public enum ENGINE {
        NASHORN_MONFLABS,
        NASHORN_OPENJDK,
        RHINO_INTERPRETED,
        RHINO_COMPILED,
        GRAALJS_INTERPRETED,
        GRAALJS_COMPILED,
        V8_JAVET
    }

    public abstract ENGINE getEngine();

    /**
     * Whether this engine/mode can actually run on this JVM. Checked before {@link #init} is
     * called - e.g. GraalJS "compiled" mode is unsupported when no real Graal compiler backs
     * the running JVM, and should be reported as unavailable rather than attempted.
     */
    public boolean isSupported() {
        return true;
    }

    /** One-time engine setup, kept out of {@link #run}'s timing. Default is a no-op. */
    public void initEngine() throws Exception {
    }

    /** Compile/parse the source and hold the compiled artifact. */
    public void parse(String code, String filePath) throws Exception {
        init(code, filePath);
    }

    public abstract void init(String code, String filePath) throws Exception;
    public abstract void terminate() throws Exception;

    @Override
    public abstract void run() throws Exception;
}
