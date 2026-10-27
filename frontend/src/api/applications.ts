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

export const useRemoveApplication = () =>
  useApplicationMutation((jobId: number) => api<void>(`/api/applications/${jobId}`, { method: 'DELETE' }))

export const useMarkSubmitted = () =>
  useApplicationMutation((jobId: number) => api<Application>(`/api/applications/${jobId}/submitted`, { method: 'POST' }))

export const useUndoSubmitted = () =>
  useApplicationMutation((jobId: number) => api<Application>(`/api/applications/${jobId}/submitted`, { method: 'DELETE' }))
