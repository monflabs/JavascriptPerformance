// Backtracking search written as a generator, the textbook way to hand a caller "the next
// solution" without building the list of all of them: every level of the search is its own
// generator that delegates to the next level with yield*. The solver allocates and drives
// tens of thousands of short-lived generators, most of which never yield anything themselves.
function* place(size, row, columns, diagonals, antiDiagonals, placement) {
  if (row === size) {
    yield placement.slice();
    return;
  }
  for (let column = 0; column < size; column++) {
    let d = row + column;
    let a = row - column + size - 1;
    if (columns[column] || diagonals[d] || antiDiagonals[a]) {
      continue;
    }
    columns[column] = diagonals[d] = antiDiagonals[a] = true;
    placement[row] = column;
    yield* place(size, row + 1, columns, diagonals, antiDiagonals, placement);
    columns[column] = diagonals[d] = antiDiagonals[a] = false;
  }
}

function* solutions(size) {
  yield* place(size, 0, new Array(size).fill(false), new Array(2 * size).fill(false),
    new Array(2 * size).fill(false), new Array(size));
}

let counts = [];
let checksum = 0;
for (let size of [6, 7, 8, 9, 10]) {
  let count = 0;
  for (let placement of solutions(size)) {
    count++;
    let hash = 0;
    for (let i = 0; i < size; i++) {
      hash = (hash * 11 + placement[i]) % 100003;
    }
    checksum = (checksum + hash) % 1000003;
  }
  counts.push(count);
}

return { expected: "4,40,92,352,724/865999", result: counts.join(",") + "/" + checksum };
