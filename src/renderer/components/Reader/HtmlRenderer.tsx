import { useEffect, useState, useRef } from 'react'
import MarkdownIt from 'markdown-it'
import { XMLParser } from 'fast-xml-parser'
import DOMPurify from 'dompurify'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { useReaderStore } from '../../stores/readerStore'
import { usePaging } from '../../reader'
import { useSettingsStore } from '../../stores/settingsStore'
import { highlightTextInDOM } from '../../utils/domSearch'
import type { Book } from '../../stores/libraryStore'

interface HtmlRendererProps {
  book: Book
  content: Uint8Array
  bookId: number
}

const md = new MarkdownIt({ html: false, linkify: true, typographer: true })

// ── review F-07：HTML/Markdown/FB2/DOCX 的高亮渲染 ────────────────────
// 这四种格式走 HtmlRenderer，而它以前完全不画 user highlights（点了「高亮」只有
// DB 里多一条，正文毫无变化）。这里用 **CSS Custom Highlight API** 渲染：
// 不改 DOM（正文是 React 的 dangerouslySetInnerHTML，改 DOM 会和重渲染打架），
// 只注册 ::highlight() 伪元素；样式由注入的一个 <style> 提供。
//   * 按颜色分组，每组一个 registry 名字（一个 Highlight 只能有一种样式）；
//   * 同一段文字可能有多条高亮，全部按文本查找后叠加，最多 MAX_ANNOTATION_RANGES 段，
//     避免「常见词高亮」把主线程拖死（与 F-02 同一个道理）。
const ANNOTATION_HIGHLIGHT_PREFIX = 'pb-annotation-'
const ANNOTATION_STYLE_ID = 'pb-annotation-styles'
const MAX_ANNOTATION_RANGES = 500
const MAX_RANGES_PER_HIGHLIGHT = 50

