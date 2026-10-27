import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { JobRow } from '../../api/runs'
import { job } from '../../test/fixtures'
import { mockApi } from '../../test/mockApi'
import { ResultsTable } from './ResultsTable'

const NOW = new Date('2026-10-26T12:00:00Z')

function LocationProbe() {
  return <p>Aktivitet page {useLocation().search}</p>
}

function renderTable(jobs: JobRow[], topN = 10) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/runs/7']}>
        <Routes>
          <Route path="/runs/7" element={<ResultsTable runId={7} jobs={jobs} topN={topN} now={NOW} />} />
          <Route path="/documents/job/:jobId" element={<p>Documents page</p>} />
          <Route path="/apply" element={<p>Apply page</p>} />
          <Route path="/aktivitet" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Titles in the order they appear in the table body. */
function titles() {
  return screen.getAllByRole('row').slice(1).map((r) => within(r).getAllByRole('cell')[3].textContent)
}

const rows = () => [
  job({ title: 'Alpha', rank: 1, total: 90, employer: 'Zeta AB', status: 'SHORTLISTED', language: 'en', deadline: '2026-10-27T10:00:00Z' }),
  job({ title: 'Bravo', rank: 2, total: 70, employer: 'Beta AB', clientEmployer: 'Taxi Stockholm', status: 'NEW' }),
  job({ title: 'Charlie', rank: 3, total: 50, employer: 'Mu AB', status: 'REJECTED', dateUnverified: true, source: 'WEB' }),
  job({ title: 'Delta', rank: null, total: null, employer: 'Alpha AB' }),
]

beforeEach(() => localStorage.clear())
afterEach(() => vi.unstubAllGlobals())

