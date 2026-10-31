import i18n from '../i18n'
import { readBookFile } from './fileReader'

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

// 卡片预览（2026-10-29）：FB2 / HTML / DOCX（导入后落盘成自包含 HTML）都是标记语言，
// 直接解码文件头会得到一屏 `<?xml …>` / `<html …>`，在书库里非常难看。这类格式先
// 剥掉标签、head/script/style 与实体再截断；纯文本类（txt / mobi / azw3 / markdown）原样。
const MARKUP_FORMATS = new Set(['fb2', 'html', 'docx'])

function stripMarkup(text: string): string {
  return text
    .replace(/^\uFEFF/, '')
    .replace(/<\?xml[\s\S]*?\?>/gi, ' ')
    .replace(/<!DOCTYPE[\s\S]*?>/gi, ' ')
    .replace(/<head[\s\S]*?<\/head>/gi, ' ')
    // FB2 的 <description> 是书名/作者/日期的元数据，卡片预览要的是正文
    .replace(/<description[\s\S]*?<\/description>/gi, ' ')
    .replace(/<(script|style)[\s\S]*?<\/\1>/gi, ' ')
    .replace(/<br\s*\/?>/gi, ' ')
    .replace(/<\/(p|div|h[1-6]|li|tr|section)>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;|&apos;/gi, "'")
    .replace(/&amp;/gi, '&')
    .replace(/&#(\d+);/g, (_, dec: string) => {
      const code = Number(dec)
      return Number.isFinite(code) && code > 0 && code <= 0x10ffff ? String.fromCodePoint(code) : ' '
    })
}

export async function extractTextPreview(filePath: string, format = '', maxLength = 120): Promise<string | null> {
  try {
    const content = await readBookFile(filePath)
    if (!content) return null
    const raw = new TextDecoder('utf-8', { fatal: false }).decode(content)
    const text = MARKUP_FORMATS.has(format) ? stripMarkup(raw) : raw
    const cleaned = text.replace(/\s+/g, ' ').trim()
    return cleaned.slice(0, maxLength) || null
  } catch {
    return null
  }
}

// 格式徽章文案（2026-10-31）：列表视图的「格式」列只有 1/12 宽、卡片徽章又压在封面上，
// `MARKDOWN` 这种长名字会溢出压到隔壁的「大小」列（实测穿模）。徽章统一用短标签；
// 详情对话框里仍用完整名字（那里是「格式：MARKDOWN」的散文，不占列宽）。
const FORMAT_BADGE_LABELS: Record<string, string> = {
  markdown: 'MD',
  md: 'MD',
}

export function formatBadgeLabel(format: string): string {
  const key = (format || '').toLowerCase()
  return FORMAT_BADGE_LABELS[key] ?? key.toUpperCase()
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
