import { useState, useEffect, useMemo, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { useSettingsStore } from '../../stores/settingsStore'
import { NavRail, ModalNavDrawer, type NavRailItem } from '../UI/NavRail'
import { useWindowSizeClass } from '../../hooks/useWindowSizeClass'
import {
  NAV_ITEMS,
  SvgIcon,
  AppearancePage,
  ReadingPage,
  TranslationPage,
  LibraryPage,
  ZLibraryPage,
  AboutPage,
  BookSourcesPage,
  type GlobalSettingsProps,
  type NavKey,
} from './GlobalSettingsPages'

const ICON_MENU = 'M4 6h16M4 12h16M4 18h16'

export function GlobalSettings({ onBack }: GlobalSettingsProps) {
  const [activeNav, setActiveNav] = useState<NavKey>('appearance')
  const { t } = useTranslation()
  const navItems = useMemo(() => NAV_ITEMS(t), [t])

  // ── M3 window size class（与书库页同一套档位）─────────────────
  // expanded (>= 840px)：240px 常驻设置抽屉（改造前行为）
  // medium   (640–839) ：80px rail，设置导航是固定 destination，直接 icon+label
  // compact  (< 640)   ：顶栏汉堡 + 模态抽屉
  const sizeClass = useWindowSizeClass()
  const isCompact = sizeClass === 'compact'
  const isMedium = sizeClass === 'medium'
  const [navOpen, setNavOpen] = useState(false)
  const closeNav = useCallback(() => setNavOpen(false), [])
  useEffect(() => { if (sizeClass === 'expanded') setNavOpen(false) }, [sizeClass])

  const selectNav = useCallback((key: NavKey) => {
    setActiveNav(key)
    setNavOpen(false)
  }, [])

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
      case 'translation': return <TranslationPage />
      case 'library': return <LibraryPage />
      case 'zlibrary': return <ZLibraryPage />
      case 'about': return <AboutPage />
      case 'booksources': return <BookSourcesPage />
    }
  }

  // 设置导航内容：expanded 常驻抽屉与模态抽屉共用同一份 JSX（不会同时挂载）
  const navList = (
    <div className="space-y-0.5">
      {navItems.map((item) => (
        <button
          key={item.key}
          onClick={() => selectNav(item.key)}
          className={`settings-nav-item ${activeNav === item.key ? 'active' : ''}`}
          style={{ position: 'relative' }}
          aria-current={activeNav === item.key ? 'page' : undefined}
        >
          <SvgIcon d={item.icon} className="w-[18px] h-[18px] shrink-0" />
          {item.label}
        </button>
      ))}
    </div>
  )

  // medium 档 rail：设置导航本身就是固定 destination 列表，直接 icon + label
  const railItems = useMemo<NavRailItem[]>(
    () => navItems.map((item) => ({
      id: item.key,
      label: item.label,
      icon: <SvgIcon d={item.icon} />,
      active: activeNav === item.key,
      onSelect: () => setActiveNav(item.key),
    })),
    [navItems, activeNav]
  )

  return (
    <div className="h-screen flex flex-col" style={{ background: 'var(--bg)' }}>
      {/* M3 top app bar */}
      <header className="m3-top-app-bar drag-region shrink-0">
        {/* compact (< 640px)：设置抽屉收起，顶栏最左侧出现汉堡按钮 */}
        {isCompact && (
          <button
            type="button"
            onClick={() => setNavOpen(true)}
            className="no-drag m3-icon-button"
            aria-label={t('打开设置导航')}
            aria-haspopup="dialog"
            aria-expanded={navOpen}
            title={t('打开设置导航')}
            data-testid="settings-nav-toggle"
          >
            <SvgIcon d={ICON_MENU} />
          </button>
        )}
        <button onClick={onBack} className="no-drag m3-icon-button" aria-label={t('返回')} title={t('返回')}>
          <SvgIcon d="M15 19l-7-7 7-7" />
        </button>
        <div className="no-drag min-w-0">
          <h1 className="m3-title-large truncate">{t('设置')}</h1>
          <p className="m3-label-medium truncate">{navItems.find((item) => item.key === activeNav)?.label}</p>
        </div>
      </header>

      {/* Body */}
      <div className="flex-1 flex min-h-0">
        {/* M3 navigation：expanded 常驻抽屉 / medium 80px rail / compact 汉堡 */}
        {sizeClass === 'expanded' && (
          <nav className="m3-nav-drawer w-60 shrink-0 p-3 overflow-y-auto" aria-label={t('设置导航')} data-testid="settings-nav-drawer">
            {navList}
          </nav>
        )}
        {isMedium && <NavRail items={railItems} aria-label={t('设置导航')} />}

        {/* Right content */}
        <main className="flex-1 overflow-y-auto px-6 py-6 lg:px-10 lg:py-8">
          {/* 内容列铺满可用宽度：此前是 max-w-2xl，窗口一宽右侧就空一大片
              （用户截图："全屏下设置界面全挤到左侧"）。按用户要求改为用满，
              不做居中留白。 */}
          <div className="w-full max-w-[1200px] mx-auto">
            {renderPage()}
          </div>
        </main>
      </div>

      {/* compact 汉堡打开的模态导航抽屉 */}
      <ModalNavDrawer
        open={navOpen && sizeClass !== 'expanded'}
        onClose={closeNav}
        title={t('设置')}
        closeLabel={t('关闭')}
      >
        <div className="px-3 py-3">{navList}</div>
      </ModalNavDrawer>
    </div>
  )
}
