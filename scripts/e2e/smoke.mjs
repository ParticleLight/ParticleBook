#!/usr/bin/env node
// ── PB-018: ParticleBook 关键路径 E2E smoke ────────────────────────
// 用法（Windows，需先构建 app + renderer）：
//   node scripts/e2e/smoke.mjs --app particlebook-cpp/build2/ParticleBook.exe
//
// 不污染真实数据：USERPROFILE / APPDATA / LOCALAPPDATA / TEMP 全部重定向到
// 临时沙箱（profile 形状见 lib/app.mjs），DB / WebView2 / SecretStore / 日志
// 都落在沙箱里；结束后删除（--keep 保留）。WebView2 通过
// WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS 打开随机空闲端口的 CDP，用 Node 内置
// WebSocket 驱动：真实窗口 + 真实 DOM + 真实桥接调用。
//
// 5 条 smoke：
//   1) launch-and-cdp          启动、CDP 就绪、页面 title、桥接 shim、沙箱隔离
//   2) bridge-read             getPendingOpenFiles / getBooks / getSettings
//   3) import-epub             生成最小 EPUB → importBooks → 书架出现
//   4) open-read-turn-close    点开书 → 正文渲染 → 进度落盘 → 翻页 → Esc 返回
//   5) settings-forward-exit   settings 写读 + 第二实例转发打开 + 优雅退出 + 磁盘数据核对
//
// 失败时：CDP 截图 + 页面 console + app stdout/stderr + reader.json 快照写入
// artifacts，退出码 1。--fail-at <step> 故意失败，用来验证失败路径。

import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { CdpClient, findFreePort, iframeTextExpression, sleep, waitForPageTarget } from './lib/cdp.mjs'
import { writeSampleEpub } from './lib/fixtures.mjs'
import * as appLib from './lib/app.mjs'

const USAGE = `ParticleBook E2E smoke (PB-018)

Usage:
  node scripts/e2e/smoke.mjs [options]

Options:
  --app <path>          ParticleBook.exe (default: particlebook-cpp/build2/ParticleBook.exe)
  --artifacts <dir>     Where screenshots / logs / report.json go
                        (default: %TEMP%\\particlebook-e2e-artifacts\\<runId>)
  --port <n>            Fixed CDP port (default: pick a free port)
  --timeout <ms>        Per-wait timeout (default: 120000)
  --keep                Keep the isolated sandbox after success
  --allow-running       Do not fail when another ParticleBook.exe is already running
                        (DANGEROUS: the running instance would receive test files)
  --fail-at <step>      Force a failure at a step (launch-and-cdp | bridge-read |
                        import-epub | open-read-turn-close | settings-forward-exit)
  --verbose             Print extra detail
  -h, --help            Show this help
`

function parseArgs(argv) {
  const options = {
    app: null,
    artifacts: null,
    port: null,
    timeout: 120000,
    keep: false,
    allowRunning: false,
    failAt: null,
    verbose: false,
    help: false,
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
      case '--artifacts': options.artifacts = next(); break
      case '--port': options.port = Number(next()); break
      case '--timeout': options.timeout = Number(next()); break
      case '--keep': options.keep = true; break
      case '--allow-running': options.allowRunning = true; break
      case '--fail-at': options.failAt = next(); break
      case '--verbose': options.verbose = true; break
      case '-h':
      case '--help': options.help = true; break
      default: throw new Error(`unknown argument: ${arg}`)
    }
  }
  if (options.port !== null && (!Number.isInteger(options.port) || options.port <= 0)) {
    throw new Error('--port must be a positive integer')
  }
  if (!Number.isFinite(options.timeout) || options.timeout < 1000) {
    throw new Error('--timeout must be >= 1000 ms')
  }
  return options
}

let options
const state = {
  startedAt: Date.now(),
  currentStep: 'preflight',
  ok: false,
  failure: null,
  steps: [],
  port: null,
  exePath: null,
  sandbox: null,
  artifacts: null,
  cdp: null,
  app: null,
  secondApp: null,
  fixture1: null,
  fixture2: null,
  bookId: null,
  env: null,
}

