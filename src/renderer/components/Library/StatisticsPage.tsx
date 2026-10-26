import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'

interface StatisticsPageProps { onBack: () => void }
interface SessionRow { id: number; book_id: number; started_at: string; ended_at: string | null; duration_seconds: number }

function dayKey(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export function StatisticsPage({ onBack }: StatisticsPageProps) {
  const { t } = useTranslation()
  const [sessions, setSessions] = useState<SessionRow[]>([])
  const [books, setBooks] = useState<any[]>([])
  const [settings, setSettings] = useState<any>({})
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    Promise.all([
      window.electronAPI.getReadingSessions(),
      window.electronAPI.getBooks(),
      window.electronAPI.getSettings(),
    ]).then(([s, b, cfg]) => {
      setSessions(Array.isArray(s) ? s : [])
      setBooks(Array.isArray(b) ? b : [])
      setSettings(cfg || {})
    }).catch(() => {}).finally(() => setLoading(false))
  }, [])

  const stats = useMemo(() => {
    const daily = new Map<string, number>()
    const perBook = new Map<number, number>()
    for (const s of sessions) {
      const secs = Math.max(0, Number(s.duration_seconds) || 0)
      if (!secs) continue
      const d = new Date(s.started_at)
      if (Number.isNaN(d.getTime())) continue
      const key = dayKey(d)
      daily.set(key, (daily.get(key) || 0) + secs)
      perBook.set(s.book_id, (perBook.get(s.book_id) || 0) + secs)
    }
    const today = new Date()
    const todayKey = dayKey(today)
    const weekStart = new Date(today); weekStart.setDate(today.getDate() - ((today.getDay() + 6) % 7))
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
    const days: Array<{ key: string; minutes: number }> = []
    for (let i = 364; i >= 0; i -= 1) {
      const d = new Date(today); d.setDate(today.getDate() - i)
      const key = dayKey(d)
      days.push({ key, minutes: (daily.get(key) || 0) / 60 })
    }
    const formatTotals = new Map<string, number>()
    for (const book of books) {
      const secs = perBook.get(book.id) || 0
      if (!secs) continue
      const fmt = String(book.format || 'other')
      formatTotals.set(fmt, (formatTotals.get(fmt) || 0) + secs)
    }
    const ranking = books
      .map((b) => ({ id: b.id, title: b.title, secs: perBook.get(b.id) || 0 }))
      .filter((b) => b.secs > 0)
      .sort((a, b) => b.secs - a.secs)
      .slice(0, 10)
    return { todaySecs, weekSecs, monthSecs, totalSecs, streak, days, ranking, formatTotals }
  }, [sessions, books])

  const dailyGoal = Number(settings['reading.dailyGoalMinutes'] ?? 30) || 0
  const weeklyGoal = Number(settings['reading.weeklyGoalMinutes'] ?? 180) || 0
  const fmtMinutes = (min: number) => (min >= 60 ? t('{{h}} 小时 {{m}} 分钟', { h: Math.floor(min / 60), m: Math.round(min % 60) }) : t('{{m}} 分钟', { m: Math.round(min) }))
  const pct = (value: number, goal: number) => goal > 0 ? Math.min(100, Math.round(value / goal * 100)) : 0
  const heatColor = (minutes: number) => {
    if (minutes <= 0) return 'var(--border)'
    if (minutes < 15) return 'rgba(96,205,255,0.25)'
    if (minutes < 30) return 'rgba(96,205,255,0.45)'
    if (minutes < 60) return 'rgba(96,205,255,0.7)'
    return 'var(--accent)'
  }

  return (
    <div className="h-full flex flex-col" style={{ background: 'var(--bg)' }}>
      <div className="flex items-center gap-3 px-6 py-4" style={{ borderBottom: '1px solid var(--border)' }}>
        <button onClick={onBack} className="p-1 rounded-md transition-colors" style={{ color: 'var(--text-tertiary)' }} aria-label={t('返回')}>
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" /></svg>
        </button>
        <span className="text-lg font-semibold" style={{ color: 'var(--text-primary)' }}>{t('阅读统计')}</span>
      </div>
      <div className="flex-1 overflow-y-auto px-6 py-4">
        {loading ? <p className="text-sm opacity-60">{t('加载中...')}</p> : (
          <>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-5">
              {[[t('今日'), stats.todaySecs], [t('本周'), stats.weekSecs], [t('本月'), stats.monthSecs], [t('累计'), stats.totalSecs]].map(([label, secs]) => (
                <div key={String(label)} className="rounded-lg p-3" style={{ background: 'var(--bg-secondary)' }}>
                  <div className="text-xs opacity-60">{label}</div>
                  <div className="text-lg font-semibold mt-1">{fmtMinutes(Number(secs) / 60)}</div>
                </div>
              ))}
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3 mb-5">
              <div className="rounded-lg p-3" style={{ background: 'var(--bg-secondary)' }}>
                <div className="flex justify-between text-xs mb-1"><span>{t('今日目标')}</span><span>{Math.round(stats.todaySecs / 60)} / {dailyGoal} {t('分钟')}</span></div>
                <div className="h-1.5 rounded-full overflow-hidden" style={{ background: 'var(--border)' }}><div className="h-full" style={{ width: `${pct(stats.todaySecs / 60, dailyGoal)}%`, background: 'var(--accent)' }} /></div>
              </div>
              <div className="rounded-lg p-3" style={{ background: 'var(--bg-secondary)' }}>
                <div className="flex justify-between text-xs mb-1"><span>{t('本周目标')}</span><span>{Math.round(stats.weekSecs / 60)} / {weeklyGoal} {t('分钟')}</span></div>
                <div className="h-1.5 rounded-full overflow-hidden" style={{ background: 'var(--border)' }}><div className="h-full" style={{ width: `${pct(stats.weekSecs / 60, weeklyGoal)}%`, background: 'var(--accent)' }} /></div>
              </div>
            </div>
            <div className="flex items-center justify-between mb-2">
              <span className="text-sm font-medium">{t('最近一年')}</span>
              <span className="text-xs opacity-60">{t('连续阅读 {{n}} 天', { n: stats.streak })}</span>
            </div>
            <div className="flex gap-[2px] overflow-x-auto pb-2 mb-5" aria-label={t('阅读热力图')}>
              {Array.from({ length: 53 }, (_, w) => (
                <div key={w} className="flex flex-col gap-[2px]">
                  {Array.from({ length: 7 }, (_, d) => {
                    const item = stats.days[w * 7 + d]
                    if (!item) return <div key={d} style={{ width: 8, height: 8 }} />
                    return <div key={d} title={`${item.key}: ${Math.round(item.minutes)} min`} style={{ width: 8, height: 8, borderRadius: 2, background: heatColor(item.minutes) }} />
                  })}
                </div>
              ))}
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <h3 className="text-sm font-medium mb-2">{t('阅读最多的书')}</h3>
                {stats.ranking.length === 0 ? <p className="text-xs opacity-60">{t('暂无阅读记录')}</p> : stats.ranking.map((b) => (
                  <div key={b.id} className="flex justify-between text-xs py-1 border-b" style={{ borderColor: 'var(--border)' }}>
                    <span className="truncate pr-2">{b.title}</span><span className="opacity-70 shrink-0">{fmtMinutes(b.secs / 60)}</span>
                  </div>
                ))}
              </div>
              <div>
                <h3 className="text-sm font-medium mb-2">{t('按格式')}</h3>
                {stats.formatTotals.size === 0 ? <p className="text-xs opacity-60">{t('暂无阅读记录')}</p> : Array.from(stats.formatTotals.entries()).sort((a, b) => b[1] - a[1]).map(([fmt, secs]) => (
                  <div key={fmt} className="flex justify-between text-xs py-1 border-b" style={{ borderColor: 'var(--border)' }}>
                    <span className="uppercase">{fmt}</span><span className="opacity-70">{fmtMinutes(secs / 60)}</span>
                  </div>
                ))}
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
