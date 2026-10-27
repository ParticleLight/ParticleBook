// ── PB-022：共享 SelectionToolbar（翻译 / 词典 / 朗读 / 高亮）──────────
//
// 选区的唯一入口：ReaderView 用它替代三个弹层各自的"选区触发"逻辑。
//   * 选区来源：ReaderAdapter.getSelection()（主文档 / EPUB iframe 统一），
//     ReaderView 已把同一份快照同步到 readerStore.selection（响应式）；
//   * 按钮点按前 preventDefault(mousedown)：保留 DOM 选区，弹层/TTS 再取
//     adapter.getSelection() 时仍拿得到选中的文字；
//   * 触发只发请求（onTranslate/onDictionary/onRead/onHighlight），弹层不再
//     监听鼠标/选区事件；高亮由调用方用 adapter.applyAnnotation 写入。
//
// 不新增文案：按钮复用既有 i18n key（翻译 / 词典 / 朗读 / 高亮）。

import { useTranslation } from 'react-i18next'
import { useReaderAdapter, type ReaderSelection } from '../../reader'
import { useReaderStore } from '../../stores/readerStore'

interface SelectionToolbarProps {
  /** 面板/设置等浮层打开时隐藏，避免叠在弹层上。 */
  hidden?: boolean
  onTranslate: (selection: ReaderSelection) => void
  onDictionary: (selection: ReaderSelection) => void
  onRead: (selection: ReaderSelection) => void
  onHighlight: (selection: ReaderSelection) => void
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), Math.max(min, max))
}

export function SelectionToolbar({
  hidden = false,
  onTranslate,
  onDictionary,
  onRead,
  onHighlight,
}: SelectionToolbarProps) {
  const { t } = useTranslation()
  const adapter = useReaderAdapter()
  const stored = useReaderStore((s) => s.selection)

  if (hidden) return null
  if (!stored || !stored.text.trim()) return null

  // 优先实时 adapter 选区（点击工具条前 mousedown 已 preventDefault，选区还在）；
  // adapter 取不到时用 ReaderView 同步的快照。
  const currentSelection = (): ReaderSelection | null => {
    const live = adapter?.getSelection()
    if (live && live.text.trim()) return live
    const snapshot = useReaderStore.getState().selection
    if (snapshot && snapshot.text.trim()) {
      return {
        text: snapshot.text,
        locator: snapshot.locator ?? null,
        rect: snapshot.rect,
        // readerStore 的类型还没带 context，但 runtime 快照是 adapter 原对象。
        context: (snapshot as ReaderSelection).context,
      }
    }
    return null
  }

  const rect = stored.rect
  const top = rect ? Math.max(8, rect.y - 44) : 80
  const left = rect
    ? clamp(rect.x, 8, Math.max(8, window.innerWidth - 280))
    : clamp(window.innerWidth / 2 - 120, 8, Math.max(8, window.innerWidth - 280))

  const buttons: Array<{ key: string; label: string; testId: string; icon: string; action: () => void }> = [
    {
      key: 'translate', label: t('翻译'), testId: 'selection-action-translate',
      icon: 'M3 5h12M9 3v2m1.048 9.5A18.022 18.022 0 016.412 9m6.088 9h7M11 21l5-10 5 10M12.751 5C11.783 10.77 8.07 15.61 3 18.129',
      action: () => { const sel = currentSelection(); if (sel) onTranslate(sel) },
    },
    {
      key: 'dictionary', label: t('词典'), testId: 'selection-action-dictionary',
      icon: 'M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253',
      action: () => { const sel = currentSelection(); if (sel) onDictionary(sel) },
    },
    {
      key: 'read', label: t('朗读'), testId: 'selection-action-read',
      icon: 'M15.536 8.464a5 5 0 010 7.072M18.364 5.636a9 9 0 010 12.728M5 10v4a1 1 0 001 1h2.586l3.293 3.293A1 1 0 0014 17.586V6.414a1 1 0 00-1.707-.707L9.586 9H6a1 1 0 00-1 1z',
      action: () => { const sel = currentSelection(); if (sel) onRead(sel) },
    },
    {
      key: 'highlight', label: t('高亮'), testId: 'selection-action-highlight',
      icon: 'M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z',
      action: () => { const sel = currentSelection(); if (sel) onHighlight(sel) },
    },
  ]

  return (
    <div
      data-pb-selection-toolbar
      className="fixed z-[58] flex items-center gap-1 px-1.5 py-1 rounded-lg shadow-xl border"
      style={{
        left,
        top,
        background: 'var(--reader-sidebar)',
        borderColor: 'var(--reader-border)',
        color: 'var(--reader-text)',
      }}
      onMouseDown={(e) => {
        // 保住 DOM 选区：否则点击按钮会先清空选区，弹层/TTS 再取就空了
        e.preventDefault()
        e.stopPropagation()
      }}
      onClick={(e) => e.stopPropagation()}
    >
      {buttons.map((button) => (
        <button
          key={button.key}
          type="button"
          data-testid={button.testId}
          className="flex items-center gap-1 px-2 py-1 rounded text-xs transition-colors hover:bg-[var(--reader-border)]"
          title={button.label}
          aria-label={button.label}
          onClick={button.action}
        >
          <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d={button.icon} />
          </svg>
          {button.label}
        </button>
      ))}
    </div>
  )
}
