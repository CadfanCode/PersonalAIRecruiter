import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { application, tab } from '../test/fixtures2'
import { mockApi } from '../test/mockApi'
import { ApplyPage } from './ApplyPage'

const approved = { id: 1, version: 1, approved: true }
const ready = (over = {}) => application({ cv: approved, letter: { ...approved, id: 2 }, readyToApply: true, blockers: [], ...over })

function renderPage(state?: unknown) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[{ pathname: '/apply', state }]}>
        <Routes>
          <Route path="/apply" element={<ApplyPage />} />
          <Route path="/submit" element={<p>Submit page</p>} />
          <Route path="/documents" element={<p>Documents page</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => localStorage.clear())
afterEach(() => vi.unstubAllGlobals())

describe('apply stage', () => {
  it('summarises the applications by what they are doing and puts the ones that need the user first', async () => {
    mockApi({
      'GET /api/logins': [],
      'GET /api/applications': [ready({ jobId: 1, title: 'Filling one' }), ready({ jobId: 2, title: 'Login one' }), ready({ jobId: 3, title: 'Done one' })],
      'GET /api/apply/status': [
        tab({ jobId: 1, title: 'Filling one', status: 'FILLING', phase: 'FILLING' }),
        tab({ jobId: 2, title: 'Login one', status: 'WAITING_FOR_SIGN_IN', phase: 'NEEDS_LOGIN', signInHost: 'jobs.example.com', message: 'Waiting for you to sign in.' }),
        tab({ jobId: 3, title: 'Done one', status: 'READY_FOR_USER', phase: 'READY_FOR_SUBMISSION', message: 'Ready for you' }),
      ],
    })
    renderPage()

    const summary = await screen.findByRole('status')
    expect(summary).toHaveTextContent('1 filling in')
    expect(summary).toHaveTextContent('1 needs login')
    expect(summary).toHaveTextContent('1 ready for submission')
    const titles = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)
    expect(titles).toEqual(['Login one', 'Filling one', 'Done one'])
  })

  it('asks the user to sign in themselves when a site needs it, and says JobPilot never creates accounts', async () => {
    const calls = mockApi({
      'GET /api/logins': [],
      'GET /api/applications': [ready()],
      'GET /api/apply/status': [tab({ status: 'WAITING_FOR_SIGN_IN', phase: 'NEEDS_LOGIN', signInHost: 'jobs.example.com', message: 'Waiting for you to sign in.' })],
      'POST /api/apply/1/focus': null,
    })
    renderPage()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('needs you to sign in or sign up (jobs.example.com)')
    expect(alert).toHaveTextContent('never creates accounts or handles BankID')
    await userEvent.click(within(alert).getByRole('button', { name: 'Show the tab' }))
    await vi.waitFor(() => expect(calls.some((c) => c.url === '/api/apply/1/focus')).toBe(true))
  })

  it('offers to save the login once the sign-in is over and stores it only through JobPilot\'s own form', async () => {
    localStorage.setItem('jobpilot.loginSeen.1', 'jobs.example.com')
    const calls = mockApi({
      'GET /api/logins': [],
      'GET /api/applications': [ready()],
      'GET /api/apply/status': [tab({ status: 'FILLING', phase: 'FILLING' })],
      'PUT /api/logins': { host: 'jobs.example.com', username: 'cai@example.com', createdAt: '2026-10-28T10:00:00Z', lastUsedAt: null },
    })
    renderPage()

    const prompt = await screen.findByRole('group', { name: 'Save your login for jobs.example.com' })
    expect(prompt).toHaveTextContent('You still press Log in yourself')
    expect(prompt).toHaveTextContent('secure credential store')
    expect(within(prompt).getByLabelText('Password')).toHaveAttribute('type', 'password')
    expect(within(prompt).getByRole('button', { name: 'Save login' })).toBeDisabled()
    await userEvent.type(within(prompt).getByLabelText('Username or email'), 'cai@example.com')
    await userEvent.type(within(prompt).getByLabelText('Password'), 's3cret')
    await userEvent.click(within(prompt).getByRole('button', { name: 'Save login' }))

    await vi.waitFor(() => expect(calls.some((c) => c.method === 'PUT' && c.url === '/api/logins')).toBe(true))
    expect(calls.find((c) => c.method === 'PUT' && c.url === '/api/logins')!.body).toEqual({ host: 'jobs.example.com', username: 'cai@example.com', password: 's3cret' })
    await vi.waitFor(() => expect(screen.queryByRole('group', { name: /Save your login/ })).not.toBeInTheDocument())
  })

  it('remembers "No thanks" for that site', async () => {
    localStorage.setItem('jobpilot.loginSeen.1', 'jobs.example.com')
    mockApi({
      'GET /api/logins': [],
      'GET /api/applications': [ready()],
      'GET /api/apply/status': [tab({ status: 'FILLING', phase: 'FILLING' })],
    })
    renderPage()

    await userEvent.click(within(await screen.findByRole('group', { name: /Save your login/ })).getByRole('button', { name: 'No thanks' }))

    expect(screen.queryByRole('group', { name: /Save your login/ })).not.toBeInTheDocument()
    expect(localStorage.getItem('jobpilot.loginDeclined.jobs.example.com')).toBe('1')
  })

  it('does not ask again for a site whose login is already saved, and says when it was filled in for the user', async () => {
    localStorage.setItem('jobpilot.loginSeen.1', 'jobs.example.com')
    mockApi({
      'GET /api/logins': [{ host: 'jobs.example.com', username: 'cai@example.com', createdAt: '2026-10-20T10:00:00Z', lastUsedAt: null }],
      'GET /api/applications': [ready()],
      'GET /api/apply/status': [tab({ status: 'WAITING_FOR_SIGN_IN', phase: 'NEEDS_LOGIN', signInHost: 'jobs.example.com', loginFilled: true,
        message: 'Your saved login for jobs.example.com is filled in. Press Log in yourself; JobPilot carries on once you are in.' })],
    })
    renderPage()

    expect(await screen.findByText(/Your saved login for jobs.example.com is filled in/)).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: /Save your login/ })).not.toBeInTheDocument()
  })

  it('answers questions the site asked and fills again', async () => {
    const calls = mockApi({
      'GET /api/logins': [],
      'GET /api/applications': [ready()],
      'GET /api/apply/status': [tab({ status: 'NEEDS_ANSWERS', phase: 'NEEDS_ATTENTION', questions: [{ fieldId: 'jp-4', question: 'Har du körkort?' }], message: 'Needs answers' })],
      'POST /api/apply/1/answers': tab(),
      'POST /api/apply/1/fill': tab(),
    })
    renderPage()

    await userEvent.type(await screen.findByLabelText('Answer: Har du körkort?'), 'Ja')
    await userEvent.click(screen.getByRole('button', { name: 'Save answers and fill' }))

    await vi.waitFor(() => expect(calls.some((c) => c.url === '/api/apply/1/fill')).toBe(true))
    expect(calls.find((c) => c.url === '/api/apply/1/answers')!.body).toEqual({ answers: [{ fieldId: 'jp-4', answer: 'Ja' }] })
  })

  it('opens the approved applications that have no tab yet, and says which applications were left out and why', async () => {
    const calls = mockApi({
      'GET /api/logins': [],
      'GET /api/applications': [ready({ jobId: 1, title: 'Approved one' }), application({ jobId: 2, title: 'Not approved', blockers: ['The CV is not approved'] })],
      'GET /api/apply/status': [],
      'POST /api/applications/apply': { started: [{ jobId: 1 }], skipped: [] },
    })
    renderPage({ skipped: [{ jobId: 9, title: 'Left behind', reason: 'The cover letter is not approved' }] })

    expect(await screen.findByText('Left behind: The cover letter is not approved')).toBeInTheDocument()
    await userEvent.click(await screen.findByRole('button', { name: 'Open and fill (1)' }))
    await vi.waitFor(() => expect(calls.some((c) => c.url === '/api/applications/apply')).toBe(true))
    const notReady = screen.getByText('Not ready yet (1)').closest('details') as HTMLElement
    expect(within(notReady).getByText(/The CV is not approved/)).toBeInTheDocument()
  })

  it('shows what the tab controls do and links a finished application on to Submit', async () => {
    const calls = mockApi({
      'GET /api/logins': [],
      'GET /api/applications': [ready()],
      'GET /api/apply/status': [tab({ status: 'READY_FOR_USER', phase: 'READY_FOR_SUBMISSION', message: 'Filled in, ready for you', done: [{ fieldId: 'a', label: 'Namn', action: 'FILL', detail: null }] })],
      'POST /api/apply/1/focus': null,
      'POST /api/apply/1/close': null,
    })
    renderPage()

    expect(await screen.findByRole('link', { name: /Go to Submit/ })).toHaveAttribute('href', '/submit')
    await userEvent.click(screen.getByRole('button', { name: 'Show tab' }))
    await userEvent.click(screen.getByRole('button', { name: 'Close tab' }))
    await vi.waitFor(() => expect(calls.some((c) => c.url === '/api/apply/1/close')).toBe(true))
    expect(calls.some((c) => c.url === '/api/apply/1/focus')).toBe(true)
  })
})
