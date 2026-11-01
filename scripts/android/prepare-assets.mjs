// Assembles android/app/src/main/assets/www from the production renderer build
// plus the C++ bridge glue, and seeds sample books for the P0 harness.
import fs from 'node:fs'
import path from 'node:path'

const repoRoot = process.cwd()
const www = path.join(repoRoot, 'android/app/src/main/assets')
const seed = path.join(repoRoot, 'android/app/src/main/assets/seed')
const renderer = process.env.PB_RENDERER || 'F:/VScode/Read/out/renderer'
const samples = process.env.PB_SAMPLES || 'F:/ParticleBookSamples'

// wipe only the web bundle, keep assets/seed
for (const entry of fs.readdirSync(www)) {
  if (entry === 'seed') continue
  fs.rmSync(path.join(www, entry), { recursive: true, force: true })
}
fs.mkdirSync(www, { recursive: true })
fs.mkdirSync(seed, { recursive: true })

fs.cpSync(renderer, www, { recursive: true })
console.log('renderer ->', www)

const bridgeCpp = fs.readFileSync(path.join(repoRoot, 'particlebook-cpp/src/BridgeServer.cpp'), 'utf8')
const start = bridgeCpp.indexOf('R"SCRIPT(')
const end = bridgeCpp.indexOf(')SCRIPT"', start)
if (start < 0 || end < 0) throw new Error('bridge glue markers not found')
const glue = bridgeCpp.slice(start + 'R"SCRIPT('.length, end)
fs.writeFileSync(path.join(www, 'bridge.js'), glue)
console.log('glue bytes =', Buffer.byteLength(glue))

const copies = [
  ['01 故乡的河.epub', 'seed-01.epub'],
  ['03 统计之美.pdf', 'seed-03.pdf'],
]
for (const [src, dst] of copies) {
  fs.copyFileSync(path.join(samples, src), path.join(seed, dst))
}

const now = new Date().toISOString()
const book = (id, title, author, format, file, size) => ({
  id, title, author, format,
  file_path: '/data/user/0/app.particlebook/files/_pb_files/' + file,
  file_size: size, description: null, publisher: null, cover_path: null,
  language: 'zh', isbn: null, added_at: now, last_opened: null,
  tags: [], series_name: '', series_index: 0, rating: 0,
})
const fixtures = {
  books: [
    book(1, '故乡的河', '沈从文', 'epub', 'seed-01.epub', 94478),
    book(2, '统计之美', null, 'pdf', 'seed-03.pdf', 152591),
  ],
  bookshelves: [],
  settings: { theme: 'light', fontSize: 18, lineHeight: 1.7, margin: 24, language: 'zh', defaultViewMode: 'grid', defaultSortBy: 'added_at' },
}
fs.writeFileSync(path.join(www, 'fixtures.json'), JSON.stringify(fixtures, null, 2))
console.log('fixtures written')
console.log('www size bytes =', fs.readdirSync(www).length, 'top-level entries')