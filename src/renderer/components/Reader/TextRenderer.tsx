import { useEffect, useRef, useMemo, useState, useCallback } from 'react'
import type { JSX } from 'react'   // React 19 removed the global JSX namespace
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { useReaderStore } from '../../stores/readerStore'
import { usePaging } from '../../reader'
import { useSettingsStore } from '../../stores/settingsStore'
import type { Book } from '../../stores/libraryStore'

interface TextRendererProps {
  book: Book
  content: Uint8Array
  bookId: number
}

/** 全文搜索一次性最多标记多少处（review F-02：无上限会让大 TXT 搜常用字时假死）。 */
const MAX_SEARCH_MATCHES = 500
/** 单条高亮（存的是纯文本、要靠 indexOf 找位置）最多标多少处，避免常见词铺满全文。 */
const MAX_HIGHLIGHT_OCCURRENCES = 200

export function TextRenderer({ book, content, bookId }: TextRendererProps) {
  const { t } = useTranslation()
  const containerRef = useRef<HTMLDivElement>(null)
  const text = useMemo(() => new TextDecoder('utf-8').decode(content), [content])

  const HIGHLIGHT_COLORS = useMemo(() => [
    { value: 'rgba(234,179,8,0.45)', label: t('黄') },
    { value: 'rgba(59,130,246,0.45)', label: t('蓝') },
    { value: 'rgba(34,197,94,0.45)', label: t('绿') },
    { value: 'rgba(239,68,68,0.45)', label: t('红') },
    { value: 'rgba(168,85,247,0.45)', label: t('紫') },
  ], [t])

  const progress = useReaderStore((s) => s.progress)
  const setProgress = useReaderStore((s) => s.setProgress)
  const saveProgress = useReaderStore((s) => s.saveProgress)
  const setTableOfContents = useReaderStore((s) => s.setTableOfContents)
  const navigateTarget = useReaderStore((s) => s.navigateTarget)
  const clearNavigateTarget = useReaderStore((s) => s.clearNavigateTarget)
  const paging = usePaging()
  const seekTarget = useReaderStore((s) => s.seekTarget)
  const clearSeekTarget = useReaderStore((s) => s.clearSeekTarget)

  const highlights = useReaderStore((s) => s.highlights)
  const addHighlight = useReaderStore((s) => s.addHighlight)
  const [highlightPopup, setHighlightPopup] = useState<{ x: number; y: number; text: string; offset: number } | null>(null)
  const popupRef = useRef<HTMLDivElement | null>(null)
  // 选区结束时只记下候选（位置 / 文本 / 偏移），等工具条点了「高亮」才弹出取色板 ——
  // 以前选区一松手就自己弹，和 SelectionToolbar 叠在一起（用户截图反馈）。
  const pendingPopupRef = useRef<{ x: number; y: number; text: string; offset: number } | null>(null)

  // Full-text search
  const searchQuery = useReaderStore((s) => s.searchQuery)
  const searchMatches = useReaderStore((s) => s.searchMatches)
  const currentSearchIndex = useReaderStore((s) => s.currentSearchIndex)
  const setSearchMatches = useReaderStore((s) => s.setSearchMatches)

  useEffect(() => {
    const lines = text.split('\n')
    const chapterRe = /^第[一二三四五六七八九十百千\d]+[章节]/
    const enChapterRe = /^Chapter\s+\d+/i
    const potential: { line: number; label: string }[] = []
    lines.forEach((line, i) => {
      const trimmed = line.trim()
      if (chapterRe.test(trimmed) || enChapterRe.test(trimmed)) {
        potential.push({ line: i, label: trimmed })
      }
    })
    let clusterEnd = -1
    for (let j = 1; j < potential.length; j++) {
      if (potential[j].line - potential[j - 1].line <= 2) {
        clusterEnd = j
      } else {
        break
      }
    }
    const toc = potential.slice(clusterEnd + 1)
      .filter((item) => item.label.length <= 20)
      .map((item, i) => {
        let charOffset = 0
        for (let k = 0; k < item.line; k++) charOffset += lines[k].length + 1
        return { label: item.label, page: (charOffset / text.length) * 100, id: `toc-${charOffset}`, level: 2 }
      })
    setTableOfContents(toc)
  }, [text, setTableOfContents])

  // review F-02：TXT 全文搜索必须**限上限**。旧实现把全文所有命中都塞进 store，
  // 渲染时又给每个命中建一个 <mark> —— 搜「的」这种字能构造数万 DOM 节点，界面
  // 假死数秒到十几秒。这里扫到第 MAX_SEARCH_MATCHES+1 个就停（扫描成本也因此有界），
  // 并给用户一条可见提示。
  const [searchTruncated, setSearchTruncated] = useState(false)
  useEffect(() => {
    if (!searchQuery || searchQuery.trim().length === 0) {
      if (searchMatches.length > 0) setSearchMatches([])
      setSearchTruncated(false)
      return
    }
    const q = searchQuery.trim()
    const escaped = q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const matches: number[] = []
    let re: RegExp
    try { re = new RegExp(escaped, 'gi') } catch { return }
    let truncated = false
    let m: RegExpExecArray | null
    while ((m = re.exec(text)) !== null) {
      if (matches.length >= MAX_SEARCH_MATCHES) { truncated = true; break }
      matches.push(m.index)
    }
    setSearchMatches(matches)
    setSearchTruncated(truncated)
  }, [searchQuery, text])

  const fontSize = useSettingsStore((s) => s.fontSize)
  const fontFamily = useSettingsStore((s) => s.fontFamily)
  const lineHeight = useSettingsStore((s) => s.lineHeight)
  const margin = useSettingsStore((s) => s.margin)
  const textAlign = useSettingsStore((s) => s.textAlign)

  useEffect(() => {
    if (!containerRef.current) return
    const container = containerRef.current
    const handleMouseUp = (e: MouseEvent) => {
      setTimeout(() => {
        const selection = window.getSelection()
        if (!selection || selection.isCollapsed) return
        const selectedText = selection.toString().trim()
        if (!selectedText || selectedText.length < 1) return
        if (!container.contains(selection.anchorNode)) return
        // Calculate character offset of selection start
        const contentDiv = container.querySelector('.reader-content') || container
        const walker = document.createTreeWalker(contentDiv, NodeFilter.SHOW_TEXT)
        let curOff = 0
        let selOff = -1
        while (walker.nextNode()) {
          const node = walker.currentNode as Text
          if (node === selection.anchorNode) {
            selOff = curOff + selection.anchorOffset
            break
          }
          curOff += (node.textContent || '').length
        }
        pendingPopupRef.current = { x: e.clientX, y: e.clientY, text: selectedText, offset: selOff >= 0 ? selOff : 0 }
      }, 0)
    }
    container.addEventListener('mouseup', handleMouseUp)
    return () => container.removeEventListener('mouseup', handleMouseUp)
  }, [])

  // 工具条点「高亮」→ 这里才把候选取色板显示出来；显示后同步清空候选，避免重复弹。
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

  // 取色板的统一收尾：关掉弹层的同时清掉「取色板打开」标记，工具条才能恢复显示。
  const closeHighlightPopup = useCallback(() => {
    setHighlightPopup(null)
    useReaderStore.getState().setHighlightPaletteOpen(false)
    // 关掉取色板后把选区收掉：否则轮询会立刻把工具条又顶出来
    try { window.getSelection()?.removeAllRanges() } catch { /* ignore */ }
    useReaderStore.getState().clearSelection()
  }, [])

  // 弹窗此前只能靠"选色"或"×"关掉：点空白处、滚动正文都不会让它消失，于是它
  // 固定在旧坐标上挡住正文、并吃掉那块区域的点击。
  useEffect(() => {
    if (!highlightPopup) return
    const onMouseDown = (e: MouseEvent) => {
      const el = popupRef.current
      if (el && e.target instanceof Node && el.contains(e.target)) return
      closeHighlightPopup()
    }
    const onScroll = () => closeHighlightPopup()
    const container = containerRef.current
    window.addEventListener('mousedown', onMouseDown)
    container?.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      window.removeEventListener('mousedown', onMouseDown)
      container?.removeEventListener('scroll', onScroll)
    }
  }, [highlightPopup, closeHighlightPopup])

  const handleAddHighlight = useCallback((color: string) => {
    if (!highlightPopup) return
    const { text: selectedText, offset } = highlightPopup
    const page = (offset / text.length) * 100
    addHighlight({ book_id: bookId, text: selectedText, color, page, cfi: `pos-${offset}` })
    closeHighlightPopup()
  }, [highlightPopup, text, bookId, addHighlight, closeHighlightPopup])

  const highlightedContent = useMemo(() => {
    const ranges: { start: number; end: number; color: string; type: 'highlight' | 'search' | 'searchActive' }[] = []

    // User highlights
    for (const hl of highlights) {
      if (hl.cfi && String(hl.cfi).startsWith('pos-')) {
        const start = parseInt(String(hl.cfi).slice(4), 10)
        if (!isNaN(start) && start >= 0 && start < text.length) {
          const end = start + hl.text.length
          if (text.slice(start, end) === hl.text) {
            ranges.push({ start, end, color: hl.color, type: 'highlight' })
          }
        }
      } else {
        // 没有位置信息的旧高亮只能按文本找：常见词可能命中成千上万次，
        // 这里限次（review F-02），否则同样会把正文撑成数万个 <mark>。
        if (!hl.text) continue
        let pos = 0
        let occurrences = 0
        while (pos < text.length && occurrences < MAX_HIGHLIGHT_OCCURRENCES) {
          const idx = text.indexOf(hl.text, pos)
          if (idx === -1) break
          ranges.push({ start: idx, end: idx + hl.text.length, color: hl.color, type: 'highlight' })
          pos = idx + hl.text.length
          occurrences += 1
        }
      }
    }

    // Search matches
    if (searchQuery && searchMatches.length > 0) {
      const qLen = searchQuery.trim().length
      for (let i = 0; i < searchMatches.length; i++) {
        const start = searchMatches[i]
        ranges.push({
          start,
          end: start + qLen,
          color: i === currentSearchIndex ? 'rgba(251,191,36,0.55)' : 'rgba(251,191,36,0.3)',
          type: i === currentSearchIndex ? 'searchActive' : 'search',
        })
      }
    }

    if (ranges.length === 0) return text

    ranges.sort((a, b) => a.start - b.start)

    // Merge overlapping ranges, preferring highlight > searchActive > search
    const merged: typeof ranges = []
    for (const r of ranges) {
      const last = merged[merged.length - 1]
      if (last && r.start <= last.end) {
        last.end = Math.max(last.end, r.end)
        if (r.type === 'highlight' || (r.type === 'searchActive' && last.type === 'search')) {
          last.type = r.type
          last.color = r.color
        }
      } else {
        merged.push({ ...r })
      }
    }

    const parts: (string | JSX.Element)[] = []
    let lastEnd = 0
    for (const r of merged) {
      if (r.start > lastEnd) parts.push(text.slice(lastEnd, r.start))
      const style: React.CSSProperties = r.type === 'highlight'
        ? { backgroundColor: r.color, borderRadius: '2px', color: 'inherit' }
        : r.type === 'searchActive'
          ? { backgroundColor: 'rgba(251,191,36,0.55)', borderRadius: '2px', color: 'inherit', outline: '2px solid rgba(251,191,36,0.8)', outlineOffset: '1px' }
          : { backgroundColor: 'rgba(251,191,36,0.3)', borderRadius: '2px', color: 'inherit' }
      parts.push(
        <mark key={`${r.start}`} style={style}>
          {text.slice(r.start, r.end)}
        </mark>
      )
      lastEnd = r.end
    }
    if (lastEnd < text.length) parts.push(text.slice(lastEnd))
    return parts
  }, [text, highlights, searchQuery, searchMatches, currentSearchIndex])

  useEffect(() => {
    if (!navigateTarget || !containerRef.current) return
    const container = containerRef.current
    // Extract character offset from id (e.g. "toc-12345")
    let charOffset = -1
    if (navigateTarget.cfi && String(navigateTarget.cfi).startsWith('toc-')) {
      const off = parseInt(String(navigateTarget.cfi).slice(4), 10)
      if (!isNaN(off)) charOffset = off
    }
    if (charOffset >= 0) {
      // Find the text node containing this character offset
      const contentDiv = container.querySelector('.reader-content') || container
      const walker = document.createTreeWalker(contentDiv, NodeFilter.SHOW_TEXT)
      let curOff = 0
      let targetNode: Text | null = null
      let targetIdx = 0
      while (walker.nextNode()) {
        const node = walker.currentNode as Text
        const len = (node.textContent || '').length
        if (curOff + len > charOffset) {
          targetNode = node
          targetIdx = charOffset - curOff
          break
        }
        curOff += len
      }
      if (targetNode) {
        const range = document.createRange()
        range.setStart(targetNode, Math.min(targetIdx, (targetNode.textContent || '').length))
        range.collapse(true)
        const rect = range.getClientRects()[0]
        if (rect) {
          container.scrollTop += rect.top - container.getBoundingClientRect().top - 80
        }
      }
    } else if (navigateTarget.page) {
      const maxScroll = container.scrollHeight - container.clientHeight
      container.scrollTop = (navigateTarget.page / 100) * maxScroll
    }
    clearNavigateTarget()
  }, [navigateTarget])

  // 统一翻页：注册「按视口滚动」的翻页器（键盘 / 点击两侧 / 箭头都走这里）。
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

  // Scroll to current search match
  useEffect(() => {
    if (searchMatches.length === 0 || currentSearchIndex < 0 || !containerRef.current) return
    const offset = searchMatches[currentSearchIndex]
    if (offset === undefined) return
    const container = containerRef.current
    const contentDiv = container.querySelector('.reader-content') || container
    const walker = document.createTreeWalker(contentDiv, NodeFilter.SHOW_TEXT)
    let curOff = 0
    let targetNode: Text | null = null
    let targetIdx = 0
    while (walker.nextNode()) {
      const node = walker.currentNode as Text
      const len = (node.textContent || '').length
      if (curOff + len > offset) {
        targetNode = node
        targetIdx = offset - curOff
        break
      }
      curOff += len
    }
    if (targetNode) {
      const range = document.createRange()
      range.setStart(targetNode, Math.min(targetIdx, (targetNode.textContent || '').length))
      range.collapse(true)
      const rect = range.getClientRects()[0]
      if (rect) {
        container.scrollTop += rect.top - container.getBoundingClientRect().top - container.clientHeight / 3
      }
    }
  }, [currentSearchIndex])

  useEffect(() => {
    if (!containerRef.current) return
    const container = containerRef.current
    let saveTimer: ReturnType<typeof setTimeout> | null = null
    const handleScroll = () => {
      const scrollTop = container.scrollTop
      const scrollHeight = container.scrollHeight - container.clientHeight
      const progressPercent = scrollHeight > 0 ? (scrollTop / scrollHeight) * 100 : 0
      setProgress({ progress: progressPercent, page: progressPercent, scrollPosition: scrollTop })
      if (saveTimer) clearTimeout(saveTimer)
      saveTimer = setTimeout(() => saveProgress(), 500)
    }
    container.addEventListener('scroll', handleScroll)
    if (progress.scrollPosition) {
      container.scrollTop = progress.scrollPosition
    }
    return () => {
      container.removeEventListener('scroll', handleScroll)
      if (saveTimer) clearTimeout(saveTimer)
    }
  }, [text])

  return (
    <>
      <div
        ref={containerRef}
        className="h-full overflow-auto bg-[var(--reader-bg)] text-[var(--reader-text)]"
        style={{ padding: `0 ${margin}px` }}
      >
        {searchTruncated && (
          <div
            role="status"
            className="sticky top-0 z-10 mb-2 px-3 py-1.5 text-xs rounded-md"
            style={{ background: 'var(--md-sys-color-error-container)', color: 'var(--md-sys-color-on-error-container)' }}
            data-text-search-truncated="1"
          >
            {t('命中过多：只标记前 {{n}} 处，请用更长的关键词缩小范围', { n: MAX_SEARCH_MATCHES })}
          </div>
        )}
        <div
          className="reader-content select-text"
          style={{
            width: '100%',
            whiteSpace: 'pre-line',
            overflowWrap: 'break-word',
            fontSize: `${fontSize}px`,
            fontFamily: fontFamily.replace('Georgia', 'JetBrains Mono').replace('serif', 'monospace'),
            lineHeight,
            textAlign,
          }}
        >
          {highlightedContent}
        </div>
      </div>

      {highlightPopup && createPortal(
        <div
          ref={popupRef}
          /* PB-025-B：不再写死深色调色板，跟随主题 token；
             表面统一到 .m3-menu（surface-container-high + radius 12 + elevation-2 + padding 8） */
          data-text-highlight-popup="1"
          className="m3-menu fixed z-50 flex items-center gap-2"
          style={{ left: highlightPopup.x, top: highlightPopup.y - 44 }}
        >
          {HIGHLIGHT_COLORS.map((c) => (
            <button
              key={c.value}
              onMouseDown={(e) => { e.preventDefault(); handleAddHighlight(c.value) }}
              className="w-6 h-6 rounded-full border border-[var(--md-sys-color-outline-variant)] hover:border-[var(--md-sys-color-primary)] hover:scale-110 transition-transform"
              style={{ backgroundColor: c.value }}
              title={c.label}
              aria-label={c.label}
            />
          ))}
          <button
            onMouseDown={(e) => { e.preventDefault(); closeHighlightPopup() }}
            className="w-6 h-6 rounded-full border border-[var(--md-sys-color-outline-variant)] flex items-center justify-center text-sm text-[var(--md-sys-color-on-surface-variant)] hover:text-[var(--md-sys-color-on-surface)] transition-colors"
            aria-label={t('关闭')}
            title={t('关闭')}
          >
            ×
          </button>
        </div>,
        document.body
      )}
    </>
  )
}