import { useState } from 'react'
import { Link } from 'react-router-dom'
import { type Application, useMarkSubmitted } from '../../api/applications'
import { type TabView, useFocusTab } from '../../api/apply'
import { Button } from '../Form'
import { useToast } from '../Toasts'
import { PhasePill, phaseOf } from './phase'

/**
 * One application that is ready for the user's own click on the employer's submit button. JobPilot only brings the tab
 * forward; after submitting, the user confirms here and the application is stored exactly as it was sent.
 */
export function SubmitCard({ app, tab }: { app: Application; tab: TabView | undefined }) {
  const focus = useFocusTab()
  const submitted = useMarkSubmitted()
  const toast = useToast()
  const [confirming, setConfirming] = useState(false)
  const byHand = !!tab && ['APPLY_MANUALLY', 'EMAIL_ONLY', 'NO_APPLY_LINK'].includes(tab.status)
  const canFocus = !!tab && !byHand && tab.status !== 'CLOSED'

  return (
    <section aria-label={`Ready to submit: ${app.title}`} className="rounded-2xl border border-[var(--line)] bg-[var(--panel)] p-5 shadow-[var(--shadow)]">
      <div className="flex flex-wrap items-center gap-3">
        <h2 className="text-[16px] font-semibold tracking-tight">{app.title}</h2>
        <span className="text-sm text-[var(--muted)]">{app.employer}</span>
        <span className="ml-auto"><PhasePill phase={phaseOf(app, tab)} /></span>
      </div>
      {tab && <p className="mt-2 text-sm text-[var(--muted)]">{tab.message}</p>}

      <div className="mt-3 flex flex-wrap items-center gap-2 text-sm">
        {app.cv && <a className="rounded-full border border-[var(--line)] px-3 py-1 hover:border-[var(--accent)]" href={`/api/documents/${app.cv.id}/pdf`} target="_blank" rel="noreferrer noopener">CV (PDF)</a>}
        {app.letter && <a className="rounded-full border border-[var(--line)] px-3 py-1 hover:border-[var(--accent)]" href={`/api/documents/${app.letter.id}/pdf`} target="_blank" rel="noreferrer noopener">Cover letter (PDF)</a>}
        {app.applyUrl && byHand && <a className="rounded-full border border-[var(--line)] px-3 py-1 hover:border-[var(--accent)]" href={app.applyUrl} target="_blank" rel="noreferrer noopener">Open the ad</a>}
      </div>

      <div className="mt-4 flex flex-wrap items-center gap-2">
        {canFocus && <Button onClick={() => focus.mutate(app.jobId)} disabled={focus.isPending}>Open the application</Button>}
        {!confirming && <Button variant="ghost" onClick={() => setConfirming(true)}>I submitted it</Button>}
        <Link to="/apply" className="ml-auto text-sm text-[var(--muted)] hover:text-[var(--ink)]">Back to Apply</Link>
        {focus.isError && <span role="alert" className="text-sm text-[var(--danger)]">{focus.error.message}</span>}
      </div>

      {confirming && (
        <div role="group" aria-label="Confirm submission" className="mt-4 rounded-2xl bg-[var(--warn-soft)] p-4 text-sm">
          <p className="mb-3">
            Did you submit this application yourself, on the employer's site (or by email)? JobPilot cannot see it, so it only records what you tell it.
            The exact CV and cover letter are saved with it.
          </p>
          <div className="flex gap-2">
            <Button disabled={submitted.isPending}
              onClick={() => submitted.mutate(app.jobId, { onSuccess: () => { setConfirming(false); toast(`${app.title} saved to Submitted applications`) }, onError: (e) => toast(e.message, 'error') })}>
              Yes, I submitted it
            </Button>
            <Button variant="ghost" onClick={() => setConfirming(false)}>Not yet</Button>
          </div>
        </div>
      )}
    </section>
  )
}
