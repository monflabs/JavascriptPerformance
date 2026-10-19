// A lexer and an evaluator for a small expression language, wired together the way generator-
// based parsers are: the lexer is a generator over the source text and yields one token at a
// time, the recursive-descent evaluator pulls tokens through a one-token lookahead. The source
// is a program of assignments and arithmetic over earlier variables.
let seed = 777;
function random(limit) {
  seed = (seed * 48271) % 2147483647;
  return seed % limit;
}

function isDigit(c) {
  return c >= 48 && c <= 57;
}

function isNameStart(c) {
  return (c >= 97 && c <= 122) || c === 95;
}

function isNamePart(c) {
  return isNameStart(c) || isDigit(c);
}

function scanWhile(text, start, predicate) {
  let end = start;
  while (end < text.length && predicate(text.charCodeAt(end))) {
    end++;
  }
  return end;
}

function* tokenize(text) {
  let i = 0;
  while (i < text.length) {
    let c = text.charCodeAt(i);
    let end = i;
    if (c === 32 || c === 10) {
      i++;
    } else if (isDigit(c)) {
      end = scanWhile(text, i, isDigit);
      yield { type: "number", value: parseInt(text.substring(i, end), 10) };
      i = end;
    } else if (isNameStart(c)) {
      end = scanWhile(text, i, isNamePart);
      yield { type: "name", value: text.substring(i, end) };
      i = end;
    } else {
      yield { type: "punct", value: text.charAt(i) };
      i++;
    }
  }
}

function Evaluator(tokens, variables) {
  this.tokens = tokens;
  this.variables = variables;
  this.lookahead = tokens.next();
}

Evaluator.prototype.peek = function () {
  return this.lookahead.done ? null : this.lookahead.value;
};

Evaluator.prototype.take = function () {
  let token = this.peek();
  this.lookahead = this.tokens.next();
  return token;
};

Evaluator.prototype.program = function () {
  let last = 0;
  while (this.peek() !== null) {
    let name = this.take().value;
    this.take();
    last = this.expression();
    this.variables.set(name, last);
    this.take();
  }
  return last;
};

Evaluator.prototype.expression = function () {
  let value = this.term();
  for (let token = this.peek(); token !== null && (token.value === "+" || token.value === "-"); token = this.peek()) {
    this.take();
    let right = this.term();
    value = (token.value === "+" ? value + right : value - right) % 1000003;
  }
  return value;
};

Evaluator.prototype.term = function () {
  let value = this.factor();
  for (let token = this.peek(); token !== null && (token.value === "*" || token.value === "/"); token = this.peek()) {
    this.take();
    let right = this.factor();
    value = token.value === "*" ? (value * right) % 1000003 : Math.trunc(value / (right === 0 ? 1 : right));
  }
  return value;
};

Evaluator.prototype.factor = function () {
  let token = this.take();
  if (token.type === "number") {
    return token.value;
  }
  if (token.type === "name") {
    return this.variables.get(token.value);
  }
  if (token.value === "(") {
    let value = this.expression();
    this.take();
    return value;
  }
  if (token.value === "-") {
    return -this.factor();
  }
  throw new Error("unexpected token " + token.value);
};

function randomExpression(depth, defined) {
  let choice = random(depth > 0 ? 6 : 3);
  if (choice === 0) return String(1 + random(97));
  if (choice === 1 && defined > 0) return "v" + random(defined);
  if (choice < 3) return String(1 + random(9));
  let operator = "+-*/".charAt(random(4));
  let left = randomExpression(depth - 1, defined);
  let right = randomExpression(depth - 1, defined);
  return choice === 3 ? "(" + left + " " + operator + " " + right + ")" : left + " " + operator + " " + right;
}

let lines = [];
for (let i = 0; i < 8000; i++) {
  lines.push("v" + i + " = " + randomExpression(4, i) + ";");
}
let source = lines.join("\n");

let variables = new Map();
let evaluator = new Evaluator(tokenize(source), variables);
let last = evaluator.program();

let checksum = 0;
variables.forEach(function (value) {
  checksum = (checksum * 31 + (value % 1009)) % 1000003;
});

return { expected: "194782/8000/-47/306555", result: source.length + "/" + variables.size + "/" + last + "/" + checksum };
