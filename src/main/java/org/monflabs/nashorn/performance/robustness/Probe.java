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
package org.monflabs.nashorn.performance.robustness;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;

import com.caoccao.javet.exceptions.BaseJavetScriptingException;
import com.caoccao.javet.interop.V8Host;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.interop.options.V8RuntimeOptions;
import org.graalvm.polyglot.PolyglotException;
import org.monflabs.nashorn.api.scripting.NashornException;
import org.monflabs.nashorn.api.scripting.NashornScriptEngineBuilder;
import org.monflabs.nashorn.performance.ScriptExecutor.ENGINE;

/**
 * Runs one script on one engine, in a realm of its own, and says how it ended.
 *
 * <p>Unlike a {@link org.monflabs.nashorn.performance.ScriptExecutor}, which times a benchmark, a
 * probe classifies: an uncaught JavaScript exception is an ordinary {@link Outcome.Kind#THROW},
 * while any other Java exception that escapes the engine is a {@link Outcome.Kind#CRASH}. Output
 * is collected by a {@code print} the {@link #PRELUDE} defines in the realm, the same function
 * on every engine, so that outputs can be compared.
 */
abstract class Probe implements AutoCloseable {

    /**
     * Installed in every realm before the script. Capped, so that a script that prints in a loop
     * cannot exhaust the heap of the child. {@code String} is captured, in case the script
     * replaces the global.
     */
    static final String PRELUDE = """
            var __out = '';
            var print = (function (S) {
              return function () {
                var s = '';
                for (var i = 0; i < arguments.length; i++) { if (i) s += ' '; s += S(arguments[i]); }
                if (__out.length < 1000000) __out += (__out.length ? '\\n' : '') + s;
              };
            })(String);
            var console = { log: print, error: print, info: print, warn: print };
            """;

    /**
     * Read back with nothing a script could have replaced: the output is a string, built with
     * {@code +} only, so a test that redefines {@code Array.prototype.join} or {@code push} still
     * reports what it printed.
     */
    static final String OUTPUT = "__out";

    abstract ENGINE engine();

    /** Runs {@code prelude} then {@code source} in a fresh realm. */
    abstract Outcome run(String prelude, String source, String name);

    @Override
    public void close() {
    }

    static Probe create(ENGINE engine) {
        return switch (engine) {
            case RHINO_MONFLABS_COMPILED -> new MonflabsRhino(true);
            case RHINO_MONFLABS_INTERPRETED -> new MonflabsRhino(false);
            case RHINO_COMPILED -> new StockRhino(true);
            case RHINO_INTERPRETED -> new StockRhino(false);
            case NASHORN_MONFLABS -> new MonflabsNashorn();
            case V8_JAVET -> new V8();
            case GRAALJS_INTERPRETED -> new GraalJS();
            default -> throw new IllegalArgumentException("no robustness probe for " + engine);
        };
    }

    static Outcome crash(Throwable t) {
        return new Outcome(Outcome.Kind.CRASH, "", describe(t));
    }

