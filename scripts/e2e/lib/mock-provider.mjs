// ── PB-018-D: 本地 OpenAI 兼容 mock provider（零依赖，127.0.0.1 随机端口）──
// E2E 用它验证 TranslationService 的真实 HTTP 路径（WinHTTP → 本机回环），
// 不联网、不碰真实 provider：
//   * 成功：把请求里的 segments 原样回成 "MOCKED: <原文>"；
//   * 429 / 500：返回带 error.message 的错误体，用来断言错误码映射。
// 只监听 127.0.0.1；端口由系统分配；请求/头/正文全部留在内存里供断言。

import http from 'node:http'

export async function startMockTranslationServer() {
  const requests = []
  const mode = { current: 'ok' }

  const server = http.createServer((request, response) => {
    let body = ''
    request.setEncoding('utf8')
    request.on('data', (chunk) => { body += chunk })
    request.on('end', () => {
      requests.push({
        method: request.method,
        url: request.url,
        headers: request.headers,
        body,
      })

      if (mode.current === '429' || mode.current === '500') {
        const status = Number(mode.current)
        response.writeHead(status, { 'content-type': 'application/json' })
        response.end(JSON.stringify({
          error: { message: status === 429 ? 'mock rate limit exceeded' : 'mock internal error', type: 'mock_error' },
        }))
        return
      }

      let content = ''
      try {
        const envelope = JSON.parse(body)
        const user = JSON.parse(envelope.messages[1].content)
        const segments = (user.segments || []).map((segment) => ({
          id: segment.id,
          text: 'MOCKED: ' + segment.text,
        }))
        content = JSON.stringify({ segments })
      } catch (error) {
        response.writeHead(400, { 'content-type': 'application/json' })
        response.end(JSON.stringify({ error: { message: 'mock could not parse request: ' + error.message } }))
        return
      }

      response.writeHead(200, { 'content-type': 'application/json' })
      response.end(JSON.stringify({
        id: 'chatcmpl-mock',
        object: 'chat.completion',
        choices: [{
          index: 0,
          message: { role: 'assistant', content },
          finish_reason: 'stop',
        }],
        usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
      }))
    })
  })

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const { port } = server.address()

  return {
    port,
    baseUrl: `http://127.0.0.1:${port}/v1`,
    requests,
    setMode(next) { mode.current = next },
    getMode() { return mode.current },
    close() {
      // WinHTTP 可能复用 keep-alive 连接；先断开所有连接，避免 server.close 挂住。
      return new Promise((resolve) => {
        try { server.closeAllConnections() } catch { /* older node */ }
        server.close(() => resolve())
      })
    },
  }
}
