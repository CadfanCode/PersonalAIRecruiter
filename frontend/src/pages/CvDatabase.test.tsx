import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Entry, Profile } from '../api/cv'
import { mockApi } from '../test/mockApi'
import { CvDatabase } from './CvDatabase'

const profile: Profile = {
  id: 1, fullName: 'Cai Birch', headline: null, email: null, phone: null, phoneInternational: null, city: 'Stockholm',
  website: null, github: null, linkedin: null, workAuthorisation: null, languages: [],
}

const braive: Entry = {
  id: 1, kind: 'INTERNSHIP', title: 'Systemutvecklare (praktik)', titleEn: 'Software Developer (intern)',
  organisation: 'Braive AB', location: 'Stockholm', startYm: '2026-01', endYm: '2026-05', ongoing: false, summary: null,
  note: null, sortOrder: 0, hidden: false, tags: ['java'],
  facts: [{ id: 10, entryId: 1, textEn: 'Built microservices.', textSv: 'Byggde mikrotjänster.', evidence: null, metrics: null, sortOrder: 0, tags: ['java'] }],
}
const gih: Entry = {
  id: 2, kind: 'EDUCATION', title: 'Masterexamen i idrottsvetenskap', titleEn: null, organisation: 'GIH', location: null,
  startYm: null, endYm: null, ongoing: false, summary: null, note: 'Dates missing; add them.', sortOrder: 1, hidden: false,
  tags: ['sport-science'], facts: [],
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <CvDatabase />
    </QueryClientProvider>,
  )
}

function baseRoutes(entries: Entry[] = [braive, gih], p: Profile = profile) {
  return {
    'GET /api/cv/entries': entries,
    'GET /api/profile': p,
    'GET /api/cv/tags': [{ id: 1, name: 'java' }, { id: 2, name: 'sport-science' }],
    'GET /api/cv/skills': [{ id: 1, name: 'Java', category: 'language' }],
    'GET /api/cv/gaps': [{ id: 1, text: 'No professional Angular experience' }],
  }
}

afterEach(() => vi.unstubAllGlobals())

describe('CV database page', () => {
  it('groups entries by kind and flags the ones that need attention', async () => {
    mockApi(baseRoutes())
    renderPage()

    const internships = await screen.findByRole('region', { name: 'Internships' })
    expect(within(internships).getByText('Systemutvecklare (praktik)')).toBeInTheDocument()
    expect(within(internships).getByText('2026-01 – 2026-05')).toBeInTheDocument()
    const education = screen.getByRole('region', { name: 'Education' })
    expect(within(education).getByText('Dates missing; add them.')).toBeInTheDocument()
    expect(within(education).getByText('Dates missing')).toBeInTheDocument()
    expect(screen.getByText('1 entry needs your attention')).toBeInTheDocument()
  })

  it('saves an edited entry without sending its facts', async () => {
    const calls = mockApi({ ...baseRoutes(), 'PUT /api/cv/entries/1': { ...braive, title: 'Mjukvaruutvecklare' } })
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: /Systemutvecklare \(praktik\)/ }))
    const title = screen.getByLabelText('Title (Svenska)')
    await userEvent.clear(title)
    await userEvent.type(title, 'Mjukvaruutvecklare')
    await userEvent.click(screen.getByRole('button', { name: 'Save entry' }))

    const put = calls.find((c) => c.method === 'PUT')!
    expect(put.url).toBe('/api/cv/entries/1')
    expect(put.body).toMatchObject({ title: 'Mjukvaruutvecklare', organisation: 'Braive AB', tags: ['java'] })
    expect(put.body).not.toHaveProperty('facts')
  })

  it('edits a fact in both languages with its tags', async () => {
    const calls = mockApi({ ...baseRoutes(), 'PUT /api/cv/facts/10': braive.facts[0] })
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: /Systemutvecklare \(praktik\)/ }))
    const english = screen.getByDisplayValue('Built microservices.')
    await userEvent.clear(english)
    await userEvent.type(english, 'Built and deployed microservices.')
    await userEvent.type(screen.getByLabelText('Add to Fact tags'), 'spring{Enter}')
    await userEvent.click(screen.getByRole('button', { name: 'Save fact' }))

    const put = calls.find((c) => c.url === '/api/cv/facts/10')!
    expect(put.body).toEqual({
      textEn: 'Built and deployed microservices.', textSv: 'Byggde mikrotjänster.', evidence: null, metrics: null,
      tags: ['java', 'spring'],
    })
  })

  it('adds a new fact to an entry', async () => {
    const calls = mockApi({ ...baseRoutes(), 'POST /api/cv/entries/2/facts': { id: 11 } })
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: /Masterexamen/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Add fact' }))
    await userEvent.type(screen.getByLabelText('English'), 'Wrote a thesis on gait analysis.')
    await userEvent.click(screen.getByRole('button', { name: 'Save fact' }))

    const post = calls.find((c) => c.method === 'POST')!
    expect(post.url).toBe('/api/cv/entries/2/facts')
    expect(post.body).toMatchObject({ textEn: 'Wrote a thesis on gait analysis.', textSv: null })
  })

  it('creates a new entry', async () => {
    const calls = mockApi({ ...baseRoutes(), 'POST /api/cv/entries': { ...gih, id: 3 } })
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Add entry' }))
    await userEvent.selectOptions(screen.getByLabelText('Kind'), 'WORK')
    await userEvent.type(screen.getByLabelText('Title (Svenska)'), 'Idrottslärare')
    await userEvent.type(screen.getByLabelText('Start (YYYY-MM or YYYY)'), '2019-08')
    await userEvent.click(screen.getByRole('button', { name: 'Save entry' }))

    expect(calls.find((c) => c.method === 'POST')!.body).toMatchObject({ kind: 'WORK', title: 'Idrottslärare', startYm: '2019-08' })
  })

  it('shows the API error when saving fails', async () => {
    mockApi({
      ...baseRoutes(),
      'PUT /api/cv/entries/1': () =>
        new Response(JSON.stringify({ error: 'startYm must be YYYY-MM or YYYY' }), { status: 400 }),
    })
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: /Systemutvecklare \(praktik\)/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Save entry' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('startYm must be YYYY-MM or YYYY')
  })

  it('offers the seed loader when the database is empty and posts the file as the body', async () => {
    const calls = mockApi({
      ...baseRoutes([], { ...profile, id: null, fullName: '' }),
      'POST /api/cv/seed': { entries: 15 },
    })
    renderPage()

    expect(await screen.findByText('Your CV database is empty')).toBeInTheDocument()
    const file = new File(['{"profile":{}}'], 'cv_database_seed.json', { type: 'application/json' })
    await userEvent.upload(screen.getByLabelText('Seed file'), file)

    await vi.waitFor(() => expect(calls.some((c) => c.url === '/api/cv/seed')).toBe(true))
    expect(calls.find((c) => c.url === '/api/cv/seed')!.body).toEqual({ profile: {} })
  })

  it('saves known gaps one per line', async () => {
    const calls = mockApi({ ...baseRoutes(), 'PUT /api/cv/gaps': [] })
    renderPage()

    await userEvent.click(await screen.findByRole('tab', { name: 'Skills and gaps' }))
    const box = await screen.findByLabelText('Known gaps')
    await userEvent.type(box, '{Enter}No Figma experience')
    await userEvent.click(screen.getByRole('button', { name: 'Save gaps' }))

    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({
      gaps: ['No professional Angular experience', 'No Figma experience'],
    })
  })
})

