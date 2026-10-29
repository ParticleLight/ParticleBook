#!/usr/bin/env node
// ── 安装包清单校验（2026-10-29 review F1/F2 的守卫）──────────────────
// 背景：installer.nsi 的 File 清单是手写的，和 CMake install() / 构建产物没有
// 任何自动对账，于是出过两次漏包：
//   * F1 mobitool.exe 没打进去 → 安装版 AZW3/KF8 转换静默失效；
//   * F2 renderer 只拷 *.js/*.css 白名单 → app-icon.png 漏包（关于页破图）。
//
// 本脚本把两件事固化成断言：
//   A. 运行时必需的可执行/DLL 必须出现在 nsi 的 File 清单里
//      （ParticleBook.exe / mutool.exe / mobitool.exe / WebView2Loader.dll）；
//   B. renderer 与 resources 必须用「按目录递归」的 File /r 规则打包，
//      不允许退回手写文件白名单（否则以后新增 png/woff/脚本仍会漏）；
//   C. build2 存在时（CI 构建之后、发布打包之前）再核对产物确实落地，
//      包括 renderer/app-icon.png 这个曾经漏掉的文件。
//
// 用法：node scripts/check-installer-manifest.mjs
//   exit 0 = OK；exit 1 = 有问题（逐条列出）
// 只依赖 Node 内置模块；Windows / Linux 都能跑（路径统一归一成 "/"）。

import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const NSI = join(ROOT, 'particlebook-cpp', 'scripts', 'installer.nsi')
const BUILD2 = join(ROOT, 'particlebook-cpp', 'build2')

const problems = []
const notes = []
const normPath = (p) => p.split('\\').join('/')

const REQUIRED_FILES = [
  'ParticleBook.exe',
  'mutool.exe',
  'mobitool.exe',
  'WebView2Loader.dll',
]
const REQUIRED_LICENSE_FILES = [
  'third_party/mobitool/COPYING',
  'third_party/mobitool/NOTICE.md',
  'third_party/mobitool/README.md',
  'third_party/mobitool/libmobi-0.12-9062742-src.tar.gz',
]
// 按目录打包的规则：目标目录 + 源目录前缀（归一化后的 nsi 相对路径）
const REQUIRED_DIR_RULES = [
  { label: 'renderer', sourcePrefix: '../build2/renderer' },
  { label: 'resources', sourcePrefix: '../build2/resources' },
]

if (!existsSync(NSI)) {
  console.error('INSTALLER MANIFEST FAIL: installer.nsi not found -> ' + NSI)
  process.exit(1)
}

// NSIS 注释以 ; 开头（字符串里的 ; 不能当注释，这里按引号状态判断）
function stripComment(line) {
  let out = ''
  let inQuote = false
  for (const ch of line) {
    if (ch === '"') inQuote = !inQuote
    if (ch === ';' && !inQuote) break
    out += ch
  }
  return out
}

// 把一条 File 指令拆成 { flags:Set, paths:[...] }；引号内的路径保留空格
function parseFileDirective(line) {
  const rest = line.replace(/^\s*File\s+/i, '')
  const flags = new Set()
  const paths = []
  const re = /"([^"]*)"|(\S+)/g
  let m
  while ((m = re.exec(rest)) !== null) {
    if (m[1] !== undefined) { if (m[1] !== '') paths.push(m[1]); continue }
    const token = m[2]
    if (token.startsWith('/')) { flags.add(token.toLowerCase()); continue }
    paths.push(token)
  }
  return { flags, paths }
}

const lines = readFileSync(NSI, 'utf8').split(/\r?\n/)
const fileDirectives = []
for (let i = 0; i < lines.length; i++) {
  const text = stripComment(lines[i]).trim()
  if (!/^File(\s|$)/i.test(text)) continue
  const parsed = parseFileDirective(text)
  fileDirectives.push({ line: i + 1, recursive: parsed.flags.has('/r'), paths: parsed.paths.map(normPath) })
}

const listed = new Set()
for (const directive of fileDirectives) for (const p of directive.paths) listed.add(p)

