import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'

// ── PB-006-B：书库「全局全文搜索」面板 ───────────────────────────────
//
// 后端是 PB-006-A 的 SearchIndexService（C++），bridge 提供：
//   searchStatus()  -> { books, terms, chars } | null
//   searchBuild()   -> { ok, indexed, reused, removed, skipped, skippedPdf, failed,
//                        cancelled, totalChars, termCount, elapsedMs, errors[],
//                        saved, saveError }        （JobService 异步，可能较久）
//   searchQuery(q, maxHits) -> { hits[{bookId,bookTitle,bookFormat,snippet,offset,
//                                 matchLength,page,score,termHits}], booksMatched,
//                                 truncated, cancelled }
// 本组件不新增 bridge、不改 C++。
//
// 定位能力：SearchIndex 的 offset 是「规范化正文里的码点偏移」，和渲染层的
// CFI / 页码 / 滚动位置没有可逆映射，所以点击结果目前只能**打开那本书**，
// 不能精确跳到命中段落（UI 底部有说明）。等 PB-021 Locator 统一模型落地后再接。
//
// 状态：索引为空提示先建索引；建索引只显示已用时间（后端没有进度事件，给不了
// 百分比）；查询防抖 300ms；无结果 / 错误 / 截断都有提示；Esc 关闭。

interface SearchHit {
  bookId: number
  bookTitle: string
  bookFormat: string
  snippet: string
  offset: number
  matchLength: number
  page: number
  score: number
  termHits: number
}

interface SearchStatus {
  books: number
  terms: number
  chars: number
}

interface BuildResult {
  ok: boolean
  indexed: number
  reused: number
  removed: number
  skipped: number
  skippedPdf: number
  failed: number
  cancelled: boolean
  totalChars: number
  termCount: number
  elapsedMs: number
  errors: string[]
  saved: boolean
  saveError: string
}

interface LibrarySearchProps {
  onClose: () => void
  onOpenBook: (bookId: number) => void
}

const DEBOUNCE_MS = 300
const MAX_HITS = 50
const REGEX_SPECIAL = '.*+?^$()[]{}|\\'

function formatNumber(value: number): string {
  try { return new Intl.NumberFormat().format(value) } catch { return String(value) }
}

function formatSeconds(ms: number): string {
  if (ms < 1000) return '0'
  return String(Math.round(ms / 1000))
}

// 把查询拆成要高亮的词：整串 + 空白切分（中文查询通常没有空格）
function buildTerms(query: string): string[] {
  const terms: string[] = []
  const trimmed = query.trim()
  if (!trimmed) return terms
  terms.push(trimmed)
  for (const piece of trimmed.split(/\s+/)) {
    if (piece && piece !== trimmed) terms.push(piece)
  }
  return terms.slice(0, 8)
}

function escapeRegExp(text: string): string {
  let out = ''
  for (const ch of text) out += REGEX_SPECIAL.includes(ch) ? '\\' + ch : ch
  return out
}

interface SnippetPart { text: string; hit: boolean }

function splitSnippet(snippet: string, terms: string[]): SnippetPart[] {
  if (!snippet) return []
  const usable = terms.filter((term) => term.length > 0)
  if (usable.length === 0) return [{ text: snippet, hit: false }]
  const pattern = new RegExp('(' + usable.map(escapeRegExp).join('|') + ')', 'gi')
  const parts: SnippetPart[] = []
  let last = 0
  let match: RegExpExecArray | null
  while ((match = pattern.exec(snippet))) {
    if (match.index > last) parts.push({ text: snippet.slice(last, match.index), hit: false })
    parts.push({ text: match[0], hit: true })
    last = match.index + match[0].length
    if (match[0].length === 0) pattern.lastIndex++
  }
  if (last < snippet.length) parts.push({ text: snippet.slice(last), hit: false })
  return parts
}

function searchErrorKey(code: string): string {
  switch (code) {
  case 'search_unavailable': return '全文搜索暂不可用'
  case 'empty_query': return '请输入搜索词'
  case 'cancelled': return '搜索已取消'
  case 'job_service_unavailable': return '任务服务暂不可用，请稍后重试'
  default: return '搜索失败：{{error}}'
  }
}

