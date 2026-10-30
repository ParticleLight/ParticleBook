// ── PB-024-B：从 EpubRenderer.tsx 抽出的 theme / injection 小工具 ────────
// spine 条目列表、spread 取值映射、竖排（writing-mode）样式注入。纯搬移。

// epub.js exposes the spine entry list at runtime, but its bundled types omit
// it and the property name differs between builds (`items` vs `spineItems`).
// Model it once here so call sites read plainly instead of casting to any.
export interface PbSpineItem { href?: string; canonical?: string }
export const spineEntries = (spine: unknown): PbSpineItem[] => {
  const s = spine as { items?: PbSpineItem[]; spineItems?: PbSpineItem[] } | undefined
  return s?.items ?? s?.spineItems ?? []
}

// epub.js 的 spread 取值只有 none|auto|both
export function epubSpreadValue(spread: 'auto' | 'single' | 'double'): 'none' | 'auto' | 'both' {
  if (spread === 'double') return 'both'
  if (spread === 'auto') return 'auto'
  return 'none'
}

// ── 阅读主题 / 排版（单栏阅读器与分屏共用）───────────────────────────
// 抽出来的原因（2026-10-30 用户反馈「启用分屏后怎么成这样了」）：分屏的 EpubPane
// 自己 new 了一个 epub.js rendition，却只设了字号/字体，**没有套阅读主题**，于是
// 暗色主题下书页仍是「黑字 + 透明底」→ 黑字压深色底，整页几乎看不见。
// 统一在这里定义，两边调用同一份，避免再次漂移。
export interface PbRenditionThemeHost {
  themes: {
    fontSize(size: string): void
    font(family: string): void
    default(rules: Record<string, Record<string, string>>): void
    override(name: string, value: string): void
  }
}

export type ReaderTheme = 'light' | 'dark' | 'sepia'

/** 三档阅读主题在书页 iframe 里的前景/背景色（与单栏阅读器一致）。 */
export const READER_THEME_COLORS: Record<ReaderTheme, { color: string; background: string }> = {
  dark: { color: '#e2e8f0', background: '#1a1a1a' },
  light: { color: '#1a202c', background: '#ffffff' },
  sepia: { color: '#c4b494', background: '#1a1510' },
}

export interface ReaderTypography {
  fontSize: number
  fontFamily: string
  lineHeight: number
  margin: number
  textAlign: 'left' | 'justify'
}

/** 把「字号 / 字体 / 行距 / 页边距 / 对齐」套进书页。 */
export function applyReaderTypography(rendition: PbRenditionThemeHost | null | undefined, typo: ReaderTypography) {
  if (!rendition) return
  try {
    rendition.themes.fontSize(`${typo.fontSize}px`)
    rendition.themes.font(typo.fontFamily)
    rendition.themes.default({
      body: {
        'line-height': `${typo.lineHeight} !important`,
        'text-align': `${typo.textAlign} !important`,
        padding: `${typo.margin}px !important`,
      },
      p: {
        'line-height': `${typo.lineHeight} !important`,
      },
    })
  } catch { /* ignore */ }
}

/** 把阅读主题的前景/背景色套进书页（不套 = 暗色主题下黑字黑底）。 */
export function applyReaderTheme(rendition: PbRenditionThemeHost | null | undefined, theme: ReaderTheme) {
  if (!rendition) return
  const colors = READER_THEME_COLORS[theme] ?? READER_THEME_COLORS.light
  try {
    rendition.themes.override('color', colors.color)
    rendition.themes.override('background', colors.background)
  } catch { /* ignore */ }
}

// 竖排 CSS：writing-mode 只对当前 iframe 文档生效（不继承进 iframe）
export const VERTICAL_STYLE_ID = 'pb-vertical-text'
export const VERTICAL_CSS = 'html, body { writing-mode: vertical-rl !important; -webkit-writing-mode: vertical-rl !important; text-orientation: mixed; }'
// 竖排时图片/表格保持正立（避免整块被旋转成奇怪方向）
export const VERTICAL_MEDIA_CSS = 'img, svg, video, table { writing-mode: horizontal-tb !important; }'

export function setVerticalStyle(doc: Document | null | undefined, on: boolean) {
  if (!doc) return
  try {
    const existing = doc.getElementById(VERTICAL_STYLE_ID)
    if (!on) {
      existing?.remove()
      return
    }
    if (existing) return
    const style = doc.createElement('style')
    style.id = VERTICAL_STYLE_ID
    style.textContent = VERTICAL_CSS + VERTICAL_MEDIA_CSS
    ;(doc.head || doc.documentElement).appendChild(style)
  } catch { /* ignore */ }
}

