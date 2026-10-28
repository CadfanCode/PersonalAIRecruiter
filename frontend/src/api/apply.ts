import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'

export type TabStatus =
  | 'QUEUED'
  | 'OPENING'
  | 'FILLING'
  | 'WAITING_FOR_SIGN_IN'
  | 'NEEDS_MANUAL_APPLY'
  | 'NEEDS_ANSWERS'
  | 'READY_FOR_USER'
  | 'SESSION_EXPIRED'
  | 'EMAIL_ONLY'
  | 'NO_APPLY_LINK'
  | 'APPLY_MANUALLY'
  | 'CLOSED'
  | 'ERROR'

export interface TabView {
  jobId: number
  title: string
  employer: string | null
  url: string | null
  status: TabStatus
  message: string
  done: { fieldId: string; label: string; action: string; detail: string | null }[]
  skipped: { fieldId: string; label: string; reason: string }[]
  questions: { fieldId: string; question: string }[]
  passes: number
  /** The coarse state shown to the user: FILLING, NEEDS_LOGIN, NEEDS_ATTENTION, READY_FOR_SUBMISSION, FAILED, QUEUED, CLOSED. */
  phase: 'QUEUED' | 'FILLING' | 'NEEDS_LOGIN' | 'NEEDS_ATTENTION' | 'READY_FOR_SUBMISSION' | 'FAILED' | 'CLOSED'
  /** The portal a tab is waiting at for a sign-in, so a login can be saved for it. */
  signInHost: string | null
  loginFilled: boolean
}

const WORKING: TabStatus[] = ['QUEUED', 'OPENING', 'FILLING']

/** Polls while a tab is working or waiting for the user to sign in, so the card updates by itself. */
export const useApplyStatus = () =>
  useQuery({
    queryKey: ['apply', 'status'],
    queryFn: () => api<TabView[]>('/api/apply/status'),
    refetchInterval: (q) =>
      q.state.data?.some((t) => WORKING.includes(t.status) || t.status === 'WAITING_FOR_SIGN_IN') ? 2000 : false,
  })

function useApplyMutation<V, R = unknown>(fn: (v: V) => Promise<R>) {
  const client = useQueryClient()
  return useMutation({ mutationFn: fn, onSettled: () => client.invalidateQueries({ queryKey: ['apply', 'status'] }) })
}

export const useOpenApplications = () =>
  useApplyMutation((jobIds: number[]) => api<TabView[]>('/api/apply', { method: 'POST', body: { jobIds } }))
/** "Run all": every job is accepted; the ones beyond the tab limit wait as QUEUED and start by themselves. */
export const useOpenBatch = () =>
  useApplyMutation((jobIds: number[]) => api<TabView[]>('/api/apply/batch', { method: 'POST', body: { jobIds } }))
export const useFillPage = () =>
  useApplyMutation(({ jobId, restart }: { jobId: number; restart?: boolean }) =>
    api<TabView>(`/api/apply/${jobId}/fill${restart ? '?restart=true' : ''}`, { method: 'POST' }),
  )
export const useSaveAnswers = () =>
  useApplyMutation(({ jobId, answers }: { jobId: number; answers: { fieldId: string; answer: string }[] }) =>
    api<TabView>(`/api/apply/${jobId}/answers`, { method: 'POST', body: { answers } }),
  )
export const useFocusTab = () => useApplyMutation((jobId: number) => api<void>(`/api/apply/${jobId}/focus`, { method: 'POST' }))
export const useCloseTab = () => useApplyMutation((jobId: number) => api<void>(`/api/apply/${jobId}/close`, { method: 'POST' }))
