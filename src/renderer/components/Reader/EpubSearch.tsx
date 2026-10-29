import { useCallback, useEffect, useRef, useState } from 'react'
import type { MutableRefObject } from 'react'
import type { Book, Rendition } from 'epubjs'
import { spineEntries } from './EpubRendererTheme'

// ── PB-024-C：从 EpubRenderer.tsx 抽出的 EPub 搜索 / 高亮逻辑（纯搬移）──
// 全库文本抽取、CSS Highlight API 高亮、跨章节搜索计数、跳转到第 N 个匹配。
// 组件只保留渲染与副作用编排；refs 由组件传入，hook 内部维护匹配表。
interface UseEpubSearchArgs {
  viewerRef: MutableRefObject<HTMLDivElement | null>
  bookRef: MutableRefObject<Book | null>
  renditionRef: MutableRefObject<Rendition | null>
  searchQuery: string
  currentSearchIndex: number
  setSearchMatches: (matches: number[]) => void
  isReady: boolean
}

/** EPUB 全文搜索命中上限（review F-02/F-08：常见词可能几万条命中）。 */
const MAX_EPUB_SEARCH_MATCHES = 2000

export function useEpubSearch({
  viewerRef,
  bookRef,
  renditionRef,
  searchQuery,
  currentSearchIndex,
  setSearchMatches,
  isReady,
}: UseEpubSearchArgs) {
  const searchRangesRef = useRef<{ href: string; occurrenceIndex: number }[]>([])
  const searchHrefMapRef = useRef<Map<string, { text: string; matchCount: number }>>(new Map())
  const navRef = useRef(false)
  // review F-08：命中表来自异步的全文抽取；抽取完成前搜索只能算出 0 命中，
  // 而旧实现的 search effect 只依赖 [searchQuery, isReady]，抽取完成后不会再算一次，
  // 于是「打开书就搜」会一直显示 0/0。indexVersion 用来在抽取完成后重算，
  // indexReady 用来在界面上区分「还在建索引」和「真的没有命中」。
  const [indexVersion, setIndexVersion] = useState(0)
  const [indexReady, setIndexReady] = useState(false)

  // 换书/重开（isReady 回落到 false）时，上一本的命中表与「索引就绪」标记
  // 必须一起作废，否则新书会用旧书的文本算命中（review F-08）
  useEffect(() => {
    if (isReady) return
    setIndexReady(false)
    searchHrefMapRef.current = new Map()
  }, [isReady])

  // ── Full-book text extraction: count matches per section ──────────────
  const extractFullText = useCallback(async () => {
    const epubBook = bookRef.current
    if (!epubBook) return
    try {
      const items = spineEntries(epubBook.spine)
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
    // 无论成功失败都标记一次「抽取结束」：搜索框要能给出确定的结论
    setIndexReady(true)
    setIndexVersion((v) => v + 1)
  }, [bookRef])

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
  }, [viewerRef, searchQuery])

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
        // 上限兜底：和 TXT 搜索同一个道理（review F-02），避免常见词把命中表撑到几万条
        if (results.length >= MAX_EPUB_SEARCH_MATCHES) break
        idx = lowerText.indexOf(q, idx + q.length)
      }
      if (results.length >= MAX_EPUB_SEARCH_MATCHES) break
    }

    searchRangesRef.current = results
    setSearchMatches(Array.from({ length: results.length }, (_, i) => i))

    setTimeout(() => applySearchHighlights(), 300)
    // indexVersion：全文抽取完成后重算一次（review F-08）
  }, [searchQuery, isReady, indexVersion, viewerRef, setSearchMatches, applySearchHighlights])

  // ── Navigate to search match across sections ──────────────────────────
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
        // getContents() returns an ARRAY at runtime (epub.js types say a single
        // Contents); each entry is either the Document or something wrapping it.
        const contents = rendition.getContents() as unknown as Array<{ document?: Document } & Document>
        if (!contents || contents.length === 0) { navRef.current = false; return }
        const content = contents[0]
        const doc: Document = (content.document ?? content) as Document
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
  }, [currentSearchIndex, searchQuery, renditionRef, applySearchHighlights])

  // indexBuilding：搜索已发起、但全文还没抽取完 → 界面应显示「正在建立索引…」
  // 而不是「没有找到」（review F-08）
  return { extractFullText, applySearchHighlights, indexReady }
}
