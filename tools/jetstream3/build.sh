#!/usr/bin/env bash
# Regenerates src/main/resources/benchmarks/jetstream-3.0 from JetStream
# (https://github.com/WebKit/JetStream), at the commit of its JetStream3.0 branch. Needs git and
# Node 16 or later.
#
# Usage: tools/jetstream3/build.sh [checkout dir]   (default: target/jetstream)

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$ROOT/src/main/resources/benchmarks/jetstream-3.0"
WORK="${1:-$ROOT/target/jetstream}"
COMMIT=06785cf

[ -d "$WORK/.git" ] || git clone -q https://github.com/WebKit/JetStream.git "$WORK"
git -C "$WORK" checkout -q "$COMMIT"

mkdir -p "$OUT"
find "$OUT" -maxdepth 1 -name '*.js' ! -name run.js -delete
node "$HERE/package.js" "$WORK" "$OUT"
cp "$HERE/base.js" "$OUT/base.js"
cp "$WORK/LICENSE" "$OUT/LICENSE"
