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
// 10 步 smoke（前 5 条是 PB-018-A 基础链路，后 5 步是 PB-018-B 追加）：
//   1) launch-and-cdp          启动、CDP 就绪、页面 title、桥接 shim、沙箱隔离
//   2) bridge-read             getPendingOpenFiles / getBooks / getSettings
//   3) import-epub             生成最小 EPUB → importBooks → 书架出现
//   4) open-read-turn-close    点开书 → 正文渲染 → 进度落盘 → 翻页 → Esc 返回
//   5) settings-forward-exit   settings 写读 + 第二实例转发打开 + 优雅退出 + 磁盘数据核对
//   6) extended-app            同一沙箱重启（书/设置持久化），为后 4 类提供运行实例
//   7) tts                     getTtsVoices 非空 → setVolume(0) → speak → status.speaking → stop（静音）
//   8) backup                  createBackup 返回沙箱内 zip/path/bookCount → restoreBackup 返回 bookCount + pre-restore 文件
//   9) translation             translationHasKey false→setKey→true→clearKey→false；Key 不进 reader.json / 明文 secrets.dat
//  10) schema                  当前库 schema_version=3；预置 v2 库启动后迁移到 3 且留 pre-migrate 备份
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
                        import-epub | open-read-turn-close | settings-forward-exit |
                        extended-app | tts | backup | translation | schema)
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
  // PB-018-B：重启验证 / schema 迁移要多次启动实例，逐个记录以便统一清理。
  phase2: null,
  allApps: [],
  tempSandboxes: [],
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

async function bridgeOn(cdp, method, ...args) {
  const call = `window.electronAPI.${method}(${args.map((arg) => JSON.stringify(arg)).join(', ')})`
  return cdp.evaluate(call)
}