const log = (...args) => console.log('[e2e]', ...args)
const verbose = (...args) => { if (options.verbose) console.log('[e2e]', ...args) }

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

function assertEqual(actual, expected, message) {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`)
  }
}

function sanitize(name) {
  return String(name).replace(/[^a-z0-9._-]+/gi, '_').slice(0, 80)
}

async function assertFile(filePath, description) {
  try {
    const stat = await fs.stat(filePath)
    assert(stat.isFile(), `${description} is not a file: ${filePath}`)
  } catch {
    throw new Error(`missing ${description}: ${filePath}\n  hint: build it first (npm run build + C++ build, or npm run rebuild:cpp)`)
  }
}

async function step(name, fn) {
  state.currentStep = name
  const started = Date.now()
  try {
    if (options.failAt === name) throw new Error(`--fail-at ${name}: forced failure`)
    await fn()
    const ms = Date.now() - started
    state.steps.push({ name, ok: true, ms })
    log(`PASS ${name} (${ms}ms)`)
  } catch (error) {
    const ms = Date.now() - started
    state.steps.push({ name, ok: false, ms, error: error.message })
    throw error
  }
}

// ── 页面 / 桥接辅助 ───────────────────────────────────────────────
async function bridge(method, ...args) {
  const call = `window.electronAPI.${method}(${args.map((arg) => JSON.stringify(arg)).join(', ')})`
  return state.cdp.evaluate(call)
}

function iframeHas(text) {
  return `(() => {
    const iframeText = ${iframeTextExpression()};
    return typeof iframeText === 'string' && iframeText.includes(${JSON.stringify(text)});
  })()`
}

async function iframeTextSample() {
  const text = await state.cdp.evaluate(iframeTextExpression())
  return typeof text === 'string' ? text.slice(0, 300).replace(/\s+/g, ' ') : '(no iframe text)'
}

const KEY_CODES = {
  ArrowRight: { code: 'ArrowRight', windowsVirtualKeyCode: 39 },
  Escape: { code: 'Escape', windowsVirtualKeyCode: 27 },
}

async function pressKey(key) {
  await state.cdp.pressKey(key, KEY_CODES[key] || {})
}

async function waitForProgress(bookId, predicate, label, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    last = await bridge('getReadingProgress', bookId)
    if (predicate(last)) return last
    await sleep(300)
  }
  throw new Error(`timed out waiting for progress (${label}); last=${JSON.stringify(last)}`)
}

async function waitForCdpTarget(timeoutMs) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (state.app.exitedAlready()) {
      const info = await state.app.exited
      throw new Error(`app exited before CDP became reachable (code=${info?.code}); see ${state.app.logPath}`)
    }
    try {
      return await waitForPageTarget(state.port, { timeoutMs: 2000 })
    } catch {
      await sleep(250)
    }
  }
  throw new Error(`CDP target did not appear on port ${state.port} within ${timeoutMs}ms`)
}

function parseDataFile(data) {
  assert(data && typeof data === 'object', 'reader.json did not parse into an object')
  assert(Array.isArray(data.books), 'reader.json has no books array')
  return data
}

// ── 5 条 smoke ───────────────────────────────────────────────────
async function smokeLaunch() {
  const target = await waitForCdpTarget(options.timeout)
  log(`CDP target: title="${target.title}" url="${target.url}"`)
  state.cdp = new CdpClient(target.webSocketDebuggerUrl)
  await state.cdp.connect()
  await state.cdp.waitFor("document.readyState === 'complete'", {
    timeoutMs: options.timeout, label: 'renderer document.readyState === complete',
  })
  // 等 page title 真正变成 ParticleBook（target 第一次出现时可能还是 about:blank）。
  await state.cdp.waitFor("document.title.toLowerCase().includes('particlebook')", {
    timeoutMs: options.timeout, label: 'page title contains ParticleBook',
  })
  await state.cdp.waitFor('!!window.electronAPI', {
    timeoutMs: options.timeout, label: 'bridge shim window.electronAPI',
  })
  const title = await state.cdp.evaluate('document.title')
  assert(typeof title === 'string' && title.toLowerCase().includes('particlebook'),
    `unexpected renderer title: ${JSON.stringify(title)}`)
  const href = await state.cdp.evaluate('location.href')
  verbose(`renderer url: ${href}`)
  // 隔离自检：WebView2 的 user data folder 必须落在沙箱里，否则说明数据没被重定向，
  // 继续跑会污染真实书库 —— 直接失败。
  await appLib.assertIsolation(state.sandbox)
  await state.cdp.screenshot(path.join(state.artifacts, '01-launch.png'))
}

async function smokeBridgeRead() {
  const pending = await bridge('getPendingOpenFiles')
  assert(Array.isArray(pending), `getPendingOpenFiles did not return an array: ${JSON.stringify(pending)}`)
  assertEqual(pending.length, 0, 'getPendingOpenFiles should be empty on a clean launch')

  const books = await bridge('getBooks')
  assert(Array.isArray(books), `getBooks did not return an array: ${JSON.stringify(books)}`)
  assertEqual(books.length, 0, 'fresh sandbox should have an empty library')

  const settings = await bridge('getSettings')
  assert(settings && typeof settings === 'object' && !Array.isArray(settings),
    `getSettings did not return an object: ${JSON.stringify(settings)}`)
  if (settings.theme !== undefined) {
    assert(['light', 'dark', 'sepia'].includes(settings.theme),
      `settings.theme has unexpected value: ${JSON.stringify(settings.theme)}`)
  }
  if (settings.fontSize !== undefined) {
    assertEqual(typeof settings.fontSize, 'number', 'settings.fontSize type')
  }
  log(`bridge ok: pending=0 books=0 settingsKeys=${Object.keys(settings).length}`)
}

async function smokeImportEpub() {
  const title = state.fixture1.title
  const imported = await bridge('importBooks', [state.fixture1.path])
  assert(Array.isArray(imported), `importBooks did not resolve to an array: ${JSON.stringify(imported)}`)
  assertEqual(imported.length, 1, 'importBooks should import exactly one fixture')
  const book = imported[0]
  assert(book && typeof book.id === 'number', `imported book has no numeric id: ${JSON.stringify(book)}`)
  assertEqual(book.title, title, 'imported title')
  assertEqual(book.format, 'epub', 'imported format')
  state.bookId = book.id

  const books = await bridge('getBooks')
  assert(Array.isArray(books) && books.some((entry) => entry.id === book.id && entry.title === title),
    `imported book is missing from getBooks: ${JSON.stringify(books)}`)

  // 书架 UI 必须真的刷新出这本书（BridgeServer 在 book:import 后调用
  // window.__refreshLibrary；这条断言覆盖「桥接成功但界面没刷新」）。
  await state.cdp.waitFor(`document.body.innerText.includes(${JSON.stringify(title)})`, {
    timeoutMs: options.timeout, label: 'imported book visible on the bookshelf',
  })
  await state.cdp.screenshot(path.join(state.artifacts, '02-shelf.png'))
  log(`imported book #${book.id} "${title}" and it is visible on the shelf`)
}

