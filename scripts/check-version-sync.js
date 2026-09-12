#!/usr/bin/env node
// Verify the release version is identical in all three places it is stored.
// scripts/bump-version.js WRITES them in sync; this script CHECKS that they
// still are, so a manual edit or a forgotten bump fails CI instead of
// silently producing a release whose installer/update metadata disagree.
//
// NOTE: release.yml additionally re-checks package.json vs CMakeLists inline
// before publishing (defence in depth on the release path) — that duplication
// is deliberate; this script adds the installer.nsi leg and runs on every push.

const fs = require("fs")
const path = require("path")

const root = path.join(__dirname, '..')
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'))
const cmake = fs.readFileSync(path.join(root, 'particlebook-cpp/CMakeLists.txt'), 'utf8')
const nsi = fs.readFileSync(path.join(root, 'particlebook-cpp/scripts/installer.nsi'), 'utf8')

function grab(label, text, re) {
  const m = text.match(re);
  if (!m) {
    console.error(`  ${label}: NOT FOUND`);
    return null;
  }
  return m[1];
}

const versions = {
  'package.json': pkg.version,
  'CMakeLists.txt': grab('CMakeLists.txt', cmake, /project\(ParticleBook VERSION (\d+\.\d+\.\d+)/),
  'installer.nsi': grab('installer.nsi', nsi, /!define PRODUCT_VERSION "(\d+\.\d+\.\d+)"/),
};

console.log('version sources:');
for (const [k, v] of Object.entries(versions)) console.log(`  ${k.padEnd(18)} ${v}`);

const uniq = new Set(Object.values(versions));
if (uniq.size !== 1 || uniq.has(null)) {
  console.error('VERSION MISMATCH — run: node scripts/bump-version.js <x.y.z>');
  process.exit(1);
}
console.log(`VERSION SYNC OK (${[...uniq][0]} in all ${Object.keys(versions).length} places)`);
