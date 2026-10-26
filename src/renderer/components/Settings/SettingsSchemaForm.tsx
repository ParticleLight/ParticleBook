import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { useSettingsStore } from '../../stores/settingsStore'

// ── PB-023-B：schema 驱动的设置表单 ──────────────────────────────────
//
// 后端 SettingsService::SchemaJson()（C++，bridge: settings:schema）返回
// SettingSpec[]：key / type(bool|int|double|string|enum) / default / bookScoped /
// min / max / maxLength / format("url") / allowed[]。
// 本组件按 schema 自动生成控件，写入仍走 db:updateSettings（C++ 会按 schema
// Normalize：夹紧范围、枚举回退、坏 URL/超长丢弃）。
//
// 只渲染「全局」设置：
//   * 跳过 bookScoped（字号/行距/边距/对齐属于书级排版，由阅读设置负责）；
//   * 跳过 language（界面语言用专门的选择器，改 schema 值不会立刻切 i18n）；
//   * 跳过 backup.lastBackupAt（运行时状态，不是用户可编辑项）。
// 敏感值（translation.apiKey 等）从来不在 settings 里（SecretStore/DPAPI 管），
// 所以 schema 里也不会有 —— 本表单不可能把 Key 渲染出来。
//
// 布局沿用设置页现有 class（settings-card / settings-row / settings-toggle），
// 保存时逐项调用 updateSettings，并在保存后刷新 settingsStore，避免与
// 外观页等手动控件不同步。

interface SettingSpec {
  key: string
  type: string
  default: unknown
  bookScoped: boolean
  min?: number
  max?: number
  maxLength?: number
  format?: string
  allowed?: string[]
}

// 设置项的中文标签（key 本身是 i18n key，en 在 en-settings.ts）
const KEY_LABELS: Record<string, string> = {
  theme: '主题',
  accentColor: '主题色',
  fontFamily: '字体',
  autoSaveProgress: '自动保存进度',
  showReadingTime: '显示阅读时间',
  defaultViewMode: '默认视图',
  defaultSortBy: '默认排序',
  'tts.enabled': '启用朗读',
  'tts.voice': '默认音色 ID',
  'tts.rate': '语速',
  'tts.volume': '音量',
  'tts.highlightSentence': '高亮当前句',
  'tts.sleepTimerMinutes': '睡眠定时（分钟）',
  'translation.enabled': '启用 AI 翻译',
  'translation.provider': '翻译提供方',
  'translation.baseUrl': 'Base URL',
  'translation.model': '模型',
  'translation.targetLanguage': '目标语言',
  'translation.timeoutSeconds': '超时（秒）',
  'dictionary.enabled': '启用词典',
  'dictionary.provider': '词典来源',
  'dictionary.autoLookup': '自动查词',
  'dictionary.maxResults': '最多释义条数',
  'backup.enabled': '启用自动备份',
  'backup.path': '备份目录',
  'backup.intervalDays': '备份间隔（天）',
  'backup.keepCount': '保留份数',
}

// 枚举值的显示文案（机器值 → i18n key）；找不到就显示原始值
const ENUM_LABELS: Record<string, Record<string, string>> = {
  theme: { light: '浅色', dark: '深色', sepia: '护眼' },
  accentColor: { blue: '蓝色', purple: '紫色', green: '绿色', orange: '橙色' },
  defaultViewMode: { grid: '网格视图', list: '列表视图' },
  defaultSortBy: { last_opened: '最近阅读', added_at: '添加时间', title: '书名', author: '作者' },
  textAlign: { left: '左对齐', justify: '两端对齐' },
  language: { zh: '中文', en: 'English' },
}

const KEY_DESCS: Record<string, string> = {
  fontFamily: 'CSS 字体串，例如 Georgia, Noto Serif SC, serif',
  'tts.voice': 'SAPI 音色 token id；留空用系统默认音色',
  'tts.rate': '-10 最慢，10 最快，0 为正常语速',
  'translation.baseUrl': 'OpenAI 兼容接口地址，例如 https://api.deepseek.com/v1',
  'translation.timeoutSeconds': '单次请求超时时间',
  'backup.path': '备份文件存放目录，留空用默认位置',
}

const SECTION_ORDER = ['appearance', 'reading', 'library', 'tts', 'translation', 'dictionary', 'backup', 'other'] as const
type SectionId = typeof SECTION_ORDER[number]

const SECTION_TITLES: Record<SectionId, string> = {
  appearance: '外观',
  reading: '阅读',
  library: '书架',
  tts: 'TTS 朗读',
  translation: 'AI 翻译',
  dictionary: '词典',
  backup: '备份',
  other: '其他',
}

