#!/usr/bin/env node
/**
 * m3-audit.mjs — ParticleBook 安卓 M3 规范 / 可访问性门禁扫描器
 *
 * 只读扫描 android/app/src/main/java 下的 Kotlin 源码，不编译、不接触 Gradle，
 * 用于 CI 与本地门禁：命中数超过阈值（默认 0）时以退出码 1 结束。
 *
 * 规则
 *   bare-color           Color(0x...) 硬编码颜色                 （排除 token 文件）
 *   literal-shape        RoundedCornerShape(<数字>.dp) 字面量圆角 （排除 token 文件）
 *   bare-motion          tween( / spring( 裸动效调用             （排除 ui/Motion.kt）
 *   icon-missing-label   IconButton 缺少 contentDescription / Text
 *   small-touch-target   .size(<48.dp) 且同一 Modifier 链上可点击
 *
 * 用法
 *   node scripts/android/m3-audit.mjs            # 人类摘要
 *   node scripts/android/m3-audit.mjs --json     # JSON（仍写 stdout）
 *   node scripts/android/m3-audit.mjs --max-hits 12
 *   node scripts/android/m3-audit.mjs --ignore-rule bare-color,literal-shape
 *
 * 行内抑制：在命中行写 "// m3-audit-ignore: 原因"（仅该行生效）。
 *
 * 退出码：0 = 命中数 <= 阈值；1 = 超阈值（门禁失败）；2 = 用法/环境错误。
 */
