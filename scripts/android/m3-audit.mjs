#!/usr/bin/env node
/**
 * m3-audit.mjs — ParticleBook 安卓 M3 规范 / 可访问性门禁扫描器
 *
 * 规范钉在 2026-11 的 Material 3（含 M3 Expressive），两个来源缺一不可：
 *   设计侧  skill material-design-3-ui（2026-11 版）references/*.md，逐条写在 RULES[].spec
 *   实现侧  androidx.compose.material3 1.5.0（android/app/build.gradle.kts:75），
 *           库侧缺口与 departure 见 docs/android-port/m3/README.md「规范版本钉住」
 *
 * 只读扫描 android/app/src/main/java 下的 Kotlin 源码，不编译、不接触 Gradle，
 * 用于 CI 与本地门禁：命中数超过阈值（默认 0）时以退出码 1 结束。
 *
 * 规则（10 条）——spec 列是这条规则凭什么成立，不是团队习惯
 *   bare-color               Color(0x...) 硬编码颜色                   color-system.md
 *   literal-shape            RoundedCornerShape(<数字>.dp) 字面量       shape-and-elevation.md#corner-radius-scale
 *   bare-motion              裸 spring()（未走 Motion token）           motion.md#spring-tokens
 *   bare-duration-curve      裸 tween()/keyframes()/Easing（时长曲线）  motion.md#the-current-system-is-physics-not-duration
 *   icon-missing-label       IconButton 缺可读名称                      accessibility.md
 *   small-touch-target       <48dp 且可点击的触控目标                  accessibility.md
 *   deprecated-segmented     SegmentedButton / *SegmentedButtonRow      m3-expressive.md#deprecated-baselines
 *   deprecated-baseline-appbar Medium/Large TopAppBar + BottomAppBar（基线形态）  m3-expressive.md#deprecated-baselines
 *   deprecated-progress-indicator  Circular/LinearProgressIndicator（旧进度组件）  m3-expressive.md#deprecated-baselines
 *   spacing-4dp-base         注释里仍声称 4dp 基准 / 4dp 网格          spacing-and-layout.md#the-spacing-system
 *
 * 为什么 bare-motion 只管 spring、时长曲线单列一条：
 * 2026-11 的动效体系是「弹簧为默认、曲线只做不可中断的兜底」（motion.md 第一节）。
 * 两条规则分开，命中数直接读成「还有多少弹簧没走 token / 还有多少手写曲线」，
 * 也避免同一次 tween(...) 被两条规则各记一次。
 *
 * 用法
 *   node scripts/android/m3-audit.mjs                  # 人类摘要
 *   node scripts/android/m3-audit.mjs --json           # JSON（仍写 stdout）
 *   node scripts/android/m3-audit.mjs --max-hits 12    # 棘轮：允许的命中上限
 *   node scripts/android/m3-audit.mjs --ignore-rule bare-color,literal-shape
 *   node scripts/android/m3-audit.mjs --self-test      # 用合成样本逐规则自检（不扫仓库）
 *
 * 行内抑制：在命中行写 "// m3-audit-ignore: 原因"（仅该行生效，会单独统计）。
 *
 * 退出码：0 = 命中数 <= 阈值（或自检通过）；1 = 超阈值 / 自检失败；2 = 用法或环境错误。
 */
