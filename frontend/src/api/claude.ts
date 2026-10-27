import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'

export interface ClaudeHealth {
  ok: boolean
  version: string | null
  durationMs: number
  error: string | null
}

export interface ClaudeUsage {
  totals: {
    calls: number
    failures: number
    costUsd: number
    inputTokens: number
    outputTokens: number
    cacheReadTokens: number
    cacheCreationTokens: number
    durationMs: number
  }
  byTask: { task: string; calls: number; tokens: number }[]
}

export interface LimitWindow {
  utilization: number
  resetsAt: string | null
  status: string | null
}

/** Session (5 h) and weekly usage of the Claude subscription, as the CLI reports it. */
export interface ClaudeLimits {
  limits: { session: LimitWindow | null; week: LimitWindow | null; checkedAt: string } | null
  error: string | null
}

/** Runs `claude --version` and one tiny model call, so it only fires when the user asks. */
export function useClaudeHealth() {
  return useQuery({
    queryKey: ['claude', 'health'],
    queryFn: () => api<ClaudeHealth>('/api/claude/health'),
    enabled: false,
    retry: false,
    staleTime: Infinity,
  })
}

export function useClaudeUsage() {
  return useQuery({
    queryKey: ['claude', 'usage'],
    queryFn: () => api<ClaudeUsage>('/api/claude/usage'),
    retry: false,
  })
}

/** One tiny CLI call on the server, cached there for two minutes; `refresh` forces a new reading. */
export function useClaudeLimits() {
  const client = useQueryClient()
  const query = useQuery({
    queryKey: ['claude', 'limits'],
    queryFn: () => api<ClaudeLimits>('/api/claude/limits'),
    retry: false,
    staleTime: 120_000,
  })
  const refresh = useMutation({
    mutationFn: () => api<ClaudeLimits>('/api/claude/limits?refresh=true'),
    onSuccess: (fresh) => client.setQueryData(['claude', 'limits'], fresh),
  })
  return { query, refresh }
}
