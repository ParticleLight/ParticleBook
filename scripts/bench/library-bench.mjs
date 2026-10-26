#!/usr/bin/env node
// ── PB-017: 书库性能基准（5000 本，真实 app + CDP）──────────────────
// 用法（Windows，先构建 app + renderer）：
//   node scripts/bench/library-bench.mjs --app particlebook-cpp/build2/ParticleBook.exe
//
// 流程：
//   1) 在临时 profile 沙箱里写一个 schema v3 的 reader.json（默认 5000 本假书）；
//   2) 启动真实 ParticleBook（WebView2），CDP 连上渲染进程；
//   3) 测首屏（启动→首屏卡片可交互）、网格/列表的挂载 DOM 数、60 步滚动的帧间隔
//      （p50/p95/max）、搜索过滤耗时、封面 LRU 命中/未命中；
//   4) 输出 JSON + Markdown，并在指标越界时退出码 1。
// 不联网、不动真实书库（复用 scripts/e2e/lib 的沙箱/CDP 工具）。

import fs from 'node:fs/promises'
import path from 'node:path'
import { execFile } from 'node:child_process'
import { CdpClient, findFreePort, sleep, waitForPageTarget } from '../e2e/lib/cdp.mjs'
import * as appLib from '../e2e/lib/app.mjs'

const USAGE = `ParticleBook library benchmark (PB-017)

Usage:
  node scripts/bench/library-bench.mjs [options]

Options:
  --app <path>        ParticleBook.exe (default: particlebook-cpp/build2/ParticleBook.exe)
  --books <n>         Number of fake books (default: 5000)
  --runs <n>          Repeat count, each in a fresh sandbox (default: 1)
  --out <dir>         Output directory for JSON/Markdown (default: scripts/bench/results)
  --timeout <ms>      Startup/CDP timeout (default: 120000)
  --mode <m>          grid | list | both (default: both)
  --keep              Keep the temp sandbox for inspection
  --verbose           Print extra detail
  -h, --help          Show this help
`

function parseArgs(argv) {
  const options = {
    app: null, books: 5000, runs: 1, out: null, timeout: 120000,
    mode: 'both', keep: false, verbose: false, help: false,
  }
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    const next = () => {
      const value = argv[++i]
      if (value === undefined) throw new Error(`missing value for ${arg}`)
      return value
    }
    switch (arg) {
      case '--app': options.app = next(); break
      case '--books': options.books = Number(next()); break
      case '--runs': options.runs = Number(next()); break
      case '--out': options.out = next(); break
      case '--timeout': options.timeout = Number(next()); break
      case '--mode': options.mode = next(); break
      case '--keep': options.keep = true; break
      case '--verbose': options.verbose = true; break
      case '-h':
      case '--help': options.help = true; break
      default: throw new Error(`unknown argument: ${arg}`)
    }
  }
  if (!Number.isInteger(options.books) || options.books < 1) throw new Error('--books must be a positive integer')
  if (!Number.isInteger(options.runs) || options.runs < 1) throw new Error('--runs must be a positive integer')
  if (!['grid', 'list', 'both'].includes(options.mode)) throw new Error('--mode must be grid | list | both')
  if (!Number.isFinite(options.timeout) || options.timeout < 1000) throw new Error('--timeout must be >= 1000')
  return options
}

let options
const log = (...args) => console.log('[bench]', ...args)
const verbose = (...args) => { if (options.verbose) console.log('[bench]', ...args) }

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64')

