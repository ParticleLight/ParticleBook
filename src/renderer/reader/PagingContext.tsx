import { createContext, useContext, type ReactNode } from 'react'
import type { PagingApi } from './paging'

// ReaderView 挂 PagingProvider，渲染器用 usePaging() 注册自己的翻页器；
// 统一的输入层（键盘 / 点击区域 / 滚轮 / 箭头）只经过 PagingApi。
const PagingContext = createContext<PagingApi | null>(null)

export function PagingProvider({ api, children }: { api: PagingApi; children: ReactNode }) {
  return <PagingContext.Provider value={api}>{children}</PagingContext.Provider>
}

export function usePaging(): PagingApi | null {
  return useContext(PagingContext)
}
