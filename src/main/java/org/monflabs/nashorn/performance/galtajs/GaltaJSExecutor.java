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
package org.monflabs.nashorn.performance.galtajs;

import java.lang.reflect.Constructor;

import org.monflabs.galtajs.JSEnvironment;
import org.monflabs.galtajs.environments.JavaScriptEnvironment;
import org.monflabs.galtajs.library.rhino.RhinoShellLibrary;
import org.monflabs.galtajs.modules.JSInterpretedUnit;
import org.monflabs.galtajs.rt.interpreter.InterpretedGlobalRuntimeContext;
import org.monflabs.galtajs.rt.transpiler.JSTranspiledUnit;
import org.monflabs.galtajs.rt.transpiler.TranspiledGlobalRuntimeContext;
import org.monflabs.galtajs.transpiler.JSTranspiler;
import org.monflabs.galtajs.transpiler.JSTranspilerOptions;
import org.monflabs.javacompiler.FactoryClassLoader;
import org.monflabs.javacompiler.JavaCompiler;
import org.monflabs.javacompiler.JavaCompilerFactory;
import org.monflabs.javacompiler.factory.MapSourceFactory;
import org.monflabs.javacompiler.factory.MapTargetFactory;
import org.monflabs.nashorn.performance.ScriptExecutor;

/**
 * Monflabs GaltaJS. Interpreted mode walks the AST; compiled mode transpiles the script to Java
 * source and compiles it in memory with javac (JIT-compiled by the JVM like any other class).
 * Transpiling and javac run in {@link #parse}, never in the timed {@link #run}.
 */
public class GaltaJSExecutor extends ScriptExecutor {

    private static final String CLASS_NAME = "GaltaBenchmark";

    private final boolean compiled;
    private JSEnvironment env;
    private JSInterpretedUnit interpreted;
    private InterpretedGlobalRuntimeContext interpretedContext;
    private JSTranspiledUnit transpiled;
    private TranspiledGlobalRuntimeContext transpiledContext;

    public GaltaJSExecutor(boolean compiled) {
        this.compiled = compiled;
    }

    @Override
    public ENGINE getEngine() {
        return compiled ? ENGINE.GALTAJS_COMPILED : ENGINE.GALTAJS_INTERPRETED;
    }

    @Override
    public void init(String code, String filePath) throws Exception {
        initEngine();
        parse(code, filePath);
    }

    @Override
    public void initEngine() throws Exception {
        // The RhinoShell library provides the global print() every benchmark file ends with.
        env = JavaScriptEnvironment.newBuilder()
                .registerLibrary(new RhinoShellLibrary())
                .build();
    }

    @Override
    public void parse(String code, String filePath) throws Exception {
        interpreted = env.createScript(code, filePath);
        if (!compiled) {
            interpretedContext = new InterpretedGlobalRuntimeContext(env, env.createProgramExecutor());
            return;
        }
        JSTranspilerOptions options = JSTranspilerOptions.newBuilder().build();
        String javaCode = new JSTranspiler(env, options)
                .compileResult(CLASS_NAME, "Object", interpreted.getProgram(), null)
                .getJavaCode();
        MapTargetFactory targets = new MapTargetFactory();
        FactoryClassLoader classLoader =
                new FactoryClassLoader(GaltaJSExecutor.class.getClassLoader(), targets);
        try (JavaCompiler javac = JavaCompilerFactory.newBuilder()
                .classLoader(classLoader)
                .sourceFactory(MapSourceFactory.of(CLASS_NAME + ".java", javaCode))
                .targetFactory(targets)
                .build()) {
            javac.compile(CLASS_NAME);
        }
        Constructor<?> ctor = classLoader.loadClass(CLASS_NAME)
                .getConstructor(JSEnvironment.class, String.class);
        transpiled = (JSTranspiledUnit) ctor.newInstance(env, null);
        transpiledContext = new TranspiledGlobalRuntimeContext(env, env.createProgramExecutor());
    }

    @Override
    public void terminate() throws Exception {
        env = null;
        interpreted = null;
        interpretedContext = null;
        transpiled = null;
        transpiledContext = null;
    }

    @Override
    public void run() throws Exception {
        if (compiled) {
            transpiled.runValue(transpiledContext);
        } else {
            interpreted.executeWithContext(interpretedContext);
        }
    }
}
