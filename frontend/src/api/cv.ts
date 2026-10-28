import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'

export const KINDS = [
  'WORK',
  'INTERNSHIP',
  'OWN_BUSINESS',
  'PROJECT',
  'EDUCATION',
  'CERTIFICATION',
  'LICENCE',
  'COURSE',
  'AWARD',
  'CONFERENCE',
  'PUBLICATION',
  'VOLUNTEER',
  'LANGUAGE',
  'OTHER',
] as const
export type Kind = (typeof KINDS)[number]

export interface Fact {
  id: number
  entryId: number
  textEn: string | null
  textSv: string | null
  evidence: string | null
  metrics: string | null
  sortOrder: number
  tags: string[]
}

export interface FactInput {
  textEn: string | null
  textSv: string | null
  evidence: string | null
  metrics: string | null
  tags: string[]
}

export interface Entry {
  id: number
  kind: Kind
  title: string
  titleEn: string | null
  organisation: string | null
  location: string | null
  startYm: string | null
  endYm: string | null
  ongoing: boolean
  summary: string | null
  note: string | null
  sortOrder: number
  hidden: boolean
  tags: string[]
  facts: Fact[]
}

export type EntryInput = Omit<Entry, 'id' | 'facts' | 'sortOrder'> & { sortOrder?: number }

export interface Language {
  language: string
  level: string
}

export interface Profile {
  id: number | null
  fullName: string
  headline: string | null
  email: string | null
  phone: string | null
  phoneInternational: string | null
  city: string | null
  website: string | null
  github: string | null
  linkedin: string | null
  workAuthorisation: string | null
  languages: Language[]
}

export interface Skill {
  id: number
  name: string
  category: string | null
}

export interface ImportedFact {
  textEn: string | null
  textSv: string | null
  evidence?: string | null
  tags: string[]
}

export interface ImportedEntry {
  kind: Kind
  title: string
  titleEn: string | null
  organisation: string | null
  location: string | null
  startYm: string | null
  endYm: string | null
  ongoing: boolean
  tags: string[]
  facts: ImportedFact[]
}

export interface ImportPreview {
  previewId: string
  files: string[]
  entries: { entry: ImportedEntry; duplicateOfEntryId: number | null }[]
  skills: { name: string; category: string | null }[]
}

const KEY = ['cv'] as const

export const useEntries = () => useQuery({ queryKey: [...KEY, 'entries'], queryFn: () => api<Entry[]>('/api/cv/entries') })
export const useProfile = () => useQuery({ queryKey: [...KEY, 'profile'], queryFn: () => api<Profile>('/api/profile') })
export const useTags = () => useQuery({ queryKey: [...KEY, 'tags'], queryFn: () => api<{ id: number; name: string }[]>('/api/cv/tags') })
export const useSkills = () => useQuery({ queryKey: [...KEY, 'skills'], queryFn: () => api<Skill[]>('/api/cv/skills') })
export const useGaps = () =>
  useQuery({ queryKey: [...KEY, 'gaps'], queryFn: () => api<{ id: number; text: string }[]>('/api/cv/gaps') })

/** A mutation that refreshes every CV query afterwards. */
function useCvMutation<V, R = unknown>(fn: (v: V) => Promise<R>) {
  const client = useQueryClient()
  return useMutation({ mutationFn: fn, onSuccess: () => client.invalidateQueries({ queryKey: KEY }) })
}

export const useCreateEntry = () =>
  useCvMutation((input: EntryInput) => api<Entry>('/api/cv/entries', { method: 'POST', body: input }))
export const useUpdateEntry = () =>
  useCvMutation(({ id, input }: { id: number; input: EntryInput }) =>
    api<Entry>(`/api/cv/entries/${id}`, { method: 'PUT', body: input }),
  )
export const useDeleteEntry = () => useCvMutation((id: number) => api<void>(`/api/cv/entries/${id}`, { method: 'DELETE' }))
export const useAddFact = () =>
  useCvMutation(({ entryId, input }: { entryId: number; input: FactInput }) =>
    api<Fact>(`/api/cv/entries/${entryId}/facts`, { method: 'POST', body: input }),
  )
export const useUpdateFact = () =>
  useCvMutation(({ id, input }: { id: number; input: FactInput }) =>
    api<Fact>(`/api/cv/facts/${id}`, { method: 'PUT', body: input }),
  )
export const useDeleteFact = () => useCvMutation((id: number) => api<void>(`/api/cv/facts/${id}`, { method: 'DELETE' }))
export const useSaveProfile = () =>
  useCvMutation((p: Profile) => api<Profile>('/api/profile', { method: 'PUT', body: p }))
export const useSaveSkill = () =>
  useCvMutation((s: { name: string; category: string | null }) => api<Skill>('/api/cv/skills', { method: 'POST', body: s }))
export const useDeleteSkill = () => useCvMutation((id: number) => api<void>(`/api/cv/skills/${id}`, { method: 'DELETE' }))
export const useSaveGaps = () =>
  useCvMutation((gaps: string[]) => api('/api/cv/gaps', { method: 'PUT', body: { gaps } }))
export const useImportSeed = () =>
  useCvMutation((json: string) => api('/api/cv/seed', { method: 'POST', body: json, headers: { 'Content-Type': 'application/json' } }))

export const useImportPreview = () =>
  useMutation({
    mutationFn: (files: File[]) => {
      const form = new FormData()
      files.forEach((f) => form.append('files', f))
      return api<ImportPreview>('/api/cv/import', { method: 'POST', body: form })
    },
  })
export const useImportCommit = () =>
  useCvMutation(({ previewId, entries, skills }: { previewId: string; entries: ImportedEntry[]; skills: ImportPreview['skills'] }) =>
    api<{ entryIds: number[]; facts: number; skills: number }>(`/api/cv/import/${previewId}/commit`, {
      method: 'POST',
      body: { entries, skills },
    }),
  )
