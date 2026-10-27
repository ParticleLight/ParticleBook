import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useReaderStore } from '../../stores/readerStore'
import { useSettingsStore } from '../../stores/settingsStore'
import { formatReadingTime } from '../../utils/format'

// ── PB-024-B：从 ReaderView.tsx 抽出的阅读器状态视图 ─────────────────
// useAnimatedMount（侧边栏/设置面板进出场动画）、LoadError（打开失败类型）、
// ReadingTimeDisplay（底栏「本次阅读时长」）。Loading/Error 整页仍留在
// ReaderView 里（该文件正被并行修改，按 Lead 要求只做最小 targeted edit）。
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

// 打开失败的三条路径（此前都是静默 onClose()，用户只看到"闪一下回书架"）：
//   missing = 文件读不到（多半被移走/改名/删了）
//   gone    = 书架里已经没有这条记录（在别处被删）
//   unknown = 其它异常
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
