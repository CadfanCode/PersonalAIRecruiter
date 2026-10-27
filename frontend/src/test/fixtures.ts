import type { JobRow, Run, SearchProfile } from '../api/runs'

export const profile: SearchProfile = {
  roleSummary: 'Junior Java developer in Stockholm',
  titles: ['javautvecklare'],
  synonyms: [],
  skillsWanted: ['java'],
  seniority: 'graduate',
  acceptExperienceUpToYears: 2,
  locations: [{ name: 'Stockholm', municipality: 'Stockholm', radiusKm: 0 }],
  remoteOk: false,
  languages: ['sv', 'en'],
  maxAgeDays: 7,
  employmentTypes: ['heltid'],
  exclusions: ['senior'],
  yrkesrollForAktivitetsrapport: 'Systemutvecklare/Programmerare',
  relevantTags: ['java'],
  jobtechQueries: ['java utvecklare'],
}

export const run = (over: Partial<Run> = {}): Run => ({
  id: 7, searchProfileId: 3, status: 'DONE', step: 'DONE', progress: 100, message: 'Found 28 ads',
  startedAt: '2026-10-26T10:00:00Z', finishedAt: null, prompt: 'new-grad Java Stockholm 7 days',
  options: { maxAgeDays: 7, limit: null, includeWeb: null, autoContinue: false }, kind: 'SEARCH', jobId: null, ...over,
})

let nextId = 1
export const job = (over: Partial<JobRow> & { total?: number | null } = {}): JobRow => {
  const { total = 80, ...rest } = over
  const id = nextId++
  return {
    id, rank: null, source: 'JOBTECH', url: `https://platsbanken/${id}`, applyUrl: null, title: `Job ${id}`,
    employer: 'Employer AB', clientEmployer: null, location: 'Stockholm', municipality: 'Stockholm',
    publishedAt: '2026-10-25T10:00:00Z', deadline: '2026-12-01T10:00:00Z', language: 'sv', employmentType: 'Heltid',
    asksForNewGraduates: false, experienceRequiredText: null, status: 'NEW', notes: null, dateUnverified: false,
    evidenceUrl: null,
    score: total === null ? null : { total, tech: Math.round(total * 0.5), experience: Math.round(total * 0.3),
      eligibility: Math.round(total * 0.2), dealBreakers: [], fit: `fit ${id}`, gaps: `gaps ${id}`, model: 'm' },
    ...rest,
  }
}

import type { CvContent, DocView, JobDocuments, LetterContent, Report } from '../api/documents'

export const cvContent = (): CvContent => ({
  language: 'sv',
  headline: 'Javautvecklare | Spring Boot',
  profileParagraphs: ['Systemutvecklare med fokus på backend i Java.'],
  sections: [
    { heading: 'Tekniska kunskaper', kind: 'SKILLS', items: [{ entryId: 0, title: 'Java och backend', meta: 'Java, Kotlin', dateLabel: '', bullets: [] }] },
    {
      heading: 'Erfarenhet', kind: 'EXPERIENCE',
      items: [{
        entryId: 1, title: 'Systemutvecklare (praktik)', meta: 'Braive AB', location: 'Stockholm', dateLabel: '2026',
        bullets: [{ text: 'Byggde mikrotjänster i Java och Kotlin.', factIds: [10] }, { text: 'Hittade race conditions.', factIds: [11] }],
      }],
    },
  ],
  languagesLine: 'Svenska (flytande)',
  gapNote: 'Övrig anställning 2018-2021, referenser lämnas på begäran',
})

export const letterContent = (): LetterContent => ({
  language: 'sv', subject: 'Ansökan: Junior Java-utvecklare', subline: 'Comstream AB, Stockholm', greeting: 'Hej,',
  paragraphs: ['Jag är systemutvecklare.', 'Det tydligaste exemplet är Braive.'], closing: 'Vänliga hälsningar,',
  signature: 'Cai Birch', factIdsUsed: [10],
})

const facts = {
  '10': { id: 10, entryId: 1, entryTitle: 'Software Developer (intern)', textEn: 'Built microservices.', textSv: 'Byggde mikrotjänster i Java och Kotlin.' },
  '11': { id: 11, entryId: 1, entryTitle: 'Software Developer (intern)', textEn: 'Fixed race conditions.', textSv: 'Åtgärdade race conditions.' },
}

export const cvDoc = (over: Partial<DocView<CvContent>> = {}): DocView<CvContent> => ({
  id: 100, jobId: 1, kind: 'CV', language: 'sv', version: 1, approved: false, content: cvContent(),
  report: { violations: [], pages: 2, maxPages: 2 }, facts, updatedAt: '2026-10-27T08:00:00Z', ...over,
})

export const letterDoc = (over: Partial<DocView<LetterContent>> = {}): DocView<LetterContent> => ({
  id: 101, jobId: 1, kind: 'LETTER', language: 'sv', version: 1, approved: false, content: letterContent(),
  report: { violations: [], pages: 1, maxPages: 1 }, facts, updatedAt: '2026-10-27T08:00:00Z', ...over,
})

export const jobDocs = (over: Partial<JobDocuments> = {}): JobDocuments => ({
  cv: cvDoc(), letter: letterDoc(), pinned: [], excluded: [],
  selection: { entries: [{ entryId: 1, factIds: [10, 11], use: 'both', reason: 'Strong Java match' }] }, ...over,
})

export const report = (...violations: Report['violations']): Report => ({ violations, pages: 2, maxPages: 2 })
