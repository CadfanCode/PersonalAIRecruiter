import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Application } from '../api/applications'
import { mockApi } from '../test/mockApi'
import { DocumentsIndexPage } from './DocumentsIndexPage'

const app = (over: Partial<Application> = {}): Application => ({
  jobId: 1, runId: 7, title: 'Software Developer', employer: 'Ravion AB', municipality: 'Stockholm', url: null, applyUrl: null,
  searchMode: 'TARGETED', roleType: null, score: 80, jobStatus: 'DOCS_READY', selectedAt: '2026-10-27T10:00:00Z', submittedAt: null,
  cv: { id: 1, version: 1, approved: false }, letter: { id: 2, version: 1, approved: false },
  generation: { runId: 9, status: 'DONE', step: 'DONE', progress: 100, message: 'Documents ready for review' }, submittedDocs: null, ...over,
})

const overview = (jobId: number, title: string) => ({
  jobId, title, employer: 'Other AB', language: 'sv', cv: true, letter: true, cvApproved: true, letterApproved: false, updatedAt: '2026-10-20T10:00:00Z',
})

function renderPage(path = '/documents?new=1') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/documents" element={<DocumentsIndexPage />} />
          <Route path="/finalize" element={<p>Finalize page</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('documents overview for the selected jobs', () => {
  it('shows progress per job while writing and a failed job with a retry that does not block the others', async () => {
    const calls = mockApi({
      'GET /api/documents': [],
      'GET /api/applications': [
        app({ jobId: 1, cv: null, letter: null, generation: { runId: 9, status: 'RUNNING', step: 'CV', progress: 40, message: 'Writing the CV' } }),
        app({ jobId: 2, title: 'Teacher', cv: null, letter: null, generation: { runId: 10, status: 'FAILED', step: 'CV', progress: 30, message: 'the model failed' } }),
        app({ jobId: 3, title: 'Ready one' }),
      ],
      'POST /api/applications/documents': { items: [] },
    })
    renderPage()

    expect(await screen.findByRole('status')).toHaveTextContent('Writing documents: 1 of 3 still in progress')
    expect(screen.getByRole('progressbar', { name: 'Progress: Software Developer' })).toHaveAttribute('aria-valuenow', '40')
    expect(screen.getByText('Writing the CV')).toBeInTheDocument()
    expect(screen.getByText('Failed: the model failed')).toBeInTheDocument()
    expect(screen.getByText('CV to review · letter to review')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await vi.waitFor(() => expect(calls.some((c) => c.url === '/api/applications/documents')).toBe(true))
    expect(calls.find((c) => c.url === '/api/applications/documents')!.body).toEqual({ jobIds: [2], runId: 7 })
  })

  it('continues to Finalize Applications once documents exist, and lists unrelated documents separately', async () => {
    mockApi({
      'GET /api/documents': [overview(1, 'Software Developer'), overview(5, 'Older job')],
      'GET /api/applications': [app()],
    })
    renderPage()

    expect(await screen.findByRole('status')).toHaveTextContent('Done: 1 of 1 selected jobs have a CV and a cover letter')
    const other = screen.getByText('Other documents').nextElementSibling as HTMLElement
    expect(within(other).getByText('Older job')).toBeInTheDocument()
    expect(within(other).queryByText('Software Developer')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Continue to Finalize Applications' }))
    expect(await screen.findByText('Finalize page')).toBeInTheDocument()
  })

  it('disables the continue button until at least one set of documents exists', async () => {
    mockApi({
      'GET /api/documents': [],
      'GET /api/applications': [app({ cv: null, letter: null, generation: { runId: 9, status: 'QUEUED', step: 'SELECT', progress: 0, message: 'Queued' } })],
    })
    renderPage()
    expect(await screen.findByRole('button', { name: 'Continue to Finalize Applications' })).toBeDisabled()
  })

  it('hides finished applications and offers to create documents for a selected job that has none', async () => {
    const calls = mockApi({
      'GET /api/documents': [],
      'GET /api/applications': [
        app({ jobId: 1, title: 'Done and sent', submittedAt: '2026-10-27T12:00:00Z' }),
        app({ jobId: 2, title: 'Selected only', cv: null, letter: null, generation: null }),
      ],
      'POST /api/applications/documents': { items: [] },
    })
    renderPage('/documents')

    expect(await screen.findByText('Selected only')).toBeInTheDocument()
    expect(screen.queryByText('Done and sent')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('0 of 1 selected jobs')
    await userEvent.click(screen.getByRole('button', { name: 'Create documents' }))
    await vi.waitFor(() => expect(calls.some((c) => c.url === '/api/applications/documents')).toBe(true))
    expect(calls.find((c) => c.url === '/api/applications/documents')!.body).toEqual({ jobIds: [2], runId: 7 })
  })
})
