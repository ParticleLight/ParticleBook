import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'

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

  const saveConfig = () => {
    setBusy(true); setMessage('')
    window.electronAPI.updateSettings({
      'translation.enabled': enabled,
      'translation.provider': provider,
      'translation.baseUrl': baseUrl,
      'translation.model': model,
      'translation.targetLanguage': targetLanguage,
    }).then(() => setMessage(t('已保存')))
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

  const input = 'w-full px-3 py-2 rounded-lg text-sm border'
  const inputStyle = { background: 'var(--bg-primary)', borderColor: 'var(--border)', color: 'var(--text-primary)' }

  return (
    <div className="settings-card">
      <h3 className="text-[13px] font-semibold uppercase tracking-wider mb-3" style={{ color: 'var(--text-tertiary)' }}>{t('AI 翻译')}</h3>
      <p className="text-sm mb-3" style={{ color: 'var(--text-secondary)' }}>
        {t('使用你自己的 OpenAI 兼容 API。Key 只保存在本机 DPAPI 加密存储，不进入设置/备份/日志。')}
      </p>
      <label className="flex items-center gap-2 text-sm mb-3">
        <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
        {t('启用翻译')}
      </label>
      <div className="grid grid-cols-2 gap-2 mb-2">
        <input className={input} style={inputStyle} value={provider} onChange={(e) => setProvider(e.target.value)} placeholder={t('提供方（如 deepseek）')} />
        <input className={input} style={inputStyle} value={model} onChange={(e) => setModel(e.target.value)} placeholder={t('模型（如 deepseek-chat）')} />
      </div>
      <input className={input + ' mb-2'} style={inputStyle} value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} placeholder="https://api.deepseek.com/v1" />
      <div className="flex gap-2 mb-2">
        <input className={input} style={inputStyle} value={targetLanguage} onChange={(e) => setTargetLanguage(e.target.value)} placeholder={t('目标语言')} />
        <button onClick={saveConfig} disabled={busy} className="btn-secondary whitespace-nowrap">{t('保存配置')}</button>
      </div>
      <div className="flex gap-2">
        <input type="password" className={input} style={inputStyle} value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder={hasKey ? t('Key 已保存，输入可替换') : t('API Key')} />
        <button onClick={saveKey} disabled={busy || !apiKey.trim()} className="btn-secondary whitespace-nowrap">{t('保存密钥')}</button>
        {hasKey && <button onClick={clearKey} disabled={busy} className="btn-secondary whitespace-nowrap">{t('清除')}</button>}
      </div>
      {message && <p className="mt-2 text-xs" style={{ color: 'var(--text-secondary)' }}>{message}</p>}
    </div>
  )
}
