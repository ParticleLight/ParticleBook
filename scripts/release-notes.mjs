#!/usr/bin/env node
// ── 生成 GitHub Release 正文（2026-10-31）────────────────────────────
// 发布正文 = src/renderer/data/changelog.ts 里「当前 package.json 版本」的全部条目，
// 按 新增 / 修复 / 改进 分组。这样应用内更新日志与 Release 说明永远是同一份文案，
// 不用在打 tag 前手工抄一遍。
//
// 用法：node scripts/release-notes.mjs [输出路径]
//   默认输出 particlebook-cpp/build2/release-notes.md（release.yml 里作为 body_path）
// 解析方式与 scripts/check-changelog.mjs 保持一致（同样容忍转义引号）。

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const SRC = path.join(ROOT, 'src/renderer/data/changelog.ts')
const OUT = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.join(ROOT, 'particlebook-cpp/build2/release-notes.md')

const unescape = (s) => s.replace(/\\(.)/g, (_, c) => ({ n: '\n', t: '\t', r: '\r' }[c] ?? c))

const source = fs.readFileSync(SRC, 'utf8')
const entryRe = /version:\s*'([^']+)'[\s\S]*?date:\s*'([^']+)'([\s\S]*?)\n  \},/g
const entries = []
let m
while ((m = entryRe.exec(source))) {
  const [, version, date, body] = m
  const changes = []
  const changeRe = /\{\s*type:\s*'(feature|fix|improve)',\s*text:\s*'((?:[^'\\]|\\.)*)'\s*\}/g
  let c
  while ((c = changeRe.exec(body))) changes.push({ type: c[1], text: unescape(c[2]) })
  entries.push({ version, date, changes })
}

const pkg = JSON.parse(fs.readFileSync(path.join(ROOT, 'package.json'), 'utf8'))
const version = pkg.version
const entry = entries.find((e) => e.version === version)
if (!entry) {
  console.error('release-notes: changelog.ts 里找不到版本 ' + version)
  process.exit(1)
}
if (entry.changes.length === 0) {
  console.error('release-notes: v' + version + ' 没有任何 changelog 条目')
  process.exit(1)
}

const SECTIONS = [
  { type: 'feature', title: '新增' },
  { type: 'fix', title: '修复' },
  { type: 'improve', title: '改进' },
]

const lines = []
lines.push('## ParticleBook v' + version, '')
lines.push('发布日期：' + entry.date, '')
for (const section of SECTIONS) {
  const items = entry.changes.filter((c) => c.type === section.type)
  if (items.length === 0) continue
  lines.push('### ' + section.title + '（' + items.length + '）', '')
  for (const item of items) lines.push('- ' + item.text)
  lines.push('')
}
lines.push('---', '')
lines.push('下载 ParticleBook-Setup-v' + version + '.exe 安装或覆盖升级；已安装的用户也可以在应用内直接更新。')
lines.push('')

fs.mkdirSync(path.dirname(OUT), { recursive: true })
fs.writeFileSync(OUT, lines.join('\n'), 'utf8')
const counts = SECTIONS.map((s) => s.title + ' ' + entry.changes.filter((c) => c.type === s.type).length).join(' / ')
console.log('release notes -> ' + OUT)
console.log('v' + version + ': ' + entry.changes.length + ' 条（' + counts + '）')
