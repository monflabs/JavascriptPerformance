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

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.monflabs.filesystem.memory.MemoryFileSystem;
import org.monflabs.galtajs.JSEnvironment;
import org.monflabs.galtajs.modules.JSInterpretedUnit;
import org.monflabs.galtajs.rt.builtins.GlobalThis;
import org.monflabs.galtajs.rt.transpiler.JSTranspiledUnit;
import org.monflabs.galtajs.rt.transpiler.TranspiledGlobalRuntimeContext;
import org.monflabs.galtajs.transpiler.JSTranspiler;
import org.monflabs.galtajs.transpiler.JSTranspilerOptions;
import org.monflabs.javacompiler.JavaCompiler;
import org.monflabs.javacompiler.JavaCompilerFactory;
import org.monflabs.nashorn.performance.ScriptExecutor;
import org.monflabs.util.path.PathClassLoader;

/**
 * GaltaJS's "compiled" mode: the script is transpiled to Java source, compiled in-memory (an
 * NIO {@link MemoryFileSystem}, never touching disk) and loaded as an ordinary class, so
 * {@link #run()} executes real JVM bytecode rather than walking the interpreter's AST.
 */
public class GaltaJSCompiledExecutor extends ScriptExecutor {

    private static final String RESULT_CLASS = "Object";

    private JSEnvironment env;
    private JSTranspiledUnit script;

    @Override
    public ENGINE getEngine() {
        return ENGINE.GALTAJS_COMPILED;
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
        String className = classNameFor(filePath);

        JSTranspilerOptions options = JSTranspilerOptions.newBuilder()
                .debugInformation(true)
                .sourceInCode(true)
                .splitCode(true)
                .build();
        JSTranspiler transpiler = new JSTranspiler(env, options);

        // No SCRIPT_ADDTOCACHE flag: bypass the script cache so this measures actual parse
        // work rather than a hashmap hit on repeat calls.
        JSInterpretedUnit parsed = env.createScript(code, filePath, 0);
        JSTranspiler.Result result = transpiler.compileResult(className, RESULT_CLASS, parsed.getProgram(), null);

        MemoryFileSystem memoryFs = MemoryFileSystem.newBuilder().build();
        Path srcFolder = Files.createDirectory(memoryFs.getPath("src"));
        Path targetFolder = Files.createDirectory(memoryFs.getPath("tgt"));
        Files.writeString(srcFolder.resolve(className + ".java"), result.getJavaCode(), StandardCharsets.UTF_8);

        try (JavaCompiler compiler = JavaCompilerFactory.newBuilder()
                .classLoader(getClass().getClassLoader())
                .sourceFolder(srcFolder, StandardCharsets.UTF_8)
                .targetFolder(targetFolder)
                .options(List.of("-Xdiags:verbose"))
                .build()) {
            compiler.compile(className);
        }

        PathClassLoader classLoader = new PathClassLoader(getClass().getClassLoader(), targetFolder);
        Constructor<?> ctor = classLoader.loadClass(className).getConstructor(JSEnvironment.class);
        script = (JSTranspiledUnit) ctor.newInstance(env);
        script.setSourceCode(code);
        script.setTranspilerMap(result.getTranspilerMap());
    }

    /** Turns a benchmark's file path into a legal Java identifier/class name. */
    private static String classNameFor(String filePath) {
        String name = filePath.endsWith(".js") ? filePath.substring(0, filePath.length() - 3) : filePath;
        return name.replace('/', '_').replace('-', '_').replace('.', '_');
    }

    @Override
    public void terminate() throws Exception {
        script = null;
        env = null;
    }

    @Override
    public void run() throws Exception {
        TranspiledGlobalRuntimeContext context = new TranspiledGlobalRuntimeContext(env, env.createExpressionExecutor());
        GlobalThis globalThis = context.getGlobalThis();
        globalThis.put("window", globalThis); // some Octane benchmarks assume a jQuery-style window
        // Unlike the interpreter's executeWithContext(), a transpiled unit's runValue() only ever
        // reflects an explicit `return` (JSTranspiledRuntimeContext.setReturnValue() is called
        // from nowhere but ASTReturn) - a bare top-level expression statement, such as Octane/
        // v8-benchmarks-v6 run.js's trailing `lastScore;`, is never captured. So Score is always
        // null for this engine/mode; wall/cpu time remain valid.
        setLastScore(script.runValue(context));
    }
}
