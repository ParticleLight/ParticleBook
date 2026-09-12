// 这些形状的唯一定义在桥接契约里（bridge/contract.mjs -> 生成 electron.d.ts），
// 此处只做别名，避免出现会漂移的第二份手工副本。
//
// 历史教训：此处曾声明 SearchResult.name: string，而 C++ 实际发送的是 bookName。
// 类型谎言使搜索结果的书名一直显示为空、下载时把 undefined 当作书名传给后端。
// 现在类型与 C++ 对齐，同类错误会直接被 tsc 拦下。
export type SearchResult = PbSearchResult
export type DownloadProgress = PbDownloadProgress
export type BookSourceInfo = PbBookSource
