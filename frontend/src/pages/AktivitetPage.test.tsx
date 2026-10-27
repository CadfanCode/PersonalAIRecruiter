import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AktivitetEntry, AktivitetLogRow, AktivitetPlan } from '../api/aktivitet'
import { run } from '../test/fixtures'
import { mockApi } from '../test/mockApi'
import { AktivitetPage } from './AktivitetPage'
import { Dashboard } from './Dashboard'

const entry = (jobId: number, employer: string, over: Partial<AktivitetEntry> = {}): AktivitetEntry => ({
  jobId, title: `Job ${jobId}`, yrkesroll: 'Systemutvecklare/Programmerare', employerLabel: employer, municipality: 'Stockholm',
  omfattning: 'Heltid', varFinns: 'Sverige', answeredAd: true, appliedDate: '2026-10-26', ...over,
})

const plans: AktivitetPlan[] = [
  { jobId: 1, title: 'Job 1', entry: entry(1, 'Comstream AB (Taxi Stockholm)'), ineligible: null },
  { jobId: 2, title: 'Job 2', entry: entry(2, 'Saab AB', { municipality: 'Järfälla' }), ineligible: null },
  { jobId: 3, title: 'Job 3', entry: null, ineligible: 'Mark the job as applied first; only jobs you applied for are logged.' },
]

const logRow = (id: number, employer: string, result: AktivitetLogRow['result'], message: string): AktivitetLogRow => ({
  id, jobId: id, runId: 9, title: 'x', employerLabel: employer, municipality: 'Stockholm', appliedDate: '2026-10-26',
  savedAt: result === 'SAVED' ? '2026-10-27T09:00:00Z' : null, result, message,
})

function renderPage(path = '/aktivitet?jobs=1,2,3') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <AktivitetPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const inactive = { active: false, day: 20, daysLeft: 0, forMonth: '2026-10', message: '' }
const quiet = { 'GET /api/aktivitet/log': [] as AktivitetLogRow[], 'GET /api/aktivitet/reminder': inactive }

afterEach(() => vi.unstubAllGlobals())

