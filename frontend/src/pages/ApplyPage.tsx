import { Link, useLocation } from 'react-router-dom'
import { useApplications, useApplyApproved } from '../api/applications'
import { useApplyStatus } from '../api/apply'
import { ApplyCard } from '../components/apply/ApplyCard'
import { enableSystemNotifications } from '../components/apply/notifications'
import { type Phase, PHASE_LABEL, phaseOf } from '../components/apply/phase'
import { Button } from '../components/Form'
import { PageTitle, Panel } from '../components/Panel'
import { Pill } from '../components/ui/Pill'

/** The most urgent first: what needs the user, then what failed, what is busy, what is ready, what has not started. */
const ORDER: Phase[] = ['NEEDS_LOGIN', 'NEEDS_ATTENTION', 'FAILED', 'FILLING', 'QUEUED', 'BY_HAND', 'READY_FOR_SUBMISSION', 'NOT_STARTED', 'CLOSED']

/** Stage 4, Apply: a browser tab per approved application, filled in from your CV database. Nothing is ever submitted. */
export function ApplyPage() {
  const status = useApplyStatus()
  const apps = useApplications()
  const apply = useApplyApproved()
  const skipped = (useLocation().state as { skipped?: { jobId: number; title: string; reason: string }[] } | null)?.skipped ?? []

  const tabs = new Map((status.data ?? []).map((t) => [t.jobId, t]))
  const open = (apps.data ?? []).filter((a) => !a.submittedAt)
  const started = open.filter((a) => tabs.has(a.jobId) && tabs.get(a.jobId)?.phase !== 'CLOSED')
  const notStarted = open.filter((a) => !started.includes(a))
  const toStart = notStarted.filter((a) => a.readyToApply)
  const left = notStarted.filter((a) => !a.readyToApply)
  const sorted = [...started].sort((a, b) => ORDER.indexOf(phaseOf(a, tabs.get(a.jobId))) - ORDER.indexOf(phaseOf(b, tabs.get(b.jobId))))
  const counts = new Map<Phase, number>()
  started.forEach((a) => { const p = phaseOf(a, tabs.get(a.jobId)); counts.set(p, (counts.get(p) ?? 0) + 1) })

  return (
    <>
      <PageTitle subtitle="JobPilot opens each approved application in its own browser tab, fills it in from your CV database and uploads the approved CV and cover letter. It stops before the end: you always press the final button yourself.">
        Apply
      </PageTitle>
      {(status.isError || apps.isError) && <p role="alert" className="text-sm text-[var(--danger)]">Cannot reach the JobPilot API. Is the backend running?</p>}

      {skipped.length > 0 && (
        <div role="note" className="mb-5 rounded-2xl bg-[var(--warn-soft)] px-4 py-3 text-sm">
          <p className="font-medium">{skipped.length} {skipped.length === 1 ? 'application did' : 'applications did'} not go on:</p>
          <ul className="mt-1 list-disc pl-5">{skipped.map((s) => <li key={s.jobId}>{s.title}: {s.reason}</li>)}</ul>
          <p className="mt-1"><Link to="/documents" className="font-medium text-[var(--accent)] underline">Open Documents</Link> to approve them.</p>
        </div>
      )}

      {started.length > 0 && (
        <div className="mb-5 flex flex-wrap items-center gap-2" role="status">
          {ORDER.filter((p) => counts.get(p)).map((p) => <Pill key={p} tone={PHASE_LABEL[p][1]}>{counts.get(p)} {PHASE_LABEL[p][0].toLowerCase()}</Pill>)}
          <Button variant="quiet" className="ml-auto" onClick={enableSystemNotifications} title="Get a system notification when an application is ready for you to submit">
            Notify me when ready
          </Button>
        </div>
      )}

      {toStart.length > 0 && (
        <Panel className="mb-5">
          <div className="flex flex-wrap items-center gap-4">
            <p className="min-w-0 flex-1 text-sm">
              <strong className="font-semibold">{toStart.length}</strong> approved {toStart.length === 1 ? 'application has' : 'applications have'} not been opened yet.
            </p>
            <Button size="lg" disabled={apply.isPending} onClick={() => { enableSystemNotifications(); apply.mutate() }}>
              {apply.isPending ? 'Opening…' : `Open and fill (${toStart.length})`}
            </Button>
          </div>
          {apply.isError && <p role="alert" className="mt-2 text-sm text-[var(--danger)]">{apply.error.message}</p>}
        </Panel>
      )}

      {open.length === 0 && !status.isError && !apps.isError && (
        <Panel><p className="text-sm text-[var(--muted)]">Nothing to apply for yet. Approve documents in <Link className="text-[var(--accent)] underline" to="/documents">Documents</Link> first.</p></Panel>
      )}

      <div className="grid gap-4">
        {sorted.map((a) => <ApplyCard key={a.jobId} app={a} tab={tabs.get(a.jobId)} />)}
        {(status.data ?? []).filter((t) => !open.some((a) => a.jobId === t.jobId) && !(apps.data ?? []).some((a) => a.jobId === t.jobId)).map((t) => (
          <ApplyCard key={t.jobId} app={undefined} tab={t} />
        ))}
      </div>

      {left.length > 0 && (
        <details className="mt-6">
          <summary className="cursor-pointer text-[13px] font-medium text-[var(--muted)]">Not ready yet ({left.length})</summary>
          <div className="mt-3 grid gap-3">{left.map((a) => <ApplyCard key={a.jobId} app={a} tab={undefined} />)}</div>
        </details>
      )}
    </>
  )
}
