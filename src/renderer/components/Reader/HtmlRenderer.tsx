import { useEffect, useState, useRef } from 'react'
import MarkdownIt from 'markdown-it'
import { XMLParser } from 'fast-xml-parser'
import { useReaderStore } from '../../stores/readerStore'
import { useSettingsStore } from '../../stores/settingsStore'
import { highlightTextInDOM } from '../../utils/domSearch'
import type { Book } from '../../stores/libraryStore'

interface HtmlRendererProps {
  book: Book
  content: Uint8Array
  bookId: number
}

const md = new MarkdownIt({ html: false, linkify: true, typographer: true })

export function HtmlRenderer({ book, content, bookId }: HtmlRendererProps) {
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
  const turnPageDelta = useReaderStore((s) => s.turnPageDelta)
  const clearTurnPage = useReaderStore((s) => s.clearTurnPage)
  const seekTarget = useReaderStore((s) => s.seekTarget)
  const clearSeekTarget = useReaderStore((s) => s.clearSeekTarget)
  const searchQuery = useReaderStore((s) => s.searchQuery)
  const searchMatches = useReaderStore((s) => s.searchMatches)
  const currentSearchIndex = useReaderStore((s) => s.currentSearchIndex)
  const setSearchMatches = useReaderStore((s) => s.setSearchMatches)

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

  useEffect(() => {
    if (turnPageDelta === null || !containerRef.current) return
    const container = containerRef.current
    container.scrollBy({ top: turnPageDelta > 0 ? container.clientHeight : -container.clientHeight, behavior: 'smooth' })
    clearTurnPage()
  }, [turnPageDelta])

  useEffect(() => {
    if (seekTarget === null || !containerRef.current) return
    const container = containerRef.current
    const scrollTop = (seekTarget / 100) * (container.scrollHeight - container.clientHeight)
    container.scrollTop = scrollTop
    clearSeekTarget()
  }, [seekTarget])

  useEffect(() => {
    const text = new TextDecoder('utf-8').decode(content)

    switch (book.format) {
      case 'markdown':
        setHtmlContent(md.render(text))
        break
      case 'fb2':
        setHtmlContent(parseFb2(text))
        break
      case 'html':
        setHtmlContent(text)
        break
      default:
        setHtmlContent(`<pre style="white-space: pre-wrap; font-family: inherit;">${escapeHtml(text)}</pre>`)
    }
  }, [content, book.format])

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

function parseFb2(xml: string): string {
  const parser = new XMLParser({ ignoreAttributes: false })
  const parsed = parser.parse(xml)
  const body = parsed?.FictionBook?.body

  if (!body) return '<p>无法解析 FB2 文件</p>'

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

  return html || '<p>空文档</p>'
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;')
}
