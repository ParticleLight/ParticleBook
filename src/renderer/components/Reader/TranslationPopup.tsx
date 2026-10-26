import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { copyText } from '../../utils/download'
import { useReaderAdapter, type ReaderAdapter } from '../../reader'

// ── PB-002-B：阅读器「选中文本 → 翻译」浮层 ──────────────────────────
//
// 后端是 PB-002-A 的 TranslationService（C++），bridge 已提供：
//   translationTranslate(segments, sourceLang, targetLang, context?)
//   → 成功 resolve { ok, code, segments:[{id,text}], ... }
//   → 失败 reject(Error)，Error.message 是机器可读的 code，本组件负责本地化。
// 本组件不新增 bridge、不改 C++；配置来自 SettingsService 的 translation.*，
// Key 在 SecretStore。
//
// 行为：
//   * 选中非空文本（主文档或 EPUB iframe）→ 选区下方浮出「翻译」按钮；
//   * 点按钮 / Ctrl+Shift+T → 调翻译，浮层显示「原文 + 译文」；
//   * 空选区、输入框内选区、点空白处、滚动、Esc → 收起；
//   * 译文可复制；不做「替换原文」（避免破坏正文 DOM/标注锚点，留作后续）；
//   * 浮层固定在视口坐标里，bottomInset 由 ReaderView 传入（有 TTS 条时更大），
//     保证不遮挡朗读条。
//   * PB-002-C：译文按「原文 hash + 目标语言」存 localStorage（上限 200 条 /
//     1 MB，LRU 淘汰）；命中时标注「缓存」且不发网络请求（离线可看）。
//     缓存里只有原文/译文/目标语言/时间戳，没有 Key。

const MAX_SELECTION_CHARS = 4000
const PANEL_WIDTH = 380
const PANEL_MAX_HEIGHT = 320
const MARGIN = 8

// ── PB-002-C：本地译文缓存 ──────────────────────────────────────────
// 只缓存「原文 hash → 译文」，不缓存、不接触 API Key（Key 在 SecretStore，
// 从不经过这里）。命中时完全不发网络请求，所以断网也能看已翻译过的段落。
const TRANSLATION_CACHE_STORAGE_KEY = 'pb.translation.cache.v1'
const TRANSLATION_CACHE_MAX_ENTRIES = 200
const TRANSLATION_CACHE_MAX_BYTES = 1024 * 1024

interface TranslationCacheEntry {
  key: string
  src: string
  dst: string
  tgt: string
  ts: number
}

// 归一化空白，避免同一段文字因换行/空格差异重复缓存。
function normalizeCacheSource(text: string): string {
  return text.replace(/\s+/g, ' ').trim()
}

// FNV-1a 双 32 位（16 hex）：同步、零依赖，不依赖 SubtleCrypto 的安全上下文。
export function translationCacheHash(source: string, target: string): string {
  const text = normalizeCacheSource(source) + '\u0000' + target.trim().toLowerCase()
  let first = 0x811c9dc5
  let second = 0x9e3779b9
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i)
    first = Math.imul(first ^ code, 0x01000193) >>> 0
    second = Math.imul(second ^ (code + i), 0x85ebca6b) >>> 0
    second = (second ^ (second >>> 13)) >>> 0
  }
  return first.toString(16).padStart(8, '0') + second.toString(16).padStart(8, '0')
}

function cacheByteLength(text: string): number {
  try {
    return new TextEncoder().encode(text).length
  } catch {
    return text.length * 2
  }
}

