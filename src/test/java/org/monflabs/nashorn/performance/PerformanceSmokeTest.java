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
package org.monflabs.nashorn.performance;

import static org.testng.Assert.assertTrue;

import org.monflabs.nashorn.performance.ScriptExecutor.ENGINE;
import org.testng.annotations.Test;

public class PerformanceSmokeTest {

    // Both Nashorns and both Rhino modes must always be able to run; GraalJS compiled is
    // legitimately unavailable on a plain JDK and is asserted separately below.
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
}
