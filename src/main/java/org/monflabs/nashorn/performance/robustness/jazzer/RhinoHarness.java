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

import org.monflabs.mozilla.javascript.Context;
import org.monflabs.mozilla.javascript.ContextFactory;
import org.monflabs.mozilla.javascript.EvaluatorException;
import org.monflabs.mozilla.javascript.JavaScriptException;
import org.monflabs.mozilla.javascript.EcmaError;
import org.monflabs.mozilla.javascript.Script;
import org.monflabs.mozilla.javascript.TopLevel;

/**
 * The Monflabs Rhino fork under a fuzz target: a context whose scripts stop after a budget of
 * instructions, so that a fuzzed program that loops ends as an ordinary {@link Budget} error
 * rather than as a Jazzer timeout.
 *
 * <p>What a target may see from the engine is a JavaScript error: a syntax error, an exception
 * the script throws, the budget. Anything else that escapes, an {@code IllegalStateException}
 * from the class compiler, a {@code NullPointerException}, a {@code StackOverflowError}, is a
 * finding, and Jazzer reports it.
 */
final class RhinoHarness {

    /** Thrown by the instruction observer when a script has run long enough. */
    static final class Budget extends Error {
        private static final long serialVersionUID = 1L;

        Budget() {
            super("budget", null, false, false);
        }
    }

    private static final ContextFactory FACTORY = new ContextFactory() {
        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            throw new Budget();
        }
    };

    static {
        // A compiler bug that makes the fork fall back to its interpreter is a finding
        System.setProperty("rhino.failOnCompileFallback", "true");
    }

    private RhinoHarness() {
    }

    /** Enters a context for the fork's ES2026 language version, in one of its two backends. */
    static Context enter(boolean interpreted, int instructions) {
        Context cx = FACTORY.enterContext();
        cx.setLanguageVersion(Context.VERSION_ECMASCRIPT);
        cx.setInterpretedMode(interpreted);
        if (instructions > 0) {
            cx.setGenerateObserverCount(true);
            cx.setInstructionObserverThreshold(instructions);
        }
        return cx;
    }

    /** Compiles a source, accepting a syntax error. */
    static Script compile(Context cx, String source) {
        try {
            return cx.compileString(source, "fuzz.js", 1, null);
        } catch (EvaluatorException | EcmaError e) {
            return null;
        }
    }

    /**
     * A checking script compiled once per backend and run on fresh inputs, each time in a new
     * realm. A JavaScript error escaping it is a broken invariant it checks.
     */
    static final class Runner {
        private final Script compiled;
        private final Script interpreted;

        Runner(String source) {
            compiled = compileChecked(source, false);
            interpreted = compileChecked(source, true);
        }

        private static Script compileChecked(String source, boolean interpreted) {
            Context cx = enter(interpreted, 0);
            try {
                return cx.compileString(source, "check.js", 1, null);
            } finally {
                Context.exit();
            }
        }

        void run(boolean interpreted, Object... namesAndValues) {
            Context cx = enter(interpreted, 0);
            try {
                TopLevel scope = cx.initStandardObjects();
                for (int i = 0; i < namesAndValues.length; i += 2) {
                    scope.put((String) namesAndValues[i], scope, namesAndValues[i + 1]);
                }
                try {
                    (interpreted ? this.interpreted : compiled).exec(cx, scope, scope.getGlobalThis());
                } catch (EcmaError | JavaScriptException e) {
                    throw new AssertionError(e.details(), e);
                }
            } finally {
                Context.exit();
            }
        }
    }

    /** Runs a compiled script in a fresh realm, accepting any JavaScript outcome. */
    static void exec(Context cx, Script script) {
        TopLevel scope = cx.initStandardObjects();
        try {
            script.exec(cx, scope, scope.getGlobalThis());
            cx.processMicrotasks();
        } catch (EcmaError | JavaScriptException | EvaluatorException | Budget e) {
            // an ordinary end
        }
    }
}
