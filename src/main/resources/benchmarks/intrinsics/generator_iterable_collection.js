// A grid class that exposes itself through generators - its cells via [Symbol.iterator], the
// neighbours of a cell via a generator method - and the consumers real code writes against
// such an API: a breadth-first search, destructuring in for-of, Array.from with a mapper, and
// spread. The BFS creates one neighbour generator per visited cell.
let seed = 424242;
function random(limit) {
  seed = (seed * 48271) % 2147483647;
  return seed % limit;
}

function Grid(width, height, wallPercent) {
  this.width = width;
  this.height = height;
  this.cells = new Uint8Array(width * height);
  for (let i = 0; i < this.cells.length; i++) {
    this.cells[i] = random(100) < wallPercent ? 1 : 0;
  }
  for (let y = 0; y < 5; y++) {
    for (let x = 0; x < 5; x++) {
      this.cells[y * width + x] = 0;
      this.cells[(height - 1 - y) * width + (width - 1 - x)] = 0;
    }
  }
}

Grid.prototype[Symbol.iterator] = function* () {
  for (let y = 0; y < this.height; y++) {
    for (let x = 0; x < this.width; x++) {
      yield [x, y, this.cells[y * this.width + x]];
    }
  }
};

Grid.prototype.neighbors = function* (index) {
  let x = index % this.width;
  let y = (index - x) / this.width;
  if (x > 0 && this.cells[index - 1] === 0) yield index - 1;
  if (x < this.width - 1 && this.cells[index + 1] === 0) yield index + 1;
  if (y > 0 && this.cells[index - this.width] === 0) yield index - this.width;
  if (y < this.height - 1 && this.cells[index + this.width] === 0) yield index + this.width;
};

Grid.prototype.distancesFrom = function (start) {
  let distance = new Int32Array(this.cells.length).fill(-1);
  let queue = [start];
  distance[start] = 0;
  for (let head = 0; head < queue.length; head++) {
    let current = queue[head];
    for (let next of this.neighbors(current)) {
      if (distance[next] < 0) {
        distance[next] = distance[current] + 1;
        queue.push(next);
      }
    }
  }
  return distance;
};

let grid = new Grid(220, 220, 28);
let fromStart = grid.distancesFrom(0);
let fromEnd = grid.distancesFrom(grid.cells.length - 1);

let reachable = 0;
let farthest = 0;
for (let distance of fromStart) {
  if (distance >= 0) {
    reachable++;
    if (distance > farthest) farthest = distance;
  }
}

let walls = 0;
let rowWalls = new Array(grid.height).fill(0);
for (let cell of grid) {
  if (cell[2] === 1) {
    walls++;
    rowWalls[cell[1]]++;
  }
}

let densest = Math.max.apply(null, rowWalls);
let evenColumnCells = Array.from(grid, cell => (cell[0] % 2 === 0 ? 1 - cell[2] : 0));
let openEven = evenColumnCells.reduce((sum, value) => sum + value, 0);

return {
  expected: "34523/438/438/438/13436/82/17498",
  result: [reachable, farthest, fromStart[grid.cells.length - 1], fromEnd[0], walls, densest, openEven].join("/")
};