function makeLibrary(count) {
  const now = Date.now()
  const books = []
  for (let i = 1; i <= count; i++) {
    const iso = new Date(now - i * 60_000).toISOString()
    books.push({
      id: i,
      title: `Benchmark Book ${String(i).padStart(5, '0')}`,
      author: `Author ${(i % 50) + 1}`,
      format: i % 3 === 0 ? 'epub' : 'txt',
      file_path: `C:\\bench\\book-${i}.${i % 3 === 0 ? 'epub' : 'txt'}`,
      file_size: 1024 * ((i % 100) + 1),
      cover_path: null, // 运行时替换成沙箱里的 1x1 PNG（避免触发文本预览）
      added_at: iso,
      last_opened: iso,
      description: null,
      publisher: null,
      language: 'en',
      isbn: null,
      series_name: null,
      series_index: null,
      rating: i % 6,
      tags: i % 7 === 0 ? ['bench'] : [],
    })
  }
  return {
    schema_version: 3,
    books,
    nextId: count + 1,
    bookmarks: [],
    highlights: [],
    notes: [],
    reading_sessions: [],
    settings: { theme: 'dark', language: 'zh' },
    book_settings: {},
    reading_progress: {},
    bookshelves: [],
    bookshelf_books: [],
    sources: [],
    annotations: [],
    nextAnnotationId: 1,
  }
}

async function waitForCdp(app, port, timeoutMs) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (app.exitedAlready()) {
      const info = await app.exited
      throw new Error(`app exited before CDP became reachable (code=${info?.code}); see ${app.logPath}`)
    }
    try {
      return await waitForPageTarget(port, { timeoutMs: 2000 })
    } catch {
      await sleep(250)
    }
  }
  throw new Error(`CDP target did not appear on port ${port} within ${timeoutMs}ms`)
}

function percentile(sorted, p) {
  return sorted[Math.min(sorted.length - 1, Math.max(0, Math.floor(sorted.length * p)))]
}

/** 读取当前页面的书库 DOM/虚拟化/封面缓存指标。 */
async function collectDomMetrics(cdp) {
  return cdp.evaluate(`(() => {
    const scroller = document.querySelector('[data-library-scroll]');
    const virtual = document.querySelector('[data-virtualized]');
    const cards = document.querySelectorAll('[data-testid="book-card"]').length;
    const rows = document.querySelectorAll('[data-testid="book-row"]').length;
    const cacheStats = (typeof window.__pbCoverCacheStats === 'function') ? window.__pbCoverCacheStats() : null;
    return {
      cards,
      rows,
      totalNodes: document.querySelectorAll('*').length,
      virtualized: !!virtual,
      virtualizedAttr: virtual ? virtual.getAttribute('data-virtualized') : null,
      rowHeight: virtual ? Number(virtual.getAttribute('data-row-height')) : null,
      columns: virtual ? Number(virtual.getAttribute('data-columns')) : null,
      scrollHeight: scroller ? scroller.scrollHeight : 0,
      clientHeight: scroller ? scroller.clientHeight : 0,
      cacheStats,
    };
  })()`)
}

/** 60 步滚动（滚动容器滚动到不同位置），收集 rAF 帧间隔与滚动中最大挂载数。 */
async function measureScroll(cdp, kind) {
  return cdp.evaluate(`(async () => {
    const scroller = document.querySelector('[data-library-scroll]');
    if (!scroller) return { error: 'no [data-library-scroll]' };
    const total = Math.max(0, scroller.scrollHeight - scroller.clientHeight);
    scroller.scrollTop = 0;
    await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    const steps = 60;
    const frames = [];
    let maxCards = 0;
    let maxRows = 0;
    let last = performance.now();
    for (let i = 1; i <= steps; i++) {
      scroller.scrollTop = Math.round(total * i / steps);
      await new Promise((resolve) => requestAnimationFrame(() => {
        frames.push(performance.now() - last);
        last = performance.now();
        maxCards = Math.max(maxCards, document.querySelectorAll('[data-testid="book-card"]').length);
        maxRows = Math.max(maxRows, document.querySelectorAll('[data-testid="book-row"]').length);
        resolve();
      }));
    }
    const sorted = [...frames].sort((a, b) => a - b);
    const pct = (p) => sorted[Math.min(sorted.length - 1, Math.max(0, Math.floor(sorted.length * p)))];
    const result = {
      kind: ${JSON.stringify(kind)},
      steps,
      p50: Number(pct(0.5).toFixed(1)),
      p95: Number(pct(0.95).toFixed(1)),
      max: Number(sorted[sorted.length - 1].toFixed(1)),
      maxCards,
      maxRows,
      scrollHeight: scroller.scrollHeight,
      clientHeight: scroller.clientHeight,
    };
    scroller.scrollTop = 0;
    return result;
  })()`, { timeoutMs: 90000 })
}

