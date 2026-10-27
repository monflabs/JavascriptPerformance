#!/usr/bin/env bash
#
# Copyright (c) 2026, Philippe Riand.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Coverage-guided fuzzing of the engines with Jazzer.
#
#   tools/robustness/jazzer.sh <Target> [seconds] [more libFuzzer or Jazzer options]
#
# Targets (org.monflabs.nashorn.performance.robustness.jazzer):
#   RhinoCompile   RhinoEval   RhinoRegExp   RhinoJson
#   NashornCompile             NashornRegExp NashornJson
#
# Runs for 600 seconds by default. The corpus Jazzer grows is kept in
# target/robustness/jazzer/<Target>/corpus, so a second run starts where the first stopped; the
# JSC stress tests seed the compile and eval targets when they have been fetched. Every finding
# (an input and its stack trace) is written to target/robustness/jazzer/<Target>/findings;
# rerun one with: tools/robustness/jazzer.sh <Target> 0 <findings/crash-...>. ENGINE_CP=<jars> puts
# locally built engine jars ahead of the ones in the shaded jar.
set -euo pipefail
cd "$(dirname "$0")/../.."

TARGET=${1:?usage: $0 <Target> [seconds] [options]}
DURATION=${2:-600}
shift
[ $# -gt 0 ] && shift

JAR=$(ls target/javascript-performance-*-all.jar 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
  echo "Build the jar first: mvn package -DskipTests" >&2
  exit 2
fi
VERSION=$(sed -n 's:.*<jazzer.version>\(.*\)</jazzer.version>.*:\1:p' pom.xml)
REPO=$HOME/.m2/repository/com/code-intelligence
for ARTIFACT in jazzer jazzer-api; do
  if [ ! -f "$REPO/$ARTIFACT/$VERSION/$ARTIFACT-$VERSION.jar" ]; then
    mvn -q dependency:get -Dartifact=com.code-intelligence:"$ARTIFACT":"$VERSION"
  fi
done
# ENGINE_CP, if set, goes first: engine jars built locally, tried without republishing them
CP=${ENGINE_CP:+$ENGINE_CP:}$REPO/jazzer/$VERSION/jazzer-$VERSION.jar:$REPO/jazzer-api/$VERSION/jazzer-api-$VERSION.jar:$JAR

OUT=target/robustness/jazzer/$TARGET
mkdir -p "$OUT/corpus" "$OUT/findings"
SEEDS=()
case $TARGET in
  *Compile|*Eval)
    if [ -d corpora/webkit/JSTests/stress ]; then
      SEEDS+=(corpora/webkit/JSTests/stress)
    fi
    ;;
esac

ARGS=(--target_class=org.monflabs.nashorn.performance.robustness.jazzer.$TARGET
      "--instrumentation_includes=org.monflabs.mozilla.**:org.monflabs.nashorn.internal.**"
      --keep_going=50
      -timeout=30
      -rss_limit_mb=4096
      -max_len=8192
      -dict=tools/robustness/javascript.dict
      --reproducer_path="$OUT/findings"
      -artifact_prefix="$OUT/findings/")
# A file argument replays that input; otherwise fuzz for the given time
if [ $# -gt 0 ] && [ -f "${1:-}" ]; then
  exec java -Xss8m -Xmx4g -cp "$CP" com.code_intelligence.jazzer.Jazzer "${ARGS[@]}" "$@"
fi
exec java -Xss8m -Xmx4g -cp "$CP" com.code_intelligence.jazzer.Jazzer "${ARGS[@]}" \
  -max_total_time="$DURATION" "$@" "$OUT/corpus" ${SEEDS[@]+"${SEEDS[@]}"}
