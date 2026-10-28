import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { application } from '../test/fixtures2'
import { mockApi } from '../test/mockApi'
import { DocumentsIndexPage } from './DocumentsIndexPage'

const overview = (jobId: number, title: string) => ({
  jobId, title, employer: 'Other AB', language: 'sv', cv: true, letter: true, cvApproved: true, letterApproved: false, updatedAt: '2026-10-20T10:00:00Z',
})

const approved = { id: 1, version: 1, approved: true }
const ready = (over = {}) => application({ cv: approved, letter: { ...approved, id: 2 }, readyToApply: true, blockers: [], ...over })

function ApplyPageStub() {
  const state = useLocation().state as { skipped?: { title: string }[] } | null
  return <p>Apply page, left out: {(state?.skipped ?? []).map((s) => s.title).join(', ') || 'none'}</p>
}

function renderPage(path = '/documents?new=1') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/documents" element={<DocumentsIndexPage />} />
          <Route path="/apply" element={<ApplyPageStub />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('documents overview for the selected jobs', () => {
  it('shows a green tick per approved document and says which applications go on and why the others do not', async () => {
    mockApi({
      'GET /api/documents': [],
      'GET /api/applications': [
        ready({ jobId: 1, title: 'Nurse' }),
        application({ jobId: 2, title: 'Teacher', cv: approved, letter: { id: 4, version: 1, approved: false }, blockers: ['The cover letter is not approved'] }),
      ],
    })
    renderPage()

    const nurse = (await screen.findByText('Nurse')).closest('li') as HTMLElement
    expect(within(nurse).getByRole('img', { name: 'CV: approved' })).toBeInTheDocument()
    expect(within(nurse).getByRole('img', { name: 'Cover letter: approved' })).toBeInTheDocument()
    expect(within(nurse).getByText('Goes to Apply')).toBeInTheDocument()
    const teacher = screen.getByText('Teacher').closest('li') as HTMLElement
    expect(within(teacher).getByRole('img', { name: 'CV: approved' })).toBeInTheDocument()
    expect(within(teacher).getByRole('img', { name: 'Cover letter: not approved' })).toBeInTheDocument()
    expect(within(teacher).getByText('Left out for now')).toBeInTheDocument()
    expect(within(teacher).getByText('The cover letter is not approved')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('1 of 2 applications are ready for the next step')
    expect(screen.getByRole('button', { name: 'Apply (1)' })).toBeEnabled()
  })

  it('approves everything that can be approved and names the documents that could not be, with the reason', async () => {
    const calls = mockApi({
      'GET /api/documents': [],
      'GET /api/applications': [application({ jobId: 1, title: 'Nurse' }), application({ jobId: 2, title: 'Teacher' })],
      'POST /api/applications/approve-all': [
        { jobId: 1, title: 'Nurse', kind: 'cv', approved: true, alreadyApproved: false, reason: null },
        { jobId: 1, title: 'Nurse', kind: 'letter', approved: true, alreadyApproved: false, reason: null },
        { jobId: 2, title: 'Teacher', kind: 'cv', approved: true, alreadyApproved: false, reason: null },
        { jobId: 2, title: 'Teacher', kind: 'letter', approved: false, alreadyApproved: false, reason: 'Do not use dashes (2 more)' },
      ],
    })
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Approve all' }))

    expect(await screen.findByText('Approved 3 documents.')).toBeInTheDocument()
    expect(screen.getByText(/Could not approve the cover letter for/)).toHaveTextContent('Teacher: Do not use dashes (2 more)')
    const refused = screen.getByText(/Could not approve the cover letter for/)
    expect(within(refused).getByRole('link', { name: 'Teacher' })).toHaveAttribute('href', '/documents/job/2?tab=letter')
    expect(calls.some((c) => c.method === 'POST' && c.url === '/api/applications/approve-all')).toBe(true)
  })

  it('takes only the applications with both documents approved to Apply and tells Apply which ones were left out', async () => {
    const calls = mockApi({
      'GET /api/documents': [],
      'GET /api/applications': [ready({ jobId: 1, title: 'Nurse' }), application({ jobId: 2, title: 'Teacher', blockers: ['The CV is not approved'] })],
      'POST /api/applications/apply': { started: [{ jobId: 1 }], skipped: [{ jobId: 2, title: 'Teacher', reason: 'The CV is not approved' }] },
    })
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Apply (1)' }))

    expect(await screen.findByText('Apply page, left out: Teacher')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST' && c.url === '/api/applications/apply')).toBe(true)
  })

  it('cannot go on to Apply before something is approved', async () => {
    mockApi({ 'GET /api/documents': [], 'GET /api/applications': [application()] })
    renderPage()

    expect(await screen.findByRole('button', { name: 'Apply (0)' })).toBeDisabled()
    expect(screen.getByText(/Approve the CV and the cover letter of at least one job/)).toBeInTheDocument()
  })

  it('shows progress per job while writing, and a failed job with a retry that does not block the others', async () => {
    const calls = mockApi({
      'GET /api/documents': [],
      'GET /api/applications': [
        application({ jobId: 1, cv: null, letter: null, generation: { runId: 9, status: 'RUNNING', step: 'WRITE', progress: 40, message: 'Writing the CV and the cover letter' } }),
        application({ jobId: 2, title: 'Teacher', cv: null, letter: null, generation: { runId: 10, status: 'FAILED', step: 'WRITE', progress: 30, message: 'the model failed' } }),
        ready({ jobId: 3, title: 'Ready one' }),
      ],
      'POST /api/applications/documents': { items: [] },
    })
    renderPage()

    expect(await screen.findByRole('progressbar', { name: 'Progress: Software Developer' })).toHaveAttribute('aria-valuenow', '40')
    expect(screen.getAllByText('Writing the CV and the cover letter').length).toBeGreaterThan(0)
    expect(screen.getByText('Failed: the model failed')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: 'Writing your tailored documents progress' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await vi.waitFor(() => expect(calls.some((c) => c.url === '/api/applications/documents')).toBe(true))
    expect(calls.find((c) => c.url === '/api/applications/documents')!.body).toEqual({ jobIds: [2], runId: 7 })
  })

  it('lists unrelated documents separately, and hides finished applications', async () => {
    mockApi({
      'GET /api/documents': [overview(1, 'Software Developer'), overview(5, 'Older job')],
      'GET /api/applications': [application({ jobId: 1 }), application({ jobId: 9, title: 'Done and sent', submittedAt: '2026-10-27T12:00:00Z' })],
    })
    renderPage()

    const other = (await screen.findByText('Other documents (1)')).closest('details') as HTMLElement
    expect(within(other).getByText('Older job')).toBeInTheDocument()
    expect(within(other).queryByText('Software Developer')).not.toBeInTheDocument()
    expect(screen.queryByText('Done and sent')).not.toBeInTheDocument()
  })

  it('offers to create documents for a selected job that has none', async () => {
    const calls = mockApi({
      'GET /api/documents': [],
      'GET /api/applications': [application({ jobId: 2, title: 'Selected only', cv: null, letter: null, generation: null })],
      'POST /api/applications/documents': { items: [] },
    })
    renderPage('/documents')

    expect(await screen.findByText('Selected only')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Create documents' }))
    await vi.waitFor(() => expect(calls.some((c) => c.url === '/api/applications/documents')).toBe(true))
    expect(calls.find((c) => c.url === '/api/applications/documents')!.body).toEqual({ jobIds: [2], runId: 7 })
  })
})
