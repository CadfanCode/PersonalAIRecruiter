import { Link } from 'react-router-dom'
import { useDocOverview } from '../api/documents'
import { PageTitle, Panel } from '../components/Panel'

export function DocumentsIndexPage() {
  const docs = useDocOverview()
  return (
    <>
      <PageTitle>Documents</PageTitle>
      {docs.isError && <p role="alert" className="text-sm text-red-600">Cannot reach the JobPilot API. Is the backend running?</p>}
      {docs.data?.length === 0 && (
        <Panel>
          <p className="text-sm text-[var(--muted)]">
            No documents yet. Open an ad in a <Link className="underline" to="/runs">run</Link> and choose "Generate documents" (or press g on a row).
          </p>
        </Panel>
      )}
      <ul className="flex max-w-4xl flex-col gap-2">
        {docs.data?.map((d) => (
          <li key={d.jobId}>
            <Link to={`/documents/job/${d.jobId}`} className="block rounded-lg border border-[var(--line)] bg-[var(--panel)] p-4 hover:border-[var(--accent)]">
              <span className="font-medium">{d.title}</span>
              <span className="ml-3 text-sm text-[var(--muted)]">{d.employer}</span>
              <span className="block text-xs text-[var(--muted)]">
                {d.language === 'sv' ? 'Swedish' : 'English'} · CV {d.cvApproved ? 'approved' : 'to review'} · letter {d.letterApproved ? 'approved' : 'to review'}
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </>
  )
}
