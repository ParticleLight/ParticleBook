// ── PB-024-B：从 GlobalSettings.tsx 拆出的设置页（按页分组，纯搬移）─────
// 外观 / 阅读 / 书架 / Z-Library / 关于 / 高级 / 书源 七个页面 + 共用小部件
// （SvgIcon / Toggle / Segment / SectionTitle）与导航表都在这里；
// GlobalSettings.tsx 只保留外壳（导航 + 渲染选中页）。

import { useState, useEffect, useRef, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import i18n from '../../i18n'
import { useSettingsStore, type AccentColor } from '../../stores/settingsStore'
import { copyText, downloadText } from '../../utils/download'
import { TranslationSettings } from './TranslationSettings'
import { Switch } from '../UI/Switch'
import { BookSourceManager } from './BookSourceManager'

export interface GlobalSettingsProps {
  onBack: () => void
}

interface UpdateInfo {
  version: string
  downloadUrl?: string
  fileName?: string
  sha512?: string
}

export type NavKey = 'appearance' | 'reading' | 'translation' | 'library' | 'zlibrary' | 'about' | 'booksources'

export const NAV_ITEMS = (t: TFunction): { key: NavKey; label: string; icon: string }[] => [
  { key: 'appearance', label: t('外观'), icon: 'M7 21a4 4 0 01-4-4V5a2 2 0 012-2h4a2 2 0 012 2v12a4 4 0 01-4 4zm0 0h12a2 2 0 002-2v-4a2 2 0 00-2-2h-2.343M11 7.343l1.657-1.657a2 2 0 012.828 0l2.829 2.829a2 2 0 010 2.828l-8.486 8.485M7 17h.01' },
  { key: 'reading', label: t('阅读'), icon: 'M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253' },
  // 2026-10-28：AI 翻译从「关于」页拆出来，单独一个导航项（用户要求）
  { key: 'translation', label: t('翻译'), icon: 'M3 5h12M9 3v2m1.048 9.5A18.022 18.022 0 016.412 9m6.088 9h7M11 21l5-10 5 10M12.751 5C11.783 10.77 8.07 15.61 3 18.129' },
  { key: 'library', label: t('书架'), icon: 'M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10' },
  { key: 'zlibrary', label: 'Z-Library', icon: 'M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9' },
  { key: 'about', label: t('关于'), icon: 'M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z' },
  // PB-013-A：书源管理（导入/编辑/测试/启停）
  { key: 'booksources', label: t('书源管理'), icon: 'M4 5a1 1 0 011-1h14a1 1 0 011 1v2a1 1 0 01-1 1H5a1 1 0 01-1-1V5zm0 7a1 1 0 011-1h6a1 1 0 011 1v2a1 1 0 01-1 1H5a1 1 0 01-1-1v-2zm9 0a1 1 0 011-1h5a1 1 0 011 1v2a1 1 0 01-1 1h-5a1 1 0 01-1-1v-2zM4 18a1 1 0 011-1h14a1 1 0 011 1v1a1 1 0 01-1 1H5a1 1 0 01-1-1v-1z' },
]

const FONTS = (t: TFunction) => [
  { label: t('衬线体'), value: 'Georgia, Noto Serif SC, serif', preview: t('Aa 宋体') },
  { label: t('无衬线'), value: 'Inter, Noto Sans SC, sans-serif', preview: t('Aa 黑体') },
  { label: t('等宽'), value: 'JetBrains Mono, monospace', preview: t('Aa 等宽') },
]

const THEMES = (t: TFunction) => [
  { id: 'light' as const, label: t('浅色'), icon: 'M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z' },
  { id: 'dark' as const, label: t('深色'), icon: 'M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z' },
  { id: 'sepia' as const, label: t('护眼'), icon: 'M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z' },
]

const ACCENTS = (t: TFunction): { id: AccentColor; label: string; color: string }[] => [
  { id: 'blue', label: t('蓝色'), color: '#60cdff' },
  { id: 'purple', label: t('紫色'), color: '#c084fc' },
  { id: 'green', label: t('绿色'), color: '#4ade80' },
  { id: 'orange', label: t('橙色'), color: '#fb923c' },
]

const SORT_OPTIONS = (t: TFunction) => [
  { value: 'last_opened' as const, label: t('最近阅读') },
  { value: 'added_at' as const, label: t('添加时间') },
  { value: 'title' as const, label: t('书名') },
  { value: 'author' as const, label: t('作者') },
]

// ─── Tiny components ───

export function SvgIcon({ d, className = 'w-5 h-5' }: { d: string; className?: string }) {
  return (
    <svg className={className} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d={d} />
    </svg>
  )
}

// PB-025-B：设置页所有开关改用 shared/ui Switch（token 配色 + aria/size 统一）。
// 保留 Toggle 这个名字和各调用点不变，只换实现。
function Toggle({ value, onChange }: { value: boolean; onChange: (v: boolean) => void }) {
  return <Switch checked={value} onChange={onChange} />
}

function Segment<T extends string>({ options, value, onChange }: {
  options: { id: T; label: string; icon?: string }[]
  value: T
  onChange: (v: T) => void
}) {
  return (
    <div className="settings-segment">
      {options.map((opt) => (
        <button
          key={opt.id}
          className={`settings-segment-btn ${value === opt.id ? 'active' : ''}`}
          onClick={() => onChange(opt.id)}
        >
          {opt.icon && <SvgIcon d={opt.icon} className="w-4 h-4 inline mr-1.5 -mt-0.5" />}
          {opt.label}
        </button>
      ))}
    </div>
  )
}

function SectionTitle({ children }: { children: React.ReactNode }) {
  return <h3 className="m3-section-title mb-3">{children}</h3>
}

// 数字型原始设置项（settings 里没有 store setter 的键，例如阅读目标 / 封面缓存 /
// 翻译超时）。失焦或回车才提交，越界按 C++ schema 的同一区间夹紧；写完刷新
// settingsStore，其它页面（统计页、书架）立刻拿到新值。
function NumberSettingRow({ label, desc, settingKey, fallback, min, max }: {
  label: string
  desc: string
  settingKey: string
  fallback: number
  min: number
  max: number
}) {
  const { t } = useTranslation()
  const [value, setValue] = useState<number>(fallback)
  const [status, setStatus] = useState<'idle' | 'saving' | 'saved'>('idle')
  useEffect(() => {
    let alive = true
    void window.electronAPI.getSettings().then((raw) => {
      if (!alive) return
      const num = Number((raw as Record<string, unknown>)[settingKey])
      setValue(Number.isFinite(num) ? Math.min(max, Math.max(min, num)) : fallback)
    }).catch(() => {})
    return () => { alive = false }
  }, [settingKey, fallback, min, max])
  const commit = async (raw: number) => {
    const next = Math.min(max, Math.max(min, Math.round(raw)))
    setValue(next)
    setStatus('saving')
    try {
      await window.electronAPI.updateSettings({ [settingKey]: next } as never)
      setStatus('saved')
      void useSettingsStore.getState().loadSettings()
    } catch {
      setStatus('idle')
    }
  }
  return (
    <div className="settings-row">
      <div className="pr-4">
        <div className="settings-row-label">{label}</div>
        <div className="settings-row-desc">{desc}</div>
      </div>
      <div className="flex items-center gap-2 flex-shrink-0">
        <div className="w-[120px]">
          <input
            type="number"
            inputMode="numeric"
            min={min}
            max={max}
            value={Number.isFinite(value) ? value : fallback}
            className="m3-text-field is-dense"
            aria-label={label}
            data-setting-key={settingKey}
            onChange={(e) => setValue(Number(e.target.value))}
            onBlur={(e) => { void commit(Number(e.target.value)) }}
            onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur() }}
          />
        </div>
        <span className="m3-label-small w-[3.5rem]">{status === 'saved' ? t('已保存') : status === 'saving' ? t('保存中…') : ''}</span>
      </div>
    </div>
  )
}

