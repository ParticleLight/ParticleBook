import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useBookSourceStore, type BookSourceImportSummary } from '../../stores/bookSourceStore'
import type { BookSourceInfo } from '../../types/bookSource'
import { Switch } from '../UI/Switch'
import { Modal } from '../UI/Modal'
import { M3Select, type M3SelectOption } from '../UI/M3Select'

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

  // 分组 / 排序下拉的选项（原 <option> 列表一一映射）
  const groupSelectOptions = useMemo<M3SelectOption[]>(() => [
    { value: '__all__', label: t('全部分组') },
    ...groupOptions.map((group) => ({ value: group, label: group })),
  ], [groupOptions, t])

  const sortSelectOptions = useMemo<M3SelectOption[]>(() => [
    { value: 'name', label: t('按名称') },
    { value: 'group', label: t('按分组') },
    { value: 'added', label: t('按添加时间') },
  ], [t])

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

  // 弹层材质沿用 acrylic；内部控件 / 按钮 / 列表统一走 globals.css 的 M3 基元。
  // 遮罩浓度改由 UI/Modal 的 .m3-dialog-backdrop（var(--ui-backdrop)）统一提供，
  // 不再每个弹层各写一份。
  const panelStyle: React.CSSProperties = {
    background: 'var(--acrylic-bg)',
    backdropFilter: 'blur(24px)',
    border: '1px solid var(--acrylic-border)',
  }

  return (
    <div className="space-y-3" data-book-source-manager="1">
      {/* 概览 + 操作 */}
      <div className="settings-card">
        <div className="flex items-center justify-between gap-3 flex-wrap">
          <div>
            <h3 className="m3-section-title">{t('书源管理')}</h3>
            <p className="m3-body-small mt-1" data-source-stats="1">
              {t('共 {{total}} 个 · 已启用 {{enabled}} 个', { total: sources.length, enabled: enabledCount })}
            </p>
          </div>
          <div className="flex items-center gap-2 flex-wrap">
            <button onClick={() => { void loadSources() }} disabled={isLoading} className="m3-outlined-button is-compact" data-source-refresh="1">
              {isLoading ? t('加载中…') : t('刷新')}
            </button>
            <button onClick={() => setImportOpen((v) => !v)} className="m3-filled-button is-compact" data-source-import-open="1">{t('导入书源')}</button>
            <button onClick={() => setConfirmClear(true)} className="m3-text-button is-compact" data-source-clear="1" disabled={sources.length === 0}>{t('清空全部')}</button>
          </div>
        </div>

        {confirmClear && (
          <div className="mt-3 rounded-xl p-3" style={{ background: 'var(--md-sys-color-surface-container-high)' }}>
            <p className="m3-body-medium mb-2">{t('确定清空全部书源吗？此操作不可撤销。')}</p>
            <div className="flex items-center gap-2">
              <button onClick={() => { void clearAll() }} className="m3-filled-button is-compact" data-source-clear-confirm="1">{t('确认清空')}</button>
              <button onClick={() => setConfirmClear(false)} className="m3-text-button is-compact">{t('取消')}</button>
            </div>
          </div>
        )}

        {importOpen && (
          <div className="mt-3 rounded-xl p-3" style={{ background: 'var(--md-sys-color-surface-container-low)' }}>
            <div className="flex items-center gap-1 mb-2 flex-wrap">
              {(['paste', 'file', 'url', 'clipboard'] as ImportTab[]).map((tab) => (
                <button
                  key={tab}
                  onClick={() => { setImportTab(tab); setImportError(''); setImportSummary(null) }}
                  className={'m3-filter-chip' + (importTab === tab ? ' is-selected' : '')}
                  data-import-tab={tab}
                >
                  {tab === 'paste' ? t('粘贴 JSON') : tab === 'file' ? t('从文件导入') : tab === 'url' ? t('从 URL 导入') : t('从剪贴板导入')}
                </button>
              ))}
              <span className="m3-label-small ml-auto">{t('支持 Legado 书源 JSON（数组或单个对象）')}</span>
            </div>

            {importTab === 'paste' && (
              <div className="space-y-2">
                <textarea
                  value={importText}
                  onChange={(e) => setImportText(e.target.value)}
                  rows={5}
                  placeholder={'[ { "bookSourceName": "...", "bookSourceUrl": "..." } ]'}
                  className="m3-text-field is-code"
                  data-import-textarea="1"
                />
                <button
                  onClick={() => { void runImport(importText) }}
                  disabled={importing || !importText.trim()}
                  className="m3-filled-button is-compact"
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
                <p className="m3-body-small">{t('可多选；文件里的全部字段（含未知规则字段）都会保留')}</p>
              </div>
            )}

            {importTab === 'url' && (
              <div className="flex items-center gap-2">
                <input
                  value={importUrl}
                  onChange={(e) => setImportUrl(e.target.value)}
                  placeholder="https://example.com/sources.json"
                  className="m3-text-field is-dense flex-1"
                  data-import-url="1"
                />
                <button onClick={() => { void importFromUrl() }} disabled={importing || !importUrl.trim()} className="m3-filled-button is-compact" data-import-url-submit="1">
                  {importing ? t('拉取中…') : t('拉取并导入')}
                </button>
              </div>
            )}

            {importTab === 'clipboard' && (
              <div className="space-y-2">
                <button onClick={() => { void importFromClipboard() }} disabled={importing} className="m3-outlined-button is-compact" data-import-clipboard="1">
                  {importing ? t('读取中…') : t('读取剪贴板')}
                </button>
                <p className="m3-body-small">{t('需要剪贴板读取权限；失败时请改用「粘贴 JSON」')}</p>
              </div>
            )}

            {importSummary && (
              <p className="m3-body-small mt-2" data-import-summary="1">
                {t('导入结果：成功 {{imported}} · 重复 {{duplicates}} · 无效 {{invalid}} · 失败 {{failed}}', {
                  imported: importSummary.imported,
                  duplicates: importSummary.duplicates,
                  invalid: importSummary.invalid,
                  failed: importSummary.failed,
                })}
              </p>
            )}
            {importError && (
              <p className="m3-body-small mt-2" style={{ color: 'var(--md-sys-color-error)' }} data-import-error="1">{importError}</p>
            )}
          </div>
        )}
      </div>

      {/* 列表 */}
      <div className="settings-card">
        {isLoading && sources.length === 0 ? (
          <p className="m3-body-medium" style={{ color: 'var(--md-sys-color-on-surface-variant)' }}>{t('正在加载书源…')}</p>
        ) : sources.length === 0 ? (
          <p className="m3-body-medium" style={{ color: 'var(--md-sys-color-on-surface-variant)' }} data-source-empty="1">{t('还没有书源，点「导入书源」开始')}</p>
        ) : (
          <div>
            {/* PB-013-B：分组筛选 / 排序 / 批量操作 */}
            <div className="flex items-center gap-2 flex-wrap pb-3 mb-3" style={{ borderBottom: '1px solid var(--md-sys-color-outline-variant)' }}>
              <M3Select
                value={groupFilter}
                options={groupSelectOptions}
                onChange={(next) => { setGroupFilter(next); setSelectedIds([]) }}
                aria-label={t('筛选分组')}
                data-source-group-filter="1"
              />
              <M3Select
                value={sortBy}
                options={sortSelectOptions}
                onChange={(next) => setSortBy(next as 'name' | 'group' | 'added')}
                aria-label={t('排序方式')}
                data-source-sort="1"
              />
              <span className="m3-label-small" data-source-selected-count={visibleSelectedIds.length}>
                {t('已选择 {{count}} 个', { count: visibleSelectedIds.length })}
              </span>
              <div className="flex items-center gap-1 ml-auto">
                <button onClick={() => { void bulkSetEnabled(true) }} disabled={bulkBusy || visibleSelectedIds.length === 0} className="m3-outlined-button is-compact" data-source-bulk-enable="1">{t('批量启用')}</button>
                <button onClick={() => { void bulkSetEnabled(false) }} disabled={bulkBusy || visibleSelectedIds.length === 0} className="m3-outlined-button is-compact" data-source-bulk-disable="1">{t('批量禁用')}</button>
                <button onClick={() => setConfirmBulkDelete(true)} disabled={bulkBusy || visibleSelectedIds.length === 0} className="m3-text-button is-compact" data-source-bulk-delete="1">{t('批量删除')}</button>
              </div>
            </div>
            {bulkNotice && (
              <p className="m3-body-small pb-2" data-source-bulk-notice="1">{bulkNotice}</p>
            )}
            {visibleSources.length === 0 ? (
              <p className="m3-body-medium py-3" style={{ color: 'var(--md-sys-color-on-surface-variant)' }} data-source-filter-empty="1">{t('当前分组没有书源')}</p>
            ) : (
              <div className="m3-table-wrap">
                <table className="m3-table">
                  <thead>
                    <tr>
                      <th scope="col" style={{ width: 44 }}>
                        <input
                          type="checkbox"
                          className="m3-checkbox"
                          checked={allVisibleSelected}
                          onChange={toggleSelectAll}
                          aria-label={t('全选')}
                          data-source-select-all="1"
                        />
                      </th>
                      <th scope="col" className="is-name">{t('名称')}</th>
                      <th scope="col" className="is-url">{t('地址')}</th>
                      <th scope="col" className="is-nowrap">{t('分组')}</th>
                      <th scope="col" className="is-nowrap">{t('启用')}</th>
                      <th scope="col" className="is-actions" aria-hidden="true" />
                    </tr>
                  </thead>
                  <tbody>
                    {visibleSources.map((source) => {
                      const group = asText(sourceRecord(source).bookSourceGroup)
                      const url = asText(sourceRecord(source).bookSourceUrl)
                      const name = asText(sourceRecord(source).bookSourceName) || t('未命名')
                      const disabled = busyIds.includes(source.id)
                      const unknown = unknownFieldCount(source)
                      const enabled = source.enabled !== false
                      const selected = selectedIds.includes(source.id)
                      return (
                        <tr
                          key={source.id}
                          className={selected ? 'is-selected' : undefined}
                          data-source-row={source.id}
                        >
                          <td>
                            <input
                              type="checkbox"
                              className="m3-checkbox"
                              checked={selected}
                              onChange={() => toggleSelect(source.id)}
                              aria-label={t('选择书源')}
                              data-source-select={source.id}
                            />
                          </td>
                          <td className="is-name">
                            <div className="m3-cell-clamp m3-label-large" title={name} data-source-name={source.id}>
                              {name}
                            </div>
                            {unknown > 0 && (
                              <div className="m3-label-small mt-0.5">{t('另外 {{count}} 个规则字段将原样保留', { count: unknown })}</div>
                            )}
                            {rowError[source.id] && (
                              <p className="m3-body-small mt-1" style={{ color: 'var(--md-sys-color-error)' }}>{rowError[source.id]}</p>
                            )}
                          </td>
                          <td className="is-url">
                            {/* 地址列上限 200px：6 列（含 240px 名称下限与操作列）要在 ~900px 内容区里放得下，
                                超长 URL 走 truncate + title；窄窗由 .m3-table-wrap 横向滚动兜底 */}
                            <span className="block truncate m3-body-small" style={{ maxWidth: 200 }} title={url} data-source-url={source.id}>
                              {url || t('没有地址')}
                            </span>
                          </td>
                          <td className="is-nowrap">
                            <span className="m3-label-small">{group || '—'}</span>
                          </td>
                          <td className="is-nowrap">
                            <span className="inline-flex items-center gap-2" data-source-toggle={source.id}>
                              <Switch
                                size="sm"
                                checked={enabled}
                                onChange={() => { void toggle(source) }}
                                disabled={disabled}
                                label={enabled ? t('已启用') : t('已禁用')}
                              />
                              <span className="m3-label-small" data-source-state={source.id}>
                                {enabled ? t('已启用') : t('已禁用')}
                              </span>
                            </span>
                          </td>
                          <td className="is-actions">
                            <div className="flex items-center justify-end gap-1">
                              <button onClick={() => openTest(source)} className="m3-text-button is-compact" data-source-test={source.id}>{t('测试')}</button>
                              <button onClick={() => openEdit(source)} className="m3-text-button is-compact" data-source-edit={source.id}>{t('编辑')}</button>
                              <button onClick={() => setConfirmDelete(source)} className="m3-text-button is-compact" data-source-delete={source.id}>{t('删除')}</button>
                            </div>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}
      </div>

      {/* 删除确认（review U5：弹层行为统一走 UI/Modal —— role="dialog"/aria-modal/
          Esc/焦点陷阱/关闭回焦；视觉保持原来的 acrylic 面板不变） */}
      {confirmDelete && (
        <Modal
          onClose={() => setConfirmDelete(null)}
          labelledBy="book-source-delete-title"
          containerClassName="fixed inset-0 z-[70] flex items-center justify-center p-4"
          panelClassName="relative w-[420px] max-w-[90vw] rounded-xl p-5"
          panelStyle={panelStyle}
        >
          <p className="m3-title-small mb-3" id="book-source-delete-title">
            {t('删除书源「{{name}}」？', { name: asText(sourceRecord(confirmDelete).bookSourceName) || t('未命名') })}
          </p>
          <p className="m3-body-small mb-4">{t('删除后不能再用它搜索或下载。')}</p>
          <div className="flex justify-end gap-2">
            <button onClick={() => setConfirmDelete(null)} className="m3-text-button is-compact">{t('取消')}</button>
            <button onClick={() => { void remove(confirmDelete) }} className="m3-filled-button is-compact" data-source-delete-confirm="1">{t('删除')}</button>
          </div>
        </Modal>
      )}

      {/* 批量删除确认（同 U5） */}
      {confirmBulkDelete && (
        <Modal
          onClose={() => setConfirmBulkDelete(false)}
          labelledBy="book-source-bulk-delete-title"
          containerClassName="fixed inset-0 z-[70] flex items-center justify-center p-4"
          panelClassName="relative w-[440px] max-w-[90vw] rounded-xl p-5"
          panelStyle={panelStyle}
        >
          <p className="m3-title-small mb-3" id="book-source-bulk-delete-title">
            {t('删除选中的 {{count}} 个书源？', { count: visibleSelectedIds.length })}
          </p>
          <p className="m3-body-small mb-4">{t('删除后不能再用它们搜索或下载。')}</p>
          <div className="flex justify-end gap-2">
            <button onClick={() => setConfirmBulkDelete(false)} className="m3-text-button is-compact">{t('取消')}</button>
            <button onClick={() => { void bulkDelete() }} className="m3-filled-button is-compact" data-source-bulk-delete-confirm="1">{t('删除')}</button>
          </div>
        </Modal>
      )}

      {/* 编辑对话框（review U5：语义 / Esc / 焦点陷阱 / 关闭回焦走 UI/Modal） */}
      {editSource && form && (
        <Modal
          onClose={closeEdit}
          labelledBy="book-source-edit-title"
          containerClassName="fixed inset-0 z-[70] flex items-center justify-center p-4"
          panelClassName="relative w-[720px] max-w-[94vw] max-h-[88vh] rounded-xl flex flex-col overflow-hidden"
          panelStyle={panelStyle}
        >
            <div className="flex items-center justify-between px-5 py-3" style={{ borderBottom: '1px solid var(--md-sys-color-outline-variant)' }}>
              <h3 className="m3-title-small" data-edit-title="1" id="book-source-edit-title">{t('编辑书源')}</h3>
              <button onClick={closeEdit} className="m3-icon-button" aria-label={t('关闭')}>
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" /></svg>
              </button>
            </div>
            <div className="flex-1 overflow-y-auto px-5 py-3 space-y-3">
              <div className="grid grid-cols-2 gap-2">
                <label className="block">
                  <span className="m3-label-medium block mb-1">{t('名称')}</span>
                  <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} className="m3-text-field" data-edit-name="1" />
                </label>
                <label className="block">
                  <span className="m3-label-medium block mb-1">{t('地址')}</span>
                  <input value={form.url} onChange={(e) => setForm({ ...form, url: e.target.value })} className="m3-text-field" data-edit-url="1" />
                </label>
                <label className="block">
                  <span className="m3-label-medium block mb-1">{t('分组')}</span>
                  <input value={form.group} onChange={(e) => setForm({ ...form, group: e.target.value })} className="m3-text-field" data-edit-group="1" />
                </label>
                <label className="block">
                  <span className="m3-label-medium block mb-1">{t('User-Agent')}</span>
                  <input value={form.userAgent} onChange={(e) => setForm({ ...form, userAgent: e.target.value })} className="m3-text-field" data-edit-ua="1" />
                </label>
              </div>
              <label className="block">
                <span className="m3-label-medium block mb-1">{t('搜索 URL')}</span>
                <input value={form.searchUrl} onChange={(e) => setForm({ ...form, searchUrl: e.target.value })} className="m3-text-field is-code" data-edit-search-url="1" />
              </label>
              {RULE_FIELDS.map((field) => (
                <label className="block" key={field.key}>
                  <span className="m3-label-medium block mb-1">{t(field.label)}</span>
                  <textarea
                    value={form[field.key]}
                    onChange={(e) => setForm({ ...form, [field.key]: e.target.value })}
                    rows={3}
                    placeholder="{ }"
                    className="m3-text-field is-code"
                    data-edit-rule={field.sourceKey}
                  />
                </label>
              ))}
              <p className="m3-body-small">
                {t('另外 {{count}} 个未列出的字段会原样保留（保存时只提交改动项）', { count: unknownFieldCount(editSource) })}
              </p>
              {formError && <p className="m3-body-small" style={{ color: 'var(--md-sys-color-error)' }} data-edit-error="1">{formError}</p>}
              {savedHint && <p className="m3-body-small">{savedHint}</p>}
            </div>
            <div className="flex justify-end gap-2 px-5 py-3" style={{ borderTop: '1px solid var(--md-sys-color-outline-variant)' }}>
              <button onClick={closeEdit} className="m3-text-button is-compact">{t('取消')}</button>
              <button onClick={() => { void saveEdit() }} disabled={saving} className="m3-filled-button is-compact" data-edit-save="1">{saving ? t('保存中…') : t('保存')}</button>
            </div>
        </Modal>
      )}

      {/* 单源测试（只读，review U5：走 UI/Modal） */}
      {testSource && (
        <Modal
          onClose={() => setTestSource(null)}
          labelledBy="book-source-test-title"
          containerClassName="fixed inset-0 z-[70] flex items-center justify-center p-4"
          panelClassName="relative w-[640px] max-w-[94vw] max-h-[80vh] rounded-xl flex flex-col overflow-hidden"
          panelStyle={panelStyle}
        >
            <div className="flex items-center justify-between px-5 py-3" style={{ borderBottom: '1px solid var(--md-sys-color-outline-variant)' }}>
              <div>
                <h3 className="m3-title-small" id="book-source-test-title">{t('测试书源')}</h3>
                <p className="m3-body-small mt-0.5">
                  {asText(sourceRecord(testSource).bookSourceName) || t('未命名')} · {t('该测试只读，不会修改任何书源')}
                </p>
              </div>
              <button onClick={() => setTestSource(null)} className="m3-icon-button" aria-label={t('关闭')}>
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" /></svg>
              </button>
            </div>
            <div className="px-5 py-3 flex items-center gap-2" style={{ borderBottom: '1px solid var(--md-sys-color-outline-variant)' }}>
              <input
                value={testKeyword}
                onChange={(e) => setTestKeyword(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void runTest() } }}
                placeholder={t('输入测试关键词')}
                className="m3-text-field is-dense flex-1"
                data-test-keyword="1"
              />
              <button onClick={() => { void runTest() }} disabled={testing} className="m3-filled-button is-compact" data-test-run="1">{testing ? t('测试中…') : t('开始测试')}</button>
            </div>
            <div className="flex-1 overflow-y-auto px-5 py-3">
              {testing && (
                <p className="m3-body-medium" style={{ color: 'var(--md-sys-color-on-surface-variant)' }} data-test-running="1">
                  {t('正在请求书源…（已等待 {{seconds}} 秒）', { seconds: Math.round(testElapsedMs / 1000) })}
                </p>
              )}
              {testError && <p className="m3-body-medium" style={{ color: 'var(--md-sys-color-error)' }} data-test-error="1">{testError}</p>}
              {!testing && !testError && testRan && testResults.length === 0 && (
                <p className="m3-body-medium" style={{ color: 'var(--md-sys-color-on-surface-variant)' }} data-test-empty="1">
                  {t('没有搜索结果（用时 {{seconds}} 秒）；请换个关键词，或检查书源规则/网络', { seconds: Math.round(testElapsedMs / 1000) })}
                </p>
              )}
              {!testing && !testError && !testRan && testResults.length === 0 && (
                <p className="m3-body-medium" style={{ color: 'var(--md-sys-color-on-surface-variant)' }}>{t('还没有结果；输入关键词后点「开始测试」')}</p>
              )}
              {testResults.length > 0 && (
                <div>
                  <p className="m3-label-small mb-2" data-test-count={testResults.length}>{t('测试结果：{{count}} 条', { count: testResults.length })}</p>
                  {testResults.map((item, index) => (
                    <div key={index} className="py-1.5" style={{ borderTop: index === 0 ? 'none' : '1px solid var(--md-sys-color-outline-variant)' }}>
                      <p className="m3-body-medium">{item.bookName}</p>
                      <p className="m3-body-small">{item.author || t('未知作者')}{item.bookUrl ? ' · ' + item.bookUrl : ''}</p>
                    </div>
                  ))}
                </div>
              )}
            </div>
            <div className="flex justify-end px-5 py-3" style={{ borderTop: '1px solid var(--md-sys-color-outline-variant)' }}>
              <button onClick={() => setTestSource(null)} className="m3-text-button is-compact">{t('关闭')}</button>
            </div>
        </Modal>
      )}
    </div>
  )
}


