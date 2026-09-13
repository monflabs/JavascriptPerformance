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
package org.monflabs.nashorn.performance.nashorn;

import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;

import org.monflabs.nashorn.performance.ScriptExecutor;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

public class OpenjdkNashornExecutor extends ScriptExecutor {

    private ScriptEngine engine;
    private CompiledScript script;

    @Override
    public ENGINE getEngine() {
        return ENGINE.NASHORN_OPENJDK;
    }

    @Override
    public void init(String code, String filePath) throws Exception {
        initEngine();
        parse(code, filePath);
    }

    @Override
    public void initEngine() throws Exception {
        NashornScriptEngineFactory factory = new NashornScriptEngineFactory();
        engine = factory.getScriptEngine("--optimistic-types=true", "--language=es6");
        String consolePolyfill = "var console = { log: function() {"
                + " print(Array.prototype.join.call(arguments, ' '));"
                + " } };";
        engine.eval(consolePolyfill);
    }

    @Override
    public void parse(String code, String filePath) throws Exception {
        script = ((Compilable) engine).compile(code);
    }

    @Override
    public void terminate() throws Exception {
    }

    @Override
    public void run() throws Exception {
        script.eval();
    }
}