function readCacheEntries(): TranslationCacheEntry[] {
  try {
    const raw = window.localStorage.getItem(TRANSLATION_CACHE_STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as { v?: number; entries?: unknown }
    if (!parsed || parsed.v !== 1 || !Array.isArray(parsed.entries)) return []
    const entries: TranslationCacheEntry[] = []
    for (const item of parsed.entries) {
      if (!item || typeof item !== 'object') continue
      const entry = item as Partial<TranslationCacheEntry>
      if (typeof entry.key !== 'string' || typeof entry.src !== 'string' ||
          typeof entry.dst !== 'string' || typeof entry.tgt !== 'string' ||
          typeof entry.ts !== 'number') continue
      entries.push({ key: entry.key, src: entry.src, dst: entry.dst, tgt: entry.tgt, ts: entry.ts })
    }
    return entries
  } catch {
    return []
  }
}

// 上限 200 条 / 1 MB（JSON 字节数）：超了从最旧一端淘汰。localStorage 写不进去
// （配额满 / 隐私模式）时静默放弃 —— 缓存永远不该影响翻译本身。
function persistCacheEntries(entries: TranslationCacheEntry[]): void {
  let list = entries.slice(0, TRANSLATION_CACHE_MAX_ENTRIES)
  let json = JSON.stringify({ v: 1, entries: list })
  while (list.length > 0 && cacheByteLength(json) > TRANSLATION_CACHE_MAX_BYTES) {
    const keep = Math.floor(list.length * 0.8)
    list = keep > 0 ? list.slice(0, keep) : []
    json = JSON.stringify({ v: 1, entries: list })
  }
  try {
    window.localStorage.setItem(TRANSLATION_CACHE_STORAGE_KEY, json)
  } catch { /* ignore: cache is best-effort */ }
}

// 命中即返回并把它提到最前（LRU）；离线时只有这里能拿到译文。
export function readTranslationCache(source: string, target: string): TranslationCacheEntry | null {
  const key = translationCacheHash(source, target)
  const entries = readCacheEntries()
  const index = entries.findIndex((entry) => entry.key === key)
  if (index < 0) return null
  const hit = entries[index]
  entries.splice(index, 1)
  entries.unshift({ ...hit, ts: Date.now() })
  persistCacheEntries(entries)
  return hit
}

export function writeTranslationCache(source: string, target: string, translation: string): void {
  if (!translation.trim()) return
  const key = translationCacheHash(source, target)
  const entries = readCacheEntries().filter((entry) => entry.key !== key)
  entries.unshift({
    key,
    src: normalizeCacheSource(source),
    dst: translation,
    tgt: target,
    ts: Date.now(),
  })
  persistCacheEntries(entries)
}

export function clearTranslationCache(): number {
  const count = readCacheEntries().length
  try {
    window.localStorage.removeItem(TRANSLATION_CACHE_STORAGE_KEY)
  } catch { /* ignore */ }
  return count
}


// PB-021-F：选区由 ReaderAdapter 统一提供（text + 视口 rect）。原来的
// doc/win/frame/context 字段不再需要：触发按钮位置只用 rect，翻译上下文
// 用空串（translationTranslate 的 context 是可选参数）。
interface SelectionInfo {
  text: string
  context: string
  rect: { left: number; top: number; right: number; bottom: number; width: number; height: number }
}

interface PanelState {
  open: boolean
  loading: boolean
  errorCode: string
  errorText: string
  translation: string
  source: string
  target: string
  cached: boolean  // 译文来自本地缓存（PB-002-C），没有发网络请求
}

interface TranslationPopupProps {
  bottomInset?: number
  onOpenSettings?: () => void
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), Math.max(min, max))
}

// 选区 → 浮层坐标：完全来自 adapter（主文档 / EPUB iframe 都由它归一化）。
function selectionFromAdapter(adapter: ReaderAdapter | null): SelectionInfo | null {
  const selected = adapter?.getSelection()
  if (!selected || !selected.text.trim()) return null
  const text = selected.text.replace(/\s+/g, ' ').trim().slice(0, MAX_SELECTION_CHARS)
  if (!text) return null
  const rect = selected.rect
  const left = rect ? rect.x : Math.max(MARGIN, window.innerWidth / 2 - 40)
  const top = rect ? rect.y : 80
  const width = rect ? rect.width : 0
  const height = rect ? rect.height : 0
  return {
    text,
    context: '',
    rect: { left, top, right: left + width, bottom: top + height, width, height },
  }
}

// 错误码 → 中文文案 key。Settings 页的「测试连接」复用它，保持文案一致。
export function translationErrorMessageKey(code: string): string {
  switch (code) {
  case 'translation_disabled': return '翻译功能未启用（可在设置 → AI 翻译 中开启）'
  case 'missing_api_key': return '还没有配置翻译 API Key（设置 → AI 翻译）'
  case 'translation_not_configured':
  case 'not_configured': return '翻译还没配置好（需要填写 Base URL 与模型）'
  case 'translation_unavailable':
  case 'job_service_unavailable': return '翻译服务暂时不可用'
  case 'invalid_segments':
  case 'empty_text':
  case 'invalid_input': return '选中的文本不可翻译'
  case 'network': return '网络错误，请检查网络或 Base URL'
  case 'timeout': return '请求超时，请稍后重试'
  case 'auth': return 'API Key 无效或没有权限'
  case 'quota': return '额度不足或触发限流，请稍后重试'
  case 'http': return '翻译服务返回了 HTTP 错误'
  case 'provider': return '翻译服务拒绝了请求（模型名或参数可能不对）'
  case 'parse': return '翻译响应解析失败'
  case 'cancelled': return '翻译已取消'
  case 'too_large': return '选中的文本太长，请少选一些'
  default: return '翻译失败'
  }
}

