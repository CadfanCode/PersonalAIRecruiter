import { Link } from 'react-router-dom'
import { useRuns } from '../api/runs'
import { Panel } from './Panel'

export function RecentRuns() {
  const runs = useRuns()
  if (!runs.data || runs.data.length === 0) return null
  return (
    <Panel title="Recent runs">
      <ul className="flex flex-col gap-1.5 text-sm">
        {runs.data.slice(0, 5).map((r) => (
          <li key={r.id}>
            <Link className="text-[var(--accent)] underline" to={`/runs/${r.id}`}>Run {r.id}</Link>
            <span className="text-[var(--muted)]"> · {r.status.replace('_', ' ').toLowerCase()} · {r.options.mode === 'DISCOVER' ? 'discover roles' : r.options.mode === 'MANUAL' ? 'pasted link' : (r.prompt ?? '')}</span>
          </li>
        ))}
      </ul>
    </Panel>
  )
}
