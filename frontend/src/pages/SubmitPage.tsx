import { Link } from 'react-router-dom'
import { useApplications } from '../api/applications'
import { useApplyStatus } from '../api/apply'
import { isSubmitStage } from '../components/apply/phase'
import { SubmitCard } from '../components/apply/SubmitCard'
import { PageTitle, Panel } from '../components/Panel'

/** Stage 5, Submit: the applications that are filled in and waiting for your own click on the employer's submit button. */
export function SubmitPage() {
  const apps = useApplications()
  const status = useApplyStatus()
  const tabs = new Map((status.data ?? []).map((t) => [t.jobId, t]))
  const ready = (apps.data ?? []).filter((a) => !a.submittedAt && isSubmitStage(tabs.get(a.jobId)))
  const submitted = (apps.data ?? []).filter((a) => a.submittedAt).length

  return (
    <>
      <PageTitle subtitle="You always send the application yourself. Open it, check it, press the employer's own submit button, then tell JobPilot here: it keeps the application with the exact CV and cover letter you sent.">
        Submit
      </PageTitle>
      {ready.length === 0 && (
        <Panel>
          <p className="text-sm text-[var(--muted)]">
            Nothing is ready to submit yet. Applications show up here as soon as they are filled in. Check <Link className="text-[var(--accent)] underline" to="/apply">Apply</Link> for how they are doing.
          </p>
          {submitted > 0 && <p className="mt-3 text-sm"><Link className="text-[var(--accent)] underline" to="/submitted">{submitted} submitted {submitted === 1 ? 'application' : 'applications'}</Link> are kept in Submitted applications.</p>}
        </Panel>
      )}
      <div className="grid gap-4">
        {ready.map((a) => <SubmitCard key={a.jobId} app={a} tab={tabs.get(a.jobId)} />)}
      </div>
    </>
  )
}
