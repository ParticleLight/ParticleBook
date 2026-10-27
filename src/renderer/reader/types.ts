// ── PB-021-E：ReaderAdapter —— 渲染器统一的"阅读能力"接口 ─────────────
//
// 背景（docs/ARCHITECTURE_REVIEW.md P1-1 / docs/BACKLOG.md PB-022）：
//   五个渲染器各自实现进度、搜索、选区、高亮，TTS / 翻译 / 词典 / 导出
//   只能按格式分支。本目录提供一层 adapter：对上层暴露 getLocator / goTo /
//   getSelection / getTextModel / search / applyAnnotation / removeAnnotation，
//   渲染内核一行不改 —— adapter 通过 DOM + readerStore + annotations bridge
//   工作（EPUB 的 epub.js iframe 也能读到选区文本，但拿不到 CFI，见 epubAdapter）。
//
// 约定：
//   * Locator 直接复用桥接契约的 PbLocator（与 domain/Locator.h 同形状）；
//   * getSelection 返回 { text, locator?, rect? }，text 已折叠空白，locator 在
//     拿不到可靠锚点时省略（EPUB 选区就是这种情况，只能给 text）；
//   * applyAnnotation / removeAnnotation 走 annotations bridge（C++ 双写旧表，
//     随后刷新 readerStore 的旧列表，现有渲染器的高亮渲染立即生效）；
//   * 所有方法同步/异步皆可，调用方按 Promise 处理（goTo 同步返回是否受理）。

import type { AnnotationType } from '../types/annotation'

/** 书内位置：与 bridge 生成的 PbLocator 同构。 */
export type ReaderLocator = PbLocator

/** 当前选区（text 必有；locator 取不到时为 null/undefined）。 */
export interface ReaderSelection {
  text: string
  locator?: ReaderLocator | null
  /** 选区在窗口坐标里的包围盒（浮层定位用）。 */
  rect?: { x: number; y: number; width: number; height: number }
  /** PB-002-B：选区前后文窗口（同章节/同段落 ±300 字，折叠空白）。 */
  context?: string
}

/** 纯文本模型：TXT/HTML 是全书；EPUB 仅是当前章节（iframe 内容）。 */
export interface ReaderTextModel {
  text: string
  /** 码点数（与 Locator.offset 同口径）。 */
  length: number
  scope: 'document' | 'chapter'
  source: 'dom' | 'iframe'
}

export interface ReaderSearchMatch {
  /** 命中起点（码点，与 text model 同口径）。 */
  start: number
  /** 命中长度（码点）。 */
  length: number
  snippet: string
  /** 可用于跳转的 locator（EPUB 当前章节拿不到可靠锚点时为 null）。 */
  locator?: ReaderLocator | null
}

export interface ReaderApplyAnnotationRequest {
  type: AnnotationType
  color?: string
  note?: string
  title?: string
  /** 显式位置；缺省时用当前选区 / 当前阅读进度推导。 */
  locator?: ReaderLocator | null
  /** 高亮/书签的引用文本；缺省时取当前选区文本。 */
  text?: string
}

export type ReaderRemoveAnnotationTarget =
  | number
  | {
      annotationId?: number
      locator?: ReaderLocator | null
    }

export interface ReaderAdapter {
  readonly format: string
  readonly bookId: number

  /** 当前阅读位置（EPUB=cfi；PDF/CBZ=page；其余=滚动百分比）。 */
  getLocator(): ReaderLocator | null
  /** 跳转到 locator（cfi / text offset / page / 滚动百分比）。返回是否受理。 */
  goTo(locator: ReaderLocator): boolean
  /** 当前选区；没有选区返回 null。 */
  getSelection(): ReaderSelection | null
  /** 当前可读文本模型（用于 TTS / 搜索 / 导出 / 索引）。 */
  getTextModel(): ReaderTextModel | null
  /** 当前文本模型内搜索（数据层；不负责画高亮，视觉搜索仍由渲染器负责）。 */
  search(query: string, options?: { limit?: number }): ReaderSearchMatch[]
  /** 新建标注（高亮 / 书签 / 笔记），返回落库行；前置条件不足返回 null。 */
  applyAnnotation(request: ReaderApplyAnnotationRequest): Promise<PbAnnotation | null>
  /** 删除标注（按 id，或按位置找最近的一条）。 */
  removeAnnotation(target: ReaderRemoveAnnotationTarget): Promise<boolean>
}

declare global {
  interface Window {
    /** ReaderView 挂载时暴露当前 adapter（调试 / E2E 用；卸载时清空）。 */
    __pbReaderAdapter?: ReaderAdapter | null
  }
}

// ── PB-022：共享 UI 的请求形状（ReaderView → 弹层）────────────────────
// SelectionToolbar 是选区的唯一入口：翻译/词典/朗读/高亮都由它触发，
// 弹层不再各自监听选区。请求带自增 id，弹层用 id 判断"新请求"。
export interface TranslationRequest {
  id: number
  text: string
  rect?: { x: number; y: number; width: number; height: number }
  /** PB-002-B：选区前后文（长句 / 代词消歧；TranslationService 的 user.context）。 */
  context?: string
}

export interface DictionaryRequest {
  id: number
  text: string
}
