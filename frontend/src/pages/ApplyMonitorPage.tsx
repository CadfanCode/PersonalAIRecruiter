import { useState } from 'react'
import { Link } from 'react-router-dom'
import { type TabStatus, type TabView, useApplyStatus, useCloseTab, useFillPage, useFocusTab, useSaveAnswers } from '../api/apply'
import { Button, TextInput } from '../components/Form'
import { PageTitle, Panel } from '../components/Panel'

const LABEL: Record<TabStatus, [string, string]> = {
  OPENING: ['Opening', 'bg-slate-200 text-slate-700'],
  FILLING: ['Filling', 'bg-blue-100 text-blue-800'],
  WAITING_FOR_SIGN_IN: ['Waiting for sign-in', 'bg-amber-100 text-amber-800'],
  NEEDS_MANUAL_APPLY: ['Needs you to open the form', 'bg-amber-100 text-amber-800'],
  NEEDS_ANSWERS: ['Needs answers', 'bg-violet-100 text-violet-800'],
  READY_FOR_USER: ['Ready for you to submit', 'bg-emerald-100 text-emerald-800'],
  SESSION_EXPIRED: ['Session expired', 'bg-red-100 text-red-800'],
  EMAIL_ONLY: ['Apply by email', 'bg-slate-200 text-slate-700'],
  NO_APPLY_LINK: ['No application link', 'bg-slate-200 text-slate-700'],
  CLOSED: ['Tab closed', 'bg-stone-200 text-stone-700'],
  ERROR: ['Problem', 'bg-red-100 text-red-800'],
}

export function ApplyMonitorPage() {
  const status = useApplyStatus()
  return (
    <>
      <PageTitle>Apply monitor</PageTitle>
      <p className="-mt-3 mb-5 max-w-3xl text-sm text-[var(--muted)]">
        JobPilot fills what it can and stops. It never submits, never types passwords or handles BankID, and never ticks consent
        boxes. Check each page in the browser and submit it yourself.
      </p>
      {status.isError && <p role="alert" className="text-sm text-red-600">Cannot reach the JobPilot API. Is the backend running?</p>}
      {status.data?.length === 0 && (
        <Panel>
          <p className="text-sm text-[var(--muted)]">
            No applications open. Select ads in a <Link className="underline" to="/runs">run</Link> and choose "Open applications".
          </p>
        </Panel>
      )}
      <div className="grid max-w-5xl gap-4">
        {status.data?.map((t) => <TabCard key={t.jobId} tab={t} />)}
      </div>
    </>
  )
}

function TabCard({ tab }: { tab: TabView }) {
  const fill = useFillPage()
  const focus = useFocusTab()
  const close = useCloseTab()
  const answers = useSaveAnswers()
  const [text, setText] = useState<Record<string, string>>({})
  const [label, style] = LABEL[tab.status]
  const hasTab = !['EMAIL_ONLY', 'NO_APPLY_LINK', 'CLOSED'].includes(tab.status)
  const working = tab.status === 'OPENING' || tab.status === 'FILLING'
  const answered = tab.questions.filter((q) => (text[q.fieldId] ?? '').trim() !== '')

  const saveAndFill = () =>
    answers.mutate(
      { jobId: tab.jobId, answers: answered.map((q) => ({ fieldId: q.fieldId, answer: text[q.fieldId].trim() })) },
      { onSuccess: () => fill.mutate({ jobId: tab.jobId }) },
    )

  return (
    <section aria-label={`Application: ${tab.title}`} className="rounded-lg border border-[var(--line)] bg-[var(--panel)] p-5">
      <div className="mb-2 flex flex-wrap items-center gap-3">
        <h2 className="font-semibold">{tab.title}</h2>
        <span className="text-sm text-[var(--muted)]">{tab.employer}</span>
        <span className={`ml-auto rounded-full px-2.5 py-0.5 text-xs font-medium ${style}`}>{label}</span>
      </div>
      <p className="text-sm">{tab.message}</p>
      {(tab.done.length > 0 || tab.skipped.length > 0) && (
        <p className="mt-1 text-xs text-[var(--muted)]">
          {tab.done.length} filled, {tab.skipped.length} skipped
        </p>
      )}

      {tab.questions.length > 0 && (
        <div className="mt-4 rounded-md bg-violet-50 p-3 dark:bg-violet-950">
          <h3 className="mb-2 text-sm font-semibold">Questions for you</h3>
          <ul className="flex flex-col gap-2">
            {tab.questions.map((q) => (
              <li key={q.fieldId}>
                <label className="block text-sm">
                  {q.question}
                  <TextInput
                    aria-label={`Answer: ${q.question}`}
                    className="mt-1"
                    value={text[q.fieldId] ?? ''}
                    onChange={(e) => setText({ ...text, [q.fieldId]: e.target.value })}
                  />
                </label>
              </li>
            ))}
          </ul>
          <p className="mt-2 text-xs text-[var(--muted)]">Answers are remembered for similar questions on other sites.</p>
          <div className="mt-2">
            <Button onClick={saveAndFill} disabled={answered.length === 0 || answers.isPending}>Save answers and fill</Button>
          </div>
          {answers.isError && <p role="alert" className="mt-2 text-sm text-red-600">{answers.error.message}</p>}
        </div>
      )}

      <div className="mt-4 flex flex-wrap gap-2">
        {hasTab && (
          <Button variant="ghost" onClick={() => fill.mutate({ jobId: tab.jobId })} disabled={working || fill.isPending}>
            Fill this page
          </Button>
        )}
        {tab.status === 'SESSION_EXPIRED' && (
          <Button onClick={() => fill.mutate({ jobId: tab.jobId, restart: true })} disabled={fill.isPending}>Restart and refill</Button>
        )}
        {hasTab && <Button variant="ghost" onClick={() => focus.mutate(tab.jobId)}>Focus tab</Button>}
        {hasTab && <Button variant="ghost" onClick={() => close.mutate(tab.jobId)}>Close tab</Button>}
        {(fill.isError || focus.isError || close.isError) && (
          <span role="alert" className="self-center text-sm text-red-600">
            {(fill.error ?? focus.error ?? close.error)?.message}
          </span>
        )}
      </div>

      {(tab.done.length > 0 || tab.skipped.length > 0) && (
        <details className="mt-4 text-sm">
          <summary className="cursor-pointer text-[var(--muted)]">What was filled and what was left</summary>
          <ul className="mt-2 flex flex-col gap-1">
            {tab.done.map((d) => (
              <li key={d.fieldId} className="text-emerald-700 dark:text-emerald-400">
                Filled "{d.label}"{d.detail ? `: ${d.detail}` : ''}
              </li>
            ))}
            {tab.skipped.map((s) => (
              <li key={s.fieldId} className="text-[var(--muted)]">
                Left "{s.label}": {s.reason}
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  )
}