describe('aktivitet page', () => {
  it('says that the monthly report is never submitted and shows an empty state', async () => {
    mockApi(quiet)
    renderPage('/aktivitet')

    expect(await screen.findByText(/Mark jobs as applied/)).toBeInTheDocument()
    expect(screen.getByText(/never submits the monthly report/)).toBeInTheDocument()
  })

  it('shows every value that will be written and why a job cannot be logged', async () => {
    mockApi({ 'GET /api/aktivitet/plan?jobIds=1,2,3': plans, ...quiet })
    renderPage()

    expect(await screen.findByLabelText('Arbetsgivaren for Job 1')).toHaveValue('Comstream AB (Taxi Stockholm)')
    expect(screen.getByLabelText('Yrkesroll for Job 1')).toHaveValue('Systemutvecklare/Programmerare')
    expect(screen.getByLabelText('Ort for Job 2')).toHaveValue('Järfälla')
    expect(screen.getByLabelText('Omfattning for Job 1')).toHaveValue('Heltid')
    expect(screen.getByLabelText('Var finns jobbet for Job 1')).toHaveValue('Sverige')
    expect(screen.getByLabelText('Annons for Job 1')).toHaveValue('Ja')
    expect(screen.getByLabelText('Datum for Job 1')).toHaveValue('2026-10-26')
    expect(screen.getByText(/Mark the job as applied first/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save 2 in the aktivitetsrapport' })).toBeEnabled()
  })

  it('sends exactly the edited, included entries', async () => {
    const calls = mockApi({
      'GET /api/aktivitet/plan?jobIds=1,2,3': plans,
      ...quiet,
      'POST /api/aktivitet/log': { runId: 9 },
      'GET /api/runs/9': {
        run: run({ id: 9, kind: 'AKTIVITET', status: 'RUNNING', step: 'SAVE', progress: 30, message: 'Logging 1 of 1: Comstream' }),
        profile: null,
      },
    })
    renderPage()

    const employer = await screen.findByLabelText('Arbetsgivaren for Job 1')
    await userEvent.clear(employer)
    await userEvent.type(employer, 'Comstream AB')
    await userEvent.selectOptions(screen.getByLabelText('Omfattning for Job 1'), 'Deltid')
    await userEvent.selectOptions(screen.getByLabelText('Annons for Job 1'), 'Nej')
    await userEvent.click(screen.getByLabelText('Include Job 2'))
    await userEvent.click(screen.getByRole('button', { name: 'Save 1 in the aktivitetsrapport' }))

    const post = calls.find((c) => c.method === 'POST')!
    expect(post.body).toEqual({ entries: [entry(1, 'Comstream AB', { omfattning: 'Deltid', answeredAd: false })] })
    expect(await screen.findByRole('progressbar', { name: 'Logging progress' })).toHaveAttribute('aria-valuenow', '30')
    expect(screen.getByText('Logging 1 of 1: Comstream')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Save \d in the aktivitetsrapport/ })).not.toBeInTheDocument()
  })

  it('tells the user to sign in with BankID while the run waits', async () => {
    mockApi({
      'GET /api/aktivitet/plan?jobIds=1': [plans[0]],
      ...quiet,
      'POST /api/aktivitet/log': { runId: 9 },
      'GET /api/runs/9': {
        run: run({
          id: 9, kind: 'AKTIVITET', status: 'WAITING_FOR_USER', step: 'OPEN', progress: 5,
          message: 'Waiting for you to sign in to Arbetsförmedlingen (BankID) in the JobPilot browser',
        }),
        profile: null,
      },
    })
    renderPage('/aktivitet?jobs=1')

    await userEvent.click(await screen.findByRole('button', { name: 'Save 1 in the aktivitetsrapport' }))

    expect(await screen.findByText(/Waiting for you to sign in to Arbetsförmedlingen \(BankID\)/)).toHaveClass('text-amber-700')
  })

  it('shows the result per job when the run is done, including duplicates and failures', async () => {
    mockApi({
      'GET /api/aktivitet/plan?jobIds=1': [plans[0]],
      'POST /api/aktivitet/log': { runId: 9 },
      'GET /api/runs/9': {
        run: run({ id: 9, kind: 'AKTIVITET', message: 'Saved 1 of 3 in the aktivitetsrapport. The monthly report itself is not submitted; do that yourself.' }),
        profile: null,
      },
      'GET /api/aktivitet/log': [
        logRow(1, 'Comstream AB', 'SAVED', 'Saved'),
        logRow(2, 'Friday', 'DUPLICATE', 'The aktivitetsrapport already lists this employer on this date'),
        logRow(3, 'Kiruna Gruva AB', 'FAILED', 'Not saved: Ort: no suggestion matched exactly'),
      ],
      'GET /api/aktivitet/reminder': inactive,
    })
    renderPage('/aktivitet?jobs=1')

    await userEvent.click(await screen.findByRole('button', { name: 'Save 1 in the aktivitetsrapport' }))

    expect(await screen.findByText(/Saved 1 of 3/)).toBeInTheDocument()
    const progress = screen.getByText('Progress').closest('section')!
    expect(within(progress).getByText('saved')).toBeInTheDocument()
    expect(within(progress).getByText('duplicate')).toBeInTheDocument()
    expect(within(progress).getByText('failed')).toBeInTheDocument()
    expect(within(progress).getByText('Not saved: Ort: no suggestion matched exactly')).toBeInTheDocument()
  })

  it('keeps polling while the run waits for sign-in and shows the result when it finishes', async () => {
    let polls = 0
    const view = (status: 'RUNNING' | 'WAITING_FOR_USER' | 'DONE', message: string) =>
      new Response(JSON.stringify({ run: run({ id: 9, kind: 'AKTIVITET', status, message }), profile: null }), { status: 200 })
    mockApi({
      'GET /api/aktivitet/plan?jobIds=1': [plans[0]],
      'GET /api/aktivitet/reminder': inactive,
      'POST /api/aktivitet/log': { runId: 9 },
      // RUNNING, then waiting for BankID, then running again, then done: the page must follow all of it without a reload
      'GET /api/runs/9': () => {
        polls += 1
        if (polls === 1) return view('RUNNING', 'Opening')
        if (polls === 2) return view('WAITING_FOR_USER', 'Waiting for you to sign in')
        if (polls === 3) return view('RUNNING', 'Logging 1 of 1')
        return view('DONE', 'Saved 1 of 1')
      },
      // the log has nothing until the run is done, so the table only appears if the page refetches it at the end
      'GET /api/aktivitet/log': () =>
        new Response(JSON.stringify(polls >= 4 ? [logRow(1, 'Comstream AB', 'SAVED', 'Saved')] : []), { status: 200 }),
    })
    renderPage('/aktivitet?jobs=1')

    await userEvent.click(await screen.findByRole('button', { name: 'Save 1 in the aktivitetsrapport' }))

    expect(await screen.findByText('Saved 1 of 1', {}, { timeout: 9000 })).toBeInTheDocument()
    // shown in the progress panel and in the full log: both come from the refetch at the end of the run
    expect((await screen.findAllByText('saved', {}, { timeout: 3000 })).length).toBeGreaterThan(0)
  }, 20000)

  it('shows the API error when the confirmed values are refused', async () => {
    mockApi({
      'GET /api/aktivitet/plan?jobIds=1': [plans[0]],
      ...quiet,
      'POST /api/aktivitet/log': () =>
        new Response(JSON.stringify({ error: 'Job 1: omfattning must be Heltid, Deltid or Timmar vid behov' }), { status: 400 }),
    })
    renderPage('/aktivitet?jobs=1')

    await userEvent.click(await screen.findByRole('button', { name: 'Save 1 in the aktivitetsrapport' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('omfattning must be')
  })
})

describe('reminder banner', () => {
  function renderDashboard(reminder: unknown) {
    mockApi({
      'GET /api/runs': [],
      'GET /api/claude/usage': { totals: { calls: 0, failures: 0, costUsd: 0 }, byTask: [] },
      'GET /api/aktivitet/reminder': reminder,
    })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    return render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <Dashboard />
        </MemoryRouter>
      </QueryClientProvider>,
    )
  }

  it('shows during the report window and says JobPilot never submits', async () => {
    renderDashboard({
      active: true, day: 5, daysLeft: 11, forMonth: '2026-10',
      message: 'The aktivitetsrapport for October 2026 can be submitted until the 16th (11 days left). Log your applied jobs first. JobPilot never submits the report: do that yourself on Arbetsförmedlingen.',
    })

    const note = await screen.findByRole('note')
    expect(note).toHaveTextContent('11 days left')
    expect(note).toHaveTextContent('JobPilot never submits the report')
    expect(within(note).getByRole('link', { name: 'Open the aktivitetsrapport log' })).toHaveAttribute('href', '/aktivitet')
  })

  it('is hidden outside the window', async () => {
    renderDashboard({ active: false, day: 17, daysLeft: 0, forMonth: '2026-10', message: '' })

    await screen.findByText('New run')
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
  })
})
