// ═══════════════════════════════════════════════════════════════════════
// 桥接契约的【唯一权威来源】—— bridge/contract.mjs
//
// electron.d.ts 由 scripts/gen-bridge-dts.mjs 从本表生成，请勿手改生成物。
// 改契约 = 改本表，然后 npm run gen:bridge-dts。
//
// 字段说明：
//   member  前端 window.electronAPI 上的成员名（也是调用点用的名字）
//   method  C++ 桥接方法名（invoke 的目标；事件类成员是事件名）
//   kind    invoke 请求响应 / event 事件订阅 / local 纯前端工具（不经桥接）
//   params  参数，n 名字、t 类型（字面 TS 字符串）、o 是否可选
//   returns 返回类型（字面 TS 字符串，含 Promise<>）
//
// 类型刻意写成字面 TS 字符串：生成器不做类型解析，因此无需 parser、
// 也不存在解析歧义。C++ 侧不携带类型信息，故类型靠人工维护于此。
// ═══════════════════════════════════════════════════════════════════════

export const groups = [
  "Files",
  "PDF",
  "Books",
  "Bookmarks",
  "Highlights",
  "Notes",
  "Settings",
  "Bookshelves",
  "Utilities",
  "Book Sources",
  "Z-Library",
  "Reading Sessions",
  "Menu events",
  "Auto Updater"
]

