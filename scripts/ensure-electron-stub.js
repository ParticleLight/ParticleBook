// electron-vite reads node_modules/electron/package.json to pick a Chromium
// build target, even though this app ships no Electron runtime (C++ Win32 +
// WebView2). Ensure a minimal stub exists so a fresh clone (npm ci) and CI can
// run `npm run build` without installing the real Electron package.
const fs = require('fs')
const path = require('path')

const stubPath = path.join(__dirname, '..', 'node_modules', 'electron', 'package.json')
if (!fs.existsSync(stubPath)) {
  fs.mkdirSync(path.dirname(stubPath), { recursive: true })
  fs.writeFileSync(stubPath, JSON.stringify({ name: 'electron', version: '37.0.0', private: true }, null, 2) + '\n')
  console.log('ensure-electron-stub: created node_modules/electron/package.json')
}
