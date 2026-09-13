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

import org.monflabs.nashorn.performance.ScriptExecutor;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.tools.shell.Global;

public class RhinoExecutor extends ScriptExecutor {

    private final boolean compiled;
    private Context context;
    private Scriptable scope;
    private Script script;

    public RhinoExecutor(boolean compiled) {
        this.compiled = compiled;
    }

    @Override
    public ENGINE getEngine() {
        return compiled ? ENGINE.RHINO_COMPILED : ENGINE.RHINO_INTERPRETED;
    }

    @Override
    public void init(String code, String filePath) throws Exception {
        initEngine();
        parse(code, filePath);
    }

    @Override
    public void initEngine() throws Exception {
        context = Context.enter();
        context.setLanguageVersion(Context.VERSION_ES6);
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
        script.exec(context, scope);
    }
}
