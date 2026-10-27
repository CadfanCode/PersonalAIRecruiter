import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'

export interface StyleRule {
  id: number
  ruleKey: string
  ruleText: string
  enabled: boolean
}

export interface BannedPhrase {
  id: number
  language: 'en' | 'sv'
  phrase: string
  enabled: boolean
}

export interface SelectionPreview {
  language: 'sv' | 'en'
  entries: { entryId: number; title: string; kind: string; use: 'cv' | 'letter' | 'both'; reason: string; facts: { id: number; text: string }[] }[]
}

export const useStyleRules = () => useQuery({ queryKey: ['settings', 'rules'], queryFn: () => api<StyleRule[]>('/api/settings/style-rules') })
export const useBannedPhrases = () =>
  useQuery({ queryKey: ['settings', 'phrases'], queryFn: () => api<BannedPhrase[]>('/api/settings/banned-phrases') })

export function useSaveRule() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ruleText, enabled }: { id: number; ruleText: string; enabled: boolean }) =>
      api<StyleRule[]>(`/api/settings/style-rules/${id}`, { method: 'PUT', body: { ruleText, enabled } }),
    onSuccess: (rules) => client.setQueryData(['settings', 'rules'], rules),
  })
}

export function usePhraseActions() {
  const client = useQueryClient()
  const refresh = () => client.invalidateQueries({ queryKey: ['settings', 'phrases'] })
  return {
    add: useMutation({
      mutationFn: (body: { language: 'en' | 'sv'; phrase: string }) =>
        api<BannedPhrase>('/api/settings/banned-phrases', { method: 'POST', body }),
      onSuccess: refresh,
    }),
    toggle: useMutation({
      mutationFn: ({ id, enabled }: { id: number; enabled: boolean }) =>
        api<void>(`/api/settings/banned-phrases/${id}`, { method: 'PUT', body: { enabled } }),
      onSuccess: refresh,
    }),
    remove: useMutation({
      mutationFn: (id: number) => api<void>(`/api/settings/banned-phrases/${id}`, { method: 'DELETE' }),
      onSuccess: refresh,
    }),
  }
}

export const usePreviewSelection = () =>
  useMutation({ mutationFn: (ad: string) => api<SelectionPreview>('/api/cv/preview-selection', { method: 'POST', body: { ad } }) })
