const fs = require('fs'), path = require('path')
const root = 'src/renderer'
const files = []
;(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name)
    if (e.isDirectory()) { if (!p.includes('i18n')) walk(p) }
    else if (/\.(tsx|ts)$/.test(e.name)) files.push(p)
  }
})(root)

const keys = new Set()
const collect = (src) => {
  // t('key', ...), t("key", ...), i18n.t('key', ...)
  const re = /(?:i18n\.)?\bt\(\s*(['"`])((?:[^'"`]|\\.)*)\1\s*[,)]/g
  let m
  while ((m = re.exec(src))) keys.add(m[2])
}
for (const f of files) collect(fs.readFileSync(f, 'utf8'))

const dict = new Set()
const dictFiles = ['en-settings.ts', 'en-library.ts', 'en-reader.ts', 'en-ui.ts', 'changelog.en.ts']
for (const g of dictFiles) {
  const gSrc = fs.readFileSync('src/renderer/i18n/locales/' + g, 'utf8')
  const kre = /^\s*'((?:[^'\\]|\\.)*)':/gm
  let m
  while ((m = kre.exec(gSrc))) dict.add(m[1])
}

const missing = [...keys].filter((k) => {
  if (dict.has(k)) return false
  // plural variants
  if (dict.has(k + '_one') || dict.has(k + '_other')) return false
  return true
})

console.log('t() keys found:', keys.size)
console.log('en dict keys:', dict.size)
console.log('=== MISSING from en dict (' + missing.length + ') ===')
missing.sort().forEach((k) => console.log('  ' + k))
