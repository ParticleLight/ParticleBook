import { useState, useEffect, useRef, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import i18n from '../../i18n'
import { useSettingsStore, type AccentColor } from '../../stores/settingsStore'

interface GlobalSettingsProps {
  onBack: () => void
}

interface UpdateInfo {
  version: string
  downloadUrl?: string
  fileName?: string
  sha512?: string
}

type NavKey = 'appearance' | 'reading' | 'library' | 'zlibrary' | 'about'

const NAV_ITEMS = (t: TFunction): { key: NavKey; label: string; icon: string }[] => [
  { key: 'appearance', label: t('外观'), icon: 'M7 21a4 4 0 01-4-4V5a2 2 0 012-2h4a2 2 0 012 2v12a4 4 0 01-4 4zm0 0h12a2 2 0 002-2v-4a2 2 0 00-2-2h-2.343M11 7.343l1.657-1.657a2 2 0 012.828 0l2.829 2.829a2 2 0 010 2.828l-8.486 8.485M7 17h.01' },
  { key: 'reading', label: t('阅读'), icon: 'M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253' },
  { key: 'library', label: t('书架'), icon: 'M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10' },
  { key: 'zlibrary', label: 'Z-Library', icon: 'M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9' },
  { key: 'about', label: t('关于'), icon: 'M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z' },
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

function SvgIcon({ d, className = 'w-5 h-5' }: { d: string; className?: string }) {
  return (
    <svg className={className} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d={d} />
    </svg>
  )
}

function Toggle({ value, onChange }: { value: boolean; onChange: (v: boolean) => void }) {
  return (
    <button
      className={`settings-toggle ${value ? 'active' : ''}`}
      onClick={() => onChange(!value)}
      role="switch"
      aria-checked={value}
    />
  )
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
  return <h3 className="text-[13px] font-semibold uppercase tracking-wider mb-3" style={{ color: 'var(--text-tertiary)' }}>{children}</h3>
}

// ─── Settings pages ───

function AppearancePage() {
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
            <input type="range" min={12} max={32} value={fontSize} onChange={(e) => setFontSize(Number(e.target.value))} className="w-28" />
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
            <input type="range" min={1.2} max={3} step={0.1} value={lineHeight} onChange={(e) => setLineHeight(Number(e.target.value))} className="w-28" />
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
            <input type="range" min={0} max={100} value={margin} onChange={(e) => setMargin(Number(e.target.value))} className="w-28" />
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

function ReadingPage() {
  const {
    pageTurnMode, setPageTurnMode,
    autoSaveProgress, setAutoSaveProgress,
    showReadingTime, setShowReadingTime,
  } = useSettingsStore()
  const { t } = useTranslation()

  return (
    <div className="space-y-6">
      <div className="settings-card">
        <SectionTitle>{t('翻页')}</SectionTitle>

        <div className="settings-row">
          <div>
            <div className="settings-row-label">{t('翻页方式')}</div>
            <div className="settings-row-desc">{t('选择点击翻页或滚动浏览')}</div>
          </div>
          <Segment
            options={[
              { id: 'click' as const, label: t('点击翻页') },
              { id: 'scroll' as const, label: t('滚动浏览') },
            ]}
            value={pageTurnMode}
            onChange={setPageTurnMode}
          />
        </div>
      </div>

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

function LibraryPage() {
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
        <SectionTitle>{t('书架管理')}</SectionTitle>
        <p className="text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
          {t('在书架页面可以创建书柜分组，将书籍拖入不同分类。右键点击书籍可查看详情或删除。支持拖放导入和文件对话框导入。')}
        </p>
      </div>
    </div>
  )
}

function ZLibraryPage() {
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

function AboutPage() {
  const [appVersion, setAppVersion] = useState<string | null>(null)
  const [updateStatus, setUpdateStatus] = useState<'idle' | 'checking' | 'available' | 'not-available' | 'downloading' | 'downloaded' | 'error'>('idle')
  const [updateInfo, setUpdateInfo] = useState<UpdateInfo | null>(null)
  const [downloadPercent, setDownloadPercent] = useState(0)
  const [errorMessage, setErrorMessage] = useState('')
  const idleTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const { t } = useTranslation()

  useEffect(() => {
    window.electronAPI.getAppVersion().then((v) => setAppVersion(v))

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
    return () => { unsubs.forEach((u) => u()); if (idleTimerRef.current) clearTimeout(idleTimerRef.current) }
  }, [])

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

export function GlobalSettings({ onBack }: GlobalSettingsProps) {
  const [activeNav, setActiveNav] = useState<NavKey>('appearance')
  const { t } = useTranslation()
  const navItems = useMemo(() => NAV_ITEMS(t), [t])

  // Apply accent color to document
  const accentColor = useSettingsStore((s) => s.accentColor)
  useEffect(() => {
    const root = document.documentElement
    if (accentColor === 'blue') {
      root.removeAttribute('data-accent')
    } else {
      root.setAttribute('data-accent', accentColor)
    }
  }, [accentColor])

  const renderPage = () => {
    switch (activeNav) {
      case 'appearance': return <AppearancePage />
      case 'reading': return <ReadingPage />
      case 'library': return <LibraryPage />
      case 'zlibrary': return <ZLibraryPage />
      case 'about': return <AboutPage />
    }
  }

  return (
    <div className="h-screen flex flex-col" style={{ background: 'var(--bg)' }}>
      {/* Header */}
      <header className="drag-region flex items-center gap-4 px-6 py-3.5 shrink-0" style={{ borderBottom: '1px solid var(--border)' }}>
        <button onClick={onBack} className="no-drag p-1.5 rounded-lg transition-colors hover:bg-[var(--surface-hover)]" style={{ color: 'var(--text-secondary)' }}>
          <SvgIcon d="M15 19l-7-7 7-7" />
        </button>
        <h1 className="no-drag text-base font-semibold" style={{ color: 'var(--text-primary)' }}>{t('设置')}</h1>
      </header>

      {/* Body */}
      <div className="flex-1 flex min-h-0">
        {/* Left nav */}
        <nav className="w-56 shrink-0 p-3 overflow-y-auto" style={{ borderRight: '1px solid var(--border)' }}>
          <div className="space-y-0.5">
            {navItems.map((item) => (
              <button
                key={item.key}
                onClick={() => setActiveNav(item.key)}
                className={`settings-nav-item ${activeNav === item.key ? 'active' : ''}`}
                style={{ position: 'relative' }}
              >
                <SvgIcon d={item.icon} className="w-[18px] h-[18px] shrink-0" />
                {item.label}
              </button>
            ))}
          </div>
        </nav>

        {/* Right content */}
        <main className="flex-1 overflow-y-auto p-8">
          <div className="max-w-2xl">
            {renderPage()}
          </div>
        </main>
      </div>
    </div>
  )
}