// 无前缀的基础键归到哪个分区
const BASE_SECTION: Record<string, SectionId> = {
  theme: 'appearance',
  accentColor: 'appearance',
  fontFamily: 'appearance',
  autoSaveProgress: 'reading',
  showReadingTime: 'reading',
  fontSize: 'reading',
  lineHeight: 'reading',
  margin: 'reading',
  textAlign: 'reading',
  defaultViewMode: 'library',
  defaultSortBy: 'library',
}

const HIDDEN_KEYS = new Set(['language', 'backup.lastBackupAt'])

function sectionOf(key: string): SectionId {
  const dot = key.indexOf('.')
  if (dot > 0) {
    const prefix = key.slice(0, dot)
    if ((SECTION_ORDER as readonly string[]).includes(prefix)) return prefix as SectionId
    return 'other'
  }
  return BASE_SECTION[key] ?? 'other'
}

function labelOf(t: TFunction, key: string): string {
  const known = KEY_LABELS[key]
  return known ? t(known) : key
}

function descriptionOf(t: TFunction, key: string): string {
  const desc = KEY_DESCS[key]
  return desc ? t(desc) : ''
}

function enumLabel(t: TFunction, key: string, value: string): string {
  const mapping = ENUM_LABELS[key]
  const i18nKey = mapping ? mapping[value] : undefined
  return i18nKey ? t(i18nKey) : value
}

function isHttpUrl(value: string): boolean {
  const lower = value.trim().toLowerCase()
  return lower.startsWith('http://') || lower.startsWith('https://')
}

function typeLabel(t: TFunction, spec: SettingSpec): string {
  if (spec.type === 'bool') return t('开关')
  if (spec.type === 'enum') return t('单选')
  if (spec.type === 'int') return t('整数')
  if (spec.type === 'double') return t('小数')
  if (spec.format === 'url') return t('URL')
  return t('文本')
}

interface RowStatus {
  state: 'saving' | 'saved' | 'error'
  message: string
}

interface SettingsSchemaFormProps {
  // 外层介绍文案由页面提供；表单自身只管渲染与保存
  className?: string
}

