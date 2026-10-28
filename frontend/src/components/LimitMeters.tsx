import { type LimitWindow, useClaudeLimits } from '../api/claude'

const time = new Intl.DateTimeFormat('en-GB', { weekday: 'short', hour: '2-digit', minute: '2-digit' })

function Meter({ label, window }: { label: string; window: LimitWindow | null }) {
  if (!window) {
    return (
      <div>
        <dt className="text-xs text-[var(--muted)]">{label}</dt>
        <dd className="text-sm text-[var(--muted)]">not reported</dd>
      </div>
    )
  }
  const percent = Math.round(window.utilization * 100)
  const warn = window.utilization >= 0.9 ? 'bg-[var(--danger)]' : window.utilization >= 0.75 ? 'bg-amber-500' : 'bg-navy'
  return (
    <div>
      <dt className="text-xs text-[var(--muted)]">{label}</dt>
      <dd>
        <div role="progressbar" aria-label={label} aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100}
          className="mt-1 h-2 overflow-hidden rounded-full bg-[var(--line)]">
          <div className={`h-full ${warn}`} style={{ width: `${Math.min(100, percent)}%` }} />
        </div>
        <span className="text-lg font-semibold">{percent}% used</span>
        {window.resetsAt && <span className="ml-2 text-xs text-[var(--muted)]">resets {time.format(new Date(window.resetsAt))}</span>}
      </dd>
    </div>
  )
}

/** Session (5 hour) and weekly usage of the Claude subscription, as reported by the Claude CLI. */
export function LimitMeters() {
  const { query, refresh } = useClaudeLimits()
  const data = refresh.data ?? query.data
  const busy = query.isFetching || refresh.isPending

  return (
    <div>
      <dl className="grid grid-cols-2 gap-4 text-sm">
        <Meter label="Session (5 hours)" window={data?.limits?.session ?? null} />
        <Meter label="Week (7 days)" window={data?.limits?.week ?? null} />
      </dl>
      <div className="mt-2 flex items-center gap-3 text-xs text-[var(--muted)]">
        <button type="button" disabled={busy} onClick={() => refresh.mutate()} className="underline disabled:opacity-60">
          {busy ? 'Reading...' : 'Refresh'}
        </button>
        <span>Read from your Claude subscription through the CLI; a reading is one tiny call.</span>
      </div>
      {(data?.error || query.isError) && (
        <p className="mt-1 text-xs text-[var(--danger)]">{data?.error ?? 'Cannot reach the JobPilot API.'}</p>
      )}
    </div>
  )
}
