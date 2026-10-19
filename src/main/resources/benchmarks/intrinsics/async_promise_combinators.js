// Promise-chain code, as written before async/await or wherever a library API hands out
// promises: documents are fetched, parsed, validated and normalised with then/catch/finally,
// loaded in sequential batches with reduce and Promise.all, probed with Promise.allSettled,
// and a failing source is raced against a fallback with Promise.race.
let seed = 2718;
function random(limit) {
  seed = (seed * 48271) % 2147483647;
  return seed % limit;
}

const WORDS = ["alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa"];

function makeBody(id) {
  const words = [];
  for (let i = 0, n = 5 + random(20); i < n; i++) {
    words.push(WORDS[random(WORDS.length)]);
  }
  return id + " " + words.join(" ");
}

let completed = 0;
let recovered = 0;

function fetchDocument(id) {
  return Promise.resolve(id).then(key => {
    if (key % 53 === 0) {
      throw new Error("document " + key + " not found");
    }
    return { id: key, body: makeBody(key) };
  });
}

function parse(document) {
  const words = document.body.split(" ");
  return Promise.resolve({ id: document.id, words: words.slice(1) });
}

function validate(parsed) {
  if (parsed.id % 61 === 0) {
    throw new Error("document " + parsed.id + " failed validation");
  }
  return parsed;
}

function summarize(parsed) {
  const counts = {};
  for (let word of parsed.words) {
    counts[word] = (counts[word] || 0) + 1;
  }
  const top = Object.keys(counts).sort((a, b) => counts[b] - counts[a] || (a < b ? -1 : 1))[0];
  return { id: parsed.id, length: parsed.words.length, top };
}

function load(id) {
  return fetchDocument(id)
    .then(parse)
    .then(validate)
    .then(summarize)
    .catch(error => {
      recovered++;
      return { id, length: 0, top: error.message.indexOf("not found") >= 0 ? "missing" : "invalid" };
    })
    .finally(() => {
      completed++;
    });
}

const ids = [];
for (let i = 1; i <= 8000; i++) {
  ids.push(i);
}
const chunks = [];
for (let i = 0; i < ids.length; i += 200) {
  chunks.push(ids.slice(i, i + 200));
}

const totals = { length: 0, missing: 0, invalid: 0, topAlpha: 0 };

const sequential = chunks.reduce((previous, chunk) =>
  previous
    .then(() => Promise.all(chunk.map(load)))
    .then(results => {
      for (let summary of results) {
        totals.length += summary.length;
        if (summary.top === "missing") totals.missing++;
        else if (summary.top === "invalid") totals.invalid++;
        else if (summary.top === "alpha") totals.topAlpha++;
      }
    }), Promise.resolve());

const probe = sequential
  .then(() => Promise.allSettled(ids.slice(0, 2000).map(id => fetchDocument(id).then(validate))))
  .then(settled => {
    let fulfilled = 0;
    let rejected = 0;
    for (let entry of settled) {
      if (entry.status === "fulfilled") fulfilled++;
      else rejected++;
    }
    return fulfilled * 10000 + rejected;
  });

const failover = probe.then(probeResult => {
  const primary = new Promise((resolve, reject) => reject(new Error("primary down")));
  const replica = Promise.resolve("replica");
  return Promise.race([primary.catch(() => replica), replica]).then(winner => probeResult + ":" + winner);
});

return {
  expected: "8000/279/111425/150/129/1299/19310069:replica",
  result: failover.then(summary =>
    [completed, recovered, totals.length, totals.missing, totals.invalid, totals.topAlpha, summary].join("/"))
};
