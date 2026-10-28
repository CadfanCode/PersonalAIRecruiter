import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'

export type RunStatus = 'QUEUED' | 'RUNNING' | 'WAITING_FOR_USER' | 'DONE' | 'FAILED' | 'CANCELLED'
export type JobStatus = 'NEW' | 'SHORTLISTED' | 'DOCS_READY' | 'FILLED' | 'APPLIED' | 'LOGGED' | 'REJECTED' | 'ARCHIVED'

export const JOB_STATUSES: JobStatus[] = ['NEW', 'SHORTLISTED', 'DOCS_READY', 'FILLED', 'APPLIED', 'LOGGED', 'REJECTED', 'ARCHIVED']

export interface SearchProfile {
  roleSummary: string
  titles: string[]
  synonyms: string[]
  skillsWanted: string[]
  seniority: 'student' | 'graduate' | 'junior' | 'mid' | 'senior' | 'any'
  acceptExperienceUpToYears: number
  locations: { name: string; municipality: string; radiusKm: number }[]
  remoteOk: boolean
  languages: ('sv' | 'en')[]
  maxAgeDays: number
  employmentTypes: ('heltid' | 'deltid' | 'timmar')[]
  exclusions: string[]
  yrkesrollForAktivitetsrapport: string
  relevantTags: string[]
  jobtechQueries: string[]
}

export type RunMode = 'TARGETED' | 'DISCOVER' | 'MANUAL'

export interface RunOptions {
  maxAgeDays: number | null
  limit: number | null
  includeWeb: boolean | null
  autoContinue: boolean | null
  mode?: RunMode | null
  aggressive?: boolean | null
}

/** Ads a Discover run scores when the user sets no limit, and the most it will ever score. */
export const DISCOVER_LIMIT = 60
export const DISCOVER_AGGRESSIVE_LIMIT = 150
export const DISCOVER_MAX_LIMIT = 150

export interface Run {
  id: number
  searchProfileId: number | null
  status: RunStatus
  step: 'PROFILE' | 'SUGGEST' | 'FETCH' | 'SEARCH' | 'RANK' | 'DONE' | 'SELECT' | 'CV' | 'LETTER' | 'RENDER' | 'OPEN' | 'CHECK' | 'SAVE'
  progress: number
  message: string | null
  startedAt: string
  finishedAt: string | null
  options: RunOptions
  prompt: string | null
  kind: 'SEARCH' | 'DOCUMENTS' | 'AKTIVITET'
  jobId: number | null
  /** MANUAL runs: the address the user pasted. */
  sourceUrl?: string | null
}

export interface RunView {
  run: Run
  profile: SearchProfile | null
}

export interface Score {
  total: number
  skills: number
  experience: number
  eligibility: number
  dealBreakers: string[]
  fit: string | null
  gaps: string | null
  model: string | null
}

export interface JobRow {
  id: number
  rank: number | null
  source: string
  url: string | null
  applyUrl: string | null
  title: string
  employer: string | null
  clientEmployer: string | null
  location: string | null
  municipality: string | null
  publishedAt: string | null
  deadline: string | null
  language: 'sv' | 'en' | null
  employmentType: string | null
  asksForNewGraduates: boolean
  experienceRequiredText: string | null
  status: JobStatus
  notes: string | null
  dateUnverified: boolean
  evidenceUrl: string | null
  score: Score | null
  /** Discover runs: the role the ad was scored against, and every role that found it. */
  roleType?: string | null
  roleTypes?: string[]
}

export type RoleCategory = 'RELATED' | 'HYBRID' | 'OUTSIDE_FIELD' | 'CUSTOM'

export interface RoleView {
  id: number
  title: string
  category: RoleCategory
  matchScore: number | null
  reasoning: string | null
  gaps: string | null
  selected: boolean
  userAdded: boolean
  searched: boolean
  jobCount: number
}

export interface RoleEdit {
  id?: number
  title: string
  selected: boolean
}

export interface JobDetail {
  row: JobRow
  description: string
}

