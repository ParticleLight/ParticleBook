import { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { useBookSourceStore } from '../../stores/bookSourceStore'
import type { SearchResult, DownloadProgress } from '../../types/bookSource'

interface BookSourcePanelProps {
  onClose: () => void
  isClosing: boolean
}

type Tab = 'search' | 'sources'

export function BookSourcePanel({ onClose, isClosing }: BookSourcePanelProps) {
  const { t } = useTranslation()
  const [tab, setTab] = useState<Tab>('search')
  const {
    sources, isLoading, searchResults, isSearching, searchError, downloadProgress, isDownloading,
    loadSources, importSources, toggleSource, deleteSource, clearAllSources, search, startDownload, resetDownload,
  } = useBookSourceStore()

  const [keyword, setKeyword] = useState('')
  const [downloadingId, setDownloadingId] = useState<string | null>(null)

  useEffect(() => {
    loadSources()
  }, [])

  const handleSearch = () => {
    if (keyword.trim()) search(keyword.trim())
  }

  const handleDownload = async (result: SearchResult) => {
    const key = `${result.sourceId}-${result.bookUrl}`
    setDownloadingId(key)
    try {
      await startDownload(result.sourceId, result.bookUrl ?? '', result.bookName)
    } finally {
      setDownloadingId(null)
      setTimeout(() => resetDownload(), 3000)
    }
  }

  const handleImport = async () => {
    const result = await importSources()
    if (result.total > 0) {
      alert(t('成功导入 {{imported}} 个书源（共 {{total}} 个）', { imported: result.imported, total: result.total }))
    }
  }

  const getProgressPercent = (p: DownloadProgress) => {
    if (p.total === 0) return 0
    return Math.round((p.current / p.total) * 100)
  }

  const getStatusText = (p: DownloadProgress) => {
    switch (p.status) {
      case 'fetching_toc': return t('正在获取目录...')
      case 'downloading': return t('下载中 {{current}}/{{total}}{{suffix}}', { current: p.current, total: p.total, suffix: p.chapterName ? `: ${p.chapterName}` : '' })
      case 'assembling': return t('正在组装文件...')
      case 'importing': return t('正在导入书架...')
      case 'done': return t('下载完成！')
      case 'error': return t('下载失败: {{error}}', { error: p.error })
      default: return ''
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center animate-fade-in">
      <div className="absolute inset-0 bg-black/60" onClick={onClose} />
      <div className={`relative bg-[var(--reader-bg)] rounded-2xl shadow-2xl w-[900px] max-h-[85vh] flex flex-col overflow-hidden border border-[var(--reader-border)] ${isClosing ? 'animate-scale-out' : 'animate-scale-in'}`}>
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-[var(--reader-border)]">
          <h2 className="text-lg font-bold text-[var(--reader-text)]">{t('书源管理')}</h2>
          <div className="flex items-center gap-3">
            <button
              onClick={handleImport}
              className="px-3 py-1.5 text-sm bg-indigo-600 text-white rounded-lg hover:bg-indigo-500 transition-colors"
            >
              {t('导入 JSON')}
            </button>
            <button
              onClick={onClose}
              className="p-1.5 rounded-lg text-[var(--reader-text)] opacity-60 hover:opacity-100 hover:bg-[var(--reader-sidebar)] transition-colors"
            >
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>

        {/* Tabs */}
        <div className="flex border-b border-[var(--reader-border)]">
          <button
            onClick={() => setTab('search')}
            className={`px-6 py-3 text-sm font-medium transition-colors ${
              tab === 'search'
                ? 'border-b-2'
                : 'text-[var(--reader-text)] opacity-50 hover:opacity-80'
            }`}
            style={tab === 'search' ? { color: 'var(--color-indigo)', borderColor: 'var(--color-indigo)' } : undefined}
          >
            {t('搜索')}
          </button>
          <button
            onClick={() => setTab('sources')}
            className={`px-6 py-3 text-sm font-medium transition-colors ${
              tab === 'sources'
                ? 'border-b-2'
                : 'text-[var(--reader-text)] opacity-50 hover:opacity-80'
            }`}
            style={tab === 'sources' ? { color: 'var(--color-indigo)', borderColor: 'var(--color-indigo)' } : undefined}
          >
            {t('源管理 ({{count}})', { count: sources.length })}
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto p-6">
          {tab === 'search' ? (
            <div className="space-y-4">
              {/* Search bar */}
              <div className="flex gap-2">
                <input
                  type="text"
                  value={keyword}
                  onChange={(e) => setKeyword(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter') handleSearch() }}
                  placeholder={t('搜索书名...')}
                  className="flex-1 px-4 py-2.5 bg-[var(--reader-sidebar)] border border-[var(--reader-border)] rounded-lg text-sm text-[var(--reader-text)] placeholder-gray-500 focus:outline-none focus:border-indigo-500"
                />
                <button
                  onClick={handleSearch}
                  disabled={isSearching || !keyword.trim()}
                  className="px-6 py-2.5 bg-indigo-600 text-white rounded-lg hover:bg-indigo-500 transition-colors disabled:opacity-50 disabled:cursor-not-allowed text-sm font-medium"
                >
                  {isSearching ? t('搜索中...') : t('搜索')}
                </button>
              </div>

              {/* Download progress */}
              {downloadProgress && (
                <div className="bg-[var(--reader-sidebar)] rounded-xl border border-[var(--reader-border)] p-4">
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-sm text-[var(--reader-text)]">{getStatusText(downloadProgress)}</span>
                    <span className="text-sm text-[var(--reader-text)] opacity-60">
                      {downloadProgress.status === 'downloading' ? `${getProgressPercent(downloadProgress)}%` : ''}
                    </span>
                  </div>
                  {downloadProgress.status === 'downloading' && (
                    <div className="h-2 bg-[var(--reader-border)] rounded-full overflow-hidden">
                      <div
                        className="h-full bg-indigo-500 rounded-full transition-all duration-300"
                        style={{ width: `${getProgressPercent(downloadProgress)}%` }}
                      />
                    </div>
                  )}
                  {downloadProgress.status === 'done' && (
                    <p className="text-sm mt-1" style={{ color: 'var(--color-green)' }}>{t('已成功导入书架')}</p>
                  )}
                </div>
              )}

              {/* Search results */}
              {searchResults.length > 0 ? (
                <div className="space-y-2">
                  <p className="text-sm text-[var(--reader-text)] opacity-50">{t('找到 {{count}} 个结果', { count: searchResults.length })}</p>
                  {searchResults.map((result, i) => {
                    const key = `${result.sourceId}-${result.bookUrl}`
                    const isItemDownloading = downloadingId === key
                    return (
                      <div
                        key={`${key}-${i}`}
                        className="flex items-center gap-4 p-3 bg-[var(--reader-sidebar)] rounded-xl border border-[var(--reader-border)] hover:border-[var(--reader-text)] hover:border-opacity-20 transition-colors"
                      >
                        {/* Cover placeholder */}
                        <div className="w-12 h-16 rounded-lg overflow-hidden flex-shrink-0 bg-[var(--reader-border)]">
                          {result.coverUrl ? (
                            <img src={result.coverUrl} alt={result.bookName} className="w-full h-full object-cover" />
                          ) : (
                            <div className="w-full h-full flex items-center justify-center text-[var(--reader-text)] opacity-20 text-xs">
                              <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1} d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" />
                              </svg>
                            </div>
                          )}
                        </div>

                        {/* Info */}
                        <div className="flex-1 min-w-0">
                          <div className="text-sm text-[var(--reader-text)] font-medium truncate">{result.bookName}</div>
                          <div className="text-xs text-[var(--reader-text)] opacity-50 truncate">
                            {result.author || t('未知作者')} · {result.sourceName}
                          </div>
                        </div>

                        {/* Download button */}
                        <button
                          onClick={() => handleDownload(result)}
                          disabled={isDownloading}
                          className="px-4 py-2 text-sm bg-indigo-600 text-white rounded-lg hover:bg-indigo-500 transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex-shrink-0"
                        >
                          {isItemDownloading ? t('下载中...') : t('下载')}
                        </button>
                      </div>
                    )
                  })}
                </div>
              ) : !isSearching && keyword ? (
                <div className="text-center py-12 text-[var(--reader-text)] opacity-40">
                  {searchError ? (
                    <div className="text-left max-w-lg mx-auto">
                      <p className="font-medium mb-2" style={{ color: 'var(--color-red)' }}>{t('搜索出错')}</p>
                      <pre className="text-xs text-left whitespace-pre-wrap bg-[var(--reader-sidebar)] rounded-lg p-4 border border-[var(--reader-border)]" style={{ color: 'var(--color-red)', opacity: 0.8 }}>
                        {searchError}
                      </pre>
                    </div>
                  ) : (
                    <>
                      <p>{t('未找到结果')}</p>
                      <p className="text-sm mt-1">{t('请检查关键词或启用更多书源')}</p>
                    </>
                  )}
                </div>
              ) : !keyword ? (
                <div className="text-center py-12 text-[var(--reader-text)] opacity-40">
                  <svg className="w-16 h-16 mx-auto mb-4 opacity-30" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
                  </svg>
                  <p>{t('输入书名开始搜索')}</p>
                  <p className="text-sm mt-1">
                    {sources.filter((s) => s.enabled).length > 0
                      ? t('当前有 {{count}} 个启用的书源', { count: sources.filter((s) => s.enabled).length })
                      : t('请先在"源管理"中导入并启用书源')}
                  </p>
                </div>
              ) : null}
            </div>
          ) : (
            /* Sources management tab */
            <div className="space-y-3">
              {sources.length > 0 && (
                <div className="flex justify-end">
                  <button
                    onClick={() => {
                      if (confirm(t('确定清空所有书源？此操作不可撤销。'))) clearAllSources()
                    }}
                    className="px-3 py-1.5 text-xs rounded-lg transition-colors"
                    style={{ color: 'var(--color-red)' }}
                  >
                    {t('全部清空')}
                  </button>
                </div>
              )}
              {sources.length === 0 ? (
                <div className="text-center py-12 text-[var(--reader-text)] opacity-40">
                  <svg className="w-16 h-16 mx-auto mb-4 opacity-30" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1} d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
                  </svg>
                  <p>{t('暂无书源')}</p>
                  <p className="text-sm mt-1">{t('点击右上角"导入 JSON"添加书源')}</p>
                </div>
              ) : (
                sources.map((source) => (
                  <div
                    key={source.id}
                    className="flex items-center gap-4 p-3 bg-[var(--reader-sidebar)] rounded-xl border border-[var(--reader-border)]"
                  >
                    {/* Toggle */}
                    <button
                      onClick={() => toggleSource(source.id)}
                      className={`relative w-10 h-5 rounded-full transition-colors flex-shrink-0 ${
                        source.enabled ? 'bg-indigo-600' : 'bg-[var(--reader-border)]'
                      }`}
                    >
                      <div
                        className={`absolute top-0.5 w-4 h-4 rounded-full bg-white transition-transform ${
                          source.enabled ? 'translate-x-5' : 'translate-x-0.5'
                        }`}
                      />
                    </button>

                    {/* Info */}
                    <div className="flex-1 min-w-0">
                      <div className="text-sm text-[var(--reader-text)] font-medium truncate">
                        {source.bookSourceName || t('未命名')}
                      </div>
                      <div className="text-xs text-[var(--reader-text)] opacity-40 truncate">
                        {source.bookSourceUrl}
                      </div>
                    </div>

                    {/* Status */}
                    <span className={`text-xs px-2 py-0.5 rounded-full flex-shrink-0 ${
                      source.enabled
                        ? ''
                        : 'bg-[var(--reader-border)] text-[var(--reader-text)] opacity-40'
                    }`}
                    style={source.enabled ? { color: 'var(--color-green)', backgroundColor: 'var(--color-green-bg)' } : undefined}
                    >
                      {source.enabled ? t('启用') : t('禁用')}
                    </span>

                    {/* Delete */}
                    <button
                      onClick={() => deleteSource(source.id)}
                      className="p-1.5 rounded-lg text-[var(--reader-text)] opacity-40 hover:opacity-100 transition-colors flex-shrink-0"
                    >
                      <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                      </svg>
                    </button>
                  </div>
                ))
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
