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

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.monflabs.nashorn.performance.ScriptExecutor;

public class GraalJSExecutor extends ScriptExecutor {

    /**
     * Every Octane/SunSpider/V8 benchmark file ends by calling {@code print(...)} with its
     * result; GraalJS has no such global unless one is installed. Bridges it to GraalJS's own
     * {@code console.log}, mirroring the Nashorn executors' console.log-over-print polyfill in
     * reverse, so a benchmark's own result line surfaces instead of a bare "print is not
     * defined" failure on every single file.
     */
    private static final String PRINT_POLYFILL =
            "if (typeof print === 'undefined') {"
            + " var print = function() { console.log.apply(console, arguments); };"
            + "}";

    private final boolean compiled;
    private Context graalContext;
    private Source script;

    public GraalJSExecutor(boolean compiled) {
        this.compiled = compiled;
    }

    @Override
    public ENGINE getEngine() {
        return compiled ? ENGINE.GRAALJS_COMPILED : ENGINE.GRAALJS_INTERPRETED;
    }

    @Override
    public boolean isSupported() {
        return !compiled || TruffleCompilerCheck.isCompilerAvailable();
    }

    @Override
    public void init(String code, String filePath) throws Exception {
        initEngine();
        parse(code, filePath);
    }

    @Override
    public void initEngine() throws Exception {
        graalContext = Context.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        graalContext.eval("js", PRINT_POLYFILL);
    }

    @Override
    public void parse(String code, String filePath) throws Exception {
        // cached(false): otherwise identical sources hit an internal parse cache and every
        // iteration but the first measures near-zero.
        script = Source.newBuilder("js", code, filePath).cached(false).build();
        // GraalJS parses lazily inside eval() otherwise, which would leak parse time into the
        // timed run() call. Force the parse here instead.
        graalContext.parse(script);
    }

    @Override
    public void terminate() throws Exception {
        if (graalContext != null) {
            graalContext.close();
        }
        graalContext = null;
    }

    @Override
    public void run() throws Exception {
        Value result = graalContext.eval(script);
        setLastScore(result.fitsInDouble() ? result.asDouble() : null);
    }
}
