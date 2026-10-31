#!/usr/bin/env node
// ── 打包前清掉本机正文缓存（2026-10-31）──────────────────────────────
// particlebook-cpp/build2/renderer 下有两个运行时缓存目录：
//   _pb_files —— file:read 把源书复制到这里供虚拟主机 fetch（64 个上限，按时间淘汰）
//   _pb_pdf   —— PDF 渲染缓存
// installer.nsi 用 File /r "..\build2\renderer\*.*" 整目录打包 —— 本机打包时
// 会把**我们自己下载/测试过的书**一起打进安装包。CI 是干净构建所以没有这个问题，
// 但本地跑 npm run package 必须先清掉；scripts/check-installer-manifest.mjs 也会拦。
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const RENDERER = path.join(ROOT, 'particlebook-cpp', 'build2', 'renderer')

if (!fs.existsSync(RENDERER)) {
  console.log('clean-renderer-cache: 没有 build2/renderer，跳过（干净构建）')
  process.exit(0)
}

const sizeOf = (p) => {
  const st = fs.statSync(p)
  if (!st.isDirectory()) return st.size
  return fs.readdirSync(p).reduce((n, name) => n + sizeOf(path.join(p, name)), 0)
}

let removed = 0
let bytes = 0
for (const name of fs.readdirSync(RENDERER)) {
  if (!name.startsWith('_pb_')) continue
  const target = path.join(RENDERER, name)
  const size = sizeOf(target)
  fs.rmSync(target, { recursive: true, force: true })
  removed += 1
  bytes += size
  console.log('  removed ' + name + ' (' + size + ' B)')
}

console.log('clean-renderer-cache: 清掉 ' + removed + ' 个缓存目录，共 ' + bytes + ' B')
