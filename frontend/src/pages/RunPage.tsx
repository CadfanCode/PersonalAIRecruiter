import { useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { Link, useParams } from 'react-router-dom'
import { type Run, type SearchProfile, useCancelRun, useContinueRun, useEditProfile, useProvideAdText, useRoles, useRun, useRunJobs, useSaveRoles } from '../api/runs'
import { Button } from '../components/Form'
import { PageTitle, Panel } from '../components/Panel'
import { rememberRun } from '../components/shell/flow'
import { PasteAdPanel } from '../components/runs/PasteAdPanel'
import { ProfileSection } from '../components/runs/ProfileSection'
import { ResultsTable } from '../components/runs/ResultsTable'
import { RoleSuggestions } from '../components/runs/RoleSuggestions'
import { useToast } from '../components/Toasts'
import { Processing } from '../components/ui/Processing'

const WORKING = ['QUEUED', 'RUNNING']

/** Discover finds broad results: ads scoring below this are hidden until the user widens the filter. Aggressive mode lowers the bar. */
const DISCOVER_MIN_SCORE = 40
const DISCOVER_AGGRESSIVE_MIN_SCORE = 25

function stageTitle(run: Run): string {
  switch (run.step) {
    case 'PROFILE':
      return 'Making your search plan'
    case 'SUGGEST':
      return 'Finding roles that fit you'
    case 'FETCH':
      return 'Reading the ad'
    case 'SEARCH':
      return 'Searching for jobs'
    case 'RANK':
      return 'Scoring the ads against your CV'
    default:
      return 'Working'
  }
}

/** Stage 2, Search and ranking: progress with the time left, results as they are found and scored, and your selection. */
export function RunPage() {
  const runId = Number(useParams().id)
  const view = useRun(runId)
  const status = view.data?.run.status
  const jobsEnabled = view.data !== undefined && view.data.run.searchProfileId !== null && view.data.run.step !== 'PROFILE'
  const jobs = useRunJobs(runId, jobsEnabled, status !== undefined && WORKING.includes(status))
  const client = useQueryClient()
  useEffect(() => rememberRun(runId), [runId])
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
        <PageTitle>Search {runId}</PageTitle>
        <p role="alert" className="text-sm text-[var(--danger)]">Could not load this search. <Link className="underline" to="/">Back to Mode</Link></p>
      </>
    )
  }
  if (!view.data) return <PageTitle>Your search</PageTitle>

  const { run, profile, etaSeconds } = view.data
  const save = (p: SearchProfile) =>
    edit.mutate(p, { onSuccess: () => toast('Search plan saved'), onError: (e) => toast(e.message, 'error') })
  const waiting = run.status === 'WAITING_FOR_USER'
  const working = WORKING.includes(run.status)
  const failed = run.status === 'FAILED' || run.status === 'CANCELLED'
  const manual = run.options.mode === 'MANUAL'
  const title = discover ? 'Roles that fit you' : manual ? 'Your job' : 'Your search'
  const found = jobs.data?.length ?? 0

  return (
    <>
      <PageTitle subtitle={run.prompt && !discover && !manual ? `“${run.prompt}”` : undefined}>{title}</PageTitle>

      {working && (
        <div className="mb-8">
          <Processing title={stageTitle(run)} percent={run.progress} message={run.message} etaSeconds={etaSeconds} onCancel={() => cancel.mutate()}>
            {found > 0 && <p className="text-sm font-medium text-[var(--accent)]">{found} {found === 1 ? 'ad' : 'ads'} found so far: they appear below as they are scored.</p>}
          </Processing>
        </div>
      )}

      {failed && (
        <Panel className="mb-8">
          <p role="alert" className="text-[15px] text-[var(--danger)]">{run.message}</p>
          <div className="mt-4"><Link to="/"><Button variant="ghost">Back to Mode</Button></Link></div>
        </Panel>
      )}

      {manual && (
        <div className="mb-6 max-w-4xl">
          {run.sourceUrl && <p className="mb-2 text-sm text-[var(--muted)]">Pasted link: <span className="break-all">{run.sourceUrl}</span></p>}
          {waiting && run.step === 'FETCH' && (
            <PasteAdPanel url={run.sourceUrl} message={run.message} sending={provideText.isPending}
              error={provideText.isError ? provideText.error.message : null} onSubmit={(text) => provideText.mutate(text)} />
          )}
        </div>
      )}

      {discover && roles.data && roles.data.length > 0 && (
        <div className="mb-8 max-w-4xl">
          <RoleSuggestions key={`${runId}-${waiting}`} roles={roles.data} options={run.options} waiting={waiting}
            saving={saveRoles.isPending || proceed.isPending}
            error={saveRoles.isError ? saveRoles.error.message : proceed.isError ? proceed.error.message : null}
            onSearch={(edits) => saveRoles.mutate(edits, { onSuccess: () => proceed.mutate() })} />
        </div>
      )}

      {profile && !discover && (
        <div className="mb-8 max-w-4xl">
          {waiting ? (
            <>
              <p className="mb-3 max-w-2xl text-[15px] leading-relaxed text-[var(--muted)]">
                This is the search plan made from your words. Change anything that is off, then approve it: approving starts the search.
              </p>
              <ProfileSection profile={profile} waiting saving={edit.isPending} error={edit.isError ? edit.error.message : null}
                onSave={save} onContinue={() => proceed.mutate()} continuing={proceed.isPending} />
            </>
          ) : (
            // After the review the plan is reference material; the results come first.
            <details className="rounded-2xl border border-[var(--line)] bg-[var(--panel)] px-5 py-3">
              <summary className="cursor-pointer text-sm font-medium text-[var(--muted)]">Search plan used for this search</summary>
              <div className="mt-3"><ProfileSection profile={profile} waiting={false} saving={false} error={null} onSave={save} onContinue={() => {}} continuing={false} /></div>
            </details>
          )}
        </div>
      )}
      {jobs.data && jobs.data.length > 0 && <ResultsTable runId={runId} jobs={jobs.data} preselectAll={manual}
        defaultMinScore={discover ? (run.options.aggressive ? DISCOVER_AGGRESSIVE_MIN_SCORE : DISCOVER_MIN_SCORE) : undefined} />}
      {run.status === 'DONE' && jobs.data?.length === 0 && !jobs.isFetching && (
        <Panel>
          <p className="text-sm text-[var(--muted)]">No ads matched. Try other words, a wider place or an older age in your prompt, and search again.</p>
        </Panel>
      )}
    </>
  )
}
