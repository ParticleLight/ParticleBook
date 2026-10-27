import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { copyText } from '../../utils/download'
import { useReaderAdapter, type TranslationRequest } from '../../reader'

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
export function translationCacheHash(source: string, target: string, context = ''): string {
  // PB-002-B：同一句原文在不同上下文里译文可能不同（代词指代），context 进 key，
  // 避免把上一段的译文错误复用到这一段。旧缓存（无 context）自然 miss 一次。
  const text = normalizeCacheSource(source) + '\u0000' + target.trim().toLowerCase()
    + '\u0000' + normalizeCacheSource(context).slice(0, 2000)
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
export function readTranslationCache(source: string, target: string, context = ''): TranslationCacheEntry | null {
  const key = translationCacheHash(source, target, context)
  const entries = readCacheEntries()
  const index = entries.findIndex((entry) => entry.key === key)
  if (index < 0) return null
  const hit = entries[index]
  entries.splice(index, 1)
  entries.unshift({ ...hit, ts: Date.now() })
  persistCacheEntries(entries)
  return hit
}

export function writeTranslationCache(source: string, target: string, translation: string, context = ''): void {
  if (!translation.trim()) return
  const key = translationCacheHash(source, target, context)
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
  /** PB-022：由 SelectionToolbar 发起的翻译请求（id 自增，弹层不再自己监听选区）。 */
  request: TranslationRequest | null
  /** 用户关闭面板时回调（ReaderView 用它清掉 request）。 */
  onDismiss: () => void
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), Math.max(min, max))
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

