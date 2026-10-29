import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useDraft } from '../lib/useDraft'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import {
  type ChatTurn,
  type CvContent,
  type DocView,
  type LetterContent,
  type Report,
  useApproveDocument,
  useJobDocuments,
  useSaveDocument,
  useStartDocuments,
  useUnapproveDocument,
} from '../api/documents'
import { useApplications } from '../api/applications'
import { useJob, useRun } from '../api/runs'
import { DocChat } from '../components/documents/DocChat'
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
  // kept here and outside the screen: the editor restarts on every new version, and a trip to another page must not lose the chat
  const [turns, setTurns] = useDraft<Record<Tab, ChatTurn[]>>(`docs.turns.${jobId}`, { CV: [], LETTER: [] })
  const [tab, setTab] = useState<Tab>(search.get('tab') === 'letter' ? 'LETTER' : 'CV')
  const runId = search.get('run') ? Number(search.get('run')) : null
  const fromRun = search.get('from') ? Number(search.get('from')) : undefined // the search run the ad came from
  const docs = useJobDocuments(jobId)
  const job = useJob(jobId)
  const start = useStartDocuments()
  const apps = useApplications()
  const navigate = useNavigate()
  const selected = apps.data?.find((a) => a.jobId === jobId)
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
    const atRisk = existing.some((d) => d && d.version > 1)
    if (atRisk && !window.confirm('Regenerating replaces both documents, including your edits. Continue?')) return
    generate()
  }

  const title = job.data?.row.title
  const doc = tab === 'CV' ? docs.data?.cv : docs.data?.letter
  const anyApproved = !!(docs.data?.cv?.approved || docs.data?.letter?.approved)

  return (
    <>
      <Link to="/documents" className="mb-3 inline-flex items-center gap-1 text-sm text-[var(--accent)] hover:underline">‹ All documents</Link>
      <PageTitle subtitle={job.data?.row.employer ?? undefined}>{title ?? 'Documents'}</PageTitle>

      {runState && (
        <div className="mb-5 max-w-3xl">
          {working && (
            <div role="progressbar" aria-label="Generation progress" aria-valuenow={runState.progress} aria-valuemin={0} aria-valuemax={100}
              className="mb-2 h-2 overflow-hidden rounded-full bg-[var(--line)]">
              <div className="h-full bg-navy transition-all" style={{ width: `${runState.progress}%` }} />
            </div>
          )}
          <p role="status" className={`text-sm ${runState.status === 'FAILED' ? 'text-[var(--danger)]' : 'text-[var(--muted)]'}`}>
            {runState.message}
          </p>
        </div>
      )}
      {start.isError && <p role="alert" className="mb-3 text-sm text-[var(--danger)]">{start.error.message}</p>}

      {docs.isError && <p role="alert" className="text-sm text-[var(--danger)]">Could not load the documents.</p>}

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
                {(t === 'CV' ? docs.data?.cv : docs.data?.letter)?.approved && <span className="ml-2 text-xs text-[var(--success)]">approved</span>}
              </button>
            ))}
            <Button variant="ghost" className="mb-1 ml-auto" onClick={regenerate} disabled={start.isPending || working || anyApproved}
              title={anyApproved ? 'Unapprove the documents first: approved documents are locked' : undefined}>
              Regenerate both
            </Button>
          </div>
          {doc && <DocEditor key={`${doc.id}-${doc.version}`} doc={doc} jobId={jobId} turns={turns[tab]} onTurns={(t) => setTurns((prev) => ({ ...prev, [tab]: t }))} />}
          <div className="mt-6 max-w-3xl">
            <fieldset disabled={!!doc?.approved} className="m-0 min-w-0 border-0 p-0">
              <SelectionPanel jobId={jobId} docs={docs.data} />
            </fieldset>
          </div>
        </>
      )}
      {docs.data?.cv && docs.data.letter && (
        <div className="mt-8 flex flex-wrap items-center gap-4 rounded-2xl border border-[var(--line)] bg-[var(--panel)] p-5 shadow-[var(--shadow)]">
          <p className="min-w-0 flex-1 text-sm text-[var(--muted)]">
            {docs.data.cv.approved && docs.data.letter.approved
              ? 'Both documents are approved. Back in the overview, Apply takes the approved applications on.'
              : 'Approve the CV and the cover letter to let this application go on to Apply.'}
          </p>
          <Button size="lg" onClick={() => navigate('/documents')} title={selected ? undefined : 'Go to the overview'}>Back to all documents</Button>
        </div>
      )}
    </>
  )
}

