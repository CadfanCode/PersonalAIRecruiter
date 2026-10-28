import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'

export type DocKind = 'CV' | 'LETTER'

export interface Bullet {
  text: string
  factIds: number[]
}

export interface CvItem {
  entryId: number
  title: string
  meta: string | null
  location?: string | null
  dateLabel: string | null
  bullets: Bullet[]
}

export interface CvSection {
  heading: string
  kind: 'SKILLS' | 'EXPERIENCE' | 'PROJECTS' | 'EDUCATION' | 'ACHIEVEMENTS' | 'LANGUAGES'
  items: CvItem[]
}

export interface CvContent {
  language: 'sv' | 'en'
  headline: string
  profileParagraphs: string[]
  sections: CvSection[]
  languagesLine?: string | null
  gapNote?: string | null
}

export interface LetterContent {
  language: 'sv' | 'en'
  subject: string
  subline: string
  greeting: string
  paragraphs: string[]
  closing: string
  signature: string
  factIdsUsed: number[]
}

export interface Violation {
  code: string
  severity: 'FAIL' | 'WARN'
  where: string
  text: string
  message: string
}

export interface Report {
  violations: Violation[]
  pages: number
  maxPages: number
}

export interface FactView {
  id: number
  entryId: number
  entryTitle: string
  textEn: string | null
  textSv: string | null
}

export interface DocView<C = CvContent | LetterContent> {
  id: number
  jobId: number
  kind: DocKind
  language: 'sv' | 'en'
  version: number
  approved: boolean
  content: C
  report: Report | null
  facts: Record<string, FactView>
  updatedAt: string
}

export interface JobDocuments {
  cv: DocView<CvContent> | null
  letter: DocView<LetterContent> | null
  selection: { entries: { entryId: number; factIds: number[]; use: 'cv' | 'letter' | 'both'; reason: string }[] } | null
  pinned: number[]
  excluded: number[]
}

export interface DocOverview {
  jobId: number
  title: string
  employer: string | null
  language: string
  cv: boolean
  letter: boolean
  cvApproved: boolean
  letterApproved: boolean
  updatedAt: string
}

export const useDocOverview = () => useQuery({ queryKey: ['documents'], queryFn: () => api<DocOverview[]>('/api/documents') })

export const useJobDocuments = (jobId: number) =>
  useQuery({ queryKey: ['job-documents', jobId], queryFn: () => api<JobDocuments>(`/api/jobs/${jobId}/documents`) })

/** Starts generation for a job (optionally for the search run it came from) and resolves to the DOCUMENTS run id. */
export const useStartDocuments = () =>
  useMutation({
    mutationFn: ({ jobId, runId }: { jobId: number; runId?: number }) =>
      api<{ runId: number }>(`/api/jobs/${jobId}/documents`, { method: 'POST', body: runId ? { runId } : {} }),
  })

export function useSaveDocument(jobId: number) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, content }: { id: number; content: CvContent | LetterContent }) =>
      api<DocView>(`/api/documents/${id}`, { method: 'PUT', body: content }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['job-documents', jobId] }),
  })
}

export function useApproveDocument(jobId: number) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api<Report>(`/api/documents/${id}/approve`, { method: 'POST' }),
    onSettled: () => {
      void client.invalidateQueries({ queryKey: ['job-documents', jobId] })
      void client.invalidateQueries({ queryKey: ['runs'] })
    },
  })
}

export interface UnapproveResult {
  document: DocView
  /** What the unapproval means for an application that is already open, or null when there is none. */
  notice: string | null
}

export function useUnapproveDocument(jobId: number) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api<UnapproveResult>(`/api/documents/${id}/unapprove`, { method: 'POST' }),
    onSettled: () => {
      void client.invalidateQueries({ queryKey: ['job-documents', jobId] })
      void client.invalidateQueries({ queryKey: ['runs'] })
      void client.invalidateQueries({ queryKey: ['applications'] })
      void client.invalidateQueries({ queryKey: ['apply'] })
    },
  })
}

export function useSaveSelection(jobId: number) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: { pinned: number[]; excluded: number[] }) =>
      api<void>(`/api/jobs/${jobId}/selection`, { method: 'PATCH', body }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['job-documents', jobId] }),
  })
}

export interface ChatTurn {
  role: 'user' | 'assistant'
  text: string
  /** The document before an assistant change, so the page can offer to undo it. */
  previous?: CvContent | LetterContent
  approvalRevoked?: boolean
  error?: boolean
}

interface ChatResponse {
  reply: string
  changed: boolean
  approvalRevoked: boolean
  document: DocView
}

/** "Ask Claude": revises the document as asked. The content sent is what the user sees now, including unsaved edits. */
export function useDocumentChat(jobId: number) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, message, content, history }: { id: number; message: string; content: CvContent | LetterContent; history: ChatTurn[] }) =>
      api<ChatResponse>(`/api/documents/${id}/chat`, {
        method: 'POST',
        body: { message, content, history: history.filter((t) => !t.error).map(({ role, text }) => ({ role, text })) },
      }),
    onSuccess: (r) => {
      if (!r.changed) return
      void client.invalidateQueries({ queryKey: ['job-documents', jobId] })
      void client.invalidateQueries({ queryKey: ['documents'] })
      void client.invalidateQueries({ queryKey: ['applications'] })
    },
  })
}