export function SettingsSchemaForm({ className = '' }: SettingsSchemaFormProps) {
  const { t } = useTranslation()
  const [specs, setSpecs] = useState<SettingSpec[]>([])
  const [values, setValues] = useState<Record<string, unknown>>({})
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [status, setStatus] = useState<Record<string, RowStatus>>({})
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [groupNotice, setGroupNotice] = useState<Record<string, string>>({})
  const timersRef = useRef<Record<string, ReturnType<typeof setTimeout>>>({})

  const load = useCallback(async () => {
    setLoading(true)
    setLoadError('')
    try {
      const [schema, settings] = await Promise.all([
        window.electronAPI.getSettingsSchema(),
        window.electronAPI.getSettings(),
      ])
      const list = (schema || []).filter((spec) => !spec.bookScoped && !HIDDEN_KEYS.has(spec.key))
      setSpecs(list)
      const initial: Record<string, unknown> = {}
      for (const spec of list) {
        const current = (settings as Record<string, unknown>)[spec.key]
        initial[spec.key] = current !== undefined ? current : spec.default
      }
      setValues(initial)
      setDrafts({})
      setStatus({})
      setGroupNotice({})
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])
  useEffect(() => () => { for (const id of Object.values(timersRef.current)) clearTimeout(id) }, [])

  const save = useCallback(async (spec: SettingSpec, value: unknown) => {
    setStatus((prev) => ({ ...prev, [spec.key]: { state: 'saving', message: t('保存中…') } }))
    try {
      const patch: Record<string, unknown> = {}
      patch[spec.key] = value
      await window.electronAPI.updateSettings(patch as any)
      setStatus((prev) => ({ ...prev, [spec.key]: { state: 'saved', message: t('已保存') } }))
      // 外观/阅读/书架这些键也被 settingsStore 的手动控件使用，写完刷新一下避免不同步
      void useSettingsStore.getState().loadSettings()
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e)
      setStatus((prev) => ({ ...prev, [spec.key]: { state: 'error', message: t('保存失败：{{error}}', { error: message }) } }))
    }
  }, [t])

  const commitValue = useCallback((spec: SettingSpec, value: unknown) => {
    setValues((prev) => ({ ...prev, [spec.key]: value }))
    void save(spec, value)
  }, [save])

  const scheduleCommit = useCallback((spec: SettingSpec, value: unknown) => {
    const existing = timersRef.current[spec.key]
    if (existing) clearTimeout(existing)
    timersRef.current[spec.key] = setTimeout(() => {
      delete timersRef.current[spec.key]
      commitValue(spec, value)
    }, 600)
  }, [commitValue])

  const numberValue = useCallback((spec: SettingSpec, raw: string): { ok: boolean; value: number; message: string } => {
    let num = Number(raw)
    if (!Number.isFinite(num)) return { ok: false, value: Number(spec.default) || 0, message: t('请输入数字') }
    const min = typeof spec.min === 'number' ? spec.min : undefined
    const max = typeof spec.max === 'number' ? spec.max : undefined
    if (min !== undefined && num < min) num = min
    if (max !== undefined && num > max) num = max
    if (spec.type === 'int') num = Math.round(num)
    return { ok: true, value: num, message: '' }
  }, [t])

  const stringValue = useCallback((spec: SettingSpec, raw: string): { ok: boolean; value: string; message: string } => {
    let text = raw
    if (spec.format === 'url' && text.trim() !== '' && !isHttpUrl(text)) {
      return { ok: false, value: raw, message: t('需要 http(s):// 开头的地址') }
    }
    if (typeof spec.maxLength === 'number' && spec.maxLength > 0 && text.length > spec.maxLength) {
      text = text.slice(0, spec.maxLength)
    }
    return { ok: true, value: text, message: '' }
  }, [t])

  const groups = useMemo(() => {
    const map = new Map<SectionId, SettingSpec[]>()
    for (const spec of specs) {
      const section = sectionOf(spec.key)
      const bucket = map.get(section)
      if (bucket) bucket.push(spec)
      else map.set(section, [spec])
    }
    return SECTION_ORDER
      .filter((id) => (map.get(id)?.length ?? 0) > 0)
      .map((id) => ({ id, title: t(SECTION_TITLES[id]), specs: map.get(id) as SettingSpec[] }))
  }, [specs, t])

  const resetGroup = useCallback(async (section: SectionId, groupSpecs: SettingSpec[]) => {
    const defaults: Record<string, unknown> = {}
    for (const spec of groupSpecs) defaults[spec.key] = spec.default
    try {
      await window.electronAPI.updateSettings(defaults as any)
      setValues((prev) => ({ ...prev, ...defaults }))
      setDrafts((prev) => {
        const next = { ...prev }
        for (const spec of groupSpecs) delete next[spec.key]
        return next
      })
      setStatus((prev) => {
        const next = { ...prev }
        for (const spec of groupSpecs) next[spec.key] = { state: 'saved', message: t('已恢复默认值') }
        return next
      })
      setGroupNotice((prev) => ({ ...prev, [section]: t('已恢复本组默认值') }))
      void useSettingsStore.getState().loadSettings()
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e)
      setGroupNotice((prev) => ({ ...prev, [section]: t('恢复默认值失败：{{error}}', { error: message }) }))
    }
  }, [t])

  const renderControl = (spec: SettingSpec) => {
    const value = values[spec.key]
    const inputStyle = {
      background: 'var(--bg-tertiary)',
      color: 'var(--text-primary)',
      border: '1px solid var(--border)',
    } as const

    if (spec.type === 'bool') {
      return (
        <button
          className={'settings-toggle' + (value ? ' active' : '')}
          role="switch"
          aria-checked={!!value}
          aria-label={labelOf(t, spec.key)}
          data-setting-key={spec.key}
          onClick={() => commitValue(spec, !value)}
        />
      )
    }

    if (spec.type === 'enum') {
      const allowed = spec.allowed ?? []
      return (
        <select
          value={String(value ?? '')}
          onChange={(e) => commitValue(spec, e.target.value)}
          className="px-2 py-1.5 text-sm rounded-md w-full max-w-[260px]"
          style={inputStyle}
          aria-label={labelOf(t, spec.key)}
          data-setting-key={spec.key}
        >
          {allowed.map((option) => (
            <option key={option} value={option}>{enumLabel(t, spec.key, option)}</option>
          ))}
        </select>
      )
    }

    const isNumber = spec.type === 'int' || spec.type === 'double'
    const draft = drafts[spec.key]
    const shown = draft !== undefined ? draft : String(value === undefined || value === null ? '' : value)
    return (
      <input
        type={isNumber ? 'number' : 'text'}
        value={shown}
        min={isNumber ? spec.min : undefined}
        max={isNumber ? spec.max : undefined}
        step={spec.type === 'double' ? 0.1 : 1}
        placeholder={spec.format === 'url' ? 'https://…' : ''}
        onChange={(e) => {
          const next = e.target.value
          setDrafts((prev) => ({ ...prev, [spec.key]: next }))
          const parsed = isNumber ? numberValue(spec, next) : stringValue(spec, next)
          if (parsed.ok) scheduleCommit(spec, parsed.value)
        }}
        onBlur={() => {
          const current = drafts[spec.key]
          if (current === undefined) return
          const parsed = isNumber ? numberValue(spec, current) : stringValue(spec, current)
          setDrafts((prev) => {
            const next = { ...prev }
            delete next[spec.key]
            return next
          })
          if (!parsed.ok) {
            setStatus((prev) => ({ ...prev, [spec.key]: { state: 'error', message: parsed.message } }))
            return
          }
          commitValue(spec, parsed.value)
        }}
        className="px-2 py-1.5 text-sm rounded-md w-full max-w-[300px]"
        style={inputStyle}
        aria-label={labelOf(t, spec.key)}
        data-setting-key={spec.key}
      />
    )
  }

  if (loading) {
    return (
      <div className={className}>
        <div className="settings-card flex items-center gap-3">
          <span className="inline-block w-4 h-4 rounded-full border-2 border-current border-t-transparent animate-spin" style={{ color: 'var(--text-tertiary)' }} />
          <span className="text-sm" style={{ color: 'var(--text-secondary)' }}>{t('正在加载设置 schema…')}</span>
        </div>
      </div>
    )
  }

  if (loadError) {
    return (
      <div className={className}>
        <div className="settings-card">
          <p className="text-sm mb-1" style={{ color: 'var(--color-red)' }}>{t('加载设置 schema 失败：{{error}}', { error: loadError })}</p>
          <p className="text-xs mb-3" style={{ color: 'var(--text-tertiary)' }}>{t('高级设置依赖 C++ 的 settings:schema 接口；这里失败不影响其它设置页。')}</p>
          <button onClick={() => { void load() }} className="btn-secondary text-xs" data-setting-retry="1">{t('重试')}</button>
        </div>
      </div>
    )
  }

  return (
    <div className={className} data-settings-schema-form="1">
      <p className="text-xs mb-3 break-words" style={{ color: 'var(--text-tertiary)' }}>
        {t('由 C++ Settings schema 自动生成，共 {{count}} 项；只显示全局设置（字号/行距/边距/对齐这些书级排版请到阅读设置里调整）。', { count: specs.length })}
      </p>

      {specs.length === 0 && (
        <div className="settings-card">
          <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>{t('schema 里没有可编辑的全局设置项。')}</p>
        </div>
      )}

      {groups.map((group) => (
        <div className="settings-card" key={group.id} data-setting-group={group.id}>
          <div className="flex items-center justify-between mb-1 gap-3">
            <h3 className="text-[13px] font-semibold uppercase tracking-wider" style={{ color: 'var(--text-tertiary)' }}>{group.title}</h3>
            <div className="flex items-center gap-2 flex-shrink-0">
              {groupNotice[group.id] && (
                <span className="text-[11px]" style={{ color: 'var(--text-tertiary)' }}>{groupNotice[group.id]}</span>
              )}
              <button
                onClick={() => { void resetGroup(group.id, group.specs) }}
                className="btn-secondary text-xs"
                data-setting-reset={group.id}
              >
                {t('恢复本组默认值')}
              </button>
            </div>
          </div>

          {group.specs.map((spec) => {
            const rowStatus = status[spec.key]
            const rangeHint = (spec.type === 'int' || spec.type === 'double') && (typeof spec.min === 'number' || typeof spec.max === 'number')
              ? t('范围 {{min}}–{{max}}', { min: spec.min, max: spec.max })
              : ''
            const desc = descriptionOf(t, spec.key)
            return (
              <div className="settings-row" key={spec.key} data-setting-row={spec.key}>
                <div className="flex-1 min-w-0 pr-4">
                  <div className="settings-row-label flex items-center gap-2 flex-wrap">
                    {labelOf(t, spec.key)}
                    <span className="text-[10px] px-1.5 py-0.5 rounded" style={{ background: 'var(--bg-tertiary)', color: 'var(--text-tertiary)' }}>{typeLabel(t, spec)}</span>
                  </div>
                  <div className="settings-row-desc break-words">
                    {desc || spec.key}
                    {rangeHint ? ' · ' + rangeHint : ''}
                  </div>
                  {rowStatus && (
                    <div className="text-[11px] mt-1" style={{ color: rowStatus.state === 'error' ? 'var(--color-red)' : 'var(--text-tertiary)' }} data-setting-status={spec.key}>
                      {rowStatus.message}
                    </div>
                  )}
                </div>
                <div className="flex-shrink-0 w-[300px] flex justify-end">{renderControl(spec)}</div>
              </div>
            )
          })}
        </div>
      ))}
    </div>
  )
}


