// ── PB-018-A: 本地 Z-Library 下载 mock（127.0.0.1）────────────────────
// 给真实 WebView2 提供一个「点下载就得到附件」的 URL：
//   GET /pb-download.epub  -> 200 + Content-Disposition: attachment + EPUB 字节
// 这样真机流程完全不依赖公网：
//   1) 页面导航到这个 URL → WebView2 触发 DownloadStarting；
//   2) ZLibraryService（m_zlibActive 时）拦截下载、改用 WinHTTP 拉取；
//   3) 下载完成 → 导入书架。
// 只有 127.0.0.1 监听 + 随机端口；请求记录留在内存里供 E2E 断言。

import http from 'node:http'

export async function startMockZlibServer(options = {}) {
  const fileName = options.fileName || 'pb-zlib-download.epub'
  const listenPort = Number.isInteger(options.port) ? options.port : 0
  const epubBuffer = Buffer.isBuffer(options.epubBuffer)
    ? options.epubBuffer
    : Buffer.from(options.epubBuffer || '')
  const requests = []

  const server = http.createServer((request, response) => {
    requests.push({
      method: request.method,
      url: request.url,
      headers: request.headers,
    })

    if (request.url === '/pb-download.epub') {
      response.writeHead(200, {
        'Content-Type': 'application/epub+zip',
        'Content-Length': String(epubBuffer.length),
        'Content-Disposition': `attachment; filename="${fileName}"`,
        'Cache-Control': 'no-store',
      })
      response.end(epubBuffer)
      return
    }

    response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' })
    response.end('not found')
  })

  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(listenPort, '127.0.0.1', resolve)
  })
  const { port } = server.address()
  // 注意：ZLibraryService::StartDownloadThread 手写 URL 解析、WinHTTP 端口固定按
  // 协议默认值（http=80/https=443），URL 里的 :port 会被忽略 —— 所以真实下载
  // mock 必须监听 80。
  const hostPart = port === 80 ? '127.0.0.1' : `127.0.0.1:${port}`

  return {
    port,
    fileName,
    size: epubBuffer.length,
    url: `http://${hostPart}/pb-download.epub`,
    requests,
    close() {
      return new Promise((resolve) => {
        try { server.closeAllConnections() } catch { /* older node */ }
        server.close(() => resolve())
      })
    },
  }
}
