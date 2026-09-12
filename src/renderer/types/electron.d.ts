// ═══════════════════════════════════════════════════════════════════════
// 此文件由 scripts/gen-bridge-dts.mjs 从 bridge/contract.mjs 生成。
// 请勿手改：改动会在下一次生成时丢失，且 CI 的新鲜度校验会失败。
// 要改契约请改表，然后运行： npm run gen:bridge-dts
// ═══════════════════════════════════════════════════════════════════════

// Shapes returned by electronAPI.readFile: raw bytes (JSON byte array or a
// typed array), a virtual-host reference the caller must fetch, or null when the
// read failed (C++ returns json(nullptr) for an unreadable/missing file — the
// caller MUST treat null as failure, not as an empty file).
type PbVirtualHostRef = { _pb_url: string }
type PbFileContent = Uint8Array | number[] | PbVirtualHostRef | null

// 数据形状：与 C++ 侧实际拼装对齐，见 bridge/contract.mjs 中 types 的注释。
interface PbBook {
  id: number
  title: string
  author: string | null
  format: string
  file_path: string
  file_size: number
  description: string | null
  publisher: string | null
  cover_path: string | null
  language: string | null
  isbn: string | null
  added_at: string
  last_opened?: string
}

interface PbBookmarkInput {
  book_id: number
  cfi?: string
  page?: number
  progress?: number
  title?: string
  note?: string
}

interface PbBookmark extends PbBookmarkInput {
  id: number
  created_at: string
}

interface PbHighlightInput {
  book_id: number
  cfi?: string
  page?: number
  text: string
  color: string
  note?: string
}

interface PbHighlight extends PbHighlightInput {
  id: number
  created_at: string
}

interface PbNoteInput {
  book_id: number
  highlight_id?: number
  cfi?: string
  page?: number
  content: string
}

interface PbNote extends PbNoteInput {
  id: number
  created_at: string
  updated_at: string
}

interface PbBookshelf {
  id: number
  name: string
  created_at: string
}

interface PbProgressInput {
  progress: number
  cfi?: string
  page?: number
  scrollPosition?: number
}

interface PbProgressRecord {
  book_id?: number
  progress?: number
  cfi?: string
  page?: number
  scroll_position?: number
  updated_at?: string
}

interface PbSettings {
  // 键与渲染层 SETTINGS_KEYS 一致；数据库初值为空对象，故全部可选。
  theme?: 'light' | 'dark' | 'sepia'
  accentColor?: 'blue' | 'purple' | 'green' | 'orange'
  fontSize?: number
  fontFamily?: string
  lineHeight?: number
  margin?: number
  textAlign?: 'left' | 'justify'
  autoSaveProgress?: boolean
  showReadingTime?: boolean
  defaultViewMode?: 'grid' | 'list'
  defaultSortBy?: 'title' | 'author' | 'added_at' | 'last_opened'
  language?: 'zh' | 'en'
}

interface PbBookSourceInput {
  // Legado 书源格式：应用只解释这三个字段，其余规则字段原样存取，
  // 保留字符串索引签名而不是假装穷举了外部规范。
  bookSourceName: string
  bookSourceUrl: string
  enabled?: boolean
  [key: string]: unknown
}

interface PbBookSource extends PbBookSourceInput {
  id: number
  added_at?: string
}

interface PbSourceSearchResult {
  // bookName 必需：SearchOne 只推送 nameRule 命中且非空的条目
  // （见 BookSourceService::SearchOne 的 push 守卫）；其余字段取决于规则是否命中。
  bookName: string
  author?: string
  bookUrl?: string
  coverUrl?: string
}

interface PbSearchResult extends PbSourceSearchResult {
  // SearchAll 在每条结果上补的来源信息（跨源搜索时用于区分与下载）。
  sourceId: number
  sourceName: string
}

interface PbBookInfo {
  bookUrl: string
  bookName?: string
  author?: string
  coverUrl?: string
  intro?: string
  tocUrl?: string
}

interface PbChapter {
  name: string
  url: string
}

interface PbDownloadProgress {
  // 阶段序列：fetching_toc -> downloading（带 chapterName）-> assembling
  //          -> importing -> done | error。done/error 带 bookId；
  // error 时 error 为机器可读错误码，由 UI 本地化：source_not_found /
  // no_chapters / empty_content / write_failed / import_failed。
  status: 'fetching_toc' | 'downloading' | 'assembling' | 'importing' | 'done' | 'error'
  current: number
  total: number
  chapterName?: string
  bookId?: number
  error?: string
}

interface PbUpdateInfo {
  version: string
  fileName: string
  downloadUrl: string
  size: number
  sha512: string
}

interface PbBookMetadata {
  // 未知格式时 C++ 返回空对象，故全部可选。
  title?: string
  author?: string
  language?: string
  format?: string
}

interface PbPdfText {
  pages: { pageNum: number; text: string }[]
}

interface PbZlibDownloadProgress {
  fileName: string
  received: number
  total: number
}

interface PbZlibDownloadComplete {
  fileName: string
  path: string
}

interface PbZlibImportComplete {
  fileName: string
}

interface PbZlibImportError {
  fileName: string
  error: string
}

interface PbZlibDownloadError {
  fileName: string
  // 机器可读错误码，由 UI 本地化：invalid_url / http_open_failed / connect_failed /
  // request_failed / network_error / http_<状态码> / file_create_failed /
  // empty_response / too_many_redirects
  error: string
}

interface PbUpdateDownloaded {
  success: boolean
  path: string
}

interface PbUpdateError {
  error: string
}

