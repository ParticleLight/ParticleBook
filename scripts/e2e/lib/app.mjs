// ── PB-018: 应用启动 / 沙箱 / 进程管理 ───────────────────────────
// E2E 不碰真实用户数据：USERPROFILE + APPDATA + LOCALAPPDATA + TEMP 全部
// 指向临时目录，并预先建出 Windows 认识的最小 profile 结构
// （<user>\AppData\Roaming / Local）。实测只有这种「profile 形状」才让
// SHGetFolderPathW(CSIDL_APPDATA) 跟随 USERPROFILE（App::UserDataPath 用它）；
// 只改 APPDATA 无效。
//   → DB / WebView2 user data / SecretStore / 日志全部落在沙箱内。
// CDP：WebView2 支持 WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS，启动时加
// --remote-debugging-port=<随机空闲端口>（不改 C++）。
// 单实例：应用用会话级互斥体 Local\ParticleBook.SingleInstance；如果用户
// 已经开着 ParticleBook，再启动只会把文件转发给它。E2E 在启动前检测并直接
// 报错（--allow-running 可跳过，但会有污染风险）。

import { spawn, execFile } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fnv1a64Hex } from './fixtures.mjs'

export const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')

export function defaultAppPath() {
  return path.join(REPO_ROOT, 'particlebook-cpp', 'build2', 'ParticleBook.exe')
}

export function runId() {
  const stamp = new Date().toISOString().replace(/[-:]/g, '').replace(/\..+$/, '')
  const random = Math.random().toString(16).slice(2, 8)
  return `${stamp}-${random}`
}

export async function ensureDir(dir) {
  await fs.mkdir(dir, { recursive: true })
  return dir
}

export async function pathExists(target) {
  try {
    await fs.stat(target)
    return true
  } catch {
    return false
  }
}

export async function waitForPath(target, timeoutMs = 15000, intervalMs = 200) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await pathExists(target)) return true
    await wait(intervalMs)
  }
  throw new Error(`timed out after ${timeoutMs}ms waiting for path: ${target}`)
}

export function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * 创建隔离沙箱。返回所有会被用到的路径。
 */
export async function createSandbox(id = runId()) {
  const root = path.join(os.tmpdir(), 'particlebook-e2e', id)
  const user = path.join(root, 'user')
  const sandbox = {
    id,
    root,
    user,
    appData: path.join(user, 'AppData', 'Roaming'),
    localAppData: path.join(user, 'AppData', 'Local'),
    desktop: path.join(user, 'Desktop'),
    documents: path.join(user, 'Documents'),
    temp: path.join(root, 'tmp'),
    files: path.join(root, 'files'),
    cwd: path.join(root, 'cwd'),
    artifacts: path.join(root, 'artifacts'),
  }
  sandbox.dataDir = path.join(sandbox.appData, 'particle-book')
  sandbox.dataFile = path.join(sandbox.dataDir, 'data', 'reader.json')
  for (const dir of [sandbox.appData, sandbox.localAppData, sandbox.desktop,
                     sandbox.documents, sandbox.temp, sandbox.files, sandbox.cwd,
                     sandbox.artifacts]) {
    await ensureDir(dir)
  }
  return sandbox
}

export function buildAppEnv(sandbox, port) {
  const env = { ...process.env }
  delete env.ELECTRON_RUN_AS_NODE
  env.USERPROFILE = sandbox.user
  env.APPDATA = sandbox.appData
  env.LOCALAPPDATA = sandbox.localAppData
  env.TEMP = sandbox.temp
  env.TMP = sandbox.temp
  const parsed = path.parse(sandbox.user)
  env.HOMEDRIVE = parsed.root.replace(/[\\/]+$/, '')
  env.HOMEPATH = sandbox.user.slice(parsed.root.length - 1)
  const existingArgs = env.WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS || ''
  env.WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS =
    `${existingArgs} ${existingArgs.trim() ? '' : ''}--remote-debugging-port=${port}`.trim()
  env.PB_E2E_RUN_ID = sandbox.id
  return env
}

function execFileText(file, args, options = {}) {
  return new Promise((resolve, reject) => {
    execFile(file, args, { windowsHide: true, timeout: options.timeoutMs ?? 15000 },
      (error, stdout, stderr) => {
        if (error && error.code !== 1) {
          // tasklist returns 1 when no process matches; treat as "no output".
          resolve({ ok: false, stdout: stdout || '', stderr: stderr || '', error })
          return
        }
        resolve({ ok: !error, stdout: stdout || '', stderr: stderr || '', error })
      })
  })
}

/** 是否已有 ParticleBook.exe 在跑（单实例互斥体会抢走我们的启动）。 */
export async function isAppRunning(exeName = 'ParticleBook.exe') {
  const result = await execFileText('tasklist', ['/FI', `IMAGENAME eq ${exeName}`, '/FO', 'CSV', '/NH'])
  return result.stdout.toLowerCase().includes(exeName.toLowerCase())
}

/**
 * 启动应用。stdout/stderr 写入 logPath（同时保留内存尾部，失败报告用）。
 * 返回 { child, logPath, output: string[], waitExit() }。
 */
