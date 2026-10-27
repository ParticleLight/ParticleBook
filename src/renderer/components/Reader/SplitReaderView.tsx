// ── PB-009-C：分屏阅读核心（两本书并排）────────────────────────────────
//
// 本文件是**新增、未接线**的分屏组件：不修改 ReaderView / EpubRenderer /
// App.tsx，因此现有单栏阅读行为零变化。拿两个 bookId 就能渲染左右两栏，
// 每个 pane 拥有独立的 readerSession（进度/选区/搜索/TTS 归属互不覆盖）。
//
// 用法（下一轮接线时）：
//   <SplitReaderView
//     primary={{ bookId: 1 }}
//     secondary={{ bookId: 2 }}
//     onActivePaneChange={(pane) => setTtsPane(pane)}
//     onClose={() => setSplitMode(false)}
//   />
// 入口建议：书库列表「分屏阅读」上下文菜单（选第二本书）→ 打开本组件；
// 也可以在全屏阅读器工具栏放「并排打开」，把当前书作为 primary。
//
// 与现有 readerStore 的关系：分屏不共用 readerStore。每栏一个
// createReaderSession（见 stores/readerSession.ts），进度直接走
// getReadingProgress / updateReadingProgress。单栏 ReaderView 不受影响。
//
// 限制：
//   * 本轮只支持 EPUB / TXT；PDF / CBZ / CBR / HTML / MOBI 显示「分屏暂不支持」。
//   * 同一本书放到两栏（同 bookId）技术上能渲染，但两栏会写同一条进度记录，
//     不推荐；入口应选两本不同的书。
//   * 书签/高亮/笔记/TTS 的实际朗读仍由 readerStore 与 C++ 服务承担：本组件只
//     记录「哪个 pane 是 active / TTS owner」，接线时由宿主把 TTS 命令路由到
//     该 pane 的文本（见 session.claimTts）。
//   * 两栏共用应用主题与字号/行距设置；每 pane 独立样式不在本轮范围。

import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import ePub, { type Book as EpubBook, type Rendition } from 'epubjs'
import { useSettingsStore } from '../../stores/settingsStore'
import { readBookFile } from '../../utils/fileReader'
import {
  createReaderSession,
  isSplitReadable,
  splitUnsupportedReason,
  useReaderSession,
  type ReaderSession,
} from '../../stores/readerSession'
import styles from './SplitReaderView.module.css'

export type SplitPaneKey = 'primary' | 'secondary'

export interface SplitPaneDescriptor {
  bookId: number
  /** 可选兜底：getBook 查不到时用它（测试/沙箱场景） */
  title?: string
  format?: string
  filePath?: string
}

export interface SplitReaderViewProps {
  primary: SplitPaneDescriptor
  secondary: SplitPaneDescriptor
  /** 宿主退出分屏（关闭按钮） */
  onClose?: () => void
  /** 激活 pane 变化（用于 TTS / 快捷键路由） */
  onActivePaneChange?: (pane: SplitPaneKey) => void
  /** 初始左右比例（0.25 - 0.75，默认 0.5） */
  initialSplit?: number
  className?: string
}

interface LoadedBook {
  id: number
  title: string
  format: string
  filePath: string
}

const MIN_RATIO = 0.25
const MAX_RATIO = 0.75

function clampRatio(value: number): number {
  if (!Number.isFinite(value)) return 0.5
  return Math.min(MAX_RATIO, Math.max(MIN_RATIO, value))
}

function toLoadedBook(id: number, book: PbBook | null, fallback: SplitPaneDescriptor): LoadedBook | null {
  if (book) {
    return { id: book.id, title: book.title, format: book.format, filePath: book.file_path }
  }
  if (fallback.title && fallback.format && fallback.filePath) {
    return { id, title: fallback.title, format: fallback.format, filePath: fallback.filePath }
  }
  return null
}

