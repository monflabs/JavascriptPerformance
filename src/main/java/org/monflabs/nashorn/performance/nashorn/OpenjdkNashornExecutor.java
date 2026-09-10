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
package org.monflabs.nashorn.performance.nashorn;

import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;

import org.monflabs.nashorn.performance.ScriptExecutor;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

public class OpenjdkNashornExecutor extends ScriptExecutor {

    private ScriptEngine engine;
    private CompiledScript script;

    @Override
    public ENGINE getEngine() {
        return ENGINE.NASHORN_OPENJDK;
    }

    @Override
    public void init(String code, String filePath) throws Exception {
        initEngine();
        parse(code, filePath);
    }

    @Override
    public void initEngine() throws Exception {
        NashornScriptEngineFactory factory = new NashornScriptEngineFactory();
        engine = factory.getScriptEngine("--optimistic-types=true", "--language=es6");
        String consolePolyfill = "var console = { log: function() {"
                + " print(Array.prototype.join.call(arguments, ' '));"
                + " } };";
        engine.eval(consolePolyfill);
    }

    @Override
    public void parse(String code, String filePath) throws Exception {
        script = ((Compilable) engine).compile(code);
    }

    @Override
    public void terminate() throws Exception {
    }

    @Override
    public void run() throws Exception {
        setLastScore(script.eval());
    }
}