// A. 必需的可执行 / DLL
for (const name of REQUIRED_FILES) {
  const wanted = '../build2/' + name
  if (!listed.has(wanted)) problems.push('installer.nsi 缺少 File "' + wanted.replace(/\//g, '\\') + '"（' + name + ' 必须随包分发）')
}

// A2. mobitool 的 LGPL 合规文件（随二进制分发时必须附带许可证与源码）
for (const rel of REQUIRED_LICENSE_FILES) {
  const wanted = '../' + rel
  if (!listed.has(wanted)) problems.push('installer.nsi 缺少 "' + wanted + '"（mobitool.exe 是 LGPL-3.0-or-later，需随包提供许可证/源码）')
}

// B. renderer / resources 必须按目录递归打包
for (const rule of REQUIRED_DIR_RULES) {
  const covered = fileDirectives.some((directive) =>
    directive.recursive &&
    directive.paths.some((p) => p.startsWith(rule.sourcePrefix + '/') || p === rule.sourcePrefix),
  )
  if (!covered) {
    problems.push('installer.nsi 没有按目录打包 ' + rule.label +
      '（需要 File /r "' + rule.sourcePrefix.replace(/^..\//, '..\\').split('/').join('\\') +
      '\\*.*"，手写文件白名单会漏掉 app-icon.png / 未来 png·woff）')
  }
}

// A3. 许可证源文件必须真的在仓库里（nsi 是按 ..\third_party\... 相对路径打包的）
for (const rel of REQUIRED_LICENSE_FILES) {
  const source = join(ROOT, 'particlebook-cpp', ...rel.split('/'))
  if (!existsSync(source)) problems.push('缺少 mobitool 许可证/源码文件: particlebook-cpp/' + rel)
}

// C. build2 存在时核对产物
if (!existsSync(BUILD2)) {
  notes.push('build2/ 不存在 — 只做静态清单校验（产物核对跳过；CI 在构建后跑本脚本）')
} else {
  for (const name of REQUIRED_FILES) {
    if (!existsSync(join(BUILD2, name))) {
      problems.push('build2/' + name + ' 不存在 — 构建没有产出它（mobitool 由配置期解包 zip；其余由编译/POST_BUILD 生成）')
    }
  }
  const rendererDir = join(BUILD2, 'renderer')
  if (!existsSync(join(rendererDir, 'index.html'))) {
    problems.push('build2/renderer/index.html 不存在 — 前端没有 build 或 POST_BUILD 未拷贝')
  }
  if (existsSync(rendererDir) && !existsSync(join(rendererDir, 'app-icon.png'))) {
    problems.push('build2/renderer/app-icon.png 不存在 — 「关于」页图标会 404（src/renderer/public 由 Vite 复制）')
  }
  const resourcesDir = join(BUILD2, 'resources')
  if (!existsSync(resourcesDir)) {
    problems.push('build2/resources/ 不存在 — 注入脚本会回退到内置最小版本')
  } else {
    const jsFiles = readdirSync(resourcesDir).filter((f) => f.endsWith('.js'))
    if (jsFiles.length === 0) problems.push('build2/resources/ 里没有任何 .js 注入脚本')
    else notes.push('resources: ' + jsFiles.join(' '))
  }
}

const recursiveRules = fileDirectives.filter((d) => d.recursive)
  .map((d) => d.paths.join(' ')).join(' + ') || '(none)'

if (problems.length) {
  console.error('INSTALLER MANIFEST FAIL (' + problems.length + '):')
  for (const p of problems) console.error('  - ' + p)
  console.error('修法：在 particlebook-cpp/scripts/installer.nsi 的 Install 节补齐上面的 File/File /r 规则。')
  process.exit(1)
}

console.log('INSTALLER MANIFEST OK')
console.log('  required binaries: ' + REQUIRED_FILES.join(', '))
console.log('  recursive dir rules: ' + recursiveRules)
console.log('  mobitool license files: ' + REQUIRED_LICENSE_FILES.length)
for (const note of notes) console.log('  note: ' + note)