/** 右键菜单 / 点击命中测试：虚拟化后卡片仍是真实 BookCard，交互不能丢。 */
async function checkCardInteractions(cdp) {
  return cdp.evaluate(`(async () => {
    const card = document.querySelector('[data-testid="book-card"]');
    if (!card) return { ok: false, error: 'no book card' };
    card.dispatchEvent(new MouseEvent('contextmenu', {
      bubbles: true, cancelable: true, clientX: 120, clientY: 160,
    }));
    await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    const menu = Array.from(document.querySelectorAll('div')).find((element) => {
      const style = getComputedStyle(element);
      return style.position === 'fixed' && (element.textContent || '').includes('打开')
        && (element.textContent || '').includes('详情');
    });
    if (menu) {
      document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    }
    return { ok: !!menu, menuVisible: !!menu };
  })()`, { timeoutMs: 15000 })
}

/** 小范围上下滚动 3 次：验证同一批卡片重新挂载时封面缓存能命中（LRU 复用）。 */
async function measureCacheReuse(cdp) {
  return cdp.evaluate(`(async () => {
    const scroller = document.querySelector('[data-library-scroll]');
    if (!scroller) return { error: 'no [data-library-scroll]' };
    const frame = () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    if (typeof window.__pbCoverCacheClear === 'function') window.__pbCoverCacheClear();
    const start = scroller.scrollTop;
    for (let i = 0; i < 3; i++) {
      scroller.scrollTop = start + scroller.clientHeight;
      await frame();
      scroller.scrollTop = start;
      await frame();
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
    return (typeof window.__pbCoverCacheStats === 'function') ? window.__pbCoverCacheStats() : { error: 'no cache stats' };
  })()`, { timeoutMs: 30000 })
}

async function measureSearch(cdp, query) {
  return cdp.evaluate(`(async () => {
    const input = document.querySelector('[data-testid="library-search-input"]');
    if (!input) return { error: 'no library search input' };
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    const start = performance.now();
    setter.call(input, ${JSON.stringify(query)});
    input.dispatchEvent(new Event('input', { bubbles: true }));
    const deadline = start + 8000;
    let cards = 0;
    while (performance.now() < deadline) {
      await new Promise((resolve) => requestAnimationFrame(resolve));
      cards = document.querySelectorAll('[data-testid="book-card"]').length
        + document.querySelectorAll('[data-testid="book-row"]').length;
      if (cards <= 2) break;
    }
    const ms = Number((performance.now() - start).toFixed(1));
    return { query: ${JSON.stringify(query)}, ms, cards };
  })()`)
}

async function clearSearch(cdp) {
  await cdp.evaluate(`(() => {
    const input = document.querySelector('[data-testid="library-search-input"]');
    if (!input) return false;
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    setter.call(input, '');
    input.dispatchEvent(new Event('input', { bubbles: true }));
    return true;
  })()`)
  await sleep(200)
}

async function switchView(cdp, mode) {
  const selector = mode === 'list' ? '[data-testid="view-list"]' : '[data-testid="view-grid"]'
  await cdp.evaluate(`(() => { const button = document.querySelector(${JSON.stringify(selector)}); if (button) button.click(); return !!button; })()`)
  await cdp.waitFor(
    mode === 'list'
      ? '!!document.querySelector(\'[data-testid="book-row"]\')'
      : '!!document.querySelector(\'[data-testid="book-card"]\')',
    { timeoutMs: 30000, label: `${mode} view rendered` })
  await sleep(300)
}

