// 全局键盘快捷键必须先问一句：用户此刻是不是在【打字】？
// 反例（真实 bug）：漫画阅读器在 window 上监听空格/方向键并 preventDefault，
// 且不看事件源 —— 于是在搜索框里【打不出空格】，每按一次还翻一页。
//
// 只把「文本输入类」算作打字：进度条是 <input type="range">，它聚焦时方向键
// 应当仍可翻页，所以不能一刀切地按 tagName 判断。
const TEXT_INPUT_TYPES = new Set(['text', 'search', 'password', 'email', 'number', 'url', 'tel', ''])

export function isTypingTarget(e: { target: EventTarget | null }): boolean {
  const el = e.target
  if (!(el instanceof HTMLElement)) return false
  if (el.isContentEditable) return true
  if (el.tagName === 'TEXTAREA') return true
  if (el.tagName === 'INPUT') return TEXT_INPUT_TYPES.has((el as HTMLInputElement).type)
  return false
}