function isConfigError(code: string): boolean {
  return code === 'translation_disabled' || code === 'missing_api_key'
    || code === 'translation_not_configured' || code === 'not_configured'
}

const EMPTY_PANEL: PanelState = {
  open: false, loading: false, errorCode: '', errorText: '', translation: '', source: '', target: '',
  cached: false,
}

export function TranslationPopup({ bottomInset = 120, onOpenSettings }: TranslationPopupProps) {
  const { t } = useTranslation()
  // PB-021-F：选区统一走 ReaderAdapter（EPUB iframe / TXT / HTML 同一入口）。
  const adapter = useReaderAdapter()
  const [selection, setSelection] = useState<SelectionInfo | null>(null)
  const [triggerPos, setTriggerPos] = useState<{ left: number; top: number } | null>(null)
  const [panel, setPanel] = useState<PanelState>(EMPTY_PANEL)
  const [panelPos, setPanelPos] = useState<{ left: number; top: number } | null>(null)
  const [copied, setCopied] = useState(false)
  const selectionRef = useRef<SelectionInfo | null>(null)
  const requestRef = useRef(0)

  const clearSelection = useCallback(() => {
    selectionRef.current = null
    setSelection(null)
    setTriggerPos(null)
  }, [])

  const closePanel = useCallback(() => {
    requestRef.current += 1
    setPanel(EMPTY_PANEL)
    setPanelPos(null)
    setCopied(false)
  }, [])

  // 选中文本 → 在选区下方算「翻译」按钮位置（避开朗读条）
  const refreshSelection = useCallback(() => {
    if (panel.open) return
    const found = selectionFromAdapter(adapter)
    if (!found) { clearSelection(); return }
    found.text = found.text.slice(0, MAX_SELECTION_CHARS)
    selectionRef.current = found
    setSelection(found)
    const maxBottom = window.innerHeight - bottomInset
    let top = found.rect.bottom + 6
    if (top + 32 > maxBottom) top = found.rect.top - 38
    const left = clamp(found.rect.left + Math.min(40, Math.max(0, found.rect.width / 2)), MARGIN, window.innerWidth - 96 - MARGIN)
    setTriggerPos({ left, top: clamp(top, MARGIN, Math.max(MARGIN, maxBottom - 32)) })
  }, [adapter, bottomInset, clearSelection, panel.open])

  const placePanel = useCallback((found: SelectionInfo) => {
    const maxBottom = window.innerHeight - bottomInset
    const left = clamp(found.rect.left, MARGIN, window.innerWidth - PANEL_WIDTH - MARGIN)
    let top = found.rect.bottom + 10
    if (top + PANEL_MAX_HEIGHT > maxBottom) {
      const above = found.rect.top - PANEL_MAX_HEIGHT - 10
      top = above > MARGIN ? above : Math.max(MARGIN, maxBottom - PANEL_MAX_HEIGHT)
    }
    setPanelPos({ left, top: clamp(top, MARGIN, Math.max(MARGIN, maxBottom - 120)) })
  }, [bottomInset])

  const translate = useCallback(async () => {
    const found = selectionRef.current
    if (!found) return
    const source = found.text
    if (!source.trim()) { clearSelection(); return }

    const requestId = requestRef.current + 1
    requestRef.current = requestId
    placePanel(found)
    setTriggerPos(null)
    setCopied(false)

    // 先拿目标语言（本地 settings，一次 IPC）。缓存命中时下面直接返回，
    // 完全不发网络请求 —— 断网也能看已经翻译过的段落。
    let target = 'zh'
    try {
      const settings = await window.electronAPI.getSettings()
      target = settings['translation.targetLanguage'] || 'zh'
    } catch { /* 用默认值 */ }
    if (requestRef.current !== requestId) return

    const cached = readTranslationCache(source, target)
    if (cached) {
      setPanel({
        open: true, loading: false, errorCode: '', errorText: '',
        translation: cached.dst, source, target, cached: true,
      })
      return
    }

    setPanel({ open: true, loading: true, errorCode: '', errorText: '', translation: '', source, target, cached: false })

    try {
      const result = await window.electronAPI.translationTranslate(
        [{ id: 's1', text: source }],
        'auto',
        target,
        found.context,
      )
      if (requestRef.current !== requestId) return
      const translated = result && Array.isArray(result.segments) && result.segments[0]
        ? (result.segments[0].text || '')
        : ''
      if (!translated) {
        setPanel((prev) => prev.open ? { ...prev, loading: false, errorCode: 'parse', errorText: t(translationErrorMessageKey('parse')), target, cached: false } : prev)
        return
      }
      writeTranslationCache(source, target, translated)
      setPanel((prev) => prev.open ? { ...prev, loading: false, translation: translated, target, cached: false } : prev)
    } catch (e) {
      if (requestRef.current !== requestId) return
      const raw = e instanceof Error ? e.message : String(e)
      const code = (raw || 'unknown').trim()
      setPanel((prev) => prev.open ? { ...prev, loading: false, errorCode: code, errorText: t(translationErrorMessageKey(code)), cached: false } : prev)
    }
  }, [clearSelection, placePanel, t])

  // 事件：选区（主文档 + iframe）、点空白、滚动、Esc、快捷键
  useEffect(() => {
    const watched = new WeakSet<Document>()

    const onMouseUp = () => { window.setTimeout(refreshSelection, 20) }
    const onKeyUp = (e: KeyboardEvent) => {
      if (e.key === 'Shift' || e.key.startsWith('Arrow') || e.key === 'Home' || e.key === 'End') {
        window.setTimeout(refreshSelection, 20)
      }
    }
    const onMouseDown = (e: MouseEvent) => {
      const target = e.target as Element | null
      if (target && (target.closest('[data-pb-translate-panel]') || target.closest('[data-pb-translate-trigger]'))) return
      clearSelection()
    }
    const onScroll = () => { clearSelection() }
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        if (panel.open) { e.stopPropagation(); closePanel(); return }
        if (selectionRef.current) {
          // 只是收「翻译」按钮：吞掉 Esc，别让 ReaderView 当成「退出阅读器」
          e.stopPropagation()
          clearSelection()
        }
        return
      }
      if ((e.ctrlKey || e.metaKey) && e.shiftKey && (e.key === 'T' || e.key === 't')) {
        e.preventDefault()
        e.stopPropagation()
        void translate()
      }
    }

    const attach = (doc: Document) => {
      if (watched.has(doc)) return
      watched.add(doc)
      doc.addEventListener('mouseup', onMouseUp)
      doc.addEventListener('keyup', onKeyUp as EventListener)
      doc.addEventListener('keydown', onKeyDown as EventListener)
      doc.addEventListener('mousedown', onMouseDown as EventListener)
    }

    attach(document)
    const scan = () => {
      for (const frame of Array.from(document.querySelectorAll('iframe'))) {
        try { if (frame.contentDocument) attach(frame.contentDocument) } catch { /* cross-origin */ }
      }
    }
    scan()
    const scanner = window.setInterval(scan, 700)

    window.addEventListener('scroll', onScroll, true)
    window.addEventListener('resize', onScroll)

    return () => {
      window.clearInterval(scanner)
      window.removeEventListener('scroll', onScroll, true)
      window.removeEventListener('resize', onScroll)
      document.removeEventListener('mouseup', onMouseUp)
      document.removeEventListener('keyup', onKeyUp as EventListener)
      document.removeEventListener('keydown', onKeyDown as EventListener)
      document.removeEventListener('mousedown', onMouseDown as EventListener)
    }
  }, [clearSelection, closePanel, panel.open, refreshSelection, translate])

  useEffect(() => () => { selectionRef.current = null }, [])

  const speakSource = useCallback(() => {
    if (!panel.source) return
    window.electronAPI.ttsSpeak(panel.source, 'translate-selection').catch(() => {})
  }, [panel.source])

  const copyTranslation = useCallback(() => {
    if (!panel.translation) return
    copyText(panel.translation)
      .then(() => { setCopied(true); window.setTimeout(() => setCopied(false), 1500) })
      .catch(() => {})
  }, [panel.translation])

  return (
    <>
      {triggerPos && selection && !panel.open && (
        <button
          data-pb-translate-trigger="1"
          onMouseDown={(e) => e.preventDefault()}
          onClick={() => { void translate() }}
          className="fixed z-[60] px-2.5 py-1 rounded-full text-xs shadow-lg border transition-transform hover:scale-105"
          style={{
            left: triggerPos.left,
            top: triggerPos.top,
            background: 'var(--reader-sidebar, #1f2937)',
            borderColor: 'var(--reader-border, #374151)',
            color: 'var(--reader-text, #e5e7eb)',
          }}
          title={t('翻译')}
          aria-label={t('翻译')}
        >
          <span className="inline-flex items-center gap-1">
            <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 5h12M9 3v2m1.048 9.5A18.022 18.022 0 016.412 9m6.088 9h7M11 21l5-10 5 10M12.751 5C11.783 10.77 8.07 15.61 3 18.129" />
            </svg>
            {t('翻译')}
          </span>
        </button>
      )}

      {panel.open && panelPos && (
        <div
          data-pb-translate-panel="1"
          className="fixed z-[61] rounded-xl border shadow-2xl overflow-hidden text-sm"
          style={{
            left: panelPos.left,
            top: panelPos.top,
            width: PANEL_WIDTH,
            maxHeight: PANEL_MAX_HEIGHT,
            background: 'var(--reader-sidebar, #1f2937)',
            borderColor: 'var(--reader-border, #374151)',
            color: 'var(--reader-text, #e5e7eb)',
          }}
          onMouseDown={(e) => e.stopPropagation()}
        >
          <div className="flex items-center justify-between px-3 py-2 border-b" style={{ borderColor: 'var(--reader-border, #374151)' }}>
            <span className="text-xs font-medium opacity-80">
              {t('翻译')}{panel.target ? ' → ' + panel.target : ''}
              {panel.cached && (
                <span
                  className="ml-2 px-1.5 py-0.5 rounded text-[10px] align-middle"
                  style={{ background: 'var(--accent)', color: 'var(--bg-primary)' }}
                  title={t('来自本地缓存，可离线查看')}
                >
                  {t('缓存')}
                </span>
              )}
            </span>
            <div className="flex items-center gap-1">
              <button
                onClick={speakSource}
                className="px-2 py-0.5 rounded text-xs opacity-70 hover:opacity-100"
                title={t('朗读')}
                aria-label={t('朗读')}
              >
                {t('朗读')}
              </button>
              {panel.translation && (
                <button
                  onClick={copyTranslation}
                  className="px-2 py-0.5 rounded text-xs opacity-70 hover:opacity-100"
                  title={t('复制译文')}
                  aria-label={t('复制译文')}
                >
                  {copied ? t('已复制') : t('复制译文')}
                </button>
              )}
              <button
                onClick={closePanel}
                className="p-1 rounded opacity-60 hover:opacity-100"
                title={t('关闭翻译')}
                aria-label={t('关闭翻译')}
              >
                <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>
          </div>

          <div className="px-3 py-2 overflow-y-auto" style={{ maxHeight: PANEL_MAX_HEIGHT - 44 }}>
            <p className="text-xs opacity-55 mb-2 line-clamp-3 break-words" title={panel.source}>{panel.source}</p>
            <div className="border-t pt-2" style={{ borderColor: 'var(--reader-border, #374151)' }}>
              {panel.loading && (
                <p className="text-xs opacity-70 flex items-center gap-2 py-1">
                  <span className="animate-spin inline-block w-3 h-3 rounded-full border-2 border-current border-t-transparent" />
                  {t('翻译中…')}
                </p>
              )}
              {!panel.loading && panel.errorText && (
                <div className="py-1">
                  <p className="text-xs text-red-400 break-words">{panel.errorText}</p>
                  {isConfigError(panel.errorCode) && onOpenSettings && (
                    <button
                      onClick={() => { closePanel(); onOpenSettings() }}
                      className="mt-2 px-2 py-1 rounded text-xs border"
                      style={{ borderColor: 'var(--reader-border, #374151)' }}
                    >
                      {t('去设置')}
                    </button>
                  )}
                </div>
              )}
              {!panel.loading && !panel.errorText && panel.translation && (
                <p className="text-sm leading-relaxed whitespace-pre-wrap break-words select-text">{panel.translation}</p>
              )}
            </div>
          </div>
        </div>
      )}
    </>
  )
}