/** #rrggbb / #rgb → rgba(...)，alpha 默认 0.35（保证正文仍可读）。 */
function hexToRgba(hex: string, alpha: number): string {
  const raw = hex.replace('#', '')
  const full = raw.length === 3
    ? raw.split('').map((c) => c + c).join('')
    : raw
  if (full.length !== 6) return hex
  const num = Number.parseInt(full, 16)
  if (Number.isNaN(num)) return hex
  const r = (num >> 16) & 0xff
  const g = (num >> 8) & 0xff
  const b = num & 0xff
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

/** 高亮行的 color → 可用的 ::highlight() background-color。 */
function highlightPaint(color: string | undefined): string {
  const value = (color || '').trim()
  if (!value) return 'rgba(251, 191, 36, 0.35)'
  if (value.startsWith('#')) return hexToRgba(value, 0.35)
  // 已经是 rgba()/命名色（TXT 渲染器存的就是 rgba）→ 原样使用
  return value
}

/** 在 root 里找 needle 的所有出现位置（跨文本节点不支持，与 TXT 渲染器口径一致）。 */
function findTextRanges(root: HTMLElement, needle: string, budget: number): Range[] {
  const out: Range[] = []
  if (!needle) return out
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  let node = walker.nextNode() as Text | null
  while (node && out.length < budget) {
    const data = node.data || ''
    let from = 0
    while (out.length < budget) {
      const at = data.indexOf(needle, from)
      if (at < 0) break
      try {
        const range = document.createRange()
        range.setStart(node, at)
        range.setEnd(node, at + needle.length)
        out.push(range)
      } catch {
        break
      }
      from = at + needle.length
    }
    node = walker.nextNode() as Text | null
  }
  return out
}

export function HtmlRenderer({ book, content, bookId }: HtmlRendererProps) {
  const { t } = useTranslation()
  const [htmlContent, setHtmlContent] = useState('')
  const containerRef = useRef<HTMLDivElement>(null)
  const contentRef = useRef<HTMLDivElement>(null)
  const searchMarksRef = useRef<HTMLElement[]>([])

  const progress = useReaderStore((s) => s.progress)
  const setProgress = useReaderStore((s) => s.setProgress)
  const saveProgress = useReaderStore((s) => s.saveProgress)
  const setTableOfContents = useReaderStore((s) => s.setTableOfContents)
  const navigateTarget = useReaderStore((s) => s.navigateTarget)
  const clearNavigateTarget = useReaderStore((s) => s.clearNavigateTarget)
  const paging = usePaging()
  const seekTarget = useReaderStore((s) => s.seekTarget)
  const clearSeekTarget = useReaderStore((s) => s.clearSeekTarget)
  const searchQuery = useReaderStore((s) => s.searchQuery)
  const searchMatches = useReaderStore((s) => s.searchMatches)
  const currentSearchIndex = useReaderStore((s) => s.currentSearchIndex)
  const setSearchMatches = useReaderStore((s) => s.setSearchMatches)
  // review F-07：正文高亮（EPUB/TXT 各自渲染，这四种格式此前完全没有）
  const highlights = useReaderStore((s) => s.highlights)
  const annotationNamesRef = useRef<string[]>([])

  const fontSize = useSettingsStore((s) => s.fontSize)
  const fontFamily = useSettingsStore((s) => s.fontFamily)
  const lineHeight = useSettingsStore((s) => s.lineHeight)
  const margin = useSettingsStore((s) => s.margin)
  const textAlign = useSettingsStore((s) => s.textAlign)

  useEffect(() => {
    if (!navigateTarget || !containerRef.current) return
    if (navigateTarget.cfi) {
      const el = containerRef.current.querySelector(`#${navigateTarget.cfi}`)
      if (el) el.scrollIntoView({ behavior: 'smooth' })
    } else if (navigateTarget.page) {
      const container = containerRef.current
      const scrollTop = (navigateTarget.page / 100) * container.scrollHeight
      container.scrollTop = scrollTop
    }
    clearNavigateTarget()
  }, [navigateTarget])

  // 统一翻页：注册「按视口滚动」的翻页器。
  useEffect(() => {
    if (!paging) return
    const scrollByPage = (dir: 1 | -1) => {
      const container = containerRef.current
      if (!container) return
      container.scrollBy({ top: dir * container.clientHeight * 0.92, behavior: 'smooth' })
    }
    paging.register({ next: () => scrollByPage(1), prev: () => scrollByPage(-1) })
    return () => paging.register(null)
  }, [paging])

  useEffect(() => {
    if (seekTarget === null || !containerRef.current) return
    const container = containerRef.current
    const scrollTop = (seekTarget / 100) * (container.scrollHeight - container.clientHeight)
    container.scrollTop = scrollTop
    clearSeekTarget()
  }, [seekTarget])

  useEffect(() => {
    const text = new TextDecoder('utf-8').decode(content)

    // DOMPurify strips scripts/event-handlers/javascript: URLs from untrusted
    // book content before it reaches dangerouslySetInnerHTML below. Book files
    // are attacker-controlled input rendered on the app origin (which owns the
    // full electronAPI bridge), so sanitization is mandatory for every format.
    switch (book.format) {
      case 'markdown':
        setHtmlContent(DOMPurify.sanitize(md.render(text)))
        break
      case 'fb2':
        setHtmlContent(DOMPurify.sanitize(parseFb2(text, t)))
        break
      case 'html':
      case 'docx': // PB-008-A：导入时已转成自包含 HTML
        setHtmlContent(DOMPurify.sanitize(text))
        break
      default:
        setHtmlContent(`<pre style="white-space: pre-wrap; font-family: inherit;">${escapeHtml(text)}</pre>`)
    }
  }, [content, book.format, t])

  useEffect(() => {
    if (!containerRef.current) return
    const container = containerRef.current
    let scrollTimer: ReturnType<typeof setTimeout> | null = null

    const handleScroll = () => {
      if (scrollTimer) return
      scrollTimer = setTimeout(() => {
        scrollTimer = null
        const scrollTop = container.scrollTop
        const scrollHeight = container.scrollHeight - container.clientHeight
        const progressPercent = scrollHeight > 0 ? (scrollTop / scrollHeight) * 100 : 0
        setProgress({ progress: progressPercent, scrollPosition: scrollTop })
        saveProgress()
      }, 150)
    }

    container.addEventListener('scroll', handleScroll)

    if (progress.scrollPosition) {
      container.scrollTop = progress.scrollPosition
    }

    return () => {
      container.removeEventListener('scroll', handleScroll)
      if (scrollTimer) clearTimeout(scrollTimer)
    }
  }, [htmlContent])

  useEffect(() => {
    if (!containerRef.current) return
    const headings = containerRef.current.querySelectorAll('h1, h2, h3, h4')
    const toc = Array.from(headings).map((h, i) => ({
      id: `heading-${i}`,
      label: h.textContent || '',
      level: parseInt(h.tagName[1]),
      href: `heading-${i}`,
    }))
    setTableOfContents(toc)

    headings.forEach((h, i) => {
      h.id = `heading-${i}`
    })
  }, [htmlContent])

  // review F-07：把 readerStore.highlights 画到正文上（CSS Custom Highlight API，
  // 不改 DOM）。htmlContent 变了（换书/换内容）或高亮列表变了就重建一次。
  useEffect(() => {
    const root = contentRef.current
    const registry = typeof CSS !== 'undefined' ? (CSS as unknown as { highlights?: Map<string, unknown> }).highlights : undefined
    const HighlightCtor = (window as unknown as { Highlight?: new (...ranges: Range[]) => unknown }).Highlight

    const cleanup = () => {
      if (registry) {
        for (const name of annotationNamesRef.current) {
          try { registry.delete(name) } catch { /* ignore */ }
        }
      }
      annotationNamesRef.current = []
      document.getElementById(ANNOTATION_STYLE_ID)?.remove()
    }

    cleanup()
    if (!root || !registry || typeof HighlightCtor !== 'function' || highlights.length === 0) return cleanup

    // 延迟一个宏任务再算 Range：搜索用的 effect 会在 rAF 里把命中的文本节点换成
    // <mark>（节点被替换后旧 Range 会失效），我们的 Range 必须在它之后重新算。
    let timer = 0
    timer = window.setTimeout(() => {
    const liveRoot = contentRef.current
    if (!liveRoot) return
    const byPaint = new Map<string, Range[]>()
    let budget = MAX_ANNOTATION_RANGES
    for (const hl of highlights) {
      if (budget <= 0) break
      const text = (hl.text || '').trim()
      if (!text) continue
      const ranges = findTextRanges(
        liveRoot,
        text,
        Math.min(budget, MAX_RANGES_PER_HIGHLIGHT),
      )
      if (ranges.length === 0) continue
      budget -= ranges.length
      const paint = highlightPaint(hl.color)
      const bucket = byPaint.get(paint)
      if (bucket) bucket.push(...ranges)
      else byPaint.set(paint, ranges)
    }
    if (byPaint.size === 0) return

    const names: string[] = []
    const rules: string[] = []
    let index = 0
    for (const [paint, ranges] of byPaint) {
      const name = ANNOTATION_HIGHLIGHT_PREFIX + index
      index += 1
      try {
        registry.set(name, new HighlightCtor(...ranges))
        names.push(name)
        rules.push('::highlight(' + name + '){background-color:' + paint + ';color:inherit;}')
      } catch { /* 该组注册失败不影响其它组 */ }
    }
    annotationNamesRef.current = names
    if (rules.length > 0) {
      const style = document.createElement('style')
      style.id = ANNOTATION_STYLE_ID
      style.textContent = rules.join('')
      document.head.appendChild(style)
    }
    }, 0)

    return () => {
      window.clearTimeout(timer)
      cleanup()
    }
    // searchQuery 变化也要重算：搜索高亮会替换文本节点，旧 Range 会失效
  }, [htmlContent, highlights, searchQuery])

  // Search highlighting — apply after DOM is rendered
  useEffect(() => {
    const contentDiv = contentRef.current
    if (!contentDiv) return

    // Remove old marks
    for (const m of searchMarksRef.current) {
      const parent = m.parentNode
      if (parent) {
        parent.replaceChild(document.createTextNode(m.textContent || ''), m)
        parent.normalize()
      }
    }
    searchMarksRef.current = []

    if (!searchQuery || !searchQuery.trim()) {
      if (searchMatches.length > 0) setSearchMatches([])
      return
    }

    // Wait for React to finish rendering
    requestAnimationFrame(() => {
      const marks = highlightTextInDOM(contentDiv, searchQuery.trim())
      searchMarksRef.current = marks
      if (marks.length > 0) {
        setSearchMatches(Array.from({ length: marks.length }, (_, i) => i))
      } else {
        setSearchMatches([])
      }
    })
  }, [htmlContent, searchQuery])

  // Scroll to current search match
  useEffect(() => {
    const marks = searchMarksRef.current
    if (marks.length === 0 || currentSearchIndex < 0 || currentSearchIndex >= marks.length) return
    const mark = marks[currentSearchIndex]
    // Reset all marks to default style
    for (const m of marks) {
      m.style.backgroundColor = 'rgba(251,191,36,0.3)'
      m.style.outline = ''
    }
    // Highlight active
    mark.style.backgroundColor = 'rgba(251,191,36,0.55)'
    mark.style.outline = '2px solid rgba(251,191,36,0.8)'
    mark.style.outlineOffset = '1px'
    mark.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }, [currentSearchIndex])

  return (
    <div
      ref={containerRef}
      className="h-full overflow-auto bg-[var(--reader-bg)] text-[var(--reader-text)]"
      style={{ padding: `0 ${margin}px` }}
    >
      <div
        ref={contentRef}
        className="reader-content"
        style={{
          width: '100%',
          fontSize: `${fontSize}px`,
          fontFamily,
          lineHeight,
          textAlign,
        }}
        dangerouslySetInnerHTML={{ __html: htmlContent }}
      />
    </div>
  )
}

function parseFb2(xml: string, t: TFunction): string {
  const parser = new XMLParser({ ignoreAttributes: false })
  const parsed = parser.parse(xml)
  const body = parsed?.FictionBook?.body

  if (!body) return t('<p>无法解析 FB2 文件</p>')

  let html = ''
  const sections = Array.isArray(body.section) ? body.section : [body.section]

  for (const section of sections) {
    if (section?.title) {
      const titleText = typeof section.title === 'string' ? section.title : section.title['p'] || ''
      html += `<h2>${titleText}</h2>`
    }

    const paragraphs = Array.isArray(section?.p) ? section.p : section?.p ? [section.p] : []
    for (const p of paragraphs) {
      if (typeof p === 'string') {
        html += `<p>${p}</p>`
      } else if (p?.['#text']) {
        html += `<p>${p['#text']}</p>`
      }
    }

    if (section?.epigraph) {
      const epigraph = Array.isArray(section.epigraph) ? section.epigraph : [section.epigraph]
      for (const e of epigraph) {
        const text = typeof e === 'string' ? e : e?.p || ''
        html += `<blockquote>${text}</blockquote>`
      }
    }
  }

  return html || t('<p>空文档</p>')
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;')
}
