// Loads JetStream's driver in a VM with the stubs of a shell, and returns its default benchmark
// list (the one JetStream scores in a shell: the "default" tag, without the worker tests).
const fs = require("fs");
const path = require("path");
const vm = require("vm");

function listBenchmarks(dir) {
  const context = vm.createContext({
    print() {},
    printErr() {},
    load() {},
    read: () => "",
    console: { log() {}, warn() {}, error() {}, debug() {}, assert() {} },
    performance: { now: () => 0 },
  });
  context.globalThis = context;
  for (const file of ["utils/shell-config.js", "utils/params.js", "JetStreamDriver.js"]) {
    vm.runInContext(fs.readFileSync(path.join(dir, file), "utf8"), context, { filename: file });
  }
  return vm.runInContext(
    `(() => {
      const list = [];
      const visit = b => (b.benchmarks ? b.benchmarks.forEach(visit) : list.push(b));
      JetStream.benchmarks.forEach(visit);
      return list.map(b => ({
        name: b.name,
        kind: b.constructor.name,
        tags: [...b.tags],
        files: b.files,
        preload: Object.fromEntries(b.preloadEntries),
        iterations: b.iterations,
        deterministicRandom: b._deterministicRandom,
        args: b._arguments,
      }));
    })()`,
    context
  );
}

module.exports = { listBenchmarks };