async function smokeOpenReadTurnClose() {
  const title = state.fixture1.title
  await state.cdp.clickText(title, { timeoutMs: options.timeout })
  await state.cdp.waitFor("!!document.querySelector('iframe')", {
    timeoutMs: options.timeout, label: 'reader iframe mounted',
  })
  await state.cdp.waitFor(iframeHas(state.fixture1.ch1), {
    timeoutMs: options.timeout, label: 'chapter 1 text rendered in the reader',
  })
  await state.cdp.screenshot(path.join(state.artifacts, '03-reader.png'))

  // 进度必须落盘（EpubRenderer relocated -> saveProgress，500ms 去抖）。
  const firstProgress = await waitForProgress(
    state.bookId,
    (progress) => progress && typeof progress.cfi === 'string' && progress.cfi.length > 0,
    'initial cfi saved')
  log(`reading progress saved: cfi=${firstProgress.cfi}`)

  // 翻页：ArrowRight 直到第二章正文出现。冷启动时 epubjs 可能先在同一章的
  // 第 2 页，所以允许最多 4 次（chapter1 很短，正常 1 次就够）。
  let turned = false
  for (let attempt = 1; attempt <= 4 && !turned; attempt++) {
    await pressKey('ArrowRight')
    await sleep(700)
    turned = await state.cdp.evaluate(iframeHas(state.fixture1.ch2))
    verbose(`page turn attempt ${attempt}: chapter2 visible=${turned}`)
  }
  assert(turned, `chapter 2 text did not appear after page turns; iframe text=${await iframeTextSample()}`)

  const secondProgress = await waitForProgress(
    state.bookId,
    (progress) => progress && typeof progress.cfi === 'string'
      && progress.cfi.length > 0 && progress.cfi !== firstProgress.cfi,
    'cfi changed after page turn')
  log(`page turn persisted: cfi=${secondProgress.cfi}`)

  // Esc 逐层返回：阅读器 → 书架（ReaderView 的全局 keydown 处理）。
  await pressKey('Escape')
  await state.cdp.waitFor("!document.querySelector('iframe')", {
    timeoutMs: options.timeout, label: 'reader closed back to the library',
  })
  await state.cdp.waitFor(`document.body.innerText.includes(${JSON.stringify(title)})`, {
    timeoutMs: options.timeout, label: 'bookshelf visible again after closing the book',
  })
  const persisted = await waitForProgress(
    state.bookId,
    (progress) => progress && typeof progress.cfi === 'string' && progress.cfi.length > 0,
    'progress persisted after closing the reader')
  log(`reader closed, progress persisted (cfi=${persisted.cfi})`)
}

