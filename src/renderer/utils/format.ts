import i18n from '../i18n'

// Reading time returns a bare duration ("30 分钟" / "30 minutes") — callers
// that need a verb (ReaderView "阅读 {{time}}") add it themselves so that
// wrapping contexts like BookCard "已读 {{time}}" or the "总阅读时间" stat
// don't double the verb. Uses i18next plural variants ('…_one'/'…_other' in
// the en dictionary); zh falls back to the key itself.
export function formatReadingTime(seconds: number): string {
  if (seconds < 60) return i18n.t('{{count}} 秒', { count: seconds })
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return i18n.t('{{count}} 分钟', { count: minutes })
  const hours = Math.floor(minutes / 60)
  const remainingMinutes = minutes % 60
  if (remainingMinutes > 0) {
    return i18n.t('{{hours}} 小时 {{minutes}} 分钟', { hours, minutes: remainingMinutes })
  }
  return i18n.t('{{count}} 小时', { count: hours })
}

export async function extractTextPreview(filePath: string, maxLength = 120): Promise<string | null> {
  try {
    const content = await window.electronAPI.readFile(filePath)
    const text = new TextDecoder('utf-8', { fatal: false }).decode(new Uint8Array(content))
    const cleaned = text.replace(/\s+/g, ' ').trim()
    return cleaned.slice(0, maxLength) || null
  } catch {
    return null
  }
}

export const formatColors: Record<string, string> = {
  epub: 'bg-blue-600',
  pdf: 'bg-red-600',
  mobi: 'bg-orange-600',
  txt: 'bg-gray-600',
  fb2: 'bg-green-600',
  cbz: 'bg-purple-600',
  cbr: 'bg-pink-600',
  html: 'bg-cyan-600',
  markdown: 'bg-teal-600',
}
