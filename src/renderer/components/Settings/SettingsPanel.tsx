import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { useSettingsStore } from '../../stores/settingsStore'

interface SettingsPanelProps {
  onClose: () => void
  format?: string
  isClosing?: boolean
}

export function SettingsPanel({ onClose, format, isClosing }: SettingsPanelProps) {
  const {
    fontSize, setFontSize,
    fontFamily, setFontFamily,
    lineHeight, setLineHeight,
    margin, setMargin,
    textAlign, setTextAlign,
  } = useSettingsStore()
  const { t } = useTranslation()

  const isTextFormat = !format || !['pdf', 'cbz', 'cbr'].includes(format)

  const fonts = useMemo(() => [
    { label: t('衬线体'), value: 'Georgia, Noto Serif SC, serif' },
    { label: t('无衬线'), value: 'Inter, Noto Sans SC, sans-serif' },
    { label: t('等宽'), value: 'JetBrains Mono, monospace' },
  ], [t])

  return (
    <div className={`absolute inset-0 z-40 flex justify-end ${isClosing ? 'animate-fade-out' : ''}`}>
      {/* Backdrop */}
      {/* review U14：遮罩改用统一 token（原来写死 bg-black/40，亮色主题下比其它弹层更暗） */}
      <div
        className={`absolute inset-0 ${isClosing ? 'opacity-0' : ''}`}
        style={{ background: 'var(--ui-backdrop)', transition: 'opacity 0.2s' }}
        onClick={onClose}
        aria-hidden="true"
      />
      {/* Panel —— 这是阅读器内的「阅读设置」右侧滑出面板，本身没有导航，
          因此 M3 三档导航改造（expanded 抽屉 / medium rail / compact 模态抽屉）
          只落在 GlobalSettings。这里仅把固定 320px 宽度加上视口上限：窗口比
          面板还窄时收窄而不是溢出（极窄窗口不该出现横向滚动条）。 */}
      <div className={`relative w-80 max-w-[calc(100vw-16px)] h-full border-l border-[var(--reader-border)] shadow-2xl overflow-y-auto ${isClosing ? 'animate-slide-out-right' : 'animate-slide-in-right'}`} style={{ backgroundColor: 'var(--reader-sidebar)', opacity: 0.9 }}>
      <div className="p-6 space-y-6">
        {/* Header */}
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold text-[var(--reader-text)]">{t('阅读设置')}</h2>
          {/* review U8：纯图标关闭按钮补可访问名 */}
          <button onClick={onClose} className="p-1 text-[var(--reader-text)] opacity-50 hover:opacity-80" aria-label={t('关闭')} title={t('关闭')}>
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {isTextFormat && (
          <>
            {/* Font Family */}
            <div>
              <label className="block text-sm font-medium text-[var(--reader-text)] opacity-60 mb-2">{t('字体')}</label>
              <div className="flex flex-wrap gap-2">
                {fonts.map((f) => (
                  <button
                    key={f.value}
                    onClick={() => setFontFamily(f.value)}
                    className={`px-3 py-2 text-sm rounded-lg transition-colors ${
                      fontFamily === f.value
                        ? 'bg-[var(--reader-accent)] text-white'
                        : 'bg-[var(--reader-border)] text-[var(--reader-text)] opacity-70 hover:opacity-100'
                    }`}
                    style={{ fontFamily: f.value }}
                  >
                    {f.label}
                  </button>
                ))}
              </div>
            </div>

            {/* Font Size */}
            <div>
              <label className="block text-sm font-medium text-[var(--reader-text)] opacity-60 mb-2">
                {t('字号: {{size}}px', { size: fontSize })}
              </label>
              <div className="flex items-center gap-3">
                <button
                  onClick={() => setFontSize(Math.max(12, fontSize - 1))}
                  className="px-3 py-1 bg-[var(--reader-border)] rounded-lg text-[var(--reader-text)] opacity-70 hover:opacity-100"
                >
                  A-
                </button>
                <input
                  type="range"
                  min="12"
                  max="32"
                  value={fontSize}
                  onChange={(e) => setFontSize(Number(e.target.value))}
                  className="flex-1" style={{ accentColor: 'var(--accent)' }}
                />
                <button
                  onClick={() => setFontSize(Math.min(32, fontSize + 1))}
                  className="px-3 py-1 bg-[var(--reader-border)] rounded-lg text-[var(--reader-text)] opacity-70 hover:opacity-100"
                >
                  A+
                </button>
              </div>
            </div>

            {/* Line Height */}
            <div>
              <label className="block text-sm font-medium text-[var(--reader-text)] opacity-60 mb-2">
                {t('行距: {{value}}', { value: lineHeight.toFixed(1) })}
              </label>
              <input
                type="range"
                min="1.2"
                max="3"
                step="0.1"
                value={lineHeight}
                onChange={(e) => setLineHeight(Number(e.target.value))}
                className="w-full" style={{ accentColor: 'var(--accent)' }}
              />
            </div>

            {/* Margin */}
            <div>
              <label className="block text-sm font-medium text-[var(--reader-text)] opacity-60 mb-2">
                {t('边距: {{value}}px', { value: margin })}
              </label>
              <input
                type="range"
                min="0"
                max="100"
                value={margin}
                onChange={(e) => setMargin(Number(e.target.value))}
                className="w-full" style={{ accentColor: 'var(--accent)' }}
              />
            </div>

            {/* Text Align */}
            <div>
              <label className="block text-sm font-medium text-[var(--reader-text)] opacity-60 mb-2">{t('对齐方式')}</label>
              <div className="flex gap-2">
                <button
                  onClick={() => setTextAlign('left')}
                  className={`flex-1 py-2 text-sm rounded-lg transition-colors ${
                    textAlign === 'left'
                      ? 'bg-[var(--reader-accent)] text-white'
                      : 'bg-[var(--reader-border)] text-[var(--reader-text)] opacity-70 hover:opacity-100'
                  }`}
                >
                  {t('左对齐')}
                </button>
                <button
                  onClick={() => setTextAlign('justify')}
                  className={`flex-1 py-2 text-sm rounded-lg transition-colors ${
                    textAlign === 'justify'
                      ? 'bg-[var(--reader-accent)] text-white'
                      : 'bg-[var(--reader-border)] text-[var(--reader-text)] opacity-70 hover:opacity-100'
                  }`}
                >
                  {t('两端对齐')}
                </button>
              </div>
            </div>
          </>
        )}

        {!isTextFormat && (
          <div className="text-sm text-[var(--reader-text)] opacity-50 bg-[var(--reader-bg)] rounded-lg p-4">
            {t('PDF 和漫画格式使用固定版式，不支持调整字体和排版设置。可使用')} <kbd className="px-1.5 py-0.5 bg-[var(--reader-border)] rounded text-[var(--reader-text)] opacity-60">+</kbd> / <kbd className="px-1.5 py-0.5 bg-[var(--reader-border)] rounded text-[var(--reader-text)] opacity-60">-</kbd> {t('键缩放。')}
          </div>
        )}

        {/* Keyboard shortcuts */}
        <div className="pt-4 border-t border-[var(--reader-border)]">
          <h3 className="text-sm font-medium text-[var(--reader-text)] opacity-60 mb-3">{t('快捷键')}</h3>
          <div className="space-y-2 text-xs text-[var(--reader-text)] opacity-50">
            <div className="flex justify-between">
              <span>{t('上一页')}</span>
              <kbd className="px-2 py-0.5 bg-[var(--reader-border)] rounded text-[var(--reader-text)] opacity-60">←</kbd>
            </div>
            <div className="flex justify-between">
              <span>{t('下一页')}</span>
              <kbd className="px-2 py-0.5 bg-[var(--reader-border)] rounded text-[var(--reader-text)] opacity-60">→</kbd>
            </div>
            <div className="flex justify-between">
              <span>{t('添加书签')}</span>
              <kbd className="px-2 py-0.5 bg-[var(--reader-border)] rounded text-[var(--reader-text)] opacity-60">B</kbd>
            </div>
            <div className="flex justify-between">
              <span>{t('返回')}</span>
              <kbd className="px-2 py-0.5 bg-[var(--reader-border)] rounded text-[var(--reader-text)] opacity-60">Esc</kbd>
            </div>
          </div>
        </div>
      </div>
    </div>
    </div>
  )
}
