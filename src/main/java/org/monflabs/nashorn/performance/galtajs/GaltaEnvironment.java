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
package org.monflabs.nashorn.performance.galtajs;

import org.monflabs.galtajs.JSEnvironment;
import org.monflabs.galtajs.environments.JavaScriptEnvironment;
import org.monflabs.galtajs.library.rhino.RhinoShellLibrary;
import org.monflabs.galtajs.optimizer.ScriptOptimizer;
import org.monflabs.galtajs.rt.builtins.standard.regexp.jdk.RegExpEngineJdk;

/**
 * A fresh {@link JSEnvironment} shared by both GaltaJS executors (interpreted and
 * compiled/transpiled). {@link RhinoShellLibrary} supplies a native {@code print}, so unlike the
 * other engines in this harness GaltaJS needs no print-polyfill. The JDK-backed RegExp engine is
 * used rather than the Joni backend, so this project needs no extra {@code org.jruby.joni:joni}
 * dependency; every cache is disabled so a run measures the engine's own work rather than a
 * cache hit.
 */
final class GaltaEnvironment {

    private GaltaEnvironment() {
    }

    static JSEnvironment create() {
        return JavaScriptEnvironment.newBuilder()
                .strictMode(false)
                .registerLibrary(new RhinoShellLibrary())
                .scriptOptimizer(ScriptOptimizer.defaultOptimizer())
                .regexpEngineFactory(RegExpEngineJdk.factory())
                .scriptCacheSize(0)
                .evalCacheSize(0)
                .regexpCacheSize(0)
                .build();
    }
}
