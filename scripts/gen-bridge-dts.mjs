#!/usr/bin/env node
// 从 bridge/contract.mjs 生成 src/renderer/types/electron.d.ts。
//
// 用法：
//   node scripts/gen-bridge-dts.mjs           写入生成物
//   node scripts/gen-bridge-dts.mjs --check   只校验生成物是否最新（CI 用）
//
// 生成物禁止手改：改动会在下次生成时丢失，且 --check 会失败。
import { readFileSync, writeFileSync, existsSync } from "fs"
import { fileURLToPath, pathToFileURL } from "url"
import { dirname, join } from "path"

const here = dirname(fileURLToPath(import.meta.url))
const ROOT = join(here, "..")
const OUT = join(ROOT, "src/renderer/types/electron.d.ts")
const LF = String.fromCharCode(10)
const CR = String.fromCharCode(13)
// 比对时归一化行尾：本仓库 core.autocrlf=true，全新检出会得到 CRLF，而生成器
// 输出 LF。校验的目的在于「内容是否同步」，不是行尾字节，故两侧先去 CR 再比。
const normEol = (s) => s.split(CR).join("")
const CHECK = process.argv.includes("--check")

const { contract } = await import(pathToFileURL(join(ROOT, "bridge/contract.mjs")).href)

function render() {
  const L = []
  L.push("// " + "═".repeat(71))
  L.push("// 此文件由 scripts/gen-bridge-dts.mjs 从 bridge/contract.mjs 生成。")
  L.push("// 请勿手改：改动会在下一次生成时丢失，且 CI 的新鲜度校验会失败。")
  L.push("// 要改契约请改表，然后运行： npm run gen:bridge-dts")
  L.push("// " + "═".repeat(71))
  L.push("")
  L.push("// Shapes returned by electronAPI.readFile: raw bytes (JSON byte array or a")
  L.push("// typed array), or a virtual-host reference the caller must fetch. Declared")
  L.push("// globally so both the interface and the renderer helper share one definition.")
  L.push("type PbVirtualHostRef = { _pb_url: string }")
  L.push("type PbFileContent = Uint8Array | number[] | PbVirtualHostRef")
  L.push("")
  L.push("interface ElectronAPI {")
  let lastGroup = null
  for (const e of contract) {
    if (e.group !== lastGroup) {
      if (lastGroup !== null) L.push("")
      L.push("  // " + e.group)
      lastGroup = e.group
    }
    const ps = (e.params || [])
      .map((p) => (p.o ? p.n + "?: " + p.t : p.n + ": " + p.t))
      .join(", ")
    L.push("  " + e.member + ": (" + ps + ") => " + e.returns)
  }
  L.push("}")
  L.push("")
  L.push("// 非桥接契约的手写附加项（不属于 bridge/contract.mjs 管理范围）。")
  L.push("declare interface Window {")
  L.push("  electronAPI: ElectronAPI")
  L.push("  __pbLang?: string")
  L.push("  __refreshLibrary?: () => void")
  L.push("  _droppedFiles?: { name: string; path: string }[]")
  L.push("}")
  L.push("")
  return L.join(LF)
}

const generated = render()

if (CHECK) {
  if (!existsSync(OUT)) {
    console.error("FRESHNESS FAIL: 生成物不存在 -> " + OUT)
    process.exit(1)
  }
  const current = readFileSync(OUT, "utf8")
  if (normEol(current) !== normEol(generated)) {
    console.error("FRESHNESS FAIL: electron.d.ts 与契约表不同步。")
    console.error("  契约表已改动但未重新生成，或生成物被手改。")
    console.error("  修复： npm run gen:bridge-dts")
    const a = normEol(current).split(LF), b = normEol(generated).split(LF)
    let shown = 0
    for (let i = 0; i < Math.max(a.length, b.length) && shown < 6; i++) {
      if (a[i] !== b[i]) {
        console.error("  第 " + (i + 1) + " 行:")
        console.error("    仓库: " + (a[i] === undefined ? "(缺行)" : a[i]))
        console.error("    应为: " + (b[i] === undefined ? "(多行)" : b[i]))
        shown++
      }
    }
    process.exit(1)
  }
  console.log("BRIDGE DTS FRESH (" + contract.length + " members, 生成物与契约表一致)")
} else {
  writeFileSync(OUT, generated)
  console.log("generated electron.d.ts (" + contract.length + " members)")
}