import { mkdir, mkdtemp, readdir, readFile, rm, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import os from 'node:os';
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

/**
 * 规则表。spec 是规范出处（skill references 的相对路径 + 小节锚点），
 * scope 说明这条规则看的是代码还是注释——只有 spacing-4dp-base 看注释。
 */
const RULES = [
  {
    id: 'bare-color',
    title: '裸色 Color(0x...)',
    hint: '改用 MaterialTheme.colorScheme.* 或 ColorTokens 语义角色',
    spec: 'references/color-system.md（语义角色）',
    scope: 'code',
    exclude: TOKEN_FILE_PATTERNS,
  },
  {
    id: 'literal-shape',
    title: '字面量圆角 RoundedCornerShape(<n>.dp)',
    hint: '改用 MaterialTheme.shapes.* / AppShapes 的十档，或 ShapeTokens / Radius',
    spec: 'references/shape-and-elevation.md#corner-radius-scale（十档）',
    scope: 'code',
    exclude: TOKEN_FILE_PATTERNS,
  },
  {
    id: 'bare-motion',
    title: '裸弹簧 spring()（未走 Motion token）',
    hint: '改用 Motion.product.{spatial|effects}{Fast|Default|Slow}.spec()',
    spec: 'references/motion.md#spring-tokens',
    scope: 'code',
    exclude: MOTION_TOKEN_PATTERNS,
  },
  {
    id: 'bare-duration-curve',
    title: '裸时长曲线 tween()/keyframes()/Easing',
    hint: '时长曲线只允许在 ui/Motion.kt 里（如 Motion.pulse()）；组件内改用弹簧 token',
    spec: 'references/motion.md#the-current-system-is-physics-not-duration',
    scope: 'code',
    exclude: MOTION_TOKEN_PATTERNS,
  },
  {
    id: 'icon-missing-label',
    title: '图标按钮缺可读名称',
    hint: 'Icon(..., contentDescription = "...") 或按钮内放 Text',
    spec: 'references/accessibility.md（可读名称）',
    scope: 'code',
    exclude: [],
  },
  {
    id: 'small-touch-target',
    title: '过小触控目标 (<48dp + 可点击)',
    hint: '触控目标至少 48x48dp：用 minimumInteractiveComponentSize() 或加大 size',
    spec: 'references/accessibility.md（目标尺寸）',
    scope: 'code',
    exclude: [],
  },
  {
    id: 'deprecated-segmented',
    title: '已废弃分段按钮 SegmentedButton*',
    hint: '换 connected button group；1.4.0 无该组件时按语义退回 RadioButton 列表 / DropdownMenu',
    spec: 'references/m3-expressive.md#deprecated-baselines（segmented button → connected button group）',
    scope: 'code',
    exclude: [],
  },
  {
    id: 'deprecated-baseline-appbar',
    title: '已废弃基线应用栏（Medium/Large TopAppBar、BottomAppBar）',
    hint: '顶栏换 medium/large flexible top app bar（1.5.0 正式面）或折叠为小号 TopAppBar；底栏换 docked/floating toolbar（Surface + Row 自绘）',
    spec: 'references/m3-expressive.md#deprecated-baselines（baseline medium/large app bars）',
    scope: 'code',
    exclude: [],
  },
  {
    id: 'deprecated-progress-indicator',
    title: '旧进度组件 Circular/LinearProgressIndicator',
    hint: '不确定等待换 LoadingIndicator(modifier, color)；确定进度换 Linear/CircularWavyProgressIndicator(progress = { ... })',
    spec: 'references/m3-expressive.md#deprecated-baselines（separate circular/linear progress token sets → unified progress indicator）',
    scope: 'code',
    // 无文件级豁免：进度组件的迁移已收口，规则对全仓全量生效。
    exclude: [],
  },
  {
    id: 'spacing-4dp-base',
    title: '注释声称 4dp 基准 / 4dp 网格',
    hint: 'Spacing 是 8dp 基准 + 嵌档 2/4/6/10；改注释或改值',
    spec: 'references/spacing-and-layout.md#the-spacing-system（base unit 8dp）',
    scope: 'comment',
    exclude: [],
  },
];

const RULE_BY_ID = new Map(RULES.map((r) => [r.id, r]));

// ---------------------------------------------------------------- 正则

/** 裸弹簧：不认 .spring( 这种链式写法，避免匹配到自定义方法后缀。 */
const BARE_SPRING = /(?:^|[^\w.])(spring)\s*\(/g;
/** 时长曲线：规格构造器 + 缓动单例 + 自定义 Easing lambda。 */
const DURATION_SPEC_CALL = /(?:^|[^\w.])(tween|keyframes|CubicBezierEasing)\s*(?:<[^<>]*>)?\s*[({]/g;
const EASING_SINGLETON = /(?:^|[^\w.])(FastOutSlowInEasing|LinearOutSlowInEasing|FastOutLinearInEasing|LinearEasing)\b/g;
const EASING_LAMBDA = /(?:^|[^\w.])Easing\s*\{/g;
/** 旧进度组件：必须跟左圆括号，避免命中 Wavy/Loading 变体与 KDoc 引用。 */
const DEPRECATED_PROGRESS = /(?:^|[^\w.])(CircularProgressIndicator|LinearProgressIndicator)\s*\(/g;
/** 废弃组件。 */
const SEGMENTED = /(?:^|[^\w.])(SegmentedButton|SegmentedButtonDefaults|SingleChoiceSegmentedButtonRow|MultiChoiceSegmentedButtonRow)\b/g;
const BASELINE_APPBAR = /(?:^|[^\w.])(MediumTopAppBar|LargeTopAppBar|MediumTopAppBarDefaults|LargeTopAppBarDefaults|BottomAppBar|BottomAppBarDefaults)\b/g;
/** 4dp 基准的说法：两个方向（4dp 在前 / 术语在前），一条注释只算一处。 */
const SPACING_4DP_FORWARD = /\b4\s*dp\b[^。\n]{0,12}(网格|栅格|节奏|基准|grid|rhythm|base)/i;
const SPACING_4DP_BACKWARD = /(网格|栅格|节奏)[^。\n]{0,6}\b4\s*dp\b/i;
/** 同一行出现这些词，说明是在声明该说法作废，不是在新立它。 */
const RETRACTION = /废弃|作废|旧说法|已改|纠正|不再|不是|deprecated/i;
/** 行内抑制必须写原因：m3-audit-ignore: <非空原因>；只写标记不写原因按未抑制处理。 */
const IGNORE_WITH_REASON = /m3-audit-ignore\s*:\s*\S/;

// ---------------------------------------------------------------- CLI

function parseArgs(argv) {
  const opts = {
    root: DEFAULT_ROOT,
    json: false,
    maxHits: DEFAULT_MAX_HITS,
    ignoreRules: new Set(),
    selfTest: false,
    help: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--json') opts.json = true;
    else if (arg === '--self-test') opts.selfTest = true;
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
 *
 * spacing-4dp-base 是唯一读注释的规则，它走原文（ctx.lines），不走这里。
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

/**
 * 把 import / package 行抹成空格（等长）。
 *
 * 看着多余，其实是必须的：bare-duration-curve 会匹配 LinearEasing 这类标识符，
 * 而 import androidx.compose.animation.core.LinearEasing 是合法且必然出现的行——
 * 命中 import 行只会把明细指到没有逻辑的位置。所有规则都不针对 import 行，统一抹掉。
 */
function blankImportLines(src) {
  return src.split('\n').map((line) => {
    const trimmed = line.trimStart();
    if (trimmed.startsWith('import ') || trimmed.startsWith('package ')) return ' '.repeat(line.length);
    return line;
  }).join('\n');
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

function detectorBareSpring(ctx) {
  const hits = [];
  for (const m of ctx.masked.matchAll(BARE_SPRING)) {
    hits.push(ctx.hit('bare-motion', m.index + m[0].indexOf(m[1])));
  }
  return hits;
}

function detectorBareDurationCurve(ctx) {
  const hits = [];
  const push = (regex, group) => {
    for (const m of ctx.masked.matchAll(regex)) {
      hits.push(ctx.hit('bare-duration-curve', m.index + m[0].indexOf(group ? m[group] : m[0].trim())));
    }
  };
  push(DURATION_SPEC_CALL, 1);
  push(EASING_SINGLETON, 1);
  push(EASING_LAMBDA, 0);
  return hits;
}

function detectorDeprecatedSegmented(ctx) {
  const hits = [];
  for (const m of ctx.masked.matchAll(SEGMENTED)) {
    hits.push(ctx.hit('deprecated-segmented', m.index + m[0].indexOf(m[1])));
  }
  return hits;
}

function detectorDeprecatedBaselineAppBar(ctx) {
  const hits = [];
  for (const m of ctx.masked.matchAll(BASELINE_APPBAR)) {
    hits.push(ctx.hit('deprecated-baseline-appbar', m.index + m[0].indexOf(m[1])));
  }
  return hits;
}

function detectorDeprecatedProgress(ctx) {
  const hits = [];
  for (const m of ctx.masked.matchAll(DEPRECATED_PROGRESS)) {
    hits.push(ctx.hit('deprecated-progress-indicator', m.index + m[0].indexOf(m[1])));
  }
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

/**
 * 唯一读注释的规则：注释里若还写着「4dp 基准 / 4dp 网格」，说明作者按旧节奏在对齐。
 *
 * 同一行带「废弃 / 旧说法 / 不是 / 已改」等词时放行——token 文件里那句
 * 「『4dp 节奏 / 4dp 网格』是旧说法，已废弃」正是在声明该说法作废，不该被当成违规。
 * 代价是「先写新说法再顺口提一句旧词」会漏判，属于可接受的人工复核成本。
 */
function detectorSpacingBase(ctx) {
  const hits = [];
  for (let i = 0; i < ctx.lines.length; i += 1) {
    const line = ctx.lines[i];
    if (!/4\s*dp/i.test(line)) continue;
    if (RETRACTION.test(line)) continue;
    if (!SPACING_4DP_FORWARD.test(line) && !SPACING_4DP_BACKWARD.test(line)) continue;
    const column = line.search(/4\s*dp/i);
    hits.push(ctx.hit('spacing-4dp-base', ctx.starts[i] + (column < 0 ? 0 : column)));
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
  ['bare-motion', detectorBareSpring],
  ['bare-duration-curve', detectorBareDurationCurve],
  ['icon-missing-label', detectorIconLabel],
  ['small-touch-target', detectorSmallTouch],
  ['deprecated-segmented', detectorDeprecatedSegmented],
  ['deprecated-baseline-appbar', detectorDeprecatedBaselineAppBar],
  ['deprecated-progress-indicator', detectorDeprecatedProgress],
  ['spacing-4dp-base', detectorSpacingBase],
];

/** 规则与实现必须一一对应：漏写 detector 的规则会静默变成 0 命中，比报错更危险。 */
for (const rule of RULES) {
  if (!DETECTORS.some(([id]) => id === rule.id)) {
    throw new Error('规则 ' + rule.id + ' 没有对应 detector');
  }
}

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
  const noReason = [];
  const seen = new Set();
  let tokenFilesSeen = 0;
  let scanned = 0;

  for (const abs of files) {
    const rel = path.relative(sourceRoot, abs).split(path.sep).join('/');
    if (matchesAny(rel, TOKEN_FILE_PATTERNS)) tokenFilesSeen += 1;
    if (RULES.every((rule) => matchesAny(rel, rule.exclude))) continue;
    scanned += 1;
    const original = await readFile(abs, 'utf8');
    const masked = blankImportLines(maskSource(original));
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
        const rawLine = lines[line - 1] ?? '';
        if (IGNORE_WITH_REASON.test(rawLine)) {
          suppressed.push({ rule: ruleId, file: rel, line, column, snippet });
          return null;
        }
        if (/m3-audit-ignore/.test(rawLine)) {
          // 写了标记却没写原因：照样报出，并单独统计，避免白名单退化成静默放过。
          noReason.push({ rule: ruleId, file: rel, line, column, snippet });
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
    noReason,
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
      spec: rule.spec,
      scope: rule.scope,
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
    ignoreWithoutReason: result.noReason.length,
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
    out.push('  ' + padCell(rule.id, 32) + padCell(list.length + ' 处 / ' + fileCount + ' 个文件', 18) + rule.title + status);
  }
  out.push('');
  out.push('合计 ' + result.hits.length + ' 处命中'
    + (result.suppressed.length ? '（另有 ' + result.suppressed.length + ' 处行内抑制）' : '')
    + ' → 退出码 ' + exitCode);
  if (result.noReason.length) {
    out.push('  ' + result.noReason.length + ' 处 m3-audit-ignore 没写原因（已按命中计）:');
    for (const item of result.noReason) {
      out.push('    ' + padCell(item.rule, 32) + item.file + ':' + item.line + '  ' + item.snippet);
    }
  }
  if (result.hits.length) {
    out.push('');
    out.push('明细（rule file:line）:');
    for (const hit of result.hits) {
      out.push('  ' + padCell(hit.rule, 32) + hit.file + ':' + hit.line + '  ' + hit.snippet);
    }
  }
  out.push('');
  out.push('规范锚点（每条规则凭什么成立）:');
  for (const rule of RULES) out.push('  ' + padCell(rule.id, 32) + rule.spec);
  console.log(out.join('\n'));
}

function printHelp() {
  console.log([
    'm3-audit — 安卓 M3 规范 / 可访问性门禁（规范钉在 2026-11 的 M3 + material3 1.5.0）',
    '',
    '用法: node scripts/android/m3-audit.mjs [选项]',
    '',
    '  --json                 输出 JSON（仍写 stdout）',
    '  --self-test            用合成样本逐规则自检（不读仓库，验证规则真的会命中）',
    '  --root <dir>           仓库根目录（默认脚本上两级）',
    '  --max-hits <n>         允许的命中数阈值（默认 ' + DEFAULT_MAX_HITS + '）',
    '  --ignore-rule <a,b>    忽略指定规则：' + RULES.map((r) => r.id).join(', '),
    '  -h, --help             显示本帮助',
    '',
    '规则: ' + RULES.map((r) => r.id).join(' / '),
    '抑制: 命中行写 "// m3-audit-ignore: 原因"',
    '退出码: 0 通过 / 1 超阈值或自检失败 / 2 用法或环境错误',
  ].join('\n'));
}

// ---------------------------------------------------------------- 自检

/**
 * 合成样本 + 行内标记。
 *
 * 每条规则至少要有一个 <<rule-id>> 标记的行，自检断言「该行命中、且没有多余命中」；
 * 前缀 suppressed: 的标记断言该行被 m3-audit-ignore 抑制而非报出。
 * 这样门禁全绿时才不是空转：规则真的会在该命中的地方命中。
 * ui/Motion.kt 与 ui/ShapeTokens.kt 两个样本用来验证 token 文件豁免。
 */
const SELF_TEST_FILES = {
  'ui/Motion.kt': [
    'package fixture.ui',
    '',
    'fun motionToken(): Any {',
    '    val a = tween<Float>(durationMillis = 220)',
    '    val b = spring(dampingRatio = 0.8f, stiffness = 380f)',
    '    return listOf(a, b)',
    '}',
  ].join('\n'),
  'ui/ShapeTokens.kt': [
    'package fixture.ui',
    '',
    'val tokenShape = RoundedCornerShape(12.dp)',
    'val tokenColor = Color(0xFF112233)',
  ].join('\n'),
  'fixture/Segmented.kt': [
    'package fixture',
    '',
    'fun segmentedDemo(selected: Int) {',
    '    SingleChoiceSegmentedButtonRow { // <<deprecated-segmented>>',
    '        SegmentedButton(selected = selected == 0, onClick = {}) { Text("A") } // <<deprecated-segmented>>',
    '        SegmentedButton(selected = selected == 1, onClick = {}) { Text("B") } // <<deprecated-segmented>>',
    '    }',
    '}',
  ].join('\n'),
  'fixture/AppBars.kt': [
    'package fixture',
    '',
    'fun appBarDemo() {',
    '    MediumTopAppBar(title = { Text("t") }) // <<deprecated-baseline-appbar>>',
    '    LargeTopAppBar(title = { Text("t") }) // <<deprecated-baseline-appbar>>',
    '}',
  ].join('\n'),
  'fixture/Anim.kt': [
    'package fixture',
    '',
    'fun animDemo(): Any {',
    '    val a = tween<Float>(durationMillis = 200) // <<bare-duration-curve>>',
    '    val b = spring(dampingRatio = 0.8f, stiffness = 380f) // <<bare-motion>>',
    '    val c = keyframes<Float> { 0f at 0; 1f at 300 } // <<bare-duration-curve>>',
    '    val d = Easing { it * it } // <<bare-duration-curve>>',
    '    val e = FastOutSlowInEasing // <<bare-duration-curve>>',
    '    return listOf(a, b, c, d, e)',
    '}',
  ].join('\n'),
  'fixture/Spacing.kt': [
    'package fixture',
    '',
    '// 间距沿用 4dp 网格，所有值都是 4 的倍数。 <<spacing-4dp-base>>',
    '// 「4dp 节奏 / 4dp 网格」是旧说法，已废弃（8dp 基准）。',
    '// 8dp 基准，嵌档 4dp。',
    'val gap = 8',
  ].join('\n'),
  'fixture/Labels.kt': [
    'package fixture',
    '',
    'fun labels(click: () -> Unit) {',
    '    IconButton(onClick = click) { // <<icon-missing-label>>',
    '        Icon(Icons.Filled.Add, contentDescription = null)',
    '    }',
    '    IconButton(onClick = click) {',
    '        Icon(Icons.Filled.Add, contentDescription = "添加")',
    '    }',
    '    IconButton(onClick = click) { // m3-audit-ignore: 装饰图标，标签在外层 <<suppressed:icon-missing-label>>',
    '        Icon(Icons.Filled.Add, contentDescription = null)',
    '    }',
    '}',
  ].join('\n'),
  'fixture/Touch.kt': [
    'package fixture',
    '',
    'fun touch(click: () -> Unit) {',
    '    Box(',
    '        Modifier',
    '            .size(18.dp) // <<small-touch-target>>',
    '            .clickable { click() },',
    '    )',
    '}',
  ].join('\n'),
  'fixture/Progress.kt': [
    'package fixture',
    '',
    'fun progressDemo(progress: () -> Float) {',
    '    CircularProgressIndicator(modifier = Modifier) // <<deprecated-progress-indicator>>',
    '    LinearProgressIndicator(progress = progress) // <<deprecated-progress-indicator>>',
    '    LinearWavyProgressIndicator(progress = progress)',
    '    CircularWavyProgressIndicator(progress = progress)',
    '    LoadingIndicator(modifier = Modifier)',
    '}',
  ].join('\n'),
  'fixture/Palette.kt': [
    'package fixture',
    '',
    'val raw = Color(0xFF445566) // <<bare-color>>',
    'val round = RoundedCornerShape(20.dp) // <<literal-shape>>',
    'val noReason = Color(0xFF778899) // m3-audit-ignore <<bare-color>>',
  ].join('\n'),
};

const MARKER = /<<(suppressed:)?([a-z0-9-]+)>>/g;

function expectedFromFixtures(files) {
  const expected = new Map(RULES.map((rule) => [rule.id, []]));
  const suppressed = [];
  for (const [rel, content] of Object.entries(files)) {
    content.split('\n').forEach((line, index) => {
      for (const m of line.matchAll(MARKER)) {
        const key = m[2] + '|' + rel + '|' + (index + 1);
        if (m[1]) suppressed.push(key);
        else if (expected.has(m[2])) expected.get(m[2]).push(key);
      }
    });
  }
  return { expected, suppressed };
}

async function runSelfTest(opts) {
  const tmpRoot = await mkdtemp(path.join(os.tmpdir(), 'm3-audit-selftest-'));
  const sourceRoot = path.join(tmpRoot, SOURCE_REL);
  try {
    for (const [rel, content] of Object.entries(SELF_TEST_FILES)) {
      const abs = path.join(sourceRoot, rel);
      await mkdir(path.dirname(abs), { recursive: true });
      await writeFile(abs, content, 'utf8');
    }
    const result = await scan({ root: tmpRoot, ignoreRules: new Set() });
    const { expected, suppressed } = expectedFromFixtures(SELF_TEST_FILES);

    const actualByRule = new Map();
    for (const rule of RULES) actualByRule.set(rule.id, []);
    for (const hit of result.hits) actualByRule.get(hit.rule).push(hit.rule + '|' + hit.file + '|' + hit.line);
    const actualSuppressed = result.suppressed.map((s) => s.rule + '|' + s.file + '|' + s.line);

    const cases = [];
    for (const rule of RULES) {
      const want = expected.get(rule.id).slice().sort();
      const got = actualByRule.get(rule.id).slice().sort();
      cases.push({
        rule: rule.id,
        expected: want.length,
        actual: got.length,
        ok: want.length > 0 && want.length === got.length && want.every((v, i) => v === got[i]),
        missing: want.filter((v) => !got.includes(v)),
        unexpected: got.filter((v) => !want.includes(v)),
      });
    }
    const suppressedOk = suppressed.slice().sort().join(',') === actualSuppressed.slice().sort().join(',');
    const ok = cases.every((c) => c.ok) && suppressedOk;

    if (opts.json) {
      console.log(JSON.stringify({
        tool: 'm3-audit',
        mode: 'self-test',
        generatedAt: new Date().toISOString(),
        ok,
        rules: cases,
        suppressed: { expected: suppressed.length, actual: actualSuppressed.length, ok: suppressedOk },
      }, null, 2));
    } else {
      const out = [];
      out.push('m3-audit 自检（合成样本，逐规则断言命中位置）');
      out.push('');
      for (const c of cases) {
        out.push('  ' + padCell(c.rule, 32) + padCell(c.actual + ' / ' + c.expected + ' 处', 14) + (c.ok ? 'OK' : 'FAIL'));
        for (const miss of c.missing) out.push('      缺: ' + miss);
        for (const extra of c.unexpected) out.push('      多: ' + extra);
      }
      out.push('');
      out.push('  ' + padCell('行内抑制', 32) + padCell(actualSuppressed.length + ' / ' + suppressed.length + ' 处', 14)
        + (suppressedOk ? 'OK' : 'FAIL'));
      out.push('');
      out.push('结果: ' + (ok ? RULES.length + ' 条规则全部按预期命中 / 通过' : '自检失败（规则与样本不一致）'));
      console.log(out.join('\n'));
    }
    return ok ? 0 : 1;
  } finally {
    await rm(tmpRoot, { recursive: true, force: true });
  }
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

  if (opts.selfTest) {
    let code;
    try {
      code = await runSelfTest(opts);
    } catch (err) {
      console.error('m3-audit: 自检异常: ' + err.message);
      process.exit(2);
    }
    process.exitCode = code;
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
