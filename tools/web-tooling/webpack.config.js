// One bundle per Web Tooling Benchmark workload, for engine shells: the workload's library, the
// payload files it reads (a virtual fs holding only those), and global.__wtbBenchmark = {name, fn}.
// Run by build.sh with the benchmark's checkout as the working directory.
const fs = require("fs");
const path = require("path");
const webpack = require(path.resolve("node_modules/webpack"));
const targets = [...require(path.resolve("src/cli-flags-helper")).targetList];

const vfs = fs.readFileSync(path.resolve("src/vfs.js"), "utf8");
const payloads = [...vfs.matchAll(/"third_party\/([^"]+)",\n\s*require/g)].map(m => m[1]);
const gen = path.resolve("harness/gen");
fs.mkdirSync(gen, { recursive: true });

module.exports = targets.map(target => {
  const source = fs.readFileSync(path.resolve(`src/${target}-benchmark.js`), "utf8");
  const used = payloads.filter(p => source.includes(p));
  const dirs = [...new Set(used.map(p => path.dirname(p)).filter(d => d !== "."))];
  fs.writeFileSync(
    path.join(gen, `vfs-${target}.js`),
    'const virtualfs = require("virtualfs");\n' +
      "const fs = new virtualfs.VirtualFS();\n" +
      'fs.mkdirpSync("third_party");\n' +
      dirs.map(d => `fs.mkdirpSync("third_party/${d}");\n`).join("") +
      used
        .map(p => `fs.writeFileSync("third_party/${p}", require("raw-loader!../../third_party/${p}"));\n`)
        .join("") +
      "module.exports = fs;\n"
  );
  fs.writeFileSync(
    path.join(gen, `entry-${target}.js`),
    `global.__wtbBenchmark = require("../../src/${target}-benchmark");\n`
  );
  return {
    context: path.resolve("src"),
    entry: path.join(gen, `entry-${target}.js`),
    output: { filename: `${target}.js`, path: path.resolve("harness/dist") },
    bail: true,
    resolve: {
      alias: {
        fs: path.join(gen, `vfs-${target}.js`),
        module: path.resolve("src/mocks/dummy")
      }
    },
    plugins: [
      new webpack.BannerPlugin({
        banner:
          "var global = this;\n" +
          "if (typeof console === 'undefined') {\n" +
          "  console = {log: print};\n" +
          "}",
        raw: true
      }),
      new webpack.DefinePlugin({ ONLY: JSON.stringify(target) })
    ]
  };
});
