import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'

export type ProviderId = 'cli' | 'api'

export interface ConnectionStatus {
  selected: ProviderId
  chosen: boolean
  cli: { found: boolean; path: string | null; version: string | null; help: string | null }
  api: { configured: boolean; keyHint: string | null; model: string }
  secureStore: { available: boolean; name: string }
}

export interface TestResult {
  ok: boolean
  provider: ProviderId
  /** null when ok, else NOT_INSTALLED, NOT_LOGGED_IN, INVALID_KEY, NO_KEY or OTHER */
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
export const useSaveApiKey = () =>
  useConnectionMutation(({ key, model }: { key: string; model?: string }) =>
    api<ConnectionStatus>('/api/claude/connection/api-key', { method: 'PUT', body: { key, model } }),
  )
export const useDeleteApiKey = () => useConnectionMutation(() => api<ConnectionStatus>('/api/claude/connection/api-key', { method: 'DELETE' }))
export const useSetApiModel = () =>
  useConnectionMutation((model: string) => api<ConnectionStatus>('/api/claude/connection/api-model', { method: 'PUT', body: { model } }))
export const useTestConnection = () =>
  useConnectionMutation((provider: ProviderId) => api<TestResult>('/api/claude/connection/test', { method: 'POST', body: { provider } }))
