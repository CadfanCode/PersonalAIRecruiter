import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TabView } from '../api/apply'
import { mockApi } from '../test/mockApi'
import type { Application } from '../api/applications'
import { FinalizePage } from './FinalizePage'

const tab = (over: Partial<TabView> = {}): TabView => ({
  jobId: 1, title: 'Software Developer', employer: 'Ravion Nordic AB', url: 'https://ponty.example/apply', status: 'READY_FOR_USER',
  message: 'Filled 4 field(s), 1 left for you. Check the page and submit it yourself.',
  done: [{ fieldId: 'jp-1', label: 'Namn', action: 'fill', detail: 'Cai Birch' }, { fieldId: 'jp-2', label: 'CV', action: 'upload', detail: 'CV_Cai_Birch_Ravion.pdf' }],
  skipped: [{ fieldId: 'jp-5', label: 'Jag samtycker', reason: 'left for you: consent, terms and privacy choices' }],
  questions: [], passes: 2, ...over,
})

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter><FinalizePage /></MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('finalize applications', () => {
  it('says that nothing is ever submitted and shows an empty state', async () => {
    mockApi({ 'GET /api/applications': [], 'GET /api/apply/status': [] })
    renderPage()

    expect(await screen.findByText(/No applications yet/)).toBeInTheDocument()
    expect(screen.getByText(/never submits, never types passwords or handles BankID, and never ticks consent/)).toBeInTheDocument()
  })

  it('shows one card per tab with the status, counts and what was left', async () => {
    mockApi({
      'GET /api/applications': [],
      'GET /api/apply/status': [
        tab(),
        tab({ jobId: 2, title: 'Junior Frontend', employer: 'Arbetsförmedlingen', status: 'WAITING_FOR_SIGN_IN', message: 'Waiting for you to sign in.', done: [], skipped: [] }),
      ],
    })
    renderPage()

    const ready = await screen.findByRole('region', { name: 'Application: Software Developer' })
    expect(within(ready).getByText('Ready for you to submit')).toBeInTheDocument()
    expect(within(ready).getByText('2 filled, 1 skipped')).toBeInTheDocument()
    expect(within(ready).getByText(/Left "Jag samtycker": left for you/)).toBeInTheDocument()
    expect(within(ready).getByText('Filled "CV": CV_Cai_Birch_Ravion.pdf')).toBeInTheDocument()
    const waiting = screen.getByRole('region', { name: 'Application: Junior Frontend' })
    expect(within(waiting).getByText('Waiting for sign-in')).toBeInTheDocument()
    expect(within(waiting).getByText('Waiting for you to sign in.')).toBeInTheDocument()
  })

  it('fills the current page and focuses or closes the tab on request', async () => {
    const calls = mockApi({
      'GET /api/applications': [],
      'GET /api/apply/status': [tab()],
      'POST /api/apply/1/fill': tab(), 'POST /api/apply/1/focus': null, 'POST /api/apply/1/close': null,
    })
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Fill this page' }))
    await userEvent.click(screen.getByRole('button', { name: 'Focus tab' }))
    await userEvent.click(screen.getByRole('button', { name: 'Close tab' }))

    await vi.waitFor(() => expect(calls.filter((c) => c.method === 'POST')).toHaveLength(3))
    expect(calls.filter((c) => c.method === 'POST').map((c) => c.url)).toEqual(['/api/apply/1/fill', '/api/apply/1/focus', '/api/apply/1/close'])
  })

  it('collects answers to the questions, saves them and fills again', async () => {
    const calls = mockApi({
      'GET /api/applications': [],
      'GET /api/apply/status': [tab({ status: 'NEEDS_ANSWERS', message: 'Filled 4 fields. 2 question(s) need your answer.',
        questions: [{ fieldId: 'jp-5', question: 'Har du erfarenhet av Angular?' }, { fieldId: 'jp-6', question: 'Varför vill du jobba hos oss?' }] })],
      'POST /api/apply/1/answers': tab(), 'POST /api/apply/1/fill': tab(),
    })
    renderPage()

    const save = await screen.findByRole('button', { name: 'Save answers and fill' })
    expect(save).toBeDisabled()
    await userEvent.type(screen.getByLabelText('Answer: Har du erfarenhet av Angular?'), 'Nej, men jag lär mig gärna')
    await userEvent.click(save)

    await vi.waitFor(() => expect(calls.some((c) => c.url === '/api/apply/1/fill')).toBe(true))
    const post = calls.find((c) => c.url === '/api/apply/1/answers')!
    expect(post.body).toEqual({ answers: [{ fieldId: 'jp-5', answer: 'Nej, men jag lär mig gärna' }] })
    expect(calls.map((c) => c.url).filter((u) => u.startsWith('/api/apply/1/')))
      .toEqual(['/api/apply/1/answers', '/api/apply/1/fill'])
  })

  it('offers Restart and refill only for an expired session', async () => {
    const calls = mockApi({
      'GET /api/applications': [],
      'GET /api/apply/status': [tab({ status: 'SESSION_EXPIRED', message: 'The session expired. Use Restart and refill.', done: [], skipped: [] }),
        tab({ jobId: 2, title: 'Other' })],
      'POST /api/apply/1/fill?restart=true': tab(),
    })
    renderPage()

    const expired = await screen.findByRole('region', { name: 'Application: Software Developer' })
    await userEvent.click(within(expired).getByRole('button', { name: 'Restart and refill' }))
    expect(screen.getAllByRole('button', { name: 'Restart and refill' })).toHaveLength(1)
    await vi.waitFor(() => expect(calls.some((c) => c.url === '/api/apply/1/fill?restart=true')).toBe(true))
  })

  it('has no tab buttons for an email-only ad and disables Fill while working', async () => {
    mockApi({
      'GET /api/applications': [],
      'GET /api/apply/status': [
        tab({ jobId: 1, title: 'Email ad', status: 'EMAIL_ONLY', message: 'This ad is applied for by email, so there is nothing to open.', done: [], skipped: [] }),
        tab({ jobId: 2, title: 'Busy ad', status: 'FILLING', message: 'Working out what to fill', done: [], skipped: [] }),
      ],
    })
    renderPage()

    const email = await screen.findByRole('region', { name: 'Application: Email ad' })
    // nothing to fill or focus, but the user can still say they sent it (by email)
    expect(within(email).queryByRole('button', { name: /Fill|Focus|Close|Restart/ })).not.toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Application: Busy ad' })).getByRole('button', { name: 'Fill this page' })).toBeDisabled()
  })

  it('shows a problem card with its message', async () => {
    mockApi({ 'GET /api/applications': [], 'GET /api/apply/status': [tab({ status: 'ERROR', message: 'Claude CLI exited with code 1', done: [], skipped: [] })] })
    renderPage()

    expect(await screen.findByText('Problem')).toBeInTheDocument()
    expect(screen.getByText('Claude CLI exited with code 1')).toBeInTheDocument()
  })

  const app = (over: Partial<Application> = {}): Application => ({
    jobId: 1, runId: 7, title: 'Software Developer', employer: 'Ravion Nordic AB', municipality: 'Stockholm', url: null, applyUrl: null,
    searchMode: 'TARGETED', roleType: null, score: 82, jobStatus: 'DOCS_READY', selectedAt: '2026-10-27T10:00:00Z', submittedAt: null,
    cv: { id: 10, version: 2, approved: true }, letter: { id: 11, version: 1, approved: false },
    generation: { runId: 9, status: 'DONE', step: 'DONE', progress: 100, message: 'Documents ready for review' }, submittedDocs: null, ...over,
  })

  it('shows each application with its documents, mode and a summary of where everything stands', async () => {
    mockApi({
      'GET /api/applications': [
        app(),
        app({ jobId: 2, title: 'Teacher', searchMode: 'DISCOVER', roleType: 'Lärare', score: null, cv: null, letter: null,
          generation: { runId: 12, status: 'FAILED', step: 'CV', progress: 30, message: 'the model failed' } }),
      ],
      'GET /api/apply/status': [tab(), tab({ jobId: 2, title: 'Teacher', status: 'ERROR', message: 'Claude CLI exited with code 1', done: [], skipped: [] })],
    })
    renderPage()

    const first = await screen.findByRole('region', { name: 'Application: Software Developer' })
    expect(within(first).getByText('Targeted search · score 82')).toBeInTheDocument()
    expect(within(first).getByRole('link', { name: 'CV v2 · approved' })).toHaveAttribute('href', '/documents/job/1')
    expect(within(first).getByRole('link', { name: 'Cover letter v1 · to review' })).toHaveAttribute('href', '/documents/job/1?tab=letter')
    expect(within(first).getByText(/Only approved documents are uploaded/)).toBeInTheDocument()
    const second = screen.getByRole('region', { name: 'Application: Teacher' })
    expect(within(second).getByText('Discover roles · Lärare')).toBeInTheDocument()
    expect(within(second).getByText(/Generation failed: the model failed/)).toBeInTheDocument()
    expect(within(second).getByRole('link', { name: 'Try again in Documents' })).toHaveAttribute('href', '/documents')
    expect(screen.getByRole('status')).toHaveTextContent('1 ready · 1 failed')
  })

  it('only records a submission after the user confirms it, and then offers the aktivitetsrapport', async () => {
    const calls = mockApi({
      'GET /api/applications': [app()],
      'GET /api/apply/status': [tab()],
      'POST /api/applications/1/submitted': app({ submittedAt: '2026-10-27T12:00:00Z', jobStatus: 'APPLIED' }),
    })
    renderPage()

    const card = await screen.findByRole('region', { name: 'Application: Software Developer' })
    await userEvent.click(within(card).getByRole('button', { name: 'I submitted it' }))
    expect(calls.some((c) => c.url === '/api/applications/1/submitted')).toBe(false)
    expect(within(card).getByText(/JobPilot cannot see this/)).toBeInTheDocument()
    await userEvent.click(within(card).getByRole('button', { name: 'Not yet' }))
    expect(calls.some((c) => c.url === '/api/applications/1/submitted')).toBe(false)

    await userEvent.click(within(card).getByRole('button', { name: 'I submitted it' }))
    await userEvent.click(within(card).getByRole('button', { name: 'Yes, I submitted it' }))
    await vi.waitFor(() => expect(calls.some((c) => c.url === '/api/applications/1/submitted' && c.method === 'POST')).toBe(true))
  })

  it('shows a submitted application with a link to log it and an undo', async () => {
    mockApi({
      'GET /api/applications': [app({ submittedAt: '2026-10-27T12:00:00Z', jobStatus: 'APPLIED' })],
      'GET /api/apply/status': [],
    })
    renderPage()

    const card = await screen.findByRole('region', { name: 'Application: Software Developer' })
    expect(within(card).getByText('Submitted')).toBeInTheDocument()
    expect(within(card).getByRole('link', { name: 'Log in the aktivitetsrapport' })).toHaveAttribute('href', '/aktivitet?jobs=1')
    expect(within(card).getByRole('button', { name: 'Undo' })).toBeEnabled()
    expect(within(card).queryByRole('button', { name: 'I submitted it' })).not.toBeInTheDocument()
  })

  it('fills all applications that have no open tab, in one batch', async () => {
    const calls = mockApi({
      'GET /api/applications': [app({ jobId: 1 }), app({ jobId: 2, title: 'Second' }), app({ jobId: 3, title: 'Third' })],
      'GET /api/apply/status': [tab({ jobId: 3, title: 'Third' })],
      'POST /api/apply/batch': [],
    })
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Fill all (2)' }))
    await vi.waitFor(() => expect(calls.some((c) => c.url === '/api/apply/batch')).toBe(true))
    expect(calls.find((c) => c.url === '/api/apply/batch')!.body).toEqual({ jobIds: [1, 2] })
  })

  it('shows a queued application and a closed tab that can be restarted', async () => {
    mockApi({
      'GET /api/applications': [app({ jobId: 1 }), app({ jobId: 2, title: 'Second' })],
      'GET /api/apply/status': [
        tab({ jobId: 1, status: 'QUEUED', message: 'Waiting for a free browser tab.', done: [], skipped: [] }),
        tab({ jobId: 2, title: 'Second', status: 'CLOSED', message: 'The tab was closed.', done: [], skipped: [] }),
      ],
    })
    renderPage()

    const queued = await screen.findByRole('region', { name: 'Application: Software Developer' })
    expect(within(queued).getByText('Waiting for a free tab')).toBeInTheDocument()
    expect(within(queued).queryByRole('button', { name: 'Focus tab' })).not.toBeInTheDocument()
    const closed = screen.getByRole('region', { name: 'Application: Second' })
    expect(within(closed).getByRole('button', { name: 'Restart and refill' })).toBeEnabled()
  })

  it('shows the message from the server when Focus tab fails instead of a bare error', async () => {
    mockApi({
      'GET /api/applications': [],
      'GET /api/apply/status': [tab()],
      'POST /api/apply/1/focus': new Response(JSON.stringify({ error: 'That browser tab is closed. Use Restart and refill to open it again.' }), { status: 409 }),
    })
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Focus tab' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('That browser tab is closed. Use Restart and refill')
  })

  it('does not fill applications that have no documents yet, and says what is happening to them', async () => {
    const calls = mockApi({
      'GET /api/applications': [
        app({ jobId: 1 }),
        app({ jobId: 2, title: 'Writing', cv: null, letter: null, generation: { runId: 5, status: 'RUNNING', step: 'CV', progress: 40, message: 'Writing the CV' } }),
        app({ jobId: 3, title: 'Nothing yet', cv: null, letter: null, generation: null }),
      ],
      'GET /api/apply/status': [],
      'POST /api/apply/batch': [],
    })
    renderPage()

    const writing = await screen.findByRole('region', { name: 'Application: Writing' })
    expect(within(writing).getByText(/Writing documents: Writing the CV \(40%\)/)).toBeInTheDocument()
    expect(within(writing).getByRole('button', { name: 'Open and fill' })).toBeDisabled()
    const empty = screen.getByRole('region', { name: 'Application: Nothing yet' })
    expect(within(empty).getByRole('link', { name: 'Create the documents first' })).toBeInTheDocument()
    expect(within(empty).getByRole('button', { name: 'Open and fill' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Fill all (1)' }))
    await vi.waitFor(() => expect(calls.some((c) => c.url === '/api/apply/batch')).toBe(true))
    expect(calls.find((c) => c.url === '/api/apply/batch')!.body).toEqual({ jobIds: [1] })
  })

  it('shows the documents that were used when an application was submitted', async () => {
    mockApi({
      'GET /api/applications': [app({ submittedAt: '2026-10-27T12:00:00Z', jobStatus: 'APPLIED', cv: null, letter: null,
        submittedDocs: { cv: { file: 'CV_Cai_Birch_Ravion.pdf', version: 2 }, letter: { file: 'Brev_Ravion.pdf', version: 1 } } })],
      'GET /api/apply/status': [],
    })
    renderPage()

    expect(await screen.findByText('Documents used: CV v2 (CV_Cai_Birch_Ravion.pdf), cover letter v1 (Brev_Ravion.pdf)')).toBeInTheDocument()
  })

  it('removes an application from the list on request, but not while it is filling or once submitted', async () => {
    const calls = mockApi({
      'GET /api/applications': [app({ jobId: 1 }), app({ jobId: 2, title: 'Sent', submittedAt: '2026-10-27T12:00:00Z', jobStatus: 'APPLIED' })],
      'GET /api/apply/status': [],
      'DELETE /api/applications/1': null,
    })
    renderPage()

    const card = await screen.findByRole('region', { name: 'Application: Software Developer' })
    expect(within(screen.getByRole('region', { name: 'Application: Sent' })).queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument()
    await userEvent.click(within(card).getByRole('button', { name: 'Remove' }))
    await vi.waitFor(() => expect(calls.some((c) => c.method === 'DELETE' && c.url === '/api/applications/1')).toBe(true))
  })
})
