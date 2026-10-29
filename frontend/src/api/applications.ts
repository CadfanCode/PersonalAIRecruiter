import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'

export interface DocState {
  id: number
  version: number
  approved: boolean
}

export interface Generation {
  runId: number
  status: 'QUEUED' | 'RUNNING' | 'WAITING_FOR_USER' | 'DONE' | 'FAILED' | 'CANCELLED'
  step: string | null
  progress: number
  message: string | null
}

export type SearchMode = 'TARGETED' | 'DISCOVER' | 'MANUAL'

export interface Application {
  jobId: number
  runId: number | null
  title: string
  employer: string | null
  municipality: string | null
  url: string | null
  applyUrl: string | null
  searchMode: SearchMode
  roleType: string | null
  score: number | null
  jobStatus: string
  selectedAt: string
  submittedAt: string | null
  cv: DocState | null
  letter: DocState | null
  generation: Generation | null
  submittedDocs: { cv?: { file: string | null; version: number }; letter?: { file: string | null; version: number } } | null
  /** Both documents exist and are approved, and it is not submitted: it goes on to the Apply stage. */
  readyToApply: boolean
  /** Why it does not go on yet (empty when it does). */
  blockers: string[]
  /** The user ticked it in Documents and sent it on: only these appear in the Apply stage. */
  sentToApply: boolean
}

export interface ApprovalResult {
  jobId: number
  title: string
  kind: 'cv' | 'letter'
  approved: boolean
  alreadyApproved: boolean
  reason: string | null
}

export interface ApplyResult {
  started: { jobId: number }[]
  skipped: { jobId: number; title: string; reason: string }[]
}

export const MODE_LABEL: Record<SearchMode, string> = { TARGETED: 'Targeted search', DISCOVER: 'Discover roles', MANUAL: 'Pasted link' }

export const isGenerating = (a: Application) => a.generation?.status === 'QUEUED' || a.generation?.status === 'RUNNING'

/** Polls while any selected job is still generating documents, so progress shows without a click. */
export const useApplications = () =>
  useQuery({
    queryKey: ['applications'],
    queryFn: () => api<Application[]>('/api/applications'),
    refetchInterval: (q) => (q.state.data?.some(isGenerating) ? 2000 : false),
  })

function useApplicationMutation<V, R = unknown>(fn: (v: V) => Promise<R>) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: fn,
    onSettled: () => {
      void client.invalidateQueries({ queryKey: ['applications'] })
      void client.invalidateQueries({ queryKey: ['documents'] })
      void client.invalidateQueries({ queryKey: ['runs'] })
    },
  })
}

/** Selects the jobs and starts one batch that writes a CV and a letter for each. */
export const useCreateDocuments = () =>
  useApplicationMutation(({ jobIds, runId }: { jobIds: number[]; runId?: number }) =>
    api<{ items: { jobId: number; runId: number }[] }>('/api/applications/documents', { method: 'POST', body: { jobIds, runId } }),
  )

/** "Approve all": what was approved, and what was refused with its reason. */
export const useApproveAll = () => useApplicationMutation(() => api<ApprovalResult[]>('/api/applications/approve-all', { method: 'POST', body: {} }))

/**
 * "Apply": tabs open for the named applications whose two documents are approved, and those are sent on to the Apply stage;
 * the rest come back with a reason. Without names it opens the ones already sent on that have no tab yet.
 */
export const useApplyApproved = () =>
  useApplicationMutation((jobIds?: number[]) => api<ApplyResult>('/api/applications/apply', { method: 'POST', body: { jobIds } }))

/** Deletes applications completely: selection, documents, files and the ad. Refused for submitted or still-being-written ones. */
export const useDeleteApplications = () =>
  useApplicationMutation((jobIds: number[]) => api<{ deleted: number }>('/api/applications/delete', { method: 'POST', body: { jobIds } }))

/** Takes one application back out of the Apply stage. */
export const useWithdrawFromApply = () =>
  useApplicationMutation((jobId: number) => api<void>(`/api/applications/${jobId}/apply`, { method: 'DELETE' }))

export const useRemoveApplication = () =>
  useApplicationMutation((jobId: number) => api<void>(`/api/applications/${jobId}`, { method: 'DELETE' }))

export const useMarkSubmitted = () =>
  useApplicationMutation((jobId: number) => api<Application>(`/api/applications/${jobId}/submitted`, { method: 'POST' }))

export const useUndoSubmitted = () =>
  useApplicationMutation((jobId: number) => api<Application>(`/api/applications/${jobId}/submitted`, { method: 'DELETE' }))
