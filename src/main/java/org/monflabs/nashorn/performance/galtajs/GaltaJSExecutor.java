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
package org.monflabs.nashorn.performance.galtajs;

import org.monflabs.galtajs.JSEnvironment;
import org.monflabs.galtajs.modules.JSInterpretedUnit;
import org.monflabs.galtajs.rt.builtins.GlobalThis;
import org.monflabs.galtajs.rt.interpreter.InterpretedGlobalRuntimeContext;
import org.monflabs.nashorn.performance.ScriptExecutor;

/** GaltaJS's AST-walking interpreter - no bytecode/Java source generated. */
public class GaltaJSExecutor extends ScriptExecutor {

    private JSEnvironment env;
    private JSInterpretedUnit script;

    @Override
    public ENGINE getEngine() {
        return ENGINE.GALTAJS_INTERPRETED;
    }

    @Override
    public void init(String code, String filePath) throws Exception {
        initEngine();
        parse(code, filePath);
    }

    @Override
    public void initEngine() throws Exception {
        env = GaltaEnvironment.create();
    }

    @Override
    public void parse(String code, String filePath) throws Exception {
        // No SCRIPT_ADDTOCACHE flag: bypass the script cache so this measures actual parse
        // work rather than a hashmap hit on repeat calls.
        script = env.createScript(code, filePath, 0);
    }

    @Override
    public void terminate() throws Exception {
        script = null;
        env = null;
    }

    @Override
    public void run() throws Exception {
        InterpretedGlobalRuntimeContext context = new InterpretedGlobalRuntimeContext(env, env.createProgramExecutor());
        GlobalThis globalThis = context.getGlobalThis();
        globalThis.put("window", globalThis); // some Octane benchmarks assume a jQuery-style window
        script.executeWithContext(context);
    }
}
