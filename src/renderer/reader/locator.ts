// ── PB-021-E：ReaderAdapter 的位置 / 选区工具 ────────────────────────
//
// 渲染层没有统一的"书内位置"来源：EPUB 有 CFI，TXT 有字符偏移，HTML 只有
// 滚动百分比。本文件把这些口径收敛成 PbLocator，并实现两件底层能力：
//   * 从当前阅读进度构造 locator（getLocator 用）；
//   * 把 locator 翻成 readerStore 现有的 navigateTo / seekTo 调用（goTo 用）。
// 选区相关：主文档与 EPUB iframe 都能读到 Selection；只有 TXT/HTML 能算出
// 稳定的字符偏移（用滚动容器的文本前缀长度），EPUB 章节内拿不到 CFI
// （epub.js rendition 不对外暴露，见 docs 报告）。

import { useReaderStore, type ReadingProgress } from '../stores/readerStore'
import type { ReaderLocator, ReaderSelection } from './types'

export function clampPercent(value: number): number {
  if (!Number.isFinite(value)) return 0
  return Math.min(100, Math.max(0, value))
}

/** 码点数（Locator.offset 的口径；JS 字符串 length 是 UTF-16 单元数）。 */
export function countCodepoints(text: string): number {
  let count = 0
  for (const _ of text) count += 1
  return count
}

/** 阅读进度 → Locator（与 AnnotationPanel 的映射口径一致）。 */
export function locatorFromProgress(format: string, progress: ReadingProgress): ReaderLocator | null {
  if (format === 'epub') {
    return progress.cfi ? { version: 1, kind: 'epub', cfi: progress.cfi } : null
  }
  if (format === 'pdf' || format === 'cbz' || format === 'cbr') {
    const page = typeof progress.page === 'number' && progress.page >= 1 ? Math.round(progress.page) : 1
    return { version: 1, kind: 'pdf', page }
  }
  // 文本类：旧进度把百分比写在 page（TXT）或 progress（HTML）里。
  const percent = clampPercent(
    typeof progress.page === 'number' && progress.page > 0
      ? progress.page
      : typeof progress.progress === 'number'
        ? progress.progress
        : 0,
  )
  return { version: 1, kind: 'scroll', percent }
}

/**
 * locator → readerStore 的导航调用。返回 false 表示这个 locator 当前渲染器
 * 不认识（调用方可以提示"无法跳转"）。
 */
export function goToLocator(locator: ReaderLocator): boolean {
  const reader = useReaderStore.getState()
  switch (locator.kind) {
    case 'epub':
      if (!locator.cfi) return false
      reader.navigateTo({ cfi: locator.cfi })
      return true
    case 'pdf':
      if (typeof locator.page !== 'number' || locator.page < 1) return false
      reader.navigateTo({ page: locator.page })
      return true
    case 'text': {
      if (typeof locator.offset !== 'number' || locator.offset < 0) return false
      // TextRenderer 既有锚点格式 toc-<字符 offset>；HTML 渲染器不认，调用方
      // 在 HTML 上应该给 scroll locator（adapter 的 getSelection 就是这么产出的）。
      reader.navigateTo({ cfi: 'toc-' + locator.offset })
      return true
    }
    case 'html': {
      const fragment = (locator.href ?? '').split('#')[1]
      if (fragment) {
        reader.navigateTo({ cfi: fragment })
        return true
      }
      return false
    }
    case 'scroll':
      if (typeof locator.percent !== 'number') return false
      reader.seekTo(clampPercent(locator.percent))
      return true
    default:
      return false
  }
}

// ── 选区 ─────────────────────────────────────────────────────────────

interface RawSelection {
  doc: Document
  range: Range
  text: string
  iframe: HTMLIFrameElement | null
}

function readSelection(doc: Document, iframe: HTMLIFrameElement | null): RawSelection | null {
  let selection: Selection | null = null
  try {
    selection = doc.getSelection ? doc.getSelection() : null
  } catch {
    return null
  }
  if (!selection || selection.isCollapsed || selection.rangeCount === 0) return null
  const text = selection.toString().replace(/\s+/g, ' ').trim()
  if (!text) return null
  return { doc, range: selection.getRangeAt(0), text, iframe }
}

