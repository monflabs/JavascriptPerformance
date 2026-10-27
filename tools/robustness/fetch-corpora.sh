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
# Fetches the test corpora of the robustness harness into corpora/ (not versioned):
#   corpora/webkit/JSTests/stress   JavaScriptCore's stress tests, about 6,000 files, 50 MB
#
# A sparse, shallow clone: only that directory of WebKit's repository is downloaded. Run it
# again to update to WebKit's current main.
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p corpora

if [ -d corpora/webkit/.git ]; then
  git -C corpora/webkit pull --depth 1 --ff-only
else
  git clone --depth 1 --filter=blob:none --sparse https://github.com/WebKit/WebKit.git corpora/webkit
  git -C corpora/webkit sparse-checkout set JSTests/stress
fi
echo "JSTests/stress at WebKit $(git -C corpora/webkit rev-parse --short HEAD):" \
  "$(ls corpora/webkit/JSTests/stress | wc -l | tr -d ' ') entries"
