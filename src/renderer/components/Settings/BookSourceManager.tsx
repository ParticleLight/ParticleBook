import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useBookSourceStore, type BookSourceImportSummary } from '../../stores/bookSourceStore'
import type { BookSourceInfo } from '../../types/bookSource'

// ── PB-013-A：设置页「书源管理」────────────────────────────────────
//
// 数据全部走既有 bridge（contract 里已有，未新增契约）：
//   getBookSources / insertBookSource / updateBookSource / deleteBookSource /
//   toggleBookSource / clearAllBookSources / searchBooksFromSource(单源测试)
// C++ 语义（已核对 DatabaseService）：
//   * insert 不做校验/去重 —— 去重只能在前端做（按 bookSourceUrl）；
//   * update 是逐键 merge —— 因此「未知的 Legado 规则字段」只要不放进去就原样保留；
//   * toggle 翻转 enabled；
//   * 旧的系统文件对话框导入（importBookSources）只拷白名单字段，会丢未知字段，
//     所以本页的文件导入用 <input type=file> 读原文 → 自己解析 → 保留全部字段。
//
// 本页只读写书源数据，不碰现有 BookSourcePanel（书库里的搜索/下载面板）。

type ImportTab = 'paste' | 'file' | 'url' | 'clipboard'

interface EditForm {
  name: string
  url: string
  group: string
  searchUrl: string
  userAgent: string
  ruleSearch: string
  ruleBookInfo: string
  ruleToc: string
  ruleContent: string
}

interface TestResultRow {
  bookName: string
  author?: string
  bookUrl?: string
}

const RULE_FIELDS: Array<{ key: keyof EditForm; sourceKey: string; label: string }> = [
  { key: 'ruleSearch', sourceKey: 'ruleSearch', label: '搜索规则' },
  { key: 'ruleBookInfo', sourceKey: 'ruleBookInfo', label: '详情规则' },
  { key: 'ruleToc', sourceKey: 'ruleToc', label: '目录规则' },
  { key: 'ruleContent', sourceKey: 'ruleContent', label: '正文规则' },
]

function sourceRecord(source: BookSourceInfo): Record<string, unknown> {
  return source as unknown as Record<string, unknown>
}

