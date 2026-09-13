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