export function TranslationPopup({ bottomInset = 120, onOpenSettings, request, onDismiss }: TranslationPopupProps) {
  const { t } = useTranslation()
  // PB-022：弹层不再监听选区 —— 翻译请求由共享 SelectionToolbar 发起
  // （Ctrl+Shift+T 仍作为快捷键读取 adapter 当前选区）。
  const adapter = useReaderAdapter()
  const [panel, setPanel] = useState<PanelState>(EMPTY_PANEL)
  const [panelPos, setPanelPos] = useState<{ left: number; top: number } | null>(null)
  const [copied, setCopied] = useState(false)
  const requestRef = useRef(0)
  const handledRequestRef = useRef(0)

  const closePanel = useCallback(() => {
    requestRef.current += 1
    setPanel(EMPTY_PANEL)
    setPanelPos(null)
    setCopied(false)
    onDismiss()
  }, [onDismiss])

  const placePanel = useCallback((rect?: { x: number; y: number; width: number; height: number }) => {
    const maxBottom = window.innerHeight - bottomInset
    const left = clamp(rect ? rect.x : window.innerWidth / 2 - PANEL_WIDTH / 2, MARGIN, window.innerWidth - PANEL_WIDTH - MARGIN)
    const anchorBottom = rect ? rect.y + rect.height : Math.min(window.innerHeight * 0.4, Math.max(MARGIN, maxBottom - PANEL_MAX_HEIGHT - 10))
    let top = anchorBottom + 10
    if (top + PANEL_MAX_HEIGHT > maxBottom) {
      const above = (rect ? rect.y : anchorBottom) - PANEL_MAX_HEIGHT - 10
      top = above > MARGIN ? above : Math.max(MARGIN, maxBottom - PANEL_MAX_HEIGHT)
    }
    setPanelPos({ left, top: clamp(top, MARGIN, Math.max(MARGIN, maxBottom - 120)) })
  }, [bottomInset])

  // 实际翻译：SelectionToolbar 请求 / Ctrl+Shift+T 都会走这里。
  const translateText = useCallback(async (
    rawText: string,
    rect?: { x: number; y: number; width: number; height: number },
    rawContext?: string,
  ) => {
    const source = rawText.replace(/\s+/g, ' ').trim().slice(0, MAX_SELECTION_CHARS)
    if (!source) return
    // PB-002-B：前后文窗口也归一化并限长，随请求发给 TranslationService。
    const contextText = (rawContext || '').replace(/\s+/g, ' ').trim().slice(0, 2000)

    const requestId = requestRef.current + 1
    requestRef.current = requestId
    placePanel(rect)
    setCopied(false)

    // 先拿目标语言（本地 settings，一次 IPC）。缓存命中时下面直接返回，
    // 完全不发网络请求 —— 断网也能看已经翻译过的段落。
    let target = 'zh'
    try {
      const settings = await window.electronAPI.getSettings()
      target = settings['translation.targetLanguage'] || 'zh'
    } catch { /* 用默认值 */ }
    if (requestRef.current !== requestId) return

    const cached = readTranslationCache(source, target, contextText)
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
        contextText,
      )
      if (requestRef.current !== requestId) return
      const translated = result && Array.isArray(result.segments) && result.segments[0]
        ? (result.segments[0].text || '')
        : ''
      if (!translated) {
        setPanel((prev) => prev.open ? { ...prev, loading: false, errorCode: 'parse', errorText: t(translationErrorMessageKey('parse')), target, cached: false } : prev)
        return
      }
      writeTranslationCache(source, target, translated, contextText)
      setPanel((prev) => prev.open ? { ...prev, loading: false, translation: translated, target, cached: false } : prev)
    } catch (e) {
      if (requestRef.current !== requestId) return
      const raw = e instanceof Error ? e.message : String(e)
      const code = (raw || 'unknown').trim()
      setPanel((prev) => prev.open ? { ...prev, loading: false, errorCode: code, errorText: t(translationErrorMessageKey(code)), cached: false } : prev)
    }
  }, [placePanel, t])

  // 请求 → 翻译（id 去重，避免同一次请求被重复触发）
  useEffect(() => {
    if (!request || !request.text.trim()) return
    if (request.id === handledRequestRef.current) return
    handledRequestRef.current = request.id
    void translateText(request.text, request.rect, request.context)
  }, [request, translateText])

  // 快捷键：Esc 关面板；Ctrl+Shift+T 翻译 adapter 当前选区（共享工具条之外的入口）。
  // 选区本身的监听已上移到 SelectionToolbar（PB-022），这里不再处理鼠标/滚动。
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && panel.open) {
        e.stopPropagation()
        closePanel()
        return
      }
      if ((e.ctrlKey || e.metaKey) && e.shiftKey && (e.key === 'T' || e.key === 't')) {
        e.preventDefault()
        e.stopPropagation()
        const selected = adapter?.getSelection()
        if (selected && selected.text.trim()) void translateText(selected.text, selected.rect, selected.context)
      }
    }
    document.addEventListener('keydown', onKeyDown, true)
    return () => document.removeEventListener('keydown', onKeyDown, true)
  }, [adapter, closePanel, panel.open, translateText])

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
      {panel.open && panelPos && (
        <div
          data-pb-translate-panel="1"
          className="m3-dialog fixed z-[61] overflow-hidden text-sm"
          style={{
            left: panelPos.left,
            top: panelPos.top,
            width: PANEL_WIDTH,
            maxHeight: PANEL_MAX_HEIGHT,
          }}
          onMouseDown={(e) => e.stopPropagation()}
        >
          <div className="flex items-center justify-between px-4 py-3">
            <span className="m3-title-small">
              {t('翻译')}{panel.target ? ' → ' + panel.target : ''}
              {panel.cached && (
                <span
                  className="m3-badge ml-2 align-middle"
                  title={t('来自本地缓存，可离线查看')}
                >
                  {t('缓存')}
                </span>
              )}
            </span>
            <div className="flex items-center gap-1">
              <button
                onClick={speakSource}
                className="m3-text-button !min-h-8 !px-2"
                title={t('朗读')}
                aria-label={t('朗读')}
              >
                {t('朗读')}
              </button>
              {panel.translation && (
                <button
                  onClick={copyTranslation}
                  className="m3-text-button !min-h-8 !px-2"
                  title={t('复制译文')}
                  aria-label={t('复制译文')}
                >
                  {copied ? t('已复制') : t('复制译文')}
                </button>
              )}
              <button
                onClick={closePanel}
                className="m3-icon-button !w-8 !h-8"
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
            <div className="border-t pt-2" style={{ borderColor: 'var(--md-sys-color-outline-variant)' }}>
              {panel.loading && (
                <p className="text-xs opacity-70 flex items-center gap-2 py-1">
                  <span className="animate-spin inline-block w-3 h-3 rounded-full border-2 border-current border-t-transparent" />
                  {t('翻译中…')}
                </p>
              )}
              {!panel.loading && panel.errorText && (
                <div className="py-1">
                  <p className="m3-body-small break-words" style={{ color: 'var(--md-sys-color-error)' }}>{panel.errorText}</p>
                  {isConfigError(panel.errorCode) && onOpenSettings && (
                    <button
                      onClick={() => { closePanel(); onOpenSettings() }}
                      className="m3-outlined-button is-compact mt-2"
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


