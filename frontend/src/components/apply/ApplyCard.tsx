import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { type Application, isGenerating, MODE_LABEL, useRemoveApplication } from '../../api/applications'
import { type TabView, useCloseTab, useFillPage, useFocusTab, useOpenBatch, useSaveAnswers } from '../../api/apply'
import { useLogins } from '../../api/logins'
import { Button, TextInput } from '../Form'
import { WarnIcon } from '../ui/Icons'
import { type Phase, PhasePill, phaseOf } from './phase'
import { loginDeclined, SaveLoginPrompt } from './SaveLoginPrompt'

const seenKey = (jobId: number) => `jobpilot.loginSeen.${jobId}`

/** The portal this application had to sign in to, remembered so the "save your login?" question can follow the sign-in. */
function useSignInMemory(jobId: number, tab: TabView | undefined) {
  const logins = useLogins()
  const [dismissed, setDismissed] = useState(false)
  useEffect(() => {
    if (tab?.phase === 'NEEDS_LOGIN' && tab.signInHost) {
      try {
        localStorage.setItem(seenKey(jobId), tab.signInHost)
      } catch {
        // without storage the question is simply not asked after a page change
      }
    }
  }, [jobId, tab?.phase, tab?.signInHost])
  let host: string | null = null
  try {
    host = localStorage.getItem(seenKey(jobId))
  } catch {
    host = null
  }
  const afterSignIn = !!tab && tab.phase !== 'NEEDS_LOGIN' && tab.phase !== 'QUEUED' && tab.phase !== 'CLOSED'
  const saved = !!host && !!logins.data?.some((l) => l.host === host)
  const ask = afterSignIn && !!host && !saved && !loginDeclined(host) && !tab?.loginFilled && !dismissed && logins.isSuccess
  const close = () => {
    setDismissed(true)
    try {
      localStorage.removeItem(seenKey(jobId))
    } catch {
      // nothing to clean up
    }
  }
  return { askHost: ask ? host : null, close }
}

