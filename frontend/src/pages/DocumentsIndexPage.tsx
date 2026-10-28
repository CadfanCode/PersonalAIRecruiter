import { useState } from 'react'
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
} from '../api/applications'
import { useDocOverview } from '../api/documents'
import { enableSystemNotifications } from '../components/apply/notifications'
import { Button } from '../components/Form'
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
  const navigate = useNavigate()
  const [results, setResults] = useState<ApprovalResult[] | null>(null)

  // A submitted application is finished: it lives in Submitted applications, not in this list.
  const selected = (apps.data ?? []).filter((a) => !a.submittedAt)
  const selectedIds = new Set((apps.data ?? []).map((a) => a.jobId))
  const others = (docs.data ?? []).filter((d) => !selectedIds.has(d.jobId))
  const generating = selected.filter(isGenerating)
  const ready = selected.filter((a) => a.readyToApply)
  const waiting = selected.filter((a) => !a.readyToApply)
  const unapproved = selected.some((a) => (a.cv && !a.cv.approved) || (a.letter && !a.letter.approved))
  const writingPercent = selected.length === 0 ? 0 : Math.round(selected.reduce((sum, a) => sum + (isGenerating(a) ? (a.generation?.progress ?? 0) : 100), 0) / selected.length)

  const approveEverything = () => approveAll.mutate(undefined, { onSuccess: setResults })
  const goApply = () => {
    enableSystemNotifications() // asked for here, on the click, so the "ready for submission" notification can reach the user later
    apply.mutate(undefined, { onSuccess: (r) => navigate('/apply', { state: { skipped: r.skipped } }) })
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
            <div className="hidden grid-cols-[1fr_7rem_9rem_14rem] gap-4 border-b border-[var(--line)] bg-[var(--panel-2)] px-5 py-2.5 text-[13px] font-medium text-[var(--muted)] sm:grid">
              <span>Job</span><span className="text-center">CV approved</span><span className="text-center">Cover letter approved</span><span>Next step</span>
            </div>
            <ul>
              {selected.map((a) => <Row key={a.jobId} app={a} retrying={retry.isPending} onRetry={() => retry.mutate({ jobIds: [a.jobId], runId: a.runId ?? undefined })} />)}
            </ul>
          </div>
          {retry.isError && <p role="alert" className="mt-2 text-sm text-[var(--danger)]">{retry.error.message}</p>}

          <div className="mt-6 flex flex-wrap items-center gap-4 rounded-2xl border border-[var(--line)] bg-[var(--panel)] p-5 shadow-[var(--shadow)]">
            <div className="min-w-0 flex-1 text-sm">
              {ready.length > 0 ? (
                <p>
                  <strong className="font-semibold">{ready.length}</strong> {ready.length === 1 ? 'application goes' : 'applications go'} to Apply.
                  JobPilot opens each one in its own browser tab and fills it in with the approved documents. Nothing is submitted.
                </p>
              ) : (
                <p className="text-[var(--muted)]">Approve the CV and the cover letter of at least one job to go on.</p>
              )}
              {waiting.length > 0 && ready.length > 0 && (
                <p className="mt-1 text-[var(--muted)]">{waiting.length} {waiting.length === 1 ? 'is' : 'are'} left out for now: see "Next step" above.</p>
              )}
            </div>
            <Button size="lg" onClick={goApply} disabled={ready.length === 0 || apply.isPending || generating.length > 0}>
              <span className="flex items-center gap-2">{apply.isPending ? 'Opening…' : `Apply (${ready.length})`}<ArrowRightIcon width={18} height={18} /></span>
            </Button>
            {apply.isError && <p role="alert" className="w-full text-sm text-[var(--danger)]">{apply.error.message}</p>}
          </div>
        </section>
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

function Row({ app: a, retrying, onRetry }: { app: Application; retrying: boolean; onRetry: () => void }) {
  const writing = isGenerating(a)
  const failed = !writing && a.generation?.status === 'FAILED' && !(a.cv && a.letter)
  const empty = !writing && !a.cv && !a.letter && !a.generation
  return (
    <li className="grid items-center gap-x-4 gap-y-2 border-b border-[var(--line)] px-5 py-4 last:border-b-0 sm:grid-cols-[1fr_7rem_9rem_14rem]">
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
          <Pill tone="success"><CheckIcon width={12} height={12} /> Goes to Apply</Pill>
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
