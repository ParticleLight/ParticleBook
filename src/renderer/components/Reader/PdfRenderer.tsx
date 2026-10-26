import { useEffect, useRef, useState, useCallback } from 'react'
import { useReaderStore } from '../../stores/readerStore'
import { useSettingsStore } from '../../stores/settingsStore'
import type { Book } from '../../stores/libraryStore'
import { isTypingTarget } from '../../utils/keyboard'

let savedZoom = 1.0

interface PdfRendererProps { book: Book; content: Uint8Array; bookId: number }
interface PdfTextLayer { page: number; width: number; height: number; chars: Array<{ x: number; y: number; w: number; h: number; c: string }> }

export function PdfRenderer({ book, content: _content, bookId }: PdfRendererProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [totalPages, setTotalPages] = useState(0)
  const [visiblePage, setVisiblePage] = useState(1)
  const [pageBounds, setPageBounds] = useState<Array<{ width: number; height: number }>>([])
  const [pageImages, setPageImages] = useState<Map<number, string>>(new Map())
  const [pdfAnnotations, setPdfAnnotations] = useState<PbAnnotation[]>([])
  const [dragRect, setDragRect] = useState<{ page: number; x0: number; y0: number; x1: number; y1: number } | null>(null)
  const [textLayers, setTextLayers] = useState<Map<number, PdfTextLayer>>(new Map())
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

  const loadPdfAnnotations = useCallback(() => {
    window.electronAPI.getAnnotations(bookId)
      .then((rows) => setPdfAnnotations(rows.filter((a) => a.locator?.kind === 'pdf' && a.locator.page)))
      .catch(() => {})
  }, [bookId])

  useEffect(() => { loadPdfAnnotations() }, [loadPdfAnnotations])

  // PB-003: fetch selectable character boxes for current page +/- 1.
  useEffect(() => {
    const docId = docIdRef.current
    if (!docId || totalPages <= 0) return
    const pages = [visiblePage - 1, visiblePage, visiblePage + 1].filter((p) => p >= 1 && p <= totalPages)
    for (const pageNum of pages) {
      if (textLayers.has(pageNum)) continue
      window.electronAPI.pdfTextLayer(docId, pageNum - 1)
        .then((layer) => {
          if (!layer) return
          setTextLayers((prev) => { if (prev.has(pageNum)) return prev; const next = new Map(prev); next.set(pageNum, layer); return next })
        })
        .catch(() => {})
    }
  }, [visiblePage, totalPages, textLayers])

  const finishDrag = useCallback((page: number, x0: number, y0: number, x1: number, y1: number) => {
    const x = Math.max(0, Math.min(x0, x1))
    const y = Math.max(0, Math.min(y0, y1))
    const w = Math.min(1 - x, Math.abs(x1 - x0))
    const h = Math.min(1 - y, Math.abs(y1 - y0))
    if (w < 0.01 || h < 0.01) return
    window.electronAPI.addAnnotation({
      bookId,
      type: 'highlight',
      locator: { version: 1, kind: 'pdf', page, rect: { x, y, w, h }, quote: '' },
      color: '#ffd54f',
    }).then(() => loadPdfAnnotations()).catch(() => {})
  }, [bookId, loadPdfAnnotations])

  const pdfPageMatchesRef = useRef<number[]>([])

  // Open PDF
  useEffect(() => {
    let cancelled = false
    ;(async () => {
      try {
        const info = await window.electronAPI.pdfOpen(book.file_path)
        if (!info) {
          console.error('PDF open returned null for', book.file_path)
          return
        }
        // 打开是异步的（大文件要走 mutool 渲染，界面此时显示 0 / 0）。若用户在这期间
        // 就点了返回，cleanup 已经跑过且当时 docIdRef 还是 null，C++ 侧文档与临时 PNG
        // 就再也没人关 —— 所以这里必须自己判断"已卸载"并立刻关掉。
        if (cancelled) { window.electronAPI.pdfClose(info.id); return }
        docIdRef.current = info.id
        setTotalPages(info.pageCount)
        setPageBounds(info.pageBounds)
      } catch (e) {
        console.error('PDF open failed:', e)
      }
    })()
    return () => {
      cancelled = true
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

  // Lazy rendering + exact current-page tracking via IntersectionObserver.
  // The most-visible page (highest intersectionRatio) is the current page. This
  // avoids the +1 drift (a neighbouring page also intersecting) and the
  // offsetTop-in-lazy-render pitfall (unrendered pages can report a bogus 0).
  useEffect(() => {
    const el = containerRef.current
    if (!el || totalPages === 0) return
    const observer = new IntersectionObserver((entries) => {
      let best = 0
      let bestRatio = -1
      for (const entry of entries) {
        if (!entry.isIntersecting) continue
        const pageNum = Number(entry.target.getAttribute('data-page'))
        if (!pageNum) continue
        // lazy-render current ±1
        renderPage(pageNum)
        if (pageNum > 1) renderPage(pageNum - 1)
        if (pageNum < totalPages) renderPage(pageNum + 1)
        // track the most visible page for progress
        if (entry.intersectionRatio > bestRatio) {
          bestRatio = entry.intersectionRatio
          best = pageNum
        }
      }
      if (best && best !== visiblePageRef.current) {
        visiblePageRef.current = best
        setVisiblePage(best)
        setProgress({ progress: (best / totalPages) * 100, page: best })
        if (initialScrollDone.current) saveProgress()
      }
    }, { root: el, threshold: [0, 0.1, 0.25, 0.5, 0.75, 1] })
    const els = el.querySelectorAll('[data-page]')
    els.forEach((n) => observer.observe(n))
    return () => observer.disconnect()
  }, [totalPages, renderPage, setProgress, saveProgress, setVisiblePage])

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

  // Restore to the last-read page once the document is open. Reads the latest
  // store value at restore time (not a stale closure) and clamps to a valid page.
  useEffect(() => {
    if (totalPages === 0 || !containerRef.current) return
    requestAnimationFrame(() => {
      const savedPage = useReaderStore.getState().progress.page
      const targetPage = Math.max(1, Math.min(savedPage || 1, totalPages))
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
      // 同漫画：输入框里打字时不能抢键，否则搜索框打不出连字符还会触发缩放
      if (isTypingTarget(e)) return
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
            <div key={pageNum} data-page={pageNum} className="relative py-2 select-none" style={{ contentVisibility: 'auto', containIntrinsicSize: `auto ${height}px` }}
              onMouseDown={(e) => {
                if (e.button !== 0) return
                const r = e.currentTarget.getBoundingClientRect()
                setDragRect({ page: pageNum, x0: (e.clientX - r.left) / r.width, y0: (e.clientY - r.top) / r.height, x1: (e.clientX - r.left) / r.width, y1: (e.clientY - r.top) / r.height })
              }}
              onMouseMove={(e) => {
                if (!dragRect || dragRect.page !== pageNum) return
                const r = e.currentTarget.getBoundingClientRect()
                setDragRect({ ...dragRect, x1: (e.clientX - r.left) / r.width, y1: (e.clientY - r.top) / r.height })
              }}
              onMouseUp={(e) => {
                if (!dragRect || dragRect.page !== pageNum) return
                if (window.getSelection()?.toString().trim()) { setDragRect(null); return }
                const r = e.currentTarget.getBoundingClientRect()
                finishDrag(pageNum, dragRect.x0, dragRect.y0, (e.clientX - r.left) / r.width, (e.clientY - r.top) / r.height)
                setDragRect(null)
              }}
            >
              {img ? (
                <img src={img} alt={`Page ${pageNum}`} draggable={false} style={{ filter: filterStyle, width: '100%', height: `${height}px`, objectFit: 'contain' }} />
              ) : (
                <div style={{ width: '100%', height: `${height}px`, background: 'var(--reader-bg)', flexShrink: 0 }} />
              )}
              {pdfAnnotations.filter((a) => a.locator.page === pageNum && a.locator.rect).map((a) => {
                const rect = a.locator.rect!
                return (
                  <div key={a.id}
                    className="absolute rounded-sm"
                    style={{ left: `${rect.x * 100}%`, top: `${rect.y * 100}%`, width: `${rect.w * 100}%`, height: `${rect.h * 100}%`, background: 'rgba(255,213,79,0.35)', border: '1px solid rgba(255,213,79,0.8)' }}
                    onClick={(ev) => ev.stopPropagation()}
                  >
                    <button
                      className="absolute -top-2 -right-2 w-4 h-4 rounded-full text-[10px] bg-black/60 text-white"
                      onClick={(ev) => { ev.stopPropagation(); window.electronAPI.deleteAnnotation(a.id).then(loadPdfAnnotations).catch(() => {}) }}
                      aria-label="删除标注"
                    >×</button>
                  </div>
                )
              })}
              {(() => {
                const layer = textLayers.get(pageNum)
                if (!layer || layer.chars.length === 0) return null
                return (
                  <div className="absolute inset-0 select-text" style={{ zIndex: 2 }} data-pdf-text-layer={pageNum}>
                    {layer.chars.map((ch, idx) => (
                      <span key={idx}
                        style={{
                          position: 'absolute',
                          left: `${(ch.x / layer.width) * 100}%`,
                          top: `${(ch.y / layer.height) * 100}%`,
                          width: `${(ch.w / layer.width) * 100}%`,
                          height: `${(ch.h / layer.height) * 100}%`,
                          fontSize: `${(ch.h / layer.height) * height}px`,
                          lineHeight: 1,
                          color: 'transparent',
                          whiteSpace: 'pre',
                          userSelect: 'text',
                          cursor: 'text',
                        }}
                      >{ch.c}</span>
                    ))}
                  </div>
                )
              })()}
              {dragRect && dragRect.page === pageNum && (
                <div className="absolute pointer-events-none rounded-sm" style={{ left: `${Math.min(dragRect.x0, dragRect.x1) * 100}%`, top: `${Math.min(dragRect.y0, dragRect.y1) * 100}%`, width: `${Math.abs(dragRect.x1 - dragRect.x0) * 100}%`, height: `${Math.abs(dragRect.y1 - dragRect.y0) * 100}%`, background: 'rgba(96,205,255,0.35)', border: '1px solid rgba(96,205,255,0.9)' }} />
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