/** One application in the Apply stage: what it is doing, what it needs from you, and the controls for its browser tab. */
export function ApplyCard({ app, tab }: { app: Application | undefined; tab: TabView | undefined }) {
  const fill = useFillPage()
  const focus = useFocusTab()
  const close = useCloseTab()
  const answers = useSaveAnswers()
  const openOne = useOpenBatch()
  const remove = useRemoveApplication()
  const [text, setText] = useState<Record<string, string>>({})

  const jobId = (app?.jobId ?? tab?.jobId) as number
  const title = app?.title ?? tab?.title ?? `Job ${jobId}`
  const employer = app?.employer ?? tab?.employer
  const phase: Phase = phaseOf(app, tab)
  const hasTab = !!tab && !['EMAIL_ONLY', 'NO_APPLY_LINK', 'APPLY_MANUALLY', 'CLOSED', 'QUEUED'].includes(tab.status)
  const working = tab?.status === 'OPENING' || tab?.status === 'FILLING'
  const reopen = tab?.status === 'CLOSED' || tab?.status === 'ERROR'
  const questions = tab?.questions ?? []
  const answered = questions.filter((q) => (text[q.fieldId] ?? '').trim() !== '')
  const signIn = useSignInMemory(jobId, tab)
  const error = fill.error ?? focus.error ?? close.error ?? openOne.error ?? remove.error

  const saveAndFill = () =>
    answers.mutate({ jobId, answers: answered.map((q) => ({ fieldId: q.fieldId, answer: text[q.fieldId].trim() })) }, { onSuccess: () => fill.mutate({ jobId }) })

  return (
    <section aria-label={`Application: ${title}`} className="rounded-2xl border border-[var(--line)] bg-[var(--panel)] p-5 shadow-[var(--shadow)]">
      <div className="flex flex-wrap items-center gap-3">
        <h2 className="text-[16px] font-semibold tracking-tight">{title}</h2>
        <span className="text-sm text-[var(--muted)]">{employer}</span>
        <span className="ml-auto"><PhasePill phase={phase} /></span>
      </div>
      {app && (
        <p className="mt-1 text-xs text-[var(--muted)]">
          {MODE_LABEL[app.searchMode]}{app.roleType ? ` · ${app.roleType}` : ''}{app.score !== null ? ` · score ${app.score}` : ''}
        </p>
      )}
      {app && (
        <div className="mt-3 flex flex-wrap items-center gap-2 text-sm">
          {app.cv && <Link className="rounded-full border border-[var(--line)] px-3 py-1 hover:border-[var(--accent)]" to={`/documents/job/${jobId}`}>CV v{app.cv.version}{app.cv.approved ? ' ✓' : ''}</Link>}
          {app.letter && <Link className="rounded-full border border-[var(--line)] px-3 py-1 hover:border-[var(--accent)]" to={`/documents/job/${jobId}?tab=letter`}>Cover letter v{app.letter.version}{app.letter.approved ? ' ✓' : ''}</Link>}
          {isGenerating(app) && app.generation && <span className="text-[var(--muted)]">Writing documents: {app.generation.message}</span>}
          {!app.readyToApply && app.blockers.length > 0 && (
            <span className="text-[var(--warn)]">{app.blockers.join('. ')}. <Link className="underline" to="/documents">Fix in Documents</Link></span>
          )}
        </div>
      )}

      {tab && <p className="mt-3 text-sm">{tab.message}</p>}
      {tab && (tab.done.length > 0 || tab.skipped.length > 0) && (
        <p className="mt-1 text-xs text-[var(--muted)]">{tab.done.length} filled, {tab.skipped.length} left for you</p>
      )}

      {tab?.phase === 'NEEDS_LOGIN' && (
        <div role="alert" className="mt-4 flex items-start gap-3 rounded-2xl bg-[var(--warn-soft)] p-4 text-sm">
          <WarnIcon className="mt-0.5 shrink-0 text-[var(--warn)]" />
          <div>
            <p className="font-semibold">This site needs you to sign in or sign up{tab.signInHost ? ` (${tab.signInHost})` : ''}.</p>
            <p className="mt-1 text-[var(--muted)]">
              Do it yourself in the browser tab. JobPilot never creates accounts or handles BankID, and carries on by itself once you are in.
            </p>
            <div className="mt-3"><Button onClick={() => focus.mutate(jobId)} disabled={focus.isPending}>Show the tab</Button></div>
          </div>
        </div>
      )}

      {signIn.askHost && <SaveLoginPrompt host={signIn.askHost} onClose={signIn.close} />}

      {questions.length > 0 && (
        <div className="mt-4 rounded-2xl bg-[var(--accent-soft)] p-4">
          <h3 className="mb-2 text-sm font-semibold">Questions for you</h3>
          <ul className="flex flex-col gap-2">
            {questions.map((q) => (
              <li key={q.fieldId}>
                <label className="block text-sm">
                  {q.question}
                  <TextInput aria-label={`Answer: ${q.question}`} className="mt-1" value={text[q.fieldId] ?? ''} onChange={(e) => setText({ ...text, [q.fieldId]: e.target.value })} />
                </label>
              </li>
            ))}
          </ul>
          <p className="mt-2 text-xs text-[var(--muted)]">Answers are remembered for similar questions on other sites.</p>
          <div className="mt-2"><Button onClick={saveAndFill} disabled={answered.length === 0 || answers.isPending}>Save answers and fill</Button></div>
          {answers.isError && <p role="alert" className="mt-2 text-sm text-[var(--danger)]">{answers.error.message}</p>}
        </div>
      )}

      <div className="mt-4 flex flex-wrap items-center gap-2">
        {!tab && app?.readyToApply && (
          <Button onClick={() => openOne.mutate([jobId])} disabled={openOne.isPending}>Open and fill</Button>
        )}
        {hasTab && <Button variant="ghost" onClick={() => fill.mutate({ jobId })} disabled={working || fill.isPending}>Fill this page</Button>}
        {(tab?.status === 'SESSION_EXPIRED' || reopen) && (
          <Button onClick={() => fill.mutate({ jobId, restart: true })} disabled={fill.isPending}>Restart and refill</Button>
        )}
        {hasTab && <Button variant="ghost" onClick={() => focus.mutate(jobId)}>Show tab</Button>}
        {hasTab && <Button variant="ghost" onClick={() => close.mutate(jobId)}>Close tab</Button>}
        {tab?.phase === 'READY_FOR_SUBMISSION' && <Link to="/submit" className="ml-auto text-sm font-medium text-[var(--accent)] hover:underline">Go to Submit ›</Link>}
        {app && !working && !tab && (
          <Button variant="quiet" className="ml-auto" onClick={() => remove.mutate(jobId)} disabled={remove.isPending} title="Take it off this list. Its documents are kept.">Remove</Button>
        )}
        {error && <span role="alert" className="self-center text-sm text-[var(--danger)]">{error.message}</span>}
      </div>

      {tab && (tab.done.length > 0 || tab.skipped.length > 0) && (
        <details className="mt-4 text-sm">
          <summary className="cursor-pointer text-[var(--muted)]">What was filled and what was left</summary>
          <ul className="mt-2 flex flex-col gap-1">
            {tab.done.map((d) => <li key={d.fieldId} className="text-[var(--success)]">Filled "{d.label}"{d.detail ? `: ${d.detail}` : ''}</li>)}
            {tab.skipped.map((s) => <li key={s.fieldId} className="text-[var(--muted)]">Left "{s.label}": {s.reason}</li>)}
          </ul>
        </details>
      )}
    </section>
  )
}
