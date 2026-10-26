// ── PB-021-C：渲染层标注模型 + 纯函数 ───────────────────────────────
//
// 协议形状来自 bridge 生成的全局类型（src/renderer/types/electron.d.ts：
// PbLocator / PbAnnotationInput / PbAnnotation，源头是 bridge/contract.mjs）。
// 本文件只做三件事，刻意不依赖 React / Zustand / 桥接：
//
//   1. 给 UI 起更短的别名（Annotation / AnnotationInput / Locator）与固定顺序
//      的类型列表（侧栏 tab、筛选器共用同一顺序）；
//   2. 位置 / 标注的守卫与稳定 key（React list key、去重、同一位置判定）；
//   3. 纯函数：排序、过滤、分组、计数、旧表 id 关联（并行双写期用）。
//
// 为什么要独立成 types 文件：Reader 组件（后续接线）与 annotationStore 必须
// 用同一套「书内位置怎么排、搜索怎么匹配」的口径，否则侧栏顺序会和搜索
// 跳转对不上；纯函数也可以脱离浏览器直接跑（node / esbuild 即可验证）。

/** 书内位置种类（epub CFI / 文本偏移 / HTML 锚点 / PDF 页内矩形 / 滚动百分比）。 */
export type LocatorKind = PbLocator['kind']
/** 书内位置：kind 决定哪些字段有意义（见 domain/Locator.h 的字段语义）。 */
export type Locator = PbLocator
/** 标注类型：书签 / 高亮 / 笔记。 */
export type AnnotationType = PbAnnotation['type']
/** annotations:add 的入参（id / 时间戳 / legacy* 由 C++ 补）。 */
export type AnnotationInput = PbAnnotationInput
/** 数据库里的一行统一标注（含 legacyId 双写关联）。 */
export type Annotation = PbAnnotation
/** 排序口径：书内位置 / 创建时间 / 更新时间。 */
export type AnnotationSort = 'position' | 'created' | 'updated'
/** 侧栏筛选器：某个类型或全部。 */
export type AnnotationTypeFilter = AnnotationType | 'all'

/** 固定顺序：侧栏 tab、筛选器、空态文案都按这个顺序排。 */
export const ANNOTATION_TYPES: readonly AnnotationType[] = ['bookmark', 'highlight', 'note']
/** 筛选器可选值（含 'all'）。 */
export const ANNOTATION_TYPE_FILTERS: readonly AnnotationTypeFilter[] = [
  'all',
  ...ANNOTATION_TYPES,
]
/** 排序可选值。 */
export const ANNOTATION_SORTS: readonly AnnotationSort[] = ['position', 'created', 'updated']

// 跨 kind 的位置排序：先按 kind 分组（与 Locator 头文件里的 rank 一致），
// kind 内部再按主锚点。纯展示用，不参与"是否同一位置"的判定。
const KIND_RANK: Record<LocatorKind, number> = {
  epub: 0,
  text: 1,
  html: 2,
  pdf: 3,
  scroll: 4,
}

// ── 类型守卫 ─────────────────────────────────────────────────────────

export function isAnnotationType(value: unknown): value is AnnotationType {
  return value === 'bookmark' || value === 'highlight' || value === 'note'
}

export function isEpubLocator(
  locator: Locator,
): locator is Locator & { kind: 'epub'; cfi: string } {
  return locator.kind === 'epub' && typeof locator.cfi === 'string' && locator.cfi.length > 0
}

export function isTextLocator(
  locator: Locator,
): locator is Locator & { kind: 'text'; offset: number } {
  return locator.kind === 'text' && typeof locator.offset === 'number' && locator.offset >= 0
}

export function isHtmlLocator(
  locator: Locator,
): locator is Locator & { kind: 'html'; href: string } {
  return locator.kind === 'html' && typeof locator.href === 'string' && locator.href.length > 0
}

export function isPdfLocator(
  locator: Locator,
): locator is Locator & { kind: 'pdf'; page: number } {
  return locator.kind === 'pdf' && typeof locator.page === 'number' && locator.page >= 1
}

