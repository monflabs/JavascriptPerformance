// Driver for the intrinsics suite. BenchmarkRunner wraps each benchmark file in
// __benchmarkBody__() (there is no base.js), so a file may use top-level let/const/class and
// can be run again and again in the same realm.
//
// A benchmark ends with `return { expected: <string>, result: <string | Promise<string>> };`.
// The result is a checksum of everything the workload computed, so an engine that skips,
// truncates or mis-orders the work is reported as FAILED instead of as fast.
//
// Asynchronous work only finishes when the engine runs its job queue, which each executor does
// before run() returns (inside the timed region). A promise result is therefore checked by
// recording its outcome here and enforcing it at the start of the next run: an engine that
// failed to drain its queue is caught on the second iteration rather than looking fast.
var __intrinsicsGlobal__ = this;

(function () {
  var previous = __intrinsicsGlobal__.__previousRun__;
  __intrinsicsGlobal__.__previousRun__ = undefined;
  if (previous) {
    if (!previous.settled) {
      throw new Error("asynchronous work of the previous run had not completed: the job queue was not drained");
    }
    if (previous.error) {
      throw previous.error;
    }
  }

  var outcome = __benchmarkBody__();

  function verify(actual) {
    if (actual !== outcome.expected) {
      return new Error("wrong result: expected " + outcome.expected + " but got " + actual);
    }
    return null;
  }

  var result = outcome.result;
  if (result && typeof result.then === "function") {
    var record = { settled: false, error: null };
    __intrinsicsGlobal__.__previousRun__ = record;
    result.then(function (value) {
      record.error = verify(value);
      record.settled = true;
    }, function (error) {
      record.error = error;
      record.settled = true;
    });
  } else {
    var error = verify(result);
    if (error) {
      throw error;
    }
  }
})();

undefined;