export function LibrarySearch({ onClose, onOpenBook }: LibrarySearchProps) {
  const { t } = useTranslation()
  const [status, setStatus] = useState<SearchStatus>({ books: 0, terms: 0, chars: 0 })
  const [statusLoaded, setStatusLoaded] = useState(false)
  const [building, setBuilding] = useState(false)
  const [buildElapsedMs, setBuildElapsedMs] = useState(0)
  const [buildResult, setBuildResult] = useState<BuildResult | null>(null)
  const [query, setQuery] = useState('')
  const [debounced, setDebounced] = useState('')
  const [searching, setSearching] = useState(false)
  const [hits, setHits] = useState<SearchHit[]>([])
  const [booksMatched, setBooksMatched] = useState(0)
  const [truncated, setTruncated] = useState(false)
  const [errorCode, setErrorCode] = useState('')
  const [errorText, setErrorText] = useState('')
  const [activeIndex, setActiveIndex] = useState(-1)
  const [searchNonce, setSearchNonce] = useState(0)
  const requestRef = useRef(0)
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const buildStartRef = useRef(0)

  const loadStatus = useCallback(async () => {
    try {
      const result = await window.electronAPI.searchStatus()
      setStatus(result ?? { books: 0, terms: 0, chars: 0 })
    } catch {
      setStatus({ books: 0, terms: 0, chars: 0 })
    } finally {
      setStatusLoaded(true)
    }
  }, [])

  useEffect(() => { void loadStatus() }, [loadStatus])
  useEffect(() => { inputRef.current?.focus() }, [])

  // Esc 关闭：用窗口级捕获监听，而不是只绑面板的 onKeyDown ——
  // 焦点万一不在面板里（例如用户点了滚动条/空白）也要能关。
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.preventDefault()
      e.stopPropagation()
      onClose()
    }
    window.addEventListener('keydown', onKeyDown, true)
    return () => window.removeEventListener('keydown', onKeyDown, true)
  }, [onClose])

  const pendingTerms = useMemo(() => buildTerms(debounced), [debounced])

  // 防抖：输入停止 300ms 后才真正查询
  useEffect(() => {
    const id = window.setTimeout(() => setDebounced(query.trim()), DEBOUNCE_MS)
    return () => window.clearTimeout(id)
  }, [query])

  // 建索引「已用时间」（后端没有进度事件，只能给耗时，给不了百分比）
  useEffect(() => {
    if (!building) return
    buildStartRef.current = Date.now()
    setBuildElapsedMs(0)
    const id = window.setInterval(() => setBuildElapsedMs(Date.now() - buildStartRef.current), 1000)
    return () => window.clearInterval(id)
  }, [building])

  // 查询（防抖后 + 索引重建后自动重跑）
  useEffect(() => {
    const q = debounced
    setActiveIndex(-1)
    if (!q) {
      setHits([])
      setBooksMatched(0)
      setTruncated(false)
      setSearching(false)
      setErrorCode('')
      setErrorText('')
      return
    }
    const requestId = requestRef.current + 1
    requestRef.current = requestId
    setSearching(true)
    setErrorCode('')
    setErrorText('')
    window.electronAPI.searchQuery(q, MAX_HITS)
      .then((result) => {
        if (requestRef.current !== requestId) return
        setHits(result?.hits ?? [])
        setBooksMatched(result?.booksMatched ?? 0)
        setTruncated(!!result?.truncated)
      })
      .catch((e) => {
        if (requestRef.current !== requestId) return
        const code = (e instanceof Error ? e.message : String(e)) || 'unknown'
        setHits([])
        setBooksMatched(0)
        setTruncated(false)
        setErrorCode(code)
        setErrorText(code === 'search_failed' ? t(searchErrorKey('')) : code === 'empty_query' || code === 'cancelled' || code === 'search_unavailable' || code === 'job_service_unavailable'
          ? t(searchErrorKey(code))
          : t(searchErrorKey(''), { error: code }))
      })
      .finally(() => {
        if (requestRef.current === requestId) setSearching(false)
      })
  }, [debounced, searchNonce, t])

  const buildIndex = useCallback(async () => {
    if (building) return
    setBuilding(true)
    setBuildResult(null)
    setErrorCode('')
    setErrorText('')
    try {
      const result = await window.electronAPI.searchBuild()
      setBuildResult(result)
      await loadStatus()
      if (debounced) setSearchNonce((n) => n + 1)
    } catch (e) {
      const code = (e instanceof Error ? e.message : String(e)) || 'unknown'
      setErrorCode(code)
      setErrorText(code === 'cancelled' ? t('索引建立已取消') : t('建立索引失败：{{error}}', { error: code }))
    } finally {
      setBuilding(false)
    }
  }, [building, debounced, loadStatus, t])

  const openHit = useCallback((hit: SearchHit | undefined) => {
    if (!hit) return
    onOpenBook(hit.bookId)
    onClose()
  }, [onClose, onOpenBook])

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    // Esc 由上面的窗口级监听处理（保证焦点不在面板里也能关）
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      if (hits.length === 0) return
      setActiveIndex((prev) => (prev + 1) % hits.length)
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      if (hits.length === 0) return
      setActiveIndex((prev) => (prev <= 0 ? hits.length - 1 : prev - 1))
      return
    }
    if (e.key === 'Enter') {
      e.preventDefault()
      openHit(hits[activeIndex >= 0 ? activeIndex : 0])
    }
  }, [activeIndex, hits, onClose, openHit])

  // 键盘选中项滚进视口
  useEffect(() => {
    if (activeIndex < 0 || !listRef.current) return
    const node = listRef.current.querySelector('[data-hit-index="' + activeIndex + '"]')
    if (node && typeof (node as HTMLElement).scrollIntoView === 'function') {
      (node as HTMLElement).scrollIntoView({ block: 'nearest' })
    }
  }, [activeIndex])

  const statusText = t('索引：{{books}} 本 · {{terms}} 个词 · {{chars}} 字', {
    books: formatNumber(status.books),
    terms: formatNumber(status.terms),
    chars: formatNumber(status.chars),
  })
  const indexEmpty = statusLoaded && status.books === 0 && !building

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center pt-[7vh] animate-fade-in"
      onClick={onClose}
      onKeyDown={handleKeyDown}
      data-global-search="1"
    >
      <div className="absolute inset-0" style={{ background: 'rgba(0,0,0,0.45)' }} />
      <div
        className="relative w-[760px] max-w-[94vw] max-h-[82vh] rounded-xl shadow-win-lg flex flex-col overflow-hidden animate-scale-in"
        style={{ background: 'var(--acrylic-bg)', backdropFilter: 'blur(24px)', border: '1px solid var(--acrylic-border)' }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between px-5 py-3.5 gap-3" style={{ borderBottom: '1px solid var(--border)' }}>
          <div className="min-w-0">
            <h3 className="text-base font-semibold" style={{ color: 'var(--text-primary)' }}>{t('全文搜索')}</h3>
            <p className="text-xs mt-0.5" style={{ color: 'var(--text-tertiary)' }}>{statusText}</p>
          </div>
          <div className="flex items-center gap-2 flex-shrink-0">
            <button
              onClick={() => { void buildIndex() }}
              disabled={building}
              className="btn-secondary text-xs"
              data-global-search-rebuild="1"
              title={t('重建索引会扫描全部书籍，可能需要一段时间')}
            >
              {building ? t('正在建立索引…已用 {{seconds}}s', { seconds: formatSeconds(buildElapsedMs) }) : t('重建索引')}
            </button>
            <button
              onClick={onClose}
              aria-label={t('关闭全文搜索')}
              className="p-1 rounded-md transition-colors"
              style={{ color: 'var(--text-tertiary)' }}
            >
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" /></svg>
            </button>
          </div>
        </div>

        {(building || buildResult) && (
          <div className="px-5 py-2 text-xs flex items-center gap-2 flex-wrap" style={{ borderBottom: '1px solid var(--border)', color: 'var(--text-secondary)' }}>
            {building && <span className="inline-block w-3 h-3 rounded-full border-2 border-current border-t-transparent animate-spin" />}
            {building && <span>{t('正在建立索引…已用 {{seconds}}s', { seconds: formatSeconds(buildElapsedMs) })}</span>}
            {!building && buildResult && (
              <span>
                {t('索引完成：新建 {{indexed}} 本 · 复用 {{reused}} 本 · 跳过 {{skipped}} 本 · 失败 {{failed}} 本 · 用时 {{seconds}}s', {
                  indexed: buildResult.indexed,
                  reused: buildResult.reused,
                  skipped: buildResult.skipped,
                  failed: buildResult.failed,
                  seconds: formatSeconds(buildResult.elapsedMs),
                })}
              </span>
            )}
            {!building && buildResult && buildResult.errors.length > 0 && (
              <span style={{ color: 'var(--color-red)' }}>{t('{{count}} 本书索引失败', { count: buildResult.errors.length })}</span>
            )}
            {!building && buildResult && !buildResult.saved && (
              <span style={{ color: 'var(--color-red)' }}>{t('索引保存失败：{{error}}', { error: buildResult.saveError })}</span>
            )}
          </div>
        )}

        <div className="px-5 py-3" style={{ borderBottom: '1px solid var(--border)' }}>
          <div className="relative">
            <svg className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 pointer-events-none" fill="none" viewBox="0 0 24 24" stroke="currentColor" style={{ color: 'var(--text-tertiary)' }}><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" /></svg>
            <input
              ref={inputRef}
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={t('搜索全文内容…')}
              className="w-full pl-9 pr-9 py-2 text-sm rounded-lg"
              style={{ background: 'var(--bg-tertiary)', color: 'var(--text-primary)', border: '1px solid var(--border)' }}
              data-global-search-input="1"
            />
            {searching && (
              <span className="absolute right-3 top-1/2 -translate-y-1/2 inline-block w-3 h-3 rounded-full border-2 border-current border-t-transparent animate-spin" style={{ color: 'var(--text-tertiary)' }} />
            )}
          </div>
        </div>

        <div ref={listRef} className="flex-1 overflow-y-auto px-2 py-2">
          {!statusLoaded ? (
            <p className="text-center py-10 text-sm" style={{ color: 'var(--text-tertiary)' }}>{t('正在读取索引状态…')}</p>
          ) : indexEmpty && !debounced ? (
            <div className="text-center py-10 px-6">
              <p className="text-sm mb-1" style={{ color: 'var(--text-secondary)' }}>{t('索引为空，点「重建索引」开始')}</p>
              <p className="text-xs mb-4" style={{ color: 'var(--text-tertiary)' }}>{t('重建索引会扫描全部书籍，可能需要一段时间')}</p>
              <button onClick={() => { void buildIndex() }} className="btn-primary text-sm">{t('重建索引')}</button>
            </div>
          ) : errorText ? (
            <div className="text-center py-8 px-6">
              <p className="text-sm mb-3 break-words" style={{ color: 'var(--color-red)' }}>{errorText}</p>
              <button onClick={() => setSearchNonce((n) => n + 1)} className="btn-secondary text-xs">{t('重试')}</button>
            </div>
          ) : searching && hits.length === 0 ? (
            <p className="text-center py-10 text-sm" style={{ color: 'var(--text-tertiary)' }}>{t('搜索中…')}</p>
          ) : !debounced ? (
            <div className="text-center py-10 px-6">
              <p className="text-sm" style={{ color: 'var(--text-tertiary)' }}>{t('输入关键词，在所有书的正文里搜索')}</p>
            </div>
          ) : hits.length === 0 ? (
            <div className="text-center py-10 px-6">
              <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>{t('没有找到包含「{{query}}」的内容', { query: debounced })}</p>
              <p className="text-xs mt-1" style={{ color: 'var(--text-tertiary)' }}>{t('可以试试更短的关键词，或先重建索引')}</p>
            </div>
          ) : (
            <>
              <p className="px-3 py-1 text-xs" style={{ color: 'var(--text-tertiary)' }}>
                {t('{{count}} 条结果，涉及 {{books}} 本书', { count: hits.length, books: booksMatched })}
                {truncated ? t(' · 结果已截断') : ''}
              </p>
              {hits.map((hit, index) => (
                <button
                  key={String(hit.bookId) + '-' + String(hit.offset) + '-' + index}
                  data-hit-index={index}
                  data-global-search-hit="1"
                  onClick={() => openHit(hit)}
                  onMouseEnter={() => setActiveIndex(index)}
                  className="w-full text-left px-3 py-2 rounded-lg transition-colors"
                  style={{ background: activeIndex === index ? 'var(--surface-hover)' : 'transparent' }}
                >
                  <div className="flex items-center gap-2 mb-0.5 min-w-0">
                    <span className="text-sm font-medium truncate" style={{ color: 'var(--text-primary)' }}>{hit.bookTitle}</span>
                    <span className="text-[10px] px-1.5 py-0.5 rounded flex-shrink-0" style={{ background: 'var(--bg-tertiary)', color: 'var(--text-tertiary)' }}>{hit.bookFormat.toUpperCase()}</span>
                    {hit.page > 0 && (
                      <span className="text-[10px] flex-shrink-0" style={{ color: 'var(--text-tertiary)' }}>{t('第 {{page}} 页', { page: hit.page })}</span>
                    )}
                  </div>
                  <p className="text-xs leading-relaxed break-words" style={{ color: 'var(--text-secondary)' }}>
                    {splitSnippet(hit.snippet, pendingTerms).map((part, partIndex) => part.hit
                      ? <mark key={partIndex} style={{ background: 'var(--color-indigo-bg)', color: 'var(--text-primary)', padding: '0 1px', borderRadius: 2 }}>{part.text}</mark>
                      : <span key={partIndex}>{part.text}</span>)}
                  </p>
                </button>
              ))}
            </>
          )}
        </div>

        <div className="px-5 py-2.5 text-[11px] flex items-center justify-between gap-3" style={{ borderTop: '1px solid var(--border)', color: 'var(--text-tertiary)' }}>
          <span>{t('目前会打开命中的书；正文里的精确定位还在做')}</span>
          <span>{t('↑↓ 选择 · Enter 打开 · Esc 关闭')}</span>
        </div>
      </div>
    </div>
  )
}


