import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'

export interface Country {
  code: string
  name: string
  jobBoard: string
  market: string
  activityReport: boolean
  available: boolean
}

export interface CountryView {
  selected: string
  chosen: boolean
  /** Whether the chosen country has an activity report JobPilot can fill in (Sweden). */
  activityReport: boolean
  countries: Country[]
}

export const useCountry = () => useQuery({ queryKey: ['country'], queryFn: () => api<CountryView>('/api/country') })

export function useChooseCountry() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (code: string) => api<CountryView>('/api/country', { method: 'PUT', body: { code } }),
    onSuccess: (view) => {
      client.setQueryData(['country'], view)
      void client.invalidateQueries({ queryKey: ['onboarding'] })
    },
  })
}
