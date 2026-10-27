// ── PB-021-E：EPUB adapter ───────────────────────────────────────────
//
// EPUB 由 epub.js 渲染在（同源）iframe 里：
//   * 选区文本：读 iframe 的 Selection ✓
//   * 当前位置：readerStore.progress.cfi（epub.js 的 relocated 事件写入）✓
//   * goTo(cfi)：navigateTo({cfi}) → EpubRenderer 调 rendition.display(cfi) ✓
//   * 章节文本：iframe body.innerText（仅当前章节，全文需遍历 spine，留给 PB-022）✓
//   * 选区 → CFI：epub.js 的 rendition 只在 EpubRenderer 内部持有，DOM 侧
//     反推不出可靠 CFI —— 所以 getSelection 只返回 text，applyAnnotation 的
//     highlight 需要调用方显式给 locator（cfi），否则返回 null（已在报告注明）。
//
// 标注写入仍走 annotations bridge；C++ 双写旧表 + refreshLegacyAnnotations
// 后，EpubRenderer 会按 readerStore.highlights 重画（它本来就订阅旧列表）。

import { countCodepoints, currentSelection, goToLocator, locatorFromProgress, iframeDocumentSelection } from './locator'
import { applyAnnotationViaBridge, removeAnnotationViaBridge } from './annotations'
import { useReaderStore } from '../stores/readerStore'
import type {
  ReaderAdapter,
  ReaderApplyAnnotationRequest,
  ReaderLocator,
  ReaderRemoveAnnotationTarget,
  ReaderSearchMatch,
  ReaderSelection,
  ReaderTextModel,
} from './types'

function currentIframeDocument(): Document | null {
  for (const frame of Array.from(document.querySelectorAll('iframe'))) {
    try {
      if (frame.contentDocument && frame.contentDocument.body) return frame.contentDocument
    } catch {
      /* cross-origin */
    }
  }
  return null
}

function chapterText(): ReaderTextModel | null {
  const doc = currentIframeDocument()
  if (!doc || !doc.body) return null
  const text = (doc.body.innerText ?? '').replace(/\r\n/g, '\n')
  if (!text) return null
  return { text, length: countCodepoints(text), scope: 'chapter', source: 'iframe' }
}

function codepointOffsetAt(text: string, utf16Index: number): number {
  return countCodepoints(text.slice(0, Math.max(0, utf16Index)))
}

export function createEpubAdapter(bookId: number): ReaderAdapter {
  return {
    format: 'epub',
    bookId,

    getLocator(): ReaderLocator | null {
      return locatorFromProgress('epub', useReaderStore.getState().progress)
    },

    goTo(locator: ReaderLocator): boolean {
      const handled = goToLocator(locator)
      // PB-006-C/D：EPUB 搜索命中的章节内定位。
      //  - paginated flow（EpubRenderer 默认）下 iframe 不可滚动：调用
      //    EpubRenderer 暴露的 __pbEpubSeekQuote(quote)，在 rendition.getContents()
      //    DOM 里找到文本 Range → CFI → rendition.display（真正换页到命中位置）；
      //  - quote 缺失 / 未命中（多词查询不连续、旧索引无 quote）→ 退回按
      //    offset/章节长度比例滚动（scrolled flow 或渲染器未注册 hook 时）。
      if (handled && locator.kind === 'scroll') {
        const quote = typeof locator.quote === 'string' ? locator.quote.trim() : ''
        const relativeOffset = typeof locator.offset === 'number' && locator.offset >= 0
          ? locator.offset
          : -1
        const timers: number[] = []
        const clearTimers = () => {
          while (timers.length > 0) {
            const id = timers.pop()
            if (id !== undefined) window.clearTimeout(id)
          }
        }
        const trySeek = (): boolean => {
          const seek = (window as any).__pbEpubSeekQuote
          if (quote.length >= 2 && typeof seek === 'function') {
            try {
              if (seek(quote)) {
                clearTimers()
                return true
              }
            } catch (error) {
              console.debug('epubAdapter: seek quote failed', error)
            }
          }
          if (relativeOffset < 0) return false
          let scrolled = false
          for (const frame of Array.from(document.querySelectorAll('iframe'))) {
            try {
              const doc = frame.contentDocument
              const win = doc?.defaultView
              if (!doc || !win || !doc.body) continue
              const frameBox = frame.getBoundingClientRect()
              if (frameBox.width < 40 || frameBox.height < 40) continue
              const length = countCodepoints(doc.body.innerText ?? '')
              if (length <= 0) continue
              const ratio = Math.min(1, Math.max(0, relativeOffset / length))
              const scroller = (doc.scrollingElement ?? doc.documentElement) as HTMLElement | null
              const maxScroll = Math.max(0, (scroller?.scrollHeight ?? 0) - win.innerHeight)
              if (maxScroll <= 0) continue
              win.scrollTo(0, ratio * maxScroll)
              scrolled = true
            } catch (error) {
              console.debug('epubAdapter: within-chapter scroll failed', error)
            }
          }
          if (scrolled) clearTimers()
          return scrolled
        }
        for (const delay of [250, 700, 1400, 2400, 3800, 5500]) {
          timers.push(window.setTimeout(trySeek, delay))
        }
      }
      return handled
    },

    // EPUB 选区：只有文本可靠；CFI 见文件头说明。
    getSelection(): ReaderSelection | null {
      const raw = iframeDocumentSelection() ?? currentSelection('epub')
      if (!raw) return null
      return currentSelection('epub')
    },

    getTextModel(): ReaderTextModel | null {
      return chapterText()
    },

    search(query: string, options?: { limit?: number }): ReaderSearchMatch[] {
      const q = query.trim().toLowerCase()
      const model = chapterText()
      if (!q || !model) return []
      const limit = Math.max(1, Math.min(options?.limit ?? 50, 200))
      const haystack = model.text.toLowerCase()
      const matches: ReaderSearchMatch[] = []
      let from = 0
      while (matches.length < limit) {
        const at = haystack.indexOf(q, from)
        if (at < 0) break
        const startCp = codepointOffsetAt(model.text, at)
        const lengthCp = countCodepoints(model.text.slice(at, at + q.length))
        const snippetStart = Math.max(0, at - 40)
        const snippetEnd = Math.min(model.text.length, at + q.length + 40)
        matches.push({
          start: startCp,
          length: lengthCp,
          snippet: model.text.slice(snippetStart, snippetEnd).replace(/\s+/g, ' '),
          locator: null, // 当前章节内无法从 DOM 反推 CFI
        })
        from = at + Math.max(1, q.length)
      }
      return matches
    },

    async applyAnnotation(request: ReaderApplyAnnotationRequest): Promise<PbAnnotation | null> {
      const selection = currentSelection('epub')
      // 高亮必须有可落地的 CFI：调用方给 request.locator，或按当前进度收书签/笔记。
      const fallback =
        request.type === 'highlight'
          ? null
          : locatorFromProgress('epub', useReaderStore.getState().progress)
      return applyAnnotationViaBridge(
        bookId,
        { ...request, text: request.text ?? selection?.text },
        fallback,
      )
    },

    async removeAnnotation(target: ReaderRemoveAnnotationTarget): Promise<boolean> {
      return removeAnnotationViaBridge(bookId, target)
    },
  }
}
