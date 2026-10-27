import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { RoleView } from '../api/runs'
import { job, profile, run } from '../test/fixtures'
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

afterEach(() => vi.unstubAllGlobals())

describe('run page', () => {
  it('waits for review: profile is editable and Continue starts the search', async () => {
    const waiting = run({ status: 'WAITING_FOR_USER', step: 'PROFILE', progress: 10, message: 'Review the search profile, then continue' })
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

    await userEvent.click(screen.getByRole('button', { name: 'Continue to search' }))
    await vi.waitFor(() => expect(calls.some((c) => c.url === '/api/runs/7/continue')).toBe(true))
  })

  it('shows progress while working and hides the editing controls', async () => {
    mockApi({
      'GET /api/runs/7': { run: run({ status: 'RUNNING', step: 'RANK', progress: 62, message: 'Scored 5 of 8' }), profile },
      'GET /api/runs/7/jobs': [],
    })
    renderRun()

    expect(await screen.findByRole('progressbar', { name: 'Run progress' })).toHaveAttribute('aria-valuenow', '62')
    expect(screen.getByText('Scored 5 of 8')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save profile' })).not.toBeInTheDocument()
    expect(screen.getByRole('listitem', { current: 'step' })).toHaveTextContent('Rank')
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

    expect(await screen.findByText('Searching Platsbanken')).toBeInTheDocument()
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

    expect(await screen.findByText('Every JobTech query failed')).toHaveClass('text-red-600')
  })

  it('starts a run from the dashboard prompt box and opens it', async () => {
    const calls = mockApi({
      'GET /api/runs': [],
      'GET /api/claude/usage': { totals: { calls: 0, failures: 0 }, byTask: [] },
      'POST /api/runs': { id: 7 },
      'GET /api/runs/7': { run: run({ status: 'QUEUED', step: 'PROFILE', progress: 0, message: 'Queued' }), profile: null },
    })
    renderRun('/')

    await userEvent.click(screen.getByRole('button', { name: 'PE teacher, Nacka' }))
    expect(screen.getByLabelText('Starting prompt')).toHaveValue('PE teacher, Nacka')
    await userEvent.type(screen.getByLabelText('Max age (days)'), '14')
    await userEvent.click(screen.getByRole('button', { name: 'Start run' }))

    expect(await screen.findByText('Queued')).toBeInTheDocument() // the run page has loaded
    expect(screen.getByRole('heading', { name: 'Run 7' })).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({
      prompt: 'PE teacher, Nacka', mode: 'TARGETED', maxAgeDays: 14, includeWeb: false, autoContinue: false,
    })
  })

  it('cannot start a run with an empty prompt', () => {
    mockApi({ 'GET /api/runs': [], 'GET /api/claude/usage': { totals: { calls: 0, failures: 0 }, byTask: [] } })
    renderRun('/')

    expect(screen.getByRole('button', { name: 'Start run' })).toBeDisabled()
  })

  describe('discover roles', () => {
    const role = (over: Partial<RoleView> = {}): RoleView => ({
      id: 1, title: 'Javautvecklare', category: 'RELATED', matchScore: 85, reasoning: 'Your Java internships.', gaps: 'No Angular.',
      selected: true, userAdded: false, searched: false, jobCount: 0, ...over,
    })
    const roles = [
      role(),
      role({ id: 2, title: 'Lärare i idrott och hälsa', category: 'OUTSIDE_TECH', matchScore: 72, reasoning: 'Master in sport science.', gaps: 'No teaching licence.', selected: false }),
      role({ id: 3, title: 'Säkerhetsrådgivare', category: 'HYBRID', matchScore: 50, selected: false }),
    ]
    const discoverRun = (over = {}) => run({
      status: 'WAITING_FOR_USER', step: 'SUGGEST', progress: 40, message: 'Choose the roles to search for, then continue',
      options: { maxAgeDays: null, limit: null, includeWeb: null, autoContinue: false, mode: 'DISCOVER', aggressive: false }, ...over,
    })

    it('starts a Discover run from the dashboard with the aggressive option and no prompt needed', async () => {
      const calls = mockApi({
        'GET /api/runs': [], 'GET /api/claude/usage': { totals: { calls: 0, failures: 0 }, byTask: [] },
        'POST /api/runs': { id: 7 },
        'GET /api/runs/7': { run: discoverRun({ status: 'RUNNING', step: 'PROFILE' }), profile: null },
      })
      renderRun('/')

      await userEvent.click(screen.getByRole('radio', { name: /Discover roles/ }))
      expect(screen.getByRole('button', { name: 'Find roles' })).toBeEnabled()
      expect(screen.queryByLabelText('Also search career pages (slower)')).not.toBeInTheDocument()
      await userEvent.click(screen.getByLabelText(/Aggressive/))
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
      const outside = screen.getByRole('region', { name: 'Outside tech' })
      expect(within(outside).getByText('Gaps: No teaching licence.')).toBeInTheDocument()
      expect(screen.getByRole('region', { name: 'Hybrid roles' })).toBeInTheDocument()
      expect(screen.getByRole('listitem', { current: 'step' })).toHaveTextContent('Roles')
      expect(screen.getByRole('button', { name: 'Search 1 selected role' })).toBeEnabled()
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
})
