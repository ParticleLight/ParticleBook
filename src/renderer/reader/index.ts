// ── PB-021-E：ReaderAdapter 入口 ─────────────────────────────────────
//
// usage（ReaderView）：
//   const adapter = useMemo(() => createReaderAdapter(book.format, bookId), [book.format, bookId])
//   <ReaderAdapterProvider adapter={adapter}> ... </ReaderAdapterProvider>
//   window.__pbReaderAdapter  // 调试 / E2E
//
// 子组件：
//   const adapter = useReaderAdapter()
//   const selection = adapter?.getSelection()   // { text, locator? }
//   adapter?.goTo({ version: 1, kind: 'text', offset: 120 })

export * from './types'
export { ReaderAdapterProvider, useReaderAdapter } from './ReaderAdapterContext'
export { createDomAdapter } from './domAdapter'
export { createEpubAdapter } from './epubAdapter'

import { createDomAdapter } from './domAdapter'
import { createEpubAdapter } from './epubAdapter'
import type { ReaderAdapter } from './types'

/** 按格式创建 adapter：EPUB 走 iframe 版，其余走 DOM 版（TXT/HTML/MD/FB2/PDF）。 */
export function createReaderAdapter(format: string, bookId: number): ReaderAdapter {
  return format === 'epub' ? createEpubAdapter(bookId) : createDomAdapter(format, bookId)
}