/** 主文档选区。 */
export function mainDocumentSelection(): RawSelection | null {
  return readSelection(document, null)
}

/** EPUB iframe 里的选区（同源 iframe；跨域会被 try/catch 跳过）。 */
export function iframeDocumentSelection(): RawSelection | null {
  for (const frame of Array.from(document.querySelectorAll('iframe'))) {
    try {
      const doc = frame.contentDocument
      if (!doc) continue
      const found = readSelection(doc, frame)
      if (found) return found
    } catch {
      /* cross-origin iframe */
    }
  }
  return null
}

/** 向上找最近的滚动容器（渲染器的正文滚动区）；找不到返回 null（用 body）。 */
export function findScrollContainer(node: Node | null): HTMLElement | null {
  let element: Element | null = node
    ? node.nodeType === Node.ELEMENT_NODE
      ? (node as Element)
      : node.parentElement
    : null
  while (element && element !== document.body) {
    try {
      const style = window.getComputedStyle(element)
      const scrollable = /(auto|scroll)/.test(style.overflowY)
      if (scrollable && element.scrollHeight - element.clientHeight > 4) {
        return element as HTMLElement
      }
    } catch {
      /* ignore */
    }
    element = element.parentElement
  }
  return null
}

/** 从 root 开头到 range.start 的码点数（选区绝对偏移）。 */
export function codepointsBefore(root: Node, range: Range): number {
  try {
    const before = range.cloneRange()
    before.selectNodeContents(root)
    before.setEnd(range.startContainer, range.startOffset)
    return countCodepoints(before.toString())
  } catch {
    return -1
  }
}

function rectOfSelection(raw: RawSelection): ReaderSelection['rect'] {
  try {
    const box = raw.range.getBoundingClientRect()
    if (!box || (box.width === 0 && box.height === 0)) return undefined
    let left = box.left
    let top = box.top
    if (raw.iframe) {
      const frameBox = raw.iframe.getBoundingClientRect()
      left += frameBox.left
      top += frameBox.top
    }
    return { x: left, y: top, width: box.width, height: box.height }
  } catch {
    return undefined
  }
}

/**
 * 选区 → Locator：
 *   * EPUB：拿不到 CFI（epub.js rendition 不能从 DOM 反推），返回 null；
 *   * TXT/MOBI：字符偏移（TextRenderer 认 toc-<offset>）；
 *   * HTML/MD/FB2：滚动百分比（HtmlRenderer 只认滚动 / 元素 id，字符偏移不可靠）。
 */
export function locatorForSelection(format: string, raw: RawSelection): ReaderLocator | null {
  if (format === 'epub') return null
  const container = findScrollContainer(raw.range.startContainer) ?? raw.doc.body
  if (!container) return null
  const offset = codepointsBefore(container, raw.range)
  if (offset < 0) return null
  const length = countCodepoints(raw.range.toString())

  if (format === 'txt' || format === 'mobi') {
    return { version: 1, kind: 'text', offset, length, quote: raw.text }
  }
  // HTML 类：滚动百分比（按选区在容器内的纵向位置估算）。
  try {
    const rangeBox = raw.range.getBoundingClientRect()
    const containerBox = container.getBoundingClientRect()
    const scrollable = Math.max(1, container.scrollHeight - container.clientHeight)
    const offsetInContainer = rangeBox.top - containerBox.top + container.scrollTop
    const percent = clampPercent((offsetInContainer / Math.max(1, container.scrollHeight)) * 100)
    void scrollable
    return { version: 1, kind: 'scroll', percent, quote: raw.text }
  } catch {
    return { version: 1, kind: 'scroll', percent: 0, quote: raw.text }
  }
}

/** 当前选区（主文档优先，其次 EPUB iframe），带 text / locator / rect。 */
export function currentSelection(format: string): ReaderSelection | null {
  const raw = mainDocumentSelection() ?? iframeDocumentSelection()
  if (!raw) return null
  return {
    text: raw.text,
    locator: locatorForSelection(format, raw),
    rect: rectOfSelection(raw),
  }
}