import { readdir, readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_ROOT = path.resolve(SCRIPT_DIR, '..', '..');
const SOURCE_REL = path.join('android', 'app', 'src', 'main', 'java');
const SOURCE_LABEL = 'android/app/src/main/java';
const DEFAULT_MAX_HITS = 0;
const MIN_TOUCH_DP = 48;

/** token 文件是规范的唯一来源，扫描时整体排除（按路径后缀匹配，兼容包名变动）。 */
const TOKEN_FILE_PATTERNS = [
  /(^|\/)ui\/ColorTokens\.kt$/,
  /(^|\/)ui\/ShapeTokens\.kt$/,
  /(^|\/)ui\/SpacingTokens\.kt$/,
  /(^|\/)ui\/Theme\.kt$/,
  /(^|\/)ui\/Motion\.kt$/,
];

/** 动效规则只排除 Motion token 文件本身。 */
const MOTION_TOKEN_PATTERNS = [/(^|\/)ui\/Motion\.kt$/];

const RULES = [
  {
    id: 'bare-color',
    title: '裸色 Color(0x...)',
    hint: '改用 MaterialTheme.colorScheme.* 或 ColorTokens 语义角色',
    exclude: TOKEN_FILE_PATTERNS,
  },
  {
    id: 'literal-shape',
    title: '字面量圆角 RoundedCornerShape(<n>.dp)',
    hint: '改用 MaterialTheme.shapes.* / ShapeTokens / Radius',
    exclude: TOKEN_FILE_PATTERNS,
  },
  {
    id: 'bare-motion',
    title: '裸动效 tween() / spring()',
    hint: '改用 ui/Motion.kt 的 Motion.* token',
    exclude: MOTION_TOKEN_PATTERNS,
  },
  {
    id: 'icon-missing-label',
    title: '图标按钮缺内容描述',
    hint: 'Icon(..., contentDescription = "...") 或按钮内放 Text',
    exclude: [],
  },
  {
    id: 'small-touch-target',
    title: '过小触控目标 (<48dp + 可点击)',
    hint: '触控目标至少 48x48dp：用 minimumInteractiveComponentSize() 或加大 size',
    exclude: [],
  },
];

const RULE_BY_ID = new Map(RULES.map((r) => [r.id, r]));

// ---------------------------------------------------------------- CLI

function parseArgs(argv) {
  const opts = {
    root: DEFAULT_ROOT,
    json: false,
    maxHits: DEFAULT_MAX_HITS,
    ignoreRules: new Set(),
    help: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--json') opts.json = true;
    else if (arg === '--help' || arg === '-h') opts.help = true;
    else if (arg === '--root') opts.root = path.resolve(argv[++i] ?? '.');
    else if (arg.startsWith('--root=')) opts.root = path.resolve(arg.slice('--root='.length));
    else if (arg === '--max-hits') opts.maxHits = Number.parseInt(argv[++i] ?? '', 10);
    else if (arg.startsWith('--max-hits=')) opts.maxHits = Number.parseInt(arg.slice('--max-hits='.length), 10);
    else if (arg === '--ignore-rule') {
      for (const id of String(argv[++i] ?? '').split(',')) if (id.trim()) opts.ignoreRules.add(id.trim());
    } else if (arg.startsWith('--ignore-rule=')) {
      for (const id of arg.slice('--ignore-rule='.length).split(',')) if (id.trim()) opts.ignoreRules.add(id.trim());
    } else {
      throw new Error('未知参数: ' + arg + '（--help 查看用法）');
    }
  }
  if (!Number.isInteger(opts.maxHits) || opts.maxHits < 0) {
    throw new Error('--max-hits 需要 >= 0 的整数');
  }
  for (const id of opts.ignoreRules) {
    if (!RULE_BY_ID.has(id)) {
      throw new Error('--ignore-rule 未知规则: ' + id + '（可选: ' + RULES.map((r) => r.id).join(', ') + '）');
    }
  }
  return opts;
}

// ---------------------------------------------------------------- 文本遮罩

/**
 * 把注释与字符串内容替换成空格（换行保留），让正则与括号配平只看到代码。
 * 逐 UTF-16 code unit 处理并保持长度不变，因此索引/列号可以直接映射回原文；
 * 中文注释与中文字符串里的多字节序列不会破坏后续偏移。
 */
function maskSource(src) {
  const chars = src.split('');
  const len = chars.length;
  const blank = (from, to) => {
    for (let k = from; k < to && k < len; k += 1) {
      if (chars[k] !== '\n' && chars[k] !== '\r') chars[k] = ' ';
    }
  };
  let i = 0;
  let mode = 'code';
  while (i < len) {
    const c = chars[i];
    const d = i + 1 < len ? chars[i + 1] : '';
    if (mode === 'code') {
      if (c === '/' && d === '/') { mode = 'line'; blank(i, i + 2); i += 2; continue; }
      if (c === '/' && d === '*') { mode = 'block'; blank(i, i + 2); i += 2; continue; }
      if (c === '"') {
        if (chars[i + 1] === '"' && chars[i + 2] === '"') { mode = 'raw'; i += 3; continue; }
        mode = 'string'; i += 1; continue;
      }
      if (c === "'") { mode = 'char'; i += 1; continue; }
      i += 1; continue;
    }
    if (mode === 'line') {
      if (c === '\n') { mode = 'code'; i += 1; continue; }
      blank(i, i + 1); i += 1; continue;
    }
    if (mode === 'block') {
      if (c === '*' && d === '/') { blank(i, i + 2); mode = 'code'; i += 2; continue; }
      blank(i, i + 1); i += 1; continue;
    }
    if (mode === 'raw') {
      if (c === '"' && chars[i + 1] === '"' && chars[i + 2] === '"') { mode = 'code'; i += 3; continue; }
      blank(i, i + 1); i += 1; continue;
    }
    if (mode === 'string' || mode === 'char') {
      const quote = mode === 'string' ? '"' : "'";
      if (c === '\\') { blank(i, i + 2); i += 2; continue; }
      if (c === quote) { mode = 'code'; i += 1; continue; }
      blank(i, i + 1); i += 1; continue;
    }
    i += 1;
  }
  return chars.join('');
}

// ---------------------------------------------------------------- 位置工具

function lineStarts(src) {
  const starts = [0];
  for (let i = 0; i < src.length; i += 1) if (src[i] === '\n') starts.push(i + 1);
  return starts;
}

function locate(starts, index) {
  let lo = 0;
  let hi = starts.length - 1;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (starts[mid] <= index) lo = mid; else hi = mid - 1;
  }
  return { line: lo + 1, column: index - starts[lo] + 1 };
}

