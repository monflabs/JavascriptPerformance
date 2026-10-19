// Sorted set union over two binary search trees, the way a database or an ordered-collection
// library does it: each tree is exposed as a lazy in-order iterator (recursive yield*), the
// two streams are merged, and duplicates are dropped. Every key crosses a stack of nested
// yield* delegations as deep as the tree is.
let seed = 12345;
function random() {
  seed = (seed * 48271) % 2147483647;
  return seed;
}

function TreeNode(key) {
  this.key = key;
  this.left = null;
  this.right = null;
}

function SortedTree() {
  this.root = null;
  this.size = 0;
}

SortedTree.prototype.add = function (key) {
  let node = new TreeNode(key);
  if (this.root === null) {
    this.root = node;
    this.size++;
    return;
  }
  let current = this.root;
  for (;;) {
    if (key === current.key) {
      return;
    }
    let side = key < current.key ? "left" : "right";
    if (current[side] === null) {
      current[side] = node;
      this.size++;
      return;
    }
    current = current[side];
  }
};

function* walk(node) {
  if (node !== null) {
    yield* walk(node.left);
    yield node.key;
    yield* walk(node.right);
  }
}

SortedTree.prototype[Symbol.iterator] = function () {
  return walk(this.root);
};

function* mergeSorted(first, second) {
  let a = first.next();
  let b = second.next();
  while (!a.done || !b.done) {
    if (b.done || (!a.done && a.value <= b.value)) {
      yield a.value;
      a = first.next();
    } else {
      yield b.value;
      b = second.next();
    }
  }
}

function* distinct(values) {
  let last = -1;
  for (let value of values) {
    if (value !== last) {
      yield value;
      last = value;
    }
  }
}

function buildTree(count) {
  let tree = new SortedTree();
  for (let i = 0; i < count; i++) {
    tree.add(random() % 1000000);
  }
  return tree;
}

let left = buildTree(20000);
let right = buildTree(20000);

let count = 0;
let checksum = 0;
for (let key of distinct(mergeSorted(left[Symbol.iterator](), right[Symbol.iterator]()))) {
  count++;
  checksum = (checksum + key * (count % 97)) % 1000003;
}

return { expected: "19814/19799/39226/407611", result: left.size + "/" + right.size + "/" + count + "/" + checksum };