describe('import wizard', () => {
  const preview = {
    previewId: 'p1', files: ['cv.pdf'], skills: [{ name: 'Java', category: 'language' }],
    entries: [
      { entry: { kind: 'INTERNSHIP', title: 'Systemutvecklare (praktik)', titleEn: null, organisation: 'Braive AB', location: null, startYm: '2026-01', endYm: '2026-05', ongoing: false, tags: [], facts: [{ textEn: 'Built microservices.', textSv: null, tags: [] }] }, duplicateOfEntryId: 1 },
      { entry: { kind: 'WORK', title: 'Idrottslärare', titleEn: null, organisation: 'Nacka skola', location: null, startYm: '2019', endYm: null, ongoing: true, tags: ['teaching'], facts: [{ textEn: 'Taught PE.', textSv: null, tags: [] }] }, duplicateOfEntryId: null },
    ],
  }

  it('uploads, leaves duplicates unchecked and commits only the checked entries', async () => {
    const calls = mockApi({
      ...baseRoutes(),
      'POST /api/cv/import': preview,
      'POST /api/cv/import/p1/commit': { entryIds: [3], facts: 1, skills: 1 },
    })
    renderPage()

    await userEvent.click(await screen.findByRole('tab', { name: 'Import' }))
    await userEvent.upload(screen.getByLabelText('CV and letter files'), new File(['%PDF'], 'cv.pdf', { type: 'application/pdf' }))
    await userEvent.click(screen.getByRole('button', { name: 'Analyse' }))

    expect(await screen.findByText('already in database')).toBeInTheDocument()
    expect(screen.getByLabelText('Import Systemutvecklare (praktik)')).not.toBeChecked()
    expect(screen.getByLabelText('Import Idrottslärare')).toBeChecked()
    expect(calls.find((c) => c.url === '/api/cv/import')!.body).toBeInstanceOf(FormData)

    await userEvent.click(screen.getByRole('button', { name: 'Import 1 entry' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Imported 1 entries with 1 facts')
    const commit = calls.find((c) => c.url === '/api/cv/import/p1/commit')!.body as { entries: { title: string }[] }
    expect(commit.entries.map((e) => e.title)).toEqual(['Idrottslärare'])
  })
})

describe('preview selection', () => {
  it('shows which facts would be chosen for a pasted ad', async () => {
    const calls = mockApi({
      ...baseRoutes(),
      'POST /api/cv/preview-selection': {
        language: 'sv',
        entries: [{ entryId: 1, title: 'Software Developer (intern)', kind: 'INTERNSHIP', use: 'both', reason: 'Matches the Java requirement',
          facts: [{ id: 10, text: 'Byggde mikrotjänster.' }] }],
      },
    })
    renderPage()

    await userEvent.click(await screen.findByRole('tab', { name: 'Preview selection' }))
    expect(screen.getByRole('button', { name: 'Preview selection' })).toBeDisabled()
    await userEvent.type(screen.getByLabelText('Pasted ad'), 'Vi söker en Javautvecklare.')
    await userEvent.click(screen.getByRole('button', { name: 'Preview selection' }))

    expect(await screen.findByText('Matches the Java requirement')).toBeInTheDocument()
    expect(screen.getByText('Byggde mikrotjänster.')).toBeInTheDocument()
    expect(screen.getByText('Ad language: Swedish')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ ad: 'Vi söker en Javautvecklare.' })
  })

  it('shows the API error, for example when no search has run yet', async () => {
    mockApi({
      ...baseRoutes(),
      'POST /api/cv/preview-selection': () =>
        new Response(JSON.stringify({ error: 'Run a search first: the selection uses the newest search profile' }), { status: 400 }),
    })
    renderPage()

    await userEvent.click(await screen.findByRole('tab', { name: 'Preview selection' }))
    await userEvent.type(screen.getByLabelText('Pasted ad'), 'text')
    await userEvent.click(screen.getByRole('button', { name: 'Preview selection' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Run a search first')
  })
})
