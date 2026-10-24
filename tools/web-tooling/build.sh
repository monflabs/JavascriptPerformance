#!/usr/bin/env bash
# Regenerates src/main/resources/benchmarks/web-tooling-0.5.3 from the Web Tooling Benchmark
# (https://github.com/v8/web-tooling-benchmark), at the commit of its v0.5.3. Needs git and Node
# 16 (the benchmark builds with webpack 3); the result fingerprints are computed on that Node.
#
# Usage: tools/web-tooling/build.sh [checkout dir]   (default: target/web-tooling-benchmark)

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$ROOT/src/main/resources/benchmarks/web-tooling-0.5.3"
WORK="${1:-$ROOT/target/web-tooling-benchmark}"
COMMIT=4a12828c6a1eed02a70c011bd080445dd319a05f

[ -d "$WORK/.git" ] || git clone -q https://github.com/v8/web-tooling-benchmark.git "$WORK"
git -C "$WORK" checkout -q "$COMMIT"
(cd "$WORK" && npm ci --no-audit --no-fund >/dev/null)
(cd "$WORK" && node node_modules/webpack/bin/webpack.js --config "$HERE/webpack.config.js" >/dev/null)

find "$OUT" -maxdepth 1 -name '*.js' ! -name run.js -delete
node "$HERE/package.js" "$WORK/harness/dist" "$OUT"
cp "$WORK/LICENSE" "$OUT/LICENSE"
