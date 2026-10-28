#!/usr/bin/env node
// ── 更新日志完整性校验（2026-10-28）────────────────────────────────
// 「更新日志实时写」的护栏：src/renderer/data/changelog.ts 里每一条 change 的
// 中文文案都是 t() 的 key（ChangelogPanel 用 t(change.text) 渲染），英文模式靠
// i18n/locales/changelog.en.ts 的映射。因为 t() 的参数是变量，check-i18n-keys.js
// 扫不到这些 key —— 漏翻会静默回落到中文。本脚本补齐这一段：
//   1) 每条 change.text 必须在 changelog.en.ts 里有非空译文；
//   2) changelog.en.ts 里不允许留下已不存在的「孤儿」译文（提示，不失败）；
//   3) 版本条目必须按日期从新到旧排列（实时追加时最容易写错方向）。
// 用法：node scripts/check-changelog.mjs [--strict]
//   --strict 时孤儿译文也算失败（默认只警告）。

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const SRC = path.join(ROOT, 'src/renderer/data/changelog.ts')
const EN = path.join(ROOT, 'src/renderer/i18n/locales/changelog.en.ts')
const strict = process.argv.includes('--strict')

const unescape = (s) => s.replace(/\\(.)/g, (_, c) => ({ n: '\n', t: '\t', r: '\r' }[c] ?? c))

function parseChangelog(source) {
  const entries = []
  const entryRe = /version:\s*'([^']+)'[\s\S]*?date:\s*'([^']+)'([\s\S]*?)\n  \},/g
  let m
  while ((m = entryRe.exec(source))) {
    const [, version, date, body] = m
    const changes = []
    const changeRe = /text:\s*'((?:[^'\\]|\\.)*)'/g
    let c
    while ((c = changeRe.exec(body))) changes.push(unescape(c[1]))
    entries.push({ version, date, changes, unreleased: /unreleased:\s*true/.test(body) })
  }
  return entries
}

function parseEnDict(source) {
  const map = new Map()
  const re = /^\s*'((?:[^'\\]|\\.)*)':\s*'((?:[^'\\]|\\.)*)'\s*,?\s*$/gm
  let m
  while ((m = re.exec(source))) map.set(unescape(m[1]), unescape(m[2]))
  return map
}

const changelog = parseChangelog(fs.readFileSync(SRC, 'utf8'))
const dict = parseEnDict(fs.readFileSync(EN, 'utf8'))

const problems = []
const warnings = []
if (changelog.length === 0) problems.push('changelog.ts 解析不到任何版本条目（文件结构变了？）')

const allTexts = new Set()
for (const entry of changelog) {
  if (!entry.date || !/^\d{4}-\d{2}-\d{2}$/.test(entry.date)) {
    problems.push(`v${entry.version}: date 不是 YYYY-MM-DD（当前 "${entry.date}"）`)
  }
  if (entry.changes.length === 0) problems.push(`v${entry.version}: 没有任何 change 条目`)
  for (const text of entry.changes) {
    allTexts.add(text)
    if (!dict.has(text)) problems.push(`v${entry.version}: 缺英文译文 → ${text}`)
    else if (!dict.get(text).trim()) problems.push(`v${entry.version}: 英文译文为空 → ${text}`)
  }
}

for (const [key, value] of dict) {
  if (!allTexts.has(key)) warnings.push(`孤儿译文（changelog 里已无此文案）: ${key}${value ? '' : '（空）'}`)
}

for (let i = 1; i < changelog.length; i++) {
  if (changelog[i - 1].date < changelog[i].date) {
    problems.push(`版本顺序不是从新到旧: v${changelog[i - 1].version}(${changelog[i - 1].date}) 早于 v${changelog[i].version}(${changelog[i].date})`)
  }
}

const total = changelog.reduce((n, e) => n + e.changes.length, 0)
console.log(`changelog versions: ${changelog.length}, entries: ${total}, en translations: ${dict.size}`)
console.log(`latest: v${changelog[0]?.version} (${changelog[0]?.date})${changelog[0]?.unreleased ? ' · 未发布' : ''}`)
if (warnings.length) {
  console.log(`=== WARNINGS (${warnings.length}) ===`)
  for (const w of warnings) console.log('  ' + w)
}
if (problems.length) {
  console.log(`=== PROBLEMS (${problems.length}) ===`)
  for (const p of problems) console.log('  ' + p)
  process.exit(1)
}
if (strict && warnings.length) {
  console.log('STRICT: 孤儿译文视为失败')
  process.exit(1)
}
console.log('CHANGELOG OK')
