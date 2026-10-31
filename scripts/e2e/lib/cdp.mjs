// ── PB-018: 最小 CDP 客户端（零依赖，Node 22+ 内置 WebSocket）───────
// 只实现 E2E 需要的部分：目标发现（HTTP /json/list）、JSON-RPC over
// WebSocket、Runtime.evaluate / waitFor、Input 事件、Page.captureScreenshot。
//
// 为什么不用 puppeteer/playwright：项目没有这些依赖，也不想为 5 条 smoke
// 引入一个浏览器下载；WebView2 的 CDP 端点和 Chrome 一样，内置 WebSocket
// 足够（本地手工验证过同一路径）。

export function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

async function fetchJson(url, timeoutMs) {
  const response = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) })
  if (!response.ok) throw new Error(`HTTP ${response.status} for ${url}`)
  return response.json()
}

export async function findFreePort() {
  const net = await import('node:net')
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port
      server.close(() => resolve(port))
    })
  })
}

/**
 * 轮询 WebView2 的 CDP 目标列表，直到出现 page target。
 * 返回 { webSocketDebuggerUrl, title, url }。
 */
export async function waitForPageTarget(port, options = {}) {
  const timeoutMs = options.timeoutMs ?? 60000
  const deadline = Date.now() + timeoutMs
  let lastError = 'no page target yet'
  while (Date.now() < deadline) {
    try {
      const targets = await fetchJson(`http://127.0.0.1:${port}/json/list`, 2000)
      const page = targets.find((target) =>
        target.type === 'page' && typeof target.webSocketDebuggerUrl === 'string')
      if (page) return page
      lastError = `${targets.length} CDP target(s), none is a page`
    } catch (error) {
      lastError = error?.message || String(error)
    }
    await sleep(200)
  }
  throw new Error(`CDP endpoint did not come up on port ${port} within ${timeoutMs}ms (${lastError})`)
}

export class CdpClient {
  constructor(webSocketDebuggerUrl) {
    this.url = webSocketDebuggerUrl
    this.socket = null
    this.nextId = 1
    this.pending = new Map()
    this.events = []
    this.consoleLines = []
  }

