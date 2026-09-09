function makeCounter() {
    var n = 0;
    return {
        add: function(x) { n += x; },
        get: function() { return n; }
    };
}

var c = makeCounter();
for (var i = 0; i < 2000000; ++i)
    c.add(1);
var r = c.get();
