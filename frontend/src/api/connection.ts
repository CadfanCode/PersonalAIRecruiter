import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'

export type ProviderId = 'cli'

/** The model one stage of the app uses; blank means it follows the default. */
export interface StageModel {
  stage: 'SEARCH' | 'RANKING' | 'CV_READING' | 'DOCUMENTS' | 'AUTOFILL'
  label: string
  model: string
}

export interface ConnectionStatus {
  selected: ProviderId
  chosen: boolean
  cli: { found: boolean; path: string | null; version: string | null; help: string | null; model: string; stageModels: StageModel[] }
  /** Where saved employer-portal logins are kept. */
  secureStore: { available: boolean; name: string }
}

export interface TestResult {
  ok: boolean
  provider: ProviderId
  /** null when ok, else NOT_INSTALLED, NOT_LOGGED_IN or OTHER */
  problem: string | null
  message: string
  help: string | null
  durationMs: number
}

export const useConnection = () => useQuery({ queryKey: ['connection'], queryFn: () => api<ConnectionStatus>('/api/claude/connection') })

function useConnectionMutation<V, R = ConnectionStatus>(fn: (v: V) => Promise<R>) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: fn,
    onSuccess: (result) => {
      if (result && typeof result === 'object' && 'secureStore' in result) client.setQueryData(['connection'], result)
      void client.invalidateQueries({ queryKey: ['onboarding'] })
    },
  })
}

export const useSelectProvider = () =>
  useConnectionMutation((id: ProviderId) => api<ConnectionStatus>('/api/claude/connection/provider', { method: 'PUT', body: { id } }))
/** Takes effect on the next request; an empty string means Claude Code's own default. */
export const useSetCliModel = () =>
  useConnectionMutation((model: string) => api<ConnectionStatus>('/api/claude/connection/cli-model', { method: 'PUT', body: { model } }))

export const useSetStageModel = () =>
  useConnectionMutation(({ stage, model }: { stage: StageModel['stage']; model: string }) =>
    api<ConnectionStatus>(`/api/claude/connection/stage-model/${stage}`, { method: 'PUT', body: { model } }))

export interface ModelOption {
  id: string
  label: string
}
/** Claude Code model aliases: they follow the newest model of each family, so they do not go stale. */
export const CLI_MODELS: ModelOption[] = [
  { id: '', label: 'Default (chosen by Claude Code)' },
  { id: 'sonnet', label: 'Sonnet (latest)' },
  { id: 'opus', label: 'Opus (latest)' },
  { id: 'haiku', label: 'Haiku (latest)' },
  { id: 'fable', label: 'Fable (latest)' },
]
export const useTestConnection = () => useConnectionMutation(() => api<TestResult>('/api/claude/connection/test', { method: 'POST' }))