  async connect(options = {}) {
    if (typeof WebSocket === 'undefined') {
      throw new Error('global WebSocket missing: run this E2E on Node.js 22 or newer')
    }
    const timeoutMs = options.timeoutMs ?? 15000
    this.socket = new WebSocket(this.url)
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('CDP WebSocket connect timed out')), timeoutMs)
      this.socket.addEventListener('open', () => {
        clearTimeout(timer)
        resolve()
      }, { once: true })
      this.socket.addEventListener('error', (event) => {
        clearTimeout(timer)
        reject(new Error(`CDP WebSocket error: ${event?.message || 'unknown'}`))
      }, { once: true })
    })
    this.socket.addEventListener('message', (event) => this.#onMessage(event))
    this.socket.addEventListener('close', () => {
      for (const { reject } of this.pending.values()) {
        reject(new Error('CDP connection closed'))
      }
      this.pending.clear()
    })
    await this.send('Runtime.enable')
    await this.send('Page.enable')
    await this.send('Log.enable')
  }

  #onMessage(event) {
    let message
    try {
      message = JSON.parse(event.data)
    } catch {
      return
    }
    if (message.id) {
      const pending = this.pending.get(message.id)
      if (!pending) return
      this.pending.delete(message.id)
      clearTimeout(pending.timer)
      if (message.error) {
        pending.reject(new Error(`CDP ${pending.method} failed: ${message.error.message}`))
      } else {
        pending.resolve(message.result)
      }
      return
    }
    this.events.push({ method: message.method, params: message.params, at: Date.now() })
    if (this.events.length > 500) this.events.shift()

    if (message.method === 'Runtime.consoleAPICalled') {
      const args = (message.params.args || [])
        .map((arg) => arg.value !== undefined ? String(arg.value) : (arg.description || arg.type))
        .join(' ')
      this.consoleLines.push(`console.${message.params.type}: ${args}`)
    } else if (message.method === 'Runtime.exceptionThrown') {
      const details = message.params.exceptionDetails
      const text = details?.exception?.description || details?.text || 'unknown exception'
      this.consoleLines.push(`page exception: ${text}`)
    } else if (message.method === 'Log.entryAdded') {
      const entry = message.params.entry
      this.consoleLines.push(`log.${entry.level}: ${entry.text}`)
    }
    if (this.consoleLines.length > 500) this.consoleLines.shift()
  }

  send(method, params = {}, timeoutMs = 20000) {
    if (!this.socket || this.socket.readyState !== 1) {
      return Promise.reject(new Error(`CDP socket is not open (method ${method})`))
    }
    const id = this.nextId++
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new Error(`CDP ${method} timed out after ${timeoutMs}ms`))
      }, timeoutMs)
      this.pending.set(id, { resolve, reject, method, timer })
      this.socket.send(JSON.stringify({ id, method, params }))
    })
  }

  /** Runtime.evaluate，返回 awaitPromise + returnByValue 后的 JS 值。 */
  async evaluate(expression, options = {}) {
    const result = await this.send('Runtime.evaluate', {
      expression,
      awaitPromise: options.awaitPromise !== false,
      returnByValue: true,
      userGesture: options.userGesture !== false,
      includeCommandLineAPI: false,
    }, options.timeoutMs ?? 30000)
    if (result.exceptionDetails) {
      const details = result.exceptionDetails
      const text = details.exception?.description || details.text || 'evaluate threw'
      throw new Error(`page evaluate failed: ${text}\n  expression: ${expression.slice(0, 400)}`)
    }
    return result.result?.value
  }

  /** 轮询表达式直到 truthy；返回该值。 */
  async waitFor(expression, options = {}) {
    const timeoutMs = options.timeoutMs ?? 30000
    const intervalMs = options.intervalMs ?? 150
    const label = options.label || expression.slice(0, 120)
    const deadline = Date.now() + timeoutMs
    let lastValue
    let lastError
    while (Date.now() < deadline) {
      try {
        lastValue = await this.evaluate(expression, { timeoutMs: 10000 })
        if (lastValue) return lastValue
      } catch (error) {
        lastError = error
      }
      await sleep(intervalMs)
    }
    const tail = this.consoleTail(10)
    throw new Error(
      `timed out after ${timeoutMs}ms waiting for: ${label}` +
      `\n  last value: ${JSON.stringify(lastValue)}` +
      (lastError ? `\n  last error: ${lastError.message}` : '') +
      (tail ? `\n  page console tail:\n${tail}` : ''))
  }

  /**
   * 找到文本等于 text 的叶子元素并返回其屏幕中心点。
   * 找不到 / 不可见时返回 null（调用方负责重试）。
   */
  async textCenter(text) {
    return this.evaluate(`(() => {
      const wanted = ${JSON.stringify(text)};
      const candidates = Array.from(document.querySelectorAll('p, span, div, h1, h2, h3, h4, a, button'));
      for (const element of candidates) {
        if (element.children.length !== 0) continue;
        if ((element.textContent || '').trim() !== wanted) continue;
        const rect = element.getBoundingClientRect();
        if (rect.width < 2 || rect.height < 2) continue;
        return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
      }
      return null;
    })()`)
  }

  async clickAt(x, y) {
    await this.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y })
    await this.send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 })
    await this.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 })
  }

  /** 点击页面上文本严格等于 text 的元素；带重试（等待 React 渲染）。 */
  async clickText(text, options = {}) {
    const timeoutMs = options.timeoutMs ?? 15000
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      const center = await this.textCenter(text)
      if (center) {
        await this.clickAt(center.x, center.y)
        return
      }
      await sleep(200)
    }
    throw new Error(`could not find a visible element whose text is "${text}"`)
  }

  /** 发送一次按键（CDP Input，比 JS KeyboardEvent 更接近真实用户）。 */
  async pressKey(key, options = {}) {
    const code = options.code || key
    const windowsVirtualKeyCode = options.windowsVirtualKeyCode ?? 0
    await this.send('Input.dispatchKeyEvent', {
      type: 'keyDown', key, code, windowsVirtualKeyCode, nativeVirtualKeyCode: windowsVirtualKeyCode,
    })
    await this.send('Input.dispatchKeyEvent', {
      type: 'keyUp', key, code, windowsVirtualKeyCode, nativeVirtualKeyCode: windowsVirtualKeyCode,
    })
  }

  async screenshot(filePath) {
    const { data } = await this.send('Page.captureScreenshot', { format: 'png', fromSurface: true })
    const { writeFile, mkdir } = await import('node:fs/promises')
    const path = await import('node:path')
    await mkdir(path.dirname(filePath), { recursive: true })
    await writeFile(filePath, Buffer.from(data, 'base64'))
    return filePath
  }

  consoleTail(lines = 30) {
    return this.consoleLines.slice(-lines).join('\n')
  }

  async close() {
    try {
      this.socket?.close()
    } catch {
      // ignore
    }
    this.socket = null
  }
}

/**
 * 读取 iframe（epubjs 渲染的正文）里的文本；跨源 / 尚未挂载时返回 null。
 * 用于断言「这本书真的渲染出正文了」，而不是只看 DOM 里有没有 iframe。
 */
export function iframeTextExpression() {
  return `(() => {
    try {
      const iframe = document.querySelector('iframe');
      if (!iframe) return null;
      const doc = iframe.contentDocument || iframe.contentWindow?.document;
      if (!doc || !doc.body) return null;
      return doc.body.innerText || doc.body.textContent || '';
    } catch (error) {
      return null;
    }
  })()`
}