async function runOnce(runIndex, exePath, outDir) {
  const id = `${appLib.runId()}-run${runIndex}`
  const sandbox = await appLib.createSandbox(id)
  const port = await findFreePort()
  const logPath = path.join(outDir, `${id}-app.log`)
  let app = null
  const startedAt = Date.now()
  try {
    await appLib.ensureDir(path.dirname(sandbox.dataFile))
    const coverPath = path.join(sandbox.files, 'bench-cover.png')
    await fs.writeFile(coverPath, PNG_1X1)
    const library = makeLibrary(options.books)
    for (const book of library.books) book.cover_path = coverPath
    await fs.writeFile(sandbox.dataFile, JSON.stringify(library), 'utf8')
    log(`run ${runIndex}: sandbox=${sandbox.root} books=${options.books}`)

    const env = appLib.buildAppEnv(sandbox, port)
    const launchAt = Date.now()
    app = await appLib.launchApp({
      exePath, args: [], env, cwd: sandbox.cwd, logPath,
    })
    const target = await waitForCdp(app, port, options.timeout)
    const cdp = new CdpClient(target.webSocketDebuggerUrl)
    await cdp.connect()
    await cdp.waitFor("document.readyState === 'complete'", { timeoutMs: options.timeout, label: 'renderer ready' })
    await cdp.waitFor("document.title.toLowerCase().includes('particlebook')", { timeoutMs: options.timeout, label: 'page title' })
    await cdp.waitFor('!!window.electronAPI', { timeoutMs: options.timeout, label: 'bridge shim' })
    await cdp.waitFor('!!document.querySelector(\'[data-testid="book-card"]\')', {
      timeoutMs: options.timeout, label: 'first book card visible',
    })
    const firstScreenMs = Date.now() - launchAt
    await sleep(800) // 让首屏封面请求/布局稳定

    const metrics = { run: runIndex, books: options.books, firstScreenMs, modes: {} }
    const modes = options.mode === 'both' ? ['grid', 'list'] : [options.mode]
    for (const mode of modes) {
      await switchView(cdp, mode)
      const dom = await collectDomMetrics(cdp)
      dom.cacheReuse = await measureCacheReuse(cdp)
      dom.interactions = await checkCardInteractions(cdp)
      const scroll = await measureScroll(cdp, mode)
      // 滚动一轮后回到顶部会重新挂载首屏卡片 → 应命中封面缓存；重新取一次统计，
      // 否则拿到的是滚动开始前的 hits=0。
      await sleep(250)
      dom.cacheStats = (await collectDomMetrics(cdp)).cacheStats
      if (mode === 'grid') {
        const unique = `Benchmark Book ${String(options.books).padStart(5, '0')}`
        metrics.search = await measureSearch(cdp, unique)
        await clearSearch(cdp)
        await sleep(300)
      }
      metrics.modes[mode] = { dom, scroll }
      try {
        dom.screenshot = await cdp.screenshot(path.join(outDir, `${id}-${mode}.png`))
      } catch {
        // 截图失败不影响指标
      }
      verbose(`run ${runIndex} ${mode}: cards=${dom.cards} rows=${dom.rows} nodes=${dom.totalNodes} ` +
        `p95=${scroll.p95}ms maxCards=${scroll.maxCards}`)
    }
    metrics.durationMs = Date.now() - startedAt
    await cdp.close()
    return { metrics, sandboxRoot: sandbox.root, logPath }
  } finally {
    if (app) await appLib.forceKill(app).catch(() => {})
    if (!options.keep) await appLib.removeDir(sandbox.root).catch(() => {})
  }
}

