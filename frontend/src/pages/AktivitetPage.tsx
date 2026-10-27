import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { type AktivitetEntry, useAktivitetLog, useAktivitetPlan, useStartLogging } from '../api/aktivitet'
import { type Application, MODE_LABEL, useApplications } from '../api/applications'
import { useRun } from '../api/runs'
import { Button, TextInput } from '../components/Form'
import { PageTitle, Panel } from '../components/Panel'
import { ReminderBanner } from '../components/ReminderBanner'

const OMFATTNING = ['Heltid', 'Deltid', 'Timmar vid behov'] as const

/**
 * Confirmation step, then the run, then the result per job. What is written to the aktivitetsrapport is exactly what is
 * shown and edited here; JobPilot only saves activities and never submits the monthly report.
 */
export function AktivitetPage() {
  const [params] = useSearchParams()
  const jobIds = (params.get('jobs') ?? '').split(',').map(Number).filter((n) => Number.isInteger(n) && n > 0)
  const plan = useAktivitetPlan(jobIds)
  const [runId, setRunId] = useState<number | null>(null)
  const start = useStartLogging()
  const run = useRun(runId ?? 0, runId !== null, true)
  const log = useAktivitetLog()
  const client = useQueryClient()
  const finished = run.data?.run.status === 'DONE' || run.data?.run.status === 'FAILED' || run.data?.run.status === 'CANCELLED'
  useEffect(() => {
    if (finished) void client.invalidateQueries({ queryKey: ['aktivitet', 'log'] })
  }, [finished, client])
  const [edits, setEdits] = useState<Record<number, Partial<AktivitetEntry>>>({})
  const [excluded, setExcluded] = useState<Set<number>>(new Set())

  const entries = (plan.data ?? []).flatMap((p) => (p.entry ? [{ ...p.entry, ...edits[p.jobId] }] : []))
  const chosen = entries.filter((e) => !excluded.has(e.jobId))
  const set = (jobId: number, patch: Partial<AktivitetEntry>) => setEdits({ ...edits, [jobId]: { ...edits[jobId], ...patch } })
  const state = runId !== null ? run.data?.run : undefined
  const working = state?.status === 'QUEUED' || state?.status === 'RUNNING' || state?.status === 'WAITING_FOR_USER'
  const thisRun = (log.data ?? []).filter((r) => r.runId === runId)

  return (
    <>
      <PageTitle>Activity Report</PageTitle>
      <ReminderBanner />
      <p className="-mt-2 mb-5 max-w-3xl text-sm text-[var(--muted)]">
        JobPilot saves each applied job under "Sökta jobb" and stops there. It never submits the monthly report: do that yourself on
        Arbetsförmedlingen. You must be signed in with BankID in the JobPilot browser.
      </p>

      {jobIds.length === 0 && runId === null && (
        <Panel>
          <p className="text-sm text-[var(--muted)]">
            Submit applications from <Link className="underline" to="/finalize">Finalize Applications</Link> and confirm them there; they show up here to be logged.
          </p>
        </Panel>
      )}

      {plan.data && runId === null && (
        <Panel title="Check what will be written">
          <p className="mb-3 text-xs text-[var(--muted)]">Every value below goes into the form exactly as shown. Edit anything that is wrong.</p>
          <div className="flex flex-col gap-4">
            {plan.data.map((p) =>
              p.entry ? (
                <PlanRow key={p.jobId} entry={{ ...p.entry, ...edits[p.jobId] }} included={!excluded.has(p.jobId)}
                  onInclude={(on) => setExcluded((s) => { const n = new Set(s); if (on) n.delete(p.jobId); else n.add(p.jobId); return n })}
                  onChange={(patch) => set(p.jobId, patch)} />
              ) : (
                <p key={p.jobId} className="rounded-md border border-[var(--line)] p-3 text-sm text-[var(--muted)]">
                  <strong className="text-[var(--ink)]">{p.title}:</strong> {p.ineligible}
                </p>
              ),
            )}
          </div>
          <div className="mt-4 flex items-center gap-3">
            <Button disabled={chosen.length === 0 || start.isPending}
              onClick={() => start.mutate(chosen, { onSuccess: ({ runId: id }) => setRunId(id) })}>
              Save {chosen.length} in the aktivitetsrapport
            </Button>
            {start.isError && <span role="alert" className="text-sm text-red-600">{start.error.message}</span>}
          </div>
        </Panel>
      )}

      {state && (
        <Panel title="Progress">
          {working && (
            <div role="progressbar" aria-label="Logging progress" aria-valuenow={state.progress} aria-valuemin={0} aria-valuemax={100}
              className="mb-2 h-2 overflow-hidden rounded-full bg-[var(--line)]">
              <div className="h-full bg-navy transition-all" style={{ width: `${state.progress}%` }} />
            </div>
          )}
          <p role="status" className={`text-sm ${state.status === 'FAILED' ? 'text-red-600' : state.status === 'WAITING_FOR_USER' ? 'text-amber-700' : ''}`}>
            {state.message}
          </p>
          {!working && thisRun.length > 0 && <ResultTable rows={thisRun} />}
        </Panel>
      )}

      <SubmittedApplications />

      {(log.data?.length ?? 0) > 0 && (
        <Panel title="Everything logged so far">
          <ResultTable rows={log.data!} />
        </Panel>
      )}
    </>
  )
}

