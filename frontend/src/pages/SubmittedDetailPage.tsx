import { Link, useParams } from 'react-router-dom'
import { useCountry } from '../api/country'
import { useSubmittedDetail } from '../api/submitted'
import { PageTitle, Panel } from '../components/Panel'
import { Pill } from '../components/ui/Pill'
import { submittedDate } from './SubmittedPage'

/** One submitted application, as it was sent: the ad text as it was, and the CV and cover letter PDFs that went with it. */
export function SubmittedDetailPage() {
  const id = Number(useParams().id)
  const detail = useSubmittedDetail(id)
  const country = useCountry()
  if (detail.isError) {
    return <><PageTitle>Submitted application</PageTitle><p role="alert" className="text-sm text-[var(--danger)]">Could not load it. <Link className="underline" to="/submitted">Back to the list</Link></p></>
  }
  if (!detail.data) return <PageTitle>Submitted application</PageTitle>
  const { summary: s, adText } = detail.data

  return (
    <>
      <Link to="/submitted" className="mb-3 inline-flex text-sm text-[var(--accent)] hover:underline">‹ Submitted applications</Link>
      <PageTitle subtitle={[s.employer, s.municipality].filter(Boolean).join(' · ')}>{s.title}</PageTitle>
      <div className="mb-6 flex flex-wrap items-center gap-2">
        <Pill tone="success">Submitted {submittedDate(s.submittedAt)}</Pill>
        {country.data?.activityReport && (s.reportedAt ? <Pill tone="success">Reported {submittedDate(s.reportedAt)}</Pill> : <Pill tone="warn">Not reported yet</Pill>)}
        {s.url && <a className="text-sm text-[var(--accent)] hover:underline" href={s.url} target="_blank" rel="noreferrer noopener">Original ad ↗</a>}
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        {s.cvVersion !== null && (
          <Panel title={`CV as sent (version ${s.cvVersion})`}>
            <iframe title="CV as sent" src={`/api/submitted/${s.id}/cv.pdf`} className="h-[70vh] w-full rounded-xl border border-[var(--line)] bg-white" />
          </Panel>
        )}
        {s.letterVersion !== null && (
          <Panel title={`Cover letter as sent (version ${s.letterVersion})`}>
            <iframe title="Cover letter as sent" src={`/api/submitted/${s.id}/letter.pdf`} className="h-[70vh] w-full rounded-xl border border-[var(--line)] bg-white" />
          </Panel>
        )}
      </div>

      {adText && (
        <details className="mt-6 rounded-2xl border border-[var(--line)] bg-[var(--panel)] px-5 py-4">
          <summary className="cursor-pointer text-sm font-medium">The ad as it was when you applied</summary>
          <pre className="mt-3 whitespace-pre-wrap font-sans text-sm leading-relaxed text-[var(--muted)]">{adText}</pre>
        </details>
      )}
    </>
  )
}
