import { useEffect, useRef, useState, useCallback } from 'react'
import { useReaderStore } from '../../stores/readerStore'
import { useSettingsStore } from '../../stores/settingsStore'
import type { Book } from '../../stores/libraryStore'

let savedZoom = 1.0

interface PdfRendererProps { book: Book; content: Uint8Array; bookId: number }

export function PdfRenderer({ book, content: _content, bookId }: PdfRendererProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [totalPages, setTotalPages] = useState(0)
  const [visiblePage, setVisiblePage] = useState(1)
  const [pageBounds, setPageBounds] = useState<Array<{ width: number; height: number }>>([])
  const [pageImages, setPageImages] = useState<Map<number, string>>(new Map())
  const [fitScale, setFitScale] = useState(1)
  const [zoom, _setZoom] = useState(savedZoom)
  const docIdRef = useRef<number | null>(null)
  const renderingPages = useRef<Set<number>>(new Set())
  const zoomTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const visiblePageRef = useRef(1)
  visiblePageRef.current = visiblePage
  const initialScrollDone = useRef(false)

  const progress = useReaderStore((s) => s.progress)
  const setProgress = useReaderStore((s) => s.setProgress)
  const saveProgress = useReaderStore((s) => s.saveProgress)
  const navigateTarget = useReaderStore((s) => s.navigateTarget)
  const clearNavigateTarget = useReaderStore((s) => s.clearNavigateTarget)
  const turnPageDelta = useReaderStore((s) => s.turnPageDelta)
  const clearTurnPage = useReaderStore((s) => s.clearTurnPage)
  const seekTarget = useReaderStore((s) => s.seekTarget)
  const clearSeekTarget = useReaderStore((s) => s.clearSeekTarget)
  const searchQuery = useReaderStore((s) => s.searchQuery)
  const currentSearchIndex = useReaderStore((s) => s.currentSearchIndex)
  const setSearchMatches = useReaderStore((s) => s.setSearchMatches)
  const theme = useSettingsStore((s) => s.theme)

  const pdfPageMatchesRef = useRef<number[]>([])

  // Open PDF
  useEffect(() => {
    ;(async () => {
      try {
        const info = await window.electronAPI.pdfOpen(book.file_path)
        docIdRef.current = info.id
        setTotalPages(info.pageCount)
        setPageBounds(info.pageBounds)
      } catch (e) {
        console.error('PDF open failed:', e)
      }
    })()
    return () => {
      if (docIdRef.current != null) {
        window.electronAPI.pdfClose(docIdRef.current)
        docIdRef.current = null
      }
    }
  }, [book.file_path])

  const setZoom = useCallback((z: number | ((prev: number) => number)) => {
    const next = typeof z === 'function' ? z(savedZoom) : z
    savedZoom = next
    _setZoom(next)
    if (zoomTimerRef.current) clearTimeout(zoomTimerRef.current)
    zoomTimerRef.current = setTimeout(() => setPageImages(new Map()), 300)
  }, [])

  // Compute fit scale
  useEffect(() => {
    const el = containerRef.current
    if (!el || pageBounds.length === 0) return
    const dim = pageBounds[0]
    const compute = () => {
      const w = el.clientWidth
      if (w > 0) setFitScale(w / dim.width * 0.85 * savedZoom)
    }
    compute()
    const ro = new ResizeObserver(compute)
    ro.observe(el)
    return () => ro.disconnect()
  }, [pageBounds, zoom])

  // Render page via mutool → PNG url
  const renderPage = useCallback(async (pageNum: number) => {
    const docId = docIdRef.current
    if (docId == null || renderingPages.current.has(pageNum)) return
    if (pageImages.has(pageNum)) return
    if (pageBounds.length === 0) return

    renderingPages.current.add(pageNum)
    try {
      const bounds = pageBounds[pageNum - 1]
      const dpr = Math.min(window.devicePixelRatio || 1, 2)
      const w = Math.round(bounds.width * fitScale * dpr)
      const h = Math.round(bounds.height * fitScale * dpr)
      const url = await window.electronAPI.pdfRenderPage(docId, pageNum - 1, w, h)
      if (url) {
        setPageImages((prev) => { const n = new Map(prev); n.set(pageNum, url); return n })
      }
    } catch (e) {
      console.error('Page render failed:', pageNum, e)
    } finally {
      renderingPages.current.delete(pageNum)
    }
  }, [pageBounds, fitScale, pageImages])

  // IntersectionObserver for lazy rendering
  useEffect(() => {
    if (!containerRef.current || totalPages === 0) return
    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          const pageNum = Number(entry.target.getAttribute('data-page'))
          if (!pageNum) continue
          setVisiblePage((prev) => {
            if (prev !== pageNum) {
              setProgress({ progress: (pageNum / totalPages) * 100, page: pageNum })
              if (initialScrollDone.current) saveProgress()
            }
            return pageNum
          })
          renderPage(pageNum)
          if (pageNum > 1) renderPage(pageNum - 1)
          if (pageNum < totalPages) renderPage(pageNum + 1)
        }
      }
    }, { root: containerRef.current, threshold: 0.1 })
    const els = containerRef.current.querySelectorAll('[data-page]')
    els.forEach((el) => observer.observe(el))
    return () => observer.disconnect()
  }, [totalPages, renderPage, setProgress])

  // Navigation
  useEffect(() => {
    if (!navigateTarget || !containerRef.current) return
    const el = containerRef.current.querySelector(`[data-page="${navigateTarget.page}"]`)
    if (el) el.scrollIntoView({ behavior: 'smooth' })
    clearNavigateTarget()
  }, [navigateTarget, clearNavigateTarget])

  useEffect(() => {
    if (turnPageDelta === null || !containerRef.current) return
    const tp = Math.max(1, Math.min(visiblePage + turnPageDelta, totalPages))
    const el = containerRef.current.querySelector(`[data-page="${tp}"]`)
    if (el) el.scrollIntoView({ behavior: 'smooth' })
    clearTurnPage()
  }, [turnPageDelta, visiblePage, totalPages, clearTurnPage])

  useEffect(() => {
    if (seekTarget === null || totalPages === 0 || !containerRef.current) return
    const tp = Math.max(1, Math.round((seekTarget / 100) * totalPages))
    const el = containerRef.current.querySelector(`[data-page="${tp}"]`)
    if (el) el.scrollIntoView({ behavior: 'smooth' })
    clearSeekTarget()
  }, [seekTarget, totalPages, clearSeekTarget])

  // Restore to the last-read page once the document is open. Must NOT depend on
  // progress.page: the IntersectionObserver below updates progress.page while
  // scrolling, which would re-trigger this scrollIntoView and fight the user's
  // scroll — for pages shorter than the viewport this loops, auto-scrolling all
  // the way to the bottom.
  useEffect(() => {
    if (totalPages === 0 || !containerRef.current) return
    requestAnimationFrame(() => {
      const targetPage = progress.page || 1
      const el = containerRef.current?.querySelector(`[data-page="${targetPage}"]`)
      if (el) {
        el.scrollIntoView({ block: 'start' })
        setTimeout(() => { initialScrollDone.current = true }, 500)
      }
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [totalPages])

  // Keyboard zoom
  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      if (e.key === '+' || e.key === '=') { e.preventDefault(); setZoom((s: number) => Math.min(s + 0.25, 4)) }
      if (e.key === '-') { e.preventDefault(); setZoom((s: number) => Math.max(s - 0.25, 0.5)) }
    }
    window.addEventListener('keydown', h); return () => window.removeEventListener('keydown', h)
  }, [setZoom])

  // Ctrl+wheel zoom
  useEffect(() => {
    const el = containerRef.current; if (!el) return
    const h = (e: WheelEvent) => { if (e.ctrlKey) { e.preventDefault(); setZoom((s: number) => Math.max(0.5, Math.min(4, s + (e.deltaY > 0 ? -0.1 : 0.1)))) } }
    el.addEventListener('wheel', h, { passive: false }); return () => el.removeEventListener('wheel', h)
  }, [setZoom])

  // PDF text search
  useEffect(() => {
    if (!searchQuery || !searchQuery.trim() || docIdRef.current == null) {
      pdfPageMatchesRef.current = []
      return
    }
    const q = searchQuery.trim().toLowerCase()
    ;(async () => {
      try {
        const result = await window.electronAPI.pdfExtractText(docIdRef.current!)
        if (!result?.pages) return
        const matches: number[] = []
        for (const p of result.pages) {
          if (p.text && p.text.toLowerCase().includes(q)) {
            matches.push(p.pageNum)
          }
        }
        pdfPageMatchesRef.current = matches
        setSearchMatches(matches)
      } catch {}
    })()
  }, [searchQuery])

  // Navigate to search match
  useEffect(() => {
    const matches = pdfPageMatchesRef.current
    if (matches.length === 0 || currentSearchIndex < 0 || currentSearchIndex >= matches.length) return
    const targetPage = matches[currentSearchIndex] + 1
    const el = containerRef.current?.querySelector(`[data-page="${targetPage}"]`)
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
  }, [currentSearchIndex])

  const filterStyle = theme === 'dark' ? 'invert(1) hue-rotate(180deg) brightness(0.9)' : theme === 'sepia' ? 'invert(1) hue-rotate(180deg) sepia(0.4) brightness(0.7)' : 'none'

  return (
    <div ref={containerRef} className="h-full overflow-auto bg-[var(--reader-bg)]">
      <div className="flex flex-col items-center py-2">
        {Array.from({ length: totalPages }, (_, i) => i + 1).map((pageNum) => {
          const bounds = pageBounds[pageNum - 1]
          const height = bounds ? bounds.height * fitScale : 1000
          const img = pageImages.get(pageNum)
          return (
            <div key={pageNum} data-page={pageNum} className="relative py-2" style={{ contentVisibility: 'auto', containIntrinsicSize: `auto ${height}px` }}>
              {img ? (
                <img src={img} alt={`Page ${pageNum}`} style={{ filter: filterStyle, width: '100%', height: `${height}px`, objectFit: 'contain' }} />
              ) : (
                <div style={{ width: '100%', height: `${height}px`, background: 'var(--reader-bg)', flexShrink: 0 }} />
              )}
            </div>
          )
        })}
      </div>
      <div className="fixed bottom-20 left-1/2 -translate-x-1/2 bg-black/60 backdrop-blur-sm text-white text-sm px-4 py-2 rounded-full z-10 pointer-events-none">
        {visiblePage} / {totalPages}
      </div>
    </div>
  )
}
