import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { application, tab } from '../test/fixtures2'
import { mockApi } from '../test/mockApi'
import { SubmitPage } from './SubmitPage'

const approved = { id: 1, version: 1, approved: true }
const ready = (over = {}) => application({ cv: approved, letter: { ...approved, id: 2 }, readyToApply: true, blockers: [], ...over })

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/submit']}>
        <Routes>
          <Route path="/submit" element={<SubmitPage />} />
          <Route path="/apply" element={<p>Apply page</p>} />
          <Route path="/submitted" element={<p>Submitted page</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('submit stage', () => {
  it('lists only what is filled in and waiting for the user, and states that the user always submits', async () => {
    mockApi({
      'GET /api/applications': [ready({ jobId: 1, title: 'Filled in' }), ready({ jobId: 2, title: 'Still filling' })],
      'GET /api/apply/status': [
        tab({ jobId: 1, title: 'Filled in', status: 'READY_FOR_USER', phase: 'READY_FOR_SUBMISSION', message: 'Filled in, ready for you' }),
        tab({ jobId: 2, title: 'Still filling', status: 'FILLING', phase: 'FILLING' }),
      ],
    })
    renderPage()

    expect(await screen.findByRole('region', { name: 'Ready to submit: Filled in' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: /Still filling/ })).not.toBeInTheDocument()
    expect(screen.getByText(/You always send the application yourself/)).toBeInTheDocument()
  })

  it('brings the tab forward, and records the application only after the user confirms they sent it', async () => {
    const calls = mockApi({
      'GET /api/applications': [ready()],
      'GET /api/apply/status': [tab({ status: 'READY_FOR_USER', phase: 'READY_FOR_SUBMISSION' })],
      'POST /api/apply/1/focus': null,
      'POST /api/applications/1/submitted': ready({ submittedAt: '2026-10-28T10:00:00Z' }),
    })
    renderPage()

    const card = await screen.findByRole('region', { name: /Ready to submit/ })
    await userEvent.click(within(card).getByRole('button', { name: 'Open the application' }))
    await vi.waitFor(() => expect(calls.some((c) => c.url === '/api/apply/1/focus')).toBe(true))
    expect(calls.some((c) => c.url === '/api/applications/1/submitted')).toBe(false) // opening is not submitting

    await userEvent.click(within(card).getByRole('button', { name: 'I submitted it' }))
    const confirm = within(card).getByRole('group', { name: 'Confirm submission' })
    expect(confirm).toHaveTextContent('JobPilot cannot see it, so it only records what you tell it')
    expect(confirm).toHaveTextContent('The exact CV and cover letter are saved with it')
    expect(calls.some((c) => c.url === '/api/applications/1/submitted')).toBe(false) // still nothing recorded
    await userEvent.click(within(confirm).getByRole('button', { name: 'Yes, I submitted it' }))

    await vi.waitFor(() => expect(calls.some((c) => c.method === 'POST' && c.url === '/api/applications/1/submitted')).toBe(true))
  })

  it('can be backed out of without recording anything', async () => {
    const calls = mockApi({ 'GET /api/applications': [ready()], 'GET /api/apply/status': [tab({ status: 'READY_FOR_USER', phase: 'READY_FOR_SUBMISSION' })] })
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'I submitted it' }))
    await userEvent.click(screen.getByRole('button', { name: 'Not yet' }))

    expect(screen.queryByRole('group', { name: 'Confirm submission' })).not.toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
  })

  it('treats an ad JobPilot cannot open (LinkedIn) as submitted by hand, with the PDFs to use', async () => {
    mockApi({
      'GET /api/applications': [ready({ applyUrl: 'https://www.linkedin.com/jobs/view/1' })],
      'GET /api/apply/status': [tab({ status: 'APPLY_MANUALLY', phase: 'NEEDS_ATTENTION', url: null, message: 'Apply on the site yourself.' })],
    })
    renderPage()

    const card = await screen.findByRole('region', { name: /Ready to submit/ })
    expect(within(card).getByRole('link', { name: 'CV (PDF)' })).toHaveAttribute('href', '/api/documents/1/pdf')
    expect(within(card).getByRole('link', { name: 'Cover letter (PDF)' })).toHaveAttribute('href', '/api/documents/2/pdf')
    expect(within(card).getByRole('link', { name: 'Open the ad' })).toHaveAttribute('href', 'https://www.linkedin.com/jobs/view/1')
    expect(within(card).queryByRole('button', { name: 'Open the application' })).not.toBeInTheDocument() // there is no tab to bring forward
  })

  it('says nothing is ready yet and points at Apply', async () => {
    mockApi({ 'GET /api/applications': [ready()], 'GET /api/apply/status': [tab({ status: 'FILLING', phase: 'FILLING' })] })
    renderPage()

    expect(await screen.findByText(/Nothing is ready to submit yet/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Apply' })).toHaveAttribute('href', '/apply')
  })
})
