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

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
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
        graalContext.eval(script);
    }
}