// ── 命名类型 ────────────────────────────────────────────────────────────
// 均由 C++ 侧的实际拼装推导（刻意不复用渲染层同名接口，那是另一份手工副本）：
//   PbBook        book:import 构造的对象 + InsertBook 追加 id/added_at
//                 + UpdateBookLastOpened 追加 last_opened。
//                 字段为空时 C++ 写 null（不是省略），故为 string | null。
//   PbBookmark    前端传入原样存储，C++ 仅追加 id 与 created_at（缺则补）
//   PbHighlight   同上
//   PbNote        同上，另强制刷新 updated_at
//   PbBookshelf   C++ 全量构造 { id, name, created_at }
//   PbProgress*   写入用 camelCase scrollPosition，C++ 落库为 scroll_position；
//                 读取在无记录时返回【空对象】而非 null，故读取类型全字段可选
export const types = [
  {
    name: 'PbBook',
    body: [
      '  id: number',
      '  title: string',
      '  author: string | null',
      '  format: string',
      '  file_path: string',
      '  file_size: number',
      '  description: string | null',
      '  publisher: string | null',
      '  cover_path: string | null',
      '  language: string | null',
      '  isbn: string | null',
      '  added_at: string',
      '  last_opened?: string'
    ]
  },
  {
    name: 'PbBookmarkInput',
    body: [
      '  book_id: number',
      '  cfi?: string',
      '  page?: number',
      '  progress?: number',
      '  title?: string',
      '  note?: string'
    ]
  },
  { name: 'PbBookmark', extends: 'PbBookmarkInput', body: ['  id: number', '  created_at: string'] },
  {
    name: 'PbHighlightInput',
    body: [
      '  book_id: number',
      '  cfi?: string',
      '  page?: number',
      '  text: string',
      '  color: string',
      '  note?: string'
    ]
  },
  { name: 'PbHighlight', extends: 'PbHighlightInput', body: ['  id: number', '  created_at: string'] },
  {
    name: 'PbNoteInput',
    body: [
      '  book_id: number',
      '  highlight_id?: number',
      '  cfi?: string',
      '  page?: number',
      '  content: string'
    ]
  },
  { name: 'PbNote', extends: 'PbNoteInput', body: ['  id: number', '  created_at: string', '  updated_at: string'] },
  {
    name: 'PbBookshelf',
    body: ['  id: number', '  name: string', '  created_at: string']
  },
  {
    name: 'PbProgressInput',
    body: ['  progress: number', '  cfi?: string', '  page?: number', '  scrollPosition?: number']
  },
  {
    name: 'PbProgressRecord',
    body: [
      '  book_id?: number',
      '  progress?: number',
      '  cfi?: string',
      '  page?: number',
      '  scroll_position?: number',
      '  updated_at?: string'
    ]
  }
  ,
  {
    name: 'PbSettings',
    body: [
      '  // 键与渲染层 SETTINGS_KEYS 一致；数据库初值为空对象，故全部可选。',
      "  theme?: 'light' | 'dark' | 'sepia'",
      "  accentColor?: 'blue' | 'purple' | 'green' | 'orange'",
      '  fontSize?: number',
      '  fontFamily?: string',
      '  lineHeight?: number',
      '  margin?: number',
      "  textAlign?: 'left' | 'justify'",
      '  autoSaveProgress?: boolean',
      '  showReadingTime?: boolean',
      "  defaultViewMode?: 'grid' | 'list'",
      "  defaultSortBy?: 'title' | 'author' | 'added_at' | 'last_opened'",
      "  language?: 'zh' | 'en'"
    ]
  },
  {
    name: 'PbBookSourceInput',
    body: [
      '  // Legado 书源格式：应用只解释这三个字段，其余规则字段原样存取，',
      '  // 保留字符串索引签名而不是假装穷举了外部规范。',
      '  bookSourceName: string',
      '  bookSourceUrl: string',
      '  enabled?: boolean',
      '  [key: string]: unknown'
    ]
  },
  { name: 'PbBookSource', extends: 'PbBookSourceInput', body: ['  id: number', '  added_at?: string'] },
  {
    name: 'PbSourceSearchResult',
    body: [
      '  // bookName 必需：SearchOne 只推送 nameRule 命中且非空的条目',
      '  // （见 BookSourceService::SearchOne 的 push 守卫）；其余字段取决于规则是否命中。',
      '  bookName: string',
      '  author?: string',
      '  bookUrl?: string',
      '  coverUrl?: string'
    ]
  },
  {
    name: 'PbSearchResult',
    extends: 'PbSourceSearchResult',
    body: [
      '  // SearchAll 在每条结果上补的来源信息（跨源搜索时用于区分与下载）。',
      '  sourceId: number',
      '  sourceName: string'
    ]
  },
  {
    name: 'PbBookInfo',
    body: [
      '  bookUrl: string',
      '  bookName?: string',
      '  author?: string',
      '  coverUrl?: string',
      '  intro?: string',
      '  tocUrl?: string'
    ]
  },
  { name: 'PbChapter', body: ['  name: string', '  url: string'] },
  {
    name: 'PbDownloadProgress',
    body: [
      '  // 阶段序列：fetching_toc -> downloading（带 chapterName）-> assembling',
      '  //          -> importing -> done | error。done/error 带 bookId；',
      '  // error 时 error 为机器可读错误码，由 UI 本地化：source_not_found /',
      '  // no_chapters / empty_content / write_failed / import_failed。',
      "  status: 'fetching_toc' | 'downloading' | 'assembling' | 'importing' | 'done' | 'error'",
      '  current: number',
      '  total: number',
      '  chapterName?: string',
      '  bookId?: number',
      '  error?: string'
    ]
  },
  {
    name: 'PbUpdateInfo',
    body: [
      '  version: string',
      '  fileName: string',
      '  downloadUrl: string',
      '  size: number',
      '  sha512: string'
    ]
  },
  {
    name: 'PbBookMetadata',
    body: [
      '  // 未知格式时 C++ 返回空对象，故全部可选。',
      '  title?: string',
      '  author?: string',
      '  language?: string',
      '  format?: string'
    ]
  },
  { name: 'PbPdfText', body: ['  pages: { pageNum: number; text: string }[]'] },
  { name: 'PbZlibDownloadProgress', body: ['  fileName: string', '  received: number', '  total: number'] },
  { name: 'PbZlibDownloadComplete', body: ['  fileName: string', '  path: string'] },
  { name: 'PbZlibImportComplete', body: ['  fileName: string'] },
  { name: 'PbZlibImportError', body: ['  fileName: string', '  error: string'] },
  {
    name: 'PbZlibDownloadError',
    body: [
      '  fileName: string',
      '  // 机器可读错误码，由 UI 本地化：invalid_url / http_open_failed / connect_failed /',
      '  // request_failed / network_error / http_<状态码> / file_create_failed /',
      '  // empty_response / too_many_redirects',
      '  error: string'
    ]
  },
  { name: 'PbUpdateDownloaded', body: ['  success: boolean', '  path: string'] },
  { name: 'PbUpdateError', body: ['  error: string'] }
]

