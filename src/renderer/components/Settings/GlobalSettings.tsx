import { useState, useEffect, useRef } from 'react'
import { useSettingsStore } from '../../stores/settingsStore'

interface GlobalSettingsProps {
  onBack: () => void
}

interface UpdateInfo {
  version: string
  releaseDate?: string
  releaseNotes?: string
  downloadUrl?: string
  fileName?: string
}

export function GlobalSettings({ onBack }: GlobalSettingsProps) {
  const {
    theme, setTheme,
    fontSize, setFontSize,
    fontFamily, setFontFamily,
    lineHeight, setLineHeight,
    margin, setMargin,
    textAlign, setTextAlign,
  } = useSettingsStore()

  const [appVersion, setAppVersion] = useState<string | null>(null)
  const [updateStatus, setUpdateStatus] = useState<'idle' | 'checking' | 'available' | 'not-available' | 'downloading' | 'downloaded' | 'error'>('idle')
  const [updateInfo, setUpdateInfo] = useState<UpdateInfo | null>(null)
  const [downloadPercent, setDownloadPercent] = useState(0)
  const [errorMessage, setErrorMessage] = useState('')
  const [downloadPath, setDownloadPath] = useState('')
  const idleTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const fonts = [
    { label: '衬线体', value: 'Georgia, Noto Serif SC, serif' },
    { label: '无衬线', value: 'Inter, Noto Sans SC, sans-serif' },
    { label: '等宽', value: 'JetBrains Mono, monospace' },
  ]

  const themes = [
    { id: 'light' as const, label: '亮色模式', desc: '适合白天阅读', icon: 'M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z' },
    { id: 'dark' as const, label: '暗色模式', desc: '适合夜间阅读', icon: 'M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z' },
    { id: 'sepia' as const, label: '护眼模式', desc: '降低蓝光，缓解疲劳', icon: 'M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z' },
  ]

  useEffect(() => {
    window.electronAPI.getAppVersion().then((v) => setAppVersion(v))
    window.electronAPI.zlibGetDownloadPath().then((r: any) => {
      if (r?.path) setDownloadPath(r.path)
    })
  }, [])

  useEffect(() => {
    const unsubs = [
      window.electronAPI.onUpdateAvailable((info) => {
        setUpdateInfo(info)
        setUpdateStatus('available')
      }),
      window.electronAPI.onUpdateNotAvailable(() => {
        setUpdateStatus('not-available')
        if (idleTimerRef.current) clearTimeout(idleTimerRef.current)
        idleTimerRef.current = setTimeout(() => { idleTimerRef.current = null; setUpdateStatus('idle') }, 3000)
      }),
      window.electronAPI.onUpdateDownloaded(() => {
        setUpdateStatus('downloaded')
      }),
      window.electronAPI.onUpdateError((msg) => {
        setErrorMessage(msg)
        setUpdateStatus('error')
        if (idleTimerRef.current) clearTimeout(idleTimerRef.current)
        idleTimerRef.current = setTimeout(() => { idleTimerRef.current = null; setUpdateStatus('idle') }, 5000)
      }),
      window.electronAPI.onUpdateDownloadProgress((p) => {
        setUpdateStatus('downloading')
        setDownloadPercent(p.percent)
      }),
    ]
    return () => {
      unsubs.forEach((u) => u())
      if (idleTimerRef.current) clearTimeout(idleTimerRef.current)
    }
  }, [])

  const handleCheckUpdate = async () => {
    setUpdateStatus('checking')
    setErrorMessage('')
    try {
      const info = await window.electronAPI.checkUpdate()
      if (info?.version) {
        setUpdateInfo(info)
        setUpdateStatus('available')
      } else {
        setUpdateStatus('not-available')
        if (idleTimerRef.current) clearTimeout(idleTimerRef.current)
        idleTimerRef.current = setTimeout(() => { idleTimerRef.current = null; setUpdateStatus('idle') }, 3000)
      }
    } catch {
      setErrorMessage('检查更新失败，请检查网络连接')
      setUpdateStatus('error')
    }
  }

  const handleDownloadUpdate = async () => {
    setUpdateStatus('downloading')
    setErrorMessage('')
    const result = await window.electronAPI.downloadUpdate(updateInfo?.downloadUrl || '')
    if (!result) {
      setErrorMessage('无法启动下载')
      setUpdateStatus('error')
    }
  }

  const handleQuitAndInstall = () => {
    window.electronAPI.quitAndInstall()
  }

  const handleChangeDownloadPath = async () => {
    try {
      const result = await window.electronAPI.zlibPickDownloadFolder()
      if (result?.path) setDownloadPath(result.path)
    } catch {}
  }

  return (
    <div className="h-screen flex flex-col bg-[var(--reader-bg)]">
      {/* Header */}
      <header className="drag-region flex items-center justify-between px-6 py-4 border-b border-[var(--reader-border)] bg-[var(--reader-bg)]/80 backdrop-blur-sm">
        <div className="no-drag flex items-center gap-4">
          <button
            onClick={onBack}
            className="p-2 rounded-lg hover:bg-[var(--reader-sidebar)] text-[var(--reader-text)] opacity-60 hover:opacity-100 transition-colors"
          >
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
            </svg>
          </button>
          <h1 className="text-xl font-bold text-[var(--reader-text)]">全局设置</h1>
        </div>
      </header>

      {/* Content */}
      <main className="flex-1 overflow-y-auto">
        <div className="max-w-2xl mx-auto p-8 space-y-10">

          {/* Theme */}
          <section>
            <h2 className="text-lg font-semibold text-[var(--reader-text)] mb-4">外观主题</h2>
            <div className="grid grid-cols-3 gap-4">
              {themes.map((t) => (
                <button
                  key={t.id}
                  onClick={() => setTheme(t.id)}
                  className={`p-4 rounded-xl border-2 transition-all text-left ${
                    theme === t.id
                      ? 'border-[var(--border-focus)] bg-[var(--color-indigo-bg)]'
                      : 'border-[var(--reader-border)] hover:border-[var(--border-focus)] bg-[var(--reader-sidebar)]'
                  }`}
                >
                  <svg className="w-6 h-6 mb-2 text-[var(--reader-text)]" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d={t.icon} />
                  </svg>
                  <p className="font-medium text-[var(--reader-text)]">{t.label}</p>
                  <p className="text-xs text-[var(--reader-text)] opacity-50 mt-1">{t.desc}</p>
                </button>
              ))}
            </div>
          </section>

          {/* Font Family */}
          <section>
            <h2 className="text-lg font-semibold text-[var(--reader-text)] mb-4">字体</h2>
            <div className="flex flex-wrap gap-3">
              {fonts.map((f) => (
                <button
                  key={f.value}
                  onClick={() => setFontFamily(f.value)}
                  className={`px-5 py-3 rounded-xl text-sm transition-all border-2 ${
                    fontFamily === f.value
                      ? 'border-[var(--border-focus)] bg-[var(--color-indigo-bg)] text-[var(--reader-text)]'
                      : 'border-[var(--reader-border)] bg-[var(--reader-sidebar)] text-[var(--reader-text)] opacity-70 hover:opacity-100'
                  }`}
                  style={{ fontFamily: f.value }}
                >
                  {f.label}
                </button>
              ))}
            </div>
          </section>

          {/* Font Size */}
          <section>
            <h2 className="text-lg font-semibold text-[var(--reader-text)] mb-4">字号</h2>
            <div className="flex items-center gap-4">
              <button
                onClick={() => setFontSize(Math.max(12, fontSize - 1))}
                className="w-10 h-10 rounded-lg bg-[var(--reader-sidebar)] border border-[var(--reader-border)] text-[var(--reader-text)] hover:bg-[var(--reader-border)] transition-colors font-bold"
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
                className="w-10 h-10 rounded-lg bg-[var(--reader-sidebar)] border border-[var(--reader-border)] text-[var(--reader-text)] hover:bg-[var(--reader-border)] transition-colors font-bold"
              >
                A+
              </button>
              <span className="text-[var(--reader-text)] opacity-60 w-12 text-center">{fontSize}px</span>
            </div>
          </section>

          {/* Line Height */}
          <section>
            <h2 className="text-lg font-semibold text-[var(--reader-text)] mb-4">行距</h2>
            <div className="flex items-center gap-4">
              <span className="text-[var(--reader-text)] opacity-50 text-sm">紧凑</span>
              <input
                type="range"
                min="1.2"
                max="3"
                step="0.1"
                value={lineHeight}
                onChange={(e) => setLineHeight(Number(e.target.value))}
                className="flex-1" style={{ accentColor: 'var(--accent)' }}
              />
              <span className="text-[var(--reader-text)] opacity-50 text-sm">宽松</span>
              <span className="text-[var(--reader-text)] opacity-60 w-12 text-center">{lineHeight.toFixed(1)}</span>
            </div>
          </section>

          {/* Margin */}
          <section>
            <h2 className="text-lg font-semibold text-[var(--reader-text)] mb-4">边距</h2>
            <div className="flex items-center gap-4">
              <span className="text-[var(--reader-text)] opacity-50 text-sm">窄</span>
              <input
                type="range"
                min="0"
                max="100"
                value={margin}
                onChange={(e) => setMargin(Number(e.target.value))}
                className="flex-1" style={{ accentColor: 'var(--accent)' }}
              />
              <span className="text-[var(--reader-text)] opacity-50 text-sm">宽</span>
              <span className="text-[var(--reader-text)] opacity-60 w-12 text-center">{margin}px</span>
            </div>
          </section>

          {/* Text Align */}
          <section>
            <h2 className="text-lg font-semibold text-[var(--reader-text)] mb-4">对齐方式</h2>
            <div className="flex gap-3">
              <button
                onClick={() => setTextAlign('left')}
                className={`flex-1 py-3 rounded-xl text-sm transition-all border-2 ${
                  textAlign === 'left'
                    ? 'border-[var(--border-focus)] bg-[var(--color-indigo-bg)] text-[var(--reader-text)]'
                    : 'border-[var(--reader-border)] bg-[var(--reader-sidebar)] text-[var(--reader-text)] opacity-70 hover:opacity-100'
                }`}
              >
                左对齐
              </button>
              <button
                onClick={() => setTextAlign('justify')}
                className={`flex-1 py-3 rounded-xl text-sm transition-all border-2 ${
                  textAlign === 'justify'
                    ? 'border-[var(--border-focus)] bg-[var(--color-indigo-bg)] text-[var(--reader-text)]'
                    : 'border-[var(--reader-border)] bg-[var(--reader-sidebar)] text-[var(--reader-text)] opacity-70 hover:opacity-100'
                }`}
              >
                两端对齐
              </button>
            </div>
          </section>

          {/* Download Path */}
          <section>
            <h2 className="text-lg font-semibold text-[var(--reader-text)] mb-4">Z-Library 下载位置</h2>
            <div className="bg-[var(--reader-sidebar)] rounded-xl border border-[var(--reader-border)] p-4">
              <p className="text-sm text-[var(--reader-text)] opacity-70 mb-3 break-all">{downloadPath || '加载中...'}</p>
              <button
                onClick={handleChangeDownloadPath}
                className="px-4 py-2 rounded-lg text-sm font-medium transition-colors"
                style={{ color: 'var(--color-indigo)', backgroundColor: 'var(--color-indigo-bg)' }}
              >
                更改文件夹
              </button>
            </div>
          </section>

          {/* Info */}
          <section className="pt-6 border-t border-[var(--reader-border)]">
            <p className="text-sm text-[var(--reader-text)] opacity-40">
              此处的设置对所有书籍生效。如果在阅读某本书时单独修改了设置，该书将使用独立设置，不受此处更改影响。
            </p>
          </section>

          {/* About */}
          <section className="pt-6 border-t border-[var(--reader-border)]">
            <h2 className="text-lg font-semibold text-[var(--reader-text)] mb-4">关于</h2>
            <div className="bg-[var(--reader-sidebar)] rounded-xl border border-[var(--reader-border)] p-6">
              <div className="flex items-center gap-4 mb-4">
                <div className="w-14 h-14 rounded-xl flex items-center justify-center" style={{ backgroundColor: 'var(--color-indigo-bg)' }}>
                  <svg className="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor" style={{ color: 'var(--color-indigo)' }}>
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" />
                  </svg>
                </div>
                <div>
                  <h3 className="text-xl font-bold text-[var(--reader-text)]">ParticleBook</h3>
                  <p className="text-sm text-[var(--reader-text)] opacity-50">v{appVersion || '...'}</p>
                </div>
              </div>

              {/* Update section */}
              <div className="mb-4">
                {updateStatus === 'checking' && (
                  <div className="flex items-center gap-2 p-3 rounded-lg" style={{ color: 'var(--notify-info-text)', backgroundColor: 'var(--notify-info-bg)' }}>
                    <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-current" />
                    <span className="text-sm">正在检查更新...</span>
                  </div>
                )}
                {updateStatus === 'available' && updateInfo && (
                  <div className="p-3 rounded-lg" style={{ color: 'var(--notify-success-text)', backgroundColor: 'var(--notify-success-bg)' }}>
                    <p className="text-sm font-medium mb-2">发现新版本 v{updateInfo.version}</p>
                    {updateInfo.releaseNotes && (
                      <p className="text-xs opacity-70 mb-2">{updateInfo.releaseNotes}</p>
                    )}
                    <button
                      onClick={handleDownloadUpdate}
                      className="px-3 py-1.5 text-xs rounded-md font-medium"
                      style={{ backgroundColor: 'var(--color-green)', color: '#fff' }}
                    >
                      下载更新
                    </button>
                  </div>
                )}
                {updateStatus === 'downloading' && (
                  <div className="p-3 rounded-lg" style={{ color: 'var(--notify-info-text)', backgroundColor: 'var(--notify-info-bg)' }}>
                    <div className="flex items-center gap-2 mb-1">
                      <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-current" />
                      <span className="text-sm">正在下载更新...</span>
                    </div>
                    <div className="h-1.5 rounded-full mt-2" style={{ backgroundColor: 'var(--reader-border)' }}>
                      <div className="h-full rounded-full transition-all" style={{ width: `${downloadPercent}%`, backgroundColor: 'var(--color-indigo)' }} />
                    </div>
                  </div>
                )}
                {updateStatus === 'downloaded' && (
                  <div className="p-3 rounded-lg" style={{ color: 'var(--notify-success-text)', backgroundColor: 'var(--notify-success-bg)' }}>
                    <p className="text-sm font-medium mb-2">更新已下载，重启软件即可安装</p>
                    <button
                      onClick={handleQuitAndInstall}
                      className="px-3 py-1.5 text-xs rounded-md font-medium"
                      style={{ backgroundColor: 'var(--color-green)', color: '#fff' }}
                    >
                      立即重启
                    </button>
                  </div>
                )}
                {updateStatus === 'not-available' && (
                  <div className="p-3 rounded-lg text-sm" style={{ color: 'var(--notify-success-text)', backgroundColor: 'var(--notify-success-bg)' }}>
                    已是最新版本
                  </div>
                )}
                {updateStatus === 'error' && (
                  <div className="p-3 rounded-lg text-sm" style={{ color: 'var(--notify-error-text)', backgroundColor: 'var(--notify-error-bg)' }}>
                    {errorMessage}
                  </div>
                )}
                {(updateStatus === 'idle' || updateStatus === 'not-available' || updateStatus === 'error') && (
                  <button
                    onClick={handleCheckUpdate}
                    className="w-full px-4 py-2.5 rounded-lg text-sm font-medium transition-colors flex items-center justify-center gap-2"
                    style={{ color: 'var(--color-indigo)', backgroundColor: 'var(--color-indigo-bg)' }}
                  >
                    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                    </svg>
                    检查更新
                  </button>
                )}
              </div>

              <p className="text-sm text-[var(--reader-text)] opacity-70 leading-relaxed mb-3">
                一款内置 Z-Library 的全功能电子书阅读器，支持 EPUB、PDF、MOBI、TXT、FB2、CBZ/CBR、HTML、Markdown 等多种格式。
              </p>
              <p className="text-sm text-[var(--reader-text)] opacity-70 leading-relaxed mb-3">
                提供书架管理、阅读进度同步、书签、高亮标注、笔记、多种主题切换等功能，为您带来舒适的阅读体验。
              </p>
              <div className="flex flex-wrap gap-2 mt-4">
                {['EPUB', 'PDF', 'MOBI', 'TXT', 'FB2', 'CBZ/CBR', 'HTML', 'Markdown'].map((fmt) => (
                  <span key={fmt} className="px-2 py-1 text-xs rounded-md" style={{ color: 'var(--color-indigo)', backgroundColor: 'var(--color-indigo-bg)' }}>{fmt}</span>
                ))}
              </div>
            </div>
          </section>

        </div>
      </main>
    </div>
  )
}
