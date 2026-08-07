import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { changelog } from '../../data/changelog'

interface ChangelogPanelProps {
  onClose: () => void
  isClosing: boolean
}

const typeLabels = (t: TFunction): Record<string, { label: string; style: React.CSSProperties }> => ({
  feature: { label: t('新功能'), style: { color: 'var(--color-green)', backgroundColor: 'var(--color-green-bg)' } },
  fix: { label: t('修复'), style: { color: 'var(--color-red)', backgroundColor: 'var(--color-red-bg)' } },
  improve: { label: t('优化'), style: { color: 'var(--color-indigo)', backgroundColor: 'var(--color-indigo-bg)' } },
})

export function ChangelogPanel({ onClose, isClosing }: ChangelogPanelProps) {
  const { t } = useTranslation()
  const labels = typeLabels(t)

  return (
    <div className={`absolute inset-0 z-50 flex items-center justify-center ${isClosing ? 'animate-fade-out' : ''}`}>
      <div className="absolute inset-0 bg-black/50" onClick={onClose} style={{ transition: 'opacity 0.2s' }} />
      <div
        className={`relative w-[600px] max-h-[80vh] rounded-xl shadow-2xl overflow-hidden ${isClosing ? 'animate-scale-out' : 'animate-scale-in'}`}
        style={{ backgroundColor: 'var(--reader-sidebar, #1e1e2e)' }}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b" style={{ borderColor: 'var(--reader-border)' }}>
          <div className="flex items-center gap-2">
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" style={{ color: 'var(--color-indigo)' }}>
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
            </svg>
            <span className="text-lg font-semibold" style={{ color: 'var(--reader-text)' }}>{t('更新日志')}</span>
          </div>
          <button onClick={onClose} className="p-1 rounded-lg transition-colors" style={{ color: 'var(--reader-text)', opacity: 0.6 }}>
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* Content */}
        <div className="overflow-y-auto max-h-[calc(80vh-64px)] px-6 py-4">
          {changelog.map((entry, i) => (
            <div key={entry.version} className={i < changelog.length - 1 ? 'mb-6 pb-6 border-b' : ''} style={i < changelog.length - 1 ? { borderColor: 'var(--reader-border)' } : undefined}>
              <div className="flex items-center gap-3 mb-3">
                <span className="text-base font-semibold" style={{ color: 'var(--reader-text)' }}>v{entry.version}</span>
                <span className="text-xs" style={{ color: 'var(--reader-text)', opacity: 0.4 }}>{entry.date}</span>
              </div>
              <div className="space-y-2">
                {entry.changes.map((change, j) => (
                  <div key={j} className="flex items-start gap-2">
                    <span className="flex-shrink-0 mt-0.5 px-1.5 py-0.5 text-[10px] font-medium rounded" style={labels[change.type]?.style || { color: 'var(--reader-text)', opacity: 0.5 }}>
                      {labels[change.type]?.label || change.type}
                    </span>
                    <span className="text-sm" style={{ color: 'var(--reader-text)', opacity: 0.8 }}>{t(change.text)}</span>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