export const contract = [
  // ── Files ──
  {
    member: "openFile", method: "dialog:openFile",
    kind: "invoke",
    group: "Files",
    params: [],
    returns: "Promise<string | null>"
  },
  {
    member: "readFile", method: "file:read",
    kind: "invoke",
    group: "Files",
    params: [{ n: "filePath", t: "string" }],
    // C++ 在文件不可读时返回 json(nullptr)（FileHandlers.cpp 的 wlen<=0 与
    // INVALID_HANDLE_VALUE 两条路径）—— 类型必须包含 null，否则调用方会把
    // null 当成"空文件"而不是失败。
    returns: "Promise<PbFileContent>"
  },
  {
    member: "importBooks", method: "book:import",
    kind: "invoke",
    group: "Files",
    params: [{ n: "filePaths", t: "string[]" }],
    returns: "Promise<PbBook[]>"
  },
  {
    member: "writeDroppedFile", method: "book:writeDroppedFile",
    kind: "invoke",
    group: "Files",
    params: [{ n: "name", t: "string" }, { n: "dataB64", t: "string" }],
    returns: "Promise<{ path: string; size: number } | null>"
  },
  {
    member: "getCoverImage", method: "book:cover",
    kind: "invoke",
    group: "Files",
    params: [{ n: "bookId", t: "number" }],
    returns: "Promise<string | null>"
  },
  // ── PDF ──
  {
    member: "pdfOpen", method: "pdf:open",
    kind: "invoke",
    group: "PDF",
    params: [{ n: "filePath", t: "string" }],
    returns: "Promise<{ id: number; pageCount: number; pageBounds: { width: number; height: number }[] } | null>"
  },
  {
    member: "pdfRenderPage", method: "pdf:renderPage",
    kind: "invoke",
    group: "PDF",
    params: [{ n: "id", t: "number" }, { n: "pageNum", t: "number" }, { n: "width", t: "number" }, { n: "height", t: "number" }],
    returns: "Promise<string | null>"
  },
  {
    member: "pdfExtractText", method: "pdf:extractText",
    kind: "invoke",
    group: "PDF",
    params: [{ n: "id", t: "number" }],
    returns: "Promise<PbPdfText | null>"
  },
  {
    member: "pdfClose", method: "pdf:close",
    kind: "invoke",
    group: "PDF",
    params: [{ n: "id", t: "number" }],
    returns: "Promise<void>"
  },
  // ── Books ──
  {
    member: "getBooks", method: "db:getBooks",
    kind: "invoke",
    group: "Books",
    params: [],
    returns: "Promise<PbBook[]>"
  },
  {
    member: "getBook", method: "db:getBook",
    kind: "invoke",
    group: "Books",
    params: [{ n: "id", t: "number" }],
    returns: "Promise<PbBook | null>"
  },
  {
    member: "deleteBook", method: "db:deleteBook",
    kind: "invoke",
    group: "Books",
    params: [{ n: "id", t: "number" }],
    returns: "Promise<void>"
  },
  {
    member: "updateReadingProgress", method: "db:updateProgress",
    kind: "invoke",
    group: "Books",
    params: [{ n: "bookId", t: "number" }, { n: "progress", t: "PbProgressInput" }],
    returns: "Promise<void>"
  },
  {
    member: "getReadingProgress", method: "db:getProgress",
    kind: "invoke",
    group: "Books",
    params: [{ n: "bookId", t: "number" }],
    returns: "Promise<PbProgressRecord>"
  },
  // ── Bookmarks ──
  {
    member: "getBookmarks", method: "db:getBookmarks",
    kind: "invoke",
    group: "Bookmarks",
    params: [{ n: "bookId", t: "number" }],
    returns: "Promise<PbBookmark[]>"
  },
  {
    member: "addBookmark", method: "db:addBookmark",
    kind: "invoke",
    group: "Bookmarks",
    params: [{ n: "bookmark", t: "PbBookmarkInput" }],
    returns: "Promise<void>"
  },
  {
    member: "deleteBookmark", method: "db:deleteBookmark",
    kind: "invoke",
    group: "Bookmarks",
    params: [{ n: "id", t: "number" }],
    returns: "Promise<void>"
  },
  {
    member: "updateBookmarkTitle", method: "db:updateBookmarkTitle",
    kind: "invoke",
    group: "Bookmarks",
    params: [{ n: "id", t: "number" }, { n: "title", t: "string" }],
    returns: "Promise<void>"
  },
  // ── Highlights ──
  {
    member: "getHighlights", method: "db:getHighlights",
    kind: "invoke",
    group: "Highlights",
    params: [{ n: "bookId", t: "number" }],
    returns: "Promise<PbHighlight[]>"
  },
  {
    member: "addHighlight", method: "db:addHighlight",
    kind: "invoke",
    group: "Highlights",
    params: [{ n: "highlight", t: "PbHighlightInput" }],
    returns: "Promise<void>"
  },
  {
    member: "deleteHighlight", method: "db:deleteHighlight",
    kind: "invoke",
    group: "Highlights",
    params: [{ n: "id", t: "number" }],
    returns: "Promise<void>"
  },
  // ── Notes ──
  {
    member: "getNotes", method: "db:getNotes",
    kind: "invoke",
    group: "Notes",
    params: [{ n: "bookId", t: "number" }],
    returns: "Promise<PbNote[]>"
  },
  {
    member: "addNote", method: "db:addNote",
    kind: "invoke",
    group: "Notes",
    params: [{ n: "note", t: "PbNoteInput" }],
    returns: "Promise<void>"
  },
  {
    member: "updateNote", method: "db:updateNote",
    kind: "invoke",
    group: "Notes",
    params: [{ n: "id", t: "number" }, { n: "content", t: "string" }],
    returns: "Promise<void>"
  },
  {
    member: "deleteNote", method: "db:deleteNote",
    kind: "invoke",
    group: "Notes",
    params: [{ n: "id", t: "number" }],
    returns: "Promise<void>"
  },
  // ── Settings ──
  {
    member: "getSettings", method: "db:getSettings",
    kind: "invoke",
    group: "Settings",
    params: [],
    returns: "Promise<PbSettings>"
  },
  {
    member: "updateSettings", method: "db:updateSettings",
    kind: "invoke",
    group: "Settings",
    params: [{ n: "settings", t: "PbSettings" }],
    returns: "Promise<void>"
  },
  {
    member: "setLanguage", method: "app:setLanguage",
    kind: "invoke",
    group: "Settings",
    params: [{ n: "lang", t: "string" }],
    returns: "Promise<boolean>"
  },
  {
    member: "getBookSettings", method: "db:getBookSettings",
    kind: "invoke",
    group: "Settings",
    params: [{ n: "bookId", t: "number" }],
    returns: "Promise<PbSettings>"
  },
  {
    member: "updateBookSettings", method: "db:updateBookSettings",
    kind: "invoke",
    group: "Settings",
    params: [{ n: "bookId", t: "number" }, { n: "settings", t: "PbSettings" }],
    returns: "Promise<void>"
  },
  // ── Bookshelves ──
  {
    member: "getBookshelves", method: "db:getBookshelves",
    kind: "invoke",
    group: "Bookshelves",
    params: [],
    returns: "Promise<PbBookshelf[]>"
  },
  {
    member: "addBookshelf", method: "db:addBookshelf",
    kind: "invoke",
    group: "Bookshelves",
    params: [{ n: "name", t: "string" }],
    returns: "Promise<PbBookshelf>"
  },
  {
    member: "deleteBookshelf", method: "db:deleteBookshelf",
    kind: "invoke",
    group: "Bookshelves",
    params: [{ n: "id", t: "number" }],
    returns: "Promise<void>"
  },
  {
    member: "renameBookshelf", method: "db:renameBookshelf",
    kind: "invoke",
    group: "Bookshelves",
    params: [{ n: "id", t: "number" }, { n: "name", t: "string" }],
    returns: "Promise<void>"
  },
  {
    member: "getBooksInShelf", method: "db:getBooksInShelf",
    kind: "invoke",
    group: "Bookshelves",
    params: [{ n: "shelfId", t: "number" }],
    returns: "Promise<number[]>"
  },
  {
    member: "addBookToShelf", method: "db:addBookToShelf",
    kind: "invoke",
    group: "Bookshelves",
    params: [{ n: "shelfId", t: "number" }, { n: "bookId", t: "number" }],
    returns: "Promise<void>"
  },
  {
    member: "removeBookFromShelf", method: "db:removeBookFromShelf",
    kind: "invoke",
    group: "Bookshelves",
    params: [{ n: "shelfId", t: "number" }, { n: "bookId", t: "number" }],
    returns: "Promise<void>"
  },
  // ── Utilities ──
  {
    member: "getFilePath", method: null,
    kind: "local",
    group: "Utilities",
    params: [{ n: "file", t: "File" }],
    returns: "string"
  },
  // ── Book Sources ──
  {
    member: "getBookSources", method: "bookSource:getAll",
    kind: "invoke",
    group: "Book Sources",
    params: [],
    returns: "Promise<PbBookSource[]>"
  },
  {
    // 以下 6 个书源细分 API（getBookSource / insertBookSource / updateBookSource /
    // searchBooksFromSource / getBookInfoFromSource / getChapterListFromSource）目前
    // 【无渲染层消费者】：导入走 C++ 侧文件对话框 bookSource:importFile，下载在
    // BookSourceService::DownloadBook 内部自行取 info 与章节。经确认它们是为将来的
    // 「书源编辑器 / 单源搜索」预留的接口，故保留；若长期不用应删除以免死接口面扩大
    // （scripts/audit-bridge-usage.mjs 会持续报告）。
    member: "getBookSource", method: "bookSource:get",
    kind: "invoke",
    reserved: true,
    group: "Book Sources",
    params: [{ n: "id", t: "number" }],
    returns: "Promise<PbBookSource | null>"
  },
  {
    member: "insertBookSource", method: "bookSource:insert",
    kind: "invoke",
    reserved: true,
    group: "Book Sources",
    params: [{ n: "source", t: "PbBookSourceInput" }],
    returns: "Promise<PbBookSource>"
  },
  {
    member: "updateBookSource", method: "bookSource:update",
    kind: "invoke",
    reserved: true,
    group: "Book Sources",
    params: [{ n: "id", t: "number" }, { n: "updates", t: "Partial<PbBookSourceInput>" }],
    returns: "Promise<void>"
  },
  {
    member: "deleteBookSource", method: "bookSource:delete",
    kind: "invoke",
    group: "Book Sources",
    params: [{ n: "id", t: "number" }],
    returns: "Promise<void>"
  },
  {
    member: "toggleBookSource", method: "bookSource:toggle",
    kind: "invoke",
    group: "Book Sources",
    params: [{ n: "id", t: "number" }],
    returns: "Promise<void>"
  },
  {
    member: "clearAllBookSources", method: "bookSource:clearAll",
    kind: "invoke",
    group: "Book Sources",
    params: [],
    returns: "Promise<void>"
  },
  {
    member: "importBookSources", method: "bookSource:importFile",
    kind: "invoke",
    group: "Book Sources",
    params: [],
    // C++ 只返回 { imported }（FileHandlers.cpp 导入处），取消对话框或异常时返回
    // json(nullptr) —— 此前声明成 { imported, total } 让前端读 result.total（恒
    // undefined）导致成功提示永不出现、取消时读 null.total 直接抛 TypeError。
    returns: "Promise<{ imported: number } | null>"
  },
  {
    member: "searchBooks", method: "bookSource:search",
    kind: "invoke",
    group: "Book Sources",
    params: [{ n: "keyword", t: "string" }, { n: "page", t: "number", o: true }],
    returns: "Promise<PbSearchResult[]>"
  },
  {
    member: "searchBooksFromSource", method: "bookSource:searchOne",
    kind: "invoke",
    reserved: true,
    group: "Book Sources",
    params: [{ n: "sourceId", t: "number" }, { n: "keyword", t: "string" }, { n: "page", t: "number", o: true }],
    returns: "Promise<PbSourceSearchResult[]>"
  },
  {
    member: "getBookInfoFromSource", method: "bookSource:getBookInfo",
    kind: "invoke",
    reserved: true,
    group: "Book Sources",
    params: [{ n: "sourceId", t: "number" }, { n: "bookUrl", t: "string" }],
    returns: "Promise<PbBookInfo>"
  },
  {
    member: "getChapterListFromSource", method: "bookSource:getChapterList",
    kind: "invoke",
    reserved: true,
    group: "Book Sources",
    params: [{ n: "sourceId", t: "number" }, { n: "tocUrl", t: "string" }],
    returns: "Promise<PbChapter[]>"
  },
  {
    member: "downloadBook", method: "bookSource:download",
    kind: "invoke",
    group: "Book Sources",
    params: [{ n: "sourceId", t: "number" }, { n: "bookUrl", t: "string" }, { n: "bookName", t: "string" }, { n: "format", t: "string" }],
    // C++ 返回字符串 "started"（下载在后台线程跑，结果经 downloadProgress 事件回报）。
    // 此前声明为 Promise<number> 属类型错误；渲染层不使用其返回值。
    returns: "Promise<string>"
  },
  {
    member: "onDownloadProgress", method: "bookSource:downloadProgress",
    kind: "event",
    group: "Book Sources",
    params: [{ n: "callback", t: "(progress: PbDownloadProgress) => void" }],
    returns: "() => void"
  },
  // ── Z-Library ──
  {
    member: "zlibShow", method: "zlib:show",
    kind: "invoke",
    group: "Z-Library",
    params: [],
    returns: "Promise<void>"
  },
  {
    member: "zlibGetMirrorInfo", method: "zlib:getMirrorInfo",
    kind: "invoke",
    group: "Z-Library",
    params: [],
    returns: "Promise<{ index: number; url: string; mirrors: string[] }>"
  },
  {
    member: "zlibGetDownloadPath", method: "zlib:getDownloadPath",
    kind: "invoke",
    group: "Z-Library",
    params: [],
    returns: "Promise<{ path: string }>"
  },
  {
    member: "zlibPickDownloadFolder", method: "zlib:pickDownloadFolder",
    kind: "invoke",
    group: "Z-Library",
    params: [],
    returns: "Promise<{ path: string } | null>"
  },
  {
    member: "onZlibMirrorChanged", method: "zlib:mirrorChanged",
    kind: "event",
    group: "Z-Library",
    params: [{ n: "callback", t: "(info: { index: number; url: string; mirrors: string[] }) => void" }],
    returns: "() => void"
  },
  {
    member: "onZlibAllMirrorsFailed", method: "zlib:allMirrorsFailed",
    kind: "event",
    group: "Z-Library",
    params: [{ n: "callback", t: "() => void" }],
    returns: "() => void"
  },
  {
    // 此前 C++ 的 9 条下载失败路径都会发出 zlib:downloadError，但存根里没有可订阅的
    // 成员，用户下载失败时毫无提示。补上这个订阅入口（App.tsx 用它显示失败原因）。
    member: "onZlibDownloadError", method: "zlib:downloadError",
    kind: "event",
    group: "Z-Library",
    params: [{ n: "callback", t: "(data: PbZlibDownloadError) => void" }],
    returns: "() => void"
  },
  // ── Reading Sessions ──
  {
    member: "startReadingSession", method: "db:startReadingSession",
    kind: "invoke",
    group: "Reading Sessions",
    params: [{ n: "bookId", t: "number" }],
    returns: "Promise<number>"
  },
  {
    member: "endReadingSession", method: "db:endReadingSession",
    kind: "invoke",
    group: "Reading Sessions",
    params: [{ n: "sessionId", t: "number" }],
    returns: "Promise<void>"
  },
  {
    member: "updateReadingSessionDuration", method: "db:updateReadingSessionDuration",
    kind: "invoke",
    group: "Reading Sessions",
    params: [{ n: "sessionId", t: "number" }, { n: "durationSeconds", t: "number" }],
    returns: "Promise<void>"
  },
  {
    member: "getAllReadingTime", method: "db:getAllReadingTime",
    kind: "invoke",
    group: "Reading Sessions",
    params: [],
    returns: "Promise<Record<number, number>>"
  },
  {
    member: "getAllReadingProgress", method: "db:getAllReadingProgress",
    kind: "invoke",
    group: "Reading Sessions",
    params: [],
    returns: "Promise<Record<number, { progress: number; page?: number; updated_at: string }>>"
  },
  // ── Menu events ──
  {
    // 书库已变化（C++ 内部导入后发出：书源下载、Z-Library 下载自动入库）。
    // 取代了原先语义错位的 menu:importBooks —— 那个事件的消费者把它当"导入这些
    // 路径"，而 C++ 传来的是空对象；且本应用并没有原生菜单。
    member: "onLibraryChanged", method: "library:changed",
    kind: "event",
    group: "Library",
    params: [{ n: "callback", t: "() => void" }],
    returns: "() => void"
  },
  // ── Auto Updater ──
  {
    member: "checkUpdate", method: "app:checkUpdate",
    kind: "invoke",
    group: "Auto Updater",
    params: [],
    returns: "Promise<PbUpdateInfo | null>"
  },
  {
    member: "getAppVersion", method: "app:getVersion",
    kind: "invoke",
    group: "Auto Updater",
    params: [],
    returns: "Promise<string>"
  },
  {
    member: "downloadUpdate", method: "app:downloadUpdate",
    kind: "invoke",
    group: "Auto Updater",
    params: [{ n: "url", t: "string" }, { n: "sha512", t: "string", o: true }],
    returns: "Promise<boolean | null>"
  },
  {
    member: "quitAndInstall", method: "app:quitAndInstall",
    kind: "invoke",
    group: "Auto Updater",
    params: [],
    returns: "Promise<boolean>"
  },
  {
    member: "onUpdateChecked", method: "app:updateChecked",
    kind: "event",
    group: "Auto Updater",
    params: [{ n: "callback", t: "(info: PbUpdateInfo | null) => void" }],
    returns: "() => void"
  },
  {
    member: "onUpdateDownloaded", method: "app:updateDownloaded",
    kind: "event",
    group: "Auto Updater",
    params: [{ n: "callback", t: "() => void" }],
    returns: "() => void"
  },
  {
    member: "onUpdateError", method: "app:updateError",
    kind: "event",
    group: "Auto Updater",
    params: [{ n: "callback", t: "(message: string) => void" }],
    returns: "() => void"
  },
  {
    member: "onUpdateDownloadProgress", method: "app:downloadProgress",
    kind: "event",
    group: "Auto Updater",
    params: [{ n: "callback", t: "(progress: { percent: number }) => void" }],
    returns: "() => void"
  },
]
