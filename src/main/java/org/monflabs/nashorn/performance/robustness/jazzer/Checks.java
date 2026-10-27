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
package org.monflabs.nashorn.performance.robustness.jazzer;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;

/** The checking scripts the RegExp and JSON targets run on both engines, and their inputs. */
final class Checks {

    /**
     * Compiles a pattern and runs it: a {@code SyntaxError} is the only error a pattern may
     * raise. The subject is short, so that a pattern that backtracks exponentially stays fast
     * enough not to be reported as a timeout.
     */
    static final String REGEXP = """
            (function () {
              var re;
              try { re = new RegExp(pattern, flags); } catch (e) { if (e instanceof SyntaxError) return; throw e; }
              var m = re.exec(subject);
              if (m !== null && (typeof m.index !== 'number' || typeof m[0] !== 'string')) throw new Error('bad match');
              subject.replace(re, '[$&]');
              subject.split(re);
              String(re);
              new RegExp(re.source, re.flags);
            })();
            """;

    /** Parses a text: a {@code SyntaxError} or a value whose JSON text survives a round trip. */
    static final String JSON = """
            (function () {
              var v;
              try { v = JSON.parse(text); } catch (e) { if (e instanceof SyntaxError) return; throw e; }
              var s = JSON.stringify(v);
              if (s !== undefined && JSON.stringify(JSON.parse(s)) !== s) throw new Error('JSON round trip of ' + s);
            })();
            """;

    private static final char[] FLAGS = {'d', 'g', 'i', 'm', 's', 'u', 'v', 'y'};

    private Checks() {
    }

    /** {pattern, flags, subject} from the fuzzer's bytes. */
    static String[] regexpInputs(FuzzedDataProvider data) {
        StringBuilder flags = new StringBuilder();
        for (char f : FLAGS) {
            if (data.consumeBoolean()) {
                flags.append(f);
            }
        }
        String pattern = data.consumeString(80);
        String subject = data.consumeRemainingAsString();
        if (subject.length() > 24) {
            subject = subject.substring(0, 24);
        }
        return new String[] {pattern, flags.toString(), subject};
    }
}
