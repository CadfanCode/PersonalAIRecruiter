import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { RoleView } from '../api/runs'
import { job, profile, run } from '../test/fixtures'
import { countryView, onboarded } from '../test/fixtures2'
import { mockApi } from '../test/mockApi'
import { Dashboard } from './Dashboard'
import { RunPage } from './RunPage'

function renderRun(path = '/runs/7') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/runs/:id" element={<RunPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const searchMode = {
  'GET /api/runs': [],
  'GET /api/onboarding': onboarded,
  'GET /api/country': countryView,
  'GET /api/aktivitet/reminder': { active: false, day: 20, daysLeft: 0, forMonth: '2026-09', message: '' },
}

afterEach(() => vi.unstubAllGlobals())

describe('run page', () => {
  it('waits for review: profile is editable and Continue starts the search', async () => {
    const waiting = run({ status: 'WAITING_FOR_USER', step: 'PROFILE', progress: 10, message: 'Review the search plan, then continue' })
    const calls = mockApi({
      'GET /api/runs/7': { run: waiting, profile },
      'GET /api/runs/7/jobs': [],
      'PUT /api/runs/7/profile': { run: waiting, profile },
      'POST /api/runs/7/continue': { run: waiting, profile },
    })
    renderRun()

    expect(await screen.findByText('Junior Java developer in Stockholm')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Add to Municipalities'), 'solna{Enter}')
    await userEvent.type(screen.getByLabelText('Add to Exclusions (words in the title)'), 'lead{Enter}')
    await userEvent.click(screen.getByRole('button', { name: 'Save profile' }))
    await vi.waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true))
    const saved = calls.find((c) => c.method === 'PUT')!.body as typeof profile
    expect(saved.locations.map((l) => l.municipality)).toEqual(['Stockholm', 'Solna'])
    expect(saved.exclusions).toEqual(['senior', 'lead'])

    expect(screen.getByText(/Approving starts the search/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Approve and search' }))
    await vi.waitFor(() => expect(calls.some((c) => c.url === '/api/runs/7/continue')).toBe(true))
  })

  it('shows progress with the time left while working, results as they are scored, and hides the editing controls', async () => {
    mockApi({
      'GET /api/runs/7': { run: run({ status: 'RUNNING', step: 'RANK', progress: 62, message: 'Scored 5 of 8' }), profile, etaSeconds: 95 },
      'GET /api/runs/7/jobs': [job({ title: 'Junior Java-utvecklare', rank: 1, total: 87, employer: 'Comstream AB' })],
    })
    renderRun()

    expect(await screen.findByRole('progressbar', { name: 'Scoring the ads against your CV progress' })).toHaveAttribute('aria-valuenow', '62')
    expect(screen.getByText('Scored 5 of 8')).toBeInTheDocument()
    expect(screen.getByText('About 2 minutes left')).toBeInTheDocument()
    expect(await screen.findByText(/1 ad found so far/)).toBeInTheDocument()
    expect(await screen.findByText('Junior Java-utvecklare')).toBeInTheDocument() // the ad is in the table before the run ends
    expect(screen.queryByRole('button', { name: 'Save profile' })).not.toBeInTheDocument()
  })

  it('says it is estimating until there is something to base the time on', async () => {
    mockApi({
      'GET /api/runs/7': { run: run({ status: 'RUNNING', step: 'SEARCH', progress: 20, message: 'Searching Platsbanken' }), profile, etaSeconds: null },
      'GET /api/runs/7/jobs': [],
    })
    renderRun()

    expect(await screen.findByText('Estimating the time left…')).toBeInTheDocument()
  })

  it('shows the ranked table once the run is done', async () => {
    mockApi({
      'GET /api/runs/7': { run: run(), profile },
      'GET /api/runs/7/jobs': [job({ title: 'Junior Java-utvecklare', rank: 1, total: 87, employer: 'Comstream AB' })],
    })
    renderRun()

    expect(await screen.findByText('Junior Java-utvecklare')).toBeInTheDocument()
    expect(screen.getByText('Comstream AB')).toBeInTheDocument()
  })

  it('fills the table by itself while the run progresses and never claims "no ads" on the way', async () => {
    let runPolls = 0
    let jobPolls = 0
    const finished = [job({ title: 'Junior Java-utvecklare', rank: 1, total: 87, employer: 'Comstream AB' })]
    mockApi({
      'GET /api/runs/7': () => {
        runPolls++
        return runPolls < 3
          ? { run: run({ status: 'RUNNING', step: 'SEARCH', progress: 30, message: 'Searching Platsbanken' }), profile }
          : { run: run(), profile }
      },
      'GET /api/runs/7/jobs': () => (++jobPolls < 3 ? [] : finished),
    })
    renderRun()

    expect((await screen.findAllByText('Searching Platsbanken')).length).toBeGreaterThan(0)
    expect(screen.queryByText(/No ads matched/)).not.toBeInTheDocument()
    expect(await screen.findByText('Junior Java-utvecklare', undefined, { timeout: 8000 })).toBeInTheDocument()
    expect(screen.queryByText(/No ads matched/)).not.toBeInTheDocument()
    expect(runPolls).toBeGreaterThanOrEqual(3)
  }, 12000)

  it('explains a failed run', async () => {
    mockApi({
      'GET /api/runs/7': { run: run({ status: 'FAILED', step: 'SEARCH', message: 'Every JobTech query failed' }), profile: null },
    })
    renderRun()

    expect(await screen.findByText('Every JobTech query failed')).toHaveClass('text-[var(--danger)]')
  })

  it('starts a Prompt search from Search mode: no max-age field, no review or career-page checkbox, and opens the run', async () => {
    const calls = mockApi({
      ...searchMode,
      'POST /api/runs': { id: 7 },
      'GET /api/runs/7': { run: run({ status: 'QUEUED', step: 'PROFILE', progress: 0, message: 'Queued' }), profile: null },
    })
    renderRun('/')

    await userEvent.click(await screen.findByRole('button', { name: 'Accountant in Malmö, part time' }))
    expect(screen.getByLabelText('Starting prompt')).toHaveValue('Accountant in Malmö, part time')
    expect(screen.queryByLabelText(/Max age/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Review the search profile first/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Also search career pages/)).not.toBeInTheDocument()
    expect(screen.getByPlaceholderText('20')).toBeInTheDocument() // ads to score default to 20
    await userEvent.click(screen.getByRole('button', { name: 'Make a search plan' }))

    expect(await screen.findByRole('heading', { name: 'Your search' })).toBeInTheDocument() // the run page has loaded
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ prompt: 'Accountant in Malmö, part time', mode: 'TARGETED' })
  })

  it('sends the number of ads to score when the user changes it', async () => {
    const calls = mockApi({
      ...searchMode,
      'POST /api/runs': { id: 7 },
      'GET /api/runs/7': { run: run({ status: 'QUEUED', step: 'PROFILE', progress: 0, message: 'Queued' }), profile: null },
    })
    renderRun('/')

    await userEvent.type(await screen.findByLabelText('Starting prompt'), 'Nurse in Uppsala')
    await userEvent.type(screen.getByLabelText(/Ads to score/), '120')
    await userEvent.click(screen.getByRole('button', { name: 'Make a search plan' }))

    await vi.waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ prompt: 'Nurse in Uppsala', mode: 'TARGETED', limit: 120 })
  })

  it('cannot start a search with an empty prompt', async () => {
    mockApi(searchMode)
    renderRun('/')

    expect(await screen.findByRole('button', { name: 'Make a search plan' })).toBeDisabled()
  })

  describe('discover roles', () => {
    const role = (over: Partial<RoleView> = {}): RoleView => ({
      id: 1, title: 'Javautvecklare', category: 'RELATED', matchScore: 85, reasoning: 'Your Java internships.', gaps: 'No Angular.',
      selected: true, userAdded: false, searched: false, jobCount: 0, ...over,
    })
    const roles = [
      role(),
      role({ id: 2, title: 'Lärare i idrott och hälsa', category: 'OUTSIDE_FIELD', matchScore: 72, reasoning: 'Master in sport science.', gaps: 'No teaching licence.', selected: false }),
      role({ id: 3, title: 'Säkerhetsrådgivare', category: 'HYBRID', matchScore: 50, selected: false }),
    ]
    const discoverRun = (over = {}) => run({
      status: 'WAITING_FOR_USER', step: 'SUGGEST', progress: 40, message: 'Choose the roles to search for, then continue',
      options: { maxAgeDays: null, limit: null, includeWeb: null, autoContinue: false, mode: 'DISCOVER', aggressive: false }, ...over,
    })

    it('starts a Discovery search from Search mode with the aggressive option and no prompt needed', async () => {
      const calls = mockApi({
        ...searchMode,
        'POST /api/runs': { id: 7 },
        'GET /api/runs/7': { run: discoverRun({ status: 'RUNNING', step: 'PROFILE' }), profile: null },
      })
      renderRun('/')

      await userEvent.click(await screen.findByRole('radio', { name: /Discovery Mode/ }))
      expect(screen.getByRole('button', { name: 'Find roles' })).toBeEnabled()
      expect(screen.queryByLabelText('Also search career pages (slower)')).not.toBeInTheDocument()
      await userEvent.click(screen.getByLabelText(/Go wider/))
      await userEvent.click(screen.getByRole('button', { name: 'Find roles' }))

      await vi.waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
      expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ prompt: '', mode: 'DISCOVER', aggressive: true })
    })

    it('shows the suggested roles in groups with score, reasoning and gaps, and the Roles step', async () => {
      mockApi({ 'GET /api/runs/7': { run: discoverRun(), profile }, 'GET /api/runs/7/jobs': [], 'GET /api/runs/7/roles': roles })
      renderRun()

      const related = await screen.findByRole('region', { name: 'Closely related roles' })
      expect(within(related).getByText('Javautvecklare')).toBeInTheDocument()
      expect(within(related).getByLabelText('Match 85 out of 100')).toBeInTheDocument()
      expect(within(related).getByText('Your Java internships.')).toBeInTheDocument()
      const outside = screen.getByRole('region', { name: 'Outside your main field' })
      expect(within(outside).getByText('Gaps: No teaching licence.')).toBeInTheDocument()
      expect(screen.getByRole('region', { name: 'Hybrid roles' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Search 1 selected role' })).toBeEnabled()
      // the search plan of a Discovery run is made and accepted for the user: it is not shown
      expect(screen.queryByText(/Where and when to search/)).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Save profile' })).not.toBeInTheDocument()
    })

    it('selects all, lets the user add and edit roles, then saves them and continues the search', async () => {
      const calls = mockApi({
        'GET /api/runs/7': { run: discoverRun(), profile }, 'GET /api/runs/7/jobs': [], 'GET /api/runs/7/roles': roles,
        'PUT /api/runs/7/roles': roles, 'POST /api/runs/7/continue': { run: discoverRun(), profile },
      })
      renderRun()

      await userEvent.click(await screen.findByLabelText(/Select all/))
      expect(screen.getByRole('button', { name: 'Search 3 selected roles' })).toBeInTheDocument()
      await userEvent.type(screen.getByLabelText('Add your own role'), 'Behandlingsassistent{Enter}')
      expect(screen.getByRole('region', { name: 'Your own roles' })).toHaveTextContent('Behandlingsassistent')
      await userEvent.click(screen.getByRole('button', { name: 'Edit Säkerhetsrådgivare' }))
      await userEvent.clear(screen.getByLabelText('Rename Säkerhetsrådgivare'))
      await userEvent.type(screen.getByLabelText('Rename Säkerhetsrådgivare'), 'Säkerhetssamordnare{Enter}')
      expect(screen.getByText(/Renamed: its search terms are rebuilt/)).toBeInTheDocument()
      await userEvent.click(screen.getByLabelText('Search for Lärare i idrott och hälsa')) // untick one again
      await userEvent.click(screen.getByRole('button', { name: 'Search 3 selected roles' }))

      await vi.waitFor(() => expect(calls.some((c) => c.url === '/api/runs/7/continue')).toBe(true))
      expect(calls.find((c) => c.method === 'PUT' && c.url === '/api/runs/7/roles')!.body).toEqual([
        { id: 1, title: 'Javautvecklare', selected: true },
        { id: 2, title: 'Lärare i idrott och hälsa', selected: false },
        { id: 3, title: 'Säkerhetssamordnare', selected: true },
        { title: 'Behandlingsassistent', selected: true },
      ])
    })

    it('keeps the suggestions after the search, with what each role found, and tags every ad with its role', async () => {
      const done = discoverRun({ status: 'DONE', step: 'DONE', progress: 100, message: 'Found 9 ads' })
      const rolesAfter = [role({ searched: true, jobCount: 4 }), role({ id: 2, title: 'Lärare', searched: false })]
      mockApi({
        'GET /api/runs/7': { run: done, profile }, 'GET /api/runs/7/roles': rolesAfter,
        'GET /api/runs/7/jobs': [
          { ...job({ title: 'Idrottslärare', rank: 1, total: 31 }), roleType: 'Lärare', roleTypes: ['Lärare', 'Javautvecklare'] },
          { ...job({ title: 'Java dev', rank: 2, total: 20 }), roleType: 'Javautvecklare', roleTypes: ['Javautvecklare'] },
        ],
      })
      renderRun()

      expect(await screen.findByText('Role suggestions for this run (1 of 2 searched)')).toBeInTheDocument()
      expect(screen.getByText('4 ads found')).toBeInTheDocument()
      // Discover starts with a minimum score of 40, so the 31 and 20 point ads are hidden until the user widens it
      expect(screen.getByLabelText('Minimum score')).toHaveValue(40)
      expect(screen.getByText('No ads match.')).toBeInTheDocument()
      await userEvent.clear(screen.getByLabelText('Minimum score'))
      expect(await screen.findByText('Idrottslärare')).toBeInTheDocument()
      expect(screen.getByText('also found for Javautvecklare')).toBeInTheDocument()
      await userEvent.selectOptions(screen.getByLabelText('Filter by role type'), 'Javautvecklare')
      expect(screen.queryByText('Idrottslärare')).not.toBeInTheDocument()
    })
  })

  describe('paste a job link', () => {
    const manualRun = (over = {}) => run({
      status: 'WAITING_FOR_USER', step: 'FETCH', progress: 10, sourceUrl: 'https://www.linkedin.com/jobs/view/1',
      message: 'This site discourages automatic reading, so JobPilot does not open it. Copy the ad text from your own browser and paste it below.',
      options: { maxAgeDays: null, limit: null, includeWeb: null, autoContinue: true, mode: 'MANUAL', aggressive: null }, ...over,
    })

    it('adds a job from Search mode by its address and follows the run', async () => {
      const calls = mockApi({
        ...searchMode,
        'POST /api/import': { id: 7 },
        'GET /api/runs/7': { run: manualRun({ status: 'RUNNING', message: 'Reading the ad' }), profile: null },
      })
      renderRun('/')

      await userEvent.click(await screen.findByText('I found a job myself, add it from a link'))
      expect(screen.getByRole('button', { name: 'Add this job' })).toBeDisabled()
      expect(screen.getByText(/never opened or read by JobPilot/)).toBeInTheDocument()
      await userEvent.type(screen.getByLabelText('Job ad address'), 'https://www.linkedin.com/jobs/view/1')
      await userEvent.click(screen.getByRole('button', { name: 'Add this job' }))

      expect((await screen.findAllByText('Reading the ad')).length).toBeGreaterThan(0)
      expect(calls.find((c) => c.url === '/api/import')!.body).toEqual({ url: 'https://www.linkedin.com/jobs/view/1' })
    })

    it('asks for the pasted ad text when the page cannot be read and sends it', async () => {
      const calls = mockApi({
        'GET /api/runs/7': { run: manualRun(), profile: null }, 'GET /api/runs/7/jobs': [],
        'POST /api/import/7/text': null,
      })
      renderRun()

      expect(await screen.findAllByText(/discourages automatic reading/)).not.toHaveLength(0)
      expect(screen.getByRole('link', { name: 'the ad' })).toHaveAttribute('href', 'https://www.linkedin.com/jobs/view/1')
      const send = screen.getByRole('button', { name: 'Use this text' })
      expect(send).toBeDisabled()
      await userEvent.type(screen.getByLabelText('Ad text'), 'x'.repeat(250))
      await userEvent.click(send)

      await vi.waitFor(() => expect(calls.some((c) => c.url === '/api/import/7/text')).toBe(true))
      expect(calls.find((c) => c.url === '/api/import/7/text')!.body).toEqual({ text: 'x'.repeat(250) })
    })

    it('starts the single pasted job ticked, so the next step is one click away', async () => {
      mockApi({
        'GET /api/runs/7': { run: manualRun({ status: 'DONE', step: 'DONE', message: 'Added it' }), profile },
        'GET /api/runs/7/jobs': [job({ title: 'Lärare i idrott', rank: 1, total: 65 })],
      })
      renderRun()

      expect(await screen.findByRole('button', { name: 'Create tailored documents for 1 selected job' })).toBeInTheDocument()
    })
  })
})
