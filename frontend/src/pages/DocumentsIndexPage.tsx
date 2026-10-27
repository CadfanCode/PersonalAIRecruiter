import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { type Application, isGenerating, MODE_LABEL, useApplications, useCreateDocuments } from '../api/applications'
import { useDocOverview } from '../api/documents'
import { Button } from '../components/Form'
import { PageTitle, Panel } from '../components/Panel'

const docLine = (a: Application) =>
  [a.cv ? `CV ${a.cv.approved ? 'approved' : 'to review'}` : null, a.letter ? `letter ${a.letter.approved ? 'approved' : 'to review'}` : null]
    .filter(Boolean)
    .join(' · ')

export function DocumentsIndexPage() {
  const docs = useDocOverview()
  const apps = useApplications()
  const retry = useCreateDocuments()
  const navigate = useNavigate()
  const [params] = useSearchParams()

  const selected = apps.data ?? []
  const selectedIds = new Set(selected.map((a) => a.jobId))
  const others = (docs.data ?? []).filter((d) => !selectedIds.has(d.jobId))
  const generating = selected.filter(isGenerating).length
  const ready = selected.filter((a) => a.cv && a.letter).length
  const failed = selected.filter((a) => a.generation?.status === 'FAILED' && !(a.cv && a.letter)).length

  return (
    <>
      <PageTitle>Documents</PageTitle>
      {(docs.isError || apps.isError) && <p role="alert" className="text-sm text-red-600">Cannot reach the JobPilot API. Is the backend running?</p>}

      {selected.length > 0 && (
        <section aria-label="Documents for your selected jobs" className="mb-8 max-w-4xl">
          <div className="mb-3 flex flex-wrap items-center gap-3 rounded-lg border border-[var(--accent)] bg-[var(--panel)] px-4 py-3">
            <p role="status" className="text-sm">
              {generating > 0
                ? `Writing documents: ${generating} of ${selected.length} still in progress`
                : params.get('new')
                  ? `Done: ${ready} of ${selected.length} selected jobs have a CV and a cover letter${failed ? `, ${failed} failed` : ''}`
                  : `${ready} of ${selected.length} selected jobs have a CV and a cover letter${failed ? `, ${failed} failed` : ''}`}
            </p>
            <Button className="ml-auto" disabled={ready === 0} onClick={() => navigate('/finalize')}
              title={ready === 0 ? 'Wait for at least one set of documents' : 'Review and fill in the applications'}>
              Continue to Finalize Applications
            </Button>
          </div>
          <ul className="flex flex-col gap-2">
            {selected.map((a) => (
              <li key={a.jobId} className="rounded-lg border border-[var(--line)] bg-[var(--panel)] p-4">
                <div className="flex flex-wrap items-center gap-3">
                  <Link to={`/documents/job/${a.jobId}`} className="font-medium hover:underline">{a.title}</Link>
                  <span className="text-sm text-[var(--muted)]">{a.employer}</span>
                  <span className="ml-auto text-xs text-[var(--muted)]">
                    {MODE_LABEL[a.searchMode]}{a.roleType ? ` · ${a.roleType}` : ''}
                  </span>
                </div>
                {isGenerating(a) && a.generation && (
                  <div className="mt-2">
                    <div role="progressbar" aria-label={`Progress: ${a.title}`} aria-valuenow={a.generation.progress} aria-valuemin={0} aria-valuemax={100}
                      className="h-1.5 overflow-hidden rounded-full bg-[var(--line)]">
                      <div className="h-full bg-navy transition-all" style={{ width: `${a.generation.progress}%` }} />
                    </div>
                    <p className="mt-1 text-xs text-[var(--muted)]">{a.generation.message}</p>
                  </div>
                )}
                {!isGenerating(a) && a.generation?.status === 'FAILED' && !(a.cv && a.letter) && (
                  <div className="mt-2 flex flex-wrap items-center gap-3">
                    <p role="alert" className="text-sm text-red-600">Failed: {a.generation.message}</p>
                    <Button variant="ghost" disabled={retry.isPending}
                      onClick={() => retry.mutate({ jobIds: [a.jobId], runId: a.runId ?? undefined })}>
                      Try again
                    </Button>
                  </div>
                )}
                {!isGenerating(a) && (a.cv || a.letter) && (
                  <p className="mt-1 text-xs text-[var(--muted)]">
                    {docLine(a)}
                    {a.generation?.status === 'DONE' && a.generation.message?.includes('problems') ? ' · has problems to fix before approval' : ''}
                  </p>
                )}
              </li>
            ))}
          </ul>
          {retry.isError && <p role="alert" className="mt-2 text-sm text-red-600">{retry.error.message}</p>}
        </section>
      )}

      {selected.length === 0 && docs.data?.length === 0 && (
        <Panel>
          <p className="text-sm text-[var(--muted)]">
            No documents yet. Select ads in the <Link className="underline" to="/runs">results</Link> and choose "Create tailored documents".
          </p>
        </Panel>
      )}

      {others.length > 0 && (
        <>
          {selected.length > 0 && <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-[var(--muted)]">Other documents</h2>}
          <ul className="flex max-w-4xl flex-col gap-2">
            {others.map((d) => (
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
      )}
    </>
  )
}
