// ── PB-021-E：adapter 的标注读写（annotations bridge + 旧表刷新）────
//
// 统一表是 annotations；C++ 写入会双写到 bookmarks/highlights/notes。
// 现有渲染器的高亮渲染仍读 readerStore 的旧列表，所以 adapter 写完要重拉
// 三个旧列表 —— 这样"用 adapter 加高亮"能立刻在正文里看到（EPUB 由
// EpubRenderer 的 rendition.annotations 应用，TXT/HTML 由各自渲染器应用）。

import { useAnnotationStore } from '../stores/annotationStore'
import { useReaderStore } from '../stores/readerStore'
import { locatorKey } from '../types/annotation'
import type {
  ReaderApplyAnnotationRequest,
  ReaderLocator,
  ReaderRemoveAnnotationTarget,
} from './types'

/** 重拉旧表列表（书签 / 高亮 / 笔记），让现有渲染器立即看到 adapter 的写入。 */
export async function refreshLegacyAnnotations(bookId: number): Promise<void> {
  const reader = useReaderStore.getState()
  try {
    await Promise.all([
      reader.loadBookmarks(bookId),
      reader.loadHighlights(bookId),
      reader.loadNotes(bookId),
    ])
  } catch (error) {
    console.error('ReaderAdapter: failed to refresh legacy annotations:', error)
  }
}

/**
 * 新建标注：locator 优先用 request.locator，其次 fallback（选区 / 当前进度）。
 * 高亮必须有引用文本（选区文本或 locator.quote），否则返回 null。
 */
export async function applyAnnotationViaBridge(
  bookId: number,
  request: ReaderApplyAnnotationRequest,
  fallback: ReaderLocator | null,
): Promise<PbAnnotation | null> {
  const locator: ReaderLocator | null = request.locator ?? fallback
  if (!locator) return null

  const quote = (request.text ?? '').trim() || (locator.quote ?? '').trim()
  if (request.type === 'highlight' && !quote) return null

  const payload: PbAnnotationInput = {
    bookId,
    type: request.type,
    locator: { ...locator },
  }
  if (quote) payload.locator.quote = quote
  if (request.color) payload.color = request.color
  if (request.note) payload.note = request.note
  if (request.title) payload.title = request.title

  const created = await useAnnotationStore.getState().add(payload)
  if (created) await refreshLegacyAnnotations(bookId)
  return created
}

/** 删除标注：按 id，或按 locator 在已加载/懒加载的当前书标注里找同位置的一条。 */
export async function removeAnnotationViaBridge(
  bookId: number,
  target: ReaderRemoveAnnotationTarget,
): Promise<boolean> {
  let id = typeof target === 'number' ? target : target.annotationId
  if (typeof target === 'object' && target && !id && target.locator) {
    const store = useAnnotationStore.getState()
    if (!store.loadedAll && !store.loadedBooks.includes(bookId)) {
      await store.load(bookId)
    }
    const key = locatorKey(target.locator)
    const match = useAnnotationStore
      .getState()
      .annotations.find((annotation) => annotation.bookId === bookId && locatorKey(annotation.locator) === key)
    id = match && match.id
  }
  if (!id) return false
  const removed = await useAnnotationStore.getState().remove(id)
  if (removed) await refreshLegacyAnnotations(bookId)
  return removed
}
