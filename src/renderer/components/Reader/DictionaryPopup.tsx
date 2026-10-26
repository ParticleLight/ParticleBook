import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'

// ── PB-010-B：本地词典弹层（离线查词）──────────────────────────────
// 工具栏「词典」打开：输入单词查词 / 从当前选区取词；显示音标、释义、词性、
// 考试标签、词形变化；首次使用导入 ECDICT CSV 或 StarDict .ifo。
// 数据全部来自本机 DictionaryService（bridge dictionary:*），不联网。
// 导入在 C++ 侧走 JobService，进度通过 dictionary:progress 事件回报。
interface DictionaryHit {
  word: string
  phonetic: string
  translation: string
  definition: string
  pos: string
  tag: string
  exchange: string
  example: string
  kind: string  // 'exact' | 'prefix' | 'morphology'（bridge 给的是字符串）
  matched: string
  verified: boolean
}

interface LookupResult {
  ok: boolean
  found: boolean
  code: string
  error: string
  query: string
  hits: DictionaryHit[]
}

interface DictionaryStatus {
  ok: boolean
  loaded: boolean
  source: string
  entries: number
  bytes: number
}

interface DictionaryPopupProps {
  open: boolean
  onClose: () => void
}

const EXCHANGE_LABELS: Record<string, string> = {
  p: '过去式',
  d: '过去分词',
  i: '现在分词',
  '3': '第三人称单数',
  s: '复数',
  r: '比较级',
  t: '最高级',
  '0': '原型',
  '1': '词形变化',
}

function readSelectionText(): string {
  try {
    const main = window.getSelection()?.toString().replace(/\s+/g, ' ').trim()
    if (main) return main.slice(0, 200)
  } catch { /* ignore */ }
  for (const frame of Array.from(document.querySelectorAll('iframe'))) {
    try {
      const text = frame.contentWindow?.getSelection()?.toString().replace(/\s+/g, ' ').trim()
      if (text) return text.slice(0, 200)
    } catch { /* cross-origin */ }
  }
  return ''
}

