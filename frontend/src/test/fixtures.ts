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
  options: { maxAgeDays: 7, limit: null, includeWeb: null, autoContinue: false }, ...over,
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
