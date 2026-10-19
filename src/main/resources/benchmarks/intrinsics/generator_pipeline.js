// A lazy log-analysis pipeline: a generator produces access-log lines, further generators
// parse them, filter and transform the records, and group them in batches that are folded into
// per-endpoint statistics. Nothing is materialised except one batch at a time - the usual
// reason to write an ETL as composed generators.
let seed = 987654321;
function random(limit) {
  seed = (seed * 48271) % 2147483647;
  return seed % limit;
}

let METHODS = ["GET", "GET", "GET", "POST", "PUT", "DELETE"];
let PATHS = ["/api/users", "/api/orders", "/api/orders/items", "/api/cart", "/login", "/logout",
  "/static/app.js", "/static/app.css", "/search", "/health", "/api/payments", "/api/inventory"];
let STATUSES = [200, 200, 200, 200, 201, 204, 301, 304, 400, 401, 404, 500, 503];

function* logLines(count) {
  for (let i = 0; i < count; i++) {
    let ip = "10." + random(256) + "." + random(256) + "." + random(256);
    let method = METHODS[random(METHODS.length)];
    let path = PATHS[random(PATHS.length)];
    let status = STATUSES[random(STATUSES.length)];
    let bytes = 200 + random(50000);
    let millis = 1 + random(900);
    yield `${ip} - - [12/Oct/2026:10:${random(60)}:${random(60)}] "${method} ${path}?id=${random(5000)} HTTP/1.1" ${status} ${bytes} ${millis}`;
  }
}

function* parse(lines) {
  for (let line of lines) {
    let open = line.indexOf('"');
    let close = line.indexOf('"', open + 1);
    let [method, target] = line.substring(open + 1, close).split(" ");
    let [status, bytes, millis] = line.substring(close + 2).split(" ").map(Number);
    let query = target.indexOf("?");
    yield { ip: line.substring(0, line.indexOf(" ")), method, path: query < 0 ? target : target.substring(0, query), status, bytes, millis };
  }
}

function* filter(items, predicate) {
  for (let item of items) {
    if (predicate(item)) {
      yield item;
    }
  }
}

function* map(items, transform) {
  for (let item of items) {
    yield transform(item);
  }
}

function* batches(items, size) {
  let batch = [];
  for (let item of items) {
    batch.push(item);
    if (batch.length === size) {
      yield batch;
      batch = [];
    }
  }
  if (batch.length > 0) {
    yield batch;
  }
}

let stats = new Map();
let batchCount = 0;
let totalBytes = 0;
let pipeline = batches(
  map(
    filter(parse(logLines(60000)), record => record.path.indexOf("/static/") !== 0 && record.status !== 304),
    record => ({ key: record.method + " " + record.path, failed: record.status >= 500, slow: record.millis > 500, bytes: record.bytes })),
  250);
for (let batch of pipeline) {
  batchCount++;
  for (let entry of batch) {
    let stat = stats.get(entry.key);
    if (stat === undefined) {
      stat = { requests: 0, failed: 0, slow: 0, bytes: 0 };
      stats.set(entry.key, stat);
    }
    stat.requests++;
    stat.failed += entry.failed ? 1 : 0;
    stat.slow += entry.slow ? 1 : 0;
    stat.bytes += entry.bytes;
    totalBytes += entry.bytes;
  }
}

let checksum = 0;
for (let key of Array.from(stats.keys()).sort()) {
  let stat = stats.get(key);
  checksum = (checksum * 31 + stat.requests * 7 + stat.failed * 13 + stat.slow * 17) % 1000003;
}

return { expected: "40/185/1159198566/831077", result: stats.size + "/" + batchCount + "/" + totalBytes + "/" + checksum };