// ─── Settings pages ───

export function AppearancePage() {
  const {
    theme, setTheme,
    accentColor, setAccentColor,
    fontFamily, setFontFamily,
    fontSize, setFontSize,
    lineHeight, setLineHeight,
    margin, setMargin,
    textAlign, setTextAlign,
  } = useSettingsStore()
  const language = useSettingsStore((s) => s.language)
  const setLanguage = useSettingsStore((s) => s.setLanguage)
  const { t } = useTranslation()
  const fonts = useMemo(() => FONTS(t), [t])
  const themes = useMemo(() => THEMES(t), [t])
  const accents = useMemo(() => ACCENTS(t), [t])

  return (
    <div className="space-y-6">
      {/* Language */}
      <div className="settings-card">
        <SectionTitle>{t('语言')}</SectionTitle>
        <div className="settings-row">
          <div>
            <div className="settings-row-label">{t('界面语言')}</div>
            <div className="settings-row-desc">{t('选择应用界面显示的语言')}</div>
          </div>
          <Segment
            options={[
              { id: 'zh' as const, label: '简体中文' },
              { id: 'en' as const, label: 'English' },
            ]}
            value={language}
            onChange={setLanguage}
          />
        </div>
      </div>

      {/* Theme */}
      <div className="settings-card">
        <SectionTitle>{t('外观主题')}</SectionTitle>
        <div className="grid grid-cols-3 gap-3">
          {themes.map((themeOpt) => (
            <button
              key={themeOpt.id}
              onClick={() => setTheme(themeOpt.id)}
              className="relative flex flex-col items-center gap-2 p-4 rounded-xl border-2 transition-all"
              style={{
                borderColor: theme === themeOpt.id ? 'var(--accent)' : 'var(--border)',
                background: theme === themeOpt.id ? 'var(--color-indigo-bg)' : 'var(--surface)',
              }}
            >
              <SvgIcon d={themeOpt.icon} className="w-6 h-6" />
              <span className="text-sm font-medium">{themeOpt.label}</span>
              {theme === themeOpt.id && (
                <div className="absolute top-2 right-2 w-4 h-4 rounded-full flex items-center justify-center" style={{ background: 'var(--accent)' }}>
                  <svg className="w-2.5 h-2.5" fill="none" viewBox="0 0 24 24" stroke="var(--accent-text)" strokeWidth={3}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                  </svg>
                </div>
              )}
            </button>
          ))}
        </div>
      </div>

      {/* Accent Color */}
      <div className="settings-card">
        <SectionTitle>{t('主题色')}</SectionTitle>
        <div className="flex gap-3">
          {accents.map((a) => (
            <button
              key={a.id}
              onClick={() => setAccentColor(a.id)}
              className="flex items-center gap-2.5 px-4 py-2.5 rounded-lg border-2 transition-all"
              style={{
                borderColor: accentColor === a.id ? 'var(--accent)' : 'var(--border)',
                background: accentColor === a.id ? 'var(--color-indigo-bg)' : 'var(--surface)',
              }}
            >
              <div className="w-4 h-4 rounded-full" style={{ background: a.color }} />
              <span className="text-sm">{a.label}</span>
            </button>
          ))}
        </div>
      </div>

      {/* Font */}
      <div className="settings-card">
        <SectionTitle>{t('字体')}</SectionTitle>
        <div className="flex gap-3">
          {fonts.map((f) => (
            <button
              key={f.value}
              onClick={() => setFontFamily(f.value)}
              className="flex-1 p-3 rounded-xl border-2 transition-all text-center"
              style={{
                borderColor: fontFamily === f.value ? 'var(--accent)' : 'var(--border)',
                background: fontFamily === f.value ? 'var(--color-indigo-bg)' : 'var(--surface)',
                fontFamily: f.value,
              }}
            >
              <div className="text-lg mb-1">{f.preview}</div>
              <div className="text-xs" style={{ color: 'var(--text-tertiary)', fontFamily: 'inherit' }}>{f.label}</div>
            </button>
          ))}
        </div>
      </div>

      {/* Typography */}
      <div className="settings-card">
        <SectionTitle>{t('排版')}</SectionTitle>

        {/* Font size */}
        <div className="settings-row">
          <div>
            <div className="settings-row-label">{t('字号')}</div>
            <div className="settings-row-desc">{t('调整正文字体大小')}</div>
          </div>
          <div className="flex items-center gap-3">
            <button onClick={() => setFontSize(Math.max(12, fontSize - 1))} className="btn-ghost px-2 py-1 text-sm font-bold rounded-md">A−</button>
            <span className="text-sm w-10 text-center font-mono" style={{ color: 'var(--text-secondary)' }}>{fontSize}px</span>
            <button onClick={() => setFontSize(Math.min(32, fontSize + 1))} className="btn-ghost px-2 py-1 text-sm font-bold rounded-md">A+</button>
            <input type="range" min={12} max={32} value={fontSize} onChange={(e) => setFontSize(Number(e.target.value))} className="m3-slider w-28" aria-label={t('字号')} />
          </div>
        </div>

        {/* Line height */}
        <div className="settings-row">
          <div>
            <div className="settings-row-label">{t('行距')}</div>
            <div className="settings-row-desc">{t('调整行与行之间的距离')}</div>
          </div>
          <div className="flex items-center gap-3">
            <span className="text-xs" style={{ color: 'var(--text-tertiary)' }}>{t('紧凑')}</span>
            <input type="range" min={1.2} max={3} step={0.1} value={lineHeight} onChange={(e) => setLineHeight(Number(e.target.value))} className="m3-slider w-28" aria-label={t('行距')} />
            <span className="text-xs" style={{ color: 'var(--text-tertiary)' }}>{t('宽松')}</span>
            <span className="text-sm w-8 text-center font-mono" style={{ color: 'var(--text-secondary)' }}>{lineHeight.toFixed(1)}</span>
          </div>
        </div>

        {/* Margin */}
        <div className="settings-row">
          <div>
            <div className="settings-row-label">{t('边距')}</div>
            <div className="settings-row-desc">{t('页面两侧的留白宽度')}</div>
          </div>
          <div className="flex items-center gap-3">
            <span className="text-xs" style={{ color: 'var(--text-tertiary)' }}>{t('窄')}</span>
            <input type="range" min={0} max={100} value={margin} onChange={(e) => setMargin(Number(e.target.value))} className="m3-slider w-28" aria-label={t('边距')} />
            <span className="text-xs" style={{ color: 'var(--text-tertiary)' }}>{t('宽')}</span>
            <span className="text-sm w-10 text-center font-mono" style={{ color: 'var(--text-secondary)' }}>{margin}px</span>
          </div>
        </div>

        {/* Text align */}
        <div className="settings-row">
          <div>
            <div className="settings-row-label">{t('对齐方式')}</div>
            <div className="settings-row-desc">{t('段落文字的对齐方式')}</div>
          </div>
          <Segment
            options={[
              { id: 'left' as const, label: t('左对齐') },
              { id: 'justify' as const, label: t('两端对齐') },
            ]}
            value={textAlign}
            onChange={setTextAlign}
          />
        </div>
      </div>

      {/* Note */}
      <p className="text-xs leading-relaxed px-1" style={{ color: 'var(--text-tertiary)' }}>
        {t('以上设置为全局默认值。阅读某本书时可在侧边栏单独调整，该书将使用独立设置。')}
      </p>
    </div>
  )
}