function validate(metrics) {
  const failures = []
  const check = (condition, message) => { if (!condition) failures.push(message) }
  const grid = metrics.modes.grid
  const list = metrics.modes.list
  if (grid) {
    check(grid.dom.virtualized && grid.dom.virtualizedAttr === '1', 'grid is not virtualized')
    check(grid.dom.cards < 200, `grid renders ${grid.dom.cards} cards (expected < 200)`)
    check(grid.dom.totalNodes < 6000, `grid DOM has ${grid.dom.totalNodes} nodes (expected < 6000)`)
    check(grid.scroll.maxCards < 200, `grid renders ${grid.scroll.maxCards} cards during scroll (expected < 200)`)
    check(grid.scroll.p95 < 120, `grid scroll p95 ${grid.scroll.p95}ms (expected < 120ms)`)
    check(grid.dom.interactions?.ok === true, 'grid card right-click context menu is not available')
  }
  if (list) {
    check(list.dom.virtualized && list.dom.virtualizedAttr === '1', 'list is not virtualized')
    check(list.dom.rows < 200, `list renders ${list.dom.rows} rows (expected < 200)`)
    check(list.dom.totalNodes < 6000, `list DOM has ${list.dom.totalNodes} nodes (expected < 6000)`)
    check(list.scroll.maxRows < 200, `list renders ${list.scroll.maxRows} rows during scroll (expected < 200)`)
    check(list.scroll.p95 < 120, `list scroll p95 ${list.scroll.p95}ms (expected < 120ms)`)
  }
  if (metrics.search) {
    check(!metrics.search.error, `search failed: ${metrics.search.error || ''}`)
    check(metrics.search.cards <= 2, `search filtered to ${metrics.search.cards} cards (expected <= 2)`)
    check(metrics.search.ms < 5000, `search took ${metrics.search.ms}ms (expected < 5000ms)`)
  }
  for (const mode of ['grid', 'list']) {
    const stats = metrics.modes[mode]?.dom?.cacheStats
    if (!stats) continue
    check(stats.size <= stats.max, `${mode} cover cache size ${stats.size} > max ${stats.max}`)
    // 滚动一整轮会懒加载到大量新书，misses 本来就会增长；关键是「同一批卡片
    // 重新挂载时能命中缓存」（cacheReuse.hits），且总量不随书数线性爆炸。
    const reuse = metrics.modes[mode]?.dom?.cacheReuse
    check(reuse && reuse.hits > 0, `${mode} cover cache never hit on remount (no request reuse)`)
    check(stats.misses < Math.max(800, options.books * 0.8),
      `${mode} made ${stats.misses} cover requests (expected < ${Math.max(800, Math.floor(options.books * 0.8))})`)
  }
  return failures
}

function toMarkdown(results, summary) {
  const lines = [
    '# Library benchmark (PB-017)',
    '',
    '- node: ' + process.version,
    '- platform: ' + process.platform,
    '- app: ' + summary.appPath,
    '- books: ' + summary.books,
    '- runs: ' + summary.runs,
    '',
    '| run | mode | first screen (ms) | mounted cards/rows | DOM nodes | scroll p50/p95/max (ms) | max mounted during scroll | search (ms) | cover cache (size/hits/misses) |',
    '|---|---|---|---|---|---|---|---|---|',
  ]
  for (const result of results) {
    const m = result.metrics
    for (const mode of Object.keys(m.modes)) {
      const { dom, scroll } = m.modes[mode]
      const cache = dom.cacheStats
      lines.push(`| ${m.run} | ${mode} | ${m.firstScreenMs} | ${dom.cards || dom.rows} | ${dom.totalNodes} | ` +
        `${scroll.p50} / ${scroll.p95} / ${scroll.max} | ${mode === 'grid' ? scroll.maxCards : scroll.maxRows} | ` +
        `${mode === 'grid' && m.search ? m.search.ms : '-'} | ` +
        `${cache ? `${cache.size}/${cache.hits}/${cache.misses}` : '-'} |`)
    }
    lines.push(`| ${m.run} | all | ` + '' + '| | | | | | | |')
  }
  lines.push('')
  lines.push('## Summary')
  lines.push('')
  lines.push('- first screen avg: ' + summary.firstScreenAvgMs + ' ms')
  lines.push('- grid mounted avg: ' + summary.gridMountedAvg + ' cards; grid DOM avg: ' + summary.gridNodesAvg + ' nodes')
  if (summary.searchAvgMs !== null) lines.push('- search avg: ' + summary.searchAvgMs + ' ms')
  lines.push('- failures: ' + (summary.failures.length ? summary.failures.join('; ') : 'none'))
  return lines.join('\n') + '\n'
}

