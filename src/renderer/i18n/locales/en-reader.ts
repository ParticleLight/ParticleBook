// English translations for the Reader components (ReaderView, ReaderControls,
// Sidebar, SearchBar, TextRenderer, HtmlRenderer, EpubRenderer, ComicRenderer).
// Keys are the original Chinese UI strings — i18next falls back to the key
// itself (the Chinese text) when a key has no English entry, so zh mode needs
// no dictionary. Keep keys EXACTLY as they appear in the components (they
// double as the Chinese source). {{var}} placeholders must match verbatim.
export const enReader: Record<string, string> = {
  // ReaderView
  '本次阅读时长': 'Reading time',
  '这本书已不在书架': 'This book is no longer in your library',
  '找不到这本书的文件': "Can't find this book's file",
  '打开这本书失败': 'Failed to open this book',
  '它的文件已经不在原来的位置了 —— 可能被移动、重命名或删除。': 'Its file is no longer where it used to be — it may have been moved, renamed, or deleted.',
  '它可能已在其它页面被删除。': 'It may have been deleted from another view.',
  '读取文件时出错：{{error}}': 'Error reading the file: {{error}}',
  '返回书架': 'Back to library',
  '从书架移除': 'Remove from library',
  '从书架移除这本书？': 'Remove this book from your library?',
  '将删除书架记录以及它的阅读进度、书签和笔记；磁盘上的文件不会被删除。': 'This deletes the library entry along with its reading progress, bookmarks and notes. The file on disk is not deleted.',
  '移除': 'Remove',
  '把文件放回原位，或重新导入、重新下载这本书，即可继续阅读。': 'Put the file back, or re-import / re-download this book to keep reading.',
  '上一页': 'Previous page',
  '下一页': 'Next page',
  '第 {{page}} 页': 'Page {{page}}',

  // ReaderControls
  '书签{{n}}': 'Bookmark {{n}}',
  '输入笔记内容...': 'Enter note content...',
  '保存': 'Save',
  '取消': 'Cancel',
  '搜索全书... (Enter/Shift+Enter)': 'Search book... (Enter/Shift+Enter)',
  '上一个 (Shift+Enter)': 'Previous (Shift+Enter)',
  '下一个 (Enter)': 'Next (Enter)',
  '关闭搜索 (Esc)': 'Close search (Esc)',
  '目录': 'Table of contents',
  '取消书签': 'Remove bookmark',
  '添加书签': 'Add bookmark',
  '书签列表': 'Bookmarks',
  '添加笔记': 'Add note',
  '搜索 (Ctrl+F)': 'Search (Ctrl+F)',
  '搜索': 'Search',
  '笔记列表': 'Notes',
  '亮': 'Light',
  '暗': 'Dark',
  '设置': 'Settings',

  // Sidebar
  '书签': 'Bookmark',
  '高亮': 'Highlights',
  '笔记': 'Notes',
  '无目录信息': 'No table of contents',
  '章节 {{n}}': 'Chapter {{n}}',
  '暂无书签': 'No bookmarks',
  '双击重命名': 'Double-click to rename',
  '重命名': 'Rename',
  '删除': 'Delete',
  '暂无高亮': 'No highlights',
  '添加笔记...': 'Add note...',
  '添加': 'Add',
  '暂无笔记': 'No notes',

  // SearchBar
  '搜索全书...': 'Search book...',
  '关闭 (Esc)': 'Close (Esc)',

  // TextRenderer
  '黄': 'Yellow',
  '蓝': 'Blue',
  '绿': 'Green',
  '红': 'Red',
  '紫': 'Purple',

  // HtmlRenderer
  '<p>无法解析 FB2 文件</p>': '<p>Failed to parse FB2 file</p>',
  '<p>空文档</p>': '<p>Empty document</p>',

  // EpubRenderer
  '黄色': 'Yellow',
  '绿色': 'Green',
  '蓝色': 'Blue',
  '粉色': 'Pink',
  '紫色': 'Purple',

  // ComicRenderer
  '正在解压漫画...': 'Extracting comic...',
  '未找到图片': 'No images found',
}
