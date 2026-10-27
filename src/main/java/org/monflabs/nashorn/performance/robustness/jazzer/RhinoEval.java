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

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import org.monflabs.mozilla.javascript.Context;
import org.monflabs.mozilla.javascript.Script;

/**
 * Compiles and runs fuzzed source with the Rhino fork, within a budget of instructions. Reaches
 * the runtime as well as the compiler: built-ins called with odd arguments, the generated code
 * of whatever parses.
 */
public final class RhinoEval {

    private RhinoEval() {
    }

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        boolean interpreted = data.consumeBoolean();
        String source = data.consumeRemainingAsString();
        Context cx = RhinoHarness.enter(interpreted, 100_000);
        try {
            Script script = RhinoHarness.compile(cx, source);
            if (script != null) {
                RhinoHarness.exec(cx, script);
            }
        } finally {
            Context.exit();
        }
    }
}
