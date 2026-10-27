import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TabView } from '../api/apply'
import { mockApi } from '../test/mockApi'
import { ApplyMonitorPage } from './ApplyMonitorPage'

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
      <MemoryRouter><ApplyMonitorPage /></MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('apply monitor', () => {
  it('says that nothing is ever submitted and shows an empty state', async () => {
    mockApi({ 'GET /api/apply/status': [] })
    renderPage()

    expect(await screen.findByText(/No applications open/)).toBeInTheDocument()
    expect(screen.getByText(/never submits, never types passwords or handles BankID, and never ticks consent/)).toBeInTheDocument()
  })

  it('shows one card per tab with the status, counts and what was left', async () => {
    mockApi({
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
      'GET /api/apply/status': [
        tab({ jobId: 1, title: 'Email ad', status: 'EMAIL_ONLY', message: 'This ad is applied for by email, so there is nothing to open.', done: [], skipped: [] }),
        tab({ jobId: 2, title: 'Busy ad', status: 'FILLING', message: 'Working out what to fill', done: [], skipped: [] }),
      ],
    })
    renderPage()

    const email = await screen.findByRole('region', { name: 'Application: Email ad' })
    expect(within(email).queryByRole('button')).not.toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Application: Busy ad' })).getByRole('button', { name: 'Fill this page' })).toBeDisabled()
  })

  it('shows a problem card with its message', async () => {
    mockApi({ 'GET /api/apply/status': [tab({ status: 'ERROR', message: 'Claude CLI exited with code 1', done: [], skipped: [] })] })
    renderPage()

    expect(await screen.findByText('Problem')).toBeInTheDocument()
    expect(screen.getByText('Claude CLI exited with code 1')).toBeInTheDocument()
  })
})
