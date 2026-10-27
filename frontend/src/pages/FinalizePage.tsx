import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  type Application,
  MODE_LABEL,
  useApplications,
  useMarkSubmitted,
  useUndoSubmitted,
} from '../api/applications'
import {
  type TabStatus,
  type TabView,
  useApplyStatus,
  useCloseTab,
  useFillPage,
  useFocusTab,
  useOpenBatch,
  useSaveAnswers,
} from '../api/apply'
import { Button, TextInput } from '../components/Form'
import { PageTitle, Panel } from '../components/Panel'

const LABEL: Record<TabStatus, [string, string]> = {
  QUEUED: ['Waiting for a free tab', 'bg-slate-200 text-slate-700'],
  OPENING: ['Opening', 'bg-slate-200 text-slate-700'],
  FILLING: ['Filling', 'bg-blue-100 text-blue-800'],
  WAITING_FOR_SIGN_IN: ['Waiting for sign-in', 'bg-amber-100 text-amber-800'],
  NEEDS_MANUAL_APPLY: ['Needs you to open the form', 'bg-amber-100 text-amber-800'],
  NEEDS_ANSWERS: ['Needs answers', 'bg-violet-100 text-violet-800'],
  READY_FOR_USER: ['Ready for you to submit', 'bg-emerald-100 text-emerald-800'],
  SESSION_EXPIRED: ['Session expired', 'bg-red-100 text-red-800'],
  EMAIL_ONLY: ['Apply by email', 'bg-slate-200 text-slate-700'],
  NO_APPLY_LINK: ['No application link', 'bg-slate-200 text-slate-700'],
  APPLY_MANUALLY: ['Apply on the site yourself', 'bg-amber-100 text-amber-800'],
  CLOSED: ['Tab closed', 'bg-stone-200 text-stone-700'],
  ERROR: ['Problem', 'bg-red-100 text-red-800'],
}

type Group = 'ready' | 'progress' | 'attention' | 'failed' | 'submitted' | 'notstarted'

/** The coarse state shown in the summary line: ready, in progress, needs attention, failed, submitted. */
function groupOf(app: Application | undefined, tab: TabView | undefined): Group {
  if (app?.submittedAt) return 'submitted'
  if (!tab) return 'notstarted'
  switch (tab.status) {
    case 'READY_FOR_USER':
      return 'ready'
    case 'QUEUED':
    case 'OPENING':
    case 'FILLING':
      return 'progress'
    case 'ERROR':
    case 'SESSION_EXPIRED':
      return 'failed'
    case 'CLOSED':
      return 'notstarted'
    default:
      return 'attention'
  }
}

const SUMMARY: [Group, string][] = [
  ['ready', 'ready'],
  ['progress', 'in progress'],
  ['attention', 'need attention'],
  ['failed', 'failed'],
  ['submitted', 'submitted'],
]

export function FinalizePage() {
  const status = useApplyStatus()
  const apps = useApplications()
  const runAll = useOpenBatch()

  const tabs = new Map((status.data ?? []).map((t) => [t.jobId, t]))
  const appById = new Map((apps.data ?? []).map((a) => [a.jobId, a]))
  // Applications first, then any tab that was opened from a run without selecting the job.
  const ids = [...(apps.data ?? []).map((a) => a.jobId), ...(status.data ?? []).filter((t) => !appById.has(t.jobId)).map((t) => t.jobId)]
  const groups = ids.map((id) => groupOf(appById.get(id), tabs.get(id)))
  const counts = new Map<Group, number>()
  groups.forEach((g) => counts.set(g, (counts.get(g) ?? 0) + 1))
  // "Fill all" starts what has no open tab yet; a card already filling, waiting or finished is left alone.
  const startable = ids.filter((id, i) => groups[i] === 'notstarted' && appById.get(id)?.generation?.status !== 'RUNNING')

  return (
    <>
      <PageTitle>Finalize Applications</PageTitle>
      <p className="-mt-3 mb-4 max-w-3xl text-sm text-[var(--muted)]">
        JobPilot fills in each application with your tailored documents and then stops. It never submits, never types passwords or
        handles BankID, and never ticks consent boxes. You check each page in the browser, submit it yourself, and then confirm it here.
      </p>
      {(status.isError || apps.isError) && <p role="alert" className="text-sm text-red-600">Cannot reach the JobPilot API. Is the backend running?</p>}

      {ids.length === 0 && !status.isError && !apps.isError && (
        <Panel>
          <p className="text-sm text-[var(--muted)]">
            No applications yet. Select ads in the <Link className="underline" to="/runs">results</Link> and choose "Create tailored documents".
          </p>
        </Panel>
      )}

      {ids.length > 0 && (
        <div className="mb-5 flex max-w-5xl flex-wrap items-center gap-3 rounded-lg border border-[var(--line)] bg-[var(--panel)] px-4 py-3">
          <p role="status" className="text-sm">
            {SUMMARY.filter(([g]) => counts.get(g)).map(([g, label]) => `${counts.get(g)} ${label}`).join(' · ') || `${ids.length} not started`}
          </p>
          <Button className="ml-auto" disabled={startable.length === 0 || runAll.isPending}
            title="Opens each application in the JobPilot browser and fills it in, five at a time. Nothing is submitted."
            onClick={() => runAll.mutate(startable)}>
            {runAll.isPending ? 'Starting...' : `Fill all (${startable.length})`}
          </Button>
          {runAll.isError && <span role="alert" className="w-full text-sm text-red-600">{runAll.error.message}</span>}
        </div>
      )}

      <div className="grid max-w-5xl gap-4">
        {ids.map((id) => <ApplicationCard key={id} app={appById.get(id)} tab={tabs.get(id)} />)}
      </div>
    </>
  )
}

