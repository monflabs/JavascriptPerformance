// An asynchronous service handler: a pool of workers drains a queue of requests, each request
// awaiting a bounded LRU cache, falling back to a flaky "database" with retry on failure, and
// then fanning out to render its response. Only microtasks are involved (no timers), so the
// cost measured is async/await itself: creating promises, suspending and resuming functions,
// try/catch across awaits, Promise.all.
let seed = 31337;
function random(limit) {
  seed = (seed * 48271) % 2147483647;
  return seed % limit;
}

class LruCache {
  constructor(capacity) {
    this.capacity = capacity;
    this.entries = new Map();
  }

  async get(key) {
    if (!this.entries.has(key)) {
      return undefined;
    }
    const value = this.entries.get(key);
    this.entries.delete(key);
    this.entries.set(key, value);
    return value;
  }

  async put(key, value) {
    this.entries.delete(key);
    this.entries.set(key, value);
    if (this.entries.size > this.capacity) {
      this.entries.delete(this.entries.keys().next().value);
    }
  }
}

const stats = { hits: 0, misses: 0, retries: 0, failures: 0 };
const firstAttempt = new Set();
const cache = new LruCache(400);

async function queryDatabase(key) {
  if (key % 23 === 0 && !firstAttempt.has(key)) {
    firstAttempt.add(key);
    throw new Error("connection reset while reading user " + key);
  }
  if (key % 997 === 0) {
    throw new Error("user " + key + " is corrupt");
  }
  return { id: key, name: "user" + key, score: (key * 7919) % 1000, tags: ["a" + (key % 5), "b" + (key % 7)] };
}

async function loadUser(key) {
  const cached = await cache.get(key);
  if (cached !== undefined) {
    stats.hits++;
    return cached;
  }
  stats.misses++;
  for (let attempt = 1; ; attempt++) {
    try {
      const row = await queryDatabase(key);
      await cache.put(key, row);
      return row;
    } catch (e) {
      if (attempt === 3 || e.message.indexOf("corrupt") >= 0) {
        stats.failures++;
        return null;
      }
      stats.retries++;
    }
  }
}

async function loadScore(user) {
  return user.score * 3 + user.tags.length;
}

async function loadBadges(user) {
  return user.tags.map(tag => tag.toUpperCase()).join("|");
}

async function render(user) {
  const [score, badges] = await Promise.all([loadScore(user), loadBadges(user)]);
  return user.name + ":" + score + ":" + badges;
}

async function handle(request) {
  const user = await loadUser(request.userId);
  if (user === null) {
    return 0;
  }
  const body = await render(user);
  let hash = request.id;
  for (let i = 0; i < body.length; i++) {
    hash = (hash * 31 + body.charCodeAt(i)) % 1000003;
  }
  return hash;
}

const requests = [];
for (let i = 0; i < 16000; i++) {
  requests.push({ id: i, userId: 1 + random(1 + random(1500)) });
}

async function main() {
  let next = 0;
  let checksum = 0;
  async function worker() {
    while (next < requests.length) {
      const request = requests[next++];
      checksum = (checksum + await handle(request)) % 1000003;
    }
  }
  const workers = [];
  for (let i = 0; i < 24; i++) {
    workers.push(worker());
  }
  await Promise.all(workers);
  return [stats.hits, stats.misses, stats.retries, stats.failures, checksum].join("/");
}

return { expected: "7301/8699/62/5/685354", result: main() };
