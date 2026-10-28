import { Link } from 'react-router-dom'
import { useRuns } from '../api/runs'
import { PageTitle, Panel } from '../components/Panel'
import { Pill } from '../components/ui/Pill'

/** Every search so far, newest first. */
export function RunsPage() {
  const runs = useRuns()
  return (
    <>
      <PageTitle subtitle="Open one to see its results again, or to continue selecting jobs.">Past searches</PageTitle>
      {runs.isError && <p role="alert" className="text-sm text-[var(--danger)]">Cannot reach the JobPilot API. Is the backend running?</p>}
      {runs.data?.length === 0 && (
        <Panel><p className="text-sm text-[var(--muted)]">No searches yet. Start one in <Link className="text-[var(--accent)] underline" to="/">Search mode</Link>.</p></Panel>
      )}
      <ul className="flex flex-col gap-2">
        {runs.data?.map((r) => (
          <li key={r.id}>
            <Link to={`/runs/${r.id}`} className="block rounded-2xl border border-[var(--line)] bg-[var(--panel)] p-4 transition hover:border-[var(--accent)]">
              <span className="flex flex-wrap items-center gap-3">
                <span className="font-medium">
                  {r.options.mode === 'DISCOVER' ? 'Discovered roles' : r.options.mode === 'MANUAL' ? 'Pasted link' : (r.prompt ?? '(search plan not built yet)')}
                </span>
                <Pill tone={r.status === 'DONE' ? 'success' : r.status === 'FAILED' ? 'danger' : r.status === 'WAITING_FOR_USER' ? 'warn' : 'accent'}>
                  {r.status === 'WAITING_FOR_USER' ? 'waiting for you' : r.status.replace('_', ' ').toLowerCase()}
                </Pill>
                <span className="ml-auto text-xs text-[var(--muted)]">{new Date(r.startedAt).toLocaleString('sv-SE')}</span>
              </span>
              <span className="mt-1 block text-xs text-[var(--muted)]">{r.message}</span>
            </Link>
          </li>
        ))}
      </ul>
    </>
  )
}
