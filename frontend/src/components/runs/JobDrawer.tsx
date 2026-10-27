import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { type JobRow, useJobDetail, usePatchJob } from '../../api/runs'
import { Button, TextArea } from '../Form'
import { useToast } from '../Toasts'
import { shortDate } from './format'
import { ScoreBreakdown } from './ScoreBar'
import { StatusChip } from './StatusChip'

/** Side drawer: the whole ad, the score breakdown, links, notes and the actions for one job. */
export function JobDrawer({ runId, jobId, onClose }: { runId: number; jobId: number | null; onClose: () => void }) {
  const detail = useJobDetail(jobId, runId)
  const patch = usePatchJob(runId)
  const toast = useToast()
  const navigate = useNavigate()
  const row: JobRow | undefined = detail.data?.row

  if (jobId === null) return null

  const update = (body: { status?: JobRow['status']; notes?: string }, done?: string) =>
    patch.mutate({ id: jobId, ...body }, {
      onSuccess: () => done && toast(done),
      onError: (e) => toast(e.message, 'error'),
    })

  return (
    <aside role="dialog" aria-label="Job details"
      className="fixed inset-y-0 right-0 z-40 w-[34rem] max-w-full overflow-y-auto border-l border-[var(--line)] bg-[var(--panel)] p-6 shadow-2xl">
      <div className="mb-4 flex items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold">{row?.title ?? 'Loading...'}</h2>
          {row && (
            <p className="text-sm text-[var(--muted)]">
              {row.employer}{row.clientEmployer ? ` (${row.clientEmployer})` : ''} · {row.municipality ?? row.location}
            </p>
          )}
        </div>
        <Button variant="ghost" onClick={onClose} aria-label="Close details">Close</Button>
      </div>

      {detail.isError && <p role="alert" className="text-sm text-red-600">Could not load the ad.</p>}

      {row && (
        <div className="flex flex-col gap-5">
          <div className="flex flex-wrap items-center gap-3 text-sm">
            <StatusChip status={row.status} />
            <span>Published {shortDate(row.publishedAt) || 'unknown'}</span>
            <span>Deadline {shortDate(row.deadline) || 'none'}</span>
            {row.dateUnverified && <span className="text-amber-600">date unverified</span>}
            {row.asksForNewGraduates && <span className="rounded bg-sky-100 px-1.5 text-sky-800">asks for new graduates</span>}
          </div>

          <div className="flex flex-wrap gap-2">
            <Button variant="ghost" onClick={() => update({ status: 'SHORTLISTED' }, 'Shortlisted')}>Shortlist</Button>
            <Button variant="ghost" onClick={() => navigate(`/documents/job/${jobId}?from=${runId}`)}>
              {row.status === 'DOCS_READY' ? 'Open documents' : 'Documents'}
            </Button>
            <Button variant="ghost" disabled title="Arrives in milestone 5">Open application</Button>
            <Button variant="ghost" onClick={() => update({ status: 'APPLIED' }, 'Marked as applied')}>Mark applied</Button>
            <Button variant="ghost" disabled title="Arrives in milestone 6">Log in aktivitetsrapport</Button>
            <Button variant="danger" onClick={() => update({ status: 'REJECTED' }, 'Rejected')}>Reject</Button>
          </div>

          {row.score && (
            <section aria-label="Score">
              <h3 className="mb-2 text-sm font-semibold">Score {row.score.total}/100</h3>
              <ScoreBreakdown score={row.score} />
              {row.score.fit && <p className="mt-3 text-sm"><strong>Fit:</strong> {row.score.fit}</p>}
              {row.score.gaps && <p className="mt-1 text-sm"><strong>Gaps:</strong> {row.score.gaps}</p>}
              {row.score.dealBreakers.length > 0 && (
                <p className="mt-1 text-sm text-red-600"><strong>Deal breakers:</strong> {row.score.dealBreakers.join('; ')}</p>
              )}
              {row.experienceRequiredText && (
                <p className="mt-1 text-sm text-[var(--muted)]">Experience asked: {row.experienceRequiredText}</p>
              )}
            </section>
          )}

          <section aria-label="Links" className="flex gap-4 text-sm">
            {(row.applyUrl ?? row.url) && (
              <a className="text-[var(--accent)] underline" href={(row.applyUrl ?? row.url)!} target="_blank" rel="noopener noreferrer">
                Apply link
              </a>
            )}
            {row.url && row.url !== row.applyUrl && (
              <a className="text-[var(--accent)] underline" href={row.url} target="_blank" rel="noopener noreferrer">Original ad</a>
            )}
          </section>

          <NotesBox key={`${row.id}-${row.notes}`} saved={row.notes ?? ''} onSave={(notes) => update({ notes }, 'Notes saved')} />

          <section aria-label="Ad text">
            <h3 className="mb-2 text-sm font-semibold">Ad</h3>
            <p className="whitespace-pre-wrap text-sm leading-relaxed">{detail.data?.description}</p>
          </section>
        </div>
      )}
    </aside>
  )
}

/** Remounted (via key) whenever the saved notes change, so the draft always starts from the saved text. */
function NotesBox({ saved, onSave }: { saved: string; onSave: (notes: string) => void }) {
  const [notes, setNotes] = useState(saved)
  return (
    <section aria-label="Job notes">
      <label className="mb-1 block text-xs font-medium text-[var(--muted)]" htmlFor="job-notes">Notes</label>
      <TextArea id="job-notes" rows={3} value={notes} onChange={(e) => setNotes(e.target.value)} />
      <div className="mt-2">
        <Button variant="ghost" disabled={notes === saved} onClick={() => onSave(notes)}>Save notes</Button>
      </div>
    </section>
  )
}