export function ReadingPage() {
  const {
    autoSaveProgress, setAutoSaveProgress,
    showReadingTime, setShowReadingTime,
  } = useSettingsStore()
  const { t } = useTranslation()

  return (
    <div className="space-y-6">
      <div className="settings-card">
        <SectionTitle>{t('进度与统计')}</SectionTitle>

        <div className="settings-row">
          <div>
            <div className="settings-row-label">{t('自动保存进度')}</div>
            <div className="settings-row-desc">{t('退出阅读时自动记住阅读位置')}</div>
          </div>
          <Toggle value={autoSaveProgress} onChange={setAutoSaveProgress} />
        </div>

        <div className="settings-row">
          <div>
            <div className="settings-row-label">{t('显示阅读时间')}</div>
            <div className="settings-row-desc">{t('在阅读页面右下角显示本次阅读时长')}</div>
          </div>
          <Toggle value={showReadingTime} onChange={setShowReadingTime} />
        </div>
      </div>

      <div className="settings-card">
        <SectionTitle>{t('阅读目标')}</SectionTitle>
        <NumberSettingRow
          label={t('每日阅读目标（分钟）')}
          desc={t('阅读统计页「今日目标」进度条的目标值；0 表示不设目标（进度条恒为 0）')}
          settingKey="reading.dailyGoalMinutes"
          fallback={30}
          min={0}
          max={1440}
        />
        <NumberSettingRow
          label={t('每周阅读目标（分钟）')}
          desc={t('阅读统计页「本周目标」进度条的目标值；0 表示不设目标（进度条恒为 0）')}
          settingKey="reading.weeklyGoalMinutes"
          fallback={180}
          min={0}
          max={10080}
        />
      </div>

      <div className="settings-card">
        <SectionTitle>{t('快捷键')}</SectionTitle>
        <div className="grid grid-cols-2 gap-x-8 gap-y-2.5">
          {([
            ['上一页 / 下一页', '← →'],
            ['全书搜索', 'Ctrl+F'],
            ['下一个搜索结果', 'Enter'],
            ['上一个搜索结果', 'Shift+Enter'],
            ['关闭搜索 / 返回', 'Esc'],
            ['添加书签', 'B'],
            ['缩放 (PDF)', '+ − 或 Ctrl+滚轮'],
            ['下一页 (PDF/漫画)', 'Space'],
          ] as const).map(([action, keys]) => (
            <div key={action} className="flex items-center justify-between py-1">
              <span className="text-sm" style={{ color: 'var(--text-secondary)' }}>{t(action)}</span>
              <kbd className="px-2 py-0.5 text-xs rounded-md font-mono" style={{ background: 'var(--bg-tertiary)', color: 'var(--text-tertiary)', border: '1px solid var(--border)' }}>{keys}</kbd>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

// AI 翻译（直接复用 TranslationSettings：它自带 settings-card 与「AI 翻译」小节标题，
// Key 走 SecretStore/DPAPI，不进 settings）。
export function TranslationPage() {
  const { t } = useTranslation()
  return (
    <>
      <SectionTitle>{t('翻译')}</SectionTitle>
      <p className="text-sm mb-4 break-words" style={{ color: 'var(--text-secondary)' }}>
        {t('选中正文即可翻译（也支持整段）。支持 Chat Completions / Anthropic Messages / Responses 三种接口格式；只使用你自己配置的服务，不内置任何免费源。')}
      </p>
      <TranslationSettings />
      <div className="settings-card">
        <SectionTitle>{t('连接')}</SectionTitle>
        <NumberSettingRow
          label={t('翻译请求超时（秒）')}
          desc={t('单次 AI 翻译请求最长等待时间；超时按「超时」错误处理')}
          settingKey="translation.timeoutSeconds"
          fallback={60}
          min={5}
          max={300}
        />
      </div>
    </>
  )
}

export function LibraryPage() {
  const {
    defaultViewMode, setDefaultViewMode,
    defaultSortBy, setDefaultSortBy,
  } = useSettingsStore()
  const { t } = useTranslation()
  const sortOptions = useMemo(() => SORT_OPTIONS(t), [t])

  return (
    <div className="space-y-6">
      <div className="settings-card">
        <SectionTitle>{t('显示')}</SectionTitle>

        <div className="settings-row">
          <div>
            <div className="settings-row-label">{t('默认视图')}</div>
            <div className="settings-row-desc">{t('书架的默认展示方式')}</div>
          </div>
          <Segment
            options={[
              { id: 'grid' as const, label: t('网格') },
              { id: 'list' as const, label: t('列表') },
            ]}
            value={defaultViewMode}
            onChange={setDefaultViewMode}
          />
        </div>

        <div className="settings-row">
          <div>
            <div className="settings-row-label">{t('默认排序')}</div>
            <div className="settings-row-desc">{t('新打开书架时的默认排序方式')}</div>
          </div>
          <div className="settings-segment">
            {sortOptions.map((opt) => (
              <button
                key={opt.value}
                className={`settings-segment-btn ${defaultSortBy === opt.value ? 'active' : ''}`}
                onClick={() => setDefaultSortBy(opt.value)}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      <div className="settings-card">
        <SectionTitle>{t('性能')}</SectionTitle>
        <NumberSettingRow
          label={t('封面缓存上限（本）')}
          desc={t('书架最多缓存多少本书的封面，超出按「最近最少使用」淘汰；调大更占内存，调小滚动时会重新读封面')}
          settingKey="library.coverCacheSize"
          fallback={300}
          min={50}
          max={1000}
        />
      </div>

      <div className="settings-card">
        <SectionTitle>{t('书架管理')}</SectionTitle>
        <p className="text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
          {t('在书架页面可以创建书柜分组，将书籍拖入不同分类。右键点击书籍可查看详情或删除。支持拖放导入和文件对话框导入。')}
        </p>
      </div>
    </div>
  )
}

export function ZLibraryPage() {
  const [downloadPath, setDownloadPath] = useState('')
  const { t } = useTranslation()

  useEffect(() => {
    window.electronAPI.zlibGetDownloadPath().then((r: any) => {
      if (r?.path) setDownloadPath(r.path)
    })
  }, [])

  const handleChangePath = async () => {
    try {
      const result = await window.electronAPI.zlibPickDownloadFolder()
      if (result?.path) setDownloadPath(result.path)
    } catch {}
  }

  return (
    <div className="space-y-6">
      <div className="settings-card">
        <SectionTitle>{t('下载设置')}</SectionTitle>

        <div className="settings-row">
          <div>
            <div className="settings-row-label">{t('下载位置')}</div>
            <div className="settings-row-desc break-all">{downloadPath || t('加载中...')}</div>
          </div>
          <button onClick={handleChangePath} className="btn-secondary text-sm">{t('更改')}</button>
        </div>
      </div>

      <div className="settings-card">
        <SectionTitle>{t('使用说明')}</SectionTitle>
        <div className="space-y-3 text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
          <p>{t('• 内置 Z-Library 浏览器，可在应用内直接搜索、浏览和下载电子书')}</p>
          <p>{t('• 下载完成后自动导入书架，无需手动操作')}</p>
          <p>{t('• 底部工具栏支持前进/后退/刷新和线路切换')}</p>
          <p>{t('• 如果某个线路无法访问，可切换到其他镜像线路')}</p>
        </div>
      </div>
    </div>
  )
}

// ── PB-011-C：备份失败重试 ──────────────────────────────────────────
// 约束：只做「手动重试同一操作」，绝不自动循环重试。桥接层失败时 C++ 只
// reject(error 文本)，稳定错误码有时就是 token（cancelled / db_path_unavailable），
// 有时只剩英文说明 —— 这里统一分类成 { code, detail } 给 UI 展示。
export type BackupAction = 'create' | 'restore'

export interface BackupAttempt {
  action: BackupAction
  // 只有 restore 有：恢复指定备份文件的路径。当前 UI 走文件选择框，通常
  // 没有路径；保留该字段让「指定文件恢复」或脚本调用可以直接复用。
  path?: string
}

export interface BackupFailure extends BackupAttempt {
  code: string    // 稳定错误码（token）或动作级兜底码 backup_failed / restore_failed
  detail: string  // 桥接层原始 message（可能是英文说明）；空串表示没有额外信息
  attempts: number // 连续失败次数（首次失败 = 1，重试再失败 = 2）
}

const BACKUP_KNOWN_CODES = new Set([
  'backup_failed', 'restore_failed', 'cancelled', 'db_path_unavailable',
  'job_service_unavailable', 'reload_failed', 'schema_too_new', 'path_traversal',
  'backup_missing', 'manifest_missing', 'manifest_invalid', 'format_too_new',
  'payload_missing', 'payload_invalid', 'checksum_mismatch', 'pre_restore_failed',
  'write_failed', 'io_error', 'invalid_options', 'db_missing', 'db_empty',
  'db_unreadable', 'db_too_large', 'invalid_db', 'too_large',
])

export function classifyBackupError(
  action: BackupAction,
  error: unknown,
): { code: string; detail: string } {
  let message = ''
  if (typeof error === 'string') {
    message = error
  } else if (error && typeof error === 'object' && 'message' in error) {
    const raw = (error as { message?: unknown }).message
    if (typeof raw === 'string') message = raw
  }
  const trimmed = message.trim()
  if (BACKUP_KNOWN_CODES.has(trimmed)) return { code: trimmed, detail: '' }
  if (/^[a-z][a-z0-9_]{2,47}$/.test(trimmed)) return { code: trimmed, detail: '' }
  return {
    code: action === 'create' ? 'backup_failed' : 'restore_failed',
    detail: trimmed,
  }
}

// 重试 = 重放「上一次动作 + 参数」。正在忙或没有失败记录时什么都不做
// （这也是「不自动重试」的落点：只有用户点按钮才会走到这里）。
export function planBackupRetry(
  failure: BackupFailure | null,
  busy: boolean,
): BackupAttempt | null {
  if (!failure || busy) return null
  return failure.action === 'restore'
    ? { action: 'restore', path: failure.path }
    : { action: 'create' }
}

export function AboutPage() {
  const [appVersion, setAppVersion] = useState<string | null>(null)
  const [updateStatus, setUpdateStatus] = useState<'idle' | 'checking' | 'available' | 'not-available' | 'downloading' | 'downloaded' | 'error'>('idle')
  const [updateInfo, setUpdateInfo] = useState<UpdateInfo | null>(null)
  const [downloadPercent, setDownloadPercent] = useState(0)
  const [errorMessage, setErrorMessage] = useState('')
  const [diagState, setDiagState] = useState<'idle' | 'exporting' | 'done' | 'error'>('idle')
  const [diagPath, setDiagPath] = useState('')
  const [backupMsg, setBackupMsg] = useState('')
  const [backupFailure, setBackupFailure] = useState<BackupFailure | null>(null)
  const [backupProgress, setBackupProgress] = useState(0)
  const [exportAllBusy, setExportAllBusy] = useState(false)
  const [exportAllMsg, setExportAllMsg] = useState('')
  const [backupBusy, setBackupBusy] = useState<'create' | 'restore' | ''>('')
  const idleTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const { t } = useTranslation()

  useEffect(() => {
    window.electronAPI.getAppVersion().then((v) => setAppVersion(v))
    const unsubBackup = window.electronAPI.onBackupProgress((p) => setBackupProgress(p.percent))

    // checkUpdate runs on a C++ background thread and emits app:updateChecked
    // with the result. Both the startup auto-check and the manual button below
    // funnel through this event (the invoke itself returns immediately).
    const handleChecked = (info: any) => {
      if (info?.version) {
        setUpdateInfo(info); setUpdateStatus('available')
      } else {
        setUpdateStatus('not-available')
        if (idleTimerRef.current) clearTimeout(idleTimerRef.current)
        idleTimerRef.current = setTimeout(() => { setUpdateStatus('idle') }, 3000)
      }
    }

    const unsubs = [
      window.electronAPI.onUpdateChecked(handleChecked),
      window.electronAPI.onUpdateDownloaded(() => { setUpdateStatus('downloaded') }),
      window.electronAPI.onUpdateError((msg) => {
        // C++ sends error codes (download_failed_network / hash_mismatch / unknown).
        // Map to a Chinese natural key so the frontend localizes them.
        const raw = msg as any
        const code = typeof raw === 'string' ? raw : (raw?.error || '')
        const errorMap: Record<string, string> = {
          'download_failed_network': '下载失败（请检查网络后重试）',
          'hash_mismatch': '更新包校验失败（哈希不匹配），已中止安装',
          'unknown': '更新失败',
        }
        const zhText = errorMap[code]
        setErrorMessage(zhText ? t(zhText) : (code || t('更新失败'))); setUpdateStatus('error')
        if (idleTimerRef.current) clearTimeout(idleTimerRef.current)
        idleTimerRef.current = setTimeout(() => { setUpdateStatus('idle') }, 5000)
      }),
      window.electronAPI.onUpdateDownloadProgress((p) => { setUpdateStatus('downloading'); setDownloadPercent(p.percent) }),
    ]
    return () => { unsubs.forEach((u) => u()); unsubBackup(); if (idleTimerRef.current) clearTimeout(idleTimerRef.current) }
  }, [])

  const handleCopyAll = () => {
    setExportAllBusy(true); setExportAllMsg('')
    window.electronAPI.exportAnnotations(0, 'markdown')
      .then((r) => copyText(r.content).then((ok) => setExportAllMsg(ok ? t('已复制到剪贴板') : t('复制失败'))))
      .catch(() => setExportAllMsg(t('复制失败')))
      .finally(() => setExportAllBusy(false))
  }

  const handleExportAll = (format: 'markdown' | 'csv' | 'json') => {
    setExportAllBusy(true); setExportAllMsg('')
    window.electronAPI.exportAnnotations(0, format)
      .then((r) => { downloadText(r.content, r.fileName, r.mimeType); setExportAllMsg(t('已导出：{{name}}', { name: r.fileName })) })
      .catch(() => setExportAllMsg(t('导出失败')))
      .finally(() => setExportAllBusy(false))
  }

  // ── PB-011-C：创建 / 恢复 / 重试 ─────────────────────────────────
  const beginBackup = (action: BackupAction) => {
    setBackupBusy(action)
    setBackupMsg('')
    setBackupProgress(0)
  }

  const finishBackup = () => setBackupBusy('')

  // 失败统一走这里：记录动作、参数与连续失败次数，供「重试」复用。
  const failBackup = (action: BackupAction, path: string | undefined, error: unknown) => {
    const { code, detail } = classifyBackupError(action, error)
    setBackupFailure((prev) => ({
      action,
      path,
      code,
      detail,
      attempts: prev && prev.action === action ? prev.attempts + 1 : 1,
    }))
    setBackupMsg('')
  }

  const runCreateBackup = () => {
    if (backupBusy) return
    beginBackup('create')
    window.electronAPI.createBackup()
      .then((r) => {
        if (!r.ok) {
          failBackup('create', undefined, r.error || r.code)
          return
        }
        setBackupFailure(null)
        setBackupMsg(t('备份已保存：{{path}}', { path: r.path }))
      })
      .catch((e) => failBackup('create', undefined, e))
      .finally(finishBackup)
  }

  const runRestoreBackup = (path?: string) => {
    if (backupBusy) return
    beginBackup('restore')
    window.electronAPI.restoreBackup(path)
      .then((r) => {
        if (!r.ok) {
          failBackup('restore', path, r.error || r.code)
          return
        }
        setBackupFailure(null)
        setBackupMsg(t('已恢复 {{count}} 本书，正在重新加载...', { count: r.bookCount }))
        setTimeout(() => window.location.reload(), 800)
      })
      .catch((e) => {
        // 用户在文件选择框点了取消：不是失败，不显示错误 / 不提供重试。
        if (e && typeof e === 'object' && (e as { message?: string }).message === 'cancelled') {
          setBackupFailure(null)
          setBackupMsg('')
          return
        }
        failBackup('restore', path, e)
      })
      .finally(finishBackup)
  }

  const handleCreateBackup = () => runCreateBackup()

  const handleRestoreBackup = () => {
    if (!window.confirm(t('恢复会覆盖当前书库、进度与笔记。继续？'))) return
    runRestoreBackup()
  }

  // 只重放上一次失败的操作与参数；不会自动重试（必须用户点击）。
  const handleRetryBackup = () => {
    const attempt = planBackupRetry(backupFailure, !!backupBusy)
    if (!attempt) return
    if (attempt.action === 'restore' &&
        !window.confirm(t('恢复会覆盖当前书库、进度与笔记。继续？'))) {
      return
    }
    if (attempt.action === 'create') runCreateBackup()
    else runRestoreBackup(attempt.path)
  }

  const handleExportDiagnostics = () => {
    setDiagState('exporting')
    setDiagPath('')
    window.electronAPI.exportDiagnostics()
      .then((path) => {
        if (path) { setDiagPath(path); setDiagState('done') }
        else setDiagState('error')
      })
      .catch(() => setDiagState('error'))
  }

  const handleCheckUpdate = () => {
    setUpdateStatus('checking'); setErrorMessage('')
    // Background check — the result arrives via the app:updateChecked event.
    window.electronAPI.checkUpdate().catch(() => setErrorMessage(t('检查更新失败')))
  }

  return (
    <div className="space-y-6">
      {/* App info card */}
      <div className="settings-card">
        <div className="flex items-center gap-4 mb-4">
          <div className="w-14 h-14 rounded-xl flex items-center justify-center" style={{ background: 'var(--color-indigo-bg)' }}>
            <svg className="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="var(--color-indigo)" strokeWidth={1.5}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" />
            </svg>
          </div>
          <div>
            <h3 className="text-xl font-bold">ParticleBook</h3>
            <p className="text-sm" style={{ color: 'var(--text-tertiary)' }}>v{appVersion || '...'}</p>
          </div>
        </div>
        <p className="text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
          {t('内置 Z-Library 的全功能电子书阅读器，支持 EPUB、PDF、MOBI、TXT、FB2、CBZ/CBR、HTML、Markdown 等多种格式。')}
        </p>
        <div className="flex flex-wrap gap-2 mt-4">
          {['EPUB', 'PDF', 'MOBI', 'TXT', 'FB2', 'CBZ/CBR', 'HTML', 'Markdown'].map((fmt) => (
            <span key={fmt} className="px-2 py-1 text-xs rounded-md font-medium" style={{ background: 'var(--color-indigo-bg)', color: 'var(--color-indigo)' }}>{fmt}</span>
          ))}
        </div>
      </div>

      {/* Update */}
      <div className="settings-card">
        <SectionTitle>{t('软件更新')}</SectionTitle>

        {updateStatus === 'checking' && (
          <div className="flex items-center gap-2 p-3 rounded-lg mb-3" style={{ background: 'var(--notify-info-bg)', color: 'var(--notify-info-text)' }}>
            <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-current" />
            <span className="text-sm">{t('正在检查更新...')}</span>
          </div>
        )}
        {updateStatus === 'available' && updateInfo && (
          <div className="p-3 rounded-lg mb-3" style={{ background: 'var(--notify-success-bg)', color: 'var(--notify-success-text)' }}>
            <p className="text-sm font-medium mb-2">{t('发现新版本 v{{version}}', { version: updateInfo.version })}</p>
            <button onClick={() => { setUpdateStatus('downloading'); window.electronAPI.downloadUpdate(updateInfo?.downloadUrl || '', updateInfo?.sha512 || '') }} className="btn-primary text-xs px-3 py-1.5">{t('下载更新')}</button>
          </div>
        )}
        {updateStatus === 'downloading' && (
          <div className="p-3 rounded-lg mb-3" style={{ background: 'var(--notify-info-bg)', color: 'var(--notify-info-text)' }}>
            <div className="flex items-center gap-2 mb-2"><div className="animate-spin rounded-full h-4 w-4 border-b-2 border-current" /><span className="text-sm">{t('正在下载... {{percent}}%', { percent: Math.round(downloadPercent) })}</span></div>
            <div className="h-1.5 rounded-full overflow-hidden" style={{ background: 'var(--border)' }}><div className="h-full rounded-full transition-all" style={{ width: `${downloadPercent}%`, background: 'var(--accent)' }} /></div>
          </div>
        )}
        {updateStatus === 'downloaded' && (
          <div className="p-3 rounded-lg mb-3" style={{ background: 'var(--notify-success-bg)', color: 'var(--notify-success-text)' }}>
            <p className="text-sm font-medium mb-2">{t('更新已下载')}</p>
            <button onClick={() => window.electronAPI.quitAndInstall()} className="btn-primary text-xs px-3 py-1.5">{t('立即重启安装')}</button>
          </div>
        )}
        {updateStatus === 'not-available' && (
          <div className="p-3 rounded-lg mb-3 text-sm" style={{ background: 'var(--notify-success-bg)', color: 'var(--notify-success-text)' }}>{t('已是最新版本')}</div>
        )}
        {updateStatus === 'error' && (
          <div className="p-3 rounded-lg mb-3 text-sm" style={{ background: 'var(--notify-error-bg)', color: 'var(--notify-error-text)' }}>{errorMessage}</div>
        )}

        {(updateStatus === 'idle' || updateStatus === 'not-available' || updateStatus === 'error') && (
          <button onClick={handleCheckUpdate} className="btn-secondary w-full flex items-center justify-center gap-2">
            <SvgIcon d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" className="w-4 h-4" />
            {t('检查更新')}
          </button>
        )}
      </div>

      {/* Export all annotations */}
      <div className="settings-card">
        <SectionTitle>{t('导出全部标注')}</SectionTitle>
        <p className="text-sm mb-3" style={{ color: 'var(--text-secondary)' }}>
          {t('把全书库的书签、高亮与笔记导出为文件。Markdown 适合阅读，CSV 适合表格，JSON 适合备份/二次处理。')}
        </p>
        <div className="flex gap-2">
          {(['markdown', 'csv', 'json'] as const).map((fmt) => (
            <button key={fmt} onClick={() => handleExportAll(fmt)} disabled={exportAllBusy}
              className="btn-secondary flex-1">
              {fmt === 'markdown' ? 'Markdown' : fmt.toUpperCase()}
            </button>
          ))}
        </div>
        <button onClick={handleCopyAll} disabled={exportAllBusy} className="btn-secondary w-full mt-2">{t('复制 Markdown')}</button>
        <button onClick={() => window.electronAPI.openDefaultApps()} className="btn-secondary w-full mt-2">{t('打开默认应用设置')}</button>
        {exportAllMsg && <p className="mt-3 text-xs break-all" style={{ color: 'var(--text-secondary)' }}>{exportAllMsg}</p>}
      </div>

      {/* Backup / restore */}
      <div className="settings-card">
        <SectionTitle>{t('备份与恢复')}</SectionTitle>
        <p className="text-sm mb-3" style={{ color: 'var(--text-secondary)' }}>
          {t('把书库、进度、标注与设置打包成本地 zip；恢复前会自动备份当前数据。不包含书籍正文与 API Key。')}
        </p>
        <div className="flex gap-2">
          <button onClick={handleCreateBackup} disabled={!!backupBusy} className="btn-secondary flex-1 flex items-center justify-center gap-2">
            <SvgIcon d="M12 4v12m0 0l-4-4m4 4l4-4M4 20h16" className="w-4 h-4" />
            {backupBusy === 'create' ? t('正在处理...') : t('创建备份')}
          </button>
          <button onClick={handleRestoreBackup} disabled={!!backupBusy} className="btn-secondary flex-1 flex items-center justify-center gap-2">
            <SvgIcon d="M12 20V8m0 0l-4 4m4-4l4 4M4 4h16" className="w-4 h-4" />
            {backupBusy === 'restore' ? t('正在处理...') : t('恢复备份')}
          </button>
        </div>
        {!!backupBusy && (
          <div className="mt-3 h-1.5 rounded-full overflow-hidden" style={{ background: 'var(--border)' }}>
            <div className="h-full rounded-full transition-all" style={{ width: `${Math.max(5, backupProgress)}%`, background: 'var(--accent)' }} />
          </div>
        )}
        {/* PB-011-C：失败状态 + 手动重试（只重放上一次操作，绝不自动循环） */}
        {backupFailure && !backupBusy && (
          <div
            role="alert"
            aria-live="polite"
            className="mt-3 rounded-lg px-3 py-2.5 text-xs"
            style={{ border: '1px solid var(--notify-error-text)', background: 'var(--surface-hover)' }}
          >
            <div className="flex items-start gap-3">
              <div className="min-w-0 flex-1 space-y-1">
                <p className="font-medium break-all" style={{ color: 'var(--notify-error-text)' }}>
                  {backupFailure.action === 'create' ? t('备份失败') : t('恢复失败')}
                  {' · '}
                  {t('错误码：{{code}}', { code: backupFailure.code })}
                </p>
                {backupFailure.detail && (
                  <p className="break-all" style={{ color: 'var(--text-secondary)' }}>
                    {backupFailure.detail}
                  </p>
                )}
                <p style={{ color: 'var(--text-secondary)' }}>
                  {t('上次操作：{{op}}（第 {{attempt}} 次尝试）', {
                    op: backupFailure.action === 'create' ? t('创建备份') : t('恢复备份'),
                    attempt: backupFailure.attempts,
                  })}
                </p>
                {backupFailure.path && (
                  <p className="break-all" style={{ color: 'var(--text-secondary)' }}>
                    {t('备份文件：{{path}}', { path: backupFailure.path })}
                  </p>
                )}
                <p style={{ color: 'var(--text-secondary)' }}>
                  {t('不会自动重试；修好后可点「重试」。')}
                </p>
              </div>
              <button
                type="button"
                onClick={handleRetryBackup}
                disabled={!!backupBusy}
                className="btn-secondary shrink-0"
                aria-label={t('重试')}
              >
                {t('重试')}
              </button>
            </div>
          </div>
        )}

        {backupMsg && (
          <p className="mt-3 text-xs break-all" style={{ color: 'var(--notify-success-text)' }}>
            {backupMsg}
          </p>
        )}
      </div>

      {/* Diagnostics */}
      <div className="settings-card">
        <SectionTitle>{t('诊断')}</SectionTitle>
        <p className="text-sm mb-3" style={{ color: 'var(--text-secondary)' }}>
          {t('导出本地诊断包：日志、版本、系统信息与脱敏设置（不含 API Key）。完全本地，不上传。')}
        </p>
        <button
          onClick={handleExportDiagnostics}
          disabled={diagState === 'exporting'}
          className="btn-secondary w-full flex items-center justify-center gap-2"
        >
          <SvgIcon d="M12 10v6m0 0l-3-3m3 3l3-3M3 17V7a2 2 0 012-2h6l2 2h6a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2z" className="w-4 h-4" />
          {diagState === 'exporting' ? t('正在导出...') : t('导出诊断包')}
        </button>
        {diagState === 'done' && (
          <p className="mt-3 text-xs break-all" style={{ color: 'var(--notify-success-text)' }}>{t('诊断包已导出：{{path}}', { path: diagPath })}</p>
        )}
        {diagState === 'error' && (
          <p className="mt-3 text-xs" style={{ color: 'var(--notify-error-text)' }}>{t('导出失败')}</p>
        )}
      </div>

      {/* Credits */}
      <div className="settings-card">
        <SectionTitle>{t('致谢')}</SectionTitle>
        <div className="text-sm leading-relaxed space-y-1" style={{ color: 'var(--text-secondary)' }}>
          <p>{t('作者：')}<span style={{ color: 'var(--text-primary)' }}>ParticleLight</span></p>
          <p>{t('框架：')}C++ Win32 + WebView2 + React + TypeScript + Zustand</p>
          <p>{t('PDF 引擎：')}MuPDF</p>
          <p>{t('许可证：')}MIT</p>
        </div>
      </div>
    </div>
  )
}

// ─── Main ───

export function BookSourcesPage() {
  return <BookSourceManager />
}

