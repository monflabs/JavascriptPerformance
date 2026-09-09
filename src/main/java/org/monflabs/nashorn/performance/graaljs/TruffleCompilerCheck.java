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
