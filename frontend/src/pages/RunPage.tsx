import { useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { Link, useParams } from 'react-router-dom'
import { type SearchProfile, useCancelRun, useContinueRun, useEditProfile, useProvideAdText, useRoles, useRun, useRunJobs, useSaveRoles } from '../api/runs'
import { Button } from '../components/Form'
import { PageTitle, Panel } from '../components/Panel'
import { ResultsTable } from '../components/runs/ResultsTable'
import { ProfileSection } from '../components/runs/ProfileSection'
import { PasteAdPanel } from '../components/runs/PasteAdPanel'
import { RoleSuggestions } from '../components/runs/RoleSuggestions'
import { StepTracker } from '../components/runs/StepTracker'
import { useToast } from '../components/Toasts'

const WORKING = ['QUEUED', 'RUNNING']

/** Discover finds broad results: ads scoring below this are hidden until the user widens the filter. Aggressive mode lowers the bar. */
const DISCOVER_MIN_SCORE = 40
const DISCOVER_AGGRESSIVE_MIN_SCORE = 25

export function RunPage() {
  const runId = Number(useParams().id)
  const view = useRun(runId)
  const status = view.data?.run.status
  const jobsEnabled = view.data !== undefined && view.data.run.searchProfileId !== null && view.data.run.step !== 'PROFILE'
  const jobs = useRunJobs(runId, jobsEnabled, status !== undefined && WORKING.includes(status))
  const client = useQueryClient()
  // The last poll can land before the final scores are written, so fetch once more when the run ends.
  useEffect(() => {
    if (status && !WORKING.includes(status)) void client.invalidateQueries({ queryKey: ['runs', runId, 'jobs'] })
  }, [status, runId, client])
  const edit = useEditProfile(runId)
  const proceed = useContinueRun(runId)
  const cancel = useCancelRun(runId)
  const toast = useToast()
  const discover = view.data?.run.options.mode === 'DISCOVER'
  const rolesReady = discover && (status === 'WAITING_FOR_USER' || status === 'DONE' || view.data?.run.step === 'SEARCH' || view.data?.run.step === 'RANK')
  const roles = useRoles(runId, rolesReady)
  const saveRoles = useSaveRoles(runId)
  const provideText = useProvideAdText(runId)

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
  const save = (p: SearchProfile) =>
    edit.mutate(p, { onSuccess: () => toast('Search profile saved'), onError: (e) => toast(e.message, 'error') })
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

      {run.options.mode === 'MANUAL' && (
        <div className="mb-5 max-w-4xl">
          {run.sourceUrl && <p className="mb-2 text-sm text-[var(--muted)]">Pasted link: <span className="break-all">{run.sourceUrl}</span></p>}
          {waiting && run.step === 'FETCH' && (
            <PasteAdPanel url={run.sourceUrl} message={run.message} sending={provideText.isPending}
              error={provideText.isError ? provideText.error.message : null} onSubmit={(text) => provideText.mutate(text)} />
          )}
        </div>
      )}

      {discover && roles.data && roles.data.length > 0 && (
        <div className="mb-6 max-w-4xl">
          <RoleSuggestions key={`${runId}-${waiting}`} roles={roles.data} options={run.options} waiting={waiting}
            saving={saveRoles.isPending || proceed.isPending}
            error={saveRoles.isError ? saveRoles.error.message : proceed.isError ? proceed.error.message : null}
            onSearch={(edits) => saveRoles.mutate(edits, { onSuccess: () => proceed.mutate() })} />
        </div>
      )}

      {profile && (
        <div className="mb-6 max-w-4xl">
          {waiting ? (
            <>
              {discover && <p className="mb-2 text-sm text-[var(--muted)]">Where and when to search (optional): the roles above use these locations and this age limit.</p>}
              <ProfileSection profile={profile} waiting saving={edit.isPending} error={edit.isError ? edit.error.message : null}
                onSave={save} onContinue={() => proceed.mutate()} continuing={proceed.isPending} showContinue={!discover} />
            </>
          ) : (
            // After the review the profile is reference material; the results come first.
            <details>
              <summary className="mb-2 cursor-pointer text-sm font-medium text-[var(--muted)]">Search profile used for this run</summary>
              <ProfileSection profile={profile} waiting={false} saving={false} error={null} onSave={save} onContinue={() => {}} continuing={false} />
            </details>
          )}
        </div>
      )}

      {jobs.data && jobs.data.length > 0 && <ResultsTable runId={runId} jobs={jobs.data} preselectAll={run.options.mode === 'MANUAL'}
        defaultMinScore={discover ? (run.options.aggressive ? DISCOVER_AGGRESSIVE_MIN_SCORE : DISCOVER_MIN_SCORE) : undefined} />}
      {run.status === 'DONE' && jobs.data?.length === 0 && !jobs.isFetching && (
        <Panel>
          <p className="text-sm text-[var(--muted)]">No ads matched. Widen the municipalities or the age limit and start a new run.</p>
        </Panel>
      )}
    </>
  )
}
