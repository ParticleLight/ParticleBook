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

