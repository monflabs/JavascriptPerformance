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

/** Compiles and runs a fuzzed regular expression, with fuzzed flags, on a fuzzed subject, with the Rhino fork's class compiler and interpreter in turn. */
public final class RhinoRegExp {

    private static final RhinoHarness.Runner RUNNER = new RhinoHarness.Runner(Checks.REGEXP);

    private RhinoRegExp() {
    }

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        boolean interpreted = data.consumeBoolean();
        String[] in = Checks.regexpInputs(data);
        RUNNER.run(interpreted, "pattern", in[0], "flags", in[1], "subject", in[2]);
    }
}