function firstToken(text: string): string {
  const match = text.match(/[A-Za-z][A-Za-z'-]*|[\u4e00-\u9fff]+/)
  return match ? match[0] : text
}

export function DictionaryPopup({ open, onClose }: DictionaryPopupProps) {
  const { t } = useTranslation()
  const [status, setStatus] = useState<DictionaryStatus | null>(null)
  const [word, setWord] = useState('')
  const [result, setResult] = useState<LookupResult | null>(null)
  const [busy, setBusy] = useState(false)
  const [importing, setImporting] = useState(false)
  const [progress, setProgress] = useState(0)
  const [message, setMessage] = useState('')
  const inputRef = useRef<HTMLInputElement | null>(null)

  const refreshStatus = useCallback(async () => {
    try {
      setStatus(await window.electronAPI.dictionaryStatus())
    } catch {
      setStatus(null)
    }
  }, [])

  useEffect(() => {
    if (!open) return
    void refreshStatus()
    const timer = window.setTimeout(() => inputRef.current?.focus(), 50)
    return () => window.clearTimeout(timer)
  }, [open, refreshStatus])

  useEffect(() => {
    if (!open) return
    let unsubscribe: (() => void) | undefined
    try {
      unsubscribe = window.electronAPI.onDictionaryProgress((p) => {
        setProgress(Math.max(0, Math.min(100, p.percent)))
      })
    } catch { /* ignore */ }
    return () => { try { unsubscribe?.() } catch { /* ignore */ } }
  }, [open])

  useEffect(() => {
    if (!open) return
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        onClose()
      }
    }
    document.addEventListener('keydown', onKeyDown, true)
    return () => document.removeEventListener('keydown', onKeyDown, true)
  }, [open, onClose])

  const lookup = useCallback(async (raw: string) => {
    const query = raw.trim()
    if (!query) return
    setBusy(true)
    setMessage('')
    try {
      const lookupResult = await window.electronAPI.dictionaryLookup(query, {
        prefix: true,
        morphology: true,
      })
      setResult(lookupResult)
    } catch (error) {
      const code = error instanceof Error ? error.message : String(error)
      setResult({ ok: false, found: false, code, error: code, query, hits: [] })
    } finally {
      setBusy(false)
    }
  }, [])

  const useSelection = () => {
    const text = readSelectionText()
    if (!text) return
    const token = firstToken(text)
    setWord(token)
    void lookup(token)
  }

  const importDictionary = async () => {
    setImporting(true)
    setProgress(0)
    setMessage('')
    try {
      const imported = await window.electronAPI.dictionaryImport()
      setMessage(t('已导入 {{count}} 个词条', { count: imported.entries }))
      await refreshStatus()
    } catch (error) {
      const code = error instanceof Error ? error.message : String(error)
      if (code !== 'cancelled') setMessage(t('导入失败：{{code}}', { code }))
    } finally {
      setImporting(false)
      setProgress(0)
    }
  }

  const clearDictionary = async () => {
    if (!window.confirm(t('清空本地词库？'))) return
    try {
      await window.electronAPI.dictionaryClear()
      setResult(null)
      setMessage('')
      await refreshStatus()
    } catch (error) {
      const code = error instanceof Error ? error.message : String(error)
      setMessage(t('导入失败：{{code}}', { code }))
    }
  }

  if (!open) return null

  const panelStyle = {
    background: 'var(--reader-sidebar, #1f2937)',
    borderColor: 'var(--reader-border, #374151)',
    color: 'var(--reader-text, #e5e7eb)',
  }
  const loaded = !!(status && status.loaded)
  const hitLabel = (kind: string) =>
    kind === 'prefix' ? t('前缀匹配') : kind === 'morphology' ? t('词形还原') : t('精确匹配')

  return (
    <div className="fixed inset-0 z-[70] flex items-start justify-center pt-16 pointer-events-none" role="dialog" aria-modal="true" aria-label={t('词典')}>
      <div className="pointer-events-auto w-[560px] max-w-[92vw] max-h-[75vh] rounded-xl border shadow-2xl overflow-hidden text-sm" style={panelStyle}>
        <div className="flex items-center justify-between px-4 py-2.5 border-b" style={{ borderColor: 'var(--reader-border, #374151)' }}>
          <div className="flex items-center gap-2 min-w-0">
            <span className="text-xs font-medium">{t('本地词典')}</span>
            <span className="text-[11px] opacity-60 truncate">
              {loaded ? t('词库已加载：{{count}} 个词条', { count: status?.entries ?? 0 }) : t('尚未导入词库')}
            </span>
          </div>
          <button
            onClick={onClose}
            className="p-1 rounded opacity-60 hover:opacity-100"
            title={t('关闭词典')}
            aria-label={t('关闭词典')}
          >
            <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <div className="px-4 py-3 space-y-3 overflow-y-auto" style={{ maxHeight: 'calc(75vh - 44px)' }}>
          <div className="flex items-center gap-2">
            <input
              ref={inputRef}
              value={word}
              onChange={(e) => setWord(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') void lookup(word) }}
              placeholder={t('输入单词后按 Enter 或点「查词」')}
              className="flex-1 px-3 py-2 rounded-lg text-sm border"
              style={{ background: 'var(--reader-bg, #111827)', borderColor: 'var(--reader-border, #374151)', color: 'var(--reader-text, #e5e7eb)' }}
            />
            <button onClick={() => void lookup(word)} disabled={busy || !word.trim()} className="px-3 py-2 rounded-lg text-xs border shrink-0" style={{ borderColor: 'var(--reader-border, #374151)' }}>
              {t('查词')}
            </button>
            <button onClick={useSelection} className="px-3 py-2 rounded-lg text-xs border shrink-0" style={{ borderColor: 'var(--reader-border, #374151)' }} title={t('取选区')}>
              {t('取选区')}
            </button>
          </div>

          <div className="flex items-center gap-2">
            <button onClick={() => void importDictionary()} disabled={importing} className="px-3 py-1.5 rounded-lg text-xs border" style={{ borderColor: 'var(--reader-border, #374151)' }}>
              {t('导入词库')}
            </button>
            <button onClick={() => void clearDictionary()} disabled={importing || !loaded} className="px-3 py-1.5 rounded-lg text-xs border opacity-80" style={{ borderColor: 'var(--reader-border, #374151)' }}>
              {t('清空词库')}
            </button>
            {importing && <span className="text-[11px] opacity-70">{t('正在导入… {{percent}}%', { percent: Math.round(progress) })}</span>}
          </div>

          {message && <p className="text-xs opacity-80 break-all">{message}</p>}
          {!loaded && !importing && (
            <p className="text-xs opacity-60">{t('尚未导入词库，点「导入词库」选择 ECDICT CSV 或 StarDict .ifo')}</p>
          )}

          {result && (
            <div className="border-t pt-2" style={{ borderColor: 'var(--reader-border, #374151)' }}>
              {!result.ok && (
                <p className="text-xs text-red-400 break-words">{result.error || result.code}</p>
              )}
              {result.ok && !result.found && (
                <p className="text-xs opacity-70">{t('未找到该词')}</p>
              )}
              {result.ok && result.found && result.hits.length === 0 && (
                <p className="text-xs opacity-70">{t('未找到该词')}</p>
              )}
              {result.hits.map((hit, index) => (
                <div key={hit.word + '-' + index} className={index === 0 ? '' : 'mt-3 pt-3 border-t'} style={index === 0 ? undefined : { borderColor: 'var(--reader-border, #374151)' }}>
                  <div className="flex items-baseline gap-2 flex-wrap">
                    <span className="text-sm font-semibold break-all">{hit.word}</span>
                    {hit.phonetic && <span className="text-xs opacity-70">{hit.phonetic}</span>}
                    {hit.kind !== 'exact' && (
                      <span className="text-[10px] px-1.5 py-0.5 rounded opacity-80" style={{ background: 'var(--reader-bg, #111827)' }}>
                        {hitLabel(hit.kind)}{hit.kind === 'morphology' && hit.matched && hit.matched !== hit.word ? ' ← ' + hit.matched : ''}
                      </span>
                    )}
                  </div>
                  {(hit.translation || hit.definition) && (
                    <p className="mt-1 whitespace-pre-wrap break-words leading-relaxed">
                      {hit.translation || hit.definition}
                    </p>
                  )}
                  {hit.translation && hit.definition && (
                    <p className="mt-1 text-xs opacity-60 whitespace-pre-wrap break-words">{hit.definition}</p>
                  )}
                  {(hit.pos || hit.tag) && (
                    <p className="mt-1 text-[11px] opacity-70 break-all">
                      {hit.pos && <span>{t('词性')}: {hit.pos}</span>}
                      {hit.pos && hit.tag && <span className="mx-1.5">·</span>}
                      {hit.tag && <span>{t('考试标签')}: {hit.tag}</span>}
                    </p>
                  )}
                  {hit.exchange && (
                    <p className="mt-1 text-[11px] opacity-70 break-all">
                      {t('词形')}: {hit.exchange.split('/').filter(Boolean).map((item) => {
                        const colon = item.indexOf(':')
                        const key = colon < 0 ? item : item.slice(0, colon)
                        const value = colon < 0 ? '' : item.slice(colon + 1)
                        const label = EXCHANGE_LABELS[key]
                        return (label ? t(label) : key) + (value ? ' ' + value : '')
                      }).join(' · ')}
                    </p>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
