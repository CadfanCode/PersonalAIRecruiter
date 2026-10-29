import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  type Application,
  type ApprovalResult,
  isGenerating,
  MODE_LABEL,
  useApplications,
  useApplyApproved,
  useApproveAll,
  useCreateDocuments,
  useDeleteApplications,
} from '../api/applications'
import { useDocOverview } from '../api/documents'
import { enableSystemNotifications } from '../components/apply/notifications'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { Button } from '../components/Form'
import { clearDraftsWithPrefix } from '../lib/useDraft'
import { PageTitle, Panel } from '../components/Panel'
import { ApprovalTick } from '../components/ui/ApprovalTick'
import { ArrowRightIcon, CheckIcon } from '../components/ui/Icons'
import { Pill } from '../components/ui/Pill'
import { Processing } from '../components/ui/Processing'

/** Stage 3, Documents: one row per selected job, the two approvals, and what goes on to the Apply stage. */
export function DocumentsIndexPage() {
  const docs = useDocOverview()
  const apps = useApplications()
  const retry = useCreateDocuments()
  const approveAll = useApproveAll()
  const apply = useApplyApproved()
  const remove = useDeleteApplications()
  const [confirmDelete, setConfirmDelete] = useState(false)
  const navigate = useNavigate()
  const [results, setResults] = useState<ApprovalResult[] | null>(null)
  const [picked, setPicked] = useState<Set<number>>(loadPicked)
  useEffect(() => savePicked(picked), [picked])

  // A submitted application is finished: it lives in Submitted applications, not in this list.
  const selected = (apps.data ?? []).filter((a) => !a.submittedAt)
  const selectedIds = new Set((apps.data ?? []).map((a) => a.jobId))
  const others = (docs.data ?? []).filter((d) => !selectedIds.has(d.jobId))
  const generating = selected.filter(isGenerating)
  const ready = selected.filter((a) => a.readyToApply)
  // Any application can be ticked (to delete it); only ticked ones that are ready go on to Apply.
  const ticked = selected.filter((a) => picked.has(a.jobId))
  const chosen = ready.filter((a) => picked.has(a.jobId))
  const tickedWriting = ticked.some(isGenerating)
  const toggle = (jobId: number) => setPicked((p) => { const n = new Set(p); if (n.has(jobId)) n.delete(jobId); else n.add(jobId); return n })
  const allPicked = selected.length > 0 && ticked.length === selected.length
  const pickAll = () => setPicked(allPicked ? new Set() : new Set(selected.map((a) => a.jobId)))
  const unapproved = selected.some((a) => (a.cv && !a.cv.approved) || (a.letter && !a.letter.approved))
  const writingPercent = selected.length === 0 ? 0 : Math.round(selected.reduce((sum, a) => sum + (isGenerating(a) ? (a.generation?.progress ?? 0) : 100), 0) / selected.length)

  const deleteTicked = () =>
    remove.mutate(ticked.map((a) => a.jobId), {
      onSuccess: () => {
        ticked.forEach((a) => {
          clearDraftsWithPrefix(`docs.turns.${a.jobId}`)
          if (a.cv) clearDraftsWithPrefix(`docs.draft.${a.cv.id}`)
          if (a.letter) clearDraftsWithPrefix(`docs.draft.${a.letter.id}`)
        })
        setPicked(new Set())
        setResults(null)
        setConfirmDelete(false)
      },
    })
  const approveEverything = () => approveAll.mutate(undefined, { onSuccess: setResults })
  const goApply = () => {
    enableSystemNotifications() // asked for here, on the click, so the "ready for submission" notification can reach the user later
    apply.mutate(chosen.map((a) => a.jobId), { onSuccess: (r) => { setPicked(new Set()); navigate('/apply', { state: { skipped: r.skipped } }) } })
  }
  const refused = (results ?? []).filter((r) => !r.approved)
  const newlyApproved = (results ?? []).filter((r) => r.approved && !r.alreadyApproved).length

  return (
    <>
      <PageTitle subtitle="Each selected job gets its own CV and cover letter, written for that ad. Open one to read, change or ask the AI to adjust it, then approve it.">
        Your documents
      </PageTitle>
      {(docs.isError || apps.isError) && <p role="alert" className="text-sm text-[var(--danger)]">Cannot reach the JobPilot API. Is the backend running?</p>}

      {generating.length > 0 && (
        <div className="mb-6">
          <Processing title="Writing your tailored documents" percent={writingPercent}
            message={`${generating.length} of ${selected.length} ${selected.length === 1 ? 'job is' : 'jobs are'} still being written. You can open the finished ones already.`} />
        </div>
      )}

      {selected.length > 0 && (
        <section aria-label="Documents for your selected jobs">
          <div className="mb-4 flex flex-wrap items-center gap-3">
            <p role="status" className="text-[15px]">
              <strong className="font-semibold">{ready.length}</strong> of {selected.length} {selected.length === 1 ? 'application is' : 'applications are'} ready for the next step
            </p>
            <Button variant="ghost" className="ml-auto" onClick={approveEverything} disabled={!unapproved || approveAll.isPending || generating.length > 0}
              title="Approves every CV and cover letter that has no problems. Ones with problems are listed with the reason.">
              {approveAll.isPending ? 'Approving…' : 'Approve all'}
            </Button>
            <Button variant="danger" onClick={() => { remove.reset(); setConfirmDelete(true) }} disabled={ticked.length === 0 || tickedWriting}
              title={tickedWriting ? 'Wait until the documents are written' : 'Deletes the ticked applications and their documents for good'}>
              {ticked.length > 0 ? `Delete (${ticked.length})` : 'Delete'}
            </Button>
          </div>

          {results && (
            <div role="status" className="mb-4 rounded-2xl bg-[var(--panel-2)] px-4 py-3 text-sm">
              {newlyApproved > 0 && <p className="flex items-center gap-2 font-medium text-[var(--success)]"><CheckIcon width={16} height={16} /> Approved {newlyApproved} {newlyApproved === 1 ? 'document' : 'documents'}.</p>}
              {newlyApproved === 0 && refused.length === 0 && <p className="text-[var(--muted)]">Everything was already approved.</p>}
              {refused.length > 0 && (
                <ul className="mt-1 flex flex-col gap-1 text-[var(--ink)]">
                  {refused.map((r) => (
                    <li key={`${r.jobId}-${r.kind}`}>
                      Could not approve the {r.kind === 'cv' ? 'CV' : 'cover letter'} for{' '}
                      <Link className="font-medium text-[var(--accent)] underline" to={`/documents/job/${r.jobId}${r.kind === 'letter' ? '?tab=letter' : ''}`}>{r.title}</Link>: {r.reason}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}

          <div className="overflow-hidden rounded-2xl border border-[var(--line)] bg-[var(--panel)] shadow-[var(--shadow)]">
            <div className="hidden grid-cols-[2.5rem_1fr_7rem_9rem_14rem] gap-4 border-b border-[var(--line)] bg-[var(--panel-2)] px-5 py-2.5 text-[13px] font-medium text-[var(--muted)] sm:grid">
              <span><input type="checkbox" aria-label="Select all applications" checked={allPicked} onChange={pickAll} className="size-4 accent-[var(--accent)]" /></span>
              <span>Job</span><span className="text-center">CV approved</span><span className="text-center">Cover letter approved</span><span>Next step</span>
            </div>
            <ul>
              {selected.map((a) => <Row key={a.jobId} app={a} picked={picked.has(a.jobId)} onToggle={() => toggle(a.jobId)} retrying={retry.isPending} onRetry={() => retry.mutate({ jobIds: [a.jobId], runId: a.runId ?? undefined })} />)}
            </ul>
          </div>
          {retry.isError && <p role="alert" className="mt-2 text-sm text-[var(--danger)]">{retry.error.message}</p>}

          <div className="mt-6 flex flex-wrap items-center gap-4 rounded-2xl border border-[var(--line)] bg-[var(--panel)] p-5 shadow-[var(--shadow)]">
            <div className="min-w-0 flex-1 text-sm">
              {chosen.length > 0 ? (
                <p>
                  <strong className="font-semibold">{chosen.length}</strong> {chosen.length === 1 ? 'application goes' : 'applications go'} to Apply.
                  JobPilot opens each one in its own browser tab and fills it in with the approved documents. Nothing is submitted.
                </p>
              ) : ready.length > 0 ? (
                <p className="text-[var(--muted)]">Tick the applications you want to send on. The others stay here.</p>
              ) : (
                <p className="text-[var(--muted)]">Approve the CV and the cover letter of at least one job to go on.</p>
              )}
              {chosen.length > 0 && selected.length > chosen.length && (
                <p className="mt-1 text-[var(--muted)]">{selected.length - chosen.length} stay{selected.length - chosen.length === 1 ? 's' : ''} here, not sent.</p>
              )}
            </div>
            <Button size="lg" onClick={goApply} disabled={chosen.length === 0 || apply.isPending || generating.length > 0}>
              <span className="flex items-center gap-2">{apply.isPending ? 'Opening…' : `Apply (${chosen.length})`}<ArrowRightIcon width={18} height={18} /></span>
            </Button>
            {apply.isError && <p role="alert" className="w-full text-sm text-[var(--danger)]">{apply.error.message}</p>}
          </div>
        </section>
      )}

      {confirmDelete && (
        <ConfirmDialog title={ticked.length === 1 ? 'Delete this application?' : `Delete ${ticked.length} applications?`} confirmLabel="Delete for good"
          busy={remove.isPending} error={remove.isError ? remove.error.message : undefined} onConfirm={deleteTicked} onCancel={() => setConfirmDelete(false)}>
          <p>
            {ticked.length === 1 ? `“${ticked[0].title}”` : `The ${ticked.length} selected applications`} will be removed together with the CV and the cover letter
            {ticked.length === 1 ? '' : ' of each'}. Nothing is kept anywhere and this cannot be undone.
          </p>
        </ConfirmDialog>
      )}

      {selected.length === 0 && docs.data?.length === 0 && (
        <Panel>
          <p className="text-sm text-[var(--muted)]">
            No documents yet. Find jobs in a <Link className="text-[var(--accent)] underline" to="/">search</Link>, tick the ones you like and choose "Create tailored documents".
          </p>
        </Panel>
      )}

      {others.length > 0 && (
        <details className="mt-8">
          <summary className="cursor-pointer text-[13px] font-medium text-[var(--muted)]">Other documents ({others.length})</summary>
          <ul className="mt-3 flex max-w-4xl flex-col gap-2">
            {others.map((d) => (
              <li key={d.jobId}>
                <Link to={`/documents/job/${d.jobId}`} className="flex items-center gap-3 rounded-2xl border border-[var(--line)] bg-[var(--panel)] p-4 transition hover:border-[var(--accent)]">
                  <span className="min-w-0 flex-1"><span className="font-medium">{d.title}</span><span className="ml-3 text-sm text-[var(--muted)]">{d.employer}</span></span>
                  <ApprovalTick approved={d.cvApproved} exists label="CV" />
                  <ApprovalTick approved={d.letterApproved} exists label="Cover letter" />
                </Link>
              </li>
            ))}
          </ul>
        </details>
      )}
    </>
  )
}

const PICKED_KEY = 'jobpilot.documents.picked'

function loadPicked(): Set<number> {
  try {
    return new Set(JSON.parse(localStorage.getItem(PICKED_KEY) ?? '[]') as number[])
  } catch {
    return new Set()
  }
}

function savePicked(picked: Set<number>) {
  try {
    localStorage.setItem(PICKED_KEY, JSON.stringify([...picked]))
  } catch {
    // The ticks just are not remembered.
  }
}

function Row({ app: a, picked, onToggle, retrying, onRetry }: { app: Application; picked: boolean; onToggle: () => void; retrying: boolean; onRetry: () => void }) {
  const writing = isGenerating(a)
  const failed = !writing && a.generation?.status === 'FAILED' && !(a.cv && a.letter)
  const empty = !writing && !a.cv && !a.letter && !a.generation
  return (
    <li className="grid items-center gap-x-4 gap-y-2 border-b border-[var(--line)] px-5 py-4 last:border-b-0 sm:grid-cols-[2.5rem_1fr_7rem_9rem_14rem]">
      <div>
        <input type="checkbox" aria-label={`Select ${a.title}`} checked={picked} onChange={onToggle}
          title={a.readyToApply ? 'Select to send on to Apply, or to delete' : 'Select to delete. It can only go to Apply once the CV and the cover letter are approved'} className="size-4 accent-[var(--accent)]" />
      </div>
      <div className="min-w-0">
        <Link to={`/documents/job/${a.jobId}`} className="block truncate text-[15px] font-semibold tracking-tight hover:text-[var(--accent)]">{a.title}</Link>
        <p className="truncate text-sm text-[var(--muted)]">
          {[a.employer, MODE_LABEL[a.searchMode], a.roleType].filter(Boolean).join(' · ')}
        </p>
        {writing && a.generation && (
          <div className="mt-2">
            <div role="progressbar" aria-label={`Progress: ${a.title}`} aria-valuenow={a.generation.progress} aria-valuemin={0} aria-valuemax={100} className="h-1.5 overflow-hidden rounded-full bg-[var(--line)]">
              <div className="shimmer h-full rounded-full" style={{ width: `${Math.max(a.generation.progress, 6)}%` }} />
            </div>
            <p className="mt-1 text-xs text-[var(--muted)]">{a.generation.message}</p>
          </div>
        )}
        {failed && (
          <p role="alert" className="mt-2 flex flex-wrap items-center gap-3 text-sm text-[var(--danger)]">
            Failed: {a.generation?.message}
            <Button variant="ghost" disabled={retrying} onClick={onRetry}>Try again</Button>
          </p>
        )}
        {empty && (
          <p className="mt-2 flex items-center gap-3 text-sm text-[var(--muted)]">No documents yet.
            <Button disabled={retrying} onClick={onRetry}>Create documents</Button></p>
        )}
      </div>
      <div className="flex sm:justify-center"><ApprovalTick approved={!!a.cv?.approved} exists={!!a.cv} label="CV" /></div>
      <div className="flex sm:justify-center"><ApprovalTick approved={!!a.letter?.approved} exists={!!a.letter} label="Cover letter" /></div>
      <div className="text-sm">
        {a.readyToApply ? (
          picked ? <Pill tone="success"><CheckIcon width={12} height={12} /> Goes to Apply</Pill> : <Pill tone="neutral">Ready, not sent</Pill>
        ) : writing ? (
          <Pill tone="accent">Being written</Pill>
        ) : (
          <span className="flex flex-col gap-1">
            <Pill tone="warn" className="self-start">Left out for now</Pill>
            <span className="text-xs text-[var(--muted)]">{a.blockers.join('. ')}</span>
          </span>
        )}
      </div>
    </li>
  )
}
