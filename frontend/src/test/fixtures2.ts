import type { Application } from '../api/applications'
import type { TabView } from '../api/apply'
import type { OnboardingState } from '../api/onboarding'

/** An install that has been through the welcome steps. */
export const onboarded: OnboardingState = {
  cv: { present: true, entries: 4, facts: 12, skills: 5 },
  claude: { chosen: true, provider: 'cli', works: true },
  country: { chosen: true, code: 'SE' },
  mode: { chosen: true, mode: 'PROMPT' },
  complete: true,
  nextStep: null,
}

export const countryView = {
  selected: 'SE',
  chosen: true,
  activityReport: true,
  countries: [
    { code: 'SE', name: 'Sweden', jobBoard: 'Platsbanken', market: 'the Swedish job market', activityReport: true, available: true },
    { code: 'NO', name: 'Norway', jobBoard: 'Finn.no', market: 'the Norwegian job market', activityReport: false, available: false },
  ],
}

export const application = (over: Partial<Application> = {}): Application => ({
  jobId: 1, runId: 7, title: 'Software Developer', employer: 'Ravion AB', municipality: 'Stockholm', url: null, applyUrl: null,
  searchMode: 'TARGETED', roleType: null, score: 80, jobStatus: 'DOCS_READY', selectedAt: '2026-10-27T10:00:00Z', submittedAt: null,
  cv: { id: 1, version: 1, approved: false }, letter: { id: 2, version: 1, approved: false },
  generation: { runId: 9, status: 'DONE', step: 'DONE', progress: 100, message: 'Documents ready for review' }, submittedDocs: null,
  readyToApply: false, blockers: ['The CV is not approved', 'The cover letter is not approved'], sentToApply: false, ...over,
})

export const tab = (over: Partial<TabView> = {}): TabView => ({
  jobId: 1, title: 'Software Developer', employer: 'Ravion AB', url: 'https://jobs.example.com/apply/1', status: 'FILLING', message: 'Working out what to fill',
  done: [], skipped: [], questions: [], passes: 1, phase: 'FILLING', signInHost: null, loginFilled: false,
  stage: 'FINDING_FORM', stageLabel: 'Finding application form', failedStage: null, failure: null, reasons: [], browserVisible: false, ...over,
})