function ApplicationCard({ app, tab }: { app: Application | undefined; tab: TabView | undefined }) {
  const fill = useFillPage()
  const focus = useFocusTab()
  const close = useCloseTab()
  const answers = useSaveAnswers()
  const openOne = useOpenBatch()
  const submitted = useMarkSubmitted()
  const undo = useUndoSubmitted()
  const [text, setText] = useState<Record<string, string>>({})
  const [confirming, setConfirming] = useState(false)

  const jobId = (app?.jobId ?? tab?.jobId) as number
  const title = app?.title ?? tab?.title ?? `Job ${jobId}`
  const employer = app?.employer ?? tab?.employer
  const [label, style] = tab ? LABEL[tab.status] : ['Not started', 'bg-slate-200 text-slate-700']
  const hasTab = !!tab && !['EMAIL_ONLY', 'NO_APPLY_LINK', 'APPLY_MANUALLY', 'CLOSED', 'QUEUED'].includes(tab.status)
  const working = tab?.status === 'OPENING' || tab?.status === 'FILLING'
  const reopen = tab?.status === 'CLOSED' || tab?.status === 'ERROR'
  const questions = tab?.questions ?? []
  const answered = questions.filter((q) => (text[q.fieldId] ?? '').trim() !== '')
  const isSubmitted = !!app?.submittedAt
  const unapproved = app && (app.cv || app.letter) && (!app.cv?.approved || !app.letter?.approved)

  const saveAndFill = () =>
    answers.mutate(
      { jobId, answers: answered.map((q) => ({ fieldId: q.fieldId, answer: text[q.fieldId].trim() })) },
      { onSuccess: () => fill.mutate({ jobId }) },
    )

  const error = fill.error ?? focus.error ?? close.error ?? openOne.error ?? submitted.error ?? undo.error

  return (
    <section aria-label={`Application: ${title}`} className="rounded-lg border border-[var(--line)] bg-[var(--panel)] p-5">
      <div className="mb-2 flex flex-wrap items-center gap-3">
        <h2 className="font-semibold">{title}</h2>
        <span className="text-sm text-[var(--muted)]">{employer}</span>
        {isSubmitted ? (
          <span className="ml-auto rounded-full bg-emerald-600 px-2.5 py-0.5 text-xs font-medium text-white">Submitted</span>
        ) : (
          <span className={`ml-auto rounded-full px-2.5 py-0.5 text-xs font-medium ${style}`}>{label}</span>
        )}
      </div>

      {app && (
        <p className="mb-2 text-xs text-[var(--muted)]">
          {MODE_LABEL[app.searchMode]}
          {app.roleType ? ` · ${app.roleType}` : ''}
          {app.score !== null ? ` · score ${app.score}` : ''}
        </p>
      )}

      {app && (
        <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
          {app.cv ? (
            <Link className="rounded-md border border-[var(--line)] px-2 py-1 hover:border-[var(--accent)]" to={`/documents/job/${jobId}`}>
              CV v{app.cv.version} · {app.cv.approved ? 'approved' : 'to review'}
            </Link>
          ) : (
            <span className="text-[var(--muted)]">No CV yet</span>
          )}
          {app.letter ? (
            <Link className="rounded-md border border-[var(--line)] px-2 py-1 hover:border-[var(--accent)]" to={`/documents/job/${jobId}?tab=letter`}>
              Cover letter v{app.letter.version} · {app.letter.approved ? 'approved' : 'to review'}
            </Link>
          ) : (
            <span className="text-[var(--muted)]">No cover letter yet</span>
          )}
          {!app.cv && !app.letter && app.generation?.status === 'FAILED' && (
            <span className="text-red-600">Generation failed: {app.generation.message}</span>
          )}
        </div>
      )}
      {unapproved && !isSubmitted && (
        <p className="mb-2 text-xs text-amber-700">
          Only approved documents are uploaded into forms. <Link className="underline" to={`/documents/job/${jobId}`}>Review and approve them</Link> first.
        </p>
      )}

      {tab && <p className="text-sm">{tab.message}</p>}
      {tab && (tab.done.length > 0 || tab.skipped.length > 0) && (
        <p className="mt-1 text-xs text-[var(--muted)]">
          {tab.done.length} filled, {tab.skipped.length} skipped
        </p>
      )}

      {questions.length > 0 && (
        <div className="mt-4 rounded-md bg-violet-50 p-3 dark:bg-violet-950">
          <h3 className="mb-2 text-sm font-semibold">Questions for you</h3>
          <ul className="flex flex-col gap-2">
            {questions.map((q) => (
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

      <div className="mt-4 flex flex-wrap items-center gap-2">
        {!tab && !isSubmitted && (
          <Button onClick={() => openOne.mutate([jobId])} disabled={openOne.isPending}>Open and fill</Button>
        )}
        {hasTab && (
          <Button variant="ghost" onClick={() => fill.mutate({ jobId })} disabled={working || fill.isPending}>
            Fill this page
          </Button>
        )}
        {(tab?.status === 'SESSION_EXPIRED' || reopen) && !isSubmitted && (
          <Button onClick={() => fill.mutate({ jobId, restart: true })} disabled={fill.isPending}>Restart and refill</Button>
        )}
        {hasTab && <Button variant="ghost" onClick={() => focus.mutate(jobId)}>Focus tab</Button>}
        {hasTab && <Button variant="ghost" onClick={() => close.mutate(jobId)}>Close tab</Button>}
        {!isSubmitted && !confirming && (
          <Button variant="ghost" className="ml-auto" onClick={() => setConfirming(true)}>I submitted it</Button>
        )}
        {error && <span role="alert" className="self-center text-sm text-red-600">{error.message}</span>}
      </div>

      {confirming && !isSubmitted && (
        <div role="group" aria-label="Confirm submission" className="mt-3 rounded-md border border-amber-300 bg-amber-50 p-3 text-sm dark:bg-amber-950">
          <p className="mb-2">Did you submit this application yourself on the employer's site? JobPilot cannot see this, so it only records what you tell it.</p>
          <div className="flex gap-2">
            <Button onClick={() => submitted.mutate(jobId, { onSuccess: () => setConfirming(false) })} disabled={submitted.isPending}>
              Yes, I submitted it
            </Button>
            <Button variant="ghost" onClick={() => setConfirming(false)}>Not yet</Button>
          </div>
        </div>
      )}

      {isSubmitted && app && (
        <div className="mt-3 flex flex-wrap items-center gap-3 rounded-md bg-emerald-50 p-3 text-sm dark:bg-emerald-950">
          <span>Recorded as submitted on {new Date(app.submittedAt as string).toLocaleDateString('sv-SE')}.</span>
          {app.jobStatus === 'LOGGED' ? (
            <span className="text-emerald-700 dark:text-emerald-400">Logged in the aktivitetsrapport.</span>
          ) : (
            <Link className="font-medium underline" to={`/aktivitet?jobs=${jobId}`}>Log in the aktivitetsrapport</Link>
          )}
          <Button variant="ghost" className="ml-auto" onClick={() => undo.mutate(jobId)} disabled={undo.isPending || app.jobStatus === 'LOGGED'}>
            Undo
          </Button>
        </div>
      )}

      {tab && (tab.done.length > 0 || tab.skipped.length > 0) && (
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
