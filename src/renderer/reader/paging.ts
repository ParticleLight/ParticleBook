// ── 统一翻页（2026-10-28 重构）────────────────────────────────────────
// 旧的翻页是好几套机制叠在一起：store 里的 turnPageDelta（同值不触发 effect）、
// 每个渲染器各自监听键盘 / 点击、父层点击 + iframe 点击转发、浮动箭头 + 2 秒
// 计时器 + pointer-events 隐藏。任何一环错位都表现为「点翻页没反应」，很难定位。
//
// 新模型只有一层：
//   * 渲染器注册自己的「翻页器」（next/prev，可返回 Promise）；
//   * ReaderView 的统一输入层（键盘 / 点击区域 / 滚轮 / 两侧箭头）只调
//     paging.next() / paging.prev()；
//   * 请求进 FIFO 队列串行执行 —— 连点几下就翻几页，上一页还在渲染时也不丢；
//   * 没有渲染器注册（加载失败等）时静默忽略。

export interface ReaderPager {
  next: () => void | Promise<void>
  prev: () => void | Promise<void>
}

export type PagingZone = 'prev' | 'page' | 'next'

export interface PagingApi {
  /** 渲染器挂载时注册，卸载时传 null。 */
  register: (pager: ReaderPager | null) => void
  next: () => void
  prev: () => void
  /** 内容区点击：左 30% 上一页 / 右 30% 下一页（中间不翻）。 */
  click: (clientX: number) => void
  /** 仅供测试 / 调试。 */
  zoneOf: (clientX: number) => PagingZone
  isBusy: () => boolean
}

const MAX_QUEUE = 16
const LEFT_ZONE = 0.3
const RIGHT_ZONE = 0.7

export function createPagingApi(): PagingApi {
  let pager: ReaderPager | null = null
  const queue: Array<1 | -1> = []
  let running = false

  const zoneOf = (clientX: number): PagingZone => {
    const width = window.innerWidth || document.documentElement.clientWidth
    if (!width) return 'page'
    const ratio = clientX / width
    if (ratio < LEFT_ZONE) return 'prev'
    if (ratio > RIGHT_ZONE) return 'next'
    return 'page'
  }

  const drain = async () => {
    if (running) return
    running = true
    try {
      while (queue.length > 0) {
        const current = pager
        if (!current) { queue.length = 0; break }
        const dir = queue.shift() as 1 | -1
        try {
          if (dir > 0) await current.next()
          else await current.prev()
        } catch {
          // epub.js 在章节/书首尾会 reject；PDF/漫画在边界处自己夹紧。都当无事发生。
        }
      }
    } finally {
      running = false
    }
  }

  const enqueue = (dir: 1 | -1) => {
    if (!pager) return
    if (queue.length >= MAX_QUEUE) queue.shift()
    queue.push(dir)
    void drain()
  }

  return {
    register(next) { pager = next; queue.length = 0 },
    next: () => enqueue(1),
    prev: () => enqueue(-1),
    click(clientX) {
      const zone = zoneOf(clientX)
      if (zone === 'prev') enqueue(-1)
      else if (zone === 'next') enqueue(1)
    },
    zoneOf,
    isBusy: () => running,
  }
}
