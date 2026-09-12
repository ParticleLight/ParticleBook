// English translations for the UI layer (UpdateBanner / ErrorBoundary /
// ConfirmDialog / App / BookSourcePanel / StatisticsPage / StatisticsPanel /
// bookSourceStore). Keys are the original Chinese UI strings — i18next falls
// back to the key itself (the Chinese text) when a key has no English entry,
// so zh mode needs no dictionary. Keep keys EXACTLY as they appear in the UI
// (they double as the Chinese source), including any {{var}} placeholders.
export const enUi: Record<string, string> = {
  // UpdateBanner
  '发现新版本 v{{version}}': 'New version v{{version}} available',
  'v{{version}} 已下载，重启后自动安装': 'v{{version}} downloaded, will auto-install on restart',
  '下载中...': 'Downloading...',
  '立即重启': 'Restart Now',
  '下载更新': 'Download Update',

  // ErrorBoundary
  '出了点问题': 'Something went wrong',
  '应用遇到了意外错误，请尝试重启软件。': 'The app hit an unexpected error. Please try restarting it.',
  '重试': 'Retry',

  // ConfirmDialog
  '确认': 'Confirm',
  '取消': 'Cancel',

  // App.tsx (ZlibLoadingOverlay / ZlibFailedBanner)
  '正在连接 Z-Library...': 'Connecting to Z-Library...',
  '所有 Z-Library 镜像暂时不可达，请稍后重试或手动切换线路': 'All Z-Library mirrors are temporarily unreachable. Please retry later or switch mirrors manually.',

  // bookSourceStore
  '搜索失败': 'Search failed',

  // BookSourcePanel
  '成功导入 {{imported}} 个书源（共 {{total}} 个）': 'Imported {{imported}} of {{total}} book sources',
  '正在获取目录...': 'Fetching table of contents...',
  '下载中 {{current}}/{{total}}{{suffix}}': 'Downloading {{current}}/{{total}}{{suffix}}',
  '正在组装文件...': 'Assembling file...',
  '正在导入书架...': 'Importing to library...',
  '下载完成！': 'Download complete!',
  '下载失败: {{error}}': 'Download failed: {{error}}',
  // C++ 只发送机器可读错误码，这些是 BookSourcePanel 的映射目标
  '书源不存在或已被删除': 'Book source missing or deleted',
  '未能获取章节目录': 'Could not fetch the chapter list',
  '章节内容为空': 'Chapter content was empty',
  '写入文件失败': 'Failed to write the file',
  '导入书架失败': 'Failed to import into the library',
  '未知错误': 'Unknown error',
  '导入 JSON': 'Import JSON',
  '搜索': 'Search',
  '源管理 ({{count}})': 'Sources ({{count}})',
  '搜索书名...': 'Search book name...',
  '搜索中...': 'Searching...',
  '已成功导入书架': 'Successfully added to library',
  '找到 {{count}} 个结果_one': 'Found {{count}} result',
  '找到 {{count}} 个结果_other': 'Found {{count}} results',
  '未知作者': 'Unknown author',
  '最新: {{chapter}}': 'Latest: {{chapter}}',
  '下载': 'Download',
  '搜索出错': 'Search error',
  '未找到结果': 'No results found',
  '请检查关键词或启用更多书源': 'Check your keyword or enable more book sources',
  '输入书名开始搜索': 'Enter a book name to search',
  '当前有 {{count}} 个启用的书源_one': '{{count}} enabled book source currently',
  '当前有 {{count}} 个启用的书源_other': '{{count}} enabled book sources currently',
  '请先在"源管理"中导入并启用书源': 'Import and enable book sources under "Sources" first',
  '确定清空所有书源？此操作不可撤销。': 'Clear all book sources? This action cannot be undone.',
  '全部清空': 'Clear All',
  '暂无书源': 'No book sources',
  '点击右上角"导入 JSON"添加书源': 'Click "Import JSON" in the top-right corner to add book sources',
  '未命名': 'Unnamed',
  '启用': 'Enabled',
  '禁用': 'Disabled',

  // StatisticsPage / StatisticsPanel
  '未阅读': 'Not read',
  '总书籍': 'Total Books',
  '总阅读时间': 'Total Reading Time',
  '已开始阅读': 'Started Reading',
  '暂无书籍': 'No books yet',
}