export function isScrollLocator(
  locator: Locator,
): locator is Locator & { kind: 'scroll'; percent: number } {
  return (
    locator.kind === 'scroll' &&
    typeof locator.percent === 'number' &&
    locator.percent >= 0 &&
    locator.percent <= 100
  )
}

// ── 稳定 key / 文本 ──────────────────────────────────────────────────

/** 位置的稳定字符串（同一位置不同 quote 得到同一个 key）。 */
export function locatorKey(locator: Locator): string {
  switch (locator.kind) {
    case 'epub':
      return 'epub:' + (locator.cfi ?? '')
    case 'text':
      return 'text:' + (locator.offset ?? -1) + ':' + (locator.length ?? -1)
    case 'html':
      return 'html:' + (locator.href ?? '') + ':' + (locator.offset ?? -1)
    case 'pdf': {
      const rect = locator.rect
      const suffix = rect ? ':' + [rect.x, rect.y, rect.w, rect.h].join(',') : ''
      return 'pdf:' + (locator.page ?? -1) + suffix
    }
    case 'scroll':
      return 'scroll:' + (locator.percent ?? 0)
    default:
      return 'unknown'
  }
}

/** 标注的稳定 key（书 + 类型 + 位置）：并行双写期用来发现同一位置的重复行。 */
export function annotationKey(annotation: Annotation): string {
  return annotation.bookId + ':' + annotation.type + ':' + locatorKey(annotation.locator)
}

/** 位置上的选区文本（可空）。 */
export function locatorQuote(locator: Locator): string {
  return (locator.quote ?? '').trim()
}

/** 适合列表/提示展示的一行文本：选区 → 标题 → 笔记正文。 */
export function annotationText(annotation: Annotation): string {
  const quote = locatorQuote(annotation.locator)
  if (quote) return quote
  if (annotation.title) return annotation.title
  if (annotation.note) return annotation.note
  return ''
}

// ── 排序 ─────────────────────────────────────────────────────────────

/** 位置比较：kind rank → 主锚点。返回 <0 / 0 / >0。 */
export function compareLocators(a: Locator, b: Locator): number {
  const rankA = KIND_RANK[a.kind] ?? Number.MAX_SAFE_INTEGER
  const rankB = KIND_RANK[b.kind] ?? Number.MAX_SAFE_INTEGER
  if (rankA !== rankB) return rankA - rankB

  switch (a.kind) {
    case 'epub': {
      // CFI 是结构化字符串，同一本书内字典序与实际阅读顺序一致（章节路径递增）。
      const left = a.cfi ?? ''
      const right = b.kind === 'epub' ? (b.cfi ?? '') : ''
      return left === right ? 0 : left < right ? -1 : 1
    }
    case 'text': {
      const offset = a.offset ?? -1
      const other = b.kind === 'text' ? (b.offset ?? -1) : -1
      if (offset !== other) return offset - other
      const length = a.length ?? -1
      const otherLength = b.kind === 'text' ? (b.length ?? -1) : -1
      return length - otherLength
    }
    case 'html': {
      const href = a.href ?? ''
      const otherHref = b.kind === 'html' ? (b.href ?? '') : ''
      if (href !== otherHref) return href < otherHref ? -1 : 1
      return (a.offset ?? -1) - (b.kind === 'html' ? (b.offset ?? -1) : -1)
    }
    case 'pdf': {
      const page = a.page ?? -1
      const otherPage = b.kind === 'pdf' ? (b.page ?? -1) : -1
      if (page !== otherPage) return page - otherPage
      const y = a.rect?.y ?? 0
      const otherY = b.kind === 'pdf' ? (b.rect?.y ?? 0) : 0
      if (y !== otherY) return y - otherY
      const x = a.rect?.x ?? 0
      const otherX = b.kind === 'pdf' ? (b.rect?.x ?? 0) : 0
      return x - otherX
    }
    case 'scroll':
      return (a.percent ?? 0) - (b.kind === 'scroll' ? (b.percent ?? 0) : 0)
    default:
      return 0
  }
}