async function smokeSettingsForwardExit() {
  // settings 往返（PB-023 的 db:getSettings/db:updateSettings 归一化路径）。
  await bridge('updateSettings', { theme: 'light' })
  const settings = await bridge('getSettings')
  assertEqual(settings.theme, 'light', 'settings.theme after updateSettings')

  // 单实例转发（PB-019）：第二个进程带 fixture 路径启动 → 主实例导入并打开。
  state.secondApp = await appLib.launchApp({
    exePath: state.exePath,
    args: [state.fixture2.path],
    env: state.env,
    cwd: state.sandbox.cwd,
    logPath: path.join(state.artifacts, 'app-second.log'),
  })
  const secondExit = await appLib.waitExit(state.secondApp, 30000)
  assert(secondExit !== null, 'second instance never exited (single-instance forwarding stuck)')
  assertEqual(secondExit.code, 0, 'second instance exit code (forward then exit)')

  await state.cdp.waitFor("!!document.querySelector('iframe')", {
    timeoutMs: options.timeout, label: 'forwarded file opened in the reader',
  })
  await state.cdp.waitFor(iframeHas(state.fixture2.ch1), {
    timeoutMs: options.timeout, label: 'forwarded book text rendered',
  })
  await pressKey('Escape')
  await state.cdp.waitFor(`document.body.innerText.includes(${JSON.stringify(state.fixture2.title)})`, {
    timeoutMs: options.timeout, label: 'forwarded book visible on the bookshelf',
  })
  log('single-instance forwarding imported + opened the second fixture')

  // 优雅关闭：WM_CLOSE -> App::Shutdown -> DB flush；不能靠强杀掩盖落盘问题。
  const closed = await appLib.gracefulClose(state.app)
  assert(closed.graceful, 'app had to be force-killed instead of exiting gracefully')
  assertEqual(closed.code, 0, 'app exit code after graceful close')
  log('app exited gracefully (exit code 0)')

  // 磁盘核对：书、设置、阅读进度都在 reader.json 里。
  const data = parseDataFile(await appLib.readJsonIfExists(state.sandbox.dataFile))
  const titles = data.books.map((book) => book.title)
  assert(titles.includes(state.fixture1.title), `reader.json is missing fixture 1: ${JSON.stringify(titles)}`)
  assert(titles.includes(state.fixture2.title), `reader.json is missing fixture 2: ${JSON.stringify(titles)}`)
  assertEqual(data.settings?.theme, 'light', 'reader.json settings.theme')
  const progressMap = data.reading_progress || {}
  const record = progressMap[String(state.bookId)]
  assert(record && typeof record.cfi === 'string' && record.cfi.length > 0,
    `reader.json progress missing cfi for book ${state.bookId}: ${JSON.stringify(record)}`)
  log(`on-disk verify ok: books=${data.books.length} theme=light cfi=${record.cfi}`)
}

