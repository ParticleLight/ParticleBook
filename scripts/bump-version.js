#!/usr/bin/env node
// Single source of truth for the release version. The version lives in three
// places (package.json, CMakeLists project(VERSION), installer.nsi fallback);
// this script keeps all three in sync. Run it before tagging a release, e.g.:
//   node scripts/bump-version.js 2.2.0
const fs = require('fs')
const path = require('path')

const version = process.argv[2]
if (!/^\d+\.\d+\.\d+$/.test(version || '')) {
  console.error('Usage: node scripts/bump-version.js <x.y.z>')
  process.exit(1)
}

// package.json
const pkgPath = path.join(__dirname, '..', 'package.json')
const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'))
pkg.version = version
fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + '\n')
console.log('package.json ->', version)

// CMakeLists.txt project(VERSION x.y.z)
const cmakePath = path.join(__dirname, '..', 'particlebook-cpp', 'CMakeLists.txt')
let cmake = fs.readFileSync(cmakePath, 'utf8')
const m = cmake.match(/project\(ParticleBook VERSION \d+\.\d+\.\d+/)
if (!m) {
  console.error('CMakeLists.txt: could not find project(ParticleBook VERSION ...)')
  process.exit(1)
}
cmake = cmake.replace(m[0], `project(ParticleBook VERSION ${version}`)
fs.writeFileSync(cmakePath, cmake)
console.log('CMakeLists.txt ->', version)

// installer.nsi fallback !define PRODUCT_VERSION (used only when makensis runs
// without /DPRODUCT_VERSION=)
const nsiPath = path.join(__dirname, '..', 'particlebook-cpp', 'scripts', 'installer.nsi')
let nsi = fs.readFileSync(nsiPath, 'utf8')
if (!/!define PRODUCT_VERSION "\d+\.\d+\.\d+"/.test(nsi)) {
  console.error('installer.nsi: could not find PRODUCT_VERSION fallback')
  process.exit(1)
}
nsi = nsi.replace(/!define PRODUCT_VERSION "\d+\.\d+\.\d+"/, `!define PRODUCT_VERSION "${version}"`)
fs.writeFileSync(nsiPath, nsi)
console.log('installer.nsi ->', version)

console.log(`\nDone. Commit all three files, then tag: git tag v${version} && git push origin v${version}`)