export async function launchApp(options) {
  const { exePath, args = [], env, cwd, logPath } = options
  await ensureDir(path.dirname(logPath))
  const logHandle = await fs.open(logPath, 'w')
  const output = []
  const child = spawn(exePath, args, {
    cwd,
    env,
    windowsHide: false,
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  const append = (chunk) => {
    const text = chunk.toString('utf8')
    output.push(text)
    if (output.length > 300) output.shift()
    logHandle.write(text).catch(() => {})
  }
  child.stdout?.on('data', append)
  child.stderr?.on('data', append)
  child.on('error', (error) => append(`[spawn error] ${error.message}\n`))

  const exited = new Promise((resolve) => {
    child.once('exit', (code, signal) => {
      const info = { code, signal }
      append(`\n[process exited] code=${code} signal=${signal}\n`)
      logHandle.close().catch(() => {})
      resolve(info)
    })
  })

  return {
    child,
    logPath,
    output,
    exited,
    exitedAlready: () => child.exitCode !== null || child.signalCode !== null,
    async waitExit(timeoutMs = 20000) {
      const timer = new Promise((resolve) => setTimeout(() => resolve(null), timeoutMs))
      const result = await Promise.race([exited, timer])
      return result
    },
  }
}

/** 等一个已启动进程退出；超时返回 null。 */
export async function waitExit(app, timeoutMs = 20000) {
  if (app.child.exitCode !== null || app.child.signalCode !== null) {
    return { code: app.child.exitCode, signal: app.child.signalCode }
  }
  return app.waitExit(timeoutMs)
}

/**
 * 优雅关闭：taskkill 不带 /F 会发 WM_CLOSE → 应用走 App::Shutdown（落盘）。
 * 超时则 /F /T 强杀，返回 graceful=false 让 smoke 报出来。
 */
export async function gracefulClose(app, options = {}) {
  const timeoutMs = options.timeoutMs ?? 25000
  if (app.child.exitCode !== null || app.child.signalCode !== null) {
    return { graceful: true, code: app.child.exitCode, signal: app.child.signalCode }
  }
  await execFileText('taskkill', ['/PID', String(app.child.pid)])
  const result = await waitExit(app, timeoutMs)
  if (result) return { graceful: true, ...result }
  await forceKill(app)
  return { graceful: false, code: app.child.exitCode, signal: app.child.signalCode }
}

export async function forceKill(app) {
  if (!app || !app.child || app.child.exitCode !== null) return
  try {
    await execFileText('taskkill', ['/F', '/T', '/PID', String(app.child.pid)])
  } catch {
    // ignore
  }
  await waitExit(app, 10000)
}

export async function readJsonIfExists(filePath) {
  try {
    const text = await fs.readFile(filePath, 'utf8')
    return JSON.parse(text)
  } catch {
    return null
  }
}

export async function copyIfExists(source, destination) {
  try {
    await fs.copyFile(source, destination)
    return true
  } catch {
    return false
  }
}

export async function removeDir(dir) {
  try {
    await fs.rm(dir, { recursive: true, force: true, maxRetries: 3 })
  } catch {
    // ignore
  }
}

/**
 * 只删本次 fixture 在 renderer/_pb_files 里生成的缓存副本
 * （FileHandlers 用 FNV-1a 64(path) 命名）。开发者既有缓存不受影响。
 */
export async function cleanupFixtureCache(exePath, fixturePaths) {
  const cacheDir = path.join(path.dirname(exePath), 'renderer', '_pb_files')
  const removed = []
  for (const fixturePath of fixturePaths) {
    const fileName = fnv1a64Hex(fixturePath)
    const target = path.join(cacheDir, fileName)
    try {
      await fs.unlink(target)
      removed.push(target)
    } catch {
      // not copied (import failure) — fine
    }
  }
  return removed
}

/** 应用数据文件（reader.json）是否落在沙箱里 —— 隔离自检。 */
export async function assertIsolation(sandbox) {
  const expected = sandbox.dataDir
  const resolved = path.resolve(expected)
  if (!resolved.toLowerCase().startsWith(path.resolve(sandbox.root).toLowerCase())) {
    throw new Error(`E2E data dir escaped the sandbox: ${resolved}`)
  }
  // WebView2 会立刻在 user data folder 下建 EBWebView；建出来即证明
  // App::UserDataPath() 确实跟随了临时 profile。
  await waitForPath(path.join(sandbox.dataDir, 'EBWebView'), 20000)
}

/**
 * 快照目录里的文件名（目录不存在 → 空集合）。用于 PB-018-C 的 PDF 渲染缓存：
 * PdfService 把 PNG 写到 build2/renderer/_pb_pdf/doc_<id>_page_<n>.png，
 * 结束时只删本次 run 新产生的文件（不碰开发者既有缓存）。
 */
export async function snapshotDir(dir) {
  try {
    const entries = await fs.readdir(dir)
    return new Set(entries)
  } catch {
    return new Set()
  }
}

/** 删除 dir 中不在 before 快照里的文件；返回删除列表。 */
export async function removeNewFiles(dir, before) {
  const removed = []
  if (!before) return removed
  try {
    const entries = await fs.readdir(dir)
    for (const name of entries) {
      if (before.has(name)) continue
      const target = path.join(dir, name)
      try {
        await fs.unlink(target)
        removed.push(target)
      } catch {
        // ignore
      }
    }
  } catch {
    // dir missing
  }
  return removed
}
