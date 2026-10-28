import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
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
      <MemoryRouter><CvDatabase /></MemoryRouter>
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

  it('points an empty database at the import and does not offer the author\'s seed file', async () => {
    mockApi(baseRoutes([], { ...profile, id: null, fullName: '' }))
    renderPage()

    expect(await screen.findByText('Your CV database is empty')).toBeInTheDocument()
    expect(screen.getByText(/Read your CV in the Import tab/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Seed file')).not.toBeInTheDocument()
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
  const entries = [
    { entry: { kind: 'INTERNSHIP', title: 'Systemutvecklare (praktik)', titleEn: null, organisation: 'Braive AB', location: null, startYm: '2026-01', endYm: '2026-05', ongoing: false, tags: [], facts: [{ textEn: 'Built microservices.', textSv: null, tags: [] }] },
      matchEntryId: 1, matchTitle: 'Systemutvecklare (praktik), Braive AB', newFacts: 1, knownFacts: [] },
    { entry: { kind: 'WORK', title: 'Idrottslärare', titleEn: null, organisation: 'Nacka skola', location: null, startYm: '2019', endYm: null, ongoing: true, tags: ['teaching'], facts: [{ textEn: 'Taught PE.', textSv: null, tags: [] }] },
      matchEntryId: null, matchTitle: null, newFacts: 0, knownFacts: [] },
    { entry: { kind: 'WORK', title: 'Gammalt jobb', titleEn: null, organisation: 'Old AB', location: null, startYm: '2010', endYm: '2012', ongoing: false, tags: [], facts: [] },
      matchEntryId: 2, matchTitle: 'Gammalt jobb, Old AB', newFacts: 0, knownFacts: [] },
  ]
  const done = { runId: 5, status: 'DONE', message: 'Ready to review', files: ['cv.pdf'], preview: { previewId: 'p1', files: ['cv.pdf'], skills: [{ name: 'Java', category: 'language' }], entries } }

  /** The server side of a reading: starting one makes it current, and it can be set to any state. */
  function reading(initial: unknown = null) {
    let current: unknown = initial
    return {
      set: (v: unknown) => { current = v },
      routes: {
        'GET /api/cv/import/current': () => current,
        'POST /api/cv/import': () => { current = { ...done, status: 'RUNNING', preview: null }; return current },
        'DELETE /api/cv/import/5': () => { current = null; return null },
      },
    }
  }

  async function upload() {
    await userEvent.click(await screen.findByRole('tab', { name: 'Import' }))
    await userEvent.upload(screen.getByLabelText('CV and letter files'), new File(['%PDF'], 'cv.pdf', { type: 'application/pdf' }))
  }

  it('has a real Browse files button, not just a clickable line of text', async () => {
    mockApi({ ...baseRoutes(), ...reading().routes })
    renderPage()

    await userEvent.click(await screen.findByRole('tab', { name: 'Import' }))

    expect(screen.getByRole('button', { name: 'Browse files' })).toBeInTheDocument()
  })

  it('reads in the background, then adds new facts to the entry it matches and creates entries for the rest', async () => {
    const server = reading()
    const calls = mockApi({
      ...baseRoutes(), ...server.routes,
      'POST /api/cv/import/p1/commit': { entryIds: [1, 3], created: 1, enriched: 1, facts: 2, skills: 1, profileFilled: false },
    })
    renderPage()

    await upload()
    await userEvent.click(screen.getByRole('button', { name: 'Read this file' }))
    expect(calls.find((c) => c.url === '/api/cv/import')!.body).toBeInstanceOf(FormData)
    expect(await screen.findByText('Reading your CV')).toBeInTheDocument()

    server.set(done)

    expect(await screen.findByText('Adds 1 fact to Systemutvecklare (praktik), Braive AB', {}, { timeout: 4000 })).toBeInTheDocument()
    expect(screen.getByLabelText('Import Systemutvecklare (praktik)')).toBeChecked()
    expect(screen.getByLabelText('Import Idrottslärare')).toBeChecked()
    expect(screen.getByLabelText('Import Gammalt jobb')).not.toBeChecked() // matches, and has nothing new

    await userEvent.click(screen.getByRole('button', { name: 'Import 2 entries' }))

    expect(await screen.findByRole('status')).toHaveTextContent('1 new entry, 1 entry enriched, 2 facts added')
    const commit = calls.find((c) => c.url === '/api/cv/import/p1/commit')!.body as { entries: { entry: { title: string }; mergeIntoEntryId: number | null }[] }
    expect(commit.entries.map((e) => [e.entry.title, e.mergeIntoEntryId])).toEqual([['Systemutvecklare (praktik)', 1], ['Idrottslärare', null]])
  })

  it('marks the facts an entry already has, and commits that decision', async () => {
    const withKnown = { ...done, preview: { ...done.preview, entries: [{
      ...entries[0], newFacts: 1, knownFacts: [0],
      entry: { ...entries[0].entry, facts: [{ textEn: 'Built microservices.', textSv: null, tags: [] }, { textEn: 'Mentored four students.', textSv: null, tags: [] }] },
    }] } }
    const calls = mockApi({
      ...baseRoutes(), ...reading(withKnown).routes,
      'POST /api/cv/import/p1/commit': { entryIds: [1], created: 0, enriched: 1, facts: 1, skills: 0, profileFilled: false },
    })
    renderPage()

    await userEvent.click(await screen.findByRole('tab', { name: 'Import' }))

    expect(await screen.findByText('Adds 1 fact to Systemutvecklare (praktik), Braive AB')).toBeInTheDocument()
    expect(within(screen.getByText('Built microservices.').closest('li')!).getByText('already stored')).toBeInTheDocument()
    expect(screen.getByText('Mentored four students.').closest('li')).not.toHaveTextContent('already stored')
    await userEvent.click(screen.getByRole('button', { name: 'Import 1 entry' }))

    await screen.findByRole('status')
    const commit = calls.find((c) => c.url === '/api/cv/import/p1/commit')!.body as { entries: { skipFacts: number[] }[] }
    expect(commit.entries[0].skipFacts).toEqual([0])
  })

  it('lets a matched entry be added as a new entry instead', async () => {
    const calls = mockApi({
      ...baseRoutes(), ...reading(done).routes,
      'POST /api/cv/import/p1/commit': { entryIds: [3], created: 2, enriched: 0, facts: 2, skills: 1, profileFilled: false },
    })
    renderPage()

    await userEvent.click(await screen.findByRole('tab', { name: 'Import' }))
    await userEvent.click((await screen.findAllByRole('button', { name: 'Add as a new entry instead' }))[0])
    expect(screen.getByText('Will be a new entry')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Import 2 entries' }))

    await screen.findByRole('status')
    const commit = calls.find((c) => c.url === '/api/cv/import/p1/commit')!.body as { entries: { mergeIntoEntryId: number | null }[] }
    expect(commit.entries.map((e) => e.mergeIntoEntryId)).toEqual([null, null])
  })

  it('finds a reading again after the page was left, and keeps showing it while it runs', async () => {
    mockApi({ ...baseRoutes(), ...reading({ ...done, status: 'RUNNING', preview: null }).routes })
    renderPage()

    await userEvent.click(await screen.findByRole('tab', { name: 'Import' }))

    expect(await screen.findByText('Reading your CV')).toBeInTheDocument()
    expect(screen.getByText(/You can leave this page/)).toBeInTheDocument()
  })

  it('says why a reading failed and offers to try again', async () => {
    mockApi({ ...baseRoutes(), ...reading({ ...done, status: 'FAILED', message: 'Claude took too long', preview: null }).routes })
    renderPage()

    await userEvent.click(await screen.findByRole('tab', { name: 'Import' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Claude took too long')
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
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
