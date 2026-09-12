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

import { readFileSync, readdirSync } from "fs"
import { fileURLToPath, pathToFileURL } from "url"
import { dirname, join } from "path"

const here = dirname(fileURLToPath(import.meta.url))
const ROOT = join(here, "..")
const { contract } = await import(pathToFileURL(join(ROOT, "bridge/contract.mjs")).href)

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
const consumers = files.filter((f) =>
  !f.endsWith("types\\electron.d.ts") && !f.endsWith("BridgeServer.cpp")
);
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
