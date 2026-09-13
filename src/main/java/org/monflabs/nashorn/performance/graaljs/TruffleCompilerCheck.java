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
package org.monflabs.nashorn.performance.graaljs;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleRuntime;

/**
 * Detects whether a real, JIT-compiling Truffle runtime backs this JVM (a GraalVM JDK, or a
 * plain JDK with the Graal compiler on its module/class path) as opposed to Truffle's
 * interpreter-only fallback runtime (any plain JDK without it) - the difference between
 * GraalJS "compiled" mode being meaningful and it being identical to "interpreted".
 */
final class TruffleCompilerCheck {

    private TruffleCompilerCheck() {
    }

    static boolean isCompilerAvailable() {
        try {
            TruffleRuntime runtime = Truffle.getRuntime();
            String className = runtime.getClass().getName();
            return !className.contains("Default") && !className.contains("Fallback");
        } catch (Throwable t) {
            return false;
        }
    }
}
