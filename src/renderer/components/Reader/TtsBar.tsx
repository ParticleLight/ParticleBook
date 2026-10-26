import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useReaderStore } from '../../stores/readerStore'
import { useSettingsStore } from '../../stores/settingsStore'

// ── PB-001-B：阅读器内的 TTS 控制条 ──────────────────────────────────
//
// 后端是 PB-001-A 的 SapiTts（C++），bridge 已经提供：
//   getTtsVoices / ttsSpeak(text,tag,flush) / ttsPause / ttsResume / ttsStop /
//   ttsStatus / ttsSetVoice / ttsSetRate / ttsSetVolume / onTtsEvent
// 本组件只做 UI + 取文本 + 高亮，不新增 bridge、不改 C++。
//
// 朗读文本来源（优先级）：
//   1) 当前选区（主文档或 EPUB iframe 里的选区）—— 用户选中什么读什么；
//   2) 当前可见正文：
//        epub                       → epub.js 的 iframe body（从当前滚动位置开始）
//        txt/mobi/fb2/html/markdown → 渲染器统一的 .reader-content 容器
//        pdf                        → pdfOpen + pdfExtractText 取「当前页」文本
//        cbz/cbr                   → 明确禁用（纯图片，没有可读文本）
//   3) 一次最多朗读 MAX_SPEAK_CHARS 字符（从当前可见位置开始），避免把整本书
//      一次性塞进 SAPI 队列。
//
// 高亮：把正文按「文本节点」切成 segments（offset ↔ DOM 节点一一对应），按句子
// 切分后逐句 ttsSpeak（tag = 句子序号），收到 sentence/word 事件时用
// CSS Custom Highlight API 高亮当前句，必要时 scrollIntoView。
//   * 用事件里的 charOffset（UTF-16 code unit）而不是 byteOffset：DOM/JS 字符串
//     索引就是 UTF-16，charOffset 可直接用于 Range；byteOffset 是给非 JS 侧用的。
//   * 选区模式下没有 offset→DOM 映射，所以不画高亮（只在条上显示当前句）。
//
// 生命周期：组件卸载（关条 / 关书 / 切书）一定 ttsStop。
//
// PB-001-C 睡眠定时：5/15/30/60 分钟 + 「到本章结束」。倒计时按 Date.now()
// 计算（不依赖 interval 累加，系统休眠/掉帧都不会漂），到点 ttsStop 并清除
// 定时器；手动停止 / 关闭朗读条 / 切书（组件卸载）都会清掉定时器。
// 「到本章结束」= 读完本次加载的这段文本就停（本实现不会自动续读下一章），
// 读完时在条上给一句提示。

const MAX_SPEAK_CHARS = 8000
const HIGHLIGHT_NAME = 'pb-tts'
const HIGHLIGHT_STYLE_ID = 'pb-tts-style'
const HIGHLIGHT_CSS = '::highlight(pb-tts){background-color:rgba(99,102,241,0.32);}'

interface TextSegment {
  node: Text
  start: number
  end: number
}

// 一段「可朗读文本」及其在 DOM 里的位置映射。
interface SpokenDoc {
  text: string              // 真正送去朗读的文本（已按可见位置/长度裁剪）
  baseOffset: number        // 在完整正文里的起始偏移
  segments: TextSegment[]   // 完整正文的节点区间（绝对偏移）
  doc: Document | null      // segments 所属文档（主文档或 iframe 文档）
  win: Window | null
  source: 'selection' | 'document'
}

interface Sentence {
  text: string
  start: number             // 相对于 SpokenDoc.text
}

interface TtsBarProps {
  bookId: number
  filePath: string
  format: string
  onClose: () => void
}

type Phase = 'idle' | 'playing' | 'paused'

// 睡眠定时：off / 分钟数 / 到本章结束
type SleepMode = 'off' | '5' | '15' | '30' | '60' | 'chapter'
const SLEEP_MINUTES = [5, 15, 30, 60] as const

function formatCountdown(milliseconds: number): string {
  const total = Math.max(0, Math.ceil(milliseconds / 1000))
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  return (minutes < 10 ? '0' : '') + minutes + ':' + (seconds < 10 ? '0' : '') + seconds
}

