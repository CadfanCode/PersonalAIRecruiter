import { useQuery } from '@tanstack/react-query'
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
  byTask: { task: string; calls: number; costUsd: number }[]
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
