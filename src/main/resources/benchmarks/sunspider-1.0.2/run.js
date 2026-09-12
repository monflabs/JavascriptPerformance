// Each SunSpider test is a standalone script with no shared driver of its own (unlike Octane/
// v8-benchmarks-v6's BenchmarkSuite framework), so BenchmarkRunner wraps the test body in
// __benchmarkBody__() rather than running it inline, letting this file call it a fixed number
// of times instead. A single call is often well under 1ms - too short relative to timer/GC/JIT
// noise to be a stable measurement - and the tests are wildly different in cost (from trivial
// bit-twiddling loops to a raytracer), so REPEATS is keyed per file rather than a single count,
// each picked so that one measured pass takes roughly 250ms on a mid-speed engine
// (NASHORN_OPENJDK). A time-boxed loop (run until N ms elapsed) was tried first and rejected:
// it makes every engine converge to the same wall time regardless of speed, since the harness
// would then be measuring the calibration window instead of the engine.
var REPEATS = {
  "3d-cube.js": 28,
  "3d-morph.js": 106,
  "3d-raytrace.js": 21,
  "access-binary-trees.js": 208,
  "access-fannkuch.js": 52,
  "access-nbody.js": 208,
  "access-nsieve.js": 48,
  "bitops-3bit-bits-in-byte.js": 3000,
  "bitops-bits-in-byte.js": 430,
  "bitops-bitwise-and.js": 56,
  "bitops-nsieve-bits.js": 77,
  "controlflow-recursive.js": 700,
  "crypto-aes.js": 46,
  "crypto-md5.js": 93,
  "crypto-sha1.js": 74,
  "date-format-tofte.js": 16,
  "date-format-xparb.js": 23,
  "math-cordic.js": 270,
  "math-partial-sums.js": 56,
  "math-spectral-norm.js": 830,
  "regexp-dna.js": 4,
  "string-base64.js": 82,
  "string-fasta.js": 10,
  "string-tagcloud.js": 21,
  "string-unpack-code.js": 10,
  "string-validate-input.js": 57
};

var __repeat__ = REPEATS[__benchmarkFile__] || 100;
for (var __i__ = 0; __i__ < __repeat__; __i__++) {
  __benchmarkBody__();
}

undefined;