// ── DOM 文本抽取 ────────────────────────────────────────────────────

function isVisibleElement(el: Element): boolean {
  try {
    const view = el.ownerDocument.defaultView
    if (!view) return false
    const style = view.getComputedStyle(el)
    if (!style) return true
    if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return false
    return true
  } catch {
    return true
  }
}

function collectSegments(root: Node, doc: Document): TextSegment[] {
  const segments: TextSegment[] = []
  let total = 0
  try {
    const walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT)
    let node = walker.nextNode() as Text | null
    while (node) {
      const parent = node.parentElement
      if (parent && isVisibleElement(parent)) {
        const data = node.data || ''
        if (data) {
          segments.push({ node, start: total, end: total + data.length })
          total += data.length
        }
      }
      node = walker.nextNode() as Text | null
    }
  } catch {
    // 跨域 / 文档已卸载：忽略
  }
  return segments
}

function textOfSegments(segments: TextSegment[]): string {
  let text = ''
  for (const seg of segments) text += seg.node.data
  return text
}

// 第一个「仍在可视区域里」的文本节点偏移（从用户当前看到的位置开始读）。
function firstVisibleOffset(segments: TextSegment[]): number {
  for (const seg of segments) {
    try {
      const range = seg.node.ownerDocument.createRange()
      range.selectNodeContents(seg.node)
      const rect = range.getBoundingClientRect()
      if (rect.bottom > 8) return seg.start
    } catch {
      return seg.start
    }
  }
  return 0
}

function readSelection(): { text: string; doc: Document | null; win: Window | null } | null {
  try {
    const text = window.getSelection()?.toString().trim()
    if (text) return { text, doc: document, win: window }
  } catch { /* ignore */ }
  for (const frame of Array.from(document.querySelectorAll('iframe'))) {
    try {
      const win = frame.contentWindow
      const text = win?.getSelection()?.toString().trim()
      if (text) return { text, doc: win?.document ?? null, win: win ?? null }
    } catch { /* cross-origin iframe */ }
  }
  return null
}

// ── 句子切分 ────────────────────────────────────────────────────────

// 中英文句子切分：中文看 。！？；… 与换行；英文看 . ! ? 后面跟空白/结尾。
// 之所以要单独处理英文句号：中文标点正则里没有 ASCII '.'，而 PDF / 英文书里
// 全是 "xxx. Yyy."，不切分的话整页会当成一句（句子事件只有 1 条，进度显示 1/1）。
// 数字里的点（3.14 / 1.2.3）不切。
const SENTENCE_TERMINATORS = '。！？!?；;…'
const TRAILING_QUOTES = '"”』’'

function splitSentences(text: string): Sentence[] {
  const out: Sentence[] = []

  const pushPiece = (from: number, to: number) => {
    const raw = text.slice(from, to)
    const trimmed = raw.trim()
    if (!trimmed) return
    const leading = raw.length - raw.replace(/^\s+/, '').length
    const start = from + leading
    // 没有标点的超长片段（整段没句号）按 300 字硬切，避免一次朗读太久无法定位
    if (trimmed.length > 300) {
      for (let offset = 0; offset < trimmed.length; offset += 300) {
        out.push({ text: trimmed.slice(offset, offset + 300), start: start + offset })
      }
    } else {
      out.push({ text: trimmed, start })
    }
  }

  let start = 0
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    let isEnd = SENTENCE_TERMINATORS.includes(c) || c === '\n'
    if (!isEnd && c === '.') {
      const next = text[i + 1]
      const prev = text[i - 1] || ''
      const nextIsBreak = next === undefined || /\s/.test(next) || TRAILING_QUOTES.includes(next)
      if (nextIsBreak && !/[0-9]/.test(prev)) isEnd = true
    }
    if (!isEnd) continue

    let end = i + 1
    // 吸收连续终止符与收尾引号，避免出现 "。。" / "。"" 这样的碎片
    while (end < text.length && (SENTENCE_TERMINATORS.includes(text[end]) || TRAILING_QUOTES.includes(text[end]))) end++
    pushPiece(start, end)
    start = end
    i = end - 1
  }
  if (start < text.length) pushPiece(start, text.length)
  return out
}

// ── Range / Highlight 工具 ──────────────────────────────────────────

