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
import { dirname, join, relative, sep } from "path"
import { execFileSync } from "child_process"

const here = dirname(fileURLToPath(import.meta.url))
const ROOT = join(here, "..")
const OUT = join(ROOT, "src/renderer/types/electron.d.ts")
const LF = String.fromCharCode(10)
const CR = String.fromCharCode(13)
// 比对时归一化行尾：本仓库 core.autocrlf=true，全新检出会得到 CRLF，而生成器
// 输出 LF。校验的目的在于「内容是否同步」，不是行尾字节，故两侧先去 CR 再比。
const normEol = (s) => s.split(CR).join("")
// 保持文件既有行尾再写：本仓库 autocrlf=true 时 git 在 Windows 上检出为 CRLF，
// 若生成器一律写 LF，文件在磁盘上就不是 git 期望的形态，git status 会长期显示
// 修改（而 git diff 为空）。写回同样的行尾可避免这种困惑。
const eolOf = (s) => (s.indexOf(CR) >= 0 ? CR + LF : LF)

// 文件不存在时向 git 询问它期望的行尾，而不是猜：
//   w/crlf|w/lf -> 直接采用（git 已物化的形态）
//   w/none      -> 文件缺失，按 index 形态结合 core.autocrlf 推断
// 若 git 不可用则退回 LF（CI 上即为正确值）。
function preferredEol(absPath) {
  try {
    const rel = relative(ROOT, absPath).split(sep).join("/")
    const out = execFileSync("git", ["ls-files", "--eol", "--", rel], {
      cwd: ROOT,
      encoding: "utf8"
    })
    const w = out.match(/(?:^|\s)w\/(\S+)/)
    if (w && w[1] === "crlf") return CR + LF
    if (w && w[1] === "lf") return LF
    if (/\bi\/lf\b/.test(out)) {
      const ac = execFileSync("git", ["config", "--get", "core.autocrlf"], {
        cwd: ROOT,
        encoding: "utf8"
      }).trim()
      if (ac === "true") return CR + LF
    }
  } catch {
    /* git 不可用则用默认 */
  }
  return LF
}
const CHECK = process.argv.includes("--check")

const { contract, types } = await import(
  pathToFileURL(join(ROOT, "bridge/contract.mjs")).href
)

function render() {
  const L = []
  L.push("// " + "═".repeat(71))
  L.push("// 此文件由 scripts/gen-bridge-dts.mjs 从 bridge/contract.mjs 生成。")
  L.push("// 请勿手改：改动会在下一次生成时丢失，且 CI 的新鲜度校验会失败。")
  L.push("// 要改契约请改表，然后运行： npm run gen:bridge-dts")
  L.push("// " + "═".repeat(71))
  L.push("")
  L.push("// Shapes returned by electronAPI.readFile: raw bytes (JSON byte array or a")
  L.push("// typed array), a virtual-host reference the caller must fetch, or null when the")
  L.push("// read failed (C++ returns json(nullptr) for an unreadable/missing file — the")
  L.push("// caller MUST treat null as failure, not as an empty file).")
  L.push("type PbVirtualHostRef = { _pb_url: string }")
  L.push("type PbFileContent = Uint8Array | number[] | PbVirtualHostRef | null")
  L.push("")
  L.push("// 数据形状：与 C++ 侧实际拼装对齐，见 bridge/contract.mjs 中 types 的注释。")
  for (const t of types || []) {
    L.push("interface " + t.name + (t.extends ? " extends " + t.extends : "") + " {")
    for (const b of t.body) L.push(b)
    L.push("}")
    L.push("")
  }
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
  // 内容已等价则跳过写入：避免无谓地更新 mtime（会让 git status 出现假的
  // " M"，直到 git 刷新 stat 缓存为止），使本命令对 git 幂等。
  if (existsSync(OUT) && normEol(readFileSync(OUT, "utf8")) === normEol(generated)) {
    console.log("electron.d.ts already in sync (" + contract.length + " members, 未改写)")
  } else {
    const eol = existsSync(OUT) ? eolOf(readFileSync(OUT, "utf8")) : preferredEol(OUT)
    writeFileSync(OUT, generated.split(LF).join(eol))
    console.log("generated electron.d.ts (" + contract.length + " members)")
  }
}
