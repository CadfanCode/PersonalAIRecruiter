import { useClaudeHealth, useClaudeUsage } from '../api/claude'
import { LimitMeters } from './LimitMeters'
import { Panel } from './Panel'

export function ClaudeHealthCard() {
  const health = useClaudeHealth()
  const usage = useClaudeUsage()
  const result = health.data

  return (
    <Panel title="Claude Code">
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={() => void health.refetch()}
          disabled={health.isFetching}
          className="rounded-md bg-navy px-3 py-1.5 text-sm font-medium text-white hover:bg-navy-soft disabled:opacity-60"
        >
          {health.isFetching ? 'Checking...' : 'Check Claude'}
        </button>
        <span className="text-xs text-[var(--muted)]">Runs the local CLI once with a tiny prompt.</span>
      </div>

      {result && (
        <p role="status" className={`mt-3 text-sm ${result.ok ? 'text-emerald-600' : 'text-red-600'}`}>
          {result.ok
            ? `ok: ${result.version} (${(result.durationMs / 1000).toFixed(1)} s)`
            : `Not working: ${result.error ?? 'unknown error'}`}
        </p>
      )}
      {health.isError && (
        <p role="alert" className="mt-3 text-sm text-red-600">
          Cannot reach the JobPilot API. Is the backend running?
        </p>
      )}

      <div className="mt-4">
        <LimitMeters />
      </div>
      {usage.data && (
        <dl className="mt-4 grid grid-cols-3 gap-4 text-sm">
          <Stat label="JobPilot calls" value={String(usage.data.totals.calls)} />
          <Stat label="Failures" value={String(usage.data.totals.failures)} />
        </dl>
      )}
    </Panel>
  )
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs text-[var(--muted)]">{label}</dt>
      <dd className="text-lg font-semibold">{value}</dd>
    </div>
  )
}