function snippetOf(lines, line) {
  const text = (lines[line - 1] ?? '').replace(/\s+$/, '').trim();
  return text.length > 160 ? text.slice(0, 157) + '...' : text;
}

// ---------------------------------------------------------------- 规则实现

function detectorBareColor(ctx) {
  const hits = [];
  const re = /Color\(\s*0x[0-9A-Fa-f]+\s*\)/g;
  for (const m of ctx.masked.matchAll(re)) hits.push(ctx.hit('bare-color', m.index));
  return hits;
}

function detectorLiteralShape(ctx) {
  const hits = [];
  const re = /RoundedCornerShape\(\s*(\d+(?:\.\d+)?)(?:\s*\.dp)?\s*\)/g;
  for (const m of ctx.masked.matchAll(re)) hits.push(ctx.hit('literal-shape', m.index));
  return hits;
}

function detectorBareMotion(ctx) {
  const hits = [];
  const re = /(?:^|[^\w.])((?:tween|spring))\(/g;
  for (const m of ctx.masked.matchAll(re)) hits.push(ctx.hit('bare-motion', m.index + m[0].indexOf(m[1])));
  return hits;
}

const ICON_BUTTON_CALL = /(?:^|[^\w.])(IconButton|FilledIconButton|FilledTonalIconButton|OutlinedIconButton|IconToggleButton)\s*\(/g;

function detectorIconLabel(ctx) {
  const hits = [];
  const src = ctx.masked;
  for (const m of src.matchAll(ICON_BUTTON_CALL)) {
    const name = m[1];
    const open = m.index + m[0].length - 1;
    const close = matchingPair(src, open, '(', ')');
    if (close < 0) continue;
    // Icon 通常在尾部 lambda 里：IconButton(onClick = ...) { Icon(...) }，body 要含参数与内容块。
    let bodyEnd = close;
    let cursor = close + 1;
    while (cursor < src.length && /\s/.test(src[cursor])) cursor += 1;
    if (src[cursor] === '{') {
      const blockEnd = matchingPair(src, cursor, '{', '}');
      if (blockEnd > 0) bodyEnd = blockEnd;
    }
    const body = src.slice(open + 1, bodyEnd);
    // contentDescription = null 视为"没有标签"：交互图标必须有可读描述。
    if (/contentDescription\s*=\s*(?!null\b)[^,\s)]/.test(body)) continue;
    if (/\bText\s*\(/.test(body)) continue;
    if (/m3-audit-ignore/.test(ctx.original.slice(open, close))) continue;
    hits.push(ctx.hit('icon-missing-label', m.index + m[0].indexOf(name)));
  }
  return hits;
}

function matchingPair(src, openIndex, openCh, closeCh) {
  let depth = 0;
  for (let i = openIndex; i < src.length; i += 1) {
    const c = src[i];
    if (c === openCh) depth += 1;
    else if (c === closeCh) {
      depth -= 1;
      if (depth === 0) return i;
    }
  }
  return -1;
}

const SMALL_SIZE = /\.size\(\s*(\d+(?:\.\d+)?)\.dp\s*\)/g;

function detectorSmallTouch(ctx) {
  const hits = [];
  for (const m of ctx.masked.matchAll(SMALL_SIZE)) {
    const dp = Number.parseFloat(m[1]);
    if (!(dp > 0) || dp >= MIN_TOUCH_DP) continue; // size(0.dp) 是显式折叠占位，不是触控目标
    const { line } = locate(ctx.starts, m.index);
    const { start, end } = chainRange(ctx.lines, line - 1);
    const chain = ctx.lines.slice(start, end + 1).join('\n');
    if (!/\b(clickable|combinedClickable|toggleable|selectable|pointerInput)\b/.test(chain)) continue;
    if (/minimumInteractiveComponentSize|defaultMinSize|sizeIn\s*\(/.test(chain)) continue;
    if (/m3-audit-ignore/.test(chain)) continue;
    hits.push(ctx.hit('small-touch-target', m.index));
  }
  return hits;
}

/** 从某一行向上下扩展出同一条 Modifier 链（以 "." 开头 / 以 "." 结尾为界）。 */
function chainRange(lines, li) {
  let start = li;
  let end = li;
  while (start > 0 && lines[start].trim().startsWith('.')) start -= 1;
  while (end + 1 < lines.length) {
    const next = lines[end + 1].trim();
    if (next.startsWith('.') || lines[end].trim().endsWith('.')) end += 1;
    else break;
  }
  return { start, end };
}

const DETECTORS = [
  ['bare-color', detectorBareColor],
  ['literal-shape', detectorLiteralShape],
  ['bare-motion', detectorBareMotion],
  ['icon-missing-label', detectorIconLabel],
  ['small-touch-target', detectorSmallTouch],
];

// ---------------------------------------------------------------- 扫描

async function walkKt(dir) {
  const out = [];
  let entries;
  try {
    entries = await readdir(dir, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const entry of entries.sort((a, b) => a.name.localeCompare(b.name))) {
    const abs = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'build' || entry.name === '.gradle') continue;
      out.push(...(await walkKt(abs)));
    } else if (entry.isFile() && entry.name.endsWith('.kt')) {
      out.push(abs);
    }
  }
  return out;
}

function matchesAny(relPosix, patterns) {
  return patterns.some((re) => re.test(relPosix));
}

async function scan(opts) {
  const sourceRoot = path.join(opts.root, SOURCE_REL);
  if (!existsSync(sourceRoot)) {
    throw new Error('源码目录不存在: ' + sourceRoot);
  }
  const files = (await walkKt(sourceRoot)).sort();
  const hits = [];
  const suppressed = [];
  const seen = new Set();
  let tokenFilesSeen = 0;
  let scanned = 0;

  for (const abs of files) {
    const rel = path.relative(sourceRoot, abs).split(path.sep).join('/');
    if (matchesAny(rel, TOKEN_FILE_PATTERNS)) tokenFilesSeen += 1;
    if (RULES.every((rule) => matchesAny(rel, rule.exclude))) continue;
    scanned += 1;
    const original = await readFile(abs, 'utf8');
    const masked = maskSource(original);
    const lines = original.split(/\r?\n/);
    const starts = lineStarts(original);

    const ctx = {
      rel,
      abs,
      original,
      masked,
      lines,
      starts,
      /** 同一规则同一行只记一条（规范按 文件:行 判定）。 */
      hit(ruleId, index) {
        const { line, column } = locate(starts, index);
        const rule = RULE_BY_ID.get(ruleId);
        if (rule.exclude.length && matchesAny(rel, rule.exclude)) return null;
        const key = ruleId + '|' + rel + '|' + line;
        if (seen.has(key)) return null;
        seen.add(key);
        const snippet = snippetOf(lines, line);
        if (/m3-audit-ignore/.test(lines[line - 1] ?? '')) {
          suppressed.push({ rule: ruleId, file: rel, line, column, snippet });
          return null;
        }
        return { rule: ruleId, file: rel, line, column, snippet };
      },
    };

    for (const [ruleId, detect] of DETECTORS) {
      if (opts.ignoreRules.has(ruleId)) continue;
      for (const hit of detect(ctx)) if (hit) hits.push(hit);
    }
  }

  hits.sort((a, b) => a.file.localeCompare(b.file) || a.line - b.line || a.rule.localeCompare(b.rule));

  const byRule = new Map();
  for (const rule of RULES) byRule.set(rule.id, []);
  for (const hit of hits) byRule.get(hit.rule).push(hit);

  return {
    root: opts.root,
    sourceRoot,
    files: files.length,
    scanned,
    tokenFiles: tokenFilesSeen,
    hits,
    suppressed,
    byRule,
  };
}

// ---------------------------------------------------------------- 输出

function buildJson(result, opts, exitCode) {
  const rules = RULES.map((rule) => {
    const list = result.byRule.get(rule.id);
    const files = new Set(list.map((h) => h.file));
    return {
      id: rule.id,
      title: rule.title,
      hint: rule.hint,
      ignored: opts.ignoreRules.has(rule.id),
      hits: list.length,
      files: files.size,
    };
  });
  return {
    tool: 'm3-audit',
    generatedAt: new Date().toISOString(),
    root: result.root,
    source: SOURCE_LABEL,
    filesScanned: result.scanned,
    filesTotal: result.files,
    tokenFiles: result.tokenFiles,
    maxHits: opts.maxHits,
    exitCode,
    totalHits: result.hits.length,
    suppressed: result.suppressed.length,
    rules,
    violations: result.hits.map((h) => ({
      rule: h.rule,
      file: h.file,
      line: h.line,
      column: h.column,
      snippet: h.snippet,
    })),
  };
}

function padCell(text, width) {
  const s = String(text);
  const w = [...s].reduce((acc, ch) => acc + (ch.codePointAt(0) > 0x2e80 ? 2 : 1), 0);
  return s + ' '.repeat(Math.max(0, width - w));
}

function printHuman(result, opts, exitCode) {
  const out = [];
  out.push('ParticleBook M3 审计（m3-audit）');
  out.push('根目录  : ' + result.root);
  out.push('扫描范围: ' + SOURCE_LABEL + '/**/*.kt');
  out.push('文件    : 扫描 ' + result.scanned + ' 个（其中 token 文件 ' + result.tokenFiles + ' 个：色彩/形状/动效规则对其豁免）');
  out.push('阈值    : 命中数 > ' + opts.maxHits + ' 即失败（--max-hits 可调）');
  out.push('');
  for (const rule of RULES) {
    const list = result.byRule.get(rule.id);
    const fileCount = new Set(list.map((h) => h.file)).size;
    const status = opts.ignoreRules.has(rule.id) ? '（已忽略）' : '';
    out.push('  ' + padCell(rule.id, 22) + padCell(list.length + ' 处 / ' + fileCount + ' 个文件', 20) + rule.title + status);
  }
  out.push('');
  out.push('合计 ' + result.hits.length + ' 处命中'
    + (result.suppressed.length ? '（另有 ' + result.suppressed.length + ' 处行内抑制）' : '')
    + ' → 退出码 ' + exitCode);
  if (result.hits.length) {
    out.push('');
    out.push('明细（rule file:line）:');
    for (const hit of result.hits) {
      out.push('  ' + padCell(hit.rule, 22) + hit.file + ':' + hit.line + '  ' + hit.snippet);
    }
  }
  console.log(out.join('\n'));
}

function printHelp() {
  console.log([
    'm3-audit — 安卓 M3 规范 / 可访问性门禁',
    '',
    '用法: node scripts/android/m3-audit.mjs [选项]',
    '',
    '  --json                 输出 JSON（仍写 stdout）',
    '  --root <dir>           仓库根目录（默认脚本上两级）',
    '  --max-hits <n>         允许的命中数阈值（默认 ' + DEFAULT_MAX_HITS + '）',
    '  --ignore-rule <a,b>    忽略指定规则：' + RULES.map((r) => r.id).join(', '),
    '  -h, --help             显示本帮助',
    '',
    '规则: ' + RULES.map((r) => r.id).join(' / '),
    '抑制: 命中行写 "// m3-audit-ignore: 原因"',
    '退出码: 0 通过 / 1 超阈值 / 2 用法或环境错误',
  ].join('\n'));
}

// ---------------------------------------------------------------- main

async function main() {
  let opts;
  try {
    opts = parseArgs(process.argv.slice(2));
  } catch (err) {
    console.error('m3-audit: ' + err.message);
    process.exit(2);
  }
  if (opts.help) {
    printHelp();
    return;
  }

  let result;
  try {
    result = await scan(opts);
  } catch (err) {
    console.error('m3-audit: ' + err.message);
    process.exit(2);
  }

  const exitCode = result.hits.length > opts.maxHits ? 1 : 0;
  if (opts.json) console.log(JSON.stringify(buildJson(result, opts, exitCode), null, 2));
  else printHuman(result, opts, exitCode);
  process.exitCode = exitCode;
}

await main();
