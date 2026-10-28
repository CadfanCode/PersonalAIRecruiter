import type { ReactNode } from 'react'

/** "About 2 minutes left", "About 40 seconds left", or null when there is no estimate yet. */
export function etaText(seconds: number | null | undefined): string | null {
  if (seconds === null || seconds === undefined || seconds < 0) return null
  if (seconds < 10) return 'A few seconds left'
  if (seconds < 90) return `About ${Math.round(seconds / 5) * 5} seconds left`
  return `About ${Math.round(seconds / 60)} minutes left`
}

const R = 52
const C = 2 * Math.PI * R

/**
 * The processing view of a stage: a ring with the percentage, what is happening now, the time left, and room for what is
 * already there (results that appear while the run goes on).
 */
export function Processing({ title, percent, message, etaSeconds, children, onCancel }: {
  title: string
  percent: number
  message?: string | null
  etaSeconds?: number | null
  children?: ReactNode
  onCancel?: () => void
}) {
  const pct = Math.max(0, Math.min(100, Math.round(percent)))
  const eta = etaText(etaSeconds)
  return (
    <section aria-label={title} className="animate-rise flex items-center gap-7 rounded-3xl border border-[var(--line)] bg-[var(--panel)] p-7 shadow-[var(--shadow)]">
      <div role="progressbar" aria-label={`${title} progress`} aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100} className="relative h-32 w-32 shrink-0">
        <svg viewBox="0 0 120 120" className="h-full w-full -rotate-90">
          <circle cx="60" cy="60" r={R} fill="none" stroke="var(--line)" strokeWidth="8" />
          <circle cx="60" cy="60" r={R} fill="none" stroke="var(--accent)" strokeWidth="8" strokeLinecap="round"
            strokeDasharray={C} strokeDashoffset={C * (1 - pct / 100)} style={{ transition: 'stroke-dashoffset .6s cubic-bezier(.2,.8,.2,1)' }} />
        </svg>
        <span className="absolute inset-0 flex items-center justify-center text-3xl font-semibold tabular-nums tracking-tight">{pct}%</span>
      </div>
      <div className="min-w-0 flex-1">
        <h2 className="text-xl font-semibold tracking-tight">{title}</h2>
        {message && <p role="status" className="mt-1 text-[15px] text-[var(--muted)]">{message}</p>}
        <div className="mt-4 h-1.5 overflow-hidden rounded-full bg-[var(--line)]">
          <div className="shimmer h-full rounded-full" style={{ width: `${Math.max(pct, 6)}%` }} />
        </div>
        <div className="mt-2 flex items-center gap-3 text-sm text-[var(--muted)]">
          <span>{eta ?? 'Estimating the time left…'}</span>
          {onCancel && (
            <button type="button" onClick={onCancel} className="ml-auto rounded-full px-3 py-1 text-[var(--danger)] transition hover:bg-[var(--danger-soft)]">
              Cancel
            </button>
          )}
        </div>
        {children && <div className="mt-4">{children}</div>}
      </div>
    </section>
  )
}
