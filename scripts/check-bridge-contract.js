#!/usr/bin/env node
// 校验桥接契约的三个方向：
//   1. 运行时真相：BridgeServer.cpp 注入的 JS 存根（window.electronAPI）
//   2. 权威表：bridge/contract.mjs
//   3. 生成物新鲜度：由 `node scripts/gen-bridge-dts.mjs --check` 单独校验
//      （分成两步，失败信息更精确）
//
// 本脚本断言：
//   A. 存根与表的成员名集合一致；
//   B. 每个成员的【参数个数】一致（此前完全没有这项检查）；
//   C. 每个成员的【桥接方法名】一致（能抓出表里写成 db:getBookmark 而存根
//      实际调 db:getBookmarks 这类错配）；
//   D. 表自身完整性：成员不重复、kind 合法、method 有无与 kind 匹配、方法名形状。
// 任一条不满足即 exit 1。

const fs = require("fs")
const path = require("path")
const { pathToFileURL } = require("url")

const CR = String.fromCharCode(13);
const LF = String.fromCharCode(10);
const stripCR = (s) => s.split(CR).join("");

async function main() {
  const root = path.join(__dirname, "..");
  const { contract } = await import(
    pathToFileURL(path.join(root, "bridge/contract.mjs")).href
  );

  // ── 解析存根：member -> { method, kind, arity } ──────────────────
  const cpp = stripCR(
    fs.readFileSync(path.join(root, "particlebook-cpp/src/BridgeServer.cpp"), "utf8")
  );
  const stubStart = cpp.indexOf("window.electronAPI =");
  const stub = cpp.slice(stubStart, cpp.indexOf("};", stubStart));
  const defRe = /^\s+([A-Za-z][A-Za-z0-9]*)\s*:\s*(?:async\s+)?function\s*\(([^)]*)\)/gm;
  const stubInfo = new Map();
  let m;
  while ((m = defRe.exec(stub)) !== null) {
    const braceStart = stub.indexOf("{", m.index + m[0].length - 1);
    let depth = 0, i = braceStart;
    for (; i < stub.length; i++) {
      if (stub[i] === "{") depth++;
      else if (stub[i] === "}") { depth--; if (depth === 0) break; }
    }
    const body = stub.slice(braceStart, i + 1);
    const inv = body.match(/invoke\(\s*['"]([a-zA-Z]+:[a-zA-Z]+)['"]/);
    const ev = body.match(/onEvent\(\s*['"]([a-zA-Z]+:[a-zA-Z]+)['"]/);
    const raw = m[2].trim();
    stubInfo.set(m[1], {
      method: inv ? inv[1] : (ev ? ev[1] : null),
      arity: raw === "" ? 0 : raw.split(",").length
    });
  }

  const problems = [];

  // ── D. 表自身完整性 ─────────────────────────────────────────────
  const seen = new Set();
  const KINDS = new Set(["invoke", "event", "local"]);
  for (const e of contract) {
    if (seen.has(e.member)) problems.push("表中成员重复: " + e.member);
    seen.add(e.member);
    if (!KINDS.has(e.kind)) problems.push(e.member + ": kind 非法 -> " + e.kind);
    if (e.kind === "local" && e.method !== null)
      problems.push(e.member + ": kind=local 但给了 method");
    if (e.kind !== "local" && !e.method)
      problems.push(e.member + ": kind=" + e.kind + " 但 method 为空");
    if (e.method && !/^[a-zA-Z]+:[a-zA-Z]+$/.test(e.method))
      problems.push(e.member + ": method 形状异常 -> " + e.method);
    if (!Array.isArray(e.params)) problems.push(e.member + ": params 不是数组");
    if (!e.returns) problems.push(e.member + ": 缺 returns");
  }

  // ── A/B/C. 存根 ↔ 表 ────────────────────────────────────────────
  const tableMembers = new Set(contract.map((e) => e.member));
  const onlyStub = [...stubInfo.keys()].filter((n) => !tableMembers.has(n));
  const onlyTable = [...tableMembers].filter((n) => !stubInfo.has(n));
  for (const n of onlyStub) problems.push("存根有、表无: " + n);
  for (const n of onlyTable) problems.push("表有、存根无: " + n);

  for (const e of contract) {
    const s = stubInfo.get(e.member);
    if (!s) continue;
    const want = (e.params || []).length;
    if (s.arity !== want)
      problems.push(e.member + ": 参数个数不一致 存根=" + s.arity + " 表=" + want);
    if (e.kind !== "local") {
      if (s.method !== e.method)
        problems.push(
          e.member + ": 方法名不一致 存根=" + s.method + " 表=" + e.method
        );
    } else if (s.method !== null) {
      problems.push(e.member + ": 标为 local 但存根在调桥接: " + s.method);
    }
  }

  if (problems.length) {
    console.error("BRIDGE CONTRACT DRIFT (" + problems.length + "):");
    for (const p of problems) console.error("  - " + p);
    process.exit(1);
  }

  const byKind = { invoke: 0, event: 0, local: 0 };
  for (const e of contract) byKind[e.kind]++;
  console.log(
    "BRIDGE CONTRACT OK (" + contract.length + " members: " +
    byKind.invoke + " invoke / " + byKind.event + " event / " + byKind.local +
    " local; 名称+元数+方法名 均与存根一致)"
  );
}

main().catch((e) => { console.error(e); process.exit(1); });
