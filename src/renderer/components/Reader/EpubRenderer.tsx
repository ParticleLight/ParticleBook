import { useEffect, useRef, useCallback, useState, useMemo } from 'react'
import { createPortal } from 'react-dom'
import ePub, { Book, Rendition } from 'epubjs'
import { useTranslation } from 'react-i18next'
import { useReaderStore } from '../../stores/readerStore'
import { useSettingsStore } from '../../stores/settingsStore'
import type { Book as BookType } from '../../stores/libraryStore'
import { VERTICAL_STYLE_ID, epubSpreadValue, setVerticalStyle, spineEntries } from './EpubRendererTheme'
import { useEpubSearch } from './EpubSearch'
import { usePaging } from '../../reader'

interface EpubRendererProps {
  book: BookType
  content: Uint8Array
  bookId: number
  // PB-009-A：分栏与竖排（由 ReaderView 从 settingsStore 传下来）
  //   spread: auto = 宽屏自动双栏 / single = 单栏 / double = 强制双栏
  //   verticalText: writing-mode: vertical-rl（竖排）
  spread?: 'auto' | 'single' | 'double'
  verticalText?: boolean
}

export function EpubRenderer({ book, content, bookId, spread = 'single', verticalText = false }: EpubRendererProps) {
  const { t } = useTranslation()
  const HIGHLIGHT_COLORS = useMemo(() => [
    { name: t('黄色'), value: '#fbbf24' },
    { name: t('绿色'), value: '#34d399' },
    { name: t('蓝色'), value: '#60a5fa' },
    { name: t('粉色'), value: '#f472b6' },
    { name: t('紫色'), value: '#a78bfa' },
  ], [t])
  const viewerRef = useRef<HTMLDivElement>(null)
  const bookRef = useRef<Book | null>(null)
  const renditionRef = useRef<Rendition | null>(null)
  const [isReady, setIsReady] = useState(false)
  const [highlightPopup, setHighlightPopup] = useState<{ x: number; y: number; cfiRange: string; text: string } | null>(null)
  // 选区结束时只记下候选（位置 / CFI / 文本），等工具条点「高亮」才弹出取色板；
  // 以前一选中就自己弹，会和 SelectionToolbar 叠在一起。
  const pendingPopupRef = useRef<{ x: number; y: number; cfiRange: string; text: string } | null>(null)

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
  const paging = usePaging()
  const seekTarget = useReaderStore((s) => s.seekTarget)
  const clearSeekTarget = useReaderStore((s) => s.clearSeekTarget)
  const searchQuery = useReaderStore((s) => s.searchQuery)
  const currentSearchIndex = useReaderStore((s) => s.currentSearchIndex)
  const setSearchMatches = useReaderStore((s) => s.setSearchMatches)
  // PB-024-C：搜索 / 高亮逻辑已抽到 EpubSearch（纯搬移）
  const { extractFullText, applySearchHighlights } = useEpubSearch({
    viewerRef,
    bookRef,
    renditionRef,
    searchQuery,
    currentSearchIndex,
    setSearchMatches,
    isReady,
  })

  const fontSize = useSettingsStore((s) => s.fontSize)
  const fontFamily = useSettingsStore((s) => s.fontFamily)
  const lineHeight = useSettingsStore((s) => s.lineHeight)
  const margin = useSettingsStore((s) => s.margin)
  const textAlign = useSettingsStore((s) => s.textAlign)
  const theme = useSettingsStore((s) => s.theme)
  const spineLengthRef = useRef(0)
  // content hook 里拿不到最新 props（闭包只捕获创建 rendition 那一刻），用 ref 同步
  const verticalRef = useRef(verticalText)
  useEffect(() => { verticalRef.current = verticalText }, [verticalText])

  // Handle navigation from sidebar bookmarks/TOC
  useEffect(() => {
    if (!navigateTarget || !renditionRef.current) return
    if (navigateTarget.cfi) {
      renditionRef.current.display(navigateTarget.cfi)
    }
    clearNavigateTarget()
  }, [navigateTarget])

  // 统一翻页：把 EPUB 的 next/prev 注册给 PagingApi；队列/连点去重由 paging 统一处理。
  useEffect(() => {
    if (!paging) return
    paging.register({
      next: () => renditionRef.current?.next(),
      prev: () => renditionRef.current?.prev(),
    })
    return () => paging.register(null)
  }, [paging])

  // Handle seek from progress bar
  useEffect(() => {
    if (seekTarget === null || !renditionRef.current || !bookRef.current) return
    const epubBook = bookRef.current
    const spineLen = spineLengthRef.current
    if (spineLen > 0) {
      const targetIndex = Math.round((seekTarget / 100) * (spineLen - 1))
      const items = spineEntries(epubBook.spine)
      if (items[targetIndex]) {
        const href = items[targetIndex].href
        if (href) renditionRef.current.display(href)
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
      spread: epubSpreadValue(spread),
      flow: 'paginated',
      allowScriptedContent: false,
    })

    renditionRef.current = rendition

    epubBook.ready.then(() => {
      const toc = epubBook.navigation?.toc || []
      setTableOfContents(toc)

      // Get spine length for progress calculation
      spineLengthRef.current = spineEntries(epubBook.spine).length

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
        const items = spineEntries(epubBook.spine)
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

      pendingPopupRef.current = { x, y, cfiRange, text }
    })

    // Fix iframe sandbox, enable text selection, and forward mousemove
    // NOTE: allow-scripts is intentionally omitted — EPUB content is untrusted
    // (a malicious <script> inside a chapter would run on the app origin which
    // owns the electronAPI bridge). Selection/highlighting doesn't need it.
    const hookedDocs = new WeakSet<Document>()
    const contentCleanupFns: Array<() => void> = []
    rendition.hooks.content.register((contents: any) => {
      const iframe = viewerRef.current?.querySelector('iframe')
      if (iframe) {
        iframe.sandbox = 'allow-same-origin allow-popups'
      }
      const doc = contents.document
      if (!doc || hookedDocs.has(doc)) return
      hookedDocs.add(doc)

      doc.body.style.userSelect = 'text'
      doc.body.style.webkitUserSelect = 'text'

      // PB-009-A：竖排在每章新文档里也要重新注入
      setVerticalStyle(doc, verticalRef.current)

      // Inject CSS highlight styles into iframe
      if (!doc.getElementById('pb-search-styles')) {
        const st = doc.createElement('style')
        st.id = 'pb-search-styles'
        st.textContent = '::highlight(pb-search){background-color:rgba(251,191,36,0.3);color:inherit}::highlight(pb-search-active){background-color:rgba(251,191,36,0.55);outline:2px solid rgba(251,191,36,0.8);outline-offset:1px}'
        doc.head?.appendChild(st)
      }

      // 转发时带上换算后的窗口坐标：以前不传坐标（默认 0,0），父层的「鼠标在上下热区
      // 才显示工具栏」会被误判成永远在最上面，阅读器 chrome 一直不收。
      const onMousemove = (e: MouseEvent) => {
        const frame = viewerRef.current?.querySelector('iframe')
        const bounds = frame?.getBoundingClientRect()
        window.dispatchEvent(new MouseEvent('mousemove', {
          bubbles: true,
          clientX: (bounds?.left ?? 0) + e.clientX,
          clientY: (bounds?.top ?? 0) + e.clientY,
        }))
      }
      // EPUB 正文在 iframe 里：点击**不会冒泡到父文档**，所以父层的「点左侧 30%
      // 上一页 / 右侧 30% 下一页」对正文点击一直没生效，用户只能瞄准那两个小小的
      // 圆形箭头（“点翻页键前几次没反应”）。这里把 iframe 内的点击翻译成翻页，
      // 并排除拖选文字 / 点到链接的情况。
      let downX = 0
      let downY = 0
      const onMouseDown = (e: MouseEvent) => { downX = e.clientX; downY = e.clientY }
      const onClick = (e: MouseEvent) => {
        const target = e.target as HTMLElement | null
        if (target && typeof target.closest === 'function' && target.closest('a')) return
        // 拖选文字（含双击选词）时不要翻页
        if (Math.hypot(e.clientX - downX, e.clientY - downY) > 6) return
        const sel = doc.getSelection()
        if (sel && !sel.isCollapsed) return
        const frame = viewerRef.current?.querySelector('iframe')
        if (!frame) return
        const bounds = frame.getBoundingClientRect()
        paging?.click(bounds.left + e.clientX)
      }
      const onWheel = (e: WheelEvent) => {
        window.dispatchEvent(new WheelEvent('wheel', {
          deltaY: e.deltaY, deltaX: e.deltaX,
          ctrlKey: e.ctrlKey, shiftKey: e.shiftKey, altKey: e.altKey, bubbles: true,
        }))
      }
      // 键盘同样转给父窗口：点过正文后焦点在 iframe 里，Esc / ← → / B 这些全局
      // 快捷键原本到不了父窗口（「点了下正文，翻页键就不管用了」的另一条路径）。
      const onKeydown = (e: KeyboardEvent) => {
        window.dispatchEvent(new KeyboardEvent('keydown', {
          key: e.key,
          code: e.code,
          ctrlKey: e.ctrlKey,
          metaKey: e.metaKey,
          altKey: e.altKey,
          shiftKey: e.shiftKey,
          repeat: e.repeat,
          bubbles: true,
          cancelable: true,
        }))
      }
      doc.addEventListener('mousemove', onMousemove)
      doc.addEventListener('mousedown', onMouseDown)
      doc.addEventListener('click', onClick)
      doc.addEventListener('wheel', onWheel)
      doc.addEventListener('keydown', onKeydown)
      contentCleanupFns.push(() => {
        doc.removeEventListener('mousemove', onMousemove)
        doc.removeEventListener('mousedown', onMouseDown)
        doc.removeEventListener('click', onClick)
        doc.removeEventListener('wheel', onWheel)
        doc.removeEventListener('keydown', onKeydown)
      })

      // Fallback: detect selection via mouseup if 'selected' event doesn't fire
      // 必须具名并登记清理：此前是匿名箭头函数，清理数组里收不到它 —— 监听器永远
      // 摘不掉，里面的 setTimeout 也不会被取消。
      let mouseupTimer: ReturnType<typeof setTimeout> | null = null
      const onMouseup = () => {
          mouseupTimer = setTimeout(() => {
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
              pendingPopupRef.current = { x, y, cfiRange: cfi, text }
            }
          }, 10)
      }
      doc.addEventListener('mouseup', onMouseup)
      contentCleanupFns.push(() => {
        doc.removeEventListener('mouseup', onMouseup)
        if (mouseupTimer) { clearTimeout(mouseupTimer); mouseupTimer = null }
      })
    })

    return () => {
      contentCleanupFns.forEach(fn => fn())
      // Remove injected search styles from all iframes
      document.querySelectorAll('iframe').forEach(iframe => {
        try {
          const style = iframe.contentDocument?.getElementById('pb-search-styles')
          style?.remove()
          iframe.contentDocument?.getElementById(VERTICAL_STYLE_ID)?.remove()
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

  // PB-006-D：搜索命中「章节内定位」——paginated flow 下 iframe 不可滚动，
  // 唯一可靠的做法是在 rendition.getContents() 的 DOM 里按 quote 找 Range，
  // 转成 CFI 后 display。供 EpubAdapter 的 goTo(scroll+quote) 调用。
  useEffect(() => {
    if (!isReady) return
    const seekQuote = (quote: string): boolean => {
      const rendition = renditionRef.current
      const text = (quote || '').trim()
      if (!rendition || text.length < 2) return false
      try {
        const rawContents = rendition.getContents() as any
        const contentsList = Array.isArray(rawContents) ? rawContents : [rawContents]
        for (const contents of contentsList) {
          const doc: Document | undefined = contents?.document
          if (!doc || !doc.body) continue
          const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT)
          while (walker.nextNode()) {
            const node = walker.currentNode as Text
            const value = node.textContent || ''
            const at = value.indexOf(text)
            if (at < 0) continue
            const range = doc.createRange()
            range.setStart(node, at)
            range.setEnd(node, at + text.length)
            const cfi = typeof contents.cfiFromRange === 'function' ? contents.cfiFromRange(range) : null
            if (!cfi) continue
            Promise.resolve(rendition.display(cfi)).catch(() => {})
            return true
          }
        }
      } catch (error) {
        console.debug('EpubRenderer: seek quote failed', error)
      }
      return false
    }
    ;(window as any).__pbEpubSeekQuote = seekQuote
    return () => {
      if ((window as any).__pbEpubSeekQuote === seekQuote) delete (window as any).__pbEpubSeekQuote
    }
  }, [isReady])

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

  // ── PB-009-A：分栏模式（epub.js spread: none/auto/both）──────────────
  // 运行时切换：改 rendition.settings.spread 后重新触发一次布局。
  useEffect(() => {
    const rendition = renditionRef.current
    if (!rendition || !isReady) return
    try {
      ;(rendition as any).spread(epubSpreadValue(spread))
      // epub.js 内部 manager 需要一次 resize 才会按新分栏重排
      window.setTimeout(() => {
        const el = viewerRef.current
        if (el) { try { rendition.resize(el.clientWidth, el.clientHeight) } catch { /* ignore */ } }
      }, 60)
    } catch { /* ignore */ }
  }, [spread, isReady])

  // ── PB-009-A：竖排（writing-mode: vertical-rl）────────────────────────
  // 1) 给当前所有 iframe 文档注入/移除样式；2) 触发重排。
  useEffect(() => {
    if (!isReady) return
    const apply = () => {
      const frames = Array.from(viewerRef.current?.querySelectorAll('iframe') ?? [])
      for (const frame of frames) {
        try { setVerticalStyle(frame.contentDocument, verticalText) } catch { /* ignore */ }
      }
    }
    apply()
    // 换章后 iframe 可能被替换：再补一次
    const timer = window.setTimeout(() => {
      apply()
      const el = viewerRef.current
      if (el) { try { renditionRef.current?.resize(el.clientWidth, el.clientHeight) } catch { /* ignore */ } }
    }, 120)
    return () => window.clearTimeout(timer)
  }, [verticalText, isReady, content])




  // 工具条点「高亮」→ 显示候选取色板（显示后清空候选，避免重复弹）。
  useEffect(() => {
    const onOpen = () => {
      const pending = pendingPopupRef.current
      if (!pending) return
      pendingPopupRef.current = null
      setHighlightPopup(pending)
      useReaderStore.getState().setHighlightPaletteOpen(true)
      window.dispatchEvent(new CustomEvent('pb:highlightPaletteOpened'))
    }
    window.addEventListener('pb:openHighlightPalette', onOpen)
    return () => window.removeEventListener('pb:openHighlightPalette', onOpen)
  }, [])

  // 取色板统一收尾：关闭弹层并清掉「取色板打开」标记（工具条恢复显示）。
  const closeHighlightPopup = useCallback(() => {
    setHighlightPopup(null)
    useReaderStore.getState().setHighlightPaletteOpen(false)
    // iframe 里的选区不会自己清，轮询会把工具条又顶出来；这里主动收掉
    try {
      const contents: any = renditionRef.current?.getContents?.()
      const list = Array.isArray(contents) ? contents : contents ? [contents] : []
      list.forEach((item: any) => { item?.window?.getSelection?.()?.removeAllRanges?.() })
    } catch { /* ignore */ }
    useReaderStore.getState().clearSelection()
  }, [])

  // 之前这里没有「点空白 / 滚动」关闭：取色板会一直挂在旧坐标上。顺手补上。
  useEffect(() => {
    if (!highlightPopup) return
    const onMouseDown = (e: MouseEvent) => {
      const el = document.querySelector('[data-epub-highlight-popup]')
      if (el && e.target instanceof Node && el.contains(e.target)) return
      closeHighlightPopup()
    }
    const onScroll = () => closeHighlightPopup()
    window.addEventListener('mousedown', onMouseDown)
    window.addEventListener('scroll', onScroll, true)
    return () => {
      window.removeEventListener('mousedown', onMouseDown)
      window.removeEventListener('scroll', onScroll, true)
    }
  }, [highlightPopup, closeHighlightPopup])

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

    closeHighlightPopup()
  }, [highlightPopup, bookId, addHighlight, closeHighlightPopup])

  return (
    <div className="h-full relative">
      <div ref={viewerRef} className="h-full" />
      {!isReady && (
        <div className="absolute inset-0 flex items-center justify-center bg-[var(--reader-bg)]">
          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-[var(--md-sys-color-primary)]"></div>
        </div>
      )}

      {/* Highlight popup - rendered via portal to escape overflow-hidden */}
      {highlightPopup && createPortal(
        /* 表面统一到 .m3-menu（surface-container-high + radius 12 + elevation-2 + padding 8）；
           不挂 animate-scale-in：这里靠内联 transform 做 -50%/-100% 锚点，
           动画的 transform 会盖掉它，弹层会先跳到错位再回弹。 */
        <div
          className="m3-menu fixed z-[9999] flex items-center gap-2"
          data-epub-highlight-popup="1"
          style={{ left: highlightPopup.x, top: highlightPopup.y, transform: 'translate(-50%, -100%)' }}
          onClick={(e) => e.stopPropagation()}
        >
          {HIGHLIGHT_COLORS.map((c) => (
            <button
              key={c.value}
              onClick={() => handleAddHighlight(c.value)}
              className="w-6 h-6 rounded-full border border-[var(--md-sys-color-outline-variant)] hover:border-[var(--md-sys-color-primary)] transition-colors"
              style={{ backgroundColor: c.value }}
              title={c.name}
              aria-label={c.name}
            />
          ))}
          <button
            onClick={closeHighlightPopup}
            className="ml-1 text-[var(--md-sys-color-on-surface-variant)] opacity-60 hover:opacity-100 text-xs"
            aria-label={t('关闭')}
            title={t('关闭')}
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