    static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder(t.getClass().getName()).append(": ").append(t.getMessage());
        StackTraceElement[] trace = t.getStackTrace();
        for (int i = 0; i < Math.min(trace.length, 12); i++) {
            sb.append("\n\tat ").append(trace[i]);
        }
        for (Throwable c = t.getCause(); c != null && c != t; c = c.getCause()) {
            sb.append("\nCaused by: ").append(c.getClass().getName()).append(": ").append(c.getMessage());
            StackTraceElement[] ct = c.getStackTrace();
            for (int i = 0; i < Math.min(ct.length, 8); i++) {
                sb.append("\n\tat ").append(ct[i]);
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return sb.toString();
    }

    /** The Monflabs Rhino fork, in its ES2026 language version. */
    private static final class MonflabsRhino extends Probe {
        private final boolean compiled;

        MonflabsRhino(boolean compiled) {
            this.compiled = compiled;
        }

        @Override
        ENGINE engine() {
            return compiled ? ENGINE.RHINO_MONFLABS_COMPILED : ENGINE.RHINO_MONFLABS_INTERPRETED;
        }

        @Override
        Outcome run(String prelude, String source, String name) {
            org.monflabs.mozilla.javascript.Context cx =
                    org.monflabs.mozilla.javascript.ContextFactory.getGlobal().enterContext();
            try {
                cx.setLanguageVersion(org.monflabs.mozilla.javascript.Context.VERSION_ECMASCRIPT);
                cx.setInterpretedMode(!compiled);
                org.monflabs.mozilla.javascript.TopLevel scope = cx.initStandardObjects();
                cx.evaluateString(scope, prelude, "prelude.js", 1, null);
                Outcome.Kind kind = Outcome.Kind.OK;
                String error = "";
                try {
                    cx.evaluateString(scope, source, name, 1, null);
                    cx.processMicrotasks();
                } catch (org.monflabs.mozilla.javascript.WrappedException e) {
                    return crash(e.getWrappedException());
                } catch (org.monflabs.mozilla.javascript.RhinoException e) {
                    kind = Outcome.Kind.THROW;
                    error = e.details();
                }
                String output = org.monflabs.mozilla.javascript.Context.toString(
                        cx.evaluateString(scope, OUTPUT, "output.js", 1, null));
                return new Outcome(kind, output, error);
            } catch (Throwable t) {
                return crash(t);
            } finally {
                org.monflabs.mozilla.javascript.Context.exit();
            }
        }
    }

    /** Upstream Rhino, in {@code VERSION_ES6}: a reference for what the fork changed. */
    private static final class StockRhino extends Probe {
        private final boolean compiled;

        StockRhino(boolean compiled) {
            this.compiled = compiled;
        }

        @Override
        ENGINE engine() {
            return compiled ? ENGINE.RHINO_COMPILED : ENGINE.RHINO_INTERPRETED;
        }

        @Override
        Outcome run(String prelude, String source, String name) {
            org.mozilla.javascript.Context cx = org.mozilla.javascript.Context.enter();
            try {
                cx.setLanguageVersion(org.mozilla.javascript.Context.VERSION_ES6);
                cx.setInterpretedMode(!compiled);
                org.mozilla.javascript.Scriptable scope = cx.initStandardObjects();
                cx.evaluateString(scope, prelude, "prelude.js", 1, null);
                Outcome.Kind kind = Outcome.Kind.OK;
                String error = "";
                try {
                    cx.evaluateString(scope, source, name, 1, null);
                    cx.processMicrotasks();
                } catch (org.mozilla.javascript.WrappedException e) {
                    return crash(e.getWrappedException());
                } catch (org.mozilla.javascript.RhinoException e) {
                    kind = Outcome.Kind.THROW;
                    error = e.details();
                }
                String output = org.mozilla.javascript.Context.toString(
                        cx.evaluateString(scope, OUTPUT, "output.js", 1, null));
                return new Outcome(kind, output, error);
            } catch (Throwable t) {
                return crash(t);
            } finally {
                org.mozilla.javascript.Context.exit();
            }
        }
    }

    /** The Monflabs Nashorn fork, with optimistic types and the event loop, as in the benchmarks. */
    private static final class MonflabsNashorn extends Probe {
        private final ScriptEngine engine = new NashornScriptEngineBuilder()
                .optimisticTypes(true)
                .eventLoop(true)
                .build();

        @Override
        ENGINE engine() {
            return ENGINE.NASHORN_MONFLABS;
        }

        @Override
        Outcome run(String prelude, String source, String name) {
            try {
                ScriptContext context = new SimpleScriptContext();
                Bindings global = engine.createBindings();
                context.setBindings(global, ScriptContext.ENGINE_SCOPE);
                engine.eval(prelude, context);
                Outcome.Kind kind = Outcome.Kind.OK;
                String error = "";
                try {
                    context.setAttribute(ScriptEngine.FILENAME, name, ScriptContext.ENGINE_SCOPE);
                    engine.eval(source, context);
                } catch (ScriptException e) {
                    Throwable cause = e.getCause();
                    if (cause != null && !(cause instanceof NashornException)) {
                        return crash(cause);
                    }
                    kind = Outcome.Kind.THROW;
                    error = e.getMessage();
                } catch (NashornException e) {
                    kind = Outcome.Kind.THROW;
                    error = e.getMessage();
                }
                String output = String.valueOf(engine.eval(OUTPUT, context));
                return new Outcome(kind, output, error);
            } catch (Throwable t) {
                return crash(t);
            }
        }
    }

    /** V8 through Javet: the reference engine. */
    private static final class V8 extends Probe {
        static {
            // Sloppy mode exists, as in every other engine (see JavetExecutor)
            V8RuntimeOptions.V8_FLAGS.setUseStrict(false);
        }

        @Override
        ENGINE engine() {
            return ENGINE.V8_JAVET;
        }

        @Override
        Outcome run(String prelude, String source, String name) {
            try (V8Runtime v8 = V8Host.getV8Instance().createV8Runtime()) {
                v8.getExecutor(prelude).setResourceName("prelude.js").executeVoid();
                Outcome.Kind kind = Outcome.Kind.OK;
                String error = "";
                try {
                    v8.getExecutor(source).setResourceName(name).executeVoid();
                    v8.await();
                } catch (BaseJavetScriptingException e) {
                    kind = Outcome.Kind.THROW;
                    error = e.getMessage();
                }
                String output = v8.getExecutor(OUTPUT).executeString();
                return new Outcome(kind, output, error);
            } catch (Throwable t) {
                return crash(t);
            }
        }
    }

    /** GraalJS without a compiler: a second reference, slower than V8. */
    private static final class GraalJS extends Probe {
        private final org.graalvm.polyglot.Engine engine = org.graalvm.polyglot.Engine.newBuilder()
                .option("engine.WarnInterpreterOnly", "false")
                .build();

        @Override
        ENGINE engine() {
            return ENGINE.GRAALJS_INTERPRETED;
        }

        @Override
        Outcome run(String prelude, String source, String name) {
            try (org.graalvm.polyglot.Context context =
                    org.graalvm.polyglot.Context.newBuilder("js").engine(engine).build()) {
                context.eval("js", prelude);
                Outcome.Kind kind = Outcome.Kind.OK;
                String error = "";
                try {
                    context.eval(org.graalvm.polyglot.Source.newBuilder("js", source, name).build());
                } catch (PolyglotException e) {
                    if (e.isInternalError() || e.isHostException()) {
                        return crash(e);
                    }
                    kind = Outcome.Kind.THROW;
                    error = e.getMessage();
                }
                String output = context.eval("js", OUTPUT).asString();
                return new Outcome(kind, output, error);
            } catch (Throwable t) {
                return crash(t);
            }
        }

        @Override
        public void close() {
            engine.close();
        }
    }
}
