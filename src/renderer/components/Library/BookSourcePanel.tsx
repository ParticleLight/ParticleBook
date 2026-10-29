import { useEffect, useId, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useBookSourceStore } from '../../stores/bookSourceStore'
import type { SearchResult, DownloadProgress } from '../../types/bookSource'
import { Modal } from '../UI/Modal'
import { Switch } from '../UI/Switch'
import { ConfirmDialog } from '../UI/ConfirmDialog'

interface BookSourcePanelProps {
  onClose: () => void
  isClosing: boolean
}

type Tab = 'search' | 'sources'

// review U9：标签页顺序（←/→ 在两者间循环）
const TAB_ORDER: Tab[] = ['search', 'sources']

export function BookSourcePanel({ onClose, isClosing }: BookSourcePanelProps) {
  const { t } = useTranslation()
  const [tab, setTab] = useState<Tab>('search')
  const {
    sources, isLoading, searchResults, isSearching, searchError, downloadProgress, isDownloading,
    loadSources, importSources, toggleSource, deleteSource, clearAllSources, search, startDownload, resetDownload,
  } = useBookSourceStore()

  const [keyword, setKeyword] = useState('')
  const [downloadingId, setDownloadingId] = useState<string | null>(null)
  // review U6：原生 confirm/alert 不跟主题、阻塞渲染 —— 清空走 M3 ConfirmDialog，
  // 导入成功改成页面内 banner（可关闭）。
  const [confirmClear, setConfirmClear] = useState(false)
  const [importNotice, setImportNotice] = useState<string | null>(null)
  const titleId = useId()
  const tabRefs = useRef<Record<Tab, HTMLButtonElement | null>>({ search: null, sources: null })

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
    // C++ 只回 { imported }；用户在文件对话框点取消时回 null（此前会读 null.total 抛错）
    if (result && result.imported > 0) {
      setImportNotice(t('成功导入 {{imported}} 个书源', { imported: result.imported }))
    }
  }

  const getProgressPercent = (p: DownloadProgress) => {
    if (p.total === 0) return 0
    return Math.round((p.current / p.total) * 100)
  }

  // C++ 发来的是机器可读错误码（见 BookSourceService::DownloadBook 的 fail()），
  // 在此映射成本地化文案 —— 后端不产出未翻译的用户可见字符串。
  const DOWNLOAD_ERROR_KEYS: Record<string, string> = {
    source_not_found: '书源不存在或已被删除',
    no_chapters: '未能获取章节目录',
    empty_content: '章节内容为空',
    write_failed: '写入文件失败',
    import_failed: '导入书架失败',
    // 书源 HTTP 补了超时/取消（review · B-02）之后会返回 network_error，
    // 以前没有映射，用户会看到「未知错误」。
    network_error: '网络错误或超时',
    cancelled: '下载已取消'
  }

  const getStatusText = (p: DownloadProgress) => {
    switch (p.status) {
      case 'fetching_toc': return t('正在获取目录...')
      case 'downloading': return t('下载中 {{current}}/{{total}}{{suffix}}', { current: p.current, total: p.total, suffix: p.chapterName ? `: ${p.chapterName}` : '' })
      case 'assembling': return t('正在组装文件...')
      case 'importing': return t('正在导入书架...')
      case 'done': return t('下载完成！')
      case 'error': return t('下载失败: {{error}}', {
        error: t(DOWNLOAD_ERROR_KEYS[p.error ?? ''] ?? '未知错误')
      })
      default: return ''
    }
  }

  // review U9：WAI-ARIA tabs —— ←/→/Home/End 切换并把焦点带到目标 tab
  const handleTabKeyDown = (event: React.KeyboardEvent<HTMLButtonElement>, index: number) => {
    const keys = ['ArrowRight', 'ArrowLeft', 'Home', 'End']
    if (!keys.includes(event.key)) return
    event.preventDefault()
    let nextIndex = index
    if (event.key === 'ArrowRight') nextIndex = (index + 1) % TAB_ORDER.length
    else if (event.key === 'ArrowLeft') nextIndex = (index - 1 + TAB_ORDER.length) % TAB_ORDER.length
    else if (event.key === 'Home') nextIndex = 0
    else nextIndex = TAB_ORDER.length - 1
    const nextTab = TAB_ORDER[nextIndex]
    setTab(nextTab)
    tabRefs.current[nextTab]?.focus()
  }

  const renderTab = (id: Tab, index: number, label: string) => {
    const active = tab === id
    return (
      <button
        key={id}
        ref={(node) => { tabRefs.current[id] = node }}
        type="button"
        role="tab"
        id={`book-source-tab-${id}`}
        aria-selected={active}
        aria-controls="book-source-panel"
        tabIndex={active ? 0 : -1}
        onClick={() => setTab(id)}
        onKeyDown={(event) => handleTabKeyDown(event, index)}
        className={`px-6 py-3 text-sm font-medium transition-colors ${active ? 'border-b-2' : ''}`}
        style={active
          ? { color: 'var(--md-sys-color-primary)', borderColor: 'var(--md-sys-color-primary)' }
          : { color: 'var(--text-tertiary)' }}
      >
        {label}
      </button>
    )
  }

  return (
    // review U5：整块弹层交给 UI/Modal —— role="dialog"/aria-modal/Esc/焦点陷阱/关闭回焦。
    // review U1：宽度写 !w-[900px] 兜底（原设计 900，此前被 .m3-dialog 压成 560）。
    <Modal
      onClose={onClose}
      labelledBy={titleId}
      containerClassName="fixed inset-0 z-50 flex items-center justify-center animate-fade-in p-4"
      panelClassName={`m3-dialog relative !w-[900px] max-h-[85vh] flex flex-col overflow-hidden ${isClosing ? 'animate-scale-out' : 'animate-scale-in'}`}
    >
      {/* Header */}
      <div className="flex items-center justify-between px-6 py-4">
        <h2 className="m3-title-large" id={titleId}>{t('书源管理')}</h2>
        <div className="flex items-center gap-3">
          <button
            onClick={handleImport}
            className="m3-filled-button"
          >
            {t('导入 JSON')}
          </button>
          <button
            onClick={onClose}
            className="m3-icon-button" aria-label={t('关闭')} title={t('关闭')}
          >
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>
      </div>

      {/* review U6：导入成功用页面内 banner（原本是 alert） */}
      {importNotice && (
        <div className="mx-6 mb-2 flex items-center justify-between gap-3 rounded-lg px-3 py-2 text-sm"
          style={{ background: 'var(--md-sys-color-primary-container)', color: 'var(--md-sys-color-on-primary-container)' }}
          role="status" aria-live="polite" data-import-notice="1">
          <span className="min-w-0 truncate">{importNotice}</span>
          <button onClick={() => setImportNotice(null)} className="m3-text-button is-compact shrink-0" aria-label={t('关闭')}>{t('关闭')}</button>
        </div>
      )}

      {/* Tabs（review U9：role=tablist/tab + aria-selected + aria-controls + 方向键） */}
      <div className="flex" role="tablist" aria-label={t('书源管理')} style={{ borderBottom: '1px solid var(--md-sys-color-outline-variant)' }}>
        {renderTab('search', 0, t('搜索'))}
        {renderTab('sources', 1, t('源管理 ({{count}})', { count: sources.length }))}
      </div>

      {/* Content */}
      <div
        className="flex-1 overflow-y-auto p-6"
        role="tabpanel"
        id="book-source-panel"
        aria-labelledby={`book-source-tab-${tab}`}
      >
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
                className="m3-text-field flex-1"
                aria-label={t('搜索书名...')}
              />
              <button
                onClick={handleSearch}
                disabled={isSearching || !keyword.trim()}
                className="m3-filled-button"
              >
                {isSearching ? t('搜索中...') : t('搜索')}
              </button>
            </div>

            {/* Download progress */}
            {downloadProgress && (
              <div className="bg-[var(--reader-sidebar)] rounded-xl border border-[var(--reader-border)] p-4">
                <div className="flex items-center justify-between mb-2">
                  <span className="text-sm text-[var(--reader-text)]">{getStatusText(downloadProgress)}</span>
                  <span className="text-sm" style={{ color: 'var(--text-tertiary)' }}>
                    {downloadProgress.status === 'downloading' ? `${getProgressPercent(downloadProgress)}%` : ''}
                  </span>
                </div>
                {downloadProgress.status === 'downloading' && (
                  <div className="h-2 bg-[var(--reader-border)] rounded-full overflow-hidden">
                    <div
                      className="h-full rounded-full transition-all duration-300" style={{ background: 'var(--md-sys-color-primary)', width: `${getProgressPercent(downloadProgress)}%` }}
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
                {/* review U11：次要文字不再在 --reader-text 上叠 opacity-50（有效对比度
                    只有 3.25:1），直接用 --text-tertiary（globals 里已按 AA 标定）。 */}
                <p className="text-sm" style={{ color: 'var(--text-tertiary)' }}>{t('找到 {{count}} 个结果', { count: searchResults.length })}</p>
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
                          <div className="w-full h-full flex items-center justify-center text-xs" style={{ color: 'var(--text-tertiary)' }}>
                            <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true">
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1} d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" />
                            </svg>
                          </div>
                        )}
                      </div>

                      {/* Info */}
                      <div className="flex-1 min-w-0">
                        <div className="text-sm text-[var(--reader-text)] font-medium truncate">{result.bookName}</div>
                        <div className="text-xs truncate" style={{ color: 'var(--text-tertiary)' }}>
                          {result.author || t('未知作者')} · {result.sourceName}
                        </div>
                      </div>

                      {/* Download button */}
                      <button
                        onClick={() => handleDownload(result)}
                        disabled={isDownloading}
                        className="m3-filled-button flex-shrink-0"
                      >
                        {isItemDownloading ? t('下载中...') : t('下载')}
                      </button>
                    </div>
                  )
                })}
              </div>
            ) : !isSearching && keyword ? (
              <div className="text-center py-12" style={{ color: 'var(--text-tertiary)' }}>
                {searchError ? (
                  <div className="text-left max-w-lg mx-auto">
                    <p className="font-medium mb-2" style={{ color: 'var(--md-sys-color-error)' }}>{t('搜索出错')}</p>
                    <pre className="text-xs text-left whitespace-pre-wrap bg-[var(--reader-sidebar)] rounded-lg p-4 border border-[var(--reader-border)]" style={{ color: 'var(--md-sys-color-error)' }}>
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
              <div className="text-center py-12" style={{ color: 'var(--text-tertiary)' }}>
                <svg className="w-16 h-16 mx-auto mb-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true">
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
                  onClick={() => setConfirmClear(true)}
                  className="px-3 py-1.5 text-xs rounded-lg transition-colors"
                  style={{ color: 'var(--md-sys-color-error)' }}
                >
                  {t('全部清空')}
                </button>
              </div>
            )}
            {sources.length === 0 ? (
              <div className="text-center py-12" style={{ color: 'var(--text-tertiary)' }}>
                <svg className="w-16 h-16 mx-auto mb-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1} d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
                </svg>
                <p>{t('暂无书源')}</p>
                <p className="text-sm mt-1">{t('点击右上角"导入 JSON"添加书源')}</p>
              </div>
            ) : (
              sources.map((source) => {
                // 沿用改造前的判定（enabled 可能为 undefined，按 falsy 处理）
                const enabled = Boolean(source.enabled)
                return (
                <div
                  key={source.id}
                  className="flex items-center gap-4 p-3 bg-[var(--reader-sidebar)] rounded-xl border border-[var(--reader-border)]"
                >
                  {/* review U7：与设置页同一形态 —— UI/Switch（role="switch" +
                      aria-checked + aria-label，52/32 触控目标），不再自造 40×20 div 开关 */}
                  <Switch
                    size="sm"
                    checked={enabled}
                    onChange={() => toggleSource(source.id)}
                    label={enabled ? t('已启用') : t('已禁用')}
                  />

                  {/* Info */}
                  <div className="flex-1 min-w-0">
                    <div className="text-sm text-[var(--reader-text)] font-medium truncate">
                      {source.bookSourceName || t('未命名')}
                    </div>
                    {/* review U11：URL 不再叠 opacity-40（有效 2.50:1），改用 --text-tertiary */}
                    <div className="text-xs truncate" style={{ color: 'var(--text-tertiary)' }}>
                      {source.bookSourceUrl}
                    </div>
                  </div>

                  {/* Status */}
                  <span className={`text-xs px-2 py-0.5 rounded-full flex-shrink-0 ${enabled ? '' : 'bg-[var(--reader-border)]'}`}
                  style={enabled
                    ? { color: 'var(--color-green)', backgroundColor: 'var(--color-green-bg)' }
                    : { color: 'var(--text-tertiary)' }}
                  >
                    {enabled ? t('启用') : t('禁用')}
                  </span>

                  {/* review U8：纯图标按钮补可访问名（删除书源） */}
                  <button
                    onClick={() => deleteSource(source.id)}
                    className="p-1.5 rounded-lg text-[var(--reader-text)] opacity-60 hover:opacity-100 transition-colors flex-shrink-0"
                    aria-label={t('删除书源')}
                    title={t('删除书源')}
                  >
                    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                    </svg>
                  </button>
                </div>
                )
              })
            )}
          </div>
        )}
      </div>

      {/* review U6：清空书源确认（原本是原生 confirm） */}
      {confirmClear && (
        <ConfirmDialog
          title={t('确定清空所有书源？此操作不可撤销。')}
          message={t('清空后需要重新导入 JSON 才能搜索和下载。')}
          confirmText={t('全部清空')}
          danger
          onConfirm={() => { setConfirmClear(false); clearAllSources() }}
          onCancel={() => setConfirmClear(false)}
        />
      )}
    </Modal>
  )
}
