#!/usr/bin/env node
// 桥接接口的「有无消费者」审计 —— 只报告，不失败（不设 exit 1）。
//
// 背景：清理死接口时踩过三次坑，这个脚本把正确的判定方式固化下来：
//   1. 必须排除【生成的】src/renderer/types/electron.d.ts —— 它必然包含全部成员名，
//      把它算作消费者会让审计变成全绿假象；
//   2. 必须扫描【嵌在 C++ 里的注入 JS】（如 App.cpp 的工具栏脚本），否则会漏判；
//   3. 判定要用真实调用形态 electronAPI.<成员>（含解构赋值），
//      用裸成员名会命中 C++ 的注册字符串（如 "db:deleteBookSettings"）而假阳性。
//
// 表中标了 reserved: true 的条目是为将来功能预留的，单独归类，不计入待处理。

import { readFileSync, readdirSync, existsSync } from "fs"
import { fileURLToPath, pathToFileURL } from "url"
import { dirname, join } from "path"

const here = dirname(fileURLToPath(import.meta.url))
const ROOT = join(here, "..")
const { contract } = await import(pathToFileURL(join(ROOT, "bridge/contract.mjs")).href)

// 路径比较一律先归一成 "/"：本仓库在 Windows 上开发、CI 也在 Windows，但审计
// 脚本必须跨平台成立 —— 用 endsWith("types\\electron.d.ts") 这种带反斜杠的写法
// 在 Linux/macOS 上永远 false，生成的 d.ts（含全部成员名）会被当成消费者，
// 审计变成"全绿假象"（正是本文件头注释第 1 条要防的坑）。
const norm = (p) => p.split("\\").join("/")
const files = []
const walk = (d) => {
  for (const e of readdirSync(d, { withFileTypes: true })) {
    const p = join(d, e.name)
    if (e.isDirectory()) walk(p)
    else if (/\.(ts|tsx|js|mjs|cpp|h)$/.test(e.name)) files.push(p)
  }
}
walk(join(ROOT, "src/renderer"))
walk(join(ROOT, "particlebook-cpp/src"))
// 注入脚本的真源在 particlebook-cpp/resources/*.js（App.cpp 里只剩内置回退），
// 它们是"原始消息通道"上 zlib 事件的主要消费者，必须一起扫。
const RESOURCES_DIR = join(ROOT, "particlebook-cpp/resources")
if (existsSync(RESOURCES_DIR)) walk(RESOURCES_DIR)
const consumers = files.filter((f) => {
  const n = norm(f)
  return !n.endsWith("types/electron.d.ts") && !n.endsWith("BridgeServer.cpp")
});
const text = consumers.map((f) => readFileSync(f, "utf8")).join("\n");

const dead = [];
const deadReserved = [];
for (const e of contract) {
  if (e.kind === "local") continue;
  const call = new RegExp("electronAPI\\s*\\.\\s*" + e.member + "\\b");
  const destructuring = new RegExp(
    "\\b" + e.member + "\\b[^}]{0,200}\\}\\s*=\\s*window\\.electronAPI"
  );
  if (call.test(text) || destructuring.test(text)) continue;
  (e.reserved ? deadReserved : dead).push(e);
}

const total = contract.filter((e) => e.kind !== "local").length;
console.log(
  "bridge usage audit: " + total + " members, " +
  dead.length + " without a consumer, " +
  deadReserved.length + " reserved-but-unused"
);
if (deadReserved.length) {
  console.log("  [reserved, informational] " + deadReserved.map((e) => e.member).join(" "));
}
if (dead.length) {
  console.log("  [no consumer — consider removing or marking reserved:]");
  for (const e of dead) console.log("    " + e.member + "  (" + e.kind + ", " + e.method + ")");
} else {
  console.log("  every non-reserved member has a consumer");
}

// ── 反向检查：C++ 发出的事件是否有人消费 ─────────────────────────────
// 事件有两个消费通道，检查必须覆盖两者，否则会把活跃事件误判为死代码：
//   1. 桥接成员：存根里 onXxx -> onEvent('event:name')，渲染层调 electronAPI.onXxx
//   2. 原始消息通道：注入脚本直接监听 chrome.webview 消息并按事件名比较
//      （App.cpp 的 Z-Library 工具栏就是这样显示下载状态图标的）
// 这个盲区曾让我误删掉 6 个仍在使用的事件发射，故在此固化。
const cppFiles = []
const walkCpp = (d) => {
  for (const e of readdirSync(d, { withFileTypes: true })) {
    const p = join(d, e.name)
    if (e.isDirectory()) walkCpp(p)
    else if (/\.(cpp|h)$/.test(e.name) && !e.name.endsWith('BridgeServer.cpp')) cppFiles.push(p)
  }
}
walkCpp(join(ROOT, "particlebook-cpp/src"))
const cppText = cppFiles.map((f) => readFileSync(f, "utf8")).join("\n")
const emitted = new Set()
for (const m of cppText.matchAll(/EmitEvent\("([A-Za-z]+:[A-Za-z]+)"/g)) emitted.add(m[1])

const subscribed = new Set(contract.filter((e) => e.kind === "event").map((e) => e.method))
const orphanEmits = []
for (const ev of emitted) {
  if (subscribed.has(ev)) continue
  // 原始消息通道：JS 里以字符串字面量比较该事件名
  const byLiteral = new RegExp("['\"]" + ev + "['\"]").test(text)
  if (!byLiteral) orphanEmits.push(ev)
}
console.log(
  "  emitted events: " + emitted.size + ", " +
  (orphanEmits.length ? orphanEmits.length + " with NO consumer: " + orphanEmits.join(" ") : "all consumed")
);