function DocEditor({ doc, jobId, turns, onTurns }: { doc: DocView; jobId: number; turns: ChatTurn[]; onTurns: (t: ChatTurn[]) => void }) {
  const [draft, setDraft] = useDraft<CvContent | LetterContent>(`docs.draft.${doc.id}`, doc.content)
  const [refused, setRefused] = useState<Report | null>(null)
  const save = useSaveDocument(jobId)
  const approve = useApproveDocument(jobId)
  const unapprove = useUnapproveDocument(jobId)
  const [notice, setNotice] = useState<string | null>(null)
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

  const doUnapprove = () => {
    setRefused(null)
    setNotice(null)
    unapprove.mutate(doc.id, { onSuccess: (r) => setNotice(r.notice) })
  }

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(360px,2fr)_minmax(0,3fr)]">
      <div className="min-w-0">
        <div className="mb-4 flex flex-wrap items-center gap-3 rounded-2xl border border-[var(--line)] bg-[var(--panel)] px-4 py-3 shadow-[var(--shadow)]">
          <span className="text-sm font-semibold">{doc.kind === 'CV' ? 'CV' : 'Cover letter'}</span>
          <span className="text-xs text-[var(--muted)]">version {doc.version}</span>
          <Button variant="ghost" onClick={() => save.mutate({ id: doc.id, content: draft })} disabled={doc.approved || !dirty || save.isPending}>
            Save and re-render
          </Button>
          {doc.approved ? (
            <span className="ml-auto flex items-center gap-3">
              <span className="text-sm font-medium text-[var(--success)]">✓ Approved</span>
              <Button variant="ghost" onClick={doUnapprove} disabled={unapprove.isPending}
                title="Unlocks editing. The document stays exactly as it is until you change it.">
                {unapprove.isPending ? 'Unapproving…' : 'Unapprove'}
              </Button>
            </span>
          ) : (
            <Button className="ml-auto" size="lg" onClick={doApprove} disabled={dirty || approve.isPending}
              title={dirty ? 'Save your edits first' : undefined}>
              {approve.isPending ? 'Approving…' : `Approve ${doc.kind === 'CV' ? 'CV' : 'cover letter'}`}
            </Button>
          )}
          {unapprove.isError && <span role="alert" className="text-sm text-[var(--danger)]">{unapprove.error.message}</span>}
          {save.isError && <span role="alert" className="text-sm text-[var(--danger)]">{save.error.message}</span>}
          {approve.isError && !refused && <span role="alert" className="text-sm text-[var(--danger)]">{approve.error.message}</span>}
        </div>
        {doc.approved && (
          <p role="status" className="mb-3 rounded-2xl bg-[var(--success-soft)] px-4 py-2.5 text-sm text-[var(--success)]">
            Approved and locked. Press Unapprove to edit this {doc.kind === 'CV' ? 'CV' : 'letter'} again.
          </p>
        )}
        {notice && <p role="alert" className="mb-3 rounded-2xl bg-[var(--warn-soft)] px-4 py-2.5 text-sm">{notice}</p>}
        {refused && (
          <p role="alert" className="mb-3 text-sm text-[var(--danger)]">
            Not approved: fix the problems marked in red first.
          </p>
        )}
        {/* A disabled fieldset locks every control inside it: the chat, the text fields, the fact chips and the buttons. */}
        <fieldset disabled={doc.approved} className="m-0 min-w-0 border-0 p-0">
          <DocChat doc={doc} jobId={jobId} draft={draft} dirty={dirty} turns={turns} onTurns={onTurns} />
          {doc.kind === 'CV' ? (
            <CvEditor content={draft as CvContent} facts={doc.facts} issues={issues} onChange={setDraft} />
          ) : (
            <LetterEditor content={draft as LetterContent} facts={doc.facts} issues={issues} onChange={setDraft} />
          )}
        </fieldset>
        <div className="mt-5"><ReportPanel report={report} /></div>
      </div>
      <div className="min-w-0">
        {/* The browser's own PDF viewer: its page-thumbnail side bar starts closed (navpanes=0) and opens with the viewer's toggle;
            the page fills the width, so a whole A4 page is readable at normal zoom. */}
        <iframe title={`${doc.kind === 'CV' ? 'CV' : 'Cover letter'} preview`}
          src={`/api/documents/${doc.id}/pdf?v=${doc.version}#navpanes=0&view=FitH`}
          className="sticky top-4 h-[calc(100vh-2rem)] min-h-[900px] w-full rounded-lg border border-[var(--line)] bg-white" />
      </div>
    </div>
  )
}
