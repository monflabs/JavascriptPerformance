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

import javax.script.Compilable;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import org.monflabs.nashorn.api.scripting.NashornException;
import org.monflabs.nashorn.api.scripting.NashornScriptEngineBuilder;

/**
 * Parses and compiles fuzzed source with the Monflabs Nashorn fork. Nashorn compiles a
 * function's body when it is first called, so this reaches the parser and the compilation of
 * the program's own code; a syntax error is expected, anything else that escapes is a finding.
 */
public final class NashornCompile {

    private static ScriptEngine engine;

    private NashornCompile() {
    }

    public static void fuzzerInitialize() {
        engine = new NashornScriptEngineBuilder().optimisticTypes(true).build();
    }

    public static void fuzzerTestOneInput(FuzzedDataProvider data) throws Throwable {
        String source = data.consumeRemainingAsString();
        try {
            ((Compilable) engine).compile(source);
        } catch (ScriptException e) {
            NashornTargets.rethrowUnexpected(e);
        } catch (NashornException e) {
            // a syntax error
        }
    }
}
