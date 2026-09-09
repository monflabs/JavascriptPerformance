function fib(n) {
    if (n < 2) return n;
    return fib(n - 1) + fib(n - 2);
}

var r = 0;
for (var i = 0; i < 10; ++i)
    r += fib(28);