async function main() {
  try {
    options = parseArgs(process.argv.slice(2))
  } catch (error) {
    console.error('[bench] ' + error.message + '\n\n' + USAGE)
    return 2
  }
  if (options.help) { console.log(USAGE); return 0 }
  if (process.platform !== 'win32') {
    console.error('[bench] library benchmark only runs on Windows (WebView2 + Win32 app)')
    return 2
  }

  const exePath = path.resolve(options.app || appLib.defaultAppPath())
  try {
    const stat = await fs.stat(exePath)
    assert(stat.isFile(), 'app path is not a file: ' + exePath)
    const rendererIndex = path.join(path.dirname(exePath), 'renderer', 'index.html')
    await fs.stat(rendererIndex)
  } catch (error) {
    console.error('[bench] preflight failed: ' + error.message)
    return 2
  }
  if (await appLib.isAppRunning()) {
    console.error('[bench] ParticleBook.exe is already running; close it first (session-wide single instance mutex)')
    return 2
  }

  const outDir = path.resolve(options.out || 'scripts/bench/results')
  await appLib.ensureDir(outDir)
  const startedAt = new Date()
  const results = []
  let exitCode = 0
  for (let run = 1; run <= options.runs; run++) {
    try {
      const result = await runOnce(run, exePath, outDir)
      results.push(result)
      const failures = validate(result.metrics)
      result.validation = { ok: failures.length === 0, failures }
      if (failures.length > 0) {
        exitCode = 1
        console.error(`[bench] run ${run} FAILED: ${failures.join('; ')}`)
      } else {
        log(`run ${run} OK: firstScreen=${result.metrics.firstScreenMs}ms ` +
          `grid=${result.metrics.modes.grid?.dom.cards ?? '-'} cards/${result.metrics.modes.grid?.dom.totalNodes ?? '-'} nodes`)
      }
    } catch (error) {
      exitCode = 1
      console.error(`[bench] run ${run} error: ${error.message}`)
      results.push({ run, error: error.message, metrics: null })
    }
  }

  const valid = results.filter((result) => result.metrics)
  const avg = (values) => values.length ? Math.round(values.reduce((sum, v) => sum + v, 0) / values.length) : null
  const summary = {
    appPath: exePath,
    books: options.books,
    runs: options.runs,
    startedAt: startedAt.toISOString(),
    node: process.version,
    platform: process.platform,
    firstScreenAvgMs: avg(valid.map((result) => result.metrics.firstScreenMs)),
    gridMountedAvg: avg(valid.map((result) => result.metrics.modes.grid?.dom.cards ?? 0)),
    gridNodesAvg: avg(valid.map((result) => result.metrics.modes.grid?.dom.totalNodes ?? 0)),
    searchAvgMs: avg(valid.filter((result) => result.metrics.search).map((result) => result.metrics.search.ms)),
    failures: results.flatMap((result) => result.validation?.failures || []),
    runsDetail: results,
  }

  const stamp = startedAt.toISOString().replace(/[:.]/g, '-')
  const jsonPath = path.join(outDir, `library-bench-${stamp}.json`)
  const mdPath = path.join(outDir, `library-bench-${stamp}.md`)
  await fs.writeFile(jsonPath, JSON.stringify(summary, null, 2), 'utf8')
  await fs.writeFile(mdPath, toMarkdown(valid, summary), 'utf8')
  log(`wrote ${jsonPath}`)
  log(`wrote ${mdPath}`)
  log(`SUMMARY: firstScreenAvg=${summary.firstScreenAvgMs}ms gridMountedAvg=${summary.gridMountedAvg} ` +
    `gridNodesAvg=${summary.gridNodesAvg} searchAvg=${summary.searchAvgMs}ms failures=${summary.failures.length}`)
  return exitCode
}

main()
  .then((code) => { process.exitCode = code })
  .catch((error) => {
    console.error('[bench] unexpected failure:', error)
    process.exitCode = 1
  })
