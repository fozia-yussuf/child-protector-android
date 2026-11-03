const fs = require("node:fs");
const path = require("node:path");

const source = path.resolve(__dirname, "../migrations");
const destination = path.resolve(__dirname, "../dist/migrations");
fs.mkdirSync(destination, { recursive: true });
for (const file of fs.readdirSync(source)) {
  if (file.endsWith(".sql")) {
    fs.copyFileSync(path.join(source, file), path.join(destination, file));
  }
}
