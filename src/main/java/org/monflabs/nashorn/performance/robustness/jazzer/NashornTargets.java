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
package org.monflabs.nashorn.performance.robustness.jazzer;

import javax.script.Bindings;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;

import org.monflabs.nashorn.api.scripting.NashornException;
import org.monflabs.nashorn.api.scripting.NashornScriptEngineBuilder;

/** What the Nashorn targets share. */
final class NashornTargets {

    private NashornTargets() {
    }

    /** A {@code ScriptException} is expected when it carries a JavaScript error; not otherwise. */
    static void rethrowUnexpected(ScriptException e) throws Throwable {
        Throwable cause = e.getCause();
        if (cause != null && !(cause instanceof NashornException)) {
            throw cause;
        }
    }

    /** A script compiled once and run with fresh inputs, each time in a new global. */
    static final class Runner {
        private final ScriptEngine engine = new NashornScriptEngineBuilder().optimisticTypes(true).build();
        private final CompiledScript script;

        Runner(String source) {
            try {
                script = ((javax.script.Compilable) engine).compile(source);
            } catch (ScriptException e) {
                throw new IllegalStateException(e);
            }
        }

        void run(Object... namesAndValues) throws Throwable {
            ScriptContext context = new SimpleScriptContext();
            Bindings global = engine.createBindings();
            for (int i = 0; i < namesAndValues.length; i += 2) {
                global.put((String) namesAndValues[i], namesAndValues[i + 1]);
            }
            context.setBindings(global, ScriptContext.ENGINE_SCOPE);
            try {
                script.eval(context);
            } catch (ScriptException e) {
                rethrowUnexpected(e);
                // A JavaScript error the script let through: a broken invariant it checks
                throw new AssertionError(e.getMessage());
            }
        }
    }
}
