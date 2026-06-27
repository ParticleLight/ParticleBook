import { useEffect, useRef, useCallback, useState } from 'react'
import { createPortal } from 'react-dom'
import ePub, { Book, Rendition } from 'epubjs'
import { useReaderStore } from '../../stores/readerStore'
import { useSettingsStore } from '../../stores/settingsStore'
import type { Book as BookType } from '../../stores/libraryStore'

interface EpubRendererProps {
  book: BookType
  content: Uint8Array
  bookId: number
}

const HIGHLIGHT_COLORS = [
  { name: '黄色', value: '#fbbf24' },
  { name: '绿色', value: '#34d399' },
  { name: '蓝色', value: '#60a5fa' },
  { name: '粉色', value: '#f472b6' },
  { name: '紫色', value: '#a78bfa' },
]

export function EpubRenderer({ book, content, bookId }: EpubRendererProps) {
  const viewerRef = useRef<HTMLDivElement>(null)
  const bookRef = useRef<Book | null>(null)
  const renditionRef = useRef<Rendition | null>(null)
  const [isReady, setIsReady] = useState(false)
  const [highlightPopup, setHighlightPopup] = useState<{ x: number; y: number; cfiRange: string; text: string } | null>(null)

  const progress = useReaderStore((s) => s.progress)
  const saveProgress = useReaderStore((s) => s.saveProgress)
  const setProgress = useReaderStore((s) => s.setProgress)
  const setTableOfContents = useReaderStore((s) => s.setTableOfContents)
  const addBookmark = useReaderStore((s) => s.addBookmark)
  const addHighlight = useReaderStore((s) => s.addHighlight)
  const highlights = useReaderStore((s) => s.highlights)
  const bookmarks = useReaderStore((s) => s.bookmarks)
  const navigateTarget = useReaderStore((s) => s.navigateTarget)
  const clearNavigateTarget = useReaderStore((s) => s.clearNavigateTarget)
  const turnPageDelta = useReaderStore((s) => s.turnPageDelta)
  const clearTurnPage = useReaderStore((s) => s.clearTurnPage)
  const seekTarget = useReaderStore((s) => s.seekTarget)
  const clearSeekTarget = useReaderStore((s) => s.clearSeekTarget)
  const searchQuery = useReaderStore((s) => s.searchQuery)
  const currentSearchIndex = useReaderStore((s) => s.currentSearchIndex)
  const setSearchMatches = useReaderStore((s) => s.setSearchMatches)
  const searchRangesRef = useRef<{ href: string; occurrenceIndex: number }[]>([])
  const searchHrefMapRef = useRef<Map<string, { text: string; matchCount: number }>>(new Map())

  const fontSize = useSettingsStore((s) => s.fontSize)
  const fontFamily = useSettingsStore((s) => s.fontFamily)
  const lineHeight = useSettingsStore((s) => s.lineHeight)
  const margin = useSettingsStore((s) => s.margin)
  const textAlign = useSettingsStore((s) => s.textAlign)
  const theme = useSettingsStore((s) => s.theme)
  const spineLengthRef = useRef(0)

  // Handle navigation from sidebar bookmarks/TOC
  useEffect(() => {
    if (!navigateTarget || !renditionRef.current) return
    if (navigateTarget.cfi) {
      renditionRef.current.display(navigateTarget.cfi)
    }
    clearNavigateTarget()
  }, [navigateTarget])

  // Handle page turn from arrows
  useEffect(() => {
    if (!turnPageDelta || !renditionRef.current) return
    if (turnPageDelta > 0) renditionRef.current.next()
    else renditionRef.current.prev()
    clearTurnPage()
  }, [turnPageDelta])

  // Handle seek from progress bar
  useEffect(() => {
    if (seekTarget === null || !renditionRef.current || !bookRef.current) return
    const epubBook = bookRef.current
    const spineLen = spineLengthRef.current
    if (spineLen > 0) {
      const targetIndex = Math.round((seekTarget / 100) * (spineLen - 1))
      const spine = epubBook.spine
      const items = spine.items || spine.spineItems || []
      if (items[targetIndex]) {
        const href = items[targetIndex].href
        renditionRef.current.display(href)
      }
    }
    clearSeekTarget()
  }, [seekTarget])

  useEffect(() => {
    if (!viewerRef.current) return

    const epubBook = ePub(content.buffer as ArrayBuffer)
    bookRef.current = epubBook

    const rendition = epubBook.renderTo(viewerRef.current, {
      width: '100%',
      height: '100%',
      spread: 'none',
      flow: 'paginated',
      allowScriptedContent: true,
    })

    renditionRef.current = rendition

    epubBook.ready.then(() => {
      const toc = epubBook.navigation?.toc || []
      setTableOfContents(toc)

      // Get spine length for progress calculation
      const spine = epubBook.spine
      spineLengthRef.current = spine.items ? spine.items.length : (spine.spineItems ? spine.spineItems.length : 0)

      // Inject CSS Highlight API styles
      const styleId = 'pb-search-styles'
      if (!document.getElementById(styleId)) {
        const style = document.createElement('style')
        style.id = styleId
        style.textContent = '::highlight(pb-search){background-color:rgba(251,191,36,0.3);color:inherit}::highlight(pb-search-active){background-color:rgba(251,191,36,0.55);outline:2px solid rgba(251,191,36,0.8);outline-offset:1px}'
        document.head.appendChild(style)
      }

      // Pre-load full text for search
      extractFullText()

      if (progress.cfi) {
        rendition.display(progress.cfi)
      } else {
        rendition.display()
      }
      setIsReady(true)
    })

    rendition.on('relocated', (location: any) => {
      const cfi = location?.start?.cfi
      const href = location?.start?.href
      // Calculate progress based on spine index
      let progressPercent = 0
      const spineLen = spineLengthRef.current
      if (spineLen > 0 && href) {
        const spine = epubBook.spine
        const items = spine.items || spine.spineItems || []
        // Strip fragment from href for matching
        const cleanHref = href.split('#')[0]
        let currentIndex = -1
        for (let i = 0; i < items.length; i++) {
          const itemHref = (items[i].href || '').split('#')[0]
          const itemCanonical = (items[i].canonical || '').split('#')[0]
          if (itemHref === cleanHref || itemCanonical === cleanHref || itemHref === href || itemCanonical === href) {
            currentIndex = i
            break
          }
        }
        if (currentIndex >= 0) {
          progressPercent = ((currentIndex + 1) / spineLen) * 100
        }
      }
      setProgress({ progress: progressPercent, cfi })
      saveProgress()

      // Re-apply CSS search highlights on current page after page turn
      setTimeout(() => applySearchHighlights(), 200)
    })

    // Text selection for highlights
    rendition.on('selected', (cfiRange: string, contents: any) => {
      const selection = contents.window.getSelection()
      if (!selection || selection.isCollapsed) return
      const text = selection.toString().trim()
      if (!text) return

      const range = selection.getRangeAt(0)
      const rect = range.getBoundingClientRect()
      // Use iframe element's position to convert iframe-local coords to window coords
      const iframe = viewerRef.current?.querySelector('iframe')
      if (!iframe) return
      const iframeBounds = iframe.getBoundingClientRect()
      const x = iframeBounds.left + rect.left + rect.width / 2
      const y = iframeBounds.top + rect.top - 10

      setHighlightPopup({ x, y, cfiRange, text })
    })

    // Fix iframe sandbox, enable text selection, and forward mousemove
    const hookedDocs = new WeakSet<Document>()
    const contentCleanupFns: Array<() => void> = []
    rendition.hooks.content.register((contents: any) => {
      const iframe = viewerRef.current?.querySelector('iframe')
      if (iframe) {
        iframe.sandbox = 'allow-same-origin allow-scripts allow-popups'
      }
      const doc = contents.document
      if (!doc || hookedDocs.has(doc)) return
      hookedDocs.add(doc)

      doc.body.style.userSelect = 'text'
      doc.body.style.webkitUserSelect = 'text'

      // Inject CSS highlight styles into iframe
      if (!doc.getElementById('pb-search-styles')) {
        const st = doc.createElement('style')
        st.id = 'pb-search-styles'
        st.textContent = '::highlight(pb-search){background-color:rgba(251,191,36,0.3);color:inherit}::highlight(pb-search-active){background-color:rgba(251,191,36,0.55);outline:2px solid rgba(251,191,36,0.8);outline-offset:1px}'
        doc.head?.appendChild(st)
      }

      const onMousemove = () => window.dispatchEvent(new MouseEvent('mousemove'))
      const onWheel = (e: WheelEvent) => {
        window.dispatchEvent(new WheelEvent('wheel', {
          deltaY: e.deltaY, deltaX: e.deltaX,
          ctrlKey: e.ctrlKey, shiftKey: e.shiftKey, altKey: e.altKey, bubbles: true,
        }))
      }
      doc.addEventListener('mousemove', onMousemove)
      doc.addEventListener('wheel', onWheel)
      contentCleanupFns.push(() => {
        doc.removeEventListener('mousemove', onMousemove)
        doc.removeEventListener('wheel', onWheel)
      })

      // Fallback: detect selection via mouseup if 'selected' event doesn't fire
      doc.addEventListener('mouseup', () => {
          setTimeout(() => {
            const sel = doc.getSelection()
            if (!sel || sel.isCollapsed) return
            const text = sel.toString().trim()
            if (!text) return

            const range = sel.getRangeAt(0)
            const rect = range.getBoundingClientRect()
            const iframe = viewerRef.current?.querySelector('iframe')
            if (!iframe) return
            const iframeBounds = iframe.getBoundingClientRect()

            let cfi: string | null = null
            try {
              cfi = contents.cfiFromRange(range)
            } catch (e) {}

            if (cfi) {
              const x = iframeBounds.left + rect.left + rect.width / 2
              const y = iframeBounds.top + rect.top - 10
              setHighlightPopup({ x, y, cfiRange: cfi, text })
            }
          }, 10)
        })
    })

    return () => {
      contentCleanupFns.forEach(fn => fn())
      // Remove injected search styles from all iframes
      document.querySelectorAll('iframe').forEach(iframe => {
        try {
          const style = iframe.contentDocument?.getElementById('pb-search-styles')
          style?.remove()
        } catch {}
      })
      epubBook.destroy()
      bookRef.current = null
      renditionRef.current = null
    }
  }, [content])

  // Render existing highlights when highlights list changes
  useEffect(() => {
    const rendition = renditionRef.current
    if (!rendition || !isReady) return

    // Clear all existing highlights first to avoid duplicates
    for (const hl of highlights) {
      if (hl.cfi) {
        try {
          rendition.annotations.remove(hl.cfi, 'highlight')
        } catch {}
      }
    }

    for (const hl of highlights) {
      if (hl.cfi) {
        try {
          rendition.annotations.add('highlight', hl.cfi, {}, undefined, 'epub-highlight', {
            'fill': hl.color || '#fbbf24',
            'fill-opacity': '0.3',
            'mix-blend-mode': 'multiply',
          })
        } catch (e) {
          // ignore invalid CFIs
        }
      }
    }
  }, [highlights, isReady])

  useEffect(() => {
    if (!renditionRef.current) return
    renditionRef.current.themes.fontSize(`${fontSize}px`)
    renditionRef.current.themes.font(fontFamily)
    renditionRef.current.themes.default({
      'body': {
        'line-height': `${lineHeight} !important`,
        'text-align': `${textAlign} !important`,
        'padding': `${margin}px !important`,
      },
      'p': {
        'line-height': `${lineHeight} !important`,
      }
    })
  }, [fontSize, fontFamily, lineHeight, margin, textAlign])

  useEffect(() => {
    if (!renditionRef.current) return
    const themes = renditionRef.current.themes
    switch (theme) {
      case 'dark':
        themes.override('color', '#e2e8f0')
        themes.override('background', '#1a1a1a')
        break
      case 'light':
        themes.override('color', '#1a202c')
        themes.override('background', '#ffffff')
        break
      case 'sepia':
        themes.override('color', '#c4b494')
        themes.override('background', '#1a1510')
        break
    }
  }, [theme])

  // ── Full-book text extraction: count matches per section ──────────────
  const extractFullText = useCallback(async () => {
    const epubBook = bookRef.current
    if (!epubBook) return
    try {
      const spine = epubBook.spine
      const items = spine.items || (spine as any).spineItems || []
      const map = new Map<string, { text: string; matchCount: number }>()
      for (const item of items) {
        const href = item.href
        if (!href) continue
        try {
          const doc = await epubBook.load(href)
          const text = (doc as any)?.body?.textContent || (doc as any)?.documentElement?.textContent || ''
          map.set(href, { text, matchCount: 0 })
        } catch {}
      }
      searchHrefMapRef.current = map
    } catch {}
  }, [])

  // ── Apply CSS Highlight API to current iframe ────────────────────────
  const applySearchHighlights = useCallback(() => {
    const iframe = viewerRef.current?.querySelector('iframe') as HTMLIFrameElement | null
    const doc = iframe?.contentDocument
    const win = iframe?.contentWindow as any
    if (!doc?.body || !win) return
    // Clear existing highlights
    try { win.CSS?.highlights?.delete('pb-search') } catch {}
    try { win.CSS?.highlights?.delete('pb-search-active') } catch {}

    const q = searchQuery?.trim()
    if (!q) return

    const ranges: Range[] = []
    const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT)
    const lowerQ = q.toLowerCase()
    while (walker.nextNode()) {
      const node = walker.currentNode as Text
      const text = node.textContent || ''
      const lowerText = text.toLowerCase()
      let idx = lowerText.indexOf(lowerQ)
      while (idx !== -1) {
        try {
          const range = doc.createRange()
          range.setStart(node, idx)
          range.setEnd(node, idx + q.length)
          ranges.push(range)
        } catch {}
        idx = lowerText.indexOf(lowerQ, idx + q.length)
      }
    }
    if (ranges.length > 0) {
      try {
        const hl = new (win.Highlight)(...ranges)
        win.CSS.highlights.set('pb-search', hl)
      } catch {}
    }
  }, [searchQuery])

  // ── Full-text search across all sections ──────────────────────────────
  useEffect(() => {
    const iframe = viewerRef.current?.querySelector('iframe') as HTMLIFrameElement | null
    const doc = iframe?.contentDocument
    const win = iframe?.contentWindow as any
    try { win?.CSS?.highlights?.delete('pb-search') } catch {}
    try { win?.CSS?.highlights?.delete('pb-search-active') } catch {}

    if (!searchQuery || !searchQuery.trim() || !isReady) {
      searchRangesRef.current = []
      if (searchQuery !== '') setSearchMatches([])
      return
    }

    const q = searchQuery.trim().toLowerCase()
    const map = searchHrefMapRef.current
    const results: { href: string; occurrenceIndex: number }[] = []

    for (const [href, { text }] of map) {
      let occIdx = 0
      const lowerText = text.toLowerCase()
      let idx = lowerText.indexOf(q)
      while (idx !== -1) {
        results.push({ href, occurrenceIndex: occIdx++ })
        idx = lowerText.indexOf(q, idx + q.length)
      }
    }

    searchRangesRef.current = results
    setSearchMatches(Array.from({ length: results.length }, (_, i) => i))

    setTimeout(() => applySearchHighlights(), 300)
  }, [searchQuery, isReady])

  // ── Navigate to search match across sections ──────────────────────────
  const navRef = useRef(false)
  useEffect(() => {
    const results = searchRangesRef.current
    if (results.length === 0 || currentSearchIndex < 0 || currentSearchIndex >= results.length) return
    if (navRef.current) return // prevent re-entrant navigation

    const match = results[currentSearchIndex]
    const rendition = renditionRef.current
    if (!rendition) return

    navRef.current = true
    const q = searchQuery?.trim() || ''

    rendition.display(match.href).then(() => {
      setTimeout(() => {
        const contents = rendition.getContents()
        if (!contents || contents.length === 0) { navRef.current = false; return }
        const content = contents[0]
        const doc = (content as any).document || content
        if (!doc?.body) { applySearchHighlights(); navRef.current = false; return }

        // Find the Nth occurrence within the rendered DOM
        const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT)
        let targetOccurrence = match.occurrenceIndex
        let currentOccurrence = 0
        let foundRange: Range | null = null

        while (walker.nextNode()) {
          const node = walker.currentNode as Text
          const text = node.textContent || ''
          const lowerText = text.toLowerCase()
          let idx = lowerText.indexOf(q.toLowerCase())
          while (idx !== -1) {
            if (currentOccurrence === targetOccurrence) {
              try {
                const range = doc.createRange()
                range.setStart(node, idx)
                range.setEnd(node, idx + q.length)
                foundRange = range
              } catch {}
              break
            }
            currentOccurrence++
            idx = lowerText.indexOf(q.toLowerCase(), idx + q.length)
          }
          if (foundRange) break
        }

        if (foundRange) {
          try {
            const cfi = (content as any).cfiFromRange(foundRange)
            if (cfi) {
              rendition.display(cfi).then(() => {
                setTimeout(() => { applySearchHighlights(); navRef.current = false }, 400)
              })
              return
            }
          } catch {}
        }

        applySearchHighlights()
        navRef.current = false
      }, 600)
    }).catch(() => { navRef.current = false })
  }, [currentSearchIndex])

  const goNext = useCallback(() => renditionRef.current?.next(), [])
  const goPrev = useCallback(() => renditionRef.current?.prev(), [])

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'ArrowLeft') goPrev()
      if (e.key === 'ArrowRight') goNext()
      if (e.key === 'b') {
        const loc = renditionRef.current?.currentLocation() as any
        if (loc?.start?.cfi) {
          const { progress: p, bookmarks: bms } = useReaderStore.getState()
          const nextNum = bms.length + 1
          addBookmark({ book_id: bookId, cfi: loc.start.cfi, progress: p.progress, title: `书签${nextNum}` })
        }
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [bookId, goNext, goPrev, addBookmark])

  const handleClick = useCallback((e: React.MouseEvent) => {
    const x = e.clientX
    const w = window.innerWidth
    if (x < w * 0.3) goPrev()
    else if (x > w * 0.7) goNext()
  }, [goNext, goPrev])

  const handleAddHighlight = useCallback((color: string) => {
    if (!highlightPopup || !renditionRef.current) return
    const { cfiRange, text } = highlightPopup

    // Add visual highlight
    renditionRef.current.annotations.add('highlight', cfiRange, {}, undefined, 'epub-highlight', {
      'fill': color,
      'fill-opacity': '0.3',
      'mix-blend-mode': 'multiply',
    })

    // Save to store
    addHighlight({
      book_id: bookId,
      cfi: cfiRange,
      text,
      color,
    })

    setHighlightPopup(null)
  }, [highlightPopup, bookId, addHighlight])

  return (
    <div className="h-full relative" onClick={handleClick}>
      <div ref={viewerRef} className="h-full" />
      {!isReady && (
        <div className="absolute inset-0 flex items-center justify-center bg-[var(--reader-bg)]">
          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-indigo-500"></div>
        </div>
      )}

      {/* Highlight popup - rendered via portal to escape overflow-hidden */}
      {highlightPopup && createPortal(
        <div
          className="fixed z-[9999] flex items-center gap-2 bg-[var(--reader-sidebar)] border border-[var(--reader-border)] rounded-xl px-3 py-2 shadow-xl"
          style={{ left: highlightPopup.x, top: highlightPopup.y, transform: 'translate(-50%, -100%)' }}
          onClick={(e) => e.stopPropagation()}
        >
          {HIGHLIGHT_COLORS.map((c) => (
            <button
              key={c.value}
              onClick={() => handleAddHighlight(c.value)}
              className="w-6 h-6 rounded-full border-2 border-transparent hover:border-white transition-colors"
              style={{ backgroundColor: c.value }}
              title={c.name}
            />
          ))}
          <button
            onClick={() => setHighlightPopup(null)}
            className="ml-1 text-[var(--reader-text)] opacity-50 hover:opacity-100 text-xs"
          >
            <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>,
        document.body
      )}
    </div>
  )
}
