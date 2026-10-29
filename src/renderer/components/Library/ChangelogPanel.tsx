import { useId, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { changelog } from '../../data/changelog'
import { Modal } from '../UI/Modal'

interface ChangelogPanelProps {
  onClose: () => void
  isClosing: boolean
}

type ChangeType = 'feature' | 'improve' | 'fix'

// 分类顺序：新功能 → 优化 → 修复（发版说明的常规阅读顺序）
const TYPE_ORDER: ChangeType[] = ['feature', 'improve', 'fix']

const typeMeta = (t: TFunction): Record<ChangeType, { label: string; color: string; bg: string }> => ({
  feature: { label: t('新功能'), color: 'var(--color-green)', bg: 'var(--color-green-bg)' },
  improve: { label: t('优化'), color: 'var(--color-indigo)', bg: 'var(--color-indigo-bg)' },
  fix: { label: t('修复'), color: 'var(--color-red)', bg: 'var(--color-red-bg)' },
})

export function ChangelogPanel({ onClose, isClosing }: ChangelogPanelProps) {
  const { t } = useTranslation()
  const titleId = useId()
  const meta = typeMeta(t)
  const [filter, setFilter] = useState<'all' | ChangeType>('all')

  // 按版本分组：每个版本里再按「新功能 / 优化 / 修复」分类；空分类不渲染。
  const versions = useMemo(() => {
    return changelog
      .map((entry) => {
        const groups = TYPE_ORDER
          .map((type) => ({ type, items: entry.changes.filter((change) => change.type === type) }))
          .filter((group) => group.items.length > 0 && (filter === 'all' || group.type === filter))
        const total = groups.reduce((sum, group) => sum + group.items.length, 0)
        return { entry, groups, total }
      })
      .filter((version) => version.total > 0)
  }, [filter])

  // 顶部筛选条的数量（按整份日志统计，不随筛选变化）
  const counts = useMemo(() => {
    const acc: Record<'all' | ChangeType, number> = { all: 0, feature: 0, improve: 0, fix: 0 }
    for (const entry of changelog) {
      for (const change of entry.changes) {
        acc.all += 1
        if (change.type === 'feature' || change.type === 'improve' || change.type === 'fix') acc[change.type] += 1
      }
    }
    return acc
  }, [])

  // review U5：对话框语义 / Esc / 焦点陷阱 / 关闭回焦交给 UI/Modal（原来只有遮罩
  // 点击关闭，没有 role="dialog"、没有 Esc、Tab 会跑进背后的书库）。
  return (
    <Modal
      onClose={onClose}
      labelledBy={titleId}
      containerClassName="fixed inset-0 z-50 flex items-center justify-center p-4"
      panelClassName={`m3-dialog relative !w-[620px] max-h-[80vh] overflow-hidden ${isClosing ? 'animate-scale-out' : 'animate-scale-in'}`}
      panelTestId="changelog-panel"
    >
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4">
          <div className="flex items-center gap-2">
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true" style={{ color: 'var(--md-sys-color-primary)' }}>
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
            </svg>
            <h2 className="m3-title-large" id={titleId}>{t('更新日志')}</h2>
          </div>
          <button onClick={onClose} className="m3-icon-button" aria-label={t('关闭')}>
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* 分类筛选（2026-10-29）：按类型整理整份日志，默认「全部」 */}
        <div
          className="flex flex-wrap items-center gap-2 px-6 py-3"
          style={{ borderTop: '1px solid var(--md-sys-color-outline-variant)', borderBottom: '1px solid var(--md-sys-color-outline-variant)' }}
          role="group"
          aria-label={t('按类型筛选')}
        >
          {(['all', ...TYPE_ORDER] as const).map((key) => (
            <button
              key={key}
              type="button"
              data-testid={`changelog-filter-${key}`}
              aria-pressed={filter === key}
              className={`m3-filter-chip ${filter === key ? 'is-selected' : ''}`}
              onClick={() => setFilter(key)}
            >
              {key === 'all' ? t('全部') : meta[key].label}
              <span className="text-[11px]" style={{ opacity: 0.6 }}>{counts[key]}</span>
            </button>
          ))}
        </div>

        {/* Content */}
        <div className="overflow-y-auto max-h-[calc(80vh-124px)] px-6 py-4">
          {versions.map(({ entry, groups }, i) => (
            <div
              key={entry.version}
              className={i < versions.length - 1 ? 'mb-6 pb-6 border-b' : ''}
              style={i < versions.length - 1 ? { borderColor: 'var(--md-sys-color-outline-variant)' } : undefined}
              data-testid={`changelog-version-${entry.version}`}
            >
              <div className="flex items-center gap-3 mb-2">
                <span className="m3-title-medium">v{entry.version}</span>
                {entry.unreleased && (
                  <span
                    className="m3-label-small rounded-full px-2 py-0.5"
                    style={{ background: 'var(--md-sys-color-primary-container)', color: 'var(--md-sys-color-on-primary-container)' }}
                    data-testid="changelog-unreleased"
                  >
                    {t('未发布')}
                  </span>
                )}
                <span className="text-xs" style={{ color: 'var(--md-sys-color-on-surface)', opacity: 0.4 }}>{entry.date}</span>
                <span className="ml-auto text-[11px]" style={{ color: 'var(--md-sys-color-on-surface)', opacity: 0.35 }}>
                  {groups.map((group, gi) => (
                    <span key={group.type}>{gi > 0 ? ' · ' : ''}{meta[group.type].label} {group.items.length}</span>
                  ))}
                </span>
              </div>
              {groups.map((group) => (
                <div key={group.type} className="mb-3 last:mb-0">
                  <div className="flex items-center gap-2 mb-1.5 pl-1">
                    <span className="w-1.5 h-1.5 rounded-full" style={{ background: meta[group.type].color }} aria-hidden="true" />
                    <span className="m3-label-medium" style={{ color: meta[group.type].color }}>{meta[group.type].label}</span>
                    <span className="text-[11px]" style={{ color: 'var(--md-sys-color-on-surface)', opacity: 0.35 }}>{group.items.length}</span>
                  </div>
                  <ul className="space-y-1.5 pl-4">
                    {group.items.map((change, j) => (
                      <li key={j} className="flex items-start gap-2 text-sm" style={{ color: 'var(--md-sys-color-on-surface)', opacity: 0.85 }}>
                        <span aria-hidden="true" style={{ opacity: 0.35, lineHeight: '1.6' }}>•</span>
                        <span>{t(change.text)}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
            </div>
          ))}
        </div>
    </Modal>
  )
}