async function waitForCdpTargetFor(app, port, timeoutMs) {
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

async function waitForCdpTarget(timeoutMs) {
  return waitForCdpTargetFor(state.app, state.port, timeoutMs)
}

/**
 * 在指定沙箱上启动一个新实例并连上 CDP（PB-018-B：重启验证 / schema 迁移）。
 * 单实例互斥体是会话级的，所以调用前必须确保前一个实例已退出。
 */
async function startAppOnSandbox(sandbox, logName) {
  const port = await findFreePort()
  const launch = await appLib.launchApp({
    exePath: state.exePath,
    args: [],
    env: appLib.buildAppEnv(sandbox, port),
    cwd: sandbox.cwd,
    logPath: path.join(state.artifacts, logName),
  })
  state.allApps.push(launch)
  const target = await waitForCdpTargetFor(launch, port, options.timeout)
  const cdp = new CdpClient(target.webSocketDebuggerUrl)
  await cdp.connect()
  await cdp.waitFor("document.readyState === 'complete'", {
    timeoutMs: options.timeout, label: 'renderer ready (restarted app)',
  })
  await cdp.waitFor("document.title.toLowerCase().includes('particlebook')", {
    timeoutMs: options.timeout, label: 'page title (restarted app)',
  })
  await cdp.waitFor('!!window.electronAPI', {
    timeoutMs: options.timeout, label: 'bridge shim (restarted app)',
  })
  // 失败采集用最近一个活跃页面
  state.cdp = cdp
  log(`app started on ${path.basename(sandbox.root)} (cdp port ${port}, log ${logName})`)
  return { app: launch, cdp, port }
}

async function closeAppGracefully(app, label) {
  const closed = await appLib.gracefulClose(app)
  assert(closed.graceful, `${label}: app had to be force-killed instead of exiting gracefully`)
  assertEqual(closed.code, 0, `${label}: app exit code`)
  verbose(`${label}: exited gracefully (code 0)`)
  return closed
}

async function readJsonFile(filePath) {
  return JSON.parse(await fs.readFile(filePath, 'utf8'))
}

async function waitForFileJson(filePath, predicate, label, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    try {
      last = await readJsonFile(filePath)
    } catch {
      last = null
    }
    if (last && predicate(last)) return last
    await sleep(200)
  }
  throw new Error(`timed out waiting for ${label} (${filePath}); last=${JSON.stringify(last)}`)
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

// ── PB-018-B 追加的 4 类 smoke（在旧 5 条之后；会额外重启应用）─────
async function smokeExtendedApp() {
  // 旧 5 条结束时主实例已优雅退出；在同一沙箱上重启，验证持久化数据还在。
  state.phase2 = await startAppOnSandbox(state.sandbox, 'app-phase2.log')
  const books = await bridge('getBooks')
  assert(Array.isArray(books), `getBooks after restart did not return an array: ${JSON.stringify(books)}`)
  assertEqual(books.length, 2, 'books after restart (the two fixtures should persist)')
  const settings = await bridge('getSettings')
  assertEqual(settings.theme, 'light', 'settings.theme persisted across restart')
  await state.cdp.screenshot(path.join(state.artifacts, '04-extended.png'))
  log(`restart ok: books=${books.length}, settings.theme=${settings.theme}`)
}

async function smokeTts() {
  const voices = await bridge('getTtsVoices')
  assert(Array.isArray(voices), `getTtsVoices did not return an array: ${JSON.stringify(voices)}`)
  assert(voices.length > 0, 'getTtsVoices returned no voices (no SAPI voices installed on this machine?)')
  const voice = voices[0]
  assert(voice && typeof voice.id === 'string' && voice.id.length > 0, `bad voice entry: ${JSON.stringify(voice)}`)
  assertEqual(await bridge('ttsSetVoice', voice.id), true, 'ttsSetVoice')

  // 音量 0 = 静音：只验证合成/播放状态机，不发出声音（CI / 用户机器都安静）。
  assertEqual(await bridge('ttsSetVolume', 0), true, 'ttsSetVolume(0)')
  let status = await bridge('ttsStatus')
  assert(status && typeof status === 'object', `ttsStatus did not return an object: ${JSON.stringify(status)}`)
  assertEqual(status.volume, 0, 'ttsStatus.volume after ttsSetVolume(0)')

  const utterance = await bridge('ttsSpeak',
    'ParticleBook end to end test. This sentence is synthesized by the text to speech smoke test.')
  assert(typeof utterance === 'number' && utterance > 0,
    `ttsSpeak did not return an utterance id: ${JSON.stringify(utterance)}`)

  let speaking = false
  const speakDeadline = Date.now() + 8000
  while (Date.now() < speakDeadline) {
    status = await bridge('ttsStatus')
    if (status && status.speaking === true) { speaking = true; break }
    await sleep(100)
  }
  assert(speaking, `ttsStatus.speaking never became true (last=${JSON.stringify(status)})`)

  assertEqual(await bridge('ttsStop'), true, 'ttsStop')
  let stopped = false
  const stopDeadline = Date.now() + 8000
  while (Date.now() < stopDeadline) {
    status = await bridge('ttsStatus')
    if (status && status.speaking === false) { stopped = true; break }
    await sleep(100)
  }
  assert(stopped, `ttsStatus.speaking stayed true after ttsStop (last=${JSON.stringify(status)})`)
  assertEqual(status.volume, 0, 'ttsStatus.volume after ttsStop (mute must not be reset)')
  assertEqual(await bridge('ttsSetVolume', 100), true, 'ttsSetVolume(100) restore')
  log(`tts ok: voices=${voices.length}, voice="${voice.name}", silent speak+stop`)
}

async function smokeBackup() {
  const booksBefore = await bridge('getBooks')
  assert(Array.isArray(booksBefore) && booksBefore.length === 2,
    `expected 2 books before backup, got ${JSON.stringify(booksBefore)}`)

  const created = await bridge('createBackup')
  assert(created && typeof created === 'object', `createBackup returned ${JSON.stringify(created)}`)
  assertEqual(created.ok, true, `createBackup.ok (code=${created.code} error=${created.error})`)
  assertEqual(created.bookCount, booksBefore.length, 'backup bookCount')
  assert(typeof created.path === 'string' && created.path.length > 0, 'createBackup.path')
  const backupPath = path.resolve(created.path)
  assert(backupPath.toLowerCase().startsWith(path.resolve(state.sandbox.root).toLowerCase()),
    `backup escaped the sandbox: ${backupPath}`)
  await assertFile(backupPath, 'backup archive')
  const backupStat = await fs.stat(backupPath)
  assert(backupStat.size > 0 && created.bytes > 0, `backup archive is empty (bytes=${created.bytes})`)

  const restored = await bridge('restoreBackup', created.path)
  assert(restored && typeof restored === 'object', `restoreBackup returned ${JSON.stringify(restored)}`)
  assertEqual(restored.ok, true, `restoreBackup.ok (code=${restored.code} error=${restored.error})`)
  assertEqual(restored.bookCount, booksBefore.length, 'restored bookCount')
  assert(typeof restored.preRestorePath === 'string' && restored.preRestorePath.length > 0,
    'restore should report preRestorePath')
  await assertFile(restored.preRestorePath, 'pre-restore backup')
  assert(/pre-restore/.test(path.basename(restored.preRestorePath)),
    `unexpected pre-restore file name: ${restored.preRestorePath}`)
  const booksAfter = await bridge('getBooks')
  assertEqual(booksAfter.length, booksBefore.length, 'books after restore (DB must be reloaded)')
  log(`backup ok: ${path.basename(backupPath)} (${created.bytes} bytes) -> restored ${restored.bookCount} books, pre-restore=${path.basename(restored.preRestorePath)}`)
}

async function smokeTranslationKey() {
  assertEqual(await bridge('translationHasKey'), false, 'translationHasKey should be false in a fresh sandbox')
  const key = `sk-e2e-fake-${state.sandbox.id}`
  assertEqual(await bridge('translationSetKey', key), true, 'translationSetKey')
  assertEqual(await bridge('translationHasKey'), true, 'translationHasKey after setKey')

  const secretPath = path.join(state.sandbox.localAppData, 'ParticleBook', 'secrets.dat')
  await assertFile(secretPath, 'SecretStore file (secrets.dat)')
  const secretBytes = await fs.readFile(secretPath)
  assert(!secretBytes.includes(Buffer.from(key, 'utf8')), 'secrets.dat must not contain the plaintext key')
  const dbText = await fs.readFile(state.sandbox.dataFile, 'utf8')
  assert(!dbText.includes(key), 'reader.json must not contain the translation key')

  assertEqual(await bridge('translationClearKey'), true, 'translationClearKey')
  assertEqual(await bridge('translationHasKey'), false, 'translationHasKey after clearKey')
  log('translation key ok: false -> set(true) -> clear(false); key never in reader.json / plaintext secrets.dat')
}

async function smokeSchema() {
  // A) 当前沙箱：应用自己写出的库必须是 v3
  const current = parseDataFile(await readJsonFile(state.sandbox.dataFile))
  assertEqual(current.schema_version, 3,
    'current reader.json schema_version (DatabaseService::kCurrentSchemaVersion)')

  // B) 迁移：先关掉扩展阶段实例，手写一个 v2 库，启动后必须迁移到 v3 且留 pre-migrate 备份。
  assert(state.phase2, 'extended phase app was not started')
  await closeAppGracefully(state.phase2.app, 'extended phase app')

  const migrationSandbox = await appLib.createSandbox(appLib.runId())
  state.tempSandboxes.push(migrationSandbox)
  const dataDir = path.join(migrationSandbox.dataDir, 'data')
  await appLib.ensureDir(dataDir)
  const dbFile = path.join(dataDir, 'reader.json')
  const legacyV2 = {
    schema_version: 2,
    books: [], bookmarks: [], highlights: [], notes: [],
    settings: {}, book_settings: {}, reading_progress: {},
    bookshelves: [], bookshelf_books: [], sources: [], nextId: 1,
  }
  await fs.writeFile(dbFile, JSON.stringify(legacyV2), 'utf8')

  const phase3 = await startAppOnSandbox(migrationSandbox, 'app-schema-migration.log')
  try {
    const migrated = await waitForFileJson(dbFile, (data) => data.schema_version === 3,
      'v2 -> v3 migration', options.timeout)
    assert(Array.isArray(migrated.annotations),
      'migrated DB must contain the unified annotations array (PB-021)')
    const entries = await fs.readdir(dataDir)
    const backups = entries.filter((name) => name.startsWith('reader.json.pre-migrate-v2-'))
    assert(backups.length > 0,
      `pre-migrate backup missing in ${dataDir}; files=${JSON.stringify(entries)}`)
    const backup = await readJsonFile(path.join(dataDir, backups[0]))
    assertEqual(backup.schema_version, 2, 'pre-migrate backup must preserve the original v2 payload')
    log(`schema ok: fresh=${current.schema_version}, migrated v2->v3, pre-migrate backup=${backups[0]}`)
  } finally {
    await closeAppGracefully(phase3.app, 'schema migration app')
  }
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

  // 清理进程（包含 PB-018-B 额外启动的实例；已退出的 forceKill 会直接返回）
  const allApps = new Set([...state.allApps, state.app, state.secondApp].filter(Boolean))
  for (const running of allApps) {
    await appLib.forceKill(running).catch(() => {})
  }

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
    appLogs: state.allApps.map((launched) => ({
      path: launched.logPath,
      tail: launched.output.join('').slice(-2000),
    })),
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

  // 沙箱：成功且没 --keep 才删；失败保留方便排查（含 schema 迁移用的临时沙箱）
  if (state.sandbox) {
    if (state.ok && !options.keep) {
      await appLib.removeDir(state.sandbox.root)
    } else {
      log(`sandbox kept: ${state.sandbox.root}`)
    }
  }
  for (const sandbox of state.tempSandboxes) {
    if (state.ok && !options.keep) {
      await appLib.removeDir(sandbox.root)
    } else {
      log(`sandbox kept: ${sandbox.root}`)
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

    // ── PB-018-B：在旧 5 条之后补 TTS / 备份 / 翻译 / schema 4 类 ──
    await step('extended-app', smokeExtendedApp)
    await step('tts', smokeTts)
    await step('backup', smokeBackup)
    await step('translation', smokeTranslationKey)
    await step('schema', smokeSchema)
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