const docsUsed = (a: Application) =>
  [a.submittedDocs?.cv && `CV v${a.submittedDocs.cv.version}`, a.submittedDocs?.letter && `letter v${a.submittedDocs.letter.version}`]
    .filter(Boolean)
    .join(', ') || 'none recorded'

/** What the user said they submitted through Finalize Applications. Logging to Arbetsförmedlingen is a separate, confirmed step. */
function SubmittedApplications() {
  const apps = useApplications()
  const submitted = (apps.data ?? []).filter((a) => a.submittedAt)
  if (submitted.length === 0) return null
  const notLogged = submitted.filter((a) => a.jobStatus !== 'LOGGED')
  return (
    <Panel title="Applications you submitted">
      <table className="w-full text-left text-sm">
        <thead className="text-xs uppercase text-[var(--muted)]">
          <tr>
            <th className="py-1 font-semibold">Job</th><th className="font-semibold">Company</th><th className="font-semibold">Role type</th>
            <th className="font-semibold">Search mode</th><th className="font-semibold">Date</th><th className="font-semibold">Documents</th>
            <th className="font-semibold">Status</th>
          </tr>
        </thead>
        <tbody>
          {submitted.map((a) => (
            <tr key={a.jobId} className="border-t border-[var(--line)] align-top">
              <td className="py-1.5">{a.title}</td>
              <td>{a.employer}</td>
              <td>{a.roleType ?? ''}</td>
              <td>{MODE_LABEL[a.searchMode]}</td>
              <td>{new Date(a.submittedAt as string).toLocaleDateString('sv-SE')}</td>
              <td className="text-[var(--muted)]">{docsUsed(a)}</td>
              <td>
                {a.jobStatus === 'LOGGED' ? (
                  <span className="rounded-full bg-teal-100 px-2 py-0.5 text-xs font-medium text-teal-800">Logged</span>
                ) : (
                  <span className="rounded-full bg-emerald-100 px-2 py-0.5 text-xs font-medium text-emerald-800">Submitted</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {notLogged.length > 0 && (
        <p className="mt-3 text-sm">
          <Link className="font-medium underline" to={`/aktivitet?jobs=${notLogged.map((a) => a.jobId).join(',')}`}>
            Log {notLogged.length} in the aktivitetsrapport
          </Link>
          <span className="ml-2 text-xs text-[var(--muted)]">You confirm every value before anything is saved.</span>
        </p>
      )}
    </Panel>
  )
}

function PlanRow({ entry, included, onInclude, onChange }: {
  entry: AktivitetEntry
  included: boolean
  onInclude: (on: boolean) => void
  onChange: (patch: Partial<AktivitetEntry>) => void
}) {
  const label = entry.title
  return (
    <fieldset className={`rounded-md border border-[var(--line)] p-3 ${included ? '' : 'opacity-50'}`}>
      <legend className="px-1 text-sm font-medium">
        <label className="flex items-center gap-2">
          <input type="checkbox" checked={included} onChange={(e) => onInclude(e.target.checked)} aria-label={`Include ${label}`} />
          {label}
        </label>
      </legend>
      <div className="grid grid-cols-3 gap-3 text-xs font-medium text-[var(--muted)]">
        <label className="flex flex-col gap-1">Yrkesroll
          <TextInput aria-label={`Yrkesroll for ${label}`} value={entry.yrkesroll} onChange={(e) => onChange({ yrkesroll: e.target.value })} /></label>
        <label className="flex flex-col gap-1">Arbetsgivaren
          <TextInput aria-label={`Arbetsgivaren for ${label}`} value={entry.employerLabel} onChange={(e) => onChange({ employerLabel: e.target.value })} /></label>
        <label className="flex flex-col gap-1">Ort
          <TextInput aria-label={`Ort for ${label}`} value={entry.municipality} onChange={(e) => onChange({ municipality: e.target.value })} /></label>
        <label className="flex flex-col gap-1">Omfattning
          <select aria-label={`Omfattning for ${label}`} value={entry.omfattning} onChange={(e) => onChange({ omfattning: e.target.value as AktivitetEntry['omfattning'] })}
            className="rounded-md border border-[var(--line)] bg-[var(--surface)] px-2.5 py-1.5 text-sm text-[var(--ink)]">
            {OMFATTNING.map((o) => <option key={o}>{o}</option>)}
          </select></label>
        <label className="flex flex-col gap-1">Var finns jobbet?
          <TextInput aria-label={`Var finns jobbet for ${label}`} value={entry.varFinns} disabled /></label>
        <label className="flex flex-col gap-1">Svarade du på en annons?
          <select aria-label={`Annons for ${label}`} value={entry.answeredAd ? 'Ja' : 'Nej'} onChange={(e) => onChange({ answeredAd: e.target.value === 'Ja' })}
            className="rounded-md border border-[var(--line)] bg-[var(--surface)] px-2.5 py-1.5 text-sm text-[var(--ink)]">
            <option>Ja</option><option>Nej</option>
          </select></label>
        <label className="flex flex-col gap-1">Vilket datum sökte du jobbet?
          <TextInput type="date" aria-label={`Datum for ${label}`} value={entry.appliedDate} onChange={(e) => onChange({ appliedDate: e.target.value })} /></label>
      </div>
    </fieldset>
  )
}

const STYLE: Record<string, string> = {
  SAVED: 'bg-emerald-100 text-emerald-800', DUPLICATE: 'bg-sky-100 text-sky-800', SKIPPED: 'bg-slate-200 text-slate-700',
  UNCONFIRMED: 'bg-amber-100 text-amber-800', FAILED: 'bg-red-100 text-red-800',
}

function ResultTable({ rows }: { rows: { id: number; employerLabel: string; appliedDate: string | null; result: string; message: string | null }[] }) {
  return (
    <table className="mt-3 w-full text-left text-sm">
      <thead className="text-xs uppercase text-[var(--muted)]">
        <tr><th className="py-1 font-semibold">Employer</th><th className="font-semibold">Date</th><th className="font-semibold">Result</th><th className="font-semibold">Note</th></tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.id} className="border-t border-[var(--line)] align-top">
            <td className="py-1.5">{r.employerLabel}</td>
            <td>{r.appliedDate}</td>
            <td><span className={`rounded-full px-2 py-0.5 text-xs font-medium ${STYLE[r.result] ?? ''}`}>{r.result.toLowerCase()}</span></td>
            <td className="text-[var(--muted)]">{r.message}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
