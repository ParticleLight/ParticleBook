import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { translationErrorMessageKey } from '../Reader/TranslationPopup'
import { Switch } from '../UI/Switch'
import { M3Select, type M3SelectOption } from '../UI/M3Select'

// 「测试连接」的状态：只保存展示用的结果，Key 永远不进 state 之外的任何地方
// （输入框里的 Key 在测之前写进 SecretStore 并清空）。
interface TranslationTestState {
  status: 'idle' | 'testing' | 'ok' | 'error'
  text?: string
  code?: string
  message?: string
}

// PB-002: provider config + DPAPI-backed API key. The key is sent to C++ once
// and never stored in settings (which are backed up/exported).
export function TranslationSettings() {
  const { t } = useTranslation()
  const [enabled, setEnabled] = useState(false)
  // 接口格式（2026-10-29）：chat_completion | anthropic | responses
  const [apiFormat, setApiFormat] = useState('chat_completion')
  const [baseUrl, setBaseUrl] = useState('')
  const [model, setModel] = useState('')
  const [targetLanguage, setTargetLanguage] = useState('zh')
  const [apiKey, setApiKey] = useState('')
  const [hasKey, setHasKey] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [testState, setTestState] = useState<TranslationTestState>({ status: 'idle' })
  // 「获取模型」：拿到的是 provider 的模型 id 列表；获取失败单独提示，不影响保存配置。
  const [models, setModels] = useState<string[]>([])
  const [fetchingModels, setFetchingModels] = useState(false)
  const [modelsMessage, setModelsMessage] = useState('')

  useEffect(() => {
    window.electronAPI.getSettings().then((s: any) => {
      setEnabled(!!s['translation.enabled'])
      setApiFormat(s['translation.apiFormat'] || 'chat_completion')
      setBaseUrl(s['translation.baseUrl'] || '')
      setModel(s['translation.model'] || '')
      setTargetLanguage(s['translation.targetLanguage'] || 'zh')
    }).catch(() => {})
    window.electronAPI.translationHasKey().then(setHasKey).catch(() => {})
  }, [])

  const formatOptions = useMemo<M3SelectOption[]>(() => [
    { value: 'chat_completion', label: t('Chat Completions（OpenAI 兼容）') },
    { value: 'anthropic', label: t('Anthropic Messages') },
    { value: 'responses', label: t('Responses（OpenAI）') },
  ], [t])

  const modelOptions = useMemo<M3SelectOption[]>(
    () => models.map((id) => ({ value: id, label: id })),
    [models],
  )

  // 各格式的 baseUrl 习惯写法（只在输入框为空时作为 placeholder 提示）。
  const baseUrlHint = apiFormat === 'anthropic'
    ? 'https://api.anthropic.com/v1'
    : apiFormat === 'responses' ? 'https://api.openai.com/v1' : 'https://api.deepseek.com/v1'

  // 当前表单 → settings 补丁（保存配置和测试连接共用，保证测的就是表单里的值）。
  const settingsPatch = () => ({
    'translation.enabled': enabled,
    'translation.apiFormat': apiFormat,
    'translation.baseUrl': baseUrl,
    'translation.model': model,
    'translation.targetLanguage': targetLanguage,
  })

  // PB-002（2026-10-29）：主动拉取 provider 的模型列表（GET <base>/models）。
  // 先把当前表单（含未保存的 baseUrl / Key）落库，再让 C++ 带着 SecretStore 里的
  // Key 发一次只读请求 —— 与「测试连接」同样的顺序，保证拉到的是表单里这套配置。
  const fetchModels = async () => {
    if (fetchingModels) return
    setFetchingModels(true)
    setModelsMessage('')
    const draftKey = apiKey.trim()
    try {
      await window.electronAPI.updateSettings(settingsPatch())
      if (draftKey) {
        const saved = await window.electronAPI.translationSetKey(draftKey)
        if (!saved) throw new Error('key_save_failed')
        setHasKey(true)
        setApiKey('')
      }
      const result = await window.electronAPI.translationListModels()
      const list = result && Array.isArray(result.models) ? result.models : []
      if (result && result.ok && list.length > 0) {
        setModels(list)
        setModelsMessage(t('已获取 {{count}} 个模型', { count: list.length }))
      } else {
        const code = (result && result.code) || 'translation_failed'
        setModelsMessage(t('获取模型失败：{{code}}', { code }))
      }
    } catch (error) {
      const code = (error instanceof Error ? error.message : String(error)).trim() || 'translation_failed'
      setModelsMessage(t('获取模型失败：{{code}}', { code }))
    } finally {
      setFetchingModels(false)
    }
  }

  const saveConfig = () => {
    setBusy(true); setMessage('')
    window.electronAPI.updateSettings(settingsPatch())
      .then(() => setMessage(t('已保存')))
      .catch(() => setMessage(t('保存失败')))
      .finally(() => setBusy(false))
  }

  const saveKey = () => {
    if (!apiKey.trim()) return
    setBusy(true); setMessage('')
    window.electronAPI.translationSetKey(apiKey.trim())
      .then((ok) => { setHasKey(ok); setApiKey(''); setMessage(ok ? t('密钥已保存（DPAPI）') : t('保存失败')) })
      .catch(() => setMessage(t('保存失败')))
      .finally(() => setBusy(false))
  }

  const clearKey = () => {
    setBusy(true); setMessage('')
    window.electronAPI.translationClearKey()
      .then(() => { setHasKey(false); setMessage(t('密钥已清除')) })
      .catch(() => setMessage(t('操作失败')))
      .finally(() => setBusy(false))
  }

  // PB-002-C：测试连接 —— 先把当前表单（含未保存的改动 / Key）落到 settings +
  // SecretStore，再用 "Hello" 试翻一次。成功显示译文，失败显示错误码 + 本地化
  // 文案；任何情况下都不回显 Key。
  const testConnection = async () => {
    if (busy) return
    setBusy(true)
    setMessage('')
    setTestState({ status: 'testing' })
    const draftKey = apiKey.trim()
    try {
      await window.electronAPI.updateSettings(settingsPatch())
      if (draftKey) {
        const saved = await window.electronAPI.translationSetKey(draftKey)
        if (!saved) throw new Error('key_save_failed')
        setHasKey(true)
        setApiKey('')
      }
      const result = await window.electronAPI.translationTranslate(
        [{ id: 't', text: 'Hello' }],
        'en',
        'zh',
      )
      const translated = result && Array.isArray(result.segments) && result.segments[0]
        ? (result.segments[0].text || '').trim()
        : ''
      if (result && result.ok && translated) {
        setTestState({ status: 'ok', text: translated, code: result.code })
        return
      }
      const code = (result && result.code) || 'translation_failed'
      setTestState({ status: 'error', code, message: t(translationErrorMessageKey(code)) })
    } catch (error) {
      const code = (error instanceof Error ? error.message : String(error)).trim() || 'translation_failed'
      setTestState({ status: 'error', code, message: t(translationErrorMessageKey(code)) })
    } finally {
      setBusy(false)
    }
  }

  // 样式统一走 globals.css 的 M3 基元（.m3-text-field / .m3-outlined-button）
  return (
    <div className="settings-card">
      <h3 className="m3-section-title mb-3">{t('AI 翻译')}</h3>
      <p className="m3-body-medium mb-3" style={{ color: 'var(--md-sys-color-on-surface-variant)' }}>
        {t('使用你自己的 AI 服务，支持 Chat Completions / Anthropic Messages / Responses 三种接口格式。Key 只保存在本机 DPAPI 加密存储，不进入设置/备份/日志。')}
      </p>
      <label className="flex items-center gap-2 m3-body-medium mb-3 cursor-pointer" htmlFor="translation-enabled">
        <Switch id="translation-enabled" checked={enabled} onChange={setEnabled} />
        {t('启用翻译')}
      </label>
      <div className="grid grid-cols-2 gap-2 mb-2">
        <div>
          <label className="m3-label-small block mb-1" htmlFor="translation-api-format">{t('接口格式')}</label>
          <M3Select
            aria-label={t('接口格式')}
            value={apiFormat}
            options={formatOptions}
            onChange={(value) => { setApiFormat(value); setModels([]); setModelsMessage('') }}
            className="w-full"
            testid="translation-api-format"
          />
        </div>
        <div>
          <label className="m3-label-small block mb-1" htmlFor="translation-model">{t('模型')}</label>
          <div className="flex gap-2">
            <input
              id="translation-model"
              className="m3-text-field"
              value={model}
              onChange={(e) => setModel(e.target.value)}
              placeholder={t('模型（如 deepseek-chat）')}
            />
            <button
              type="button"
              onClick={fetchModels}
              disabled={busy || fetchingModels}
              className="m3-outlined-button whitespace-nowrap"
              title={t('按当前配置从服务端获取模型列表')}
              data-testid="translation-fetch-models"
            >
              {fetchingModels ? t('获取中…') : t('获取模型')}
            </button>
          </div>
        </div>
      </div>
      {models.length > 0 && (
        <div className="mb-2">
          <M3Select
            aria-label={t('从已获取的模型中选择')}
            value={model}
            options={modelOptions}
            onChange={setModel}
            className="w-full"
            testid="translation-model-picker"
          />
        </div>
      )}
      {modelsMessage && (
        <p className="mb-2 text-xs" style={{ color: 'var(--text-secondary)' }} data-testid="translation-models-message">
          {modelsMessage}
        </p>
      )}
      <input className="m3-text-field mb-2" value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} placeholder={baseUrlHint} />
      <div className="flex gap-2 mb-2">
        <input className="m3-text-field" value={targetLanguage} onChange={(e) => setTargetLanguage(e.target.value)} placeholder={t('目标语言')} />
        <button onClick={saveConfig} disabled={busy} className="m3-outlined-button whitespace-nowrap">{t('保存配置')}</button>
      </div>
      <div className="flex gap-2">
        <input type="password" className="m3-text-field" value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder={hasKey ? t('Key 已保存，输入可替换') : t('API Key')} />
        <button onClick={saveKey} disabled={busy || !apiKey.trim()} className="m3-outlined-button whitespace-nowrap">{t('保存密钥')}</button>
        {hasKey && <button onClick={clearKey} disabled={busy} className="m3-text-button whitespace-nowrap">{t('清除')}</button>}
      </div>
      <div className="flex items-center gap-2 mt-2">
        <button onClick={testConnection} disabled={busy} className="m3-outlined-button whitespace-nowrap">
          {testState.status === 'testing' ? t('测试中…') : t('测试连接')}
        </button>
        <span className="m3-body-small">
          {t('测试会先保存当前配置与 Key，再用 "Hello" 试翻一次；不显示 Key。')}
        </span>
      </div>
      {testState.status === 'ok' && (
        <p className="mt-2 text-xs break-all" style={{ color: 'var(--notify-success-text)' }}>
          {t('连接成功：{{text}}', { text: testState.text || '' })}
        </p>
      )}
      {testState.status === 'error' && (
        <p className="mt-2 text-xs break-all" style={{ color: 'var(--notify-error-text)' }}>
          {t('连接失败：{{code}}', { code: testState.code || 'translation_failed' })}
          {testState.message ? ' — ' + testState.message : ''}
        </p>
      )}
      {message && <p className="mt-2 text-xs" style={{ color: 'var(--text-secondary)' }}>{message}</p>}
    </div>
  )
}