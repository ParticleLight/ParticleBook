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
    member: "openDirectory", method: "dialog:openDirectory",
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
    returns: "Promise<Uint8Array | number[] | { _pb_url: string }>"
  },
  {
    member: "getBookMetadata", method: "book:metadata",
    kind: "invoke",
    group: "Files",
    params: [{ n: "filePath", t: "string" }],
    returns: "Promise<any>"
  },
  {
    member: "importBooks", method: "book:import",
    kind: "invoke",
    group: "Files",
    params: [{ n: "filePaths", t: "string[]" }],
    returns: "Promise<any[]>"
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
    member: "pdfGetFileUrl", method: "pdf:getFileUrl",
    kind: "invoke",
    group: "PDF",
    params: [{ n: "filePath", t: "string" }],
    returns: "Promise<string | null>"
  },
  {
    member: "pdfExtractText", method: "pdf:extractText",
    kind: "invoke",
    group: "PDF",
    params: [{ n: "id", t: "number" }],
    returns: "Promise<any>"
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
    returns: "Promise<any[]>"
  },
  {
    member: "getBook", method: "db:getBook",
    kind: "invoke",
    group: "Books",
    params: [{ n: "id", t: "number" }],
    returns: "Promise<any>"
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
    params: [{ n: "bookId", t: "number" }, { n: "progress", t: "any" }],
    returns: "Promise<void>"
  },
  {
    member: "getReadingProgress", method: "db:getProgress",
    kind: "invoke",
    group: "Books",
    params: [{ n: "bookId", t: "number" }],
    returns: "Promise<any>"
  },
  // ── Bookmarks ──
  {
    member: "getBookmarks", method: "db:getBookmarks",
    kind: "invoke",
    group: "Bookmarks",
    params: [{ n: "bookId", t: "number" }],
    returns: "Promise<any[]>"
  },
  {
    member: "addBookmark", method: "db:addBookmark",
    kind: "invoke",
    group: "Bookmarks",
    params: [{ n: "bookmark", t: "any" }],
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
    returns: "Promise<any[]>"
  },
  {
    member: "addHighlight", method: "db:addHighlight",
    kind: "invoke",
    group: "Highlights",
    params: [{ n: "highlight", t: "any" }],
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
    returns: "Promise<any[]>"
  },
  {
    member: "addNote", method: "db:addNote",
    kind: "invoke",
    group: "Notes",
    params: [{ n: "note", t: "any" }],
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
    returns: "Promise<any>"
  },
  {
    member: "updateSettings", method: "db:updateSettings",
    kind: "invoke",
    group: "Settings",
    params: [{ n: "settings", t: "any" }],
    returns: "Promise<void>"
  },
  {
    member: "setLanguage", method: "app:setLanguage",
    kind: "invoke",
    group: "Settings",
    params: [{ n: "lang", t: "string" }],
    returns: "Promise<any>"
  },
  {
    member: "getBookSettings", method: "db:getBookSettings",
    kind: "invoke",
    group: "Settings",
    params: [{ n: "bookId", t: "number" }],
    returns: "Promise<any>"
  },
  {
    member: "updateBookSettings", method: "db:updateBookSettings",
    kind: "invoke",
    group: "Settings",
    params: [{ n: "bookId", t: "number" }, { n: "settings", t: "any" }],
    returns: "Promise<void>"
  },
  {
    member: "deleteBookSettings", method: "db:deleteBookSettings",
    kind: "invoke",
    group: "Settings",
    params: [{ n: "bookId", t: "number" }],
    returns: "Promise<void>"
  },
  // ── Bookshelves ──
  {
    member: "getBookshelves", method: "db:getBookshelves",
    kind: "invoke",
    group: "Bookshelves",
    params: [],
    returns: "Promise<any[]>"
  },
  {
    member: "addBookshelf", method: "db:addBookshelf",
    kind: "invoke",
    group: "Bookshelves",
    params: [{ n: "name", t: "string" }],
    returns: "Promise<any>"
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
  {
    member: "getShelvesForBook", method: "db:getShelvesForBook",
    kind: "invoke",
    group: "Bookshelves",
    params: [{ n: "bookId", t: "number" }],
    returns: "Promise<number[]>"
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
    returns: "Promise<any[]>"
  },
  {
    member: "getBookSource", method: "bookSource:get",
    kind: "invoke",
    group: "Book Sources",
    params: [{ n: "id", t: "number" }],
    returns: "Promise<any>"
  },
  {
    member: "insertBookSource", method: "bookSource:insert",
    kind: "invoke",
    group: "Book Sources",
    params: [{ n: "source", t: "any" }],
    returns: "Promise<any>"
  },
  {
    member: "updateBookSource", method: "bookSource:update",
    kind: "invoke",
    group: "Book Sources",
    params: [{ n: "id", t: "number" }, { n: "updates", t: "any" }],
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
    returns: "Promise<{ imported: number; total: number }>"
  },
  {
    member: "searchBooks", method: "bookSource:search",
    kind: "invoke",
    group: "Book Sources",
    params: [{ n: "keyword", t: "string" }, { n: "page", t: "number", o: true }],
    returns: "Promise<any[]>"
  },
  {
    member: "searchBooksFromSource", method: "bookSource:searchOne",
    kind: "invoke",
    group: "Book Sources",
    params: [{ n: "sourceId", t: "number" }, { n: "keyword", t: "string" }, { n: "page", t: "number", o: true }],
    returns: "Promise<any[]>"
  },
  {
    member: "getBookInfoFromSource", method: "bookSource:getBookInfo",
    kind: "invoke",
    group: "Book Sources",
    params: [{ n: "sourceId", t: "number" }, { n: "bookUrl", t: "string" }],
    returns: "Promise<any>"
  },
  {
    member: "getChapterListFromSource", method: "bookSource:getChapterList",
    kind: "invoke",
    group: "Book Sources",
    params: [{ n: "sourceId", t: "number" }, { n: "tocUrl", t: "string" }],
    returns: "Promise<any[]>"
  },
  {
    member: "downloadBook", method: "bookSource:download",
    kind: "invoke",
    group: "Book Sources",
    params: [{ n: "sourceId", t: "number" }, { n: "bookUrl", t: "string" }, { n: "bookName", t: "string" }, { n: "format", t: "string" }],
    returns: "Promise<number>"
  },
  {
    member: "onDownloadProgress", method: "bookSource:downloadProgress",
    kind: "event",
    group: "Book Sources",
    params: [{ n: "callback", t: "(progress: any) => void" }],
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
    member: "zlibHide", method: "zlib:hide",
    kind: "invoke",
    group: "Z-Library",
    params: [],
    returns: "Promise<void>"
  },
  {
    member: "zlibNavigate", method: "zlib:navigate",
    kind: "invoke",
    group: "Z-Library",
    params: [{ n: "action", t: "'back' | 'forward' | 'reload'" }],
    returns: "Promise<void>"
  },
  {
    member: "zlibGetURL", method: "zlib:getURL",
    kind: "invoke",
    group: "Z-Library",
    params: [],
    returns: "Promise<string>"
  },
  {
    member: "zlibSetBounds", method: "zlib:setBounds",
    kind: "invoke",
    group: "Z-Library",
    params: [{ n: "bounds", t: "{ x: number; y: number; width: number; height: number }" }],
    returns: "Promise<void>"
  },
  {
    member: "zlibLogout", method: "zlib:logout",
    kind: "invoke",
    group: "Z-Library",
    params: [],
    returns: "Promise<void>"
  },
  {
    member: "zlibSwitchMirror", method: "zlib:switchMirror",
    kind: "invoke",
    group: "Z-Library",
    params: [{ n: "index", t: "number" }],
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
    member: "zlibSetDownloadPath", method: "zlib:setDownloadPath",
    kind: "invoke",
    group: "Z-Library",
    params: [{ n: "path", t: "string" }],
    returns: "Promise<void>"
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
    member: "onZlibDownloadProgress", method: "zlib:downloadProgress",
    kind: "event",
    group: "Z-Library",
    params: [{ n: "callback", t: "(progress: any) => void" }],
    returns: "() => void"
  },
  {
    member: "onZlibDownloadComplete", method: "zlib:downloadComplete",
    kind: "event",
    group: "Z-Library",
    params: [{ n: "callback", t: "(data: any) => void" }],
    returns: "() => void"
  },
  {
    member: "onZlibImportComplete", method: "zlib:importComplete",
    kind: "event",
    group: "Z-Library",
    params: [{ n: "callback", t: "(data: any) => void" }],
    returns: "() => void"
  },
  {
    member: "onZlibImportError", method: "zlib:importError",
    kind: "event",
    group: "Z-Library",
    params: [{ n: "callback", t: "(data: any) => void" }],
    returns: "() => void"
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
    member: "getReadingTime", method: "db:getReadingTime",
    kind: "invoke",
    group: "Reading Sessions",
    params: [{ n: "bookId", t: "number" }],
    returns: "Promise<number>"
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
    member: "onMenuImportBooks", method: "menu:importBooks",
    kind: "event",
    group: "Menu events",
    params: [{ n: "callback", t: "(filePaths: string[]) => void" }],
    returns: "() => void"
  },
  {
    member: "onMenuShowAbout", method: "menu:showAbout",
    kind: "event",
    group: "Menu events",
    params: [{ n: "callback", t: "() => void" }],
    returns: "() => void"
  },
  // ── Auto Updater ──
  {
    member: "checkUpdate", method: "app:checkUpdate",
    kind: "invoke",
    group: "Auto Updater",
    params: [],
    returns: "Promise<any>"
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
    returns: "Promise<any>"
  },
  {
    member: "quitAndInstall", method: "app:quitAndInstall",
    kind: "invoke",
    group: "Auto Updater",
    params: [],
    returns: "Promise<any>"
  },
  {
    member: "onUpdateAvailable", method: "app:updateAvailable",
    kind: "event",
    group: "Auto Updater",
    params: [{ n: "callback", t: "(info: any) => void" }],
    returns: "() => void"
  },
  {
    member: "onUpdateChecked", method: "app:updateChecked",
    kind: "event",
    group: "Auto Updater",
    params: [{ n: "callback", t: "(info: any) => void" }],
    returns: "() => void"
  },
  {
    member: "onUpdateNotAvailable", method: "app:updateNotAvailable",
    kind: "event",
    group: "Auto Updater",
    params: [{ n: "callback", t: "() => void" }],
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
