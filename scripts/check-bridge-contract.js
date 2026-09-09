#!/usr/bin/env node
// Verify the bridge contract: the C++-injected JS stub (window.electronAPI)
// members vs the front-end type contract src/renderer/types/electron.d.ts.
// Both use the SAME (JS) naming — catches drift at CI time. exit 1 on drift.

const fs = require("fs")
const path = require("path")

const root = path.join(__dirname, '..')
const bridgeCpp = fs.readFileSync(path.join(root, 'particlebook-cpp/src/BridgeServer.cpp'), 'utf8')
const dtsPath = path.join(root, 'src/renderer/types/electron.d.ts')
const dts = fs.readFileSync(dtsPath, 'utf8')

// Members of window.electronAPI { name: function / async function ... }
const stubStart = bridgeCpp.indexOf('window.electronAPI =')
const stubEnd = bridgeCpp.indexOf('};', stubStart)
const stub = bridgeCpp.slice(stubStart, stubEnd)
const stubMembers = new Set()
const re = /\b([A-Za-z][A-Za-z0-9]*)\s*:\s*(?:async\s+)?function/g
let m; while ((m = re.exec(stub)) !== null) stubMembers.add(m[1]);

// Only members INSIDE `interface ElectronAPI { ... }` (exclude declare interface Window).
const iStart = dts.indexOf('interface ElectronAPI {')
const iEnd = dts.indexOf('\n}', iStart)
const iface = dts.slice(iStart, iEnd)
const ifaceMembers = new Set()
const re2 = /^\s{2}([A-Za-z][A-Za-z0-9]*):\s/mg
let g; while ((g = re2.exec(iface)) !== null) ifaceMembers.add(g[1]);

const missingInDts = [...stubMembers].filter(n => !ifaceMembers.has(n));
const extraInDts = [...ifaceMembers].filter(n => !stubMembers.has(n));

if (missingInDts.length || extraInDts.length) {
  console.error('BRIDGE CONTRACT DRIFT:');
  if (missingInDts.length) {
    console.error('  stub but MISSING in electron.d.ts:');
    missingInDts.forEach(n => console.error('    -', n));
  }
  if (extraInDts.length) {
    console.error('  in electron.d.ts but not in stub:');
    extraInDts.forEach(n => console.error('    -', n));
  }
  process.exit(1);
}

console.log('BRIDGE CONTRACT OK (' + stubMembers.size + ' methods match electron.d.ts)');