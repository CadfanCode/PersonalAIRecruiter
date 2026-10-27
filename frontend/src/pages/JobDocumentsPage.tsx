import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import {
  type CvContent,
  type DocView,
  type LetterContent,
  type Report,
  useApproveDocument,
  useJobDocuments,
  useSaveDocument,
  useStartDocuments,
} from '../api/documents'
import { useJob, useRun } from '../api/runs'
import { CvEditor } from '../components/documents/CvEditor'
import { groupIssues } from '../components/documents/issues'
import { LetterEditor } from '../components/documents/LetterEditor'
import { ReportPanel } from '../components/documents/ReportPanel'
import { SelectionPanel } from '../components/documents/SelectionPanel'
import { Button } from '../components/Form'
import { PageTitle, Panel } from '../components/Panel'

type Tab = 'CV' | 'LETTER'

export function JobDocumentsPage() {
  const jobId = Number(useParams().jobId)
  const [search, setSearch] = useSearchParams()
  const [tab, setTab] = useState<Tab>('CV')
  const runId = search.get('run') ? Number(search.get('run')) : null
  const fromRun = search.get('from') ? Number(search.get('from')) : undefined // the search run the ad came from
  const docs = useJobDocuments(jobId)
  const job = useJob(jobId)
  const start = useStartDocuments()
  const client = useQueryClient()
  const run = useRun(runId ?? 0, runId !== null) // no run to follow, no request
  const runState = runId !== null ? run.data?.run : undefined
  const working = runState?.status === 'QUEUED' || runState?.status === 'RUNNING'

  // Fetch the new documents once the generation run ends.
  useEffect(() => {
    if (runState && !working) void client.invalidateQueries({ queryKey: ['job-documents', jobId] })
  }, [runState, working, client, jobId])

  const generate = () =>
    start.mutate({ jobId, runId: fromRun }, { onSuccess: ({ runId: id }) => setSearch({ run: String(id) }) })

  // Regenerating replaces both documents, so ask first when that would throw away edits or an approval.
  const regenerate = () => {
    const existing = [docs.data?.cv, docs.data?.letter]
    const atRisk = existing.some((d) => d && (d.approved || d.version > 1))
    if (atRisk && !window.confirm('Regenerating replaces both documents, including your edits and approvals. Continue?')) return
    generate()
  }

  const title = job.data?.row.title
  const doc = tab === 'CV' ? docs.data?.cv : docs.data?.letter

  return (
    <>
      <PageTitle>Documents{title ? `: ${title}` : ''}</PageTitle>
      {job.data?.row.employer && <p className="-mt-3 mb-4 text-sm text-[var(--muted)]">{job.data.row.employer}</p>}

      {runState && (
        <div className="mb-5 max-w-3xl">
          {working && (
            <div role="progressbar" aria-label="Generation progress" aria-valuenow={runState.progress} aria-valuemin={0} aria-valuemax={100}
              className="mb-2 h-2 overflow-hidden rounded-full bg-[var(--line)]">
              <div className="h-full bg-navy transition-all" style={{ width: `${runState.progress}%` }} />
            </div>
          )}
          <p role="status" className={`text-sm ${runState.status === 'FAILED' ? 'text-red-600' : 'text-[var(--muted)]'}`}>
            {runState.message}
          </p>
        </div>
      )}
      {start.isError && <p role="alert" className="mb-3 text-sm text-red-600">{start.error.message}</p>}

      {docs.isError && <p role="alert" className="text-sm text-red-600">Could not load the documents.</p>}

      {docs.data && !docs.data.cv && !docs.data.letter && !working && (
        <Panel>
          <p className="mb-3 text-sm text-[var(--muted)]">No documents yet for this job.</p>
          <Button onClick={generate} disabled={start.isPending}>Generate documents</Button>
        </Panel>
      )}

      {docs.data && (docs.data.cv || docs.data.letter) && (
        <>
          <div role="tablist" className="mb-4 flex items-center gap-1 border-b border-[var(--line)]">
            {(['CV', 'LETTER'] as Tab[]).map((t) => (
              <button key={t} role="tab" aria-selected={tab === t} onClick={() => setTab(t)}
                className={`-mb-px border-b-2 px-3 py-2 text-sm font-medium ${tab === t ? 'border-[var(--accent)] text-[var(--accent)]' : 'border-transparent text-[var(--muted)]'}`}>
                {t === 'CV' ? 'CV' : 'Cover letter'}
                {(t === 'CV' ? docs.data?.cv : docs.data?.letter)?.approved && <span className="ml-2 text-xs text-emerald-600">approved</span>}
              </button>
            ))}
            <Button variant="ghost" className="mb-1 ml-auto" onClick={regenerate} disabled={start.isPending || working}>
              Regenerate both
            </Button>
          </div>
          {doc && <DocEditor key={`${doc.id}-${doc.version}`} doc={doc} jobId={jobId} />}
          <div className="mt-6 max-w-3xl">
            <SelectionPanel jobId={jobId} docs={docs.data} />
          </div>
        </>
      )}
      <p className="mt-6 text-sm"><Link className="underline" to="/documents">All documents</Link></p>
    </>
  )
}

function DocEditor({ doc, jobId }: { doc: DocView; jobId: number }) {
  const [draft, setDraft] = useState<CvContent | LetterContent>(doc.content)
  const [refused, setRefused] = useState<Report | null>(null)
  const save = useSaveDocument(jobId)
  const approve = useApproveDocument(jobId)
  const dirty = JSON.stringify(draft) !== JSON.stringify(doc.content)
  const report = refused ?? doc.report
  const issues = groupIssues(report?.violations)

  const doApprove = () => {
    setRefused(null)
    approve.mutate(doc.id, {
      onError: (e) => {
        if (e instanceof ApiError && e.status === 409) setRefused((e.body as { report: Report }).report)
      },
    })
  }

  return (
    <div className="grid grid-cols-2 gap-6">
      <div className="min-w-0">
        <div className="mb-4 flex flex-wrap items-center gap-3">
          <Button onClick={() => save.mutate({ id: doc.id, content: draft })} disabled={!dirty || save.isPending}>
            Save and re-render
          </Button>
          <Button variant="ghost" onClick={doApprove} disabled={doc.approved || dirty || approve.isPending}
            title={dirty ? 'Save your edits first' : undefined}>
            {doc.approved ? 'Approved' : 'Approve'}
          </Button>
          <span className="text-xs text-[var(--muted)]">version {doc.version}</span>
          {save.isError && <span role="alert" className="text-sm text-red-600">{save.error.message}</span>}
          {approve.isError && !refused && <span role="alert" className="text-sm text-red-600">{approve.error.message}</span>}
        </div>
        {refused && (
          <p role="alert" className="mb-3 text-sm text-red-600">
            Not approved: fix the problems marked in red first.
          </p>
        )}
        {doc.kind === 'CV' ? (
          <CvEditor content={draft as CvContent} facts={doc.facts} issues={issues} onChange={setDraft} />
        ) : (
          <LetterEditor content={draft as LetterContent} facts={doc.facts} issues={issues} onChange={setDraft} />
        )}
        <div className="mt-5"><ReportPanel report={report} /></div>
      </div>
      <div className="min-w-0">
        <iframe title={`${doc.kind === 'CV' ? 'CV' : 'Cover letter'} preview`} src={`/api/documents/${doc.id}/pdf?v=${doc.version}`}
          className="sticky top-4 h-[85vh] w-full rounded-lg border border-[var(--line)] bg-white" />
      </div>
    </div>
  )
}
