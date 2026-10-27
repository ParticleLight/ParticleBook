import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useReaderStore } from '../../stores/readerStore'
import { useSettingsStore } from '../../stores/settingsStore'
import { formatReadingTime } from '../../utils/format'
import { Dialog } from '../UI/Dialog'
import type { Book } from '../../stores/libraryStore'

// ── PB-024-C：阅读器的状态视图（从 ReaderView.tsx 抽出，纯搬移）───────
//   * useAnimatedMount —— 侧边栏/设置面板进出场动画
//   * LoadError —— 打开失败的三种路径（missing / gone / unknown）
//   * ReadingTimeDisplay —— 底栏「本次阅读时长」
//   * ReaderLoadingView / ReaderSuspenseFallback —— 首次加载与懒加载转圈
//   * ReaderErrorView —— 打开失败整页（含「从书架移除」确认框）
export function useAnimatedMount(isOpen: boolean, duration = 200) {
  const [shouldRender, setShouldRender] = useState(isOpen)
  const [isClosing, setIsClosing] = useState(false)

  useEffect(() => {
    if (isOpen) {
      setShouldRender(true)
      setIsClosing(false)
    } else if (shouldRender) {
      setIsClosing(true)
      const timer = setTimeout(() => {
        setShouldRender(false)
        setIsClosing(false)
      }, duration)
      return () => clearTimeout(timer)
    }
  }, [isOpen, shouldRender])

  return { shouldRender, isClosing }
}

export type LoadError =
  | { kind: 'missing'; path: string }
  | { kind: 'gone' }
  | { kind: 'unknown'; detail: string }

export function ReadingTimeDisplay() {
  const { t } = useTranslation()
  const currentReadingTime = useReaderStore((s) => s.currentReadingTime)
  const showReadingTime = useSettingsStore((s) => s.showReadingTime)
  if (!showReadingTime) return null
  return (
    <span title={t('本次阅读时长')}>
      <svg className="w-3 h-3 inline mr-1" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
      </svg>
      {t('阅读 {{time}}', { time: formatReadingTime(currentReadingTime) })}
    </span>
  )
}

export function ReaderLoadingView() {
  return (
    <div className="h-screen flex items-center justify-center" style={{ background: 'var(--reader-bg)', color: 'var(--reader-text)' }}>
      <div className="animate-spin rounded-full h-12 w-12 border-b-2" style={{ borderColor: 'var(--accent)' }}></div>
    </div>
  )
}

export function ReaderSuspenseFallback() {
  return (
    <div className="h-full flex items-center justify-center">
      <div className="animate-spin rounded-full h-8 w-8 border-b-2" style={{ borderColor: 'var(--accent)' }}></div>
    </div>
  )
}

export interface ReaderErrorViewProps {
  error: LoadError
  book: Book | null
  onClose: () => void
  onRemove: () => void | Promise<void>
}

export function ReaderErrorView({ error, book, onClose, onRemove }: ReaderErrorViewProps) {
  const { t } = useTranslation()
  const [confirmRemove, setConfirmRemove] = useState(false)
  const loadError = error
  const removeBook = onRemove
  const heading = loadError.kind === 'gone' ? t('这本书已不在书架')
                : loadError.kind === 'missing' ? t('找不到这本书的文件')
                : t('打开这本书失败')
  return (
    <div className="h-screen flex items-center justify-center bg-[var(--reader-bg)] text-[var(--reader-text)] px-6">
      <div className="max-w-lg w-full text-center">
        <div className="w-14 h-14 mx-auto mb-4 rounded-full flex items-center justify-center"
             style={{ background: 'var(--color-red-bg)', color: 'var(--color-red)' }}>
          <svg className="w-7 h-7" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                  d="M12 9v4m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />
          </svg>
        </div>
        <h2 className="text-lg font-semibold mb-2">{heading}</h2>
        {book && <p className="text-sm mb-3 opacity-80 break-words">{book.title}</p>}
        {loadError.kind === 'missing' && (
          <>
            <p className="text-sm mb-3 opacity-70">{t('它的文件已经不在原来的位置了 —— 可能被移动、重命名或删除。')}</p>
            <p className="text-xs mb-6 break-all opacity-70 px-3 py-2 rounded-lg border border-[var(--reader-border)]"
               style={{ background: 'var(--reader-sidebar)' }}>{loadError.path}</p>
          </>
        )}
        {loadError.kind === 'gone' && (
          <p className="text-sm mb-6 opacity-70">{t('它可能已在其它页面被删除。')}</p>
        )}
        {loadError.kind === 'unknown' && (
          <p className="text-sm mb-6 opacity-70 break-words">{t('读取文件时出错：{{error}}', { error: loadError.detail })}</p>
        )}
        <div className="flex justify-center gap-2">
          <button className="btn-secondary" onClick={onClose}>{t('返回书架')}</button>
          {loadError.kind !== 'gone' && (
            <button className="btn-primary" style={{ background: 'var(--ui-danger)', color: 'var(--ui-danger-text)' }}
                    onClick={() => setConfirmRemove(true)}>{t('从书架移除')}</button>
          )}
        </div>
        {loadError.kind === 'missing' && (
          <p className="text-xs mt-4 opacity-60">{t('把文件放回原位，或重新导入、重新下载这本书，即可继续阅读。')}</p>
        )}
        {/* PB-025-B：确认框改用 shared/ui Dialog（焦点陷阱 / ESC / 滚动锁 / aria 一致） */}
        <Dialog
          open={confirmRemove}
          onClose={() => setConfirmRemove(false)}
          title={t('从书架移除这本书？')}
          description={t('将删除书架记录以及它的阅读进度、书签和笔记；磁盘上的文件不会被删除。')}
          footer={(
            <>
              <button className="btn-secondary" onClick={() => setConfirmRemove(false)}>{t('取消')}</button>
              <button
                className="btn-primary"
                style={{ background: 'var(--ui-danger)', color: 'var(--ui-danger-text)' }}
                onClick={() => { void removeBook() }}
                data-confirm-remove="1"
              >
                {t('移除')}
              </button>
            </>
          )}
        >
          <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>{t('移除后可以重新导入这本书继续阅读。')}</p>
        </Dialog>
      </div>
    </div>
  )
}
