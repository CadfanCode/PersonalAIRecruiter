import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'

export interface AktivitetEntry {
  jobId: number
  title: string
  yrkesroll: string
  employerLabel: string
  municipality: string
  omfattning: 'Heltid' | 'Deltid' | 'Timmar vid behov'
  varFinns: string
  answeredAd: boolean
  appliedDate: string
}

export interface AktivitetPlan {
  jobId: number
  title: string
  entry: AktivitetEntry | null
  ineligible: string | null
}

export interface AktivitetLogRow {
  id: number
  jobId: number
  runId: number | null
  title: string
  employerLabel: string
  municipality: string
  appliedDate: string | null
  savedAt: string | null
  result: 'SAVED' | 'DUPLICATE' | 'SKIPPED' | 'UNCONFIRMED' | 'FAILED'
  message: string | null
}

export interface Reminder {
  active: boolean
  day: number
  daysLeft: number
  forMonth: string
  message: string
}

export const useAktivitetPlan = (jobIds: number[]) =>
  useQuery({
    queryKey: ['aktivitet', 'plan', jobIds],
    queryFn: () => api<AktivitetPlan[]>(`/api/aktivitet/plan?jobIds=${jobIds.join(',')}`),
    enabled: jobIds.length > 0,
    staleTime: Infinity,
  })

export const useAktivitetLog = () =>
  useQuery({ queryKey: ['aktivitet', 'log'], queryFn: () => api<AktivitetLogRow[]>('/api/aktivitet/log') })

export const useReminder = () => useQuery({ queryKey: ['aktivitet', 'reminder'], queryFn: () => api<Reminder>('/api/aktivitet/reminder') })

export function useStartLogging() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (entries: AktivitetEntry[]) => api<{ runId: number }>('/api/aktivitet/log', { method: 'POST', body: { entries } }),
    onSettled: () => client.invalidateQueries({ queryKey: ['aktivitet'] }),
  })
}
