import { useCallback, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Dialog } from '../UI/Dialog'
import { useLibraryStore } from '../../stores/libraryStore'

// PB-030：下载历史页。数据来自 C++ 的下载历史表（db:getDownloads），
// 历史行只记落盘路径、不记 bookId，所以「已在书架 / 打开」要拿书架按路径对号。

interface DownloadHistoryPageProps {
  onBack: () => void
  // 「打开」直接进阅读器（与书库卡片同一个入口）
  onOpenBook: (bookId: number) => void
}

function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes)) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

// createdAt 是 C++ 写的 UTC ISO 串（NowISO 带 Z 后缀），交给 Date 解析后按本地时区显示。
function formatDateTime(dateStr: string, lang: string): string {
  if (!dateStr) return '-'
  try {
    const d = new Date(dateStr)
    if (Number.isNaN(d.getTime())) return dateStr
    return d.toLocaleString(lang, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
  } catch {
    return dateStr
  }
}

// 同一本书在两处都存绝对路径，但一处经 std::filesystem、一处经 WebView2 回调，
// 大小写与分隔符可能不同；对号前先归一（Windows 路径本就大小写不敏感）。
function normalizePath(p: string): string {
  return (p || '').replace(/\\/g, '/').toLowerCase()
}

interface DownloadRowProps {
  download: PbDownload
  book: PbBook | null
  coverUrl: string | null
  noticeText: string | null
  first: boolean
  onOpen: () => void
  onReveal: () => void
  onDelete: () => void
}

function DownloadRow({ download, book, coverUrl, noticeText, first, onOpen, onReveal, onDelete }: DownloadRowProps) {
  const { t, i18n } = useTranslation()
  const dot = download.fileName.lastIndexOf('.')
  const ext = dot >= 0 ? download.fileName.slice(dot + 1).toUpperCase().slice(0, 4) : ''
  return (
    <div
      role="listitem"
      className="flex items-center gap-3 px-3 py-2.5 flex-wrap hover:bg-[var(--md-sys-state-hover)]"
      style={{ borderTop: first ? undefined : '1px solid var(--md-sys-color-outline-variant)' }}
      data-download-id={download.id}
    >
      <div className="w-9 h-12 shrink-0 overflow-hidden" style={{ borderRadius: 'var(--md-sys-shape-xs)', background: 'var(--md-sys-color-surface-container-highest)' }}>
        {coverUrl ? (
          <img src={coverUrl} alt="" className="w-full h-full object-cover" />
        ) : (
          <span className="w-full h-full flex items-center justify-center m3-label-small" style={{ color: 'var(--md-sys-color-on-surface-variant)' }}>
            {(book?.format || ext || '?').toUpperCase().slice(0, 4)}
          </span>
        )}
      </div>

      <div className="min-w-0 flex-1" style={{ minWidth: 200 }}>
        <p className="m3-body-medium truncate" title={download.fileName}>{download.fileName}</p>
        <div className="flex items-center gap-x-2 gap-y-1 flex-wrap mt-0.5">
          <span className="m3-body-small m3-supporting-text">{formatFileSize(download.size)}</span>
          <span className="m3-body-small m3-supporting-text" aria-hidden="true">·</span>
          <span className="m3-body-small m3-supporting-text">{formatDateTime(download.createdAt, i18n.language)}</span>
          {book && (
            <span
              className="m3-label-small px-1.5 py-0.5"
              style={{ borderRadius: 'var(--md-sys-shape-xs)', background: 'var(--md-sys-color-primary-container)', color: 'var(--md-sys-color-on-primary-container)' }}
            >
              {t('已在书架')}
            </span>
          )}
          {noticeText && <span className="m3-body-small" role="status" style={{ color: 'var(--md-sys-color-error)' }}>{noticeText}</span>}
        </div>
      </div>

      <div className="flex items-center justify-end gap-1 flex-wrap ml-auto max-w-full">
        {book && (
          <button type="button" onClick={onOpen} className="m3-text-button is-compact">{t('打开')}</button>
        )}
        <button type="button" onClick={onReveal} className="m3-text-button is-compact">{t('在文件夹中查看')}</button>
        <button type="button" onClick={onDelete} className="m3-icon-button" aria-label={t('删除记录')} title={t('删除记录')}>
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
          </svg>
        </button>
      </div>
    </div>
  )
}

export function DownloadHistoryPage({ onBack, onOpenBook }: DownloadHistoryPageProps) {
  const { t } = useTranslation()
  const [downloads, setDownloads] = useState<PbDownload[]>([])
  const [books, setBooks] = useState<PbBook[]>([])
  const [loading, setLoading] = useState(true)
  const [loadFailed, setLoadFailed] = useState(false)
  const [covers, setCovers] = useState<Record<number, string | null>>({})
  // 行内提示（路径失效）：3 秒后自己消失，不打断操作
  const [notice, setNotice] = useState<{ id: number; text: string } | null>(null)
  // 删除确认弹层：pending 是待删的那一行，deleteFile 是弹层里的勾选项
  const [pending, setPending] = useState<PbDownload | null>(null)
  const [deleteFile, setDeleteFile] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  const load = useCallback(() => {
    setLoading(true)
    setLoadFailed(false)
    Promise.all([window.electronAPI.getDownloads(), window.electronAPI.getBooks()])
      .then(([history, libraryBooks]) => {
        setDownloads(Array.isArray(history) ? history : [])
        setBooks(Array.isArray(libraryBooks) ? libraryBooks : [])
      })
      .catch(() => setLoadFailed(true))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => { load() }, [load])

  // 路径 → 书架里的那本书；历史行与书架的路径对上了才算「已在书架」
  const bookByPath = useMemo(() => {
    const map = new Map<string, PbBook>()
    for (const b of books) {
      const key = normalizePath(b.file_path)
      if (key) map.set(key, b)
    }
    return map
  }, [books])

  const matchedBooks = useMemo(
    () => downloads.map((d) => bookByPath.get(normalizePath(d.filePath)) ?? null),
    [downloads, bookByPath],
  )
  // 用拼出来的 id 串当依赖：封面只取已对上书的那些，且不随每次渲染重复请求
  const matchedIdsKey = useMemo(
    () => matchedBooks.filter((b): b is PbBook => b !== null).map((b) => b.id).join(','),
    [matchedBooks],
  )

  useEffect(() => {
    const ids = matchedIdsKey ? matchedIdsKey.split(',').map(Number) : []
    if (ids.length === 0) return
    let alive = true
    Promise.all(ids.map((id) => window.electronAPI.getCoverImage(id).catch(() => null)))
      .then((urls) => {
        if (!alive) return
        const next: Record<number, string | null> = {}
        ids.forEach((id, index) => { next[id] = urls[index] ?? null })
        setCovers(next)
      })
    return () => { alive = false }
  }, [matchedIdsKey])

  useEffect(() => {
    if (!notice) return
    const timer = setTimeout(() => setNotice(null), 3000)
    return () => clearTimeout(timer)
  }, [notice])

  // revealPath 返回 false = 路径已经不可用（文件被移走 / 删掉），给一句行内提示
  const reveal = async (download: PbDownload) => {
    try {
      const ok = await window.electronAPI.revealPath(download.filePath)
      if (!ok) setNotice({ id: download.id, text: t('文件已不在原位置') })
    } catch {
      setNotice({ id: download.id, text: t('无法打开所在文件夹') })
    }
  }

  const openDelete = (download: PbDownload) => {
    setDeleteFile(false)
    setDeleteError(null)
    setPending(download)
  }

  const closeDelete = () => {
    if (deleting) return
    setPending(null)
    setDeleteError(null)
  }

  const confirmDelete = async () => {
    if (!pending) return
    const target = pending
    setDeleting(true)
    setDeleteError(null)
    try {
      const removed = await window.electronAPI.deleteDownload(target.id, deleteFile)
      if (!removed) {
        setDeleteError(t('删除失败，请重试'))
        return
      }
      setDownloads((prev) => prev.filter((d) => d.id !== target.id))
      // 勾了「同时删除文件」时 C++ 会把已导入的那本书一并下架并发 library:changed；
      // 本页打开期间书库是卸载的、收不到那个事件，这里主动刷新书库缓存。
      if (deleteFile) useLibraryStore.getState().loadBooks()
      setPending(null)
    } catch {
      setDeleteError(t('删除失败，请重试'))
    } finally {
      setDeleting(false)
    }
  }

  return (
    <div className="h-full flex flex-col" style={{ background: 'var(--md-sys-color-surface)' }}>
      <div className="flex items-center gap-3 px-6 py-4" style={{ borderBottom: '1px solid var(--md-sys-color-outline-variant)' }}>
        <button onClick={onBack} className="m3-icon-button" aria-label={t('返回')}>
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" /></svg>
        </button>
        <div className="min-w-0">
          <h1 className="m3-title-large">{t('下载历史')}</h1>
          {!loading && !loadFailed && downloads.length > 0 && (
            <p className="m3-body-small m3-supporting-text">{t('共 {{n}} 条', { n: downloads.length })}</p>
          )}
        </div>
      </div>

      <div className="flex-1 overflow-y-auto">
        <div className="mx-auto w-full max-w-[1100px] px-6 py-5">
          {loading ? (
            <p className="m3-body-medium m3-supporting-text">{t('加载中...')}</p>
          ) : loadFailed ? (
            <div className="m3-empty-state">
              <p className="m3-title-small mb-1">{t('加载下载记录失败')}</p>
              <button onClick={load} className="m3-outlined-button mt-3">{t('重试')}</button>
            </div>
          ) : downloads.length === 0 ? (
            <div className="m3-empty-state">
              <div className="m3-empty-state-icon">
                <svg className="w-10 h-10" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1M7 10l5 5m0 0l5-5m-5 5V4" />
                </svg>
              </div>
              <p className="m3-title-large mb-1">{t('还没有下载记录')}</p>
              <p className="m3-body-medium" style={{ color: 'var(--md-sys-color-on-surface-variant)' }}>
                {t('在 Z-Library 里点下载后，这里会留下记录')}
              </p>
            </div>
          ) : (
            <div className="m3-card overflow-hidden" role="list">
              {downloads.map((download, index) => {
                const book = matchedBooks[index]
                return (
                  <DownloadRow
                    key={download.id}
                    download={download}
                    book={book}
                    coverUrl={book ? covers[book.id] ?? null : null}
                    noticeText={notice && notice.id === download.id ? notice.text : null}
                    first={index === 0}
                    onOpen={() => { if (book) onOpenBook(book.id) }}
                    onReveal={() => { void reveal(download) }}
                    onDelete={() => openDelete(download)}
                  />
                )
              })}
            </div>
          )}
        </div>
      </div>

      <Dialog
        open={pending !== null}
        onClose={closeDelete}
        title={t('删除下载记录')}
        size="sm"
        footer={(
          <div className="flex items-center justify-end gap-2">
            <button type="button" onClick={closeDelete} className="m3-text-button" disabled={deleting}>{t('取消')}</button>
            <button
              type="button"
              onClick={() => { void confirmDelete() }}
              className="m3-filled-button"
              disabled={deleting}
              style={{ background: 'var(--md-sys-color-error)', color: 'var(--md-sys-color-on-error)' }}
            >
              {deleting ? t('删除中...') : t('删除')}
            </button>
          </div>
        )}
      >
        {pending && (
          <div className="flex flex-col gap-3">
            <p className="m3-body-medium break-all">{pending.fileName}</p>
            <label className="flex items-start gap-2 cursor-pointer">
              <input
                type="checkbox"
                className="m3-checkbox mt-0.5"
                checked={deleteFile}
                disabled={deleting}
                onChange={(e) => setDeleteFile(e.target.checked)}
              />
              <span className="m3-body-medium">
                {t('同时从硬盘删除文件')}
                <span className="block m3-body-small m3-supporting-text mt-1">
                  {t('书架上已导入的书也会一并移除')}
                </span>
              </span>
            </label>
            {deleteError && (
              <p className="m3-body-small" role="alert" style={{ color: 'var(--md-sys-color-error)' }}>{deleteError}</p>
            )}
          </div>
        )}
      </Dialog>
    </div>
  )
}