function widthStyle(ratio: number): React.CSSProperties {
  return { flexBasis: String(ratio * 100) + '%', flexGrow: 0, flexShrink: 0 }
}

export function SplitReaderView({
  primary, secondary, onClose, onActivePaneChange, initialSplit = 0.5, className = '',
}: SplitReaderViewProps) {
  const { t } = useTranslation()
  const [ratio, setRatio] = useState(() => clampRatio(initialSplit))
  const [activePane, setActivePane] = useState<SplitPaneKey>('primary')
  const [books, setBooks] = useState<{ primary: LoadedBook | null; secondary: LoadedBook | null }>({ primary: null, secondary: null })
  const [sessions, setSessions] = useState<{ primary: ReaderSession | null; secondary: ReaderSession | null }>({ primary: null, secondary: null })
  const [loadError, setLoadError] = useState('')
  // PB-009-D：TTS 归属 —— 只有 active pane 能朗读；切栏即停（避免两段文本串台）
  const [speakingPane, setSpeakingPane] = useState<SplitPaneKey | null>(null)
  const textGettersRef = useRef<{ primary: (() => string) | null; secondary: (() => string) | null }>({ primary: null, secondary: null })
  const rootRef = useRef<HTMLDivElement>(null)
  const draggingRef = useRef(false)

  // 1) 解析两本书（getBook 查库；测试/沙箱可用 descriptor 兜底）
  useEffect(() => {
    let cancelled = false
    setLoadError('')
    setBooks({ primary: null, secondary: null })
    setSessions({ primary: null, secondary: null })
    void (async () => {
      try {
        const [p, s] = await Promise.all([
          window.electronAPI.getBook(primary.bookId).catch(() => null),
          window.electronAPI.getBook(secondary.bookId).catch(() => null),
        ])
        if (cancelled) return
        const loadedPrimary = toLoadedBook(primary.bookId, p, primary)
        const loadedSecondary = toLoadedBook(secondary.bookId, s, secondary)
        if (!loadedPrimary || !loadedSecondary) {
          setLoadError(t('找不到要分屏的书籍'))
          return
        }
        setBooks({ primary: loadedPrimary, secondary: loadedSecondary })
      } catch (e) {
        if (!cancelled) setLoadError(t('加载书籍失败'))
        console.error('SplitReaderView: load books failed', e)
      }
    })()
    return () => { cancelled = true }
  }, [primary.bookId, secondary.bookId, primary.title, primary.format, primary.filePath, secondary.title, secondary.format, secondary.filePath, t])

  // 2) 每本书一个独立 session（进度/选区/搜索/TTS 归属），卸载时 flush
  useEffect(() => {
    if (!books.primary || !books.secondary) return
    const make = (book: LoadedBook): ReaderSession => createReaderSession({
      book: { id: book.id, title: book.title, format: book.format, filePath: book.filePath },
    })
    const primarySession = make(books.primary)
    const secondarySession = make(books.secondary)
    setSessions({ primary: primarySession, secondary: secondarySession })
    void primarySession.init()
    void secondarySession.init()
    return () => {
      primarySession.dispose()
      secondarySession.dispose()
    }
  }, [books])

  // 3) active pane → 会话 active 标记 + TTS 归属（只有 active 栏能 claimTts）
  useEffect(() => {
    const p = sessions.primary
    const s = sessions.secondary
    if (!p || !s) return
    p.store.getState().setActive(activePane === 'primary')
    s.store.getState().setActive(activePane === 'secondary')
    const active = activePane === 'primary' ? p : s
    const inactive = activePane === 'primary' ? s : p
    inactive.store.getState().releaseTts()
    active.store.getState().claimTts()
    onActivePaneChange?.(activePane)
  }, [activePane, sessions, onActivePaneChange])

  // 3b) 切栏时停掉上一栏的朗读
  useEffect(() => {
    if (!speakingPane) return
    if (speakingPane === activePane) return
    void window.electronAPI.ttsStop().catch(() => {})
    setSpeakingPane(null)
  }, [activePane, speakingPane])

  // 卸载分屏时兜底停止朗读（单栏 ReaderView 的 TtsBar 已卸载，不能留声音）
  useEffect(() => {
    return () => { void window.electronAPI.ttsStop().catch(() => {}) }
  }, [])

  // 朗读当前栏文本：再点一次 = 停止
  const handleToggleSpeak = useCallback(async (pane: SplitPaneKey) => {
    const session = sessions[pane]
    if (!session) return
    if (speakingPane === pane) {
      await window.electronAPI.ttsStop().catch(() => {})
      setSpeakingPane(null)
      return
    }
    await window.electronAPI.ttsStop().catch(() => {})
    const getter = textGettersRef.current[pane]
    const text = (getter ? getter() : '').trim()
    if (!text) return
    setActivePane(pane)
    session.store.getState().setActive(true)
    const other = pane === 'primary' ? sessions.secondary : sessions.primary
    other?.store.getState().setActive(false)
    session.store.getState().claimTts()
    try {
      const ok = await window.electronAPI.ttsSpeak(text)
      if (ok) setSpeakingPane(pane)
    } catch (e) {
      console.error('SplitReaderView: ttsSpeak failed', e)
    }
  }, [sessions, speakingPane])

  // 4) 分隔条拖动
  useEffect(() => {
    const onMove = (event: PointerEvent) => {
      if (!draggingRef.current) return
      const rect = rootRef.current?.getBoundingClientRect()
      if (!rect || rect.width === 0) return
      setRatio(clampRatio((event.clientX - rect.left) / rect.width))
    }
    const onUp = () => {
      if (!draggingRef.current) return
      draggingRef.current = false
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    return () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
    }
  }, [])

  const startDrag = useCallback(() => {
    draggingRef.current = true
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
  }, [])

  const onDividerKeyDown = useCallback((event: React.KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
    event.preventDefault()
    const delta = event.key === 'ArrowLeft' ? -0.02 : 0.02
    setRatio((current) => clampRatio(current + delta))
  }, [])

  const renderPane = (pane: SplitPaneKey) => {
    const book = books[pane]
    const session = sessions[pane]
    if (!book) {
      return <div className={styles.state}>{t('正在加载书籍…')}</div>
    }
    if (!isSplitReadable(book.format)) {
      const reason = splitUnsupportedReason(book.format)
      const reasonText = reason === 'pdf'
        ? t('PDF 暂不支持分屏阅读')
        : reason === 'comic'
          ? t('漫画（CBZ/CBR）暂不支持分屏阅读')
          : t('该格式暂不支持分屏阅读')
      return (
        <div className={styles.state} data-split-unsupported={book.format}>
          <div>
            <p>{reasonText}</p>
            <p className="mt-2 text-xs" style={{ color: 'var(--text-tertiary)' }}>
              {t('目前分屏支持 EPUB 与 TXT；可以在单栏模式继续读这本。')}
            </p>
          </div>
        </div>
      )
    }
    if (!session) {
      return <div className={styles.state}>{t('正在准备阅读…')}</div>
    }
    return (
      <SplitPane
        pane={pane}
        book={book}
        session={session}
        active={activePane === pane}
        onClose={onClose}
        speaking={speakingPane === pane}
        onToggleSpeak={() => { void handleToggleSpeak(pane) }}
        registerText={(getter) => { textGettersRef.current[pane] = getter }}
      />
    )
  }

  return (
    <div
      ref={rootRef}
      className={[styles.root, className].filter(Boolean).join(' ')}
      data-split-view="1"
      data-active-pane={activePane}
    >
      <div
        className={[styles.pane, activePane === 'primary' ? styles.paneActive : ''].filter(Boolean).join(' ')}
        style={widthStyle(ratio)}
        data-split-pane="primary"
        onPointerDownCapture={() => setActivePane('primary')}
      >
        {renderPane('primary')}
      </div>
      <div
        className={styles.divider}
        role="separator"
        aria-orientation="vertical"
        aria-label={t('拖动调整两栏宽度')}
        aria-valuenow={Math.round(ratio * 100)}
        aria-valuemin={Math.round(MIN_RATIO * 100)}
        aria-valuemax={Math.round(MAX_RATIO * 100)}
        tabIndex={0}
        onPointerDown={startDrag}
        onKeyDown={onDividerKeyDown}
        data-split-divider="1"
      />
      <div
        className={[styles.pane, activePane === 'secondary' ? styles.paneActive : ''].filter(Boolean).join(' ')}
        style={widthStyle(1 - ratio)}
        data-split-pane="secondary"
        onPointerDownCapture={() => setActivePane('secondary')}
      >
        {renderPane('secondary')}
      </div>
      {loadError && (
        <div className={styles.state} data-split-error="1">
          <div>
            <p>{loadError}</p>
            {onClose && (
              <button className={styles.controlButton} style={{ marginTop: 12 }} onClick={onClose}>
                {t('退出分屏')}
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

// ── 单个 pane：标题栏 + 内容（EPUB / TXT）──────────────────────────────

function SplitPane({
  pane, book, session, active, onClose, speaking, onToggleSpeak, registerText,
}: {
  pane: SplitPaneKey
  book: LoadedBook
  session: ReaderSession
  active: boolean
  onClose?: () => void
  speaking: boolean
  onToggleSpeak: () => void
  registerText: (getter: () => string) => void
}) {
  const { t } = useTranslation()
  const progress = useReaderSession(session, (state) => state.progress.progress)
  return (
    <div
      style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}
      data-split-active={active ? '1' : '0'}
      data-split-pane-root={pane}
    >
      <div className={styles.header}>
        <span className={styles.title} title={book.title} data-split-title={pane}>{book.title}</span>
        <span className={styles.badge}>{book.format.toUpperCase()}</span>
        <span className={styles.progress} data-split-progress={pane}>{Math.round(progress * 10) / 10}%</span>
        <button
          className={styles.controlButton}
          onClick={(event) => { event.stopPropagation(); onToggleSpeak() }}
          title={speaking ? t('停止朗读') : t('朗读')}
          aria-label={speaking ? t('停止朗读') : t('朗读')}
          aria-pressed={speaking}
          data-split-speak={pane}
        >
          {speaking ? t('停止朗读') : t('朗读')}
        </button>
        {onClose && (
          <button
            className={styles.controlButton}
            onClick={onClose}
            title={t('退出分屏')}
            aria-label={t('退出分屏')}
            data-split-close="1"
          >
            {t('退出分屏')}
          </button>
        )}
      </div>
      <div className={styles.body}>
        {book.format === 'epub' ? (
          <EpubPane book={book} session={session} active={active} registerText={registerText} />
        ) : (
          <TextPane book={book} session={session} active={active} registerText={registerText} />
        )}
      </div>
    </div>
  )
}

// ── EPUB pane（独立 epub.js 实例，独立 CFI/进度）──────────────────────

function EpubPane({ book, session, active, registerText }: { book: LoadedBook; session: ReaderSession; active: boolean; registerText: (getter: () => string) => void }) {
  const { t } = useTranslation()
  const viewerRef = useRef<HTMLDivElement | null>(null)
  const renditionRef = useRef<Rendition | null>(null)
  const epubBookRef = useRef<EpubBook | null>(null)
  const [ready, setReady] = useState(false)
  const [error, setError] = useState('')
  const initialCfiRef = useRef(session.store.getState().progress.cfi)
  const fontSize = useSettingsStore((state) => state.fontSize)
  const fontFamily = useSettingsStore((state) => state.fontFamily)

  // TTS 用：取当前章节（epub.js 当前 contents 的可见文本）
  useEffect(() => {
    registerText(() => {
      try {
        const contents: any = renditionRef.current?.getContents?.()
        const list = Array.isArray(contents) ? contents : [contents]
        const doc = list[0]?.document
        return doc?.body?.innerText || ''
      } catch { return '' }
    })
  }, [registerText])

  useEffect(() => {
    let cancelled = false
    setReady(false)
    setError('')
    setSelectionForPane(session, '')
    void (async () => {
      const content = await readBookFile(book.filePath)
      if (cancelled) return
      if (!content) {
        setError(t('读取文件失败'))
        session.store.getState().setError('file_read_failed')
        return
      }
      const buffer = content.buffer.slice(content.byteOffset, content.byteOffset + content.byteLength) as ArrayBuffer
      const epubBook = ePub(buffer)
      epubBookRef.current = epubBook
      const viewer = viewerRef.current
      if (!viewer) return
      const rendition = epubBook.renderTo(viewer, {
        width: '100%',
        height: '100%',
        spread: 'none',
        flow: 'paginated',
      })
      renditionRef.current = rendition
      // 进度语义与单栏 EpubRenderer 保持一致：按 spine 章节序号算百分比
      // （location.start.percentage 需要 epub.js 生成 locations，单章样本上恒为 0）。
      rendition.on('relocated', (location: any) => {
        const href = location?.start?.href
        let progressPercent = 0
        const items: any[] = Array.isArray((epubBook as any).spine?.spineItems) ? (epubBook as any).spine.spineItems : []
        if (href && items.length > 0) {
          const cleanHref = String(href).split('#')[0]
          const index = items.findIndex((item: any) => {
            const itemHref = String(item?.href || '').split('#')[0]
            const itemCanonical = String(item?.canonical || '').split('#')[0]
            return itemHref === cleanHref || itemCanonical === cleanHref || itemHref === href || itemCanonical === href
          })
          if (index >= 0) progressPercent = Math.round(((index + 1) / items.length) * 1000) / 10
        }
        session.store.getState().setProgress({
          progress: progressPercent,
          cfi: location?.start?.cfi,
        })
      })
      rendition.on('selected', (_cfiRange: string, contents: any) => {
        const selected = contents?.window?.getSelection?.()?.toString?.() || ''
        session.store.getState().setSelection(selected)
      })
      await epubBook.ready
      if (cancelled) return
      await rendition.display(initialCfiRef.current || undefined)
      if (cancelled) return
      setReady(true)
    })().catch((e) => {
      console.error('EpubPane: render failed', e)
      if (!cancelled) {
        setError(t('无法打开 EPUB 文件'))
        session.store.getState().setError('render_failed')
      }
    })
    return () => {
      cancelled = true
      try { renditionRef.current?.destroy() } catch { /* ignore */ }
      try { epubBookRef.current?.destroy() } catch { /* ignore */ }
      renditionRef.current = null
      epubBookRef.current = null
    }
  }, [book.filePath, session, t])

  useEffect(() => {
    if (!ready) return
    try {
      renditionRef.current?.themes.fontSize(String(fontSize) + 'px')
      renditionRef.current?.themes.font(fontFamily)
    } catch { /* ignore */ }
  }, [ready, fontSize, fontFamily])

  const next = useCallback(() => {
    try { renditionRef.current?.next() } catch { /* ignore */ }
  }, [])
  const prev = useCallback(() => {
    try { renditionRef.current?.prev() } catch { /* ignore */ }
  }, [])

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    if (!active) return
    if (event.key === 'ArrowRight' || event.key === 'PageDown') { event.preventDefault(); next() }
    else if (event.key === 'ArrowLeft' || event.key === 'PageUp') { event.preventDefault(); prev() }
  }

  return (
    <div className={styles.epubWrap} tabIndex={0} onKeyDown={onKeyDown} data-split-epub="1">
      {!ready && !error && <div className={styles.state}>{t('正在加载 EPUB…')}</div>}
      {error && <div className={styles.state} data-split-pane-error="1">{error}</div>}
      <div ref={viewerRef} className={styles.epubViewer} />
      {ready && (
        <div className={styles.epubControls}>
          <button className={styles.controlButton} onClick={prev} aria-label={t('上一页')} data-split-prev="1">{t('上一页')}</button>
          <button className={styles.controlButton} onClick={next} aria-label={t('下一页')} data-split-next="1">{t('下一页')}</button>
        </div>
      )}
    </div>
  )
}

// ── TXT pane（独立滚动位置/进度；选区文本只进自己的 session）────────────

function TextPane({ book, session, active, registerText }: { book: LoadedBook; session: ReaderSession; active: boolean; registerText: (getter: () => string) => void }) {
  const { t } = useTranslation()
  const scrollRef = useRef<HTMLDivElement | null>(null)
  const lastUpdateRef = useRef(0)
  const [text, setText] = useState('')
  const [error, setError] = useState('')
  const fontSize = useSettingsStore((state) => state.fontSize)
  const fontFamily = useSettingsStore((state) => state.fontFamily)
  const lineHeight = useSettingsStore((state) => state.lineHeight)
  const textRef = useRef('')
  textRef.current = text

  // TTS 用：整篇 TXT 文本
  useEffect(() => {
    registerText(() => textRef.current)
  }, [registerText])

  useEffect(() => {
    let cancelled = false
    setText('')
    setError('')
    void (async () => {
      const content = await readBookFile(book.filePath)
      if (cancelled) return
      if (!content) {
        setError(t('读取文件失败'))
        session.store.getState().setError('file_read_failed')
        return
      }
      const decoded = new TextDecoder('utf-8').decode(content)
      setText(decoded)
    })()
    return () => { cancelled = true }
  }, [book.filePath, session, t])

  // 恢复上次位置（优先像素 scrollPosition，其次百分比）
  useEffect(() => {
    if (!text) return
    const element = scrollRef.current
    if (!element) return
    const saved = session.store.getState().progress
    if (typeof saved.scrollPosition === 'number' && saved.scrollPosition > 0) {
      element.scrollTop = saved.scrollPosition
    } else if (saved.progress > 0) {
      const max = Math.max(0, element.scrollHeight - element.clientHeight)
      element.scrollTop = (max * saved.progress) / 100
    }
  }, [text, session])

  const onScroll = useCallback(() => {
    const element = scrollRef.current
    if (!element) return
    const now = Date.now()
    if (now - lastUpdateRef.current < 120) return
    lastUpdateRef.current = now
    const max = element.scrollHeight - element.clientHeight
    const percentage = max <= 0 ? 0 : Math.min(100, Math.max(0, (element.scrollTop / max) * 100))
    session.store.getState().setProgress({
      progress: Math.round(percentage * 10) / 10,
      scrollPosition: element.scrollTop,
    })
  }, [session])

  const onMouseUp = useCallback(() => {
    const selected = window.getSelection()?.toString() || ''
    if (selected.trim()) session.store.getState().setSelection(selected.trim())
  }, [session])

  return (
    <div
      ref={scrollRef}
      className={styles.textScroll}
      tabIndex={0}
      onScroll={onScroll}
      onMouseUp={onMouseUp}
      data-split-text="1"
      aria-label={t('分屏阅读文本')}
      style={active ? undefined : { opacity: 0.98 }}
    >
      {error ? (
        <div className={styles.state} data-split-pane-error="1">{error}</div>
      ) : text ? (
        <div
          className={styles.textContent}
          style={{ fontSize: String(fontSize) + 'px', fontFamily, lineHeight }}
        >
          {text}
        </div>
      ) : (
        <div className={styles.state}>{t('正在加载文本…')}</div>
      )}
    </div>
  )
}

function setSelectionForPane(session: ReaderSession, text: string): void {
  if (text) session.store.getState().setSelection(text)
  else session.store.getState().clearSelection()
}

export default SplitReaderView
