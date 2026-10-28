import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'

export type OnboardingStep = 'CV' | 'CLAUDE' | 'COUNTRY' | 'MODE'
export type SearchModeChoice = 'DISCOVER' | 'PROMPT'

export interface OnboardingState {
  cv: { present: boolean; entries: number; facts: number; skills: number }
  claude: { chosen: boolean; provider: 'cli' | 'gemini'; works: boolean }
  country: { chosen: boolean; code: string }
  mode: { chosen: boolean; mode: SearchModeChoice }
  complete: boolean
  nextStep: OnboardingStep | null
}

export const useOnboarding = () => useQuery({ queryKey: ['onboarding'], queryFn: () => api<OnboardingState>('/api/onboarding') })

function useOnboardingMutation<V>(fn: (v: V) => Promise<OnboardingState>) {
  const client = useQueryClient()
  return useMutation({ mutationFn: fn, onSuccess: (state) => client.setQueryData(['onboarding'], state) })
}

export const useChooseMode = () =>
  useOnboardingMutation((mode: SearchModeChoice) => api<OnboardingState>('/api/onboarding/mode', { method: 'PUT', body: { mode } }))
export const useCompleteOnboarding = () => useOnboardingMutation(() => api<OnboardingState>('/api/onboarding/complete', { method: 'POST' }))
export const useReopenOnboarding = () => useOnboardingMutation(() => api<OnboardingState>('/api/onboarding/reopen', { method: 'POST' }))