describe('results table', () => {
  it('lists ads in rank order with score, end client and unverified-date flag', () => {
    mockApi({})
    renderTable(rows())

    expect(titles()).toEqual(['Alpha', 'Bravo', 'Charlie', 'Delta'])
    expect(screen.getByText('(Taxi Stockholm)')).toBeInTheDocument()
    expect(screen.getByText('date unverified')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /Tech 45\/50 · Experience 27\/30 · Eligibility 18\/20/ })).toBeInTheDocument()
    expect(screen.getByText('not scored')).toBeInTheDocument()
  })

  it('marks deadlines within three days in red', () => {
    mockApi({})
    renderTable(rows())

    expect(screen.getByText('2026-10-27')).toHaveClass('text-red-600')
    expect(screen.getAllByText('2026-12-01')[0]).not.toHaveClass('text-red-600')
  })

  it('sorts by a column header and reverses on a second click', async () => {
    mockApi({})
    renderTable(rows())

    await userEvent.click(screen.getByRole('button', { name: /^Employer/ }))
    expect(titles()).toEqual(['Delta', 'Bravo', 'Charlie', 'Alpha']) // Alpha AB, Beta AB, Mu AB, Zeta AB
    await userEvent.click(screen.getByRole('button', { name: /^Employer/ }))
    expect(titles()).toEqual(['Alpha', 'Charlie', 'Bravo', 'Delta'])
  })

  it('sorts by score with unscored ads last', async () => {
    mockApi({})
    renderTable(rows())

    await userEvent.click(screen.getByRole('button', { name: /^Score/ })) // numbers sort high to low first
    expect(titles()).toEqual(['Alpha', 'Bravo', 'Charlie', 'Delta'])
    await userEvent.click(screen.getByRole('button', { name: /^Score/ }))
    expect(titles()).toEqual(['Charlie', 'Bravo', 'Alpha', 'Delta'])
  })

  it('filters by search text, which also matches the end client', async () => {
    mockApi({})
    renderTable(rows())

    await userEvent.type(screen.getByLabelText('Search ads'), 'taxi')
    expect(titles()).toEqual(['Bravo'])
    await userEvent.clear(screen.getByLabelText('Search ads'))
    expect(titles()).toHaveLength(4)
  })

  it('filters by status, language and minimum score', async () => {
    mockApi({})
    renderTable(rows())

    await userEvent.selectOptions(screen.getByLabelText('Filter by status'), 'REJECTED')
    expect(titles()).toEqual(['Charlie'])
    await userEvent.selectOptions(screen.getByLabelText('Filter by status'), '')
    await userEvent.selectOptions(screen.getByLabelText('Filter by language'), 'en')
    expect(titles()).toEqual(['Alpha'])
    await userEvent.selectOptions(screen.getByLabelText('Filter by language'), '')
    await userEvent.type(screen.getByLabelText('Minimum score'), '60')
    expect(titles()).toEqual(['Alpha', 'Bravo'])
    await userEvent.type(screen.getByLabelText('Search ads'), 'nothing like this')
    expect(screen.getByText('No ads match.')).toBeInTheDocument()
  })

  it('shows the top N by default and all on request', async () => {
    mockApi({})
    const many = Array.from({ length: 5 }, (_, i) => job({ title: `Job ${i + 1}`, rank: i + 1, total: 90 - i }))
    renderTable(many, 3)

    expect(screen.getAllByRole('row')).toHaveLength(4)
    await userEvent.click(screen.getByRole('button', { name: 'Show all (5)' }))
    expect(screen.getAllByRole('row')).toHaveLength(6)
    await userEvent.click(screen.getByRole('button', { name: 'Show top 3' }))
    expect(screen.getAllByRole('row')).toHaveLength(4)
  })

  it('moves with j and k, shortlists with s and opens with Enter', async () => {
    const all = rows()
    const calls = mockApi({ [`PATCH /api/jobs/${all[1].id}`]: null })
    renderTable(all)

    await userEvent.keyboard('j')
    expect(screen.getAllByRole('row')[2]).toHaveAttribute('data-active', 'true')
    await userEvent.keyboard('k')
    expect(screen.getAllByRole('row')[1]).toHaveAttribute('data-active', 'true')
    await userEvent.keyboard('j')
    await userEvent.keyboard('s')
    await vi.waitFor(() => expect(calls.some((c) => c.method === 'PATCH')).toBe(true))
    expect(calls.find((c) => c.method === 'PATCH')).toMatchObject({ url: `/api/jobs/${all[1].id}`, body: { status: 'SHORTLISTED' } })
  })

  it('sends a status change from the drawer and shows the ad text', async () => {
    const all = rows()
    const bravo = all[1]
    const calls = mockApi({
      [`GET /api/jobs/${bravo.id}?run=7`]: { row: bravo, description: 'Vi söker en utvecklare.' },
      [`PATCH /api/jobs/${bravo.id}`]: null,
    })
    renderTable(all)

    await userEvent.click(screen.getByText('Bravo'))
    const drawer = await screen.findByRole('dialog', { name: 'Job details' })
    expect(await within(drawer).findByText('Vi söker en utvecklare.')).toBeInTheDocument()
    expect(within(drawer).getByRole('link', { name: 'Apply link' })).toHaveAttribute('href', bravo.url)
    await userEvent.click(within(drawer).getByRole('button', { name: 'Mark applied' }))

    await vi.waitFor(() => expect(calls.some((c) => c.method === 'PATCH')).toBe(true))
    expect(calls.find((c) => c.method === 'PATCH')!.body).toEqual({ status: 'APPLIED' })
  })

  it('opens the application from the drawer and goes to the apply monitor', async () => {
    const all = rows()
    const bravo = all[1]
    const calls = mockApi({
      [`GET /api/jobs/${bravo.id}?run=7`]: { row: bravo, description: 'x' },
      'POST /api/apply': [],
    })
    renderTable(all)

    await userEvent.click(screen.getByText('Bravo'))
    await userEvent.click(await screen.findByRole('button', { name: 'Open application' }))

    expect(await screen.findByText('Apply page')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ jobIds: [bravo.id] })
  })

  it('opens the selected rows as applications, up to five at a time', async () => {
    const many = Array.from({ length: 7 }, (_, i) => job({ title: `Row ${i + 1}`, rank: i + 1, total: 90 - i }))
    const calls = mockApi({ 'POST /api/apply': [] })
    renderTable(many, 10)

    for (let i = 1; i <= 6; i++) await userEvent.click(screen.getByLabelText(`Select Row ${i}`))
    const open = screen.getByRole('button', { name: 'Open applications' })
    expect(open).toBeDisabled()
    expect(open).toHaveAttribute('title', expect.stringContaining('At most 5'))
    await userEvent.click(screen.getByLabelText('Select Row 6'))
    await userEvent.click(screen.getByRole('button', { name: 'Open applications' }))

    expect(await screen.findByText('Apply page')).toBeInTheDocument()
    expect((calls.find((c) => c.method === 'POST')!.body as { jobIds: number[] }).jobIds).toHaveLength(5)
  })

  it('logs only the selected jobs that are marked applied, and only when there are some', async () => {
    mockApi({})
    const list = [
      job({ title: 'Applied A', rank: 1, total: 90, status: 'APPLIED' }),
      job({ title: 'New B', rank: 2, total: 80 }),
      job({ title: 'Applied C', rank: 3, total: 70, status: 'APPLIED' }),
    ]
    renderTable(list)

    await userEvent.click(screen.getByLabelText('Select New B'))
    expect(screen.getByRole('button', { name: 'Log in aktivitetsrapport (0)' })).toBeDisabled()
    await userEvent.click(screen.getByLabelText('Select Applied A'))
    await userEvent.click(screen.getByLabelText('Select Applied C'))
    const button = screen.getByRole('button', { name: 'Log in aktivitetsrapport (2)' })
    expect(button).toBeEnabled()
    await userEvent.click(button)

    expect(await screen.findByText(`Aktivitet page ?jobs=${list[0].id},${list[2].id}`)).toBeInTheDocument()
  })

  it('enables Log in aktivitetsrapport in the drawer only for applied jobs', async () => {
    const applied = job({ title: 'Applied job', rank: 1, total: 80, status: 'APPLIED' })
    const open = job({ title: 'Open job', rank: 2, total: 70, status: 'NEW' })
    mockApi({
      [`GET /api/jobs/${applied.id}?run=7`]: { row: applied, description: 'x' },
      [`GET /api/jobs/${open.id}?run=7`]: { row: open, description: 'x' },
    })
    renderTable([applied, open])

    await userEvent.click(screen.getByText('Open job'))
    const disabled = await screen.findByRole('button', { name: 'Log in aktivitetsrapport' })
    expect(disabled).toBeDisabled()
    expect(disabled).toHaveAttribute('title', 'Mark the job as applied first')
    await userEvent.click(screen.getByRole('button', { name: 'Close details' }))

    await userEvent.click(screen.getByText('Applied job'))
    await userEvent.click(await screen.findByRole('button', { name: 'Log in aktivitetsrapport' }))
    expect(await screen.findByText(`Aktivitet page ?jobs=${applied.id}`)).toBeInTheDocument()
  })

  it('opens the documents page from the drawer without generating anything', async () => {
    const all = rows()
    const bravo = all[1]
    const calls = mockApi({ [`GET /api/jobs/${bravo.id}?run=7`]: { row: bravo, description: 'x' } })
    renderTable(all)

    await userEvent.click(screen.getByText('Bravo'))
    await userEvent.click(await screen.findByRole('button', { name: 'Documents' }))

    expect(await screen.findByText('Documents page')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
  })

  it('labels the drawer button "Open documents" once the documents are ready', async () => {
    const ready = job({ title: 'Ready', rank: 1, total: 80, status: 'DOCS_READY' })
    mockApi({ [`GET /api/jobs/${ready.id}?run=7`]: { row: ready, description: 'x' } })
    renderTable([ready])

    await userEvent.click(screen.getByText('Ready'))

    expect(await screen.findByRole('button', { name: 'Open documents' })).toBeInTheDocument()
  })

  it('opens the documents of the active row with the g key and never generates', async () => {
    const all = rows()
    const calls = mockApi({})
    renderTable(all)

    await userEvent.keyboard('g')

    expect(await screen.findByText('Documents page')).toBeInTheDocument()
    expect(calls).toHaveLength(0)
  })

  it('saves notes from the drawer', async () => {
    const all = rows()
    const alpha = all[0]
    const calls = mockApi({
      [`GET /api/jobs/${alpha.id}?run=7`]: { row: alpha, description: 'x' },
      [`PATCH /api/jobs/${alpha.id}`]: null,
    })
    renderTable(all)

    await userEvent.click(screen.getByText('Alpha'))
    await userEvent.type(await screen.findByLabelText('Notes'), 'ring på måndag')
    await userEvent.click(screen.getByRole('button', { name: 'Save notes' }))

    await vi.waitFor(() => expect(calls.some((c) => c.method === 'PATCH')).toBe(true))
    expect(calls.find((c) => c.method === 'PATCH')!.body).toEqual({ notes: 'ring på måndag' })
  })

  it('shortlists the selected rows in one go', async () => {
    const all = rows()
    const calls = mockApi({ [`PATCH /api/jobs/${all[1].id}`]: null, [`PATCH /api/jobs/${all[2].id}`]: null })
    renderTable(all)

    await userEvent.click(screen.getByLabelText('Select Bravo'))
    await userEvent.click(screen.getByLabelText('Select Charlie'))
    expect(screen.getByText('2 selected')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Shortlist selected' }))

    await vi.waitFor(() => expect(calls.filter((c) => c.method === 'PATCH')).toHaveLength(2))
  })

  it('saves a view and restores it later', async () => {
    mockApi({})
    vi.spyOn(window, 'prompt').mockReturnValue('Rejected only')
    renderTable(rows())

    await userEvent.selectOptions(screen.getByLabelText('Filter by status'), 'REJECTED')
    await userEvent.click(screen.getByRole('button', { name: 'Save view' }))
    await userEvent.selectOptions(screen.getByLabelText('Filter by status'), '')
    expect(titles()).toHaveLength(4)
    await userEvent.selectOptions(screen.getByLabelText('Saved views'), 'Rejected only')

    expect(titles()).toEqual(['Charlie'])
    expect(JSON.parse(localStorage.getItem('jobpilot.views')!)[0].name).toBe('Rejected only')
  })

  it('links to the CSV and XLSX exports of the run', () => {
    mockApi({})
    renderTable(rows())

    expect(screen.getByRole('link', { name: 'Export CSV' })).toHaveAttribute('href', '/api/runs/7/jobs.csv')
    expect(screen.getByRole('link', { name: 'Export XLSX' })).toHaveAttribute('href', '/api/runs/7/jobs.xlsx')
  })
})
