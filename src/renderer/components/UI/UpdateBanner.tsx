import { useState, useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'

interface UpdateInfo { version: string; releaseDate?: string; releaseNotes?: string; downloadUrl?: string; fileName?: string; sha512?: string }

export function UpdateBanner() {
  const { t } = useTranslation()
  const [updateInfo, setUpdateInfo] = useState<UpdateInfo | null>(null)
  const [downloading, setDownloading] = useState(false)
  const [downloaded, setDownloaded] = useState(false)
  const dismissedVersion = useRef<string | null>(null)

  useEffect(() => {
    const onUpdate = (info: UpdateInfo) => {
      if (dismissedVersion.current !== info.version) setUpdateInfo(info)
    }
    const onCustom = (e: Event) => onUpdate((e as CustomEvent).detail)
    // 更新可用性走的是【DOM 事件】'pb:updateAvailable'（由 App.tsx 收到
    // onUpdateChecked 后派发），与桥接事件同名但不同通道。此前这里还订阅了
    // electronAPI.onUpdateAvailable —— 而 C++ 从不 emit app:updateAvailable，
    // 那是个永不触发的死订阅，已随死接口清理一并移除。
    window.addEventListener('pb:updateAvailable', onCustom)

    const unsubs = [
      window.electronAPI.onUpdateDownloaded(() => {
        setDownloading(false); setDownloaded(true)
      }),
      window.electronAPI.onUpdateError(() => {
        setDownloading(false); setDownloaded(false)
      }),
    ]
    return () => {
      unsubs.forEach((u) => u())
      window.removeEventListener('pb:updateAvailable', onCustom)
    }
  }, [])

  const handleDismiss = () => {
    if (updateInfo) dismissedVersion.current = updateInfo.version
    setUpdateInfo(null); setDownloading(false); setDownloaded(false)
  }

  const handleDownload = async () => {
    setDownloading(true)
    const result = await window.electronAPI.downloadUpdate(updateInfo?.downloadUrl || '', updateInfo?.sha512 || '')
    if (!result) setDownloading(false)
  }

  const handleRestart = () => {
    window.electronAPI.quitAndInstall()
  }

  if (!updateInfo) return null

  return (
    <div className="fixed top-0 left-0 right-0 z-[9999] flex items-center justify-between px-5 py-2.5 animate-scale-in"
      // 横幅是 fixed 覆盖层（会盖住顶栏的应用名）。注意 --md-sys-color-primary-container
      // 映射到 --color-indigo-bg，是 alpha 只有 8%~15% 的 rgba —— 直接拿它当背景等于半透明，
      // 底下的 'ParticleBook' 会透上来跟横幅文字叠在一起（v2.1.0 起就有，2026-10-31 真机复现）。
      // 所以用「同色 gradient 叠一层 + 页面底色」组成不透明背景：颜色仍跟随主题与主题色。
      style={{ background: 'linear-gradient(var(--md-sys-color-primary-container), var(--md-sys-color-primary-container)), var(--bg)', color: 'var(--md-sys-color-on-primary-container)', borderBottom: '1px solid var(--md-sys-color-outline-variant)' }} role="status">
      <div className="flex items-center gap-3">
        <svg className="w-5 h-5 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 17l9.2-9.2M17 17V7H7M7 7h5m5 10v-5" /></svg>
        {downloaded ? (
          <span className="m3-body-medium">{t('v{{version}} 已下载，重启后自动安装', { version: updateInfo.version })}</span>
        ) : (
          <span className="m3-body-medium">{t('发现新版本 v{{version}}', { version: updateInfo.version })}</span>
        )}
        {downloading && <span className="text-xs opacity-70">{t('下载中...')}</span>}
      </div>
      <div className="flex items-center gap-2">
        {downloaded ? (
          <button onClick={handleRestart} className="m3-filled-button !min-h-9">{t('立即重启')}</button>
        ) : downloading ? null : (
          <button onClick={handleDownload} className="m3-filled-button !min-h-9">{t('下载更新')}</button>
        )}
        <button onClick={handleDismiss} className="m3-icon-button !w-9 !h-9" aria-label={t('关闭')}><svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" /></svg></button>
      </div>
    </div>
  )
}