function pointAt(segments: TextSegment[], offset: number): { node: Text; offset: number } | null {
  let lo = 0
  let hi = segments.length - 1
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    const seg = segments[mid]
    if (offset < seg.start) hi = mid - 1
    else if (offset >= seg.end) lo = mid + 1
    else return { node: seg.node, offset: offset - seg.start }
  }
  return null
}

function buildRange(doc: Document, segments: TextSegment[], start: number, end: number): Range | null {
  const from = pointAt(segments, start)
  const to = pointAt(segments, Math.max(start, end - 1))
  if (!from || !to) return null
  try {
    const range = doc.createRange()
    range.setStart(from.node, from.offset)
    range.setEnd(to.node, to.offset + 1)
    return range
  } catch {
    return null
  }
}

function ensureHighlightStyle(doc: Document) {
  try {
    if (doc.getElementById(HIGHLIGHT_STYLE_ID)) return
    const style = doc.createElement('style')
    style.id = HIGHLIGHT_STYLE_ID
    style.textContent = HIGHLIGHT_CSS
    ;(doc.head || doc.documentElement).appendChild(style)
  } catch { /* ignore */ }
}

function clearHighlightsIn(win: Window | null) {
  try {
    const highlights = (win as any)?.CSS?.highlights
    if (highlights) highlights.delete(HIGHLIGHT_NAME)
  } catch { /* ignore */ }
}

function highlightRange(doc: Document | null, win: Window | null, segments: TextSegment[], start: number, end: number) {
  if (!doc || !win || !segments.length) return
  try {
    const HighlightCtor = (win as any).Highlight
    const highlights = (win as any).CSS?.highlights
    if (!HighlightCtor || !highlights) return
    const range = buildRange(doc, segments, start, end)
    if (!range) return
    ensureHighlightStyle(doc)
    highlights.set(HIGHLIGHT_NAME, new HighlightCtor(range))
    // 让当前句留在视口内（EPUB 分页模式下 scrollIntoView 可能无效，忽略即可）
    const rect = range.getBoundingClientRect()
    const viewHeight = win.innerHeight || 600
    if (rect.height > 0 && (rect.top < 60 || rect.bottom > viewHeight - 160)) {
      const el = range.startContainer.parentElement
      el?.scrollIntoView({ block: 'center', behavior: 'smooth' })
    }
  } catch { /* ignore */ }
}

// ── 组件 ────────────────────────────────────────────────────────────

