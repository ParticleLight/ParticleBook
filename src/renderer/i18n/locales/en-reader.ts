// English translations for the Reader components (ReaderView, ReaderControls,
// Sidebar, SearchBar, TextRenderer, HtmlRenderer, EpubRenderer, ComicRenderer).
// Keys are the original Chinese UI strings — i18next falls back to the key
// itself (the Chinese text) when a key has no English entry, so zh mode needs
// no dictionary. Keep keys EXACTLY as they appear in the components (they
// double as the Chinese source). {{var}} placeholders must match verbatim.
export const enReader: Record<string, string> = {
  // ReaderView / ReaderControls（2026-10-28：目录 / 书签 / 笔记 合并成一个侧栏按钮）
  '侧栏': 'Sidebar',
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

  // TtsBar / ReaderControls（PB-001-B 朗读）
  '朗读 (TTS)': 'Read aloud (TTS)',
  '开始朗读': 'Start reading aloud',
  '暂停朗读': 'Pause reading',
  '继续朗读': 'Resume reading',
  '停止朗读': 'Stop reading',
  '关闭朗读条': 'Close read-aloud bar',
  '语速': 'Speed',
  '音色': 'Voice',
  '无可用音色': 'No voices available',
  '这本书暂不支持朗读': 'Read-aloud is not available for this book',
  '点击播放开始朗读（优先朗读选中文字）': 'Press play to start. Selected text is read first.',
  '没有可用的语音，请在 Windows 设置里安装语音包': 'No voices installed. Add one in Windows Settings.',
  '没有找到可朗读的文本': 'No readable text found',
  '这一页没有可朗读的文本（可能是扫描版）': 'This page has no readable text (it may be a scanned page)',
  '朗读失败': 'Read-aloud failed',
  '朗读失败：{{error}}': 'Read-aloud failed: {{error}}',
  '朗读失败（没有可用音色或被系统拒绝）': 'Read-aloud failed (no voice available or blocked by the system)',
  '暂停失败': 'Could not pause',
  '继续失败': 'Could not resume',
  '切换音色失败': 'Could not switch voice',

  // TtsBar 睡眠定时（PB-001-C）
  '睡眠定时': 'Sleep timer',
  '关闭定时': 'Off',
  '{{n}} 分钟': '{{n}} min',
  '到本章结束': 'End of chapter',
  '睡眠定时已到，朗读已停止': 'Sleep timer reached — reading stopped',
  '已读完本章，朗读已停止': 'End of chapter — reading stopped',

  // annotationStore（PB-021-C 标注数据层；错误提示由 store 统一给出）
  '加载标注失败：{{error}}': 'Failed to load annotations: {{error}}',
  '标注未保存：数据无效': 'Annotation was not saved: invalid data',
  '保存标注失败：{{error}}': 'Failed to save annotation: {{error}}',
  '标注更新失败：标注无效或已被删除': 'Could not update annotation: it is invalid or was deleted',
  '更新标注失败：{{error}}': 'Failed to update annotation: {{error}}',
  '删除标注失败：{{error}}': 'Failed to delete annotation: {{error}}',

  // TranslationPopup（PB-002-B 选中翻译）；错误码文案由 t(errorMessageKey(code)) 使用
  '翻译': 'Translate',
  '翻译中…': 'Translating…',
  '缓存': 'Cached',
  '来自本地缓存，可离线查看': 'From the local cache — available offline',
  '复制译文': 'Copy translation',
  '已复制': 'Copied',
  '关闭翻译': 'Close translation',
  '去设置': 'Open settings',
  '翻译失败': 'Translation failed',
  '翻译功能未启用（可在设置 → AI 翻译 中开启）': 'Translation is off — enable it in Settings → AI translation',
  '还没有配置翻译 API Key（设置 → AI 翻译）': 'No translation API key yet (Settings → AI translation)',
  '翻译还没配置好（需要填写 Base URL 与模型）': 'Translation is not configured yet (Base URL and model are required)',
  '翻译服务暂时不可用': 'Translation service is temporarily unavailable',
  '选中的文本不可翻译': 'The selected text cannot be translated',
  '网络错误，请检查网络或 Base URL': 'Network error — check your connection or Base URL',
  '请求超时，请稍后重试': 'The request timed out — please try again',
  'API Key 无效或没有权限': 'The API key is invalid or has no permission',
  '额度不足或触发限流，请稍后重试': 'Quota exceeded or rate-limited — please try again later',
  '翻译服务返回了 HTTP 错误': 'The translation service returned an HTTP error',
  '翻译服务拒绝了请求（模型名或参数可能不对）': 'The translation service rejected the request (check the model name)',
  '翻译响应解析失败': 'Could not parse the translation response',
  '翻译已取消': 'Translation cancelled',
  '选中的文本太长，请少选一些': 'The selection is too long — select less text',

  // AnnotationPanel（PB-021-D 标注侧栏）
  '标注': 'Annotations',
  '关闭': 'Close',
  '全部': 'All',
  '排序': 'Sort',
  '按位置': 'By position',
  '按时间': 'By date',
  '暂无标注': 'No annotations yet',
  '加载标注...': 'Loading annotations...',
  '无内容': 'No text',
  'EPUB 位置': 'EPUB location',
  '字符位置 {{offset}}': 'Character {{offset}}',
  '位置 {{percent}}%': '{{percent}}% through the book',
  '已添加书签': 'Bookmark added',
  '已添加笔记': 'Note added',
  '已删除标注': 'Annotation deleted',
  '添加失败，请重试': 'Could not add it — please try again',
  '删除失败，请重试': 'Could not delete it — please try again',
  '这个标注没有可用的跳转位置': 'This annotation has no jump target',
  '在当前位置添加书签': 'Add bookmark at current position',
  '在当前位置添加笔记': 'Add note at current position',
  '高亮请在正文中选中文字后添加（沿用现有高亮按钮）': 'To highlight, select text in the book and use the existing highlight button',

  // PB-003：PDF 页内标注（高亮/下划线/笔记/侧栏过滤）
  '下划线': 'Underline',
  'PDF 标注': 'PDF annotations',
  '编辑笔记': 'Edit note',
  '笔记内容': 'Note text',

  // PB-009-A：EPUB 分栏 / 竖排（ReaderControls 工具栏）
  '分栏': 'Columns',
  '分栏：{{mode}}（点击切换）': 'Columns: {{mode}} (click to cycle)',
  '自动': 'Auto',
  '单栏': 'Single',
  '双栏': 'Two columns',
  '竖排': 'Vertical text',
  '关闭竖排': 'Turn off vertical text',

  // PB-025-B：移除确认框（Dialog 接线）
  '移除后可以重新导入这本书继续阅读。': 'You can import the book again later to keep reading.',

  // PB-009-C：分屏阅读（SplitReaderView）
  '找不到要分屏的书籍': 'Could not find the books to show side by side',
  '加载书籍失败': 'Failed to load the books',
  '正在加载书籍…': 'Loading books…',
  'PDF 暂不支持分屏阅读': 'PDF is not supported in split view yet',
  '漫画（CBZ/CBR）暂不支持分屏阅读': 'Comics (CBZ/CBR) are not supported in split view yet',
  '该格式暂不支持分屏阅读': 'This format is not supported in split view yet',
  '目前分屏支持 EPUB 与 TXT；可以在单栏模式继续读这本。': 'Split view currently supports EPUB and TXT; you can keep reading this book in single view.',
  '正在准备阅读…': 'Preparing the reading view…',
  '拖动调整两栏宽度': 'Drag to resize the two panes',
  '退出分屏': 'Exit split view',
  '读取文件失败': 'Could not read the file',
  '无法打开 EPUB 文件': 'Could not open the EPUB file',
  '正在加载 EPUB…': 'Loading EPUB…',
  '分屏阅读文本': 'Split-view text',
  '正在加载文本…': 'Loading text…',
  '导出预览': 'Export preview',

}
