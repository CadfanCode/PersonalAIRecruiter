import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { SubmittedSummary } from '../api/submitted'
import { countryView } from '../test/fixtures2'
import { mockApi } from '../test/mockApi'
import { SubmittedDetailPage } from './SubmittedDetailPage'
import { SubmittedPage } from './SubmittedPage'

const row = (id: number, over: Partial<SubmittedSummary> = {}): SubmittedSummary => ({
  id, jobId: id, title: `Job ${id}`, employer: 'Comstream AB', municipality: 'Stockholm', url: 'https://platsbanken/1', applyUrl: null, language: 'sv',
  searchMode: 'DISCOVER', roleType: 'Lärare', scoreTotal: 72, submittedAt: '2026-10-27T09:00:00Z', cvVersion: 2, cvFile: 'CV.pdf',
  letterVersion: 1, letterFile: 'Brev.pdf', reportedAt: null, ...over,
})

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/submitted" element={<SubmittedPage />} />
          <Route path="/submitted/:id" element={<SubmittedDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('submitted applications', () => {
  it('lists what was sent with the exact documents as they were sent, and whether it was reported to Arbetsförmedlingen', async () => {
    mockApi({
      'GET /api/submitted': [row(1), row(2, { reportedAt: '2026-10-28T09:00:00Z', searchMode: 'MANUAL', roleType: null })],
      'GET /api/country': countryView,
    })
    renderAt('/submitted')

    const first = (await screen.findByRole('link', { name: 'Job 1' })).closest('li') as HTMLElement
    expect(within(first).getByText('Not reported yet')).toBeInTheDocument()
    expect(within(first).getByText('Submitted 2026-10-27')).toBeInTheDocument()
    expect(within(first).getByRole('link', { name: 'CV v2 (PDF)' })).toHaveAttribute('href', '/api/submitted/1/cv.pdf')
    expect(within(first).getByRole('link', { name: 'Cover letter v1 (PDF)' })).toHaveAttribute('href', '/api/submitted/1/letter.pdf')
    expect(first).toHaveTextContent('Discovery · Lärare · score 72')
    const second = screen.getByRole('link', { name: 'Job 2' }).closest('li') as HTMLElement
    expect(within(second).getByText('Reported 2026-10-28')).toBeInTheDocument()
    expect(screen.getByText(/1 application is not reported to Arbetsförmedlingen yet/)).toBeInTheDocument()
  })

  it('does not talk about the activity report in a country that has none', async () => {
    mockApi({ 'GET /api/submitted': [row(1)], 'GET /api/country': { ...countryView, selected: 'NO', activityReport: false } })
    renderAt('/submitted')

    await screen.findByRole('link', { name: 'Job 1' })
    expect(screen.queryByText(/Not reported yet/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Arbetsförmedlingen yet/)).not.toBeInTheDocument()
  })

  it('has an empty state that points at Submit', async () => {
    mockApi({ 'GET /api/submitted': [], 'GET /api/country': countryView })
    renderAt('/submitted')

    expect(await screen.findByText(/Nothing submitted yet/)).toBeInTheDocument()
  })

  it('shows one application with the CV and cover letter PDFs as sent and the ad as it was', async () => {
    mockApi({
      'GET /api/submitted/1': { summary: row(1), adText: 'Vi söker en sjuksköterska med legitimation.', cvContentJson: '{}', letterContentJson: '{}' },
      'GET /api/country': countryView,
    })
    renderAt('/submitted/1')

    expect(await screen.findByRole('heading', { name: 'Job 1' })).toBeInTheDocument()
    expect(screen.getByTitle('CV as sent')).toHaveAttribute('src', '/api/submitted/1/cv.pdf')
    expect(screen.getByTitle('Cover letter as sent')).toHaveAttribute('src', '/api/submitted/1/letter.pdf')
    expect(screen.getByText('The ad as it was when you applied')).toBeInTheDocument()
    expect(screen.getByText('Vi söker en sjuksköterska med legitimation.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Original ad/ })).toHaveAttribute('href', 'https://platsbanken/1')
  })
})
