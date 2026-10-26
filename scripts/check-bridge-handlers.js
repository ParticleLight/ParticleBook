#!/usr/bin/env node
// 校验「桥接契约 invoke 方法」与「C++ RegisterMethod 注册」是否对齐。
//
// 为什么需要：bridge/contract.mjs 声明 kind=invoke 只影响生成的 electron.d.ts 与
// 注入存根；C++ 若忘了 RegisterMethod，前端调用发得出去，但只会在运行时拿到
// "Method not found: <method>" 的 reject。现有的 check-bridge-contract.js 只比对
// 【存根 ↔ 表】，看不到「C++ 没注册」这一层，本脚本补上：
//
//   期望集合 = contract 中 kind=invoke 的 method
//   实际集合 = particlebook-cpp/src/**/*.cpp 里 RegisterMethod("<name>", ...) 的字面量
//
// 规则（任一不满足即 exit 1）：
//   A. 期望集合 ⊆ 实际集合 —— 缺失就是运行时 Method not found。
//   B. 实际集合 - 期望集合的每一项，必须在 scripts/bridge-handler-allowlist.json
//      里显式放行并写原因（Z-Library 内部方法：走原生 raw 消息或 C++ 内部调用，
//      不是渲染层 electronAPI 成员）。
//   C. 白名单自身不许腐化：条目已回到契约、已无对应注册、重复或字段不全都算失败。
//   D. 同一方法被重复注册（后注册覆盖先注册，前一个 handler 变死代码）也算失败。
//
// 只扫 .cpp：注册全部发生在 .cpp，.h 里只有 RegisterMethod 声明。
// 注释掉的 RegisterMethod 不计入：先去掉 // 与 /* */，并跳过普通字符串、字符
// 字面量与 raw string（BridgeServer.cpp/App.cpp 的注入 JS 就住在 raw/normal 字符串里）。
// 依赖：仅 Node 内置模块（Node 18+，Windows / Linux 均可跑）。

const fs = require("fs")
const path = require("path")
const { pathToFileURL } = require("url")

const ROOT = path.join(__dirname, "..")
const CONTRACT_PATH = path.join(ROOT, "bridge/contract.mjs")
const CPP_SRC = path.join(ROOT, "particlebook-cpp/src")
const ALLOWLIST_PATH = path.join(__dirname, "bridge-handler-allowlist.json")
const METHOD_SHAPE = /^[A-Za-z]+:[A-Za-z]+$/

const rel = (p) => path.relative(ROOT, p).split(path.sep).join("/")

// ── C++ 源码预处理 ────────────────────────────────────────────────
// 把注释替换成空格（保留换行，行号不变），字符串字面量原样保留 —— 方法名就在
// 字符串里，不能一起抹掉。raw string 的内容替换为空格：它里面是注入的 JS，
// 不该被当成 C++ 注册语句扫描。
function stripComments(src) {
  let out = ""
  let i = 0
  while (i < src.length) {
    const c = src[i]
    const next = src[i + 1]

    if (c === "/" && next === "/") {
      const nl = src.indexOf("\n", i + 2)
      const stop = nl === -1 ? src.length : nl
      out += " ".repeat(stop - i)
      i = stop
      continue
    }
    if (c === "/" && next === "*") {
      const end = src.indexOf("*/", i + 2)
      const stop = end === -1 ? src.length : end + 2
      out += src.slice(i, stop).replace(/[^\n]/g, " ")
      i = stop
      continue
    }
    const rawEnd = rawStringEnd(src, i)
    if (rawEnd !== -1) {
      out += src.slice(i, rawEnd).replace(/[^\n]/g, " ")
      i = rawEnd
      continue
    }
    if (c === '"' || c === "'") {
      let j = i + 1
      while (j < src.length && src[j] !== c) {
        if (src[j] === "\\") j++
        j++
      }
      const stop = Math.min(j + 1, src.length)
      out += src.slice(i, stop)
      i = stop
      continue
    }
    out += c
    i++
  }
  return out
}

// 若 i 处是 C++ raw string 起点（R"delim( ... )delim"），返回其结束下标；否则 -1。
// 兼容 u8R" / uR" / UR" / LR" 前缀。
function rawStringEnd(src, i) {
  if (src[i] !== "R" || src[i + 1] !== '"') return -1
  const prev = i > 0 ? src[i - 1] : ""
  if (/[A-Za-z0-9_]/.test(prev) && !/(u8|u|U|L)$/.test(src.slice(Math.max(0, i - 2), i))) {
    return -1
  }
  const open = src.indexOf("(", i + 2)
  if (open === -1) return -1
  const delim = src.slice(i + 2, open)
  if (delim.length > 16 || /[\s()\\]/.test(delim)) return -1
  const close = ")" + delim + '"'
  const end = src.indexOf(close, open + 1)
  return end === -1 ? src.length : end + close.length
}

// ── 扫描 ──────────────────────────────────────────────────────────
function walkCppFiles(dir, out = []) {
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, ent.name)
    if (ent.isDirectory()) walkCppFiles(p, out)
    else if (ent.name.endsWith(".cpp")) out.push(p)
  }
  return out
}

function scanRegisteredMethods() {
  const files = walkCppFiles(CPP_SRC)
  const methods = new Map() // name -> [{ file, line }]
  const re = /Register(?:Async)?Method\s*\(\s*"([^"\\]+)"/g
  for (const file of files) {
    const code = stripComments(fs.readFileSync(file, "utf8"))
    let m
    while ((m = re.exec(code)) !== null) {
      const line = code.slice(0, m.index).split("\n").length
      const name = m[1]
      if (!methods.has(name)) methods.set(name, [])
      methods.get(name).push({ file, line })
    }
  }
  return { files, methods }
}