function asText(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function asJsonText(value: unknown): string {
  if (value === undefined || value === null) return ''
  try { return JSON.stringify(value, null, 2) } catch { return '' }
}

function toForm(source: BookSourceInfo): EditForm {
  const rec = sourceRecord(source)
  return {
    name: asText(rec.bookSourceName),
    url: asText(rec.bookSourceUrl),
    group: asText(rec.bookSourceGroup),
    searchUrl: asText(rec.searchUrl),
    userAgent: asText(rec.httpUserAgent),
    ruleSearch: asJsonText(rec.ruleSearch),
    ruleBookInfo: asJsonText(rec.ruleBookInfo),
    ruleToc: asJsonText(rec.ruleToc),
    ruleContent: asJsonText(rec.ruleContent),
  }
}

// 未在上面表单里出现的字段数量（用于提示「未知字段会原样保留」）
function unknownFieldCount(source: BookSourceInfo): number {
  const known = new Set(['id', 'added_at', 'bookSourceName', 'bookSourceUrl', 'enabled',
    'bookSourceGroup', 'searchUrl', 'httpUserAgent', 'ruleSearch', 'ruleBookInfo', 'ruleToc', 'ruleContent'])
  return Object.keys(sourceRecord(source)).filter((key) => !known.has(key)).length
}

function errorText(e: unknown): string {
  if (e instanceof Error && e.message) return e.message
  return typeof e === 'string' ? e : 'unknown_error'
}

export function BookSourceManager() {
  const { t } = useTranslation()
  const sources = useBookSourceStore((s) => s.sources)
  const isLoading = useBookSourceStore((s) => s.isLoading)
  const loadSources = useBookSourceStore((s) => s.loadSources)
  const updateSource = useBookSourceStore((s) => s.updateSource)
  const importSourcesFromJson = useBookSourceStore((s) => s.importSourcesFromJson)
  const setEnabledMany = useBookSourceStore((s) => s.setEnabledMany)
  const deleteMany = useBookSourceStore((s) => s.deleteMany)

  const [importOpen, setImportOpen] = useState(false)
  const [importTab, setImportTab] = useState<ImportTab>('paste')
  const [importText, setImportText] = useState('')
  const [importUrl, setImportUrl] = useState('')
  const [importing, setImporting] = useState(false)
  const [importSummary, setImportSummary] = useState<BookSourceImportSummary | null>(null)
  const [importError, setImportError] = useState('')
  const fileInputRef = useRef<HTMLInputElement>(null)

  const [editSource, setEditSource] = useState<BookSourceInfo | null>(null)
  const [form, setForm] = useState<EditForm | null>(null)
  const [formError, setFormError] = useState('')
  const [saving, setSaving] = useState(false)
  const [savedHint, setSavedHint] = useState('')

  const [testSource, setTestSource] = useState<BookSourceInfo | null>(null)
  const [testKeyword, setTestKeyword] = useState('')
  const [testing, setTesting] = useState(false)
  // 测试是异步网络请求：要能区分「还没点」和「跑完了但 0 条结果」
  const [testRan, setTestRan] = useState(false)
  const [testElapsedMs, setTestElapsedMs] = useState(0)
  const [testResults, setTestResults] = useState<TestResultRow[]>([])
  const [testError, setTestError] = useState('')

  const [rowError, setRowError] = useState<Record<number, string>>({})
  const [confirmDelete, setConfirmDelete] = useState<BookSourceInfo | null>(null)
  const [confirmClear, setConfirmClear] = useState(false)
  const [busyIds, setBusyIds] = useState<number[]>([])

  // PB-013-B：分组筛选 / 排序 / 批量选择
  const [groupFilter, setGroupFilter] = useState('__all__')
  const [sortBy, setSortBy] = useState<'name' | 'group' | 'added'>('name')
  const [selectedIds, setSelectedIds] = useState<number[]>([])
  const [bulkBusy, setBulkBusy] = useState(false)
  const [confirmBulkDelete, setConfirmBulkDelete] = useState(false)
  const [bulkNotice, setBulkNotice] = useState('')

  useEffect(() => { void loadSources() }, [loadSources])

  const enabledCount = useMemo(() => sources.filter((s) => s.enabled !== false).length, [sources])

  // 现有分组（去重、按名称排序），供筛选下拉使用
  const groupOptions = useMemo(() => {
    const set = new Set<string>()
    for (const source of sources) {
      const group = asText(sourceRecord(source).bookSourceGroup).trim()
      if (group) set.add(group)
    }
    return Array.from(set).sort((a, b) => a.localeCompare(b, 'zh-Hans-CN'))
  }, [sources])

  // 筛选 + 排序后的列表（筛选按分组；排序支持名称/分组/添加时间）
  const visibleSources = useMemo(() => {
    const filtered = groupFilter === '__all__'
      ? sources.slice()
      : sources.filter((source) => asText(sourceRecord(source).bookSourceGroup).trim() === groupFilter)
    const nameOf = (source: BookSourceInfo) => asText(sourceRecord(source).bookSourceName) || asText(sourceRecord(source).bookSourceUrl)
    filtered.sort((a, b) => {
      if (sortBy === 'group') {
        const groupCompare = asText(sourceRecord(a).bookSourceGroup).localeCompare(asText(sourceRecord(b).bookSourceGroup), 'zh-Hans-CN')
        if (groupCompare !== 0) return groupCompare
        return nameOf(a).localeCompare(nameOf(b), 'zh-Hans-CN')
      }
      if (sortBy === 'added') {
        // C++ 数据模型里只有 added_at（没有 updated_at），这里按添加时间倒序
        return asText(sourceRecord(b).added_at).localeCompare(asText(sourceRecord(a).added_at))
      }
      return nameOf(a).localeCompare(nameOf(b), 'zh-Hans-CN')
    })
    return filtered
  }, [sources, groupFilter, sortBy])

  // 数据变化后裁掉已不存在的选中项（删除/导入后不留幽灵选择）
  useEffect(() => {
    setSelectedIds((prev) => {
      const alive = new Set(sources.map((s) => s.id))
      const next = prev.filter((id) => alive.has(id))
      return next.length === prev.length ? prev : next
    })
  }, [sources])

  const visibleSelectedIds = useMemo(
    () => visibleSources.filter((s) => selectedIds.includes(s.id)).map((s) => s.id),
    [visibleSources, selectedIds],
  )
  const allVisibleSelected = visibleSources.length > 0 && visibleSelectedIds.length === visibleSources.length

  const toggleSelect = useCallback((id: number) => {
    setSelectedIds((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]))
  }, [])

  const toggleSelectAll = useCallback(() => {
    setSelectedIds((prev) => {
      const visibleIds = visibleSources.map((s) => s.id)
      if (visibleIds.every((id) => prev.includes(id))) {
        return prev.filter((id) => !visibleIds.includes(id))
      }
      const merged = new Set(prev)
      for (const id of visibleIds) merged.add(id)
      return Array.from(merged)
    })
  }, [visibleSources])

  const bulkSetEnabled = useCallback(async (enabled: boolean) => {
    const ids = visibleSelectedIds.length > 0 ? visibleSelectedIds : []
    if (ids.length === 0) return
    setBulkBusy(true)
    setBulkNotice('')
    try {
      const result = await setEnabledMany(ids, enabled)
      setBulkNotice(t('批量操作完成：成功 {{ok}} · 失败 {{failed}}', { ok: result.ok, failed: result.failed }))
      setSelectedIds([])
    } finally {
      setBulkBusy(false)
    }
  }, [setEnabledMany, t, visibleSelectedIds])

  const bulkDelete = useCallback(async () => {
    const ids = visibleSelectedIds.slice()
    setConfirmBulkDelete(false)
    if (ids.length === 0) return
    setBulkBusy(true)
    setBulkNotice('')
    try {
      const result = await deleteMany(ids)
      setBulkNotice(t('批量操作完成：成功 {{ok}} · 失败 {{failed}}', { ok: result.ok, failed: result.failed }))
      setSelectedIds([])
      setRowError({})   // 删掉的行的错误提示没有意义了
    } finally {
      setBulkBusy(false)
    }
  }, [deleteMany, t, visibleSelectedIds])

  const setRowBusy = useCallback((id: number, busy: boolean) => {
    setBusyIds((prev) => (busy ? [...prev, id] : prev.filter((x) => x !== id)))
  }, [])

  const clearRowError = useCallback((id: number) => {
    setRowError((prev) => {
      if (!(id in prev)) return prev
      const next = { ...prev }
      delete next[id]
      return next
    })
  }, [])

  const toggle = useCallback(async (source: BookSourceInfo) => {
    clearRowError(source.id)
    setRowBusy(source.id, true)
    try {
      await window.electronAPI.toggleBookSource(source.id)
      await loadSources()
    } catch (e) {
      setRowError((prev) => ({ ...prev, [source.id]: t('操作失败：{{error}}', { error: errorText(e) }) }))
    } finally {
      setRowBusy(source.id, false)
    }
  }, [clearRowError, loadSources, setRowBusy, t])

  const remove = useCallback(async (source: BookSourceInfo) => {
    clearRowError(source.id)
    setRowBusy(source.id, true)
    try {
      await window.electronAPI.deleteBookSource(source.id)
      await loadSources()
      setConfirmDelete(null)
    } catch (e) {
      setRowError((prev) => ({ ...prev, [source.id]: t('操作失败：{{error}}', { error: errorText(e) }) }))
    } finally {
      setRowBusy(source.id, false)
    }
  }, [clearRowError, loadSources, setRowBusy, t])

  const clearAll = useCallback(async () => {
    try {
      await window.electronAPI.clearAllBookSources()
      await loadSources()
      setConfirmClear(false)
    } catch (e) {
      setImportError(t('操作失败：{{error}}', { error: errorText(e) }))
    }
  }, [loadSources, t])

  // ── 导入 ─────────────────────────────────────────────────────────
  const runImport = useCallback(async (raw: string) => {
    setImporting(true)
    setImportError('')
    setImportSummary(null)
    try {
      const summary = await importSourcesFromJson(raw)
      setImportSummary(summary)
      if (summary.error === 'invalid_json') setImportError(t('JSON 无法解析'))
      else if (summary.error === 'empty') setImportError(t('没有可导入的书源'))
    } catch (e) {
      setImportError(t('导入失败：{{error}}', { error: errorText(e) }))
    } finally {
      setImporting(false)
    }
  }, [importSourcesFromJson, t])

  const importFromFiles = useCallback(async (files: FileList | null) => {
    if (!files || files.length === 0) return
    setImporting(true)
    setImportError('')
    setImportSummary(null)
    let total: BookSourceImportSummary = { imported: 0, duplicates: 0, invalid: 0, failed: 0, error: '' }
    try {
      for (const file of Array.from(files)) {
        const text = await file.text()
        const summary = await importSourcesFromJson(text)
        if (summary.error === 'invalid_json' || summary.error === 'empty') {
          setImportError(t('文件「{{name}}」不是合法的书源 JSON', { name: file.name }))
          continue
        }
        total = {
          imported: total.imported + summary.imported,
          duplicates: total.duplicates + summary.duplicates,
          invalid: total.invalid + summary.invalid,
          failed: total.failed + summary.failed,
          error: '',
        }
      }
      setImportSummary(total)
    } catch (e) {
      setImportError(t('导入失败：{{error}}', { error: errorText(e) }))
    } finally {
      setImporting(false)
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }, [importSourcesFromJson, t])

  const importFromUrl = useCallback(async () => {
    const url = importUrl.trim()
    if (!url) { setImportError(t('请填写书源 JSON 的 URL')); return }
    setImporting(true)
    setImportError('')
    setImportSummary(null)
    try {
      const response = await fetch(url)
      if (!response.ok) throw new Error('HTTP ' + response.status)
      const text = await response.text()
      await runImport(text)
    } catch (e) {
      // CSP（index.html: connect-src 'self' ...）只允许同源/白名单地址；外部
      // URL 在渲染层会被浏览器直接拦掉，所以这里给出可操作的替代路径。
      setImportError(t('从 URL 拉取失败：{{error}}（外部地址需要 https:// 且可被应用直接访问；仍失败时请下载后用「从文件导入」或「粘贴 JSON」）', { error: errorText(e) }))
    } finally {
      setImporting(false)
    }
  }, [importUrl, runImport, t])

  const importFromClipboard = useCallback(async () => {
    setImporting(true)
    setImportError('')
    setImportSummary(null)
    try {
      const text = await navigator.clipboard.readText()
      if (!text || !text.trim()) { setImportError(t('剪贴板里没有文本')); return }
      await runImport(text)
    } catch (e) {
      setImportError(t('剪贴板读取失败（可能没有权限，请改用「粘贴 JSON」）'))
    } finally {
      setImporting(false)
    }
  }, [runImport, t])

  // ── 编辑 ─────────────────────────────────────────────────────────
  const openEdit = useCallback((source: BookSourceInfo) => {
    setEditSource(source)
    setForm(toForm(source))
    setFormError('')
    setSavedHint('')
  }, [])

  const closeEdit = useCallback(() => {
    setEditSource(null)
    setForm(null)
    setFormError('')
  }, [])

  const saveEdit = useCallback(async () => {
    const source = editSource
    const current = form
    if (!source || !current) return
    const name = current.name.trim()
    const url = current.url.trim()
    if (!name && !url) { setFormError(t('名称与地址至少要填一个')); return }

    const original = toForm(source)
    const updates: Record<string, unknown> = {}
    if (name !== original.name.trim()) updates.bookSourceName = name
    if (url !== original.url.trim()) updates.bookSourceUrl = url
    if (current.group !== original.group) updates.bookSourceGroup = current.group.trim()
    if (current.searchUrl !== original.searchUrl) updates.searchUrl = current.searchUrl.trim()
    if (current.userAgent !== original.userAgent) updates.httpUserAgent = current.userAgent.trim()

    for (const field of RULE_FIELDS) {
      const raw = current[field.key]
      if (raw === original[field.key]) continue
      const trimmed = raw.trim()
      if (!trimmed) { updates[field.sourceKey] = null; continue }
      try {
        updates[field.sourceKey] = JSON.parse(trimmed)
      } catch {
        setFormError(t('{{field}} 不是合法 JSON', { field: t(field.label) }))
        return
      }
    }

    if (Object.keys(updates).length === 0) { setSavedHint(t('没有改动')); return }

    setSaving(true)
    setFormError('')
    try {
      const ok = await updateSource(source.id, updates as Partial<PbBookSourceInput>)
      if (!ok) { setFormError(t('保存失败')); return }
      setSavedHint(t('已保存'))
      setEditSource(null)
      setForm(null)
    } finally {
      setSaving(false)
    }
  }, [editSource, form, t, updateSource])

  // ── 单源测试（只读）─────────────────────────────────────────────
  const openTest = useCallback((source: BookSourceInfo) => {
    setTestSource(source)
    setTestKeyword('')
    setTestResults([])
    setTestError('')
    setTestRan(false)
    setTestElapsedMs(0)
  }, [])

  const runTest = useCallback(async () => {
    const source = testSource
    if (!source) return
    const keyword = testKeyword.trim()
    if (!keyword) { setTestError(t('请输入测试关键词')); return }
    setTesting(true)
    setTestError('')
    setTestResults([])
    setTestRan(false)
    setTestElapsedMs(0)
    const startedAt = Date.now()
    const timer = window.setInterval(() => setTestElapsedMs(Date.now() - startedAt), 500)
    try {
      const results = await window.electronAPI.searchBooksFromSource(source.id, keyword, 1)
      setTestResults((results || []).slice(0, 20).map((r) => ({ bookName: r.bookName, author: r.author, bookUrl: r.bookUrl })))
    } catch (e) {
      setTestError(t('测试失败：{{error}}', { error: errorText(e) }))
    } finally {
      window.clearInterval(timer)
      setTesting(false)
      setTestRan(true)
      setTestElapsedMs(Date.now() - startedAt)
    }
  }, [t, testKeyword, testSource])

  const overlayStyle: React.CSSProperties = { background: 'rgba(0,0,0,0.45)' }
  const panelStyle: React.CSSProperties = {
    background: 'var(--acrylic-bg)',
    backdropFilter: 'blur(24px)',
    border: '1px solid var(--acrylic-border)',
  }
  const inputStyle: React.CSSProperties = {
    background: 'var(--bg-tertiary)',
    color: 'var(--text-primary)',
    border: '1px solid var(--border)',
  }
  const tabBase = 'px-3 py-1 text-xs rounded-md transition-colors'

  return (
    <div className="space-y-3" data-book-source-manager="1">
      {/* 概览 + 操作 */}
      <div className="settings-card">
        <div className="flex items-center justify-between gap-3 flex-wrap">
          <div>
            <h3 className="text-[13px] font-semibold uppercase tracking-wider" style={{ color: 'var(--text-tertiary)' }}>{t('书源管理')}</h3>
            <p className="text-xs mt-1" style={{ color: 'var(--text-secondary)' }} data-source-stats="1">
              {t('共 {{total}} 个 · 已启用 {{enabled}} 个', { total: sources.length, enabled: enabledCount })}
            </p>
          </div>
          <div className="flex items-center gap-2 flex-wrap">
            <button onClick={() => { void loadSources() }} disabled={isLoading} className="btn-secondary text-xs" data-source-refresh="1">
              {isLoading ? t('加载中…') : t('刷新')}
            </button>
            <button onClick={() => setImportOpen((v) => !v)} className="btn-primary text-xs" data-source-import-open="1">{t('导入书源')}</button>
            <button onClick={() => setConfirmClear(true)} className="btn-secondary text-xs" data-source-clear="1" disabled={sources.length === 0}>{t('清空全部')}</button>
          </div>
        </div>

        {confirmClear && (
          <div className="mt-3 p-3 rounded-lg" style={{ background: 'var(--bg-tertiary)' }}>
            <p className="text-sm mb-2" style={{ color: 'var(--text-primary)' }}>{t('确定清空全部书源吗？此操作不可撤销。')}</p>
            <div className="flex items-center gap-2">
              <button onClick={() => { void clearAll() }} className="btn-primary text-xs" data-source-clear-confirm="1">{t('确认清空')}</button>
              <button onClick={() => setConfirmClear(false)} className="btn-secondary text-xs">{t('取消')}</button>
            </div>
          </div>
        )}

        {importOpen && (
          <div className="mt-3 rounded-lg p-3" style={{ background: 'var(--bg-tertiary)' }}>
            <div className="flex items-center gap-1 mb-2 flex-wrap">
              {(['paste', 'file', 'url', 'clipboard'] as ImportTab[]).map((tab) => (
                <button
                  key={tab}
                  onClick={() => { setImportTab(tab); setImportError(''); setImportSummary(null) }}
                  className={tabBase + (importTab === tab ? ' font-semibold' : ' opacity-60')}
                  style={{ background: importTab === tab ? 'var(--bg-secondary)' : 'transparent', color: 'var(--text-primary)' }}
                  data-import-tab={tab}
                >
                  {tab === 'paste' ? t('粘贴 JSON') : tab === 'file' ? t('从文件导入') : tab === 'url' ? t('从 URL 导入') : t('从剪贴板导入')}
                </button>
              ))}
              <span className="text-[11px] ml-auto" style={{ color: 'var(--text-tertiary)' }}>{t('支持 Legado 书源 JSON（数组或单个对象）')}</span>
            </div>

            {importTab === 'paste' && (
              <div className="space-y-2">
                <textarea
                  value={importText}
                  onChange={(e) => setImportText(e.target.value)}
                  rows={5}
                  placeholder={'[ { "bookSourceName": "...", "bookSourceUrl": "..." } ]'}
                  className="w-full text-xs rounded-md p-2 font-mono"
                  style={inputStyle}
                  data-import-textarea="1"
                />
                <button
                  onClick={() => { void runImport(importText) }}
                  disabled={importing || !importText.trim()}
                  className="btn-primary text-xs"
                  data-import-submit="1"
                >
                  {importing ? t('导入中…') : t('导入')}
                </button>
              </div>
            )}

            {importTab === 'file' && (
              <div className="space-y-2">
                <input
                  ref={fileInputRef}
                  type="file"
                  accept=".json,application/json"
                  multiple
                  onChange={(e) => { void importFromFiles(e.target.files) }}
                  className="text-xs"
                  data-import-file="1"
                />
                <p className="text-[11px]" style={{ color: 'var(--text-tertiary)' }}>{t('可多选；文件里的全部字段（含未知规则字段）都会保留')}</p>
              </div>
            )}

            {importTab === 'url' && (
              <div className="flex items-center gap-2">
                <input
                  value={importUrl}
                  onChange={(e) => setImportUrl(e.target.value)}
                  placeholder="https://example.com/sources.json"
                  className="flex-1 text-xs rounded-md px-2 py-1.5"
                  style={inputStyle}
                  data-import-url="1"
                />
                <button onClick={() => { void importFromUrl() }} disabled={importing || !importUrl.trim()} className="btn-primary text-xs" data-import-url-submit="1">
                  {importing ? t('拉取中…') : t('拉取并导入')}
                </button>
              </div>
            )}

            {importTab === 'clipboard' && (
              <div className="space-y-2">
                <button onClick={() => { void importFromClipboard() }} disabled={importing} className="btn-primary text-xs" data-import-clipboard="1">
                  {importing ? t('读取中…') : t('读取剪贴板')}
                </button>
                <p className="text-[11px]" style={{ color: 'var(--text-tertiary)' }}>{t('需要剪贴板读取权限；失败时请改用「粘贴 JSON」')}</p>
              </div>
            )}

            {importSummary && (
              <p className="mt-2 text-xs" style={{ color: 'var(--text-secondary)' }} data-import-summary="1">
                {t('导入结果：成功 {{imported}} · 重复 {{duplicates}} · 无效 {{invalid}} · 失败 {{failed}}', {
                  imported: importSummary.imported,
                  duplicates: importSummary.duplicates,
                  invalid: importSummary.invalid,
                  failed: importSummary.failed,
                })}
              </p>
            )}
            {importError && (
              <p className="mt-2 text-xs" style={{ color: 'var(--color-red)' }} data-import-error="1">{importError}</p>
            )}
          </div>
        )}
      </div>

      {/* 列表 */}
      <div className="settings-card">
        {isLoading && sources.length === 0 ? (
          <p className="text-sm" style={{ color: 'var(--text-tertiary)' }}>{t('正在加载书源…')}</p>
        ) : sources.length === 0 ? (
          <p className="text-sm" style={{ color: 'var(--text-tertiary)' }} data-source-empty="1">{t('还没有书源，点「导入书源」开始')}</p>
        ) : (
          <div>
            {/* PB-013-B：分组筛选 / 排序 / 批量操作 */}
            <div className="flex items-center gap-2 flex-wrap pb-2 mb-1" style={{ borderBottom: '1px solid var(--border)' }}>
              <select
                value={groupFilter}
                onChange={(e) => { setGroupFilter(e.target.value); setSelectedIds([]) }}
                className="text-xs rounded-md px-2 py-1"
                style={inputStyle}
                aria-label={t('筛选分组')}
                data-source-group-filter="1"
              >
                <option value="__all__">{t('全部分组')}</option>
                {groupOptions.map((option) => (
                  <option key={option} value={option}>{option}</option>
                ))}
              </select>
              <select
                value={sortBy}
                onChange={(e) => setSortBy(e.target.value as 'name' | 'group' | 'added')}
                className="text-xs rounded-md px-2 py-1"
                style={inputStyle}
                aria-label={t('排序方式')}
                data-source-sort="1"
              >
                <option value="name">{t('按名称')}</option>
                <option value="group">{t('按分组')}</option>
                <option value="added">{t('按添加时间')}</option>
              </select>
              <label className="flex items-center gap-1 text-xs cursor-pointer" style={{ color: 'var(--text-secondary)' }}>
                <input type="checkbox" checked={allVisibleSelected} onChange={toggleSelectAll} data-source-select-all="1" />
                {t('全选')}
              </label>
              <span className="text-xs" style={{ color: 'var(--text-tertiary)' }} data-source-selected-count={visibleSelectedIds.length}>
                {t('已选择 {{count}} 个', { count: visibleSelectedIds.length })}
              </span>
              <div className="flex items-center gap-1 ml-auto">
                <button onClick={() => { void bulkSetEnabled(true) }} disabled={bulkBusy || visibleSelectedIds.length === 0} className="btn-secondary text-xs" data-source-bulk-enable="1">{t('批量启用')}</button>
                <button onClick={() => { void bulkSetEnabled(false) }} disabled={bulkBusy || visibleSelectedIds.length === 0} className="btn-secondary text-xs" data-source-bulk-disable="1">{t('批量禁用')}</button>
                <button onClick={() => setConfirmBulkDelete(true)} disabled={bulkBusy || visibleSelectedIds.length === 0} className="btn-secondary text-xs" data-source-bulk-delete="1">{t('批量删除')}</button>
              </div>
            </div>
            {bulkNotice && (
              <p className="text-xs pb-1" style={{ color: 'var(--text-secondary)' }} data-source-bulk-notice="1">{bulkNotice}</p>
            )}
            {visibleSources.length === 0 ? (
              <p className="text-sm py-3" style={{ color: 'var(--text-tertiary)' }} data-source-filter-empty="1">{t('当前分组没有书源')}</p>
            ) : visibleSources.map((source) => {
              const group = asText(sourceRecord(source).bookSourceGroup)
              const url = asText(sourceRecord(source).bookSourceUrl)
              const disabled = busyIds.includes(source.id)
              const unknown = unknownFieldCount(source)
              return (
                <div key={source.id} className="py-2.5 flex items-start gap-3 settings-row" style={{ alignItems: 'flex-start' }} data-source-row={source.id}>
                  <input
                    type="checkbox"
                    checked={selectedIds.includes(source.id)}
                    onChange={() => toggleSelect(source.id)}
                    aria-label={t('选择书源')}
                    data-source-select={source.id}
                    style={{ marginTop: 4 }}
                  />
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className="text-sm font-medium truncate" style={{ color: 'var(--text-primary)' }} data-source-name={source.id}>
                        {asText(sourceRecord(source).bookSourceName) || t('未命名')}
                      </span>
                      <span className="text-[10px] px-1.5 py-0.5 rounded" style={{ background: source.enabled === false ? 'var(--bg-tertiary)' : 'var(--color-indigo-bg)', color: source.enabled === false ? 'var(--text-tertiary)' : 'var(--text-primary)' }} data-source-state={source.id}>
                        {source.enabled === false ? t('已禁用') : t('已启用')}
                      </span>
                      {group && <span className="text-[10px] px-1.5 py-0.5 rounded" style={{ background: 'var(--bg-tertiary)', color: 'var(--text-tertiary)' }}>{group}</span>}
                    </div>
                    <p className="text-xs truncate mt-0.5" style={{ color: 'var(--text-tertiary)' }} title={url} data-source-url={source.id}>
                      {url || t('没有地址')}
                    </p>
                    {unknown > 0 && (
                      <p className="text-[11px] mt-0.5" style={{ color: 'var(--text-tertiary)' }}>{t('另外 {{count}} 个规则字段将原样保留', { count: unknown })}</p>
                    )}
                    {rowError[source.id] && (
                      <p className="text-[11px] mt-1" style={{ color: 'var(--color-red)' }}>{rowError[source.id]}</p>
                    )}
                  </div>
                  <div className="flex items-center gap-1 shrink-0 flex-wrap justify-end">
                    <button onClick={() => { void toggle(source) }} disabled={disabled} className="btn-secondary text-xs" data-source-toggle={source.id}>
                      {source.enabled === false ? t('启用') : t('禁用')}
                    </button>
                    <button onClick={() => openTest(source)} className="btn-secondary text-xs" data-source-test={source.id}>{t('测试')}</button>
                    <button onClick={() => openEdit(source)} className="btn-secondary text-xs" data-source-edit={source.id}>{t('编辑')}</button>
                    <button onClick={() => setConfirmDelete(source)} className="btn-secondary text-xs" data-source-delete={source.id}>{t('删除')}</button>
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </div>

      {/* 删除确认 */}
      {confirmDelete && (
        <div className="fixed inset-0 z-[70] flex items-center justify-center" style={overlayStyle} onClick={() => setConfirmDelete(null)}>
          <div className="relative w-[420px] max-w-[90vw] rounded-xl p-5" style={panelStyle} onClick={(e) => e.stopPropagation()}>
            <p className="text-sm mb-3" style={{ color: 'var(--text-primary)' }}>
              {t('删除书源「{{name}}」？', { name: asText(sourceRecord(confirmDelete).bookSourceName) || t('未命名') })}
            </p>
            <p className="text-xs mb-4" style={{ color: 'var(--text-tertiary)' }}>{t('删除后不能再用它搜索或下载。')}</p>
            <div className="flex justify-end gap-2">
              <button onClick={() => setConfirmDelete(null)} className="btn-secondary text-xs">{t('取消')}</button>
              <button onClick={() => { void remove(confirmDelete) }} className="btn-primary text-xs" data-source-delete-confirm="1">{t('删除')}</button>
            </div>
          </div>
        </div>
      )}

      {/* 批量删除确认 */}
      {confirmBulkDelete && (
        <div className="fixed inset-0 z-[70] flex items-center justify-center" style={overlayStyle} onClick={() => setConfirmBulkDelete(false)}>
          <div className="relative w-[440px] max-w-[90vw] rounded-xl p-5" style={panelStyle} onClick={(e) => e.stopPropagation()}>
            <p className="text-sm mb-3" style={{ color: 'var(--text-primary)' }}>
              {t('删除选中的 {{count}} 个书源？', { count: visibleSelectedIds.length })}
            </p>
            <p className="text-xs mb-4" style={{ color: 'var(--text-tertiary)' }}>{t('删除后不能再用它们搜索或下载。')}</p>
            <div className="flex justify-end gap-2">
              <button onClick={() => setConfirmBulkDelete(false)} className="btn-secondary text-xs">{t('取消')}</button>
              <button onClick={() => { void bulkDelete() }} className="btn-primary text-xs" data-source-bulk-delete-confirm="1">{t('删除')}</button>
            </div>
          </div>
        </div>
      )}

      {/* 编辑对话框 */}
      {editSource && form && (
        <div className="fixed inset-0 z-[70] flex items-center justify-center p-4" style={overlayStyle}>
          <div className="relative w-[720px] max-w-[94vw] max-h-[88vh] rounded-xl flex flex-col overflow-hidden" style={panelStyle}>
            <div className="flex items-center justify-between px-5 py-3" style={{ borderBottom: '1px solid var(--border)' }}>
              <h3 className="text-sm font-semibold" style={{ color: 'var(--text-primary)' }} data-edit-title="1">{t('编辑书源')}</h3>
              <button onClick={closeEdit} className="p-1 rounded" aria-label={t('关闭')} style={{ color: 'var(--text-tertiary)' }}>
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" /></svg>
              </button>
            </div>
            <div className="flex-1 overflow-y-auto px-5 py-3 space-y-3">
              <div className="grid grid-cols-2 gap-2">
                <label className="block">
                  <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>{t('名称')}</span>
                  <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} className="w-full text-sm rounded-md px-2 py-1.5 mt-1" style={inputStyle} data-edit-name="1" />
                </label>
                <label className="block">
                  <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>{t('地址')}</span>
                  <input value={form.url} onChange={(e) => setForm({ ...form, url: e.target.value })} className="w-full text-sm rounded-md px-2 py-1.5 mt-1" style={inputStyle} data-edit-url="1" />
                </label>
                <label className="block">
                  <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>{t('分组')}</span>
                  <input value={form.group} onChange={(e) => setForm({ ...form, group: e.target.value })} className="w-full text-sm rounded-md px-2 py-1.5 mt-1" style={inputStyle} data-edit-group="1" />
                </label>
                <label className="block">
                  <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>{t('User-Agent')}</span>
                  <input value={form.userAgent} onChange={(e) => setForm({ ...form, userAgent: e.target.value })} className="w-full text-sm rounded-md px-2 py-1.5 mt-1" style={inputStyle} data-edit-ua="1" />
                </label>
              </div>
              <label className="block">
                <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>{t('搜索 URL')}</span>
                <input value={form.searchUrl} onChange={(e) => setForm({ ...form, searchUrl: e.target.value })} className="w-full text-sm rounded-md px-2 py-1.5 mt-1 font-mono" style={inputStyle} data-edit-search-url="1" />
              </label>
              {RULE_FIELDS.map((field) => (
                <label className="block" key={field.key}>
                  <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>{t(field.label)}</span>
                  <textarea
                    value={form[field.key]}
                    onChange={(e) => setForm({ ...form, [field.key]: e.target.value })}
                    rows={3}
                    placeholder="{ }"
                    className="w-full text-xs rounded-md px-2 py-1.5 mt-1 font-mono"
                    style={inputStyle}
                    data-edit-rule={field.sourceKey}
                  />
                </label>
              ))}
              <p className="text-[11px]" style={{ color: 'var(--text-tertiary)' }}>
                {t('另外 {{count}} 个未列出的字段会原样保留（保存时只提交改动项）', { count: unknownFieldCount(editSource) })}
              </p>
              {formError && <p className="text-xs" style={{ color: 'var(--color-red)' }} data-edit-error="1">{formError}</p>}
              {savedHint && <p className="text-xs" style={{ color: 'var(--text-tertiary)' }}>{savedHint}</p>}
            </div>
            <div className="flex justify-end gap-2 px-5 py-3" style={{ borderTop: '1px solid var(--border)' }}>
              <button onClick={closeEdit} className="btn-secondary text-xs">{t('取消')}</button>
              <button onClick={() => { void saveEdit() }} disabled={saving} className="btn-primary text-xs" data-edit-save="1">{saving ? t('保存中…') : t('保存')}</button>
            </div>
          </div>
        </div>
      )}

      {/* 单源测试（只读） */}
      {testSource && (
        <div className="fixed inset-0 z-[70] flex items-center justify-center p-4" style={overlayStyle}>
          <div className="relative w-[640px] max-w-[94vw] max-h-[80vh] rounded-xl flex flex-col overflow-hidden" style={panelStyle}>
            <div className="flex items-center justify-between px-5 py-3" style={{ borderBottom: '1px solid var(--border)' }}>
              <div>
                <h3 className="text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>{t('测试书源')}</h3>
                <p className="text-xs mt-0.5" style={{ color: 'var(--text-tertiary)' }}>
                  {asText(sourceRecord(testSource).bookSourceName) || t('未命名')} · {t('该测试只读，不会修改任何书源')}
                </p>
              </div>
              <button onClick={() => setTestSource(null)} className="p-1 rounded" aria-label={t('关闭')} style={{ color: 'var(--text-tertiary)' }}>
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" /></svg>
              </button>
            </div>
            <div className="px-5 py-3 flex items-center gap-2" style={{ borderBottom: '1px solid var(--border)' }}>
              <input
                value={testKeyword}
                onChange={(e) => setTestKeyword(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void runTest() } }}
                placeholder={t('输入测试关键词')}
                className="flex-1 text-sm rounded-md px-2 py-1.5"
                style={inputStyle}
                data-test-keyword="1"
              />
              <button onClick={() => { void runTest() }} disabled={testing} className="btn-primary text-xs" data-test-run="1">{testing ? t('测试中…') : t('开始测试')}</button>
            </div>
            <div className="flex-1 overflow-y-auto px-5 py-3">
              {testing && (
                <p className="text-sm" style={{ color: 'var(--text-tertiary)' }} data-test-running="1">
                  {t('正在请求书源…（已等待 {{seconds}} 秒）', { seconds: Math.round(testElapsedMs / 1000) })}
                </p>
              )}
              {testError && <p className="text-sm" style={{ color: 'var(--color-red)' }} data-test-error="1">{testError}</p>}
              {!testing && !testError && testRan && testResults.length === 0 && (
                <p className="text-sm" style={{ color: 'var(--text-secondary)' }} data-test-empty="1">
                  {t('没有搜索结果（用时 {{seconds}} 秒）；请换个关键词，或检查书源规则/网络', { seconds: Math.round(testElapsedMs / 1000) })}
                </p>
              )}
              {!testing && !testError && !testRan && testResults.length === 0 && (
                <p className="text-sm" style={{ color: 'var(--text-tertiary)' }}>{t('还没有结果；输入关键词后点「开始测试」')}</p>
              )}
              {testResults.length > 0 && (
                <div>
                  <p className="text-xs mb-2" style={{ color: 'var(--text-tertiary)' }} data-test-count={testResults.length}>{t('测试结果：{{count}} 条', { count: testResults.length })}</p>
                  {testResults.map((item, index) => (
                    <div key={index} className="py-1.5" style={{ borderTop: index === 0 ? 'none' : '1px solid var(--border)' }}>
                      <p className="text-sm" style={{ color: 'var(--text-primary)' }}>{item.bookName}</p>
                      <p className="text-xs" style={{ color: 'var(--text-tertiary)' }}>{item.author || t('未知作者')}{item.bookUrl ? ' · ' + item.bookUrl : ''}</p>
                    </div>
                  ))}
                </div>
              )}
            </div>
            <div className="flex justify-end px-5 py-3" style={{ borderTop: '1px solid var(--border)' }}>
              <button onClick={() => setTestSource(null)} className="btn-secondary text-xs">{t('关闭')}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}


