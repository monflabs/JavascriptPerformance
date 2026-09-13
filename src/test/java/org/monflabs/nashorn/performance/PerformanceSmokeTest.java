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
package org.monflabs.nashorn.performance;

import static org.testng.Assert.assertTrue;

import org.monflabs.nashorn.performance.ScriptExecutor.ENGINE;
import org.testng.annotations.Test;

public class PerformanceSmokeTest {

    // Both Nashorns and both Rhino modes must always be able to run (none
    // needs a native library); GraalJS compiled and V8_JAVET are legitimately unavailable on a
    // plain JDK/uncovered platform and are asserted separately below.
    @Test
    public void runsUbenchOnAlwaysAvailableEngines() throws Exception {
        BenchmarkRunner runner = new BenchmarkRunner(
                ENGINE.NASHORN_MONFLABS, ENGINE.NASHORN_OPENJDK,
                ENGINE.RHINO_INTERPRETED, ENGINE.RHINO_COMPILED);
        runner.setWarmupIterations(0);
        runner.setRunIterations(1);
        runner.runSuite("ubench");

        String csv = runner.getCollector().csv();
        assertTrue(csv.contains("ubench") || csv.length() > 0, "expected a non-empty report:\n" + csv);
        assertTrue(!csv.contains("FAILED"), "no engine should fail ubench:\n" + csv);

        String html = runner.getCollector().toHtmlReport();
        assertTrue(html.contains("<svg") && html.contains("ubench"), "expected charts in the HTML report:\n" + html);
        assertTrue(!html.contains("FAILED"), "no engine should fail ubench:\n" + html);
    }

    @Test
    public void graalJsCompiledReportsUnavailableRatherThanFailing() throws Exception {
        ScriptExecutor compiled = BenchmarkRunner.createEngine(ENGINE.GRAALJS_COMPILED);
        if (!compiled.isSupported()) {
            // Expected on a plain JDK with no GraalVM compiler - this is the "N/A" path, not a
            // test failure.
            return;
        }
        // A real GraalVM compiler is present: the engine must actually work.
        compiled.init("1 + 1;", "smoke.js");
        try {
            compiled.run();
        } finally {
            compiled.terminate();
        }
    }

    @Test
    public void v8JavetReportsUnavailableRatherThanFailing() throws Exception {
        ScriptExecutor v8 = BenchmarkRunner.createEngine(ENGINE.V8_JAVET);
        if (!v8.isSupported()) {
            // Expected on a platform the pom's OS-activated profiles don't cover - no native V8
            // binding on the classpath, which is the "N/A" path, not a test failure.
            return;
        }
        v8.init("1 + 1;", "smoke.js");
        try {
            v8.run();
        } finally {
            v8.terminate();
        }
    }
}
