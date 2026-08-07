// English translations for the Library components (Library.tsx / BookCard.tsx /
// BookList.tsx / BookShelfPanel.tsx / BookDetailDialog.tsx). Keys are the exact
// Chinese strings passed to t() — the i18n fallback returns the key itself in
// zh mode, so keys double as the Chinese source. Keep them identical to the
// t() calls ({{placeholders}} included verbatim).
//
// '添加_sort' is an i18next context variant (t('添加', { context: 'sort' })) used
// to disambiguate the sort-dropdown label "Added" from the button label "Add".
export const enLibrary: Record<string, string> = {
  // BookPickerDialog (Library.tsx)
  '添加到「{{shelfName}}」': 'Add to "{{shelfName}}"',
  '从全部书籍中选择，已选 {{n}} 本': 'Choose from all books — {{n}} selected',
  '搜索书籍...': 'Search books...',
  '无匹配结果': 'No matching results',
  '没有可添加的书籍': 'No books to add',
  '未知作者': 'Unknown author',
  ' · 已添加': ' · Added',
  '取消': 'Cancel',
  '添加': 'Add',
  '添加_sort': 'Added',
  '「{{name}}」超过 50MB，拖放导入不支持超大文件，请改用「导入」按钮':
    '"{{name}}" exceeds 50MB. Drag-and-drop import does not support very large files. Please use the "Import" button instead.',

  // Library toolbar / sidebar / empty state
  '全部书籍': 'All books',
  '搜索书名或作者...': 'Search by title or author...',
  '最近': 'Recent',
  '书名': 'Title',
  '作者': 'Author',
  '网格视图': 'Grid view',
  '列表视图': 'List view',
  '导入书籍': 'Import books',
  '导入': 'Import',
  '更多操作': 'More actions',
  '刷新书架': 'Refresh library',
  '阅读统计': 'Reading statistics',
  '更新日志': 'Changelog',
  '全局设置': 'Settings',
  '{{count}} 本书_one': '{{count}} book',
  '{{count}} 本书_other': '{{count}} books',
  '从全部书籍中选择，已选 {{count}} 本_one': 'Select from all books — {{count}} selected',
  '从全部书籍中选择，已选 {{count}} 本_other': 'Select from all books — {{count}} selected',
  '拖放电子书文件到此处': 'Drop ebook files here',
  '支持 EPUB、PDF、MOBI、TXT、FB2、CBZ/CBR、HTML、Markdown':
    'Supports EPUB, PDF, MOBI, TXT, FB2, CBZ/CBR, HTML, Markdown',
  '开始你的阅读之旅': 'Start your reading journey',
  '点击「导入」或拖放文件到此处': 'Click "Import" or drag files here',
  '导入第一本书': 'Import your first book',

  // BookCard context menu / confirm
  '已读 {{time}}': 'Read {{time}}',
  '打开': 'Open',
  '详情': 'Details',
  '添加到书柜': 'Add to shelf',
  '从书柜移除': 'Remove from shelf',
  '删除书籍': 'Delete book',
  '确定要删除《{{title}}》吗？此操作不可撤销。':
    'Are you sure you want to delete "{{title}}"? This action cannot be undone.',
  '删除': 'Delete',

  // BookList column headers
  '格式': 'Format',
  '大小': 'Size',
  '添加时间': 'Date added',

  // BookShelfPanel
  '书柜': 'Bookshelves',
  '从全部添加': 'Add from all',
  '新建书柜': 'New bookshelf',
  '书源管理': 'Book sources',
  '重命名': 'Rename',
  '请输入书柜名称...': 'Enter a bookshelf name...',
  '创建': 'Create',

  // BookDetailDialog
  '书籍详情': 'Book details',
  '语言': 'Language',
  '出版社': 'Publisher',
  '简介': 'Description',
  '文件路径': 'File path',
  '文件大小': 'File size',
  '最后阅读': 'Last read',
  '关闭': 'Close',
}