interface ElectronAPI {
  // Files
  openFile: () => Promise<string | null>
  readFile: (filePath: string) => Promise<PbFileContent>
  importBooks: (filePaths: string[]) => Promise<PbBook[]>
  writeDroppedFile: (name: string, dataB64: string) => Promise<{ path: string; size: number } | null>
  getCoverImage: (bookId: number) => Promise<string | null>

  // PDF
  pdfOpen: (filePath: string) => Promise<{ id: number; pageCount: number; pageBounds: { width: number; height: number }[] } | null>
  pdfRenderPage: (id: number, pageNum: number, width: number, height: number) => Promise<string | null>
  pdfExtractText: (id: number) => Promise<PbPdfText | null>
  pdfClose: (id: number) => Promise<void>

  // Books
  getBooks: () => Promise<PbBook[]>
  getBook: (id: number) => Promise<PbBook | null>
  deleteBook: (id: number) => Promise<void>
  updateReadingProgress: (bookId: number, progress: PbProgressInput) => Promise<void>
  getReadingProgress: (bookId: number) => Promise<PbProgressRecord>

  // Bookmarks
  getBookmarks: (bookId: number) => Promise<PbBookmark[]>
  addBookmark: (bookmark: PbBookmarkInput) => Promise<void>
  deleteBookmark: (id: number) => Promise<void>
  updateBookmarkTitle: (id: number, title: string) => Promise<void>

  // Highlights
  getHighlights: (bookId: number) => Promise<PbHighlight[]>
  addHighlight: (highlight: PbHighlightInput) => Promise<void>
  deleteHighlight: (id: number) => Promise<void>

  // Notes
  getNotes: (bookId: number) => Promise<PbNote[]>
  addNote: (note: PbNoteInput) => Promise<void>
  updateNote: (id: number, content: string) => Promise<void>
  deleteNote: (id: number) => Promise<void>

  // Settings
  getSettings: () => Promise<PbSettings>
  updateSettings: (settings: PbSettings) => Promise<void>
  setLanguage: (lang: string) => Promise<boolean>
  getBookSettings: (bookId: number) => Promise<PbSettings>
  updateBookSettings: (bookId: number, settings: PbSettings) => Promise<void>

  // Bookshelves
  getBookshelves: () => Promise<PbBookshelf[]>
  addBookshelf: (name: string) => Promise<PbBookshelf>
  deleteBookshelf: (id: number) => Promise<void>
  renameBookshelf: (id: number, name: string) => Promise<void>
  getBooksInShelf: (shelfId: number) => Promise<number[]>
  addBookToShelf: (shelfId: number, bookId: number) => Promise<void>
  removeBookFromShelf: (shelfId: number, bookId: number) => Promise<void>

  // Utilities
  getFilePath: (file: File) => string

  // Book Sources
  getBookSources: () => Promise<PbBookSource[]>
  getBookSource: (id: number) => Promise<PbBookSource | null>
  insertBookSource: (source: PbBookSourceInput) => Promise<PbBookSource>
  updateBookSource: (id: number, updates: Partial<PbBookSourceInput>) => Promise<void>
  deleteBookSource: (id: number) => Promise<void>
  toggleBookSource: (id: number) => Promise<void>
  clearAllBookSources: () => Promise<void>
  importBookSources: () => Promise<{ imported: number } | null>
  searchBooks: (keyword: string, page?: number) => Promise<PbSearchResult[]>
  searchBooksFromSource: (sourceId: number, keyword: string, page?: number) => Promise<PbSourceSearchResult[]>
  getBookInfoFromSource: (sourceId: number, bookUrl: string) => Promise<PbBookInfo>
  getChapterListFromSource: (sourceId: number, tocUrl: string) => Promise<PbChapter[]>
  downloadBook: (sourceId: number, bookUrl: string, bookName: string, format: string) => Promise<string>
  onDownloadProgress: (callback: (progress: PbDownloadProgress) => void) => () => void

  // Z-Library
  zlibShow: () => Promise<void>
  zlibGetMirrorInfo: () => Promise<{ index: number; url: string; mirrors: string[] }>
  zlibGetDownloadPath: () => Promise<{ path: string }>
  zlibPickDownloadFolder: () => Promise<{ path: string } | null>
  onZlibMirrorChanged: (callback: (info: { index: number; url: string; mirrors: string[] }) => void) => () => void
  onZlibAllMirrorsFailed: (callback: () => void) => () => void
  onZlibDownloadError: (callback: (data: PbZlibDownloadError) => void) => () => void

  // Reading Sessions
  startReadingSession: (bookId: number) => Promise<number>
  endReadingSession: (sessionId: number) => Promise<void>
  updateReadingSessionDuration: (sessionId: number, durationSeconds: number) => Promise<void>
  getAllReadingTime: () => Promise<Record<number, number>>
  getAllReadingProgress: () => Promise<Record<number, { progress: number; page?: number; updated_at: string }>>

  // Library
  onLibraryChanged: (callback: () => void) => () => void

  // Auto Updater
  checkUpdate: () => Promise<PbUpdateInfo | null>
  getAppVersion: () => Promise<string>
  downloadUpdate: (url: string, sha512?: string) => Promise<boolean | null>
  quitAndInstall: () => Promise<boolean>
  onUpdateChecked: (callback: (info: PbUpdateInfo | null) => void) => () => void
  onUpdateDownloaded: (callback: () => void) => () => void
  onUpdateError: (callback: (message: string) => void) => () => void
  onUpdateDownloadProgress: (callback: (progress: { percent: number }) => void) => () => void
}

// 非桥接契约的手写附加项（不属于 bridge/contract.mjs 管理范围）。
declare interface Window {
  electronAPI: ElectronAPI
  __pbLang?: string
  __refreshLibrary?: () => void
  _droppedFiles?: { name: string; path: string }[]
}