// ── 失败采集 / 清理 / 报告 ───────────────────────────────────────
async function captureFailureArtifacts(error) {
  const prefix = sanitize(state.currentStep)
  if (state.cdp) {
    try {
      const shot = path.join(state.artifacts, `failure-${prefix}.png`)
      await state.cdp.screenshot(shot)
      log(`failure screenshot: ${shot}`)
    } catch (shotError) {
      log(`could not capture failure screenshot: ${shotError.message}`)
    }
    try {
      const tail = state.cdp.consoleTail(60)
      if (tail) {
        await fs.writeFile(path.join(state.artifacts, 'page-console.log'), tail + '\n', 'utf8')
        console.error(`[e2e] page console tail:\n${tail}`)
      }
    } catch {
      // ignore
    }
  }
  if (state.sandbox) {
    await appLib.copyIfExists(state.sandbox.dataFile, path.join(state.artifacts, 'failure-reader.json'))
  }
  if (state.app && state.app.logPath) {
    log(`app log: ${state.app.logPath}`)
  }
  if (state.fixture1) log(`fixture: ${state.fixture1.path}`)
}

async function finalize(fatal) {
  const durationMs = Date.now() - state.startedAt
  if (fatal) {
    state.failure = {
      step: state.currentStep,
      message: fatal.message,
      stack: fatal.stack,
    }
    console.error(`\n[e2e] FAILED at "${state.currentStep}": ${fatal.message}`)
    await captureFailureArtifacts(fatal)
  }

  // 清理进程
  if (state.secondApp) await appLib.forceKill(state.secondApp).catch(() => {})
  if (state.app) await appLib.forceKill(state.app).catch(() => {})

  // 成功时把最终 DB 快照一起放进 artifacts（CI 可对比）
  if (state.ok && state.sandbox) {
    await appLib.copyIfExists(state.sandbox.dataFile, path.join(state.artifacts, 'reader.json'))
  }

  // 只清理本次 fixture 在 renderer/_pb_files 的缓存副本
  if (state.exePath && state.fixture1) {
    const fixtures = [state.fixture1, state.fixture2].filter(Boolean).map((fixture) => fixture.path)
    try {
      const removed = await appLib.cleanupFixtureCache(state.exePath, fixtures)
      if (removed.length > 0) verbose(`cleaned ${removed.length} fixture cache file(s)`)
    } catch {
      // ignore
    }
  }

  // 报告
  const report = {
    tool: 'pb-e2e-smoke',
    runId: state.sandbox?.id || null,
    ok: state.ok,
    startedAt: new Date(state.startedAt).toISOString(),
    durationMs,
    platform: process.platform,
    node: process.version,
    appPath: state.exePath,
    cdpPort: state.port,
    dataDir: state.sandbox?.dataDir || null,
    sandboxRoot: state.sandbox?.root || null,
    artifactsDir: state.artifacts,
    fixtures: [state.fixture1, state.fixture2].filter(Boolean).map((fixture) => ({
      path: fixture.path, title: fixture.title,
    })),
    steps: state.steps,
    failure: state.failure,
    appLogTail: state.app ? state.app.output.join('').slice(-8000) : null,
    secondAppLogTail: state.secondApp ? state.secondApp.output.join('').slice(-4000) : null,
    pageConsoleTail: state.cdp ? state.cdp.consoleTail(40) : null,
  }
  if (state.artifacts) {
    try {
      await fs.mkdir(state.artifacts, { recursive: true })
      await fs.writeFile(path.join(state.artifacts, 'report.json'), JSON.stringify(report, null, 2), 'utf8')
    } catch (error) {
      log(`could not write report.json: ${error.message}`)
    }
  }

  // 沙箱：成功且没 --keep 才删；失败保留方便排查
  if (state.sandbox) {
    if (state.ok && !options.keep) {
      await appLib.removeDir(state.sandbox.root)
    } else {
      log(`sandbox kept: ${state.sandbox.root}`)
    }
  }

  // 汇总
  const passed = state.steps.filter((entry) => entry.ok).length
  const total = state.steps.length
  if (state.ok) {
    log(`RESULT: PASS (${passed}/${total} smoke, ${(durationMs / 1000).toFixed(1)}s)`)
  } else {
    log(`RESULT: FAIL after ${passed}/${total} smoke (${(durationMs / 1000).toFixed(1)}s)`)
  }
  if (state.artifacts) log(`artifacts: ${state.artifacts}`)
  if (!state.ok && state.sandbox) log(`sandbox: ${state.sandbox.root}`)
  return state.ok ? 0 : 1
}

