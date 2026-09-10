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
        setLastScore(script.exec(context, scope));
    }
}
