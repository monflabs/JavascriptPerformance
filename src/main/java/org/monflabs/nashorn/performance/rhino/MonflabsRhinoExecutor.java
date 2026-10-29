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
package org.monflabs.nashorn.performance.rhino;

import org.monflabs.mozilla.javascript.Context;
import org.monflabs.mozilla.javascript.Script;
import org.monflabs.mozilla.javascript.TopLevel;
import org.monflabs.mozilla.javascript.tools.shell.Global;
import org.monflabs.nashorn.performance.ScriptExecutor;

/**
 * The Monflabs Rhino fork (package {@code org.monflabs.mozilla}, so it sits next to upstream
 * Rhino on one classpath), run in its ECMAScript 2026 language version.
 */
public class MonflabsRhinoExecutor extends ScriptExecutor {

    private final boolean compiled;
    private final boolean ecmascript;
    private Context context;
    private TopLevel scope;
    private Script script;

    public MonflabsRhinoExecutor(boolean compiled) {
        this(compiled, true);
    }

    /**
     * @param ecmascript {@code VERSION_ECMASCRIPT} (ES2026, the fork's own language) when true,
     *                   {@code VERSION_ES6} (upstream's language and behavior) when false
     */
    public MonflabsRhinoExecutor(boolean compiled, boolean ecmascript) {
        this.compiled = compiled;
        this.ecmascript = ecmascript;
    }

    @Override
    public ENGINE getEngine() {
        if (ecmascript) {
            return compiled ? ENGINE.RHINO_MONFLABS_COMPILED : ENGINE.RHINO_MONFLABS_INTERPRETED;
        }
        return compiled ? ENGINE.RHINO_MONFLABS_ES6_COMPILED : ENGINE.RHINO_MONFLABS_ES6_INTERPRETED;
    }

    @Override
    public void init(String code, String filePath) throws Exception {
        initEngine();
        parse(code, filePath);
    }

    @Override
    public void initEngine() throws Exception {
        context = Context.enter();
        context.setLanguageVersion(ecmascript ? Context.VERSION_ECMASCRIPT : Context.VERSION_ES6);
        context.setInterpretedMode(!compiled);
        scope = new Global(context);
    }

    @Override
    public void parse(String code, String filePath) throws Exception {
        script = context.compileString(code, filePath, 1, null);
    }

    @Override
    public void terminate() throws Exception {
        if (context != null) {
            Context.exit();
        }
        context = null;
        scope = null;
        script = null;
    }

    @Override
    public void run() throws Exception {
        script.exec(context, scope, scope.getGlobalThis());
        context.processMicrotasks();
    }
}