const ACTIVE: RunStatus[] = ['QUEUED', 'RUNNING']

export const useRuns = () => useQuery({ queryKey: ['runs'], queryFn: () => api<Run[]>('/api/runs') })

/** Polls every 2 s while the run is working (SPEC 3). */
export const useRun = (id: number, enabled = true, pollWhileWaiting = false) =>
  useQuery({
    queryKey: ['runs', id],
    queryFn: () => api<RunView>(`/api/runs/${id}`),
    enabled,
    refetchInterval: (q) => {
      const status = q.state.data?.run.status
      // A search run waits for a click; an aktivitet run waits for a sign-in that is detected without a click.
      return status && (ACTIVE.includes(status) || (pollWhileWaiting && status === 'WAITING_FOR_USER')) ? 2000 : false
    },
  })

/** Ads appear while the run works, so the list is refetched every 2 s until the run has ended. */
export const useRunJobs = (id: number, enabled = true, active = false) =>
  useQuery({
    queryKey: ['runs', id, 'jobs'],
    queryFn: () => api<JobRow[]>(`/api/runs/${id}/jobs`),
    enabled,
    refetchInterval: active ? 2000 : false,
  })

export const useJob = (jobId: number) =>
  useQuery({ queryKey: ['jobs', jobId], queryFn: () => api<JobDetail>(`/api/jobs/${jobId}`) })

export const useJobDetail = (jobId: number | null, runId: number) =>
  useQuery({
    queryKey: ['jobs', jobId, runId],
    queryFn: () => api<JobDetail>(`/api/jobs/${jobId}?run=${runId}`),
    enabled: jobId !== null,
  })

export function useStartRun() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: { prompt: string; maxAgeDays?: number; limit?: number; includeWeb?: boolean; autoContinue?: boolean; mode?: RunMode; aggressive?: boolean }) =>
      api<{ id: number }>('/api/runs', { method: 'POST', body }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['runs'] }),
  })
}

function useRunAction<V>(fn: (v: V) => Promise<unknown>, runId: number) {
  const client = useQueryClient()
  return useMutation({ mutationFn: fn, onSuccess: () => client.invalidateQueries({ queryKey: ['runs', runId] }) })
}

/** The role types suggested for a Discover run; kept with the run so they can be revisited. */
export const useRoles = (runId: number, enabled: boolean) =>
  useQuery({ queryKey: ['runs', runId, 'roles'], queryFn: () => api<RoleView[]>(`/api/runs/${runId}/roles`), enabled })

export const useSaveRoles = (runId: number) => {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (roles: RoleEdit[]) => api<RoleView[]>(`/api/runs/${runId}/roles`, { method: 'PUT', body: roles }),
    onSuccess: (roles) => client.setQueryData(['runs', runId, 'roles'], roles),
  })
}

/** Adds one job by its address (and optionally its pasted text). Resolves to the MANUAL run to follow. */
export function useImportJob() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: { url: string; text?: string }) => api<{ id: number }>('/api/import', { method: 'POST', body }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['runs'] }),
  })
}

/** The ad text for a MANUAL run that could not read the page by itself. */
export const useProvideAdText = (runId: number) => useRunAction((text: string) => api(`/api/import/${runId}/text`, { method: 'POST', body: { text } }), runId)

export const useEditProfile = (runId: number) =>
  useRunAction((p: SearchProfile) => api(`/api/runs/${runId}/profile`, { method: 'PUT', body: p }), runId)
export const useContinueRun = (runId: number) => useRunAction(() => api(`/api/runs/${runId}/continue`, { method: 'POST' }), runId)
export const useCancelRun = (runId: number) => useRunAction(() => api(`/api/runs/${runId}/cancel`, { method: 'POST' }), runId)

export function usePatchJob(runId: number) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...patch }: { id: number; status?: JobStatus; notes?: string }) =>
      api<void>(`/api/jobs/${id}`, { method: 'PATCH', body: patch }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['runs', runId, 'jobs'] })
      void client.invalidateQueries({ queryKey: ['jobs'] })
    },
  })
}
