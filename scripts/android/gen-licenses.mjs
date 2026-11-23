#!/usr/bin/env node
/**
 * gen-licenses.mjs — 从 Gradle 依赖清单生成「关于 · 开源许可」数据（纯离线）
 *
 * 输入是 :app:dependencies 的文本快照，许可信息从本地 Gradle 模块缓存里的 POM 读，
 * 不联网、不跑 Gradle、也不猜：POM 没写许可的条目一律记「未标注」，并在摘要里点名。
 *
 * 用法
 *   # 采集依赖快照（只读任务；与别的构建共用 build 锁时走 scripts/android/spike2/lock.sh）
 *   gradle --offline :app:dependencies --configuration releaseRuntimeClasspath > /f/tmp/about-deps.txt
 *
 *   # 生成 / 覆盖 assets/about/licenses.json
 *   node scripts/android/gen-licenses.mjs --deps /f/tmp/about-deps.txt
 *
 *   # 只比对：与仓库现有 licenses.json 有差异时退出码 1（CI / 提交前自检）
 *   node scripts/android/gen-licenses.mjs --deps /f/tmp/about-deps.txt --check
 *
 * 退出码
 *   0 = 生成成功 / 比对一致
 *   1 = --check 发现差异
 *   2 = 用法或环境错误（文件不存在、缓存缺失、JSON 坏）
 *
 * 参数
 *   --deps <file>    依赖清单文本（必填；:app:dependencies 的标准输出）
 *   --out <file>     输出路径（默认 android/app/src/main/assets/about/licenses.json）
 *   --cache <dir>    Gradle 模块缓存根（默认 $GRADLE_USER_HOME/caches/modules-2/files-2.1）
 *   --config <name>  取哪个 configuration 的树（默认 releaseRuntimeClasspath）
 *   --check          只比对不写盘
 *   --json           摘要按 JSON 输出
 *   -h, --help       帮助
 */
import { existsSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(SCRIPT_DIR, '..', '..');
const DEFAULT_OUT = path.join(REPO_ROOT, 'android', 'app', 'src', 'main', 'assets', 'about', 'licenses.json');
const DEFAULT_CONFIG = 'releaseRuntimeClasspath';
const UNKNOWN_LICENSE = '未标注';

// ---------------------------------------------------------------- 参数

function parseArgs(argv) {
  const opts = {
    deps: null,
    out: DEFAULT_OUT,
    cache: defaultCacheDir(),
    config: DEFAULT_CONFIG,
    check: false,
    json: false,
    help: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--deps') opts.deps = argv[++i] ?? null;
    else if (arg.startsWith('--deps=')) opts.deps = arg.slice('--deps='.length);
    else if (arg === '--out') opts.out = argv[++i] ?? null;
    else if (arg.startsWith('--out=')) opts.out = arg.slice('--out='.length);
    else if (arg === '--cache') opts.cache = argv[++i] ?? null;
    else if (arg.startsWith('--cache=')) opts.cache = arg.slice('--cache='.length);
    else if (arg === '--config') opts.config = argv[++i] ?? null;
    else if (arg.startsWith('--config=')) opts.config = arg.slice('--config='.length);
    else if (arg === '--check') opts.check = true;
    else if (arg === '--json') opts.json = true;
    else if (arg === '--help' || arg === '-h') opts.help = true;
    else throw new Error('未知参数: ' + arg + '（--help 查看用法）');
  }
  return opts;
}

function defaultCacheDir() {
  const home = process.env.GRADLE_USER_HOME || path.join(os.homedir(), '.gradle');
  return path.join(home, 'caches', 'modules-2', 'files-2.1');
}

// ---------------------------------------------------------------- 依赖树解析

/** 一行依赖树节点的标记（+--- 或 \---），保留后面整段内容。 */
const NODE_LINE = /^[| ]*(?:[+\\]--- )(.*)$/;
/** group:artifact[:version][ -> resolved][ (c)][ (*)] */
const COORD = /^([^:\s]+):([^:\s]+)(?::([^\s]+))?(.*)$/;

/**
 * 从 :app:dependencies 输出里切出目标 configuration 的树。
 *
 * 结构：
 *   releaseRuntimeClasspath - Runtime classpath of '/release'.
 *   +--- group:artifact:version
 *   |    \--- group:artifact:version -> 1.2.3 (*)
 *   \--- project ':bench'
 *
 * 只按「以 +--- 或 \--- 开头的行」收集，空行视为段落结束。
 */
function sliceConfiguration(text, configName) {
  const lines = text.split(/\r?\n/);
  const escaped = configName.replace(/[^A-Za-z0-9_.-]/g, '\\$&');
  const header = new RegExp('^' + escaped + '\\b');
  const start = lines.findIndex((line) => header.test(line));
  if (start < 0) throw new Error('依赖清单里找不到 configuration: ' + configName);
  const body = [];
  for (let i = start + 1; i < lines.length; i += 1) {
    const line = lines[i];
    if (line.trim() === '') break;
    if (NODE_LINE.test(line)) body.push(line);
  }
  return body;
}

function parseNodes(lines) {
  const modules = new Map();
  const localModules = new Set();
  const constraints = new Set();
  const platforms = new Set();
  let duplicates = 0;

  for (const line of lines) {
    const match = NODE_LINE.exec(line);
    if (!match) continue;
    const rest = match[1].trim();
    if (rest.startsWith('project ')) {
      localModules.add(rest.replace(/^project\s+/, '').replace(/['"]/g, ''));
      continue;
    }
    const coord = COORD.exec(rest);
    if (!coord) continue;
    const group = coord[1];
    const artifact = coord[2];
    const declared = coord[3] ?? '';
    const tail = coord[4] ?? '';
    const arrow = /->\s*([^\s)]+)/.exec(tail);
    const version = arrow ? arrow[1] : declared;
    if (!version) continue; // 没有版本的是约束占位，不是真实模块
    if (/\(c\)/.test(tail)) {
      constraints.add(group + ':' + artifact + ':' + version);
      continue;
    }
    if (/\(n\)/.test(tail)) continue; // 未解析（不完整仓库才会出现）
    if (/bom$/i.test(artifact)) {     // platform / BOM 不产出运行期代码，不进许可清单
      platforms.add(group + ':' + artifact + ':' + version);
      continue;
    }
    const key = group + ':' + artifact;
    if (modules.has(key)) {
      duplicates += 1;
      if (modules.get(key).version !== version) {
        throw new Error('同一模块出现两个已解析版本: ' + key + ' ' + modules.get(key).version + ' / ' + version);
      }
      continue;
    }
    modules.set(key, { group, artifact, version });
  }

  return {
    modules: [...modules.values()],
    localModules: [...localModules].sort(),
    constraints: [...constraints].sort(),
    platforms: [...platforms].sort(),
    duplicates,
  };
}

// ---------------------------------------------------------------- POM 解析

/** 在模块缓存里找 <group>/<artifact>/<version>/<hash>/<artifact>-<version>.pom。 */
function findPom(cacheDir, module) {
  const base = path.join(cacheDir, module.group, module.artifact, module.version);
  if (!existsSync(base)) return null;
  const fileName = module.artifact + '-' + module.version + '.pom';
  for (const hash of readdirSync(base).sort()) {
    const candidate = path.join(base, hash, fileName);
    if (existsSync(candidate) && statSync(candidate).isFile()) return candidate;
  }
  return null;
}

function decodeEntities(text) {
  return text
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, '&');
}

function tagText(xml, tagName) {
  const match = new RegExp('<' + tagName + '>([\\s\\S]*?)</' + tagName + '>').exec(xml);
  return match ? decodeEntities(match[1]).replace(/\s+/g, ' ').trim() : '';
}

/** POM 的 <licenses> 块与项目主页；许可证 URL 优先，主页兜底。 */
function readLicense(pomText) {
  const projectUrl = tagText(pomText.split('<licenses>')[0], 'url');
  const block = /<licenses>([\s\S]*?)<\/licenses>/.exec(pomText);
  if (!block) return { names: [], url: projectUrl };
  const names = [];
  let url = projectUrl;
  for (const licence of block[1].matchAll(/<license>([\s\S]*?)<\/license>/g)) {
    const name = tagText(licence[1], 'name');
    const licenceUrl = tagText(licence[1], 'url');
    if (name && !names.includes(name)) names.push(name);
    if (licenceUrl) url = licenceUrl;
  }
  return { names, url };
}

/**
 * 规范名归一：只认 POM 里真实出现过的写法，认不出就原样保留（不做猜测性映射）。
 * 归一的是「同一个许可的不同措辞」，不是把不同许可合并。
 */
const LICENSE_ALIASES = [
  [/^the apache software license,? version 2\.0$/i, 'Apache-2.0'],
  [/^apache license,? version 2\.0$/i, 'Apache-2.0'],
  [/^the apache license,? version 2\.0$/i, 'Apache-2.0'],
  [/^apache license 2\.0$/i, 'Apache-2.0'],
  [/^apache 2(\.0)?$/i, 'Apache-2.0'],
  [/^apache-2\.0$/i, 'Apache-2.0'],
  [/^the mit license( \(mit\))?$/i, 'MIT'],
  [/^mit license$/i, 'MIT'],
  [/^mit$/i, 'MIT'],
  [/^the 3-clause bsd license$/i, 'BSD-3-Clause'],
  [/^bsd 3-clause license$/i, 'BSD-3-Clause'],
  [/^the 2-clause bsd license$/i, 'BSD-2-Clause'],
  [/^bsd 2-clause license$/i, 'BSD-2-Clause'],
  [/^eclipse public license - v 1\.0$/i, 'EPL-1.0'],
  [/^eclipse public license - v 2\.0$/i, 'EPL-2.0'],
  [/^eclipse public license v2\.0$/i, 'EPL-2.0'],
  [/^mozilla public license version 2\.0$/i, 'MPL-2.0'],
  [/^gnu lesser general public license( v3)?$/i, 'LGPL'],
];

function normalizeLicense(names) {
  if (names.length === 0) return UNKNOWN_LICENSE;
  const mapped = names.map((name) => {
    const alias = LICENSE_ALIASES.find(([pattern]) => pattern.test(name));
    return alias ? alias[1] : name;
  });
  return [...new Set(mapped)].join(' / ');
}

// ---------------------------------------------------------------- 主流程

function buildEntries(parsed, cacheDir) {
  const entries = [];
  const unresolved = [];
  for (const module of parsed.modules) {
    const pomPath = findPom(cacheDir, module);
    let license = UNKNOWN_LICENSE;
    let url = '';
    if (pomPath) {
      const { names, url: pomUrl } = readLicense(readFileSync(pomPath, 'utf8'));
      license = normalizeLicense(names);
      url = pomUrl;
    }
    if (license === UNKNOWN_LICENSE) unresolved.push(module.group + ':' + module.artifact + ':' + module.version);
    entries.push({
      name: module.group + ':' + module.artifact,
      version: module.version,
      license,
      url,
    });
  }
  entries.sort((a, b) => (a.name === b.name ? a.version.localeCompare(b.version) : a.name.localeCompare(b.name)));
  return { entries, unresolved };
}

function readExisting(file) {
  if (!existsSync(file)) return null;
  const data = JSON.parse(readFileSync(file, 'utf8'));
  if (!Array.isArray(data.entries)) throw new Error(file + ' 里没有 entries 数组');
  return data;
}

function diffEntries(existing, generated) {
  const key = (entry) => entry.name + '@' + entry.version;
  const before = new Map((existing ?? []).map((entry) => [key(entry), entry]));
  const after = new Map(generated.map((entry) => [key(entry), entry]));
  const added = [];
  const removed = [];
  const changed = [];
  for (const [id, entry] of after) {
    const old = before.get(id);
    if (!old) { added.push(entry); continue; }
    if (old.license !== entry.license || old.url !== entry.url) {
      changed.push({ id, from: { license: old.license, url: old.url }, to: { license: entry.license, url: entry.url } });
    }
  }
  for (const [id, entry] of before) if (!after.has(id)) removed.push(entry);
  return { added, removed, changed };
}

function summarize(entries) {
  const byLicense = new Map();
  for (const entry of entries) byLicense.set(entry.license, (byLicense.get(entry.license) ?? 0) + 1);
  return [...byLicense.entries()]
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .map(([license, count]) => ({ license, count }));
}

function main() {
  let opts;
  try {
    opts = parseArgs(process.argv.slice(2));
  } catch (error) {
    console.error('gen-licenses: ' + error.message);
    return 2;
  }
  if (opts.help) {
    console.log('见 scripts/android/gen-licenses.mjs 文件头注释');
    return 0;
  }
  if (!opts.deps) {
    console.error('gen-licenses: 缺少 --deps <依赖清单>');
    return 2;
  }
  if (!existsSync(opts.deps)) {
    console.error('gen-licenses: 依赖清单不存在: ' + opts.deps);
    return 2;
  }
  if (!existsSync(opts.cache)) {
    console.error('gen-licenses: Gradle 模块缓存不存在: ' + opts.cache);
    return 2;
  }

  let parsed;
  let built;
  try {
    parsed = parseNodes(sliceConfiguration(readFileSync(opts.deps, 'utf8'), opts.config));
    built = buildEntries(parsed, opts.cache);
  } catch (error) {
    console.error('gen-licenses: ' + error.message);
    return 2;
  }

  const document = {
    schema: 1,
    generatedFrom: 'gradle :app:dependencies --configuration ' + opts.config,
    source: 'Gradle 模块缓存里的 POM <licenses>，未联网',
    entries: built.entries,
  };

  if (opts.check) {
    let existing;
    try {
      existing = readExisting(opts.out);
    } catch (error) {
      console.error('gen-licenses: ' + error.message);
      return 2;
    }
    const diff = diffEntries(existing?.entries, built.entries);
    const differs = !existing || diff.added.length + diff.removed.length + diff.changed.length > 0;
    if (opts.json) {
      console.log(JSON.stringify({ mode: 'check', out: opts.out, modules: built.entries.length, differs, diff, unresolved: built.unresolved }, null, 2));
    } else {
      console.log('比对: ' + opts.out);
      console.log('  现有 ' + (existing?.entries.length ?? 0) + ' 条 / 本次生成 ' + built.entries.length + ' 条');
      for (const entry of diff.added) console.log('  + ' + entry.name + ' ' + entry.version + '  ' + entry.license);
      for (const entry of diff.removed) console.log('  - ' + entry.name + ' ' + entry.version + '  ' + entry.license);
      for (const item of diff.changed) console.log('  ~ ' + item.id + '  ' + item.from.license + ' -> ' + item.to.license);
      console.log(differs ? '有差异：请重新生成 licenses.json' : '一致');
    }
    return differs ? 1 : 0;
  }

  try {
    writeFileSync(opts.out, JSON.stringify(document, null, 2) + '\n', 'utf8');
  } catch (error) {
    console.error('gen-licenses: 写入失败: ' + error.message);
    return 2;
  }

  const summary = {
    out: opts.out,
    modules: built.entries.length,
    duplicates: parsed.duplicates,
    localModules: parsed.localModules,
    platforms: parsed.platforms,
    licenses: summarize(built.entries),
    unresolved: built.unresolved,
  };
  if (opts.json) {
    console.log(JSON.stringify(summary, null, 2));
  } else {
    console.log('写入: ' + opts.out);
    console.log('  模块 ' + summary.modules + ' 个（树内重复节点 ' + summary.duplicates + ' 个已去重）');
    if (summary.localModules.length) console.log('  本地模块（不属第三方许可）: ' + summary.localModules.join(', '));
    if (summary.platforms.length) console.log('  BOM/platform（不产出代码）: ' + summary.platforms.join(', '));
    for (const item of summary.licenses) console.log('  ' + item.license + ' · ' + item.count);
    if (summary.unresolved.length) {
      console.error('  ' + summary.unresolved.length + ' 个模块的 POM 没写许可（记为「' + UNKNOWN_LICENSE + '」）:');
      for (const id of summary.unresolved) console.error('    ' + id);
    }
  }
  return 0;
}

process.exitCode = main();
