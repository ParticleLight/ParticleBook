// ── PB-021-E：ReaderAdapter 的 React 上下文 ──────────────────────────
// ReaderView 在书加载后创建 adapter 并用 Provider 包住整棵阅读器树；
// 子组件（以及后续 TTS / 翻译 / 词典重构）用 useReaderAdapter() 取当前 adapter。
// 目前 TtsBar / TranslationPopup / DictionaryPopup 仍各自读选区（不在
// PB-021-E 的 write scope 内），它们改成 adapter 只需替换内部 getSelection。

import { createContext, useContext, type ReactNode } from 'react'
import type { ReaderAdapter } from './types'

const ReaderAdapterContext = createContext<ReaderAdapter | null>(null)

export function ReaderAdapterProvider({
  adapter,
  children,
}: {
  adapter: ReaderAdapter | null
  children: ReactNode
}) {
  return <ReaderAdapterContext.Provider value={adapter}>{children}</ReaderAdapterContext.Provider>
}

export function useReaderAdapter(): ReaderAdapter | null {
  return useContext(ReaderAdapterContext)
}
