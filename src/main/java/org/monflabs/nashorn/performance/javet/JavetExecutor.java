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
package org.monflabs.nashorn.performance.javet;

import java.util.logging.Logger;

import com.caoccao.javet.interception.logging.JavetStandardConsoleInterceptor;
import com.caoccao.javet.interop.V8Host;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.interop.options.V8RuntimeOptions;
import com.caoccao.javet.values.reference.V8Script;
import org.monflabs.nashorn.performance.ScriptExecutor;

/**
 * Real V8, via the <a href="https://github.com/caoccao/Javet">Javet</a> JNI binding, in its
 * plain "V8 mode" (as opposed to Javet's Node.js mode, which this harness has no use for).
 * Javet ships one native library per (OS, CPU architecture) as a separate artifact, so
 * {@link #isSupported()} reports unavailable rather than throwing when this JVM's platform
 * has none on the classpath (see the pom's OS-activated profiles).
 */
public class JavetExecutor extends ScriptExecutor {

    /**
     * V8 has no {@code print}/{@code console} global of its own. Bridges to the
     * {@code console.log} registered by {@link JavetStandardConsoleInterceptor}, mirroring the
     * GraalJS executor's identical polyfill, so a benchmark's own result line surfaces instead
     * of a bare "print is not defined" failure on every single file.
     */
    private static final String PRINT_POLYFILL =
            "if (typeof print === 'undefined') {"
            + " var print = function() { console.log.apply(console, arguments); };"
            + "}";

    // A java.util.logging.LogManager holds loggers by weak reference: without a strong
    // reference of our own, the Logger below (with its filter) can be GC'd between benchmark
    // files, silently replaced by a fresh, unfiltered default the next time Javet calls
    // Logger.getLogger(V8Runtime.class.getName()) - which is exactly what let the "not
    // recycled" warning below reappear partway through a long multi-file run.
    private static final Logger V8_RUNTIME_LOGGER = Logger.getLogger(V8Runtime.class.getName());

    static {
        // Javet defaults V8RuntimeOptions.V8_FLAGS.useStrict to true, which passes V8's global
        // --use-strict command-line flag - not "the script runs in strict mode" but "there is
        // no sloppy mode at all", process-wide. That is why a plain, undeclared `x = 0` throws
        // ReferenceError under Javet even though the exact same assignment works in a bare
        // `node -e` sloppy-mode script; `node --use_strict` reproduces the identical error.
        // Every other engine in this harness defaults to sloppy mode, so match that here. Must
        // run before the first V8Runtime is created - V8Flags seals once V8 initializes.
        V8RuntimeOptions.V8_FLAGS.setUseStrict(false);

        // JavetStandardConsoleInterceptor.register() binds 6 callback contexts (console.log/
        // info/debug/trace/error/warn); its unregister() only deletes the "console" property
        // and never removes them from V8Runtime's own callback-context map, so close() always
        // logs this warning for any runtime that used the interceptor - Javet's register()/
        // unregister() pair offers no way to unbind them from the outside. The map is cleared
        // right after the log line (removeCallbackContexts() calls clear() unconditionally), so
        // nothing actually accumulates across runs; only this one benign, unavoidable message
        // is filtered out.
        V8_RUNTIME_LOGGER.setFilter(record ->
                record.getMessage() == null || !record.getMessage().contains("not recycled"));
    }

    private V8Runtime v8Runtime;
    private V8Script script;
    private JavetStandardConsoleInterceptor consoleInterceptor;

    @Override
    public ENGINE getEngine() {
        return ENGINE.V8_JAVET;
    }

    @Override
    public boolean isSupported() {
        try {
            return V8Host.getV8Instance().loadLibrary();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void init(String code, String filePath) throws Exception {
        initEngine();
        parse(code, filePath);
    }

    @Override
    public void initEngine() throws Exception {
        v8Runtime = V8Host.getV8Instance().createV8Runtime();
        consoleInterceptor = new JavetStandardConsoleInterceptor(v8Runtime);
        consoleInterceptor.register(v8Runtime.getGlobalObject());
        v8Runtime.getExecutor(PRINT_POLYFILL).executeVoid();
    }

    @Override
    public void parse(String code, String filePath) throws Exception {
        script = v8Runtime.getExecutor(code).setResourceName(filePath).compileV8Script();
    }

    @Override
    public void terminate() throws Exception {
        if (script != null) {
            script.close();
        }
        script = null;
        if (consoleInterceptor != null && v8Runtime != null) {
            consoleInterceptor.unregister(v8Runtime.getGlobalObject());
        }
        consoleInterceptor = null;
        if (v8Runtime != null) {
            v8Runtime.close();
        }
        v8Runtime = null;
    }

    @Override
    public void run() throws Exception {
        script.execute(false);
    }
}
