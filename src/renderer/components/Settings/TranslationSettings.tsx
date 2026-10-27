import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { translationErrorMessageKey } from '../Reader/TranslationPopup'
import { Switch } from '../UI/Switch'

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
  const [provider, setProvider] = useState('openai')
  const [baseUrl, setBaseUrl] = useState('')
  const [model, setModel] = useState('')
  const [targetLanguage, setTargetLanguage] = useState('zh')
  const [apiKey, setApiKey] = useState('')
  const [hasKey, setHasKey] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [testState, setTestState] = useState<TranslationTestState>({ status: 'idle' })

  useEffect(() => {
    window.electronAPI.getSettings().then((s: any) => {
      setEnabled(!!s['translation.enabled'])
      setProvider(s['translation.provider'] || 'openai')
      setBaseUrl(s['translation.baseUrl'] || '')
      setModel(s['translation.model'] || '')
      setTargetLanguage(s['translation.targetLanguage'] || 'zh')
    }).catch(() => {})
    window.electronAPI.translationHasKey().then(setHasKey).catch(() => {})
  }, [])

  // 当前表单 → settings 补丁（保存配置和测试连接共用，保证测的就是表单里的值）。
  const settingsPatch = () => ({
    'translation.enabled': enabled,
    'translation.provider': provider,
    'translation.baseUrl': baseUrl,
    'translation.model': model,
    'translation.targetLanguage': targetLanguage,
  })

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
        {t('使用你自己的 OpenAI 兼容 API。Key 只保存在本机 DPAPI 加密存储，不进入设置/备份/日志。')}
      </p>
      <label className="flex items-center gap-2 m3-body-medium mb-3 cursor-pointer" htmlFor="translation-enabled">
        <Switch id="translation-enabled" checked={enabled} onChange={setEnabled} />
        {t('启用翻译')}
      </label>
      <div className="grid grid-cols-2 gap-2 mb-2">
        <input className="m3-text-field" value={provider} onChange={(e) => setProvider(e.target.value)} placeholder={t('提供方（如 deepseek）')} />
        <input className="m3-text-field" value={model} onChange={(e) => setModel(e.target.value)} placeholder={t('模型（如 deepseek-chat）')} />
      </div>
      <input className="m3-text-field mb-2" value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} placeholder="https://api.deepseek.com/v1" />
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
