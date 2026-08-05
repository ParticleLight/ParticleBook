interface ElectronAPI {
  openFile: () => Promise<string | null>
  openDirectory: () => Promise<string | null>
  readFile: (filePath: string) => Promise<Buffer>
  getBookMetadata: (filePath: string) => Promise<any>
  importBooks: (filePaths: string[]) => Promise<any[]>
  writeDroppedFile: (name: string, dataB64: string) => Promise<{ path: string; size: number } | null>
  getCoverImage: (bookId: number) => Promise<string | null>

  getBooks: () => Promise<any[]>
  getBook: (id: number) => Promise<any>
  deleteBook: (id: number) => Promise<void>
  updateReadingProgress: (bookId: number, progress: any) => Promise<void>
  getReadingProgress: (bookId: number) => Promise<any>

  getBookmarks: (bookId: number) => Promise<any[]>
  addBookmark: (bookmark: any) => Promise<void>
  deleteBookmark: (id: number) => Promise<void>
  updateBookmarkTitle: (id: number, title: string) => Promise<void>

  getHighlights: (bookId: number) => Promise<any[]>
  addHighlight: (highlight: any) => Promise<void>
  deleteHighlight: (id: number) => Promise<void>

  getNotes: (bookId: number) => Promise<any[]>
  addNote: (note: any) => Promise<void>
  updateNote: (id: number, content: string) => Promise<void>
  deleteNote: (id: number) => Promise<void>

  getSettings: () => Promise<any>
  updateSettings: (settings: any) => Promise<void>
  getBookSettings: (bookId: number) => Promise<any>
  updateBookSettings: (bookId: number, settings: any) => Promise<void>
  deleteBookSettings: (bookId: number) => Promise<void>

  // Bookshelves
  getBookshelves: () => Promise<any[]>
  addBookshelf: (name: string) => Promise<any>
  deleteBookshelf: (id: number) => Promise<void>
  renameBookshelf: (id: number, name: string) => Promise<void>
  getBooksInShelf: (shelfId: number) => Promise<number[]>
  addBookToShelf: (shelfId: number, bookId: number) => Promise<void>
  removeBookFromShelf: (shelfId: number, bookId: number) => Promise<void>
  getShelvesForBook: (bookId: number) => Promise<number[]>

  // Utilities
  getFilePath: (file: File) => string

  // Book Sources
  getBookSources: () => Promise<any[]>
  getBookSource: (id: number) => Promise<any>
  insertBookSource: (source: any) => Promise<any>
  updateBookSource: (id: number, updates: any) => Promise<void>
  deleteBookSource: (id: number) => Promise<void>
  toggleBookSource: (id: number) => Promise<void>
  clearAllBookSources: () => Promise<void>
  importBookSources: () => Promise<{ imported: number; total: number }>
  searchBooks: (keyword: string, page?: number) => Promise<any[]>
  searchBooksFromSource: (sourceId: number, keyword: string, page?: number) => Promise<any[]>
  getBookInfoFromSource: (sourceId: number, bookUrl: string) => Promise<any>
  getChapterListFromSource: (sourceId: number, tocUrl: string) => Promise<any[]>
  downloadBook: (sourceId: number, bookUrl: string, bookName: string, format: string) => Promise<number>
  onDownloadProgress: (callback: (progress: any) => void) => () => void

  // Z-Library
  zlibShow: () => Promise<void>
  zlibHide: () => Promise<void>
  zlibNavigate: (action: 'back' | 'forward' | 'reload') => Promise<void>
  zlibGetURL: () => Promise<string>
  zlibSetBounds: (bounds: { x: number; y: number; width: number; height: number }) => Promise<void>
  zlibLogout: () => Promise<void>
  zlibSwitchMirror: (index: number) => Promise<void>
  zlibGetMirrorInfo: () => Promise<{ index: number; url: string; mirrors: string[] }>
  zlibSetDownloadPath: (path: string) => Promise<void>
  zlibGetDownloadPath: () => Promise<{ path: string }>
  zlibPickDownloadFolder: () => Promise<{ path: string } | null>
  onZlibDownloadProgress: (callback: (progress: any) => void) => () => void
  onZlibDownloadComplete: (callback: (data: any) => void) => () => void
  onZlibImportComplete: (callback: (data: any) => void) => () => void
  onZlibImportError: (callback: (data: any) => void) => () => void
  onZlibMirrorChanged: (callback: (info: { index: number; url: string; mirrors: string[] }) => void) => () => void
  onZlibAllMirrorsFailed: (callback: () => void) => () => void

  // Reading Sessions
  startReadingSession: (bookId: number) => Promise<number>
  endReadingSession: (sessionId: number) => Promise<void>
  updateReadingSessionDuration: (sessionId: number, durationSeconds: number) => Promise<void>
  getReadingTime: (bookId: number) => Promise<number>
  getAllReadingTime: () => Promise<Record<number, number>>
  getAllReadingProgress: () => Promise<Record<number, { progress: number; page?: number; updated_at: string }>>

  // Menu events
  onMenuImportBooks: (callback: (filePaths: string[]) => void) => () => void
  onMenuShowAbout: (callback: () => void) => () => void

  // Auto Updater
  checkUpdate: () => Promise<any>
  getAppVersion: () => Promise<string>
  downloadUpdate: (url: string, sha512?: string) => Promise<any>
  quitAndInstall: () => Promise<any>
  onUpdateAvailable: (callback: (info: any) => void) => () => void
  onUpdateChecked: (callback: (info: any) => void) => () => void
  onUpdateNotAvailable: (callback: () => void) => () => void
  onUpdateDownloaded: (callback: () => void) => () => void
  onUpdateError: (callback: (message: string) => void) => () => void
  onUpdateDownloadProgress: (callback: (progress: { percent: number }) => void) => () => void
}

declare interface Window {
  electronAPI: ElectronAPI
  __refreshLibrary?: () => void
  _droppedFiles?: { name: string; path: string }[]
}
