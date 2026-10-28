import { useQuery } from '@tanstack/react-query'
import { api } from './client'

export interface SubmittedSummary {
  id: number
  jobId: number | null
  title: string
  employer: string | null
  municipality: string | null
  url: string | null
  applyUrl: string | null
  language: string | null
  searchMode: string | null
  roleType: string | null
  scoreTotal: number | null
  submittedAt: string
  cvVersion: number | null
  cvFile: string | null
  letterVersion: number | null
  letterFile: string | null
  /** Set when the application was reported to Arbetsförmedlingen; null while it is not. */
  reportedAt: string | null
}

export interface SubmittedDetail {
  summary: SubmittedSummary
  adText: string | null
  cvContentJson: string | null
  letterContentJson: string | null
}

export const useSubmitted = () => useQuery({ queryKey: ['submitted'], queryFn: () => api<SubmittedSummary[]>('/api/submitted') })

export const useSubmittedDetail = (id: number) =>
  useQuery({ queryKey: ['submitted', id], queryFn: () => api<SubmittedDetail>(`/api/submitted/${id}`) })
