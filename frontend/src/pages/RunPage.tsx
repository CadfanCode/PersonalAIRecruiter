import { Link, useParams } from 'react-router-dom'
import { useCancelRun, useContinueRun, useEditProfile, useRun, useRunJobs } from '../api/runs'
import { Button } from '../components/Form'
import { PageTitle, Panel } from '../components/Panel'
import { ResultsTable } from '../components/runs/ResultsTable'
import { SearchProfileCard } from '../components/runs/SearchProfileCard'
import { StepTracker } from '../components/runs/StepTracker'
import { useToast } from '../components/Toasts'

const WORKING = ['QUEUED', 'RUNNING']

export function RunPage() {
  const runId = Number(useParams().id)
  const view = useRun(runId)
  const jobsEnabled = view.data !== undefined && view.data.run.searchProfileId !== null && view.data.run.step !== 'PROFILE'
  const jobs = useRunJobs(runId, jobsEnabled)
  const edit = useEditProfile(runId)
  const proceed = useContinueRun(runId)
  const cancel = useCancelRun(runId)
  const toast = useToast()

  if (view.isError) {
    return (
      <>
        <PageTitle>Run {runId}</PageTitle>
        <p role="alert" className="text-sm text-red-600">Could not load this run. <Link className="underline" to="/runs">Back to runs</Link></p>
      </>
    )
  }
  if (!view.data) return <PageTitle>Run {runId}</PageTitle>

  const { run, profile } = view.data
  const waiting = run.status === 'WAITING_FOR_USER'
  const working = WORKING.includes(run.status)
  const failed = run.status === 'FAILED' || run.status === 'CANCELLED'

  return (
    <>
      <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <div>
          <PageTitle>Run {run.id}</PageTitle>
          {run.prompt && <p className="-mt-3 text-sm text-[var(--muted)]">“{run.prompt}”</p>}
        </div>
        <StepTracker run={run} />
      </div>

      <div className="mb-5 flex flex-wrap items-center gap-3">
        {working && (
          <div className="flex-1">
            <div role="progressbar" aria-valuenow={run.progress} aria-valuemin={0} aria-valuemax={100} aria-label="Run progress"
              className="h-2 overflow-hidden rounded-full bg-[var(--line)]">
              <div className="h-full bg-navy transition-all" style={{ width: `${run.progress}%` }} />
            </div>
          </div>
        )}
        <p role="status" className={`text-sm ${failed ? 'text-red-600' : 'text-[var(--muted)]'}`}>{run.message}</p>
        {(working || waiting) && (
          <Button variant="danger" onClick={() => cancel.mutate()} disabled={cancel.isPending}>Cancel run</Button>
        )}
      </div>

      {profile && (
        <div className="mb-6 max-w-4xl">
          <SearchProfileCard
            key={JSON.stringify(profile)}
            profile={profile}
            editable={waiting}
            saving={edit.isPending}
            onSave={(p) => edit.mutate(p, { onSuccess: () => toast('Search profile saved'), onError: (e) => toast(e.message, 'error') })}
          />
          {edit.isError && <p role="alert" className="mt-2 text-sm text-red-600">{edit.error.message}</p>}
          {waiting && (
            <div className="mt-4 flex items-center gap-3">
              <Button onClick={() => proceed.mutate()} disabled={proceed.isPending}>Continue to search</Button>
              <span className="text-sm text-[var(--muted)]">Check the titles, municipalities and queries first: they decide what is found.</span>
            </div>
          )}
        </div>
      )}

      {jobs.data && jobs.data.length > 0 && <ResultsTable runId={runId} jobs={jobs.data} />}
      {run.status === 'DONE' && jobs.data?.length === 0 && (
        <Panel>
          <p className="text-sm text-[var(--muted)]">No ads matched. Widen the municipalities or the age limit and start a new run.</p>
        </Panel>
      )}
    </>
  )
}
