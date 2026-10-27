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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What one engine did with one script.
 *
 * @param kind   how the script ended
 * @param output what it printed, one line per {@code print} call
 * @param error  for {@link Kind#THROW} the engine's message for the uncaught JavaScript value; for
 *               {@link Kind#CRASH} the Java exception that escaped the engine
 */
public record Outcome(Kind kind, String output, String error) {

    public enum Kind {
        /** Ran to completion. */
        OK,
        /** Ended with an uncaught JavaScript exception, or a syntax error: an ordinary result. */
        THROW,
        /** Did not finish within the time limit. The child JVM was abandoned. */
        TIMEOUT,
        /**
         * A Java exception that is not a JavaScript one escaped the engine (an
         * {@code IllegalStateException} from a compiler fallback, a {@code StackOverflowError}, a
         * {@code VerifyError}, ...), or the JVM running it died. Always a robustness problem.
         */
        CRASH,
        /** Not run: the corpus could not adapt the file to these engines. */
        SKIPPED
    }

    private static final Pattern ERROR_NAME = Pattern.compile("\\b((?:[A-Z][A-Za-z]*)?Error)\\b");
    private static final Pattern JAVA_ERROR = Pattern.compile("\\b(?:java|javax|org|com)\\.[\\w.$]+(?:Error|Exception)\\b");

    public static Outcome ok(String output) {
        return new Outcome(Kind.OK, output, "");
    }

    /**
     * The error's class for comparing engines, which word their messages differently: the first
     * {@code ...Error} name in a {@link Kind#THROW} message ({@code "uncaught"} when there is none,
     * as for {@code throw 1}), or the Java class of a {@link Kind#CRASH}.
     */
    public String signature() {
        return switch (kind) {
            case THROW -> {
                Matcher m = ERROR_NAME.matcher(error);
                yield m.find() ? m.group(1) : "uncaught";
            }
            case CRASH -> {
                if (error.startsWith("JVM exited") || error.startsWith("child exited")) {
                    // The child died: name the Java error its last lines report, if any
                    Matcher m = JAVA_ERROR.matcher(error);
                    String first = error.lines().findFirst().orElse("");
                    yield m.find() ? first + " (" + m.group() + ")" : first;
                }
                int colon = error.indexOf(':');
                String head = colon < 0 ? error : error.substring(0, colon);
                yield head.lines().findFirst().orElse(head);
            }
            default -> kind.name();
        };
    }

    /** One line, for the child-to-parent protocol. */
    String encode() {
        return kind.name() + "\t" + b64(output) + "\t" + b64(error);
    }

    static Outcome decode(String kind, String output, String error) {
        Base64.Decoder d = Base64.getDecoder();
        return new Outcome(Kind.valueOf(kind), new String(d.decode(output), StandardCharsets.UTF_8),
                new String(d.decode(error), StandardCharsets.UTF_8));
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