/** 两个标注的排序比较（书 → 类型 → 位置 / 时间 → id）。 */
export function compareAnnotations(
  a: Annotation,
  b: Annotation,
  sort: AnnotationSort = 'position',
): number {
  if (a.bookId !== b.bookId) return a.bookId - b.bookId
  if (a.type !== b.type) {
    return ANNOTATION_TYPES.indexOf(a.type) - ANNOTATION_TYPES.indexOf(b.type)
  }
  if (sort === 'created') {
    const byCreated = (a.createdAt ?? '').localeCompare(b.createdAt ?? '')
    if (byCreated !== 0) return byCreated
  } else if (sort === 'updated') {
    const byUpdated = (a.updatedAt ?? '').localeCompare(b.updatedAt ?? '')
    if (byUpdated !== 0) return byUpdated
  } else {
    const byPosition = compareLocators(a.locator, b.locator)
    if (byPosition !== 0) return byPosition
  }
  return a.id - b.id
}

/** 排序副本（不改入参；Zustand state 里存的一直是已排序数组）。 */
export function sortAnnotations(
  annotations: readonly Annotation[],
  sort: AnnotationSort = 'position',
): Annotation[] {
  return [...annotations].sort((a, b) => compareAnnotations(a, b, sort))
}

// ── 过滤 / 分组 / 计数 ───────────────────────────────────────────────

export interface AnnotationFilterOptions {
  /** 只保留某本书；缺省 / <= 0 表示不按书过滤。 */
  bookId?: number
  /** 只保留这些类型；缺省表示全部。 */
  types?: readonly AnnotationType[]
  /** 关键词：匹配 title / note / 选区文本与前后文（大小写不敏感）。 */
  query?: string
}

/** 关键词匹配：中文直接 includes，英文 lower-case 后 includes。 */
export function matchesAnnotationQuery(annotation: Annotation, query: string): boolean {
  const needle = query.trim().toLowerCase()
  if (!needle) return true
  const locator = annotation.locator
  const haystack = [
    annotation.title ?? '',
    annotation.note ?? '',
    locator.quote ?? '',
    locator.contextBefore ?? '',
    locator.contextAfter ?? '',
  ]
    .join('\n')
    .toLowerCase()
  return haystack.includes(needle)
}

export function filterAnnotations(
  annotations: readonly Annotation[],
  options: AnnotationFilterOptions = {},
): Annotation[] {
  const bookId = options.bookId ?? 0
  const types = options.types
  const query = options.query ?? ''
  return annotations.filter((annotation) => {
    if (bookId > 0 && annotation.bookId !== bookId) return false
    if (types && types.length > 0 && !types.includes(annotation.type)) return false
    if (query && !matchesAnnotationQuery(annotation, query)) return false
    return true
  })
}

/** 按书分组（入参顺序保留，通常已是排序后的数组）。 */
export function groupByBook(annotations: readonly Annotation[]): Record<number, Annotation[]> {
  const grouped: Record<number, Annotation[]> = {}
  for (const annotation of annotations) {
    if (!grouped[annotation.bookId]) grouped[annotation.bookId] = []
    grouped[annotation.bookId].push(annotation)
  }
  return grouped
}

/** 各类型计数（侧栏 tab 上的数字）。 */
export function countByType(annotations: readonly Annotation[]): Record<AnnotationType, number> {
  const counts: Record<AnnotationType, number> = { bookmark: 0, highlight: 0, note: 0 }
  for (const annotation of annotations) {
    if (isAnnotationType(annotation.type)) counts[annotation.type] += 1
  }
  return counts
}

/**
 * annotation.id → 旧表行 id（legacyId）。
 * 并行双写期：侧栏仍读 readerStore 的 bookmarks/highlights/notes；要把两边
 * 同一行对上（例如在新侧栏里删除后同步旧 store）时用这张表，不要猜 id 相等。
 */
export function legacyIdMap(annotations: readonly Annotation[]): Record<number, number> {
  const map: Record<number, number> = {}
  for (const annotation of annotations) {
    if (typeof annotation.legacyId === 'number' && annotation.legacyId > 0) {
      map[annotation.id] = annotation.legacyId
    }
  }
  return map
}

/** 找到某个 id 的标注（找不到返回 undefined）。 */
export function findAnnotation(
  annotations: readonly Annotation[],
  id: number,
): Annotation | undefined {
  return annotations.find((annotation) => annotation.id === id)
}