async function run() {
  try {
    options = parseArgs(process.argv.slice(2))
  } catch (error) {
    console.error(`[e2e] ${error.message}\n\n${USAGE}`)
    return 2
  }
  if (options.help) {
    console.log(USAGE)
    return 0
  }
  if (process.platform !== 'win32') {
    console.error('[e2e] this smoke only runs on Windows (WebView2 + Win32 app)')
    return 2
  }

  try {
    state.currentStep = 'preflight'
    state.exePath = path.resolve(options.app || appLib.defaultAppPath())
    await assertFile(state.exePath, 'ParticleBook.exe')
    await assertFile(path.join(path.dirname(state.exePath), 'renderer', 'index.html'),
      'renderer/index.html (CMake POST_BUILD copies out/renderer there)')
    if (!options.allowRunning && await appLib.isAppRunning()) {
      throw new Error(
        'ParticleBook.exe is already running. Close it first: the app uses a session-wide ' +
        'single-instance mutex, so this test instance would forward fixture paths into your ' +
        'real library. (or pass --allow-running to override at your own risk)')
    }

    state.port = options.port || await findFreePort()
    const id = appLib.runId()
    state.sandbox = await appLib.createSandbox(id)
    state.artifacts = options.artifacts
      ? path.resolve(options.artifacts)
      : path.join(os.tmpdir(), 'particlebook-e2e-artifacts', id)
    await appLib.ensureDir(state.artifacts)

    // 每次运行独立的 marker（避免标题/正文与既有书冲突）
    const marker = `PB-E2E-${id}`
    state.fixture1 = await writeSampleEpub(state.sandbox.files, 'pb-e2e-book-1.epub', {
      title: `${marker} Sample One`,
      ch1: `${marker} chapter one text: the reader must render this paragraph.`,
      ch2: `${marker} chapter two text: the page turn must reach this paragraph.`,
    })
    state.fixture2 = await writeSampleEpub(state.sandbox.files, 'pb-e2e-book-2.epub', {
      title: `${marker} Sample Two`,
      ch1: `${marker} forwarded book text: opened by the second instance.`,
      ch2: `${marker} second chapter of the forwarded book.`,
    })

    log(`app: ${state.exePath}`)
    log(`sandbox: ${state.sandbox.root}`)
    log(`artifacts: ${state.artifacts}`)
    log(`cdp port: ${state.port}`)

    state.env = appLib.buildAppEnv(state.sandbox, state.port)
    state.app = await appLib.launchApp({
      exePath: state.exePath,
      args: [],
      env: state.env,
      cwd: state.sandbox.cwd,
      logPath: path.join(state.artifacts, 'app.log'),
    })

    await step('launch-and-cdp', smokeLaunch)
    await step('bridge-read', smokeBridgeRead)
    await step('import-epub', smokeImportEpub)
    await step('open-read-turn-close', smokeOpenReadTurnClose)
    await step('settings-forward-exit', smokeSettingsForwardExit)
    state.ok = true
  } catch (error) {
    state.failure = {
      step: state.currentStep,
      message: error.message,
      stack: error.stack,
    }
  }

  return finalize(state.failure ? Object.assign(new Error(state.failure.message), state.failure) : null)
}

run()
  .then((code) => { process.exitCode = code })
  .catch((error) => {
    console.error('[e2e] unexpected failure:', error)
    process.exitCode = 1
  })
