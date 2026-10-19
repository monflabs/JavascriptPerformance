// Stream processing with async generators: a source delivers a CSV document in fixed-size
// chunks that cut records at arbitrary places, one stage reassembles complete lines across the
// chunk boundaries, the next parses and filters the records, and for await consumes the
// result and totals it up per category. This is the shape of reading a file or a network body
// through layered async iterators.
let seed = 1618;
function random(limit) {
  seed = (seed * 48271) % 2147483647;
  return seed % limit;
}

const CATEGORIES = ["books", "music", "garden", "toys", "tools", "food", "games", "sports"];

function makeDocument(rows) {
  const lines = ["id,category,quantity,price,note"];
  for (let i = 0; i < rows; i++) {
    lines.push([i, CATEGORIES[random(CATEGORIES.length)], 1 + random(20), (100 + random(90000)) / 100,
      "note " + random(1000)].join(","));
  }
  return lines.join("\n") + "\n";
}

async function* chunkSource(text, chunkSize) {
  for (let offset = 0; offset < text.length; offset += chunkSize) {
    yield text.substring(offset, offset + chunkSize);
  }
}

async function* lines(chunks) {
  let pending = "";
  for await (const chunk of chunks) {
    pending += chunk;
    let newline;
    let start = 0;
    while ((newline = pending.indexOf("\n", start)) >= 0) {
      yield pending.substring(start, newline);
      start = newline + 1;
    }
    pending = pending.substring(start);
  }
  if (pending.length > 0) {
    yield pending;
  }
}

async function* records(lineStream) {
  let header = null;
  for await (const line of lineStream) {
    const fields = line.split(",");
    if (header === null) {
      header = fields;
      continue;
    }
    const record = {};
    for (let i = 0; i < header.length; i++) {
      record[header[i]] = fields[i];
    }
    yield record;
  }
}

async function* where(stream, predicate) {
  for await (const item of stream) {
    if (predicate(item)) {
      yield item;
    }
  }
}

async function main() {
  const text = makeDocument(30000);
  const totals = new Map();
  let rows = 0;
  const stream = where(records(lines(chunkSource(text, 4096))), record => Number(record.quantity) > 2);
  for await (const record of stream) {
    rows++;
    const cents = Math.round(Number(record.price) * 100) * Number(record.quantity);
    totals.set(record.category, (totals.get(record.category) || 0) + cents);
  }
  let checksum = 0;
  for (let category of Array.from(totals.keys()).sort()) {
    checksum = (checksum * 31 + totals.get(category)) % 1000003;
  }
  return text.length + "/" + rows + "/" + totals.size + "/" + checksum;
}

return { expected: "895060/26997/8/60009", result: main() };
