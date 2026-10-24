// The driver of JetStream 3.0 in this harness (copied by tools/jetstream3/build.sh). The harness
// concatenates this file, a workload file and run.js, and runs the whole script on every
// iteration. The workload's sources are loaded once (they are in a function, called on the
// first run); each run is then one run of the workload as JetStream's shell driver does it: a
// new Benchmark, its init, its iterations and its validate, which throws on a wrong result
// (console.assert throws, as in JetStream's shells). An asynchronous workload completes while
// the executor drains the job queue: its outcome is checked at the start of the next run.

var __js3Global = this;

if (typeof console === "undefined") {
  __js3Global.console = {};
}
if (typeof console.log !== "function") {
  console.log = function () {
    print(Array.prototype.join.call(arguments, " "));
  };
}
console.warn = console.warn || console.log;
console.error = console.error || console.log;
console.debug = console.debug || function () {};
console.assert = function (condition, message) {
  if (!condition) {
    throw new Error("Assertion failed: " + message);
  }
};
if (typeof performance === "undefined") {
  __js3Global.performance = { now: Date.now };
}
performance.mark = performance.mark || function (name) { return { name: name }; };
performance.measure = performance.measure || function () {};
__js3Global.self = __js3Global;

if (typeof setTimeout === "undefined") {
  // Timers run on the job queue, which is all the executors drain, in the order of their
  // delays, without waiting for them: JetStream's shells provide setTimeout, and the workloads
  // use it to yield.
  (function () {
    var timers = [];
    var nextId = 1;
    var scheduled = false;
    function fire() {
      scheduled = false;
      if (timers.length === 0) return;
      var first = 0;
      for (var i = 1; i < timers.length; i++) {
        if (timers[i].delay < timers[first].delay) first = i;
      }
      var timer = timers.splice(first, 1)[0];
      schedule();
      timer.fn.apply(undefined, timer.args);
    }
    function schedule() {
      if (!scheduled && timers.length > 0) {
        scheduled = true;
        Promise.resolve().then(function () {}).then(fire);
      }
    }
    __js3Global.setTimeout = function (fn, delay) {
      var id = nextId++;
      timers.push({ id: id, fn: fn, delay: +delay || 0, args: Array.prototype.slice.call(arguments, 2) });
      schedule();
      return id;
    };
    __js3Global.clearTimeout = function (id) {
      for (var i = 0; i < timers.length; i++) {
        if (timers[i].id === id) timers.splice(i, 1);
      }
    };
  })();
}

function __js3Decode(base64) {
  var alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  var values = new Int8Array(128);
  for (var i = 0; i < alphabet.length; i++) {
    values[alphabet.charCodeAt(i)] = i;
  }
  var length = base64.length / 4 * 3;
  if (base64.endsWith("==")) length -= 2;
  else if (base64.endsWith("=")) length -= 1;
  var bytes = new Int8Array(length);
  for (var i = 0, j = 0; i < base64.length; i += 4) {
    var n = (values[base64.charCodeAt(i)] << 18) | (values[base64.charCodeAt(i + 1)] << 12)
        | (values[base64.charCodeAt(i + 2)] << 6) | values[base64.charCodeAt(i + 3)];
    if (j < length) bytes[j++] = n >> 16;
    if (j < length) bytes[j++] = n >> 8;
    if (j < length) bytes[j++] = n;
  }
  return bytes;
}

function __js3Setup(workload) {
  var preload = {};
  for (var key in workload.preload) {
    preload[key] = workload.preload[key];
  }
  var binaries = {};
  __js3Global.JetStream = {
    preload: preload,
    resources: {},
    isInBrowser: false,
    isD8: false,
    getString: function (path) {
      return Promise.resolve(workload.data[path]);
    },
    getBinary: function (path) {
      if (!binaries[path]) {
        binaries[path] = __js3Decode(workload.data[path]);
      }
      return Promise.resolve(binaries[path]);
    },
  };
  if (workload.deterministicRandom) {
    var initialSeed = 49734321;
    var seed = initialSeed;
    Math.random = function () {
      // Robert Jenkins' 32 bit integer hash function, as JetStream's driver
      seed = ((seed + 0x7ed55d16) + (seed << 12)) & 0xffffffff;
      seed = ((seed ^ 0xc761c23c) ^ (seed >>> 19)) & 0xffffffff;
      seed = ((seed + 0x165667b1) + (seed << 5)) & 0xffffffff;
      seed = ((seed + 0xd3a2646c) ^ (seed << 9)) & 0xffffffff;
      seed = ((seed + 0xfd7046c5) + (seed << 3)) & 0xffffffff;
      seed = ((seed ^ 0xb55a4f09) ^ (seed >>> 16)) & 0xffffffff;
      return (seed >>> 0) / 0x100000000;
    };
    Math.random.__resetSeed = function () {
      seed = initialSeed;
    };
  }
  workload.Benchmark = workload.load.call(__js3Global);
}

function __js3Arguments(workload) {
  var args = { iterationCount: workload.iterations };
  for (var key in workload.args) {
    args[key] = workload.args[key];
  }
  return args;
}

function __js3RunSync(workload) {
  var benchmark = new workload.Benchmark(__js3Arguments(workload));
  if (workload.kind === "WSLBenchmark") {
    benchmark.buildStdlib();
    benchmark.run();
    return;
  }
  for (var i = 0; i < workload.iterations; i++) {
    if (benchmark.prepareForNextIteration) benchmark.prepareForNextIteration();
    if (workload.deterministicRandom) Math.random.__resetSeed();
    benchmark.runIteration(i);
  }
  if (benchmark.validate) benchmark.validate(workload.iterations);
}

function __js3RunAsync(workload) {
  var benchmark = new workload.Benchmark(__js3Arguments(workload));
  var i = 0;
  function next() {
    if (i === workload.iterations) {
      if (benchmark.validate) benchmark.validate(workload.iterations);
      return undefined;
    }
    return Promise.resolve(benchmark.prepareForNextIteration && benchmark.prepareForNextIteration())
      .then(function () {
        if (workload.deterministicRandom) Math.random.__resetSeed();
        return benchmark.runIteration(i++);
      })
      .then(next);
  }
  return Promise.resolve(benchmark.init && benchmark.init()).then(next);
}

function __js3Run() {
  var previous = __js3Global.__js3Previous;
  __js3Global.__js3Previous = undefined;
  if (previous) {
    if (!previous.settled) {
      throw new Error("asynchronous work of the previous run had not completed: the job queue was not drained");
    }
    if (previous.error) {
      throw previous.error;
    }
  }
  var workload = __js3Workload;
  if (!workload.Benchmark) {
    __js3Setup(workload);
  }
  if (workload.kind === "AsyncBenchmark") {
    var record = { settled: false, error: null };
    __js3Global.__js3Previous = record;
    __js3RunAsync(workload).then(function () {
      record.settled = true;
    }, function (error) {
      record.error = error;
      record.settled = true;
    });
  } else {
    __js3RunSync(workload);
  }
}
