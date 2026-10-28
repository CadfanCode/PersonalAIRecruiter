import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { type AktivitetEntry, useAktivitetLog, useAktivitetPlan, useStartLogging } from '../api/aktivitet'
import { useCountry } from '../api/country'
import { useSubmitted } from '../api/submitted'
import { useRun } from '../api/runs'
import { Button, TextInput } from '../components/Form'
import { PageTitle, Panel } from '../components/Panel'
import { ReminderBanner } from '../components/ReminderBanner'
import { Pill } from '../components/ui/Pill'
import { Processing } from '../components/ui/Processing'

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
  const country = useCountry()
  const working = state?.status === 'QUEUED' || state?.status === 'RUNNING' || state?.status === 'WAITING_FOR_USER'
  const thisRun = (log.data ?? []).filter((r) => r.runId === runId)

  if (country.data && !country.data.activityReport) {
    return (
      <>
        <PageTitle>Activity report</PageTitle>
        <Panel><p className="text-sm text-[var(--muted)]">The activity report is only available for Sweden (Arbetsförmedlingen).</p></Panel>
      </>
    )
  }

  return (
    <>
      <PageTitle subtitle="JobPilot saves each application you submitted under 'Sökta jobb' on Arbetsförmedlingen and stops there. It never submits the monthly report: do that yourself. You sign in with BankID yourself, in the JobPilot browser.">
        Activity report
      </PageTitle>
      <ReminderBanner />

      {jobIds.length === 0 && runId === null && (
        <Panel>
          <p className="text-sm text-[var(--muted)]">
            Applications you confirm as submitted show up here, ready to be reported. Start from <Link className="text-[var(--accent)] underline" to="/submit">Submit</Link>.
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
            {start.isError && <span role="alert" className="text-sm text-[var(--danger)]">{start.error.message}</span>}
          </div>
        </Panel>
      )}

      {state && working && <div className="mb-6"><Processing title="Saving to the activity report" percent={state.progress} message={state.message} /></div>}
      {state && !working && (
        <Panel title="Result" className="mb-6">
          <p role="status" className={`text-sm ${state.status === 'FAILED' ? 'text-[var(--danger)]' : ''}`}>{state.message}</p>
          {thisRun.length > 0 && <ResultTable rows={thisRun} />}
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

/** What the user submitted, split into what still has to be reported and what already was. Reported ones are left out by default. */
function SubmittedApplications() {
  const submitted = useSubmitted()
  const all = submitted.data ?? []
  const todo = all.filter((a) => a.reportedAt === null && a.jobId !== null)
  const reported = all.filter((a) => a.reportedAt !== null)
  if (all.length === 0) return null
  return (
    <>
      <Panel title="Ready to report">
        {todo.length === 0 ? (
          <p className="text-sm text-[var(--muted)]">Everything you submitted has been reported.</p>
        ) : (
          <>
            <ul className="flex flex-col divide-y divide-[var(--line)]">
              {todo.map((a) => (
                <li key={a.id} className="flex flex-wrap items-center gap-3 py-2.5 text-sm">
                  <Link to={`/submitted/${a.id}`} className="font-medium hover:text-[var(--accent)]">{a.title}</Link>
                  <span className="text-[var(--muted)]">{a.employer}</span>
                  <span className="ml-auto text-xs text-[var(--muted)]">submitted {new Date(a.submittedAt).toLocaleDateString('sv-SE')}</span>
                </li>
              ))}
            </ul>
            <p className="mt-4 text-sm">
              <Link className="inline-flex" to={`/aktivitet?jobs=${todo.map((a) => a.jobId).join(',')}`}>
                <Button size="lg">Report {todo.length} to Arbetsförmedlingen</Button>
              </Link>
              <span className="ml-3 text-xs text-[var(--muted)]">You confirm every value before anything is saved.</span>
            </p>
          </>
        )}
      </Panel>
      {reported.length > 0 && (
        <details className="mt-4 rounded-2xl border border-[var(--line)] bg-[var(--panel)] px-5 py-3">
          <summary className="cursor-pointer text-sm font-medium text-[var(--muted)]">Already reported ({reported.length})</summary>
          <ul className="mt-2 flex flex-col divide-y divide-[var(--line)]">
            {reported.map((a) => (
              <li key={a.id} className="flex flex-wrap items-center gap-3 py-2 text-sm">
                <Link to={`/submitted/${a.id}`} className="font-medium hover:text-[var(--accent)]">{a.title}</Link>
                <span className="text-[var(--muted)]">{a.employer}</span>
                <Pill tone="success" className="ml-auto">Reported {new Date(a.reportedAt as string).toLocaleDateString('sv-SE')}</Pill>
              </li>
            ))}
          </ul>
        </details>
      )}
    </>
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
            className="rounded-xl border border-[var(--line)] bg-[var(--panel-2)] px-2.5 py-1.5 text-sm text-[var(--ink)]">
            {OMFATTNING.map((o) => <option key={o}>{o}</option>)}
          </select></label>
        <label className="flex flex-col gap-1">Var finns jobbet?
          <TextInput aria-label={`Var finns jobbet for ${label}`} value={entry.varFinns} disabled /></label>
        <label className="flex flex-col gap-1">Svarade du på en annons?
          <select aria-label={`Annons for ${label}`} value={entry.answeredAd ? 'Ja' : 'Nej'} onChange={(e) => onChange({ answeredAd: e.target.value === 'Ja' })}
            className="rounded-xl border border-[var(--line)] bg-[var(--panel-2)] px-2.5 py-1.5 text-sm text-[var(--ink)]">
            <option>Ja</option><option>Nej</option>
          </select></label>
        <label className="flex flex-col gap-1">Vilket datum sökte du jobbet?
          <TextInput type="date" aria-label={`Datum for ${label}`} value={entry.appliedDate} onChange={(e) => onChange({ appliedDate: e.target.value })} /></label>
      </div>
    </fieldset>
  )
}

const STYLE: Record<string, string> = {
  SAVED: 'bg-[var(--success-soft)] text-[var(--success)]', DUPLICATE: 'bg-[var(--accent-soft)] text-[var(--accent)]', SKIPPED: 'bg-[var(--line)] text-[var(--muted)]',
  UNCONFIRMED: 'bg-[var(--warn-soft)] text-[var(--warn)]', FAILED: 'bg-[var(--danger-soft)] text-[var(--danger)]',
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
