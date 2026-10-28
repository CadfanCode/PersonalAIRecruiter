import { useEffect, useState } from 'react'
import { useClaudeUsage } from '../../api/claude'
import { usePerformance, useUpdatePerformance } from '../../api/settings'
import { Panel } from '../Panel'

/** How many Claude calls run at once. More is faster; the limits of your Claude plan are the real ceiling. */
export function PerformancePanel() {
  const perf = usePerformance()
  const update = useUpdatePerformance()
  const usage = useClaudeUsage()
  const [value, setValue] = useState<number | null>(null)
  const current = value ?? perf.data?.claudeConcurrency ?? 4
  useEffect(() => setValue(null), [perf.data?.claudeConcurrency])

  return (
    <div className="grid gap-5">
      <Panel title="Speed">
        <p className="text-sm text-[var(--muted)]">
          Scoring ads and writing documents run several Claude calls at the same time. Raise this to go faster; lower it if Claude tells you that you are sending too many requests.
        </p>
        <div className="mt-5 flex items-center gap-4">
          <input type="range" aria-label="Calls at the same time" min={perf.data?.min ?? 1} max={perf.data?.max ?? 8} step={1} value={current}
            onChange={(e) => setValue(Number(e.target.value))}
            onPointerUp={() => value !== null && update.mutate(value)} onKeyUp={() => value !== null && update.mutate(value)}
            className="h-1.5 w-64 cursor-pointer accent-[var(--accent)]" />
          <output className="w-8 text-lg font-semibold tabular-nums">{current}</output>
          <span className="text-sm text-[var(--muted)]">at a time</span>
        </div>
        {update.isError && <p role="alert" className="mt-2 text-sm text-[var(--danger)]">{update.error.message}</p>}
      </Panel>
      <Panel title="Usage">
        {usage.isError && <p role="alert" className="text-sm text-[var(--danger)]">Cannot reach the JobPilot API.</p>}
        {usage.data && (
          <>
            <p className="mb-3 text-sm">JobPilot has made {usage.data.totals.calls} calls, {usage.data.totals.failures} failed.</p>
            <table className="w-full text-left text-sm">
              <thead className="text-xs uppercase text-[var(--muted)]"><tr><th className="py-1 font-semibold">Task</th><th className="py-1 font-semibold">Calls</th><th className="py-1 font-semibold">Tokens</th></tr></thead>
              <tbody>
                {usage.data.byTask.map((t) => (
                  <tr key={t.task} className="border-t border-[var(--line)]"><td className="py-1">{t.task}</td><td>{t.calls}</td><td>{Number(t.tokens).toLocaleString('en-GB')}</td></tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </Panel>
    </div>
  )
}
