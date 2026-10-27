import { Link } from 'react-router-dom'
import { useRuns } from '../api/runs'
import { PageTitle, Panel } from '../components/Panel'

export function RunsPage() {
  const runs = useRuns()
  return (
    <>
      <PageTitle>Runs</PageTitle>
      {runs.isError && <p role="alert" className="text-sm text-red-600">Cannot reach the JobPilot API. Is the backend running?</p>}
      {runs.data?.length === 0 && (
        <Panel><p className="text-sm text-[var(--muted)]">No runs yet. Start one from the <Link className="underline" to="/">dashboard</Link>.</p></Panel>
      )}
      <ul className="flex max-w-4xl flex-col gap-2">
        {runs.data?.map((r) => (
          <li key={r.id}>
            <Link to={`/runs/${r.id}`}
              className="block rounded-lg border border-[var(--line)] bg-[var(--panel)] p-4 hover:border-[var(--accent)]">
              <span className="font-medium">Run {r.id}</span>
              <span className="ml-3 text-sm text-[var(--muted)]">{r.status.replace('_', ' ').toLowerCase()}</span>
              <span className="block text-sm">{r.prompt ?? '(profile not built yet)'}</span>
              <span className="block text-xs text-[var(--muted)]">{new Date(r.startedAt).toLocaleString('sv-SE')} · {r.message}</span>
            </Link>
          </li>
        ))}
      </ul>
    </>
  )
}
