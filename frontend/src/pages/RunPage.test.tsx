import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
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
      'GET /api/claude/usage': { totals: { calls: 0, failures: 0, costUsd: 0 }, byTask: [] },
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
      prompt: 'PE teacher, Nacka', maxAgeDays: 14, includeWeb: false, autoContinue: false,
    })
  })

  it('cannot start a run with an empty prompt', () => {
    mockApi({ 'GET /api/runs': [], 'GET /api/claude/usage': { totals: { calls: 0, failures: 0, costUsd: 0 }, byTask: [] } })
    renderRun('/')

    expect(screen.getByRole('button', { name: 'Start run' })).toBeDisabled()
  })
})