export function TtsBar({ bookId, filePath, format, onClose }: TtsBarProps) {
  const { t } = useTranslation()
  const pdfPage = useReaderStore((s) => s.progress.page)

  const [voices, setVoices] = useState<Array<{ id: string; name: string; language: string; gender: string; oneCore: boolean }>>([])
  const [voiceId, setVoiceId] = useState('')
  const [rate, setRate] = useState(0)
  const [phase, setPhase] = useState<Phase>('idle')
  const [sentences, setSentences] = useState<string[]>([])
  const [current, setCurrent] = useState(-1)
  const [error, setError] = useState('')
  const [hint, setHint] = useState('')
  const [busy, setBusy] = useState(false)
  // PB-001-C 睡眠定时
  const [sleepMode, setSleepMode] = useState<SleepMode>('off')
  const [sleepLeft, setSleepLeft] = useState('')

  const activeRef = useRef(false)
  // 用户「想暂停」的意图。SapiTts（PB-001-A）在句子开始事件里会清 paused 标志，
  // 因此如果用户在第一个 started 事件到达之前就点了暂停，暂停会被下一句的
  // started 冲掉（后端行为，不在本任务写 scope）。这里在收到 started 后按意图
  // 再补一次 ttsPause，保证 UI 与后端最终一致；后端修好后这行是幂等的。
  const wantPausedRef = useRef(false)
  const sessionRef = useRef(0)
  const sentencesRef = useRef<Sentence[]>([])
  const spokenRef = useRef<SpokenDoc | null>(null)
  const voiceIdRef = useRef('')
  const ratePersistTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const sleepModeRef = useRef<SleepMode>('off')

  const unsupported = format === 'cbz' || format === 'cbr'

  const cleanupHighlight = useCallback(() => {
    clearHighlightsIn(window)
    for (const frame of Array.from(document.querySelectorAll('iframe'))) {
      try {
        clearHighlightsIn(frame.contentWindow)
      } catch { /* ignore */ }
    }
  }, [])

  // 清掉睡眠定时（手动停止 / 关闭条 / 切书 / 朗读自然结束都走这里）
  const clearSleepTimer = useCallback(() => {
    setSleepMode('off')
    setSleepLeft('')
  }, [])

  // 取文本：选区 > 可见正文（按格式）
  const extractSpokenText = useCallback(async (): Promise<SpokenDoc | null> => {
    const selection = readSelection()
    if (selection) {
      const text = selection.text.slice(0, MAX_SPEAK_CHARS)
      return { text, baseOffset: 0, segments: [], doc: selection.doc, win: selection.win, source: 'selection' }
    }

    if (format === 'pdf') {
      const page = Math.max(1, pdfPage || 1)
      let docId: number | null = null
      try {
        const opened = await window.electronAPI.pdfOpen(filePath)
        if (!opened) return null
        docId = opened.id
        const text = await window.electronAPI.pdfExtractText(docId)
        if (!text || !Array.isArray(text.pages) || text.pages.length === 0) return null
        // PdfService 的 pageNum 是 0 基；readerStore.progress.page 是 1 基
        const wanted = page - 1
        const exact = text.pages.find((p) => p.pageNum === wanted)
        const fallback = text.pages[Math.min(wanted, text.pages.length - 1)]
        const pageText = (exact?.text || fallback?.text || '').replace(/\s+/g, ' ').trim()
        if (!pageText) return null
        return { text: pageText.slice(0, MAX_SPEAK_CHARS), baseOffset: 0, segments: [], doc: null, win: null, source: 'document' }
      } catch {
        return null
      } finally {
        if (docId !== null) { try { await window.electronAPI.pdfClose(docId) } catch { /* ignore */ } }
      }
    }

    // EPUB：epub.js 渲染在 iframe 里
    if (format === 'epub') {
      for (const frame of Array.from(document.querySelectorAll('iframe'))) {
        try {
          const doc = frame.contentDocument
          if (!doc?.body) continue
          const segments = collectSegments(doc.body, doc)
          if (!segments.length) continue
          const full = textOfSegments(segments)
          const base = firstVisibleOffset(segments)
          const text = full.slice(base, base + MAX_SPEAK_CHARS)
          if (!text.trim()) continue
          return { text, baseOffset: base, segments, doc, win: frame.contentWindow, source: 'document' }
        } catch { /* cross-origin */ }
      }
      return null
    }

    // TXT / MOBI / FB2 / HTML / Markdown：渲染器统一用 .reader-content
    const root = document.querySelector('.reader-content')
    if (!root) return null
    try {
      const segments = collectSegments(root, document)
      if (!segments.length) return null
      const full = textOfSegments(segments)
      const base = firstVisibleOffset(segments)
      const text = full.slice(base, base + MAX_SPEAK_CHARS)
      if (!text.trim()) return null
      return { text, baseOffset: base, segments, doc: document, win: window, source: 'document' }
    } catch {
      return null
    }
  }, [format, filePath, pdfPage])

  const highlightSentence = useCallback((index: number, charOffset = 0, charLength = 0) => {
    const spoken = spokenRef.current
    const list = sentencesRef.current
    if (!spoken || !spoken.segments.length || index < 0 || index >= list.length) return
    const sentence = list[index]
    const start = spoken.baseOffset + sentence.start + Math.max(0, charOffset)
    const length = charLength > 0 ? charLength : sentence.text.length
    highlightRange(spoken.doc, spoken.win, spoken.segments, start, start + length)
  }, [])

  // 初始化：音色列表 + 恢复上次的音色/语速
  useEffect(() => {
    let disposed = false
    ;(async () => {
      try {
        const [list, status] = await Promise.all([
          window.electronAPI.getTtsVoices(),
          window.electronAPI.ttsStatus().catch(() => null),
        ])
        if (disposed) return
        setVoices(list)
        const settings = useSettingsStore.getState()
        let saved = list.find((v) => v.id === settings.ttsVoiceId)?.id ?? ''
        if (!saved && status?.voiceId && list.some((v) => v.id === status.voiceId)) saved = status.voiceId
        if (!saved) saved = list[0]?.id ?? ''
        setVoiceId(saved)
        voiceIdRef.current = saved
        if (saved) await window.electronAPI.ttsSetVoice(saved).catch(() => {})
        const restoredRate = typeof settings.ttsRate === 'number' ? settings.ttsRate : (status?.rate ?? 0)
        setRate(restoredRate)
        await window.electronAPI.ttsSetRate(restoredRate).catch(() => {})
        if (status?.speaking) setPhase(status.paused ? 'paused' : 'playing')
      } catch (e) {
        if (!disposed) setError(e instanceof Error ? e.message : String(e))
      }
    })()
    return () => { disposed = true }
  }, [])

  // 卸载（关条 / 关书 / 切书）一定停
  useEffect(() => {
    return () => {
      activeRef.current = false
      sessionRef.current += 1
      cleanupHighlight()
      window.electronAPI.ttsStop().catch(() => {})
    }
  }, [cleanupHighlight])

  // 事件：状态机 + 句子高亮
  useEffect(() => {
    const off = window.electronAPI.onTtsEvent((ev) => {
      if (!activeRef.current) return
      const index = Number(ev.tag)
      switch (ev.type) {
        case 'started':
          if (Number.isFinite(index)) { setCurrent(index); highlightSentence(index) }
          if (wantPausedRef.current) {
            setPhase('paused')
            void window.electronAPI.ttsPause().catch(() => {})
          } else {
            setPhase('playing')
          }
          break
        case 'sentence':
          if (Number.isFinite(index)) { setCurrent(index); highlightSentence(index) }
          break
        case 'word':
          if (Number.isFinite(index)) { setCurrent(index); highlightSentence(index, ev.charOffset, ev.charLength) }
          break
        case 'ended':
          if (Number.isFinite(index) && index >= sentencesRef.current.length - 1) {
            activeRef.current = false
            wantPausedRef.current = false
            setPhase('idle')
            setCurrent(-1)
            cleanupHighlight()
            // 自然读完：定时器没用了；「到本章结束」给一句提示
            const chapterMode = sleepModeRef.current === 'chapter'
            clearSleepTimer()
            if (chapterMode) setHint(t('已读完本章，朗读已停止'))
          }
          break
        case 'error':
          activeRef.current = false
          wantPausedRef.current = false
          setPhase('idle')
          setCurrent(-1)
          cleanupHighlight()
          clearSleepTimer()
          setError(ev.error ? t('朗读失败：{{error}}', { error: ev.error }) : t('朗读失败'))
          break
        default:
          break
      }
    })
    return off
  }, [cleanupHighlight, clearSleepTimer, highlightSentence, t])

  const stop = useCallback(async () => {
    activeRef.current = false
    wantPausedRef.current = false
    sessionRef.current += 1
    setPhase('idle')
    setCurrent(-1)
    cleanupHighlight()
    setBusy(false)
    // 手动停止也清睡眠定时（定时器只为「正在朗读」服务）
    clearSleepTimer()
    try { await window.electronAPI.ttsStop() } catch { /* ignore */ }
  }, [cleanupHighlight, clearSleepTimer])

  // 睡眠定时：同步 ref（事件回调里要读当前值）+ 倒计时
  useEffect(() => {
    sleepModeRef.current = sleepMode
  }, [sleepMode])

  useEffect(() => {
    if (sleepMode === 'off' || sleepMode === 'chapter') {
      setSleepLeft('')
      return
    }
    const minutes = Number(sleepMode)
    if (!Number.isFinite(minutes) || minutes <= 0) return
    // 按绝对截止时间算，而不是累加 interval —— 窗口最小化/系统睡眠恢复后不会漂
    const deadline = Date.now() + minutes * 60000
    const tick = () => {
      const left = deadline - Date.now()
      if (left <= 0) {
        setSleepMode('off')
        setSleepLeft('')
        setHint(t('睡眠定时已到，朗读已停止'))
        void stop()
        return
      }
      setSleepLeft(formatCountdown(left))
    }
    tick()
    const id = setInterval(tick, 1000)
    return () => clearInterval(id)
  }, [sleepMode, stop, t])

  const startReading = useCallback(async () => {
    if (busy) return
    setBusy(true)
    setError('')
    setHint('')
    try {
      if (unsupported) { setError(t('这本书暂不支持朗读')); return }
      if (!voices.length) { setError(t('没有可用的语音，请在 Windows 设置里安装语音包')); return }
      if (voiceIdRef.current) await window.electronAPI.ttsSetVoice(voiceIdRef.current).catch(() => {})
      await window.electronAPI.ttsSetRate(rate).catch(() => {})
      await window.electronAPI.ttsStop().catch(() => {})

      const spoken = await extractSpokenText()
      if (!spoken) {
        setError(format === 'pdf' ? t('这一页没有可朗读的文本（可能是扫描版）') : t('没有找到可朗读的文本'))
        return
      }
      const list = splitSentences(spoken.text)
      if (!list.length) { setError(t('没有找到可朗读的文本')); return }

      spokenRef.current = spoken
      sentencesRef.current = list
      setSentences(list.map((s) => s.text))
      setCurrent(0)
      setPhase('playing')
      wantPausedRef.current = false
      activeRef.current = true
      const session = ++sessionRef.current

      const first = await window.electronAPI.ttsSpeak(list[0].text, '0', true)
      if (!first) {
        activeRef.current = false
        setPhase('idle')
        setError(t('朗读失败（没有可用音色或被系统拒绝）'))
        return
      }
      for (let i = 1; i < list.length; i++) {
        if (sessionRef.current !== session || !activeRef.current) break
        void window.electronAPI.ttsSpeak(list[i].text, String(i), false).catch(() => {})
      }
      highlightSentence(0)
    } finally {
      setBusy(false)
    }
  }, [busy, extractSpokenText, format, highlightSentence, rate, t, unsupported, voices.length])

  const pause = useCallback(async () => {
    const ok = await window.electronAPI.ttsPause().catch(() => false)
    if (ok) {
      wantPausedRef.current = true
      setPhase('paused')
      setError('')
    } else {
      setError(t('暂停失败'))
    }
  }, [t])

  const resume = useCallback(async () => {
    const ok = await window.electronAPI.ttsResume().catch(() => false)
    if (ok) {
      wantPausedRef.current = false
      setPhase('playing')
      setError('')
    } else {
      setError(t('继续失败'))
    }
  }, [t])

  const handleVoiceChange = useCallback((id: string) => {
    setVoiceId(id)
    voiceIdRef.current = id
    useSettingsStore.getState().setTtsVoiceId(id)
    window.electronAPI.ttsSetVoice(id).catch(() => setError(t('切换音色失败')))
  }, [t])

  const handleRateChange = useCallback((value: number) => {
    setRate(value)
    window.electronAPI.ttsSetRate(value).catch(() => {})
    if (ratePersistTimer.current) clearTimeout(ratePersistTimer.current)
    ratePersistTimer.current = setTimeout(() => {
      useSettingsStore.getState().setTtsRate(value)
    }, 300)
  }, [])

  const handleClose = useCallback(() => {
    void stop()
    onClose()
  }, [onClose, stop])

  const currentSentence = current >= 0 ? sentences[current] : ''
  const progressText = sentences.length ? (current + 1) + '/' + sentences.length : ''

  return (
    <div className="max-w-3xl mx-auto mb-2 px-3 py-2 rounded-xl border flex items-center gap-2 text-sm"
         data-tts-bar="1"
         data-book-id={bookId}
         style={{ background: 'var(--reader-sidebar)', borderColor: 'var(--reader-border)', color: 'var(--reader-text)' }}>
      {/* 播放 / 暂停 / 继续 */}
      {phase !== 'playing' ? (
        <button
          onClick={() => { void (phase === 'paused' ? resume() : startReading()) }}
          disabled={busy || unsupported || !voices.length}
          className="p-2 rounded-lg transition-colors hover:bg-[var(--reader-bg)] disabled:opacity-40"
          title={phase === 'paused' ? t('继续朗读') : t('开始朗读')}
          aria-label={phase === 'paused' ? t('继续朗读') : t('开始朗读')}
        >
          <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24"><path d="M8 5v14l11-7z" /></svg>
        </button>
      ) : (
        <button
          onClick={() => { void pause() }}
          className="p-2 rounded-lg transition-colors hover:bg-[var(--reader-bg)]"
          title={t('暂停朗读')}
          aria-label={t('暂停朗读')}
        >
          <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24"><path d="M6 5h4v14H6zM14 5h4v14h-4z" /></svg>
        </button>
      )}

      {/* 停止 */}
      <button
        onClick={() => { void stop() }}
        disabled={phase === 'idle'}
        className="p-2 rounded-lg transition-colors hover:bg-[var(--reader-bg)] disabled:opacity-30"
        title={t('停止朗读')}
        aria-label={t('停止朗读')}
      >
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="1" /></svg>
      </button>

      {/* 当前句 / 提示 / 错误 */}
      <div className="flex-1 min-w-0 px-1">
        {error ? (
          <span className="text-red-400 truncate block" title={error}>{error}</span>
        ) : currentSentence ? (
          <span className="truncate block opacity-90" title={currentSentence}>
            <span className="opacity-60 mr-2">{progressText}</span>
            {currentSentence}
          </span>
        ) : (
          <span className="opacity-60 truncate block">
            {unsupported ? t('这本书暂不支持朗读') : (hint || t('点击播放开始朗读（优先朗读选中文字）'))}
          </span>
        )}
      </div>

      {/* 语速 */}
      <div className="hidden sm:flex items-center gap-1 shrink-0" title={t('语速')}>
        <span className="text-xs opacity-60">{t('语速')}</span>
        <input
          type="range"
          min={-10}
          max={10}
          step={1}
          value={rate}
          onChange={(e) => handleRateChange(Number(e.target.value))}
          className="w-20 h-1 rounded-lg appearance-none cursor-pointer"
          style={{ accentColor: 'var(--reader-accent)' }}
          aria-label={t('语速')}
        />
        <span className="text-xs w-6 text-center tabular-nums">{rate > 0 ? '+' + rate : rate}</span>
      </div>

      {/* 睡眠定时（PB-001-C） */}
      <div className="hidden md:flex items-center gap-1 shrink-0" title={t('睡眠定时')}>
        <svg className="w-4 h-4 opacity-60" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
        </svg>
        <select
          value={sleepMode}
          onChange={(e) => { setHint(''); setSleepMode(e.target.value as SleepMode) }}
          className="shrink-0 px-2 py-1 rounded-lg text-xs border"
          style={{ background: 'var(--reader-bg)', borderColor: 'var(--reader-border)', color: 'var(--reader-text)' }}
          title={t('睡眠定时')}
          aria-label={t('睡眠定时')}
          data-tts-sleep="1"
        >
          <option value="off">{t('关闭定时')}</option>
          {SLEEP_MINUTES.map((minutes) => (
            <option key={minutes} value={String(minutes)}>{t('{{n}} 分钟', { n: minutes })}</option>
          ))}
          <option value="chapter">{t('到本章结束')}</option>
        </select>
        {sleepLeft && (
          <span className="text-xs tabular-nums opacity-70 min-w-[2.6rem] text-center" data-tts-sleep-left="1">{sleepLeft}</span>
        )}
      </div>

      {/* 音色 */}
      <select
        value={voiceId}
        onChange={(e) => handleVoiceChange(e.target.value)}
        disabled={voices.length <= 1}
        className="shrink-0 max-w-[10rem] px-2 py-1 rounded-lg text-xs border"
        style={{ background: 'var(--reader-bg)', borderColor: 'var(--reader-border)', color: 'var(--reader-text)' }}
        title={t('音色')}
        aria-label={t('音色')}
      >
        {voices.length === 0 && <option value="">{t('无可用音色')}</option>}
        {voices.map((v) => (
          <option key={v.id} value={v.id}>
            {v.name}{v.language ? ' (' + v.language + ')' : ''}
          </option>
        ))}
      </select>

      {/* 关闭 */}
      <button
        onClick={handleClose}
        className="p-2 rounded-lg transition-colors hover:bg-[var(--reader-bg)] opacity-70 hover:opacity-100"
        title={t('关闭朗读条')}
        aria-label={t('关闭朗读条')}
      >
        <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" /></svg>
      </button>
    </div>
  )
}
