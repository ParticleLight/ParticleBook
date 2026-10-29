import { memo, useEffect, useMemo, useRef, useState, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { useReaderStore } from '../../stores/readerStore'
import { usePaging } from '../../reader'
import { useSettingsStore } from '../../stores/settingsStore'
import { useAnnotationStore } from '../../stores/annotationStore'
import type { Book } from '../../stores/libraryStore'
import { isTypingTarget } from '../../utils/keyboard'

let savedZoom = 1.0

interface PdfRendererProps { book: Book; content: Uint8Array; bookId: number }
interface PdfTextLayer { page: number; width: number; height: number; chars: Array<{ x: number; y: number; w: number; h: number; c: string }> }

// ── PB-003：PDF 页内标注 ─────────────────────────────────────────────
// 文本层选区 → 归一化矩形（相对页面宽高，缩放/换页/重开都不丢）；高亮/下划线/
// 笔记统一写 annotations bridge（C++ 双写旧表，导出/侧栏直接可见）。
//   * 高亮：type=highlight + color，locator={kind:'pdf',page,rect,quote}
//   * 下划线：同上，tags=['underline']（风格标记，验收要求的 tags style）
//   * 笔记：type='note' + note 正文；渲染成页内小图标，点击可编辑/删除
interface PdfRect { x: number; y: number; w: number; h: number }

interface PdfSelection {
  page: number
  text: string
  rect: PdfRect
  client: { left: number; top: number; bottom: number; width: number; height: number }
}

interface PdfNoteEditor {
  mode: 'create' | 'edit'
  id?: number
  page: number
  rect?: PdfRect
  quote?: string
  value: string
  client?: { left: number; top: number }
}

// review F-05：一页文本层有数千个绝对定位 span。抽成 memo 组件后，拖框选择
// （父组件原来每次 mousemove 都 setState）不会再把这几千个节点重新创建一遍。
const PdfTextLayerView = memo(function PdfTextLayerView({
  pageNum, layer, pageHeight,
}: { pageNum: number; layer: PdfTextLayer | undefined; pageHeight: number }) {
  if (!layer || layer.chars.length === 0) return null
  return (
    <div className="absolute inset-0 select-text" style={{ zIndex: 2 }} data-pdf-text-layer={pageNum}>
      {layer.chars.map((ch, idx) => (
        <span
          key={idx}
          style={{
            position: 'absolute',
            left: `${(ch.x / layer.width) * 100}%`,
            top: `${(ch.y / layer.height) * 100}%`,
            width: `${(ch.w / layer.width) * 100}%`,
            height: `${(ch.h / layer.height) * 100}%`,
            fontSize: `${(ch.h / layer.height) * pageHeight}px`,
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
})

const PDF_HIGHLIGHT_COLORS = ['#ffd54f', '#a5d6a7', '#90caf9', '#f48fb1'] as const

function clampNumber(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max)
}

export function PdfRenderer({ book, content: _content, bookId }: PdfRendererProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [totalPages, setTotalPages] = useState(0)
  const [visiblePage, setVisiblePage] = useState(1)
  const [pageBounds, setPageBounds] = useState<Array<{ width: number; height: number }>>([])
  const [pageImages, setPageImages] = useState<Map<number, string>>(new Map())
  // PB-003：文本层选区 + 笔记编辑浮层
  const [selection, setSelection] = useState<PdfSelection | null>(null)
  const [noteEditor, setNoteEditor] = useState<PdfNoteEditor | null>(null)
  const [noteSaving, setNoteSaving] = useState(false)
  const [textLayers, setTextLayers] = useState<Map<number, PdfTextLayer>>(new Map())
  // 取色板（2026-10-29）：共享 SelectionToolbar 的「高亮」发 pb:openHighlightPalette，
  // PDF 由这里认领 —— 只有 PdfRenderer 知道选区的 page + 归一化 rect（adapter 拿不到），
  // 之前那个请求没人接，于是退回「默认色 + 无 rect 的标注」，画不出来又不收选区，
  // 用户看到的就是菜单闪一下还在。
  const [palette, setPalette] = useState<{ left: number; top: number } | null>(null)
  const selectionRef = useRef<PdfSelection | null>(null)
  selectionRef.current = selection
  const [fitScale, setFitScale] = useState(1)
  const [zoom, _setZoom] = useState(savedZoom)
  const docIdRef = useRef<number | null>(null)
  const renderingPages = useRef<Set<number>>(new Set())
  const zoomTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const visiblePageRef = useRef(1)
  visiblePageRef.current = visiblePage
  const initialScrollDone = useRef(false)
  // review F-03：连点翻页的目标页必须自己递推。旧实现读闭包里的 visiblePage，
  // 而它要等 IntersectionObserver 回调才更新、scrollIntoView 又是同步返回 ——
  // paging 的串行队列对 PDF 等于没有，快速点两下只前进一页。
  const pageTargetRef = useRef(1)
  // 程序发起滚动期间，IO 报上来的中间页不要回头改写目标页（否则仍会丢步）
  const programmaticScrollUntilRef = useRef(0)
  // review F-05：拖框选择的实时几何只放 ref，并由 rAF 直接写内联样式。
  // 旧实现每次 mousemove 都 setState，导致整页列表（含上万字符 span）重渲染。
  const dragStateRef = useRef<{ page: number; x0: number; y0: number; x1: number; y1: number } | null>(null)
  const dragBoxRef = useRef<HTMLDivElement | null>(null)
  const dragRafRef = useRef(0)
  const [dragPage, setDragPage] = useState<number | null>(null)

  const progress = useReaderStore((s) => s.progress)
  const setProgress = useReaderStore((s) => s.setProgress)
  const saveProgress = useReaderStore((s) => s.saveProgress)
  const navigateTarget = useReaderStore((s) => s.navigateTarget)
  const clearNavigateTarget = useReaderStore((s) => s.clearNavigateTarget)
  const paging = usePaging()
  const seekTarget = useReaderStore((s) => s.seekTarget)
  const clearSeekTarget = useReaderStore((s) => s.clearSeekTarget)
  const searchQuery = useReaderStore((s) => s.searchQuery)
  const currentSearchIndex = useReaderStore((s) => s.currentSearchIndex)
  const setSearchMatches = useReaderStore((s) => s.setSearchMatches)
  const theme = useSettingsStore((s) => s.theme)
  const { t } = useTranslation()

  // PB-003：PDF 标注与侧栏共用 annotationStore（写入后侧栏/导出立即一致）。
  const annotations = useAnnotationStore((s) => s.annotations)
  const loadAnnotations = useAnnotationStore((s) => s.load)
  const addAnnotation = useAnnotationStore((s) => s.add)
  const updateAnnotation = useAnnotationStore((s) => s.update)
  const removeAnnotation = useAnnotationStore((s) => s.remove)
  const pdfAnnotations = useMemo(
    () => annotations.filter((a) => a.bookId === bookId && a.locator.kind === 'pdf'
      && typeof a.locator.page === 'number' && a.locator.page >= 1),
    [annotations, bookId],
  )
  useEffect(() => { void loadAnnotations(bookId) }, [bookId, loadAnnotations])

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
    void addAnnotation({
      bookId,
      type: 'highlight',
      locator: { version: 1, kind: 'pdf', page, rect: { x, y, w, h }, quote: '' },
      color: '#ffd54f',
    }).catch((error) => console.error('Failed to add PDF highlight:', error))
  }, [addAnnotation, bookId])

  // 统一的「滚到第 N 页」：记住目标页 + 标记程序滚动窗口（F-03）
  const scrollToPage = useCallback((pageNum: number) => {
    const target = Math.max(1, Math.min(pageNum, totalPages || pageNum))
    pageTargetRef.current = target
    programmaticScrollUntilRef.current = performance.now() + 600
    const el = containerRef.current?.querySelector(`[data-page="${target}"]`)
    if (el) el.scrollIntoView({ block: 'start', behavior: 'smooth' })
  }, [totalPages])

  // 拖框的实时绘制（F-05）：只碰一个元素的样式，不进 React 状态
  const paintDragBox = useCallback(() => {
    dragRafRef.current = 0
    const box = dragBoxRef.current
    const drag = dragStateRef.current
    if (!box || !drag) return
    const left = Math.min(drag.x0, drag.x1) * 100
    const top = Math.min(drag.y0, drag.y1) * 100
    box.style.left = left + '%'
    box.style.top = top + '%'
    box.style.width = Math.abs(drag.x1 - drag.x0) * 100 + '%'
    box.style.height = Math.abs(drag.y1 - drag.y0) * 100 + '%'
  }, [])

  // 兜底：在页面外松开鼠标时页自己的 onMouseUp 不会触发，别留下一个粘住的框。
  // 页面内的 mouseup 先跑完 finishDrag 并把 ref 清空，所以这里只会处理「没接住」的情况。
  useEffect(() => {
    const onWindowMouseUp = () => {
      if (!dragStateRef.current) return
      dragStateRef.current = null
      if (dragRafRef.current) { window.cancelAnimationFrame(dragRafRef.current); dragRafRef.current = 0 }
      setDragPage(null)
    }
    window.addEventListener('mouseup', onWindowMouseUp)
    return () => window.removeEventListener('mouseup', onWindowMouseUp)
  }, [])

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
    if (typeof navigateTarget.page === 'number') scrollToPage(navigateTarget.page)
    clearNavigateTarget()
  }, [navigateTarget, clearNavigateTarget, scrollToPage])

  // 统一翻页：注册「±1 页并滚动到该页」的翻页器（边界自己夹紧，单页 PDF 不会越界）。
  // review F-03：目标页从 pageTargetRef 递推，不依赖 visiblePage —— 这样连点 N 次
  // 就是 N 页，哪怕 IO 还没把可见页更新过来。
  useEffect(() => {
    if (!paging) return
    const goByPage = (dir: 1 | -1) => {
      if (totalPages <= 0) return
      const base = Math.max(1, Math.min(pageTargetRef.current || visiblePageRef.current, totalPages))
      const target = Math.max(1, Math.min(base + dir, totalPages))
      if (target === base) return
      scrollToPage(target)
    }
    paging.register({ next: () => goByPage(1), prev: () => goByPage(-1) })
    return () => paging.register(null)
  }, [paging, totalPages, scrollToPage])

  // 用户自己滚动时，目标页跟随实际可见页；程序滚动期间不覆盖（F-03）
  useEffect(() => {
    if (performance.now() < programmaticScrollUntilRef.current) return
    pageTargetRef.current = visiblePage
  }, [visiblePage])

  useEffect(() => {
    if (seekTarget === null || totalPages === 0 || !containerRef.current) return
    const tp = Math.max(1, Math.round((seekTarget / 100) * totalPages))
    scrollToPage(tp)
    clearSeekTarget()
  }, [seekTarget, totalPages, clearSeekTarget, scrollToPage])

  // Restore to the last-read page once the document is open. Reads the latest
  // store value at restore time (not a stale closure) and clamps to a valid page.
  useEffect(() => {
    if (totalPages === 0 || !containerRef.current) return
    requestAnimationFrame(() => {
      const savedPage = useReaderStore.getState().progress.page
      const targetPage = Math.max(1, Math.min(savedPage || 1, totalPages))
      const el = containerRef.current?.querySelector(`[data-page="${targetPage}"]`)
      if (el) {
        pageTargetRef.current = targetPage
        // 恢复位置要立即到位（不要平滑滚动，否则 IO 会把中间页当成当前页写进进度）
        programmaticScrollUntilRef.current = performance.now() + 600
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
    scrollToPage(targetPage)
  }, [currentSearchIndex, scrollToPage])

  const filterStyle = theme === 'dark' ? 'invert(1) hue-rotate(180deg) brightness(0.9)' : theme === 'sepia' ? 'invert(1) hue-rotate(180deg) sepia(0.4) brightness(0.7)' : 'none'

  // 文本层选区 → 页面归一化矩形（缩放/换页/重开都按比例还原）。
  const captureSelection = useCallback((pageNum: number, pageEl: HTMLElement) => {
    const sel = window.getSelection()
    const text = sel?.toString().replace(/\s+/g, ' ').trim() ?? ''
    if (!sel || sel.isCollapsed || !text || sel.rangeCount === 0) { setSelection(null); return }
    const range = sel.getRangeAt(0)
    if (!pageEl.contains(range.startContainer)) { setSelection(null); return }
    const pageBox = pageEl.getBoundingClientRect()
    const rect = range.getBoundingClientRect()
    if (pageBox.width <= 0 || pageBox.height <= 0) return
    const x = clampNumber((rect.left - pageBox.left) / pageBox.width, 0, 1)
    const y = clampNumber((rect.top - pageBox.top) / pageBox.height, 0, 1)
    const w = clampNumber(rect.width / pageBox.width, 0, 1 - x)
    const h = clampNumber(rect.height / pageBox.height, 0, 1 - y)
    if (w < 0.002 || h < 0.002) { setSelection(null); return }
    setSelection({
      page: pageNum,
      text: text.slice(0, 500),
      rect: { x, y, w, h },
      client: { left: rect.left, top: rect.top, bottom: rect.bottom, width: rect.width, height: rect.height },
    })
  }, [])

  const clearSelection = useCallback(() => {
    setSelection(null)
    try { window.getSelection()?.removeAllRanges() } catch { /* ignore */ }
  }, [])

  const applyPdfHighlight = useCallback(async (sel: PdfSelection, style: 'highlight' | 'underline', color: string) => {
    const locator: PbLocator = { version: 1, kind: 'pdf', page: sel.page, rect: sel.rect, quote: sel.text }
    await addAnnotation({
      bookId,
      type: 'highlight',
      locator,
      color,
      // 下划线用 tags 标记风格（验收：annotation tags style），高亮不加标签
      tags: style === 'underline' ? ['underline'] : [],
    }).catch((error) => console.error('Failed to add PDF highlight:', error))
    clearSelection()
  }, [addAnnotation, bookId, clearSelection])

  const closePalette = useCallback((clearSel: boolean) => {
    setPalette(null)
    useReaderStore.getState().setHighlightPaletteOpen(false)
    if (clearSel) {
      clearSelection()
      useReaderStore.getState().clearSelection()
    }
  }, [clearSelection])

  // 共享工具条点「高亮」→ 认领请求，弹出 PDF 自己的取色板
  useEffect(() => {
    const openPalette = () => {
      const sel = selectionRef.current
      if (!sel) return
      // 同步派发「已接管」：ReaderView 的 handleSelectionHighlight 靠这个事件决定
      // 不再走默认色兜底（它是在 dispatch 返回后同步检查的）。
      window.dispatchEvent(new CustomEvent('pb:highlightPaletteOpened'))
      useReaderStore.getState().setHighlightPaletteOpen(true)
      setPalette({
        left: clampNumber(sel.client.left, 8, Math.max(8, window.innerWidth - 250)),
        top: Math.max(8, sel.client.top - 46),
      })
    }
    window.addEventListener('pb:openHighlightPalette', openPalette as EventListener)
    return () => window.removeEventListener('pb:openHighlightPalette', openPalette as EventListener)
  }, [])

  // 取色板打开期间：点外部 / Esc 关闭（保持已应用的标注，收掉选区）
  useEffect(() => {
    if (!palette) return
    const onDown = (e: PointerEvent) => {
      const target = e.target as Element | null
      if (target && target.closest('[data-pdf-highlight-palette]')) return
      closePalette(true)
    }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') closePalette(true) }
    document.addEventListener('pointerdown', onDown, true)
    window.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('pointerdown', onDown, true)
      window.removeEventListener('keydown', onKey)
    }
  }, [palette, closePalette])

  // 选区没了（翻页 / 滚动 / 手动取消）就收起取色板
  useEffect(() => {
    if (palette && !selection) closePalette(false)
  }, [palette, selection, closePalette])

  const openNoteEditorForSelection = useCallback((sel: PdfSelection) => {
    setNoteEditor({
      mode: 'create',
      page: sel.page,
      rect: sel.rect,
      quote: sel.text,
      value: '',
      client: { left: sel.client.left, top: sel.client.top },
    })
  }, [])

  const openNoteEditorForAnnotation = useCallback((annotation: PbAnnotation) => {
    const rect = annotation.locator.rect
    setNoteEditor({
      mode: 'edit',
      id: annotation.id,
      page: annotation.locator.page ?? 1,
      rect: rect ?? undefined,
      quote: annotation.locator.quote ?? '',
      value: annotation.note ?? '',
    })
  }, [])

  const saveNote = useCallback(async () => {
    const editor = noteEditor
    if (!editor || noteSaving) return
    const value = editor.value.trim()
    if (!value) return
    setNoteSaving(true)
    try {
      if (editor.mode === 'create' && editor.rect) {
        const locator: PbLocator = { version: 1, kind: 'pdf', page: editor.page, rect: editor.rect, quote: editor.quote ?? '' }
        await addAnnotation({ bookId, type: 'note', locator, note: value })
      } else if (editor.id) {
        await updateAnnotation(editor.id, { note: value })
      }
      setNoteEditor(null)
      setSelection(null)
    } catch (error) {
      console.error('Failed to save PDF note:', error)
    } finally {
      setNoteSaving(false)
    }
  }, [addAnnotation, bookId, noteEditor, noteSaving, updateAnnotation])

  const deleteNote = useCallback(async () => {
    const id = noteEditor?.id
    if (!id) return
    await removeAnnotation(id).catch((error) => console.error('Failed to delete PDF note:', error))
    setNoteEditor(null)
  }, [noteEditor, removeAnnotation])

  return (
    <div ref={containerRef} className="h-full overflow-auto bg-[var(--reader-bg)]" onScroll={() => { if (selection) setSelection(null) }}>
      <div className="flex flex-col items-center py-2">
        {Array.from({ length: totalPages }, (_, i) => i + 1).map((pageNum) => {
          const bounds = pageBounds[pageNum - 1]
          const height = bounds ? bounds.height * fitScale : 1000
          const img = pageImages.get(pageNum)
          // 注意：容器必须用 margin 而不是 padding —— 文本层/高亮层是 absolute inset-0，
          // 绝对定位的包含块是 padding box；一旦容器有 padding，文字层就会比 <img> 高出
          // 一个 padding（选中框和可见文字错位，2026-10-29 用户反馈）。
          return (
            <div key={pageNum} data-page={pageNum} data-pdf-page={pageNum} className="relative mb-2 select-none" style={{ contentVisibility: 'auto', containIntrinsicSize: `auto ${height}px` }}
              onMouseDown={(e) => {
                if (e.button !== 0) return
                // 文本层上的按下是"选文字"，不能当成拖拽画框
                const target = e.target as Element
                if (target.closest('[data-pdf-text-layer]')) { dragStateRef.current = null; setDragPage(null); return }
                setSelection(null)
                const r = e.currentTarget.getBoundingClientRect()
                const x = (e.clientX - r.left) / r.width
                const y = (e.clientY - r.top) / r.height
                dragStateRef.current = { page: pageNum, x0: x, y0: y, x1: x, y1: y }
                setDragPage(pageNum)   // 只切一次「哪个页面在画框」，几何交给 rAF
              }}
              onMouseMove={(e) => {
                const drag = dragStateRef.current
                if (!drag || drag.page !== pageNum) return
                const r = e.currentTarget.getBoundingClientRect()
                drag.x1 = (e.clientX - r.left) / r.width
                drag.y1 = (e.clientY - r.top) / r.height
                // review F-05：每帧只改一个 box 的样式，不进 React 状态（原来这里
                // setState 会让整份页列表 + 每页上千个字符 span 全部重渲染）
                if (!dragRafRef.current) dragRafRef.current = window.requestAnimationFrame(paintDragBox)
              }}
              onMouseUp={(e) => {
                const target = e.target as Element
                if (target.closest('[data-pdf-text-layer]')) {
                  // PB-003：文本层选区 → 归一化矩形，浮出高亮/下划线/笔记工具条
                  captureSelection(pageNum, e.currentTarget)
                  return
                }
                const drag = dragStateRef.current
                dragStateRef.current = null
                if (dragRafRef.current) { window.cancelAnimationFrame(dragRafRef.current); dragRafRef.current = 0 }
                setDragPage(null)
                if (!drag || drag.page !== pageNum) return
                if (window.getSelection()?.toString().trim()) return
                const r = e.currentTarget.getBoundingClientRect()
                finishDrag(pageNum, drag.x0, drag.y0, (e.clientX - r.left) / r.width, (e.clientY - r.top) / r.height)
              }}
            >
              {img ? (
                <img src={img} alt={`Page ${pageNum}`} draggable={false} style={{ filter: filterStyle, width: '100%', height: `${height}px`, objectFit: 'contain' }} />
              ) : (
                <div style={{ width: '100%', height: `${height}px`, background: 'var(--reader-bg)', flexShrink: 0 }} />
              )}
              {pdfAnnotations.filter((a) => a.locator.page === pageNum && a.locator.rect).map((a) => {
                const rect = a.locator.rect!
                const color = a.color || '#ffd54f'
                const box = {
                  left: rect.x * 100 + '%',
                  top: rect.y * 100 + '%',
                  width: rect.w * 100 + '%',
                  height: rect.h * 100 + '%',
                }
                if (a.type === 'note') {
                  return (
                    <button
                      key={a.id}
                      type="button"
                      data-pdf-note={a.id}
                      className="absolute z-[4] w-5 h-5 rounded-full shadow flex items-center justify-center"
                      style={{ ...box, left: rect.x * 100 + '%', top: rect.y * 100 + '%', width: 20, height: 20, background: 'rgba(0,0,0,0.62)', color: '#fff', border: '1px solid rgba(255,255,255,0.35)' }}
                      onClick={(ev) => { ev.stopPropagation(); openNoteEditorForAnnotation(a) }}
                      title={a.note || t('笔记')}
                      aria-label={t('笔记')}
                    >
                      <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                      </svg>
                    </button>
                  )
                }
                const underline = Array.isArray(a.tags) && a.tags.includes('underline')
                return (
                  <div
                    key={a.id}
                    data-pdf-annotation={a.id}
                    data-pdf-style={underline ? 'underline' : 'highlight'}
                    className="absolute group/ann"
                    style={{
                      ...box,
                      zIndex: 3,
                      pointerEvents: 'none',
                      borderRadius: 2,
                      ...(underline
                        ? { borderBottom: '2px solid ' + color }
                        : { background: color + '59', border: '1px solid ' + color }),
                    }}
                    title={a.locator.quote || ''}
                  >
                    <button
                      type="button"
                      data-pdf-delete={a.id}
                      className="absolute -top-2 -right-2 w-4 h-4 rounded-full text-[10px] bg-black/60 text-white opacity-0 group-hover/ann:opacity-100"
                      style={{ pointerEvents: 'auto' }}
                      onClick={(ev) => { ev.stopPropagation(); void removeAnnotation(a.id).catch((error) => console.error('Failed to delete PDF annotation:', error)) }}
                      aria-label={t('删除')}
                    >×</button>
                  </div>
                )
              })}
              <PdfTextLayerView pageNum={pageNum} layer={textLayers.get(pageNum)} pageHeight={height} />
              {dragPage === pageNum && (
                <div
                  ref={dragBoxRef}
                  className="absolute pointer-events-none rounded-sm"
                  style={{ left: 0, top: 0, width: 0, height: 0, background: 'rgba(96,205,255,0.35)', border: '1px solid rgba(96,205,255,0.9)' }}
                />
              )}
            </div>
          )
        })}
      </div>
      {/* 页码胶囊：统一走 .reader-overlay-chip（暗色主题下 inverse-surface 是白斑） */}
      <div
        className="reader-overlay-chip fixed bottom-20 left-1/2 -translate-x-1/2 backdrop-blur-sm text-sm px-4 py-2 rounded-full z-10 pointer-events-none"
      >
        {visiblePage} / {totalPages}
      </div>

      {/* 取色板（2026-10-29）：由共享工具条的「高亮」触发；颜色 / 下划线 / 笔记都在这里 */}
      {palette && selection && !noteEditor && (
        <div
          data-pdf-highlight-palette
          className="fixed z-[58] flex items-center gap-1.5 px-2 py-1.5 rounded-lg shadow-xl border"
          style={{
            left: palette.left,
            top: palette.top,
            background: 'var(--reader-sidebar)',
            borderColor: 'var(--reader-border)',
            color: 'var(--reader-text)',
          }}
          onMouseDown={(e) => { e.preventDefault(); e.stopPropagation() }}
          onClick={(e) => e.stopPropagation()}
        >
          {PDF_HIGHLIGHT_COLORS.map((color) => (
            <button
              key={color}
              type="button"
              data-pdf-highlight={color}
              className="w-4 h-4 rounded-full border border-white/50 transition-transform hover:scale-110"
              style={{ background: color }}
              title={t('高亮')}
              aria-label={t('高亮')}
              onClick={() => {
                const sel = selection
                if (!sel) return
                void applyPdfHighlight(sel, 'highlight', color)
                closePalette(true)
              }}
            />
          ))}
          <span className="w-px h-4 mx-0.5" style={{ background: 'var(--reader-border)' }} />
          <button
            type="button"
            data-pdf-underline
            className="px-1.5 py-0.5 rounded text-xs underline decoration-2"
            style={{ color: 'var(--reader-text)' }}
            title={t('下划线')}
            aria-label={t('下划线')}
            onClick={() => {
              const sel = selection
              if (!sel) return
              void applyPdfHighlight(sel, 'underline', '#f59e0b')
              closePalette(true)
            }}
          >
            U
          </button>
          <button
            type="button"
            data-pdf-add-note
            className="px-1.5 py-0.5 rounded text-xs flex items-center gap-1"
            style={{ color: 'var(--reader-text)' }}
            title={t('笔记')}
            aria-label={t('笔记')}
            onClick={() => {
              const sel = selection
              if (!sel) return
              openNoteEditorForSelection(sel)
              closePalette(false)
            }}
          >
            <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
            </svg>
            {t('笔记')}
          </button>
        </div>
      )}

      {/* PB-003：页内笔记查看/编辑/删除 */}
      {noteEditor && (
        <div className="fixed inset-0 z-[70] flex items-center justify-center" data-pdf-note-editor
          onClick={() => setNoteEditor(null)}>
          <div className="m3-dialog-backdrop absolute inset-0" />
          <div
            className="m3-dialog relative p-4 !w-[420px] max-w-[92vw]"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between mb-2">
              <h3 className="m3-title-small">{noteEditor.mode === 'create' ? t('添加笔记') : t('编辑笔记')}</h3>
              <span className="text-xs opacity-60">{t('第 {{page}} 页', { page: noteEditor.page })}</span>
            </div>
            {noteEditor.quote && (
              <p className="text-xs opacity-60 mb-2 line-clamp-3 break-words">{noteEditor.quote}</p>
            )}
            <textarea
              value={noteEditor.value}
              onChange={(e) => setNoteEditor((prev) => (prev ? { ...prev, value: e.target.value } : prev))}
              rows={4}
              placeholder={t('笔记内容')}
              className="m3-text-field w-full"
              data-pdf-note-input
              autoFocus
            />
            <div className="flex items-center justify-between mt-3">
              {noteEditor.mode === 'edit' ? (
                <button
                  type="button"
                  className="m3-text-button is-compact"
                  style={{ color: 'var(--md-sys-color-error)' }}
                  data-pdf-note-delete
                  onClick={() => { void deleteNote() }}
                >
                  {t('删除')}
                </button>
              ) : <span />}
              <div className="flex items-center gap-2">
                <button type="button" className="m3-outlined-button is-compact"
                  onClick={() => setNoteEditor(null)}>{t('取消')}</button>
                <button type="button" className="m3-filled-button is-compact"
                  disabled={!noteEditor.value.trim() || noteSaving}
                  data-pdf-note-save
                  onClick={() => { void saveNote() }}>{t('保存')}</button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}