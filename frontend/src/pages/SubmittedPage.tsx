import { Link } from 'react-router-dom'
import { useCountry } from '../api/country'
import { type SubmittedSummary, useSubmitted } from '../api/submitted'
import { PageTitle, Panel } from '../components/Panel'
import { Pill } from '../components/ui/Pill'

const MODE: Record<string, string> = { TARGETED: 'Prompt search', DISCOVER: 'Discovery', MANUAL: 'Pasted link' }

export const submittedDate = (iso: string) => new Date(iso).toLocaleDateString('sv-SE')

/** Everything the user has sent, newest first, each with the exact documents that went with it. */
export function SubmittedPage() {
  const submitted = useSubmitted()
  const country = useCountry()
  const reporting = country.data?.activityReport ?? false
  const rows = submitted.data ?? []
  const toReport = rows.filter((r) => r.reportedAt === null && r.jobId !== null).length

  return (
    <>
      <PageTitle subtitle="Each application is kept as it was sent: the ad, and the exact CV and cover letter. Later edits never change this record.">
        Submitted applications
      </PageTitle>
      {submitted.isError && <p role="alert" className="text-sm text-[var(--danger)]">Cannot reach the JobPilot API. Is the backend running?</p>}
      {rows.length === 0 && !submitted.isError && (
        <Panel><p className="text-sm text-[var(--muted)]">Nothing submitted yet. When you send an application and confirm it in <Link className="text-[var(--accent)] underline" to="/submit">Submit</Link>, it is kept here.</p></Panel>
      )}
      {reporting && toReport > 0 && (
        <div className="mb-5 flex flex-wrap items-center gap-3 rounded-2xl bg-[var(--accent-soft)] px-4 py-3 text-sm">
          <span>{toReport} {toReport === 1 ? 'application is' : 'applications are'} not reported to Arbetsförmedlingen yet.</span>
          <Link to="/aktivitet" className="ml-auto font-medium text-[var(--accent)] hover:underline">Open the activity report ›</Link>
        </div>
      )}
      <ul className="grid gap-3">
        {rows.map((r) => <Item key={r.id} row={r} reporting={reporting} />)}
      </ul>
    </>
  )
}

function Item({ row: r, reporting }: { row: SubmittedSummary; reporting: boolean }) {
  return (
    <li className="rounded-2xl border border-[var(--line)] bg-[var(--panel)] p-5 shadow-[var(--shadow)]">
      <div className="flex flex-wrap items-center gap-3">
        <Link to={`/submitted/${r.id}`} className="text-[16px] font-semibold tracking-tight hover:text-[var(--accent)]">{r.title}</Link>
        <span className="text-sm text-[var(--muted)]">{r.employer}</span>
        <span className="ml-auto flex items-center gap-2">
          {reporting && (r.reportedAt ? <Pill tone="success">Reported {submittedDate(r.reportedAt)}</Pill> : <Pill tone="warn">Not reported yet</Pill>)}
          <Pill>Submitted {submittedDate(r.submittedAt)}</Pill>
        </span>
      </div>
      <p className="mt-1 text-xs text-[var(--muted)]">
        {[r.municipality, r.searchMode ? MODE[r.searchMode] ?? r.searchMode : null, r.roleType, r.scoreTotal !== null ? `score ${r.scoreTotal}` : null].filter(Boolean).join(' · ')}
      </p>
      <div className="mt-3 flex flex-wrap items-center gap-2 text-sm">
        {r.cvVersion !== null && <a className="rounded-full border border-[var(--line)] px-3 py-1 hover:border-[var(--accent)]" href={`/api/submitted/${r.id}/cv.pdf`} target="_blank" rel="noreferrer noopener">CV v{r.cvVersion} (PDF)</a>}
        {r.letterVersion !== null && <a className="rounded-full border border-[var(--line)] px-3 py-1 hover:border-[var(--accent)]" href={`/api/submitted/${r.id}/letter.pdf`} target="_blank" rel="noreferrer noopener">Cover letter v{r.letterVersion} (PDF)</a>}
        <Link to={`/submitted/${r.id}`} className="ml-auto text-[var(--accent)] hover:underline">Details ›</Link>
      </div>
    </li>
  )
}
