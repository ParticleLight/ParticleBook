import { useState, useEffect, useRef } from 'react'

interface UpdateInfo { version: string; releaseDate?: string; releaseNotes?: string; downloadUrl?: string; fileName?: string; sha512?: string }

export function UpdateBanner() {
  const [updateInfo, setUpdateInfo] = useState<UpdateInfo | null>(null)
  const [downloading, setDownloading] = useState(false)
  const [downloaded, setDownloaded] = useState(false)
  const dismissedVersion = useRef<string | null>(null)

  useEffect(() => {
    const onUpdate = (info: UpdateInfo) => {
      if (dismissedVersion.current !== info.version) setUpdateInfo(info)
    }
    const onCustom = (e: Event) => onUpdate((e as CustomEvent).detail)
    window.addEventListener('pb:updateAvailable', onCustom)

    const unsubs = [
      window.electronAPI.onUpdateAvailable(onUpdate),
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
      style={{ background: 'var(--notify-success-bg)', color: 'var(--notify-success-text)', borderBottom: '1px solid var(--border)' }}>
      <div className="flex items-center gap-3">
        <svg className="w-5 h-5 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 17l9.2-9.2M17 17V7H7M7 7h5m5 10v-5" /></svg>
        {downloaded ? (
          <span className="text-sm font-medium">v{updateInfo.version} 已下载，重启后自动安装</span>
        ) : (
          <span className="text-sm font-medium">发现新版本 v{updateInfo.version}</span>
        )}
        {downloading && <span className="text-xs opacity-70">下载中...</span>}
      </div>
      <div className="flex items-center gap-2">
        {downloaded ? (
          <button onClick={handleRestart} className="px-3 py-1.5 text-xs rounded-md font-medium" style={{ background: 'var(--color-green)', color: '#fff' }}>立即重启</button>
        ) : downloading ? null : (
          <button onClick={handleDownload} className="px-3 py-1.5 text-xs rounded-md font-medium" style={{ background: 'var(--color-green)', color: '#fff' }}>下载更新</button>
        )}
        <button onClick={handleDismiss} className="p-1 rounded-md opacity-60 hover:opacity-100 transition-opacity"><svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" /></svg></button>
      </div>
    </div>
  )
}