async function loadContractInvokeMethods() {
  const mod = await import(pathToFileURL(CONTRACT_PATH).href)
  if (!Array.isArray(mod.contract)) {
    throw new Error("bridge/contract.mjs 没有导出数组 contract")
  }
  const methods = new Set()
  for (const e of mod.contract) {
    if (!e || e.kind !== "invoke") continue
    if (typeof e.method !== "string" || e.method === "") {
      throw new Error("契约条目 " + (e.member || "?") + " 声明 kind=invoke 但 method 为空")
    }
    methods.add(e.method)
  }
  return methods
}

// 白名单格式：{ "_comment": "...", "allow": [{ "method": "...", "reason": "..." }] }
// 返回 Map<method, reason>；结构错误直接计入 problems，非法条目不参与放行。
function loadAllowlist(problems) {
  const entries = new Map()
  if (!fs.existsSync(ALLOWLIST_PATH)) {
    problems.push("[allowlist] 缺少白名单文件: " + rel(ALLOWLIST_PATH))
    return entries
  }
  let data
  try {
    data = JSON.parse(fs.readFileSync(ALLOWLIST_PATH, "utf8"))
  } catch (e) {
    problems.push("[allowlist] JSON 解析失败: " + e.message)
    return entries
  }
  if (!data || !Array.isArray(data.allow)) {
    problems.push("[allowlist] 顶层必须是对象且含数组字段 allow")
    return entries
  }
  data.allow.forEach((item, idx) => {
    const at = "[allowlist] allow[" + idx + "]"
    if (!item || typeof item !== "object" || Array.isArray(item)) {
      problems.push(at + " 不是对象")
      return
    }
    if (typeof item.method !== "string" || !METHOD_SHAPE.test(item.method)) {
      problems.push(at + " method 缺失或形状异常（应为 domain:name）: " + JSON.stringify(item.method))
      return
    }
    if (typeof item.reason !== "string" || item.reason.trim() === "") {
      problems.push(at + " (" + item.method + ") 缺少 reason（必须写明为什么不在契约里）")
      return
    }
    if (entries.has(item.method)) {
      problems.push("[allowlist] 条目重复: " + item.method)
      return
    }
    entries.set(item.method, item.reason.trim())
  })
  return entries
}

// ── 主流程 ────────────────────────────────────────────────────────
async function main() {
  const problems = []
  const contractMethods = await loadContractInvokeMethods()
  const { files, methods: registered } = scanRegisteredMethods()
  const allowEntries = loadAllowlist(problems)

  // A. 契约有、C++ 没注册 → 运行时 Method not found
  const missing = [...contractMethods].filter((m) => !registered.has(m)).sort()
  for (const m of missing) problems.push("[missing] 契约 invoke 无 C++ 注册: " + m)

  // B. C++ 注册了、契约没有、白名单也没放行
  const extraUnlisted = [...registered.keys()]
    .filter((m) => !contractMethods.has(m) && !allowEntries.has(m))
    .sort()
  for (const m of extraUnlisted) {
    const locs = registered.get(m).map((l) => rel(l.file) + ":" + l.line).join(", ")
    problems.push("[unlisted] C++ 已注册但既不在契约、也不在白名单: " + m + "  (" + locs + ")")
  }

  // C. 白名单腐化：条目回到契约 / 已无对应注册
  for (const m of [...allowEntries.keys()].sort()) {
    if (contractMethods.has(m)) {
      problems.push("[allow-in-contract] 白名单条目已出现在 contract invoke 中，应删除: " + m)
    } else if (!registered.has(m)) {
      problems.push("[stale-allow] 白名单条目已无对应 RegisterMethod（已失效），应删除: " + m)
    }
  }

  // D. 重复注册：m_methods[name] 直接赋值，后注册覆盖先注册，前一个 handler 变死代码
  for (const [m, locs] of [...registered.entries()].sort((a, b) => (a[0] < b[0] ? -1 : 1))) {
    if (locs.length > 1) {
      problems.push(
        "[dup] 重复注册（后者覆盖前者）: " + m + "  (" +
        locs.map((l) => rel(l.file) + ":" + l.line).join(", ") + ")"
      )
    }
  }

  if (problems.length) {
    console.error("BRIDGE HANDLERS DRIFT (" + problems.length + "):")
    for (const p of problems) console.error("  - " + p)
    console.error("修法：")
    console.error('  - [missing] 在对应 *Handlers/*Service 的注册函数里补 bridge->RegisterMethod("<method>", ...)')
    console.error("  - [unlisted] 若确为 C++ 内部/原生消息层方法（不是 electronAPI 成员），加入 " +
      rel(ALLOWLIST_PATH) + " 并写明原因")
    console.error("  - [stale-allow] / [allow-in-contract] 直接删掉对应的白名单条目")
    process.exit(1)
  }

  const allowNames = [...allowEntries.keys()].sort()
  console.log(
    "BRIDGE HANDLERS OK (" + contractMethods.size + " invoke methods all registered; " +
    allowNames.length + " allowlisted internal handlers)"
  )
  if (allowNames.length) console.log("  allowlisted: " + allowNames.join(" "))
  console.log("  scanned " + files.length + " .cpp files under " + rel(CPP_SRC) +
    "; " + registered.size + " unique RegisterMethod names")
}

main().catch((e) => {
  console.error("BRIDGE HANDLERS CHECK CRASHED:")
  console.error(e && e.stack ? e.stack : e)
  process.exit(1)
})
