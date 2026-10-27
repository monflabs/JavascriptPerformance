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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Generates random programs whose output is fully determined by the specification, so that two
 * correct engines print the same thing.
 *
 * <p>Everything a program prints goes through {@code __p}, which shows a value without relying on
 * anything an engine may choose: numbers exactly ({@code -0} included), strings quoted, objects
 * by their own enumerable properties and their prototype's constructor name, functions by name
 * and length, and a caught exception by its constructor's name only, since messages are the
 * engine's own. Implementation-defined corners are not generated at all: no {@code Date}, no
 * {@code Math.random}, no locale methods, no transcendental {@code Math} functions, no
 * {@code Function.prototype.toString}, no non-integer {@code toString(radix)}, no comparator that
 * could be inconsistent, {@code **} only on small integers.
 *
 * <p>Programs terminate and stay small: loops have literal bounds that the body cannot change,
 * every generated function gives up past a recursion depth, and every assignment goes through
 * {@code __c}, which caps a string or an array at 1,000 elements. Nested loops (a {@code for-in} over a
 * 1,000-character string among them) and functions with loops of their own still multiply, so every loop iteration and every call also counts
 * against a budget ({@code __b}): past 50,000 steps each one throws a {@code RangeError}, at
 * the same point on every engine.
 *
 * <p>A program is a header (the helpers and the declarations) and a list of top-level chunks, each
 * a statement in a {@code try} of its own, so that an exception ends one chunk, not the program.
 * The chunks are separated by a {@value #CHUNK} line, which the reducer splits on.
 */
final class ProgramGenerator {

    static final String CHUNK = "//#chunk";

    static final String HELPERS = """
            var __d = 0, __n = 0;
            function __b() { if (++__n > 50000) throw new RangeError('budget'); return true; }
            function __show(v, d) {
              switch (typeof v) {
                case 'number': return Object.is(v, -0) ? '-0' : String(v);
                case 'bigint': return v + 'n';
                case 'string': return JSON.stringify(v);
                case 'symbol': return 'Symbol(' + (v.description === undefined ? '' : v.description) + ')';
                case 'undefined': return 'undefined';
                case 'boolean': return String(v);
                case 'function': return 'function ' + v.name + '/' + v.length;
              }
              if (v === null) return 'null';
              if (d > 2) return '{..}';
              if (Array.isArray(v)) {
                var a = [];
                for (var i = 0; i < v.length && i < 20; i++) a.push(i in v ? __show(v[i], d + 1) : '<hole>');
                return '[' + a.join(',') + (v.length > 20 ? ',..' + v.length : '') + ']';
              }
              if (v instanceof Map) {
                var m = [];
                v.forEach(function (x, k) { if (m.length < 20) m.push(__show(k, d + 1) + '=>' + __show(x, d + 1)); });
                return 'Map{' + m.join(',') + '}';
              }
              if (v instanceof Set) {
                var s = [];
                v.forEach(function (x) { if (s.length < 20) s.push(__show(x, d + 1)); });
                return 'Set{' + s.join(',') + '}';
              }
              var p = Object.getPrototypeOf(v), tag = p === null ? 'null' : p === Object.prototype ? ''
                  : typeof p.constructor === 'function' ? p.constructor.name : '?';
              var keys = Object.keys(v), r = [];
              for (var j = 0; j < keys.length && j < 20; j++) {
                var x;
                try { x = __show(v[keys[j]], d + 1); } catch (e) { x = '!' + __err(e); }
                r.push(JSON.stringify(keys[j]) + ':' + x);
              }
              return tag + '{' + r.join(',') + '}';
            }
            function __err(e) {
              if (e !== null && typeof e === 'object' && typeof e.constructor === 'function') return e.constructor.name;
              return typeof e === 'object' || typeof e === 'function' ? 'object' : __show(e, 3);
            }
            function __p(v) { var s; try { s = __show(v, 0); } catch (e) { s = '!' + __err(e); } print(s); }
            function __t(e) { print('throw ' + __err(e)); }
            function __c(v) {
              if (typeof v === 'string' && v.length > 1000) return v.slice(0, 1000);
              if (Array.isArray(v) && v.length > 1000) return v.slice(0, 1000);
              return v;
            }
            """;

    private static final String[] NUMBERS = {"0", "-0", "1", "-1", "2", "3", "7", "10", "0.5", "-2.5",
        "1.5", "255", "256", "65535", "2147483647", "-2147483648", "4294967295", "4294967296",
        "9007199254740991", "1e21", "1e-7", "123.456", "0.1", "NaN", "Infinity", "-Infinity", "0x1f",
        "1_000", "5e-324", "1.7976931348623157e308"};
    private static final String[] STRINGS = {"\"\"", "\"a\"", "\"abc\"", "\"10\"", "\" 1 \"", "\"-0\"",
        "\"0x10\"", "\"1e3\"", "\"Hello, World\"", "\"\\u00e9t\\u00e9\"", "\"\\ud83d\\ude00\"", "\"a,b,,c\"",
        "\"NaN\"", "\"true\"", "\"length\"", "\"__proto__\"", "\"x\"", "\"y\"", "\"\\n\\t\"", "\"aAbB\""};
    private static final String[] BIGINTS = {"0n", "1n", "-1n", "7n", "(2n ** 64n)", "(-(2n ** 63n))", "123456789012345678901234567890n"};
    private static final String[] BINARY = {"+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>", ">>>",
        "==", "!=", "===", "!==", "<", ">", "<=", ">=", "&&", "||", "??", ","};
    private static final String[] UNARY = {"- ", "+ ", "!", "~", "typeof ", "void "};
    private static final String[] COMPOUND = {"+=", "-=", "*=", "|=", "&=", "^=", "<<=", ">>=", ">>>=",
        "&&=", "||=", "??="};
    private static final String[] PROPS = {"x", "y", "length", "0", "1", "p", "q", "constructor", "name", "size"};

    private final Random random;
    private final int globals;
    private final List<String> functions = new ArrayList<>();
    private final List<String> classes = new ArrayList<>();
    private final List<String> generators = new ArrayList<>();
    /** Names in scope where an expression is generated: globals, then the locals of the current body. */
    private final List<String> locals = new ArrayList<>();
    /** The locals that count a loop's iterations, which nothing may assign. */
    private final java.util.Set<String> counters = new java.util.HashSet<>(java.util.List.of("i", "n", "this.x"));
    private int depth;
    private int loopDepth;
    private int labels;

    ProgramGenerator(long seed) {
        this.random = new Random(seed);
        this.globals = 4 + random.nextInt(5);
    }

    /** The program's text: the header, then the chunks after {@value #CHUNK} lines. */
    String generate() {
        boolean strict = random.nextInt(3) == 0;
        StringBuilder header = new StringBuilder();
        if (strict) {
            header.append("'use strict';\n");
        }
        header.append(HELPERS);
        for (int i = 0; i < globals; i++) {
            header.append(random.nextBoolean() ? "var " : "let ").append('v').append(i).append(" = ")
                    .append(literalOfAnyType()).append(";\n");
        }
        int nFunctions = 1 + random.nextInt(3);
        for (int i = 0; i < nFunctions; i++) {
            header.append(function("f" + i)).append('\n');
            functions.add("f" + i);
        }
        int nGenerators = random.nextInt(2);
        for (int i = 0; i < nGenerators; i++) {
            header.append(generatorFunction("g" + i)).append('\n');
            generators.add("g" + i);
        }
        int nClasses = random.nextInt(3);
        for (int i = 0; i < nClasses; i++) {
            header.append(classDeclaration("C" + i, i > 0 && random.nextBoolean() ? "C" + (i - 1) : null)).append('\n');
            classes.add("C" + i);
        }
        StringBuilder program = new StringBuilder(header);
        int chunks = 10 + random.nextInt(20);
        for (int i = 0; i < chunks; i++) {
            program.append(CHUNK).append('\n')
                    .append("try { ").append(statement()).append(" } catch (e) { __t(e); }\n");
        }
        return program.toString();
    }

    // ---- declarations ----

    private String function(String name) {
        List<String> saved = new ArrayList<>(locals);
        locals.clear();
        locals.add("a");
        locals.add("b");
        String body = statements(1 + random.nextInt(3));
        String result = expression();
        locals.clear();
        locals.addAll(saved);
        String params = random.nextInt(4) == 0 ? "a, b = " + literal() : random.nextInt(5) == 0 ? "a, ...b" : "a, b";
        return "function " + name + "(" + params + ") { if (__d > 20) return a; __d++; try { __b(); " + body
                + " return " + result + "; } finally { __d--; } }";
    }

    private String generatorFunction(String name) {
        List<String> saved = new ArrayList<>(locals);
        locals.clear();
        locals.add("n");
        locals.add("i");
        String yielded = expression();
        String extra = random.nextBoolean() ? " yield* [" + expression() + ", " + expression() + "];" : "";
        locals.clear();
        locals.addAll(saved);
        return "function* " + name + "(n) { for (let i = 0; i < n && i < 8 && __b(); i++) { yield " + yielded + "; }"
                + extra + " return " + literal() + "; }";
    }

    private String classDeclaration(String name, String parent) {
        StringBuilder sb = new StringBuilder("class ").append(name);
        if (parent != null) {
            sb.append(" extends ").append(parent);
        }
        sb.append(" {");
        List<String> saved = new ArrayList<>(locals);
        locals.clear();
        locals.add("a");
        locals.add("this.x");
        if (random.nextBoolean()) {
            sb.append(" #c = ").append(literal()).append(";");
            sb.append(" bump() { return this.#c = __c(this.#c + 1); }");
            sb.append(" static has(o) { return #c in o; }");
        }
        if (random.nextBoolean()) {
            sb.append(" static count = ").append(literal()).append(";");
        }
        if (random.nextBoolean()) {
            sb.append(" w = ").append(expression()).append(";");
        }
        sb.append(" constructor(a) {");
        if (parent != null) {
            sb.append(" super(a);");
        }
        locals.remove("this.x");
        sb.append(" this.x = __c(").append(expression()).append(");");
        locals.add("this.x");
        sb.append(" }");
        sb.append(" m(a) { return ").append(parent != null && random.nextBoolean() ? "super.m(a) + " : "")
                .append(expression()).append("; }");
        if (random.nextBoolean()) {
            sb.append(" get g() { return ").append(expression()).append("; }");
            sb.append(" set g(a) { this.x = __c(a); }");
        }
        if (random.nextBoolean()) {
            sb.append(" static s(a) { return ").append(expression()).append("; }");
        }
        if (random.nextInt(3) == 0) {
            sb.append(" *[Symbol.iterator]() { yield this.x; yield ").append(literal()).append("; }");
        }
        if (random.nextInt(4) == 0) {
            sb.append(" toString() { return 'C:' + this.x; }");
        }
        if (random.nextInt(4) == 0) {
            sb.append(" valueOf() { return ").append(numberLiteral()).append("; }");
        }
        locals.clear();
        locals.addAll(saved);
        return sb.append(" }").toString();
    }

    // ---- statements ----

    private String statements(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(statement()).append(' ');
        }
        return sb.toString().trim();
    }

    private String statement() {
        if (depth > 3) {
            return "__p(" + expression() + ");";
        }
        depth++;
        try {
            int choice = random.nextInt(loopDepth > 1 ? 14 : 22);
            return switch (choice) {
                case 0, 1, 2, 3 -> "__p(" + expression() + ");";
                case 4, 5 -> {
                    String target = assignable();
                    yield target + " = __c(" + expression() + "); __p(" + target + ");";
                }
                case 6 -> {
                    String target = assignable();
                    yield target + " " + pick(COMPOUND) + " " + expression() + "; " + target + " = __c(" + target
                            + "); __p(" + target + ");";
                }
                case 7 -> "if (" + expression() + ") { " + statement() + " } else { " + statement() + " }";
                case 8 -> {
                    String v = "t" + locals.size();
                    String decl = random.nextBoolean() ? "let" : "const";
                    locals.add(v);
                    String rest = statement();
                    locals.remove(v);
                    yield "{ " + decl + " " + v + " = " + expression() + "; " + rest + " __p(" + v + "); }";
                }
                case 9 -> destructuring();
                case 10 -> "throw " + expression() + ";";
                case 11 -> "try { " + statement() + " } catch (e) { __t(e); " + statement() + " } finally { "
                        + statement() + " }";
                case 12 -> asyncStatement();
                case 13 -> "__p(" + callable() + ");";
                case 14, 15 -> forLoop();
                case 16 -> forOf();
                case 17 -> forIn();
                case 18 -> whileLoop();
                case 19 -> switchStatement();
                case 20 -> labeledLoop();
                default -> {
                    String obj = assignable();
                    yield switch (random.nextInt(4)) {
                        case 0 -> obj + member(false) + " = __c(" + expression() + "); __p(" + obj + ");";
                        case 1 -> "__p(delete " + obj + member(false) + "); __p(" + obj + ");";
                        case 2 -> "Object.defineProperty(" + obj + ", " + quote(pick(PROPS)) + ", { value: " + expression()
                                + ", writable: " + random.nextBoolean() + ", enumerable: " + random.nextBoolean()
                                + ", configurable: " + random.nextBoolean() + " }); __p(" + obj + ");";
                        default -> obj + "[" + expression() + "] = __c(" + expression() + "); __p(" + obj + ");";
                    };
                }
            };
        } finally {
            depth--;
        }
    }

    private String body() {
        loopDepth++;
        try {
            return statements(1 + random.nextInt(2));
        } finally {
            loopDepth--;
        }
    }

    private String forLoop() {
        String i = "i" + loopDepth;
        String header = "for (let " + i + " = 0; " + i + " < " + (1 + random.nextInt(6)) + " && __b(); " + i + "++)";
        locals.add(i);
        counters.add(i);
        String b = body();
        String extra = random.nextInt(3) == 0 ? " if (" + expression() + ") " + (random.nextBoolean() ? "break" : "continue") + ";" : "";
        locals.remove(i);
        return header + " {" + extra + " " + b + " }";
    }

    private String forOf() {
        String x = "x" + loopDepth;
        String iterable = iterable();
        locals.add(x);
        String b = body();
        locals.remove(x);
        String binding = random.nextInt(4) == 0 ? "[" + x + "]" : x;
        if (binding.startsWith("[")) {
            iterable = "[[1, 2], 'ab', [" + expression() + "]]";
        }
        return "for (const " + binding + " of " + iterable + ") { __b(); " + b + " }";
    }

    private String forIn() {
        String k = "k" + loopDepth;
        String obj = random.nextBoolean() ? objectLiteral() : variable();
        locals.add(k);
        String b = body();
        locals.remove(k);
        return "for (const " + k + " in " + obj + ") { __b(); " + b + " }";
    }

    private String whileLoop() {
        String guard = "w" + loopDepth;
        String b = body();
        boolean doWhile = random.nextBoolean();
        String cond = "(" + expression() + ") && " + guard + "++ < " + (1 + random.nextInt(5)) + " && __b()";
        return "{ let " + guard + " = 0; " + (doWhile ? "do { " + b + " } while (" + cond + ");" : "while (" + cond + ") { " + b + " }") + " }";
    }

    private String switchStatement() {
        StringBuilder sb = new StringBuilder("switch (").append(expression()).append(") {");
        int cases = 1 + random.nextInt(4);
        for (int i = 0; i < cases; i++) {
            sb.append(" case ").append(literal()).append(": ").append(statement());
            if (random.nextBoolean()) {
                sb.append(" break;");
            }
        }
        if (random.nextBoolean()) {
            sb.append(" default: ").append(statement());
        }
        return sb.append(" }").toString();
    }

    private String labeledLoop() {
        String label = "L" + (labels++);
        String i = "i" + loopDepth;
        String j = "j" + loopDepth;
        locals.add(i);
        locals.add(j);
        counters.add(i);
        counters.add(j);
        String b = body();
        String cond = expression();
        locals.remove(i);
        locals.remove(j);
        return label + ": for (let " + i + " = 0; " + i + " < 3; " + i + "++) { for (let " + j + " = 0; " + j
                + " < 3 && __b(); " + j + "++) { if (" + cond + ") " + (random.nextBoolean() ? "break " : "continue ") + label
                + "; " + b + " } }";
    }

    private String destructuring() {
        return switch (random.nextInt(4)) {
            case 0 -> "{ let [d0, d1 = " + literal() + ", ...d2] = " + iterable() + "; __p([d0, d1, d2]); }";
            case 1 -> "{ let { x: d0, y: d1 = " + literal() + ", ...d2 } = " + objectLiteral() + "; __p([d0, d1, d2]); }";
            case 2 -> "{ let [d0, [d1] = [" + literal() + "]] = [" + expression() + "]; __p([d0, d1]); }";
            default -> "{ let d0 = " + literal() + ", d1 = " + literal() + "; [d0, d1] = [d1, d0]; __p([d0, d1]); }";
        };
    }

    private String asyncStatement() {
        return switch (random.nextInt(5)) {
            case 0 -> "Promise.resolve(" + expression() + ").then(function (r) { __p(r); });";
            case 1 -> "(async function () { __p(await " + expression() + "); })().catch(__t);";
            case 2 -> "Promise.all([" + expression() + ", Promise.resolve(" + expression() + ")]).then(__p, __t);";
            case 3 -> "Promise.reject(" + expression() + ").catch(function (r) { __p(r); }).finally(function () { __p("
                    + literal() + "); });";
            default -> "(async function () { for await (const a of [" + expression() + ", Promise.resolve(" + literal()
                    + ")]) __p(a); })().catch(__t);";
        };
    }

    private String assignable() {
        return "v" + random.nextInt(globals);
    }

    // ---- expressions ----

    private String expression() {
        if (depth > 4) {
            return leaf();
        }
        depth++;
        try {
            int choice = random.nextInt(30);
            return switch (choice) {
                case 0, 1, 2, 3, 4 -> leaf();
                case 5, 6, 7, 8 -> "(" + expression() + " " + pick(BINARY) + " " + expression() + ")";
                case 9 -> "(" + pick(UNARY) + expression() + ")";
                case 10 -> "(" + expression() + " ? " + expression() + " : " + expression() + ")";
                case 11, 12, 13 -> builtinCall();
                case 14 -> "(" + expression() + ")" + member(random.nextBoolean());
                case 15 -> "(" + expression() + ")" + (random.nextBoolean() ? "?.[" : "[") + expression() + "]";
                case 16 -> arrayLiteral();
                case 17 -> objectLiteral();
                case 18 -> callable();
                case 19 -> "`" + "a${" + expression() + "}b${" + expression() + "}`";
                case 20 -> "((a, b) => " + arrow() + ")(" + expression() + ", " + expression() + ")";
                case 21 -> "(" + smallInt(-4, 5) + " ** " + smallInt(0, 12) + ")";
                case 22 -> "(" + expression() + " in " + objectLiteral() + ")";
                case 23 -> "(" + expression() + " instanceof " + (classes.isEmpty() ? pick(new String[] {"Object", "Array", "Error"}) : pick(classes)) + ")";
                case 24 -> closure();
                case 25 -> "[..." + iterable() + "]";
                case 26 -> "((s, ...v) => s.raw.join('|') + v.length)`x${" + expression() + "}y\\n`";
                case 27 -> bigintExpression();
                case 28 -> specialObject();
                default -> "(" + mutableVariable() + (random.nextBoolean() ? "++" : "--") + ")";
            };
        } finally {
            depth--;
        }
    }

    /** A property access: {@code .p}, or {@code [0]} for an index; optional with {@code ?.}. */
    private String member(boolean optional) {
        String p = pick(PROPS);
        boolean index = Character.isDigit(p.charAt(0));
        return optional ? (index ? "?.[" + p + "]" : "?." + p) : (index ? "[" + p + "]" : "." + p);
    }

    private String arrow() {
        locals.add("a");
        locals.add("b");
        String e = expression();
        locals.remove(locals.size() - 1);
        locals.remove(locals.size() - 1);
        return e;
    }

    private String closure() {
        return "((function () { let c = " + numberLiteral() + "; return function () { c = c * 2 + 1; return c; }; })())()";
    }

    private String leaf() {
        return random.nextInt(5) < 2 ? literal() : variable();
    }

    private String variable() {
        if (!locals.isEmpty() && random.nextBoolean()) {
            return pick(locals);
        }
        return "v" + random.nextInt(globals);
    }

    /** A variable an expression may change: not a loop counter, which bounds its loop. */
    private String mutableVariable() {
        List<String> candidates = locals.stream().filter(v -> !counters.contains(v)).toList();
        if (!candidates.isEmpty() && random.nextBoolean()) {
            return pick(candidates);
        }
        return "v" + random.nextInt(globals);
    }

    private String literal() {
        return switch (random.nextInt(8)) {
            case 0, 1, 2 -> numberLiteral();
            case 3, 4 -> pick(STRINGS);
            case 5 -> random.nextBoolean() ? "true" : "false";
            case 6 -> random.nextBoolean() ? "null" : "undefined";
            default -> pick(BIGINTS);
        };
    }

    private String literalOfAnyType() {
        return switch (random.nextInt(10)) {
            case 0, 1, 2 -> numberLiteral();
            case 3, 4 -> pick(STRINGS);
            case 5 -> "[" + numberLiteral() + ", " + pick(STRINGS) + ", " + numberLiteral() + "]";
            case 6 -> "{ x: " + numberLiteral() + ", y: " + pick(STRINGS) + " }";
            case 7 -> "new Map([[1, 'a'], ['b', 2]])";
            case 8 -> "new Set([1, 2, 2, 'x'])";
            default -> random.nextBoolean() ? "true" : "null";
        };
    }

    private String numberLiteral() {
        return pick(NUMBERS);
    }

    private String smallInt(int from, int to) {
        int v = from + random.nextInt(to - from);
        return v < 0 ? "(" + v + ")" : Integer.toString(v);
    }

    private String arrayLiteral() {
        int n = random.nextInt(5);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            int k = random.nextInt(8);
            if (k == 0) {
                sb.append("...").append(iterable());
            } else if (k == 1 && i < n - 1) {
                sb.append(""); // a hole
            } else {
                sb.append(expression());
            }
        }
        return sb.append("]").toString();
    }

    private String objectLiteral() {
        int n = random.nextInt(4);
        StringBuilder sb = new StringBuilder("({");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            switch (random.nextInt(7)) {
                case 0 -> sb.append("[").append(expression()).append("]: ").append(expression());
                case 1 -> sb.append("...").append(expression());
                case 2 -> sb.append("get ").append(pick(PROPS)).append("() { return ").append(expression()).append("; }");
                case 3 -> sb.append(pick(PROPS)).append("(a) { return ").append(arrow()).append("; }");
                case 4 -> sb.append(quote(pick(PROPS))).append(": ").append(expression());
                default -> sb.append(pick(PROPS)).append(": ").append(expression());
            }
        }
        if (random.nextInt(6) == 0) {
            sb.append(n > 0 ? ", " : "").append("__proto__: ").append(random.nextBoolean() ? "null" : "{ z: 1 }");
        }
        return sb.append("})").toString();
    }

    private String iterable() {
        return switch (random.nextInt(8)) {
            case 0, 1 -> arrayLiteral();
            case 2 -> pick(STRINGS);
            case 3 -> generators.isEmpty() ? "[1, 2, 3]" : pick(generators) + "(" + smallInt(0, 5) + ")";
            case 4 -> "new Set([" + expression() + ", " + expression() + "])";
            case 5 -> "new Map([[" + expression() + ", " + expression() + "]]).entries()";
            case 6 -> "Object.entries(" + objectLiteral() + ")";
            default -> "[" + literal() + ", " + literal() + "].values()";
        };
    }

    private String callable() {
        int kind = random.nextInt(4);
        if (kind == 0 && !functions.isEmpty()) {
            String f = pick(functions);
            return switch (random.nextInt(4)) {
                case 0 -> f + ".call(" + expression() + ", " + expression() + ")";
                case 1 -> f + ".apply(null, " + arrayLiteral() + ")";
                case 2 -> f + ".bind(null, " + expression() + ")(" + expression() + ")";
                default -> f + "(" + expression() + ", " + expression() + ")";
            };
        }
        if (kind == 1 && !classes.isEmpty()) {
            String c = pick(classes);
            return switch (random.nextInt(6)) {
                case 0 -> "new " + c + "(" + expression() + ").m(" + expression() + ")";
                case 1 -> "new " + c + "(" + expression() + ")";
                case 2 -> "new " + c + "(" + expression() + ").g";
                case 3 -> "(typeof " + c + ".s === 'function' ? " + c + ".s(" + expression() + ") : " + c + ".count)";
                case 4 -> "(() => { const o = new " + c + "(" + expression() + "); return typeof o.bump === 'function' ? [o.bump(), o.bump(), " + c + ".has(o), " + c + ".has({})] : String(o); })()";
                default -> "[...new " + c + "(" + expression() + ")]";
            };
        }
        if (kind == 2 && !generators.isEmpty()) {
            String g = pick(generators);
            return switch (random.nextInt(3)) {
                case 0 -> "(() => { const it = " + g + "(" + smallInt(0, 4) + "); return [it.next(), it.next(" + expression() + "), it.return(" + literal() + "), it.next()]; })()";
                case 1 -> "Array.from(" + g + "(" + smallInt(0, 6) + "), (a, i) => " + arrow() + ")";
                default -> "(" + g + "(3).toArray ? " + g + "(5).drop(1).take(2).map((a) => " + arrow() + ").toArray() : 0)";
            };
        }
        return builtinCall();
    }

    private String bigintExpression() {
        String a = pick(BIGINTS);
        String b = pick(BIGINTS);
        return switch (random.nextInt(5)) {
            case 0 -> "(" + a + " " + pick(new String[] {"+", "-", "*", "/", "%", "&", "|", "^", "<", "==", "==="}) + " " + b + ")";
            case 1 -> "BigInt.asIntN(" + smallInt(1, 70) + ", " + a + ")";
            case 2 -> "BigInt.asUintN(" + smallInt(1, 70) + ", " + a + ")";
            case 3 -> "BigInt(" + expression() + ")";
            default -> "(" + a + ").toString(" + smallInt(2, 37) + ")";
        };
    }

    private String specialObject() {
        return switch (random.nextInt(6)) {
            case 0 -> "new Proxy(" + objectLiteral() + ", { get(t, k) { return typeof k === 'string' ? k + '!' : undefined; } })";
            case 1 -> "Object.freeze(" + arrayLiteral() + ")";
            case 2 -> "Object.create(" + (random.nextBoolean() ? "null" : objectLiteral()) + ", { x: { value: " + expression() + ", enumerable: true } })";
            case 3 -> "new (class extends Array {})(" + smallInt(0, 4) + ")";
            case 4 -> "Reflect.ownKeys(" + objectLiteral() + ")";
            default -> "new Uint8Array([" + expression() + ", " + expression() + ", 300, -1])";
        };
    }

    private String builtinCall() {
        Supplier<String> e = this::expression;
        Supplier<String> s = () -> random.nextBoolean() ? pick(STRINGS) : "String(" + expression() + ")";
        Supplier<String> a = () -> random.nextBoolean() ? arrayLiteral() : "Array.from(" + iterable() + ")";
        Supplier<String> n = () -> random.nextBoolean() ? numberLiteral() : "Number(" + expression() + ")";
        String small = smallInt(-3, 8);
        return switch (random.nextInt(48)) {
            case 0 -> s.get() + ".charAt(" + e.get() + ")";
            case 1 -> s.get() + ".charCodeAt(" + e.get() + ")";
            case 2 -> s.get() + ".codePointAt(" + small + ")";
            case 3 -> s.get() + ".indexOf(" + s.get() + ", " + small + ")";
            case 4 -> s.get() + ".slice(" + e.get() + ", " + small + ")";
            case 5 -> s.get() + ".substring(" + e.get() + ", " + e.get() + ")";
            case 6 -> s.get() + (random.nextBoolean() ? ".toUpperCase()" : ".toLowerCase()");
            case 7 -> s.get() + ".padStart(" + smallInt(0, 12) + ", " + s.get() + ")";
            case 8 -> s.get() + ".repeat(" + small + ")";
            case 9 -> s.get() + ".split(" + s.get() + ", " + small + ")";
            case 10 -> s.get() + ".replace(" + regexp() + ", " + replacement() + ")";
            case 11 -> s.get() + ".replaceAll(" + s.get() + ", " + s.get() + ")";
            case 12 -> s.get() + ".at(" + small + ")";
            case 13 -> s.get() + ".trim" + pick(new String[] {"", "Start", "End"}) + "()";
            case 14 -> regexp() + ".exec(" + s.get() + ")";
            case 15 -> "[..." + s.get() + ".matchAll(/" + pick(new String[] {"a", "\\d+", "(.)\\1?", "[a-z]"}) + "/g)].map((m) => m[0] + m.index)";
            case 16 -> a.get() + ".map((a, b) => " + arrow() + ")";
            case 17 -> a.get() + ".filter((a) => " + arrow() + ")";
            case 18 -> a.get() + ".reduce((a, b) => " + arrow() + ", " + e.get() + ")";
            case 19 -> a.get() + ".join(" + s.get() + ")";
            case 20 -> a.get() + (random.nextBoolean() ? ".sort()" : ".toSorted()");
            case 21 -> a.get() + ".slice(" + small + ", " + e.get() + ")";
            case 22 -> "(() => { const z = " + a.get() + "; const r = z.splice(" + small + ", " + smallInt(0, 3) + ", " + e.get() + "); return [z, r]; })()";
            case 23 -> a.get() + ".indexOf(" + e.get() + ")";
            case 24 -> a.get() + ".includes(" + e.get() + ")";
            case 25 -> a.get() + ".concat(" + e.get() + ", " + a.get() + ")";
            case 26 -> a.get() + ".flat(" + smallInt(0, 3) + ")";
            case 27 -> a.get() + ".flatMap((a) => [a, " + literal() + "])";
            case 28 -> a.get() + pick(new String[] {".reverse()", ".toReversed()", ".keys().toArray()", ".entries().next().value"});
            case 29 -> a.get() + ".find" + pick(new String[] {"", "Index", "Last", "LastIndex"}) + "((a) => " + arrow() + ")";
            case 30 -> a.get() + ".with(" + smallInt(-2, 3) + ", " + e.get() + ")";
            case 31 -> a.get() + ".fill(" + e.get() + ", " + small + ")";
            case 32 -> "Array.of(" + e.get() + ", " + e.get() + ")";
            case 33 -> "Object." + pick(new String[] {"keys", "values", "entries", "getOwnPropertyNames", "isFrozen", "getPrototypeOf"}) + "(" + e.get() + ")";
            case 34 -> "Object.assign({}, " + e.get() + ", " + e.get() + ")";
            case 35 -> "Object.fromEntries(" + iterable() + ")";
            case 36 -> "Object.groupBy(" + a.get() + ", (a) => typeof a)";
            case 37 -> "JSON.stringify(" + e.get() + (random.nextBoolean() ? ")" : ", null, " + smallInt(0, 3) + ")");
            case 38 -> "JSON.parse(" + pick(new String[] {"'[1,\"a\",{\"b\":null}]'", "'{\"x\":1,\"x\":2}'", "' 1e3 '", "'\"\\\\u0041\"'", "'{'", "'[-0]'"}) + ")";
            case 39 -> "Math." + pick(new String[] {"abs", "floor", "ceil", "round", "trunc", "sign", "sqrt", "fround", "clz32"}) + "(" + e.get() + ")";
            case 40 -> "Math." + pick(new String[] {"max", "min", "imul"}) + "(" + e.get() + ", " + e.get() + ")";
            case 41 -> "(" + n.get() + ")." + pick(new String[] {"toFixed", "toPrecision", "toExponential"}) + "(" + smallInt(1, 21) + ")";
            case 42 -> "((" + e.get() + ") | 0).toString(" + smallInt(2, 37) + ")";
            case 43 -> pick(new String[] {"parseInt", "parseFloat", "Number", "String", "Boolean", "isNaN", "isFinite", "Number.isInteger", "Number.isSafeInteger"}) + "(" + e.get() + ")";
            case 44 -> "parseInt(" + s.get() + ", " + smallInt(0, 37) + ")";
            case 45 -> "String.fromCharCode(" + e.get() + ", " + smallInt(48, 123) + ")";
            case 46 -> "Array.prototype." + pick(new String[] {"join", "indexOf", "slice"}) + ".call(" + objectLiteral() + ", " + e.get() + ")";
            default -> "new Map([[" + e.get() + ", " + e.get() + "]]).get(" + e.get() + ")";
        };
    }

    private String regexp() {
        String body = pick(new String[] {"a", "b+", "[a-c]", "(\\w)(\\w)?", "^.", "$", "\\d*", "(?<n>[A-Z])", "x|y|", "(?:ab)+", ".", "\\s", "[^a]", "(a)|(b)", "\\bW"});
        String flags = pick(new String[] {"", "g", "i", "gi", "y", "m", "s", "u", "d"});
        return "/" + body + "/" + flags;
    }

    private String replacement() {
        return random.nextBoolean()
                ? pick(new String[] {"'$&$&'", "'[$1]'", "'$`'", "\"$'\"", "'$<n>'", "'$$'", "''"})
                : "(m, ...r) => m.length + ':' + r.length";
    }

    private String pick(String[] values) {
        return values[random.nextInt(values.length)];
    }

    private String pick(List<String> values) {
        return values.get(random.nextInt(values.size()));
    }

    private static String quote(String s) {
        return "'" + s + "'";
    }
}
