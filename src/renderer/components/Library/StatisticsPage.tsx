import { useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'

interface StatisticsPageProps { onBack: () => void }
interface SessionRow { id: number; book_id: number; started_at: string; ended_at: string | null; duration_seconds: number }
interface BookLite { id: number; title: string; format?: string }

function dayKey(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

// 热力图分档：0 无记录、1-4 由浅到深（四档阈值与配色见 globals.css 的 .m3-heatmap-cell）
function heatLevel(minutes: number): 0 | 1 | 2 | 3 | 4 {
  if (minutes <= 0) return 0
  if (minutes < 15) return 1
  if (minutes < 30) return 2
  if (minutes < 60) return 3
  return 4
}

interface HeatCell { key: string; minutes: number }

/**
 * 把最近一年切成「周一到周日」的列。
 * 旧实现直接从 365 天前连续铺 7 行，行不对应星期几、列也不对应自然周；这里对齐到
 * 周一，行 = 星期几、列 = 一周，并且只填到今天之后留空（未来格子透明）。
 */
function buildWeeks(today: Date, daily: Map<string, number>): Array<Array<HeatCell | null>> {
  const start = new Date(today)
  start.setHours(0, 0, 0, 0)
  start.setDate(start.getDate() - 364)
  start.setDate(start.getDate() - ((start.getDay() + 6) % 7)) // 回退到本周一
  const weeks: Array<Array<HeatCell | null>> = []
  const cursor = new Date(start)
  while (cursor <= today) {
    const week: Array<HeatCell | null> = []
    for (let i = 0; i < 7; i += 1) {
      if (cursor > today) { week.push(null); continue }
      const key = dayKey(cursor)
      week.push({ key, minutes: (daily.get(key) || 0) / 60 })
      cursor.setDate(cursor.getDate() + 1)
    }
    weeks.push(week)
  }
  return weeks
}

export function StatisticsPage({ onBack }: StatisticsPageProps) {
  const { t } = useTranslation()
  const [sessions, setSessions] = useState<SessionRow[]>([])
  const [books, setBooks] = useState<BookLite[]>([])
  const [settings, setSettings] = useState<Record<string, unknown>>({})
  const [loading, setLoading] = useState(true)
  const [covers, setCovers] = useState<Record<number, string | null>>({})

  useEffect(() => {
    Promise.all([
      window.electronAPI.getReadingSessions(),
      window.electronAPI.getBooks(),
      window.electronAPI.getSettings(),
    ]).then(([s, b, cfg]) => {
      setSessions(Array.isArray(s) ? s : [])
      setBooks(Array.isArray(b) ? b as BookLite[] : [])
      setSettings((cfg || {}) as Record<string, unknown>)
    }).catch(() => {}).finally(() => setLoading(false))
  }, [])

  const stats = useMemo(() => {
    const daily = new Map<string, number>()
    const perBook = new Map<number, number>()
    // 悬停明细用：某一天里「每本书读了多久」（day → bookId → 秒）
    const perDayBook = new Map<string, Map<number, number>>()
    for (const s of sessions) {
      const secs = Math.max(0, Number(s.duration_seconds) || 0)
      if (!secs) continue
      const d = new Date(s.started_at)
      if (Number.isNaN(d.getTime())) continue
      const key = dayKey(d)
      daily.set(key, (daily.get(key) || 0) + secs)
      perBook.set(s.book_id, (perBook.get(s.book_id) || 0) + secs)
      let dayMap = perDayBook.get(key)
      if (!dayMap) { dayMap = new Map<number, number>(); perDayBook.set(key, dayMap) }
      dayMap.set(s.book_id, (dayMap.get(s.book_id) || 0) + secs)
    }
    const today = new Date()
    const todayKey = dayKey(today)
    const weekStart = new Date(today); weekStart.setDate(today.getDate() - ((today.getDay() + 6) % 7)); weekStart.setHours(0, 0, 0, 0)
    const monthStart = new Date(today.getFullYear(), today.getMonth(), 1)
    let todaySecs = 0, weekSecs = 0, monthSecs = 0, totalSecs = 0
    for (const [key, secs] of daily) {
      totalSecs += secs
      const dt = new Date(key + 'T00:00:00')
      if (key === todayKey) todaySecs += secs
      if (dt >= weekStart) weekSecs += secs
      if (dt >= monthStart) monthSecs += secs
    }
    let streak = 0
    const cursor = new Date(today)
    for (;;) {
      const key = dayKey(cursor)
      if ((daily.get(key) || 0) > 0) { streak += 1; cursor.setDate(cursor.getDate() - 1) } else break
    }
    const weeks = buildWeeks(today, daily)
    const ranking = books
      .map((b) => ({
        id: b.id,
        title: b.title,
        format: String(b.format || ''),
        secs: perBook.get(b.id) || 0,
      }))
      .filter((b) => b.secs > 0)
      .sort((a, b) => b.secs - a.secs)
      .slice(0, 10)
    const titleOf = new Map<number, string>()
    for (const b of books) titleOf.set(b.id, b.title)
    return { todaySecs, weekSecs, monthSecs, totalSecs, streak, weeks, ranking, perDayBook, titleOf }
  }, [sessions, books])

  // 排行榜封面：与书库卡片同一个桥接调用（getCoverImage 返回 data URL 或 null），
  // 一次并发取完前 10 本；拿不到就退化成格式角标。
  useEffect(() => {
    const ids = stats.ranking.map((r) => r.id)
    if (ids.length === 0) return
    let alive = true
    Promise.all(ids.map((id) => window.electronAPI.getCoverImage(id).catch(() => null)))
      .then((urls) => {
        if (!alive) return
        const next: Record<number, string | null> = {}
        ids.forEach((id, index) => { next[id] = urls[index] ?? null })
        setCovers(next)
      })
    return () => { alive = false }
  }, [stats.ranking])

  // 热力图悬停明细：格子中心 + 上下沿，明细用 portal + fixed 定位（不会被卡片或滚动
  // 容器裁掉），pointer-events: none 避免鼠标移进提示框后闪烁。
  const [hoverCell, setHoverCell] = useState<{ key: string; left: number; right: number; top: number } | null>(null)
  const hoverTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => () => { if (hoverTimerRef.current) clearTimeout(hoverTimerRef.current) }, [])
  const showCellDetail = (key: string, el: HTMLElement) => {
    const rect = el.getBoundingClientRect()
    if (hoverTimerRef.current) { clearTimeout(hoverTimerRef.current); hoverTimerRef.current = null }
    // 40ms 去抖：快速扫过整片网格时不会疯狂重渲染
    hoverTimerRef.current = setTimeout(() => {
      setHoverCell({ key, left: rect.left, right: rect.right, top: rect.top })
    }, 40)
  }
  const hideCellDetail = () => {
    if (hoverTimerRef.current) { clearTimeout(hoverTimerRef.current); hoverTimerRef.current = null }
    setHoverCell(null)
  }
  const hoverDetail = useMemo(() => {
    if (!hoverCell) return null
    const dayMap = stats.perDayBook.get(hoverCell.key)
    const rows = dayMap
      ? Array.from(dayMap.entries())
          .map(([bookId, secs]) => ({ bookId, title: stats.titleOf.get(bookId) || '#' + bookId, secs }))
          .sort((a, b) => b.secs - a.secs)
      : []
    const totalSecs = rows.reduce((sum, row) => sum + row.secs, 0)
    return { rows, totalSecs }
  }, [hoverCell, stats.perDayBook, stats.titleOf])

  const dailyGoal = Number(settings['reading.dailyGoalMinutes'] ?? 30) || 0
  const weeklyGoal = Number(settings['reading.weeklyGoalMinutes'] ?? 180) || 0
  const fmtMinutes = (min: number) => (min >= 60
    ? t('{{h}} 小时 {{m}} 分钟', { h: Math.floor(min / 60), m: Math.round(min % 60) })
    : t('{{m}} 分钟', { m: Math.round(min) }))
  const pct = (value: number, goal: number) => goal > 0 ? Math.min(100, Math.round(value / goal * 100)) : 0

  const metricCards: Array<[string, number]> = [
    [t('今日'), stats.todaySecs],
    [t('本周'), stats.weekSecs],
    [t('本月'), stats.monthSecs],
    [t('累计'), stats.totalSecs],
  ]
  const goalCards: Array<[string, number, number]> = [
    [t('今日目标'), stats.todaySecs / 60, dailyGoal],
    [t('本周目标'), stats.weekSecs / 60, weeklyGoal],
  ]

  return (
    <div className="h-full flex flex-col" style={{ background: 'var(--md-sys-color-surface)' }}>
      <div className="flex items-center gap-3 px-6 py-4" style={{ borderBottom: '1px solid var(--md-sys-color-outline-variant)' }}>
        <button onClick={onBack} className="m3-icon-button" aria-label={t('返回')}>
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" /></svg>
        </button>
        <span className="m3-title-large">{t('阅读统计')}</span>
      </div>

      <div className="flex-1 overflow-y-auto">
        <div className="mx-auto w-full max-w-[1500px] px-6 py-5">
          {loading ? <p className="m3-body-medium m3-supporting-text">{t('加载中...')}</p> : (
            <>
              {/* 总量：今日 / 本周 / 本月 / 累计 */}
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
                {metricCards.map(([label, secs]) => (
                  <div key={label} className="m3-card p-4">
                    <div className="m3-label-medium">{label}</div>
                    <div className="m3-title-large mt-1">{fmtMinutes(secs / 60)}</div>
                  </div>
                ))}
              </div>

              {/* 目标进度 */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3 mb-4">
                {goalCards.map(([label, minutes, goal]) => (
                  <div key={label} className="m3-card p-4">
                    <div className="flex justify-between m3-label-medium mb-2">
                      <span>{label}</span>
                      <span>{Math.round(minutes)} / {goal} {t('分钟')}</span>
                    </div>
                    <div className="m3-linear-progress" role="progressbar" aria-valuenow={pct(minutes, goal)} aria-valuemin={0} aria-valuemax={100}>
                      <span style={{ width: `${pct(minutes, goal)}%` }} />
                    </div>
                  </div>
                ))}
              </div>

              {/* 最近一年热力图：铺满宽度（列 = 周、行 = 星期几），图例在右上 */}
              <div className="m3-card p-4 mb-4">
                <div className="flex items-start justify-between gap-4 mb-3">
                  <div>
                    <h3 className="m3-title-small">{t('最近一年')}</h3>
                    <p className="m3-body-small mt-1">{t('连续阅读 {{n}} 天', { n: stats.streak })}</p>
                  </div>
                  <div className="flex items-center gap-1.5 m3-label-small shrink-0">
                    <span>{t('少')}</span>
                    {[0, 1, 2, 3, 4].map((level) => (
                      <span key={level} className="m3-heatmap-cell" data-level={level} style={{ width: 12, height: 12 }} />
                    ))}
                    <span>{t('多')}</span>
                  </div>
                </div>
                <div
                  className="grid grid-flow-col auto-cols-fr grid-rows-7 gap-[3px]"
                  role="img"
                  aria-label={t('阅读热力图')}
                >
                  {stats.weeks.flatMap((week, wi) => week.map((cell, di) => (
                    cell ? (
                      <div
                        key={cell.key}
                        className="m3-heatmap-cell aspect-square w-full"
                        data-level={heatLevel(cell.minutes)}
                        onMouseEnter={(e) => showCellDetail(cell.key, e.currentTarget)}
                        onMouseLeave={hideCellDetail}
                      />
                    ) : (
                      <div key={`${wi}-${di}`} className="aspect-square w-full" />
                    )
                  )))}
                </div>
                {hoverCell && hoverDetail && createPortal(
                  <div
                    className="m3-menu fixed z-[80] px-3 py-2.5 pointer-events-none"
                    style={{
                      left: Math.min(Math.max(hoverCell.left + (hoverCell.right - hoverCell.left) / 2, 150), Math.max(150, window.innerWidth - 150)),
                      top: Math.max(8, hoverCell.top - 8),
                      transform: 'translate(-50%, -100%)',
                      minWidth: 240,
                      maxWidth: 320,
                    }}
                    role="status"
                    data-stat-day-detail={hoverCell.key}
                  >
                    <div className="flex items-baseline justify-between gap-3 mb-1.5">
                      <span className="m3-label-large">{hoverCell.key}</span>
                      <span className="m3-label-small">{hoverDetail.totalSecs > 0 ? fmtMinutes(hoverDetail.totalSecs / 60) : t('无阅读记录')}</span>
                    </div>
                    {hoverDetail.rows.length === 0 ? (
                      <p className="m3-body-small">{t('这天没有阅读记录')}</p>
                    ) : hoverDetail.rows.slice(0, 6).map((row) => (
                      <div key={row.bookId} className="flex items-baseline justify-between gap-3 py-0.5">
                        <span className="m3-body-small truncate" title={row.title}>{row.title}</span>
                        <span className="m3-label-small shrink-0">{fmtMinutes(row.secs / 60)}</span>
                      </div>
                    ))}
                    {hoverDetail.rows.length > 6 && (
                      <p className="m3-label-small mt-1">{t('还有 {{n}} 本…', { n: hoverDetail.rows.length - 6 })}</p>
                    )}
                  </div>,
                  document.body,
                )}
              </div>

              {/* 阅读最多的书：封面 + 书名 + 时长（前 10，宽屏两列） */}
              <div className="m3-card p-4">
                <h3 className="m3-title-small mb-2">{t('阅读最多的书')}</h3>
                {stats.ranking.length === 0 ? (
                  <p className="m3-body-medium m3-supporting-text py-2">{t('暂无阅读记录')}</p>
                ) : (
                  <div className="grid grid-cols-1 lg:grid-cols-2 gap-x-6">
                    {stats.ranking.map((book, index) => {
                      const cover = covers[book.id] || null
                      return (
                        <div
                          key={book.id}
                          className="flex items-center gap-3 py-2"
                          style={{ borderBottom: '1px solid var(--md-sys-color-outline-variant)' }}
                          data-stat-book={book.id}
                        >
                          <span className="w-5 shrink-0 text-center m3-label-medium" style={{ color: 'var(--md-sys-color-on-surface-variant)' }}>{index + 1}</span>
                          <div className="w-9 h-12 shrink-0 overflow-hidden" style={{ borderRadius: 'var(--md-sys-shape-xs)', background: 'var(--md-sys-color-surface-container-highest)' }}>
                            {cover ? (
                              <img src={cover} alt="" className="w-full h-full object-cover" />
                            ) : (
                              <span className="w-full h-full flex items-center justify-center m3-label-small" style={{ color: 'var(--md-sys-color-on-surface-variant)' }}>
                                {(book.format || '?').toUpperCase().slice(0, 4)}
                              </span>
                            )}
                          </div>
                          <div className="min-w-0 flex-1">
                            <p className="m3-body-medium truncate" title={book.title}>{book.title}</p>
                            {book.format ? <p className="m3-body-small uppercase">{book.format}</p> : null}
                          </div>
                          <span className="m3-label-medium shrink-0">{fmtMinutes(book.secs / 60)}</span>
                        </div>
                      )
                    })}
                  </div>
                )}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  )
}