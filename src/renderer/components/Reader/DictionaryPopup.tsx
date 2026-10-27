import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useReaderAdapter, type DictionaryRequest } from '../../reader'
import { useReaderStore } from '../../stores/readerStore'

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
  enabled?: boolean
  source: string
  entries: number
  bytes: number
}

interface DictionaryPopupProps {
  open: boolean
  onClose: () => void
  /** PB-022：SelectionToolbar 发来的选区请求（打开时自动查首词）。 */
  request?: DictionaryRequest | null
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

function firstToken(text: string): string {
  const match = text.match(/[A-Za-z][A-Za-z'-]*|[\u4e00-\u9fff]+/)
  return match ? match[0] : text
}

export function DictionaryPopup({ open, onClose, request }: DictionaryPopupProps) {
  const { t } = useTranslation()
  // PB-021-F：取选区统一走 ReaderAdapter（EPUB iframe / TXT / HTML 同一入口）；
  // readerStore.selection 是 ReaderView 同步的等价快照，作为 adapter 缺失时的兜底。
  const adapter = useReaderAdapter()
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
      let lookupResult = await window.electronAPI.dictionaryLookup(query, {
        prefix: true,
        morphology: true,
      })
      // PB-010: ECDICT is EN→ZH. For a Chinese phrase, try 2-char windows so
      // selecting a sentence still finds a word; first hit wins.
      if (!lookupResult.found && /[\u4e00-\u9fff]/.test(query) && query.length > 1) {
        for (let i = 0; i + 2 <= query.length; i += 1) {
          const part = query.slice(i, i + 2)
          if (!/[\u4e00-\u9fff]/.test(part)) continue
          const partResult = await window.electronAPI.dictionaryLookup(part, { prefix: false, morphology: false })
          if (partResult.found) {
            lookupResult = { ...partResult, query }
            setMessage(t('中文按 2 字词查询：{{word}}', { word: part }))
            break
          }
        }
      }
      setResult(lookupResult)
    } catch (error) {
      const code = error instanceof Error ? error.message : String(error)
      setResult({ ok: false, found: false, code, error: code, query, hits: [] })
    } finally {
      setBusy(false)
    }
  }, [])

  const useSelection = () => {
    const fromAdapter = adapter?.getSelection()?.text ?? ''
    const fromStore = useReaderStore.getState().selection?.text ?? ''
    const text = (fromAdapter || fromStore).replace(/\s+/g, ' ').trim().slice(0, 200)
    if (!text) return
    const token = firstToken(text)
    setWord(token)
    void lookup(token)
  }

  // PB-022：工具条发来的选区请求 → 取首词并查询（id 去重）
  const handledRequestRef = useRef(0)
  useEffect(() => {
    if (!open || !request) return
    if (request.id === handledRequestRef.current) return
    handledRequestRef.current = request.id
    const token = firstToken(request.text)
    if (!token) return
    setWord(token)
    void lookup(token)
  }, [open, request, lookup])

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
    background: 'var(--reader-sidebar)',
    borderColor: 'var(--reader-border)',
    color: 'var(--reader-text)',
  }
  const loaded = !!(status && status.loaded)
  const enabled = status?.enabled !== false

  const toggleEnabled = async () => {
    try {
      const res = await window.electronAPI.dictionarySetEnabled(!enabled)
      if (res?.ok) await refreshStatus()
    } catch { /* ignore */ }
  }
  const hitLabel = (kind: string) =>
    kind === 'prefix' ? t('前缀匹配') : kind === 'morphology' ? t('词形还原') : t('精确匹配')

  return (
    <div className="fixed inset-0 z-[70] flex items-start justify-center pt-16 pointer-events-none" role="dialog" aria-modal="true" aria-label={t('词典')}>
      <div className="pointer-events-auto w-[560px] max-w-[92vw] max-h-[75vh] rounded-xl border shadow-2xl overflow-hidden text-sm" style={panelStyle}>
        <div className="flex items-center justify-between px-4 py-2.5 border-b" style={{ borderColor: 'var(--reader-border)' }}>
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
              style={{ background: 'var(--reader-bg)', borderColor: 'var(--reader-border)', color: 'var(--reader-text)' }}
            />
            <button onClick={() => void lookup(word)} disabled={busy || !word.trim()} className="px-3 py-2 rounded-lg text-xs border shrink-0" style={{ borderColor: 'var(--reader-border)' }}>
              {t('查词')}
            </button>
            <button onClick={useSelection} className="px-3 py-2 rounded-lg text-xs border shrink-0" style={{ borderColor: 'var(--reader-border)' }} title={t('取选区')}>
              {t('取选区')}
            </button>
          </div>

          <div className="flex items-center gap-2">
            <button onClick={() => void importDictionary()} disabled={importing} className="px-3 py-1.5 rounded-lg text-xs border" style={{ borderColor: 'var(--reader-border)' }}>
              {t('导入词库')}
            </button>
            <button onClick={() => void clearDictionary()} disabled={importing || !loaded} className="px-3 py-1.5 rounded-lg text-xs border opacity-80" style={{ borderColor: 'var(--reader-border)' }}>
              {t('清空词库')}
            </button>
            {loaded && (
              <button onClick={() => void toggleEnabled()} disabled={importing} className="px-3 py-1.5 rounded-lg text-xs border opacity-80" style={{ borderColor: 'var(--reader-border)' }}>
                {enabled ? t('禁用词典') : t('启用词典')}
              </button>
            )}
            {importing && <span className="text-[11px] opacity-70">{t('正在导入… {{percent}}%', { percent: Math.round(progress) })}</span>}
          </div>

          {message && <p className="text-xs opacity-80 break-all">{message}</p>}
          {!loaded && !importing && (
            <p className="text-xs opacity-60">{t('尚未导入词库，点「导入词库」选择 ECDICT CSV 或 StarDict .ifo')}</p>
          )}

          {result && (
            <div className="border-t pt-2" style={{ borderColor: 'var(--reader-border)' }}>
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
                <div key={hit.word + '-' + index} className={index === 0 ? '' : 'mt-3 pt-3 border-t'} style={index === 0 ? undefined : { borderColor: 'var(--reader-border)' }}>
                  <div className="flex items-baseline gap-2 flex-wrap">
                    <span className="text-sm font-semibold break-all">{hit.word}</span>
                    {hit.phonetic && <span className="text-xs opacity-70">{hit.phonetic}</span>}
                    {hit.kind !== 'exact' && (
                      <span className="text-[10px] px-1.5 py-0.5 rounded opacity-80" style={{ background: 'var(--reader-bg)' }}>
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
