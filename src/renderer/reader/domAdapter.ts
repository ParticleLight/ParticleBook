// ── PB-021-E：TXT / HTML / Markdown / FB2 的 DOM adapter ─────────────
//
// 这些渲染器把正文渲染在主文档里（可滚动 div），所以：
//   * 选区：window.getSelection()（主文档），字符偏移按滚动容器文本前缀算；
//   * 文本模型：取文本量最大的可滚动容器 innerText；
//   * 搜索：在文本模型上做数据层搜索（视觉高亮仍由渲染器自己的搜索 UI 负责）；
//   * 标注：走 annotations bridge + 刷新旧表，渲染器会按旧表重画高亮。
//
// 不修改渲染器：所有操作只读 DOM / 调 readerStore。

import { countCodepoints, currentSelection, goToLocator, locatorFromProgress } from './locator'
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

/** 找"正文"容器：文本量最大的纵向可滚动块（渲染器的阅读区）。 */
function findReadingRoot(): HTMLElement | null {
  let best: HTMLElement | null = null
  let bestLength = 0
  for (const element of Array.from(document.querySelectorAll('div,main,article,section'))) {
    const text = (element as HTMLElement).innerText ?? ''
    if (text.length < 120) continue
    let style: CSSStyleDeclaration
    try {
      style = window.getComputedStyle(element)
    } catch {
      continue
    }
    if (!/(auto|scroll)/.test(style.overflowY)) continue
    if (text.length > bestLength) {
      best = element as HTMLElement
      bestLength = text.length
    }
  }
  return best
}

function readingText(): ReaderTextModel | null {
  const root = findReadingRoot()
  if (!root) return null
  const text = (root.innerText ?? '').replace(/\r\n/g, '\n')
  if (!text) return null
  return { text, length: countCodepoints(text), scope: 'document', source: 'dom' }
}

/** 码点下标 ↔ UTF-16 下标（搜索用；一次线性扫描维护）。 */
function codepointOffsetAt(text: string, utf16Index: number): number {
  const slice = text.slice(0, Math.max(0, utf16Index))
  return countCodepoints(slice)
}

function locatorForMatch(format: string, model: ReaderTextModel, startCp: number, lengthCp: number): ReaderLocator {
  if (format === 'txt' || format === 'mobi') {
    return { version: 1, kind: 'text', offset: startCp, length: lengthCp }
  }
  // HTML 类：只有滚动比例可靠（渲染器不认字符偏移）。
  const percent = model.length > 0 ? (startCp / model.length) * 100 : 0
  return { version: 1, kind: 'scroll', percent: Math.min(100, Math.max(0, percent)) }
}

export function createDomAdapter(format: string, bookId: number): ReaderAdapter {
  return {
    format,
    bookId,

    getLocator(): ReaderLocator | null {
      return locatorFromProgress(format, useReaderStore.getState().progress)
    },

    goTo(locator: ReaderLocator): boolean {
      return goToLocator(locator)
    },

    getSelection(): ReaderSelection | null {
      return currentSelection(format)
    },

    getTextModel(): ReaderTextModel | null {
      return readingText()
    },

    search(query: string, options?: { limit?: number }): ReaderSearchMatch[] {
      const q = query.trim().toLowerCase()
      const model = readingText()
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
        const snippet = model.text.slice(snippetStart, snippetEnd).replace(/\s+/g, ' ')
        matches.push({
          start: startCp,
          length: lengthCp,
          snippet,
          locator: locatorForMatch(format, model, startCp, lengthCp),
        })
        from = at + Math.max(1, q.length)
      }
      return matches
    },

    async applyAnnotation(request: ReaderApplyAnnotationRequest): Promise<PbAnnotation | null> {
      const selection = currentSelection(format)
      const fallback = selection?.locator ?? locatorFromProgress(format, useReaderStore.getState().progress)
      return applyAnnotationViaBridge(bookId, request, fallback)
    },

    async removeAnnotation(target: ReaderRemoveAnnotationTarget): Promise<boolean> {
      return removeAnnotationViaBridge(bookId, target)
    },
  }
}
