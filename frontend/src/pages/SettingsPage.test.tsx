import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ThemeProvider } from '../theme/ThemeProvider'
import { countryView, onboarded } from '../test/fixtures2'
import { mockApi } from '../test/mockApi'
import { SettingsPage } from './SettingsPage'

const rules = [
  { id: 1, ruleKey: 'no_dashes', ruleText: 'Never use an em-dash.', enabled: true },
  { id: 2, ruleKey: 'plain_language', ruleText: 'Write plainly.', enabled: true },
]
const phrases = [
  { id: 1, language: 'sv', phrase: 'brinner för', enabled: true },
  { id: 2, language: 'en', phrase: 'leverage', enabled: false },
]
const usage = { totals: { calls: 29, failures: 0 }, byTask: [{ task: 'scoreJob', calls: 17, tokens: 123456 }] }
const limits = {
  limits: {
    session: { utilization: 0.07, resetsAt: '2026-10-27T14:30:00Z', status: 'allowed' },
    week: { utilization: 0.96, resetsAt: '2026-10-30T18:00:00Z', status: 'allowed_warning' },
    checkedAt: '2026-10-27T12:00:00Z',
  },
  error: null,
}
const connection = (over = {}) => ({
  selected: 'cli', chosen: true, cli: { found: true, path: '/home/u/.local/bin/claude', version: '2.1.312 (Claude Code)', help: null, model: '' },
  secureStore: { available: true, name: 'Secret Service' }, ...over,
})

function setup(extra: Record<string, unknown> = {}) {
  const calls = mockApi({
    'GET /api/claude/connection': connection(),
    'GET /api/claude/usage': usage,
    'GET /api/claude/limits': limits,
    'GET /api/settings/style-rules': rules,
    'GET /api/settings/banned-phrases': phrases,
    'GET /api/settings/performance': { claudeConcurrency: 4, min: 1, max: 8 },
    'GET /api/settings/search': { careerPages: false },
    'GET /api/logins': [],
    'GET /api/country': countryView,
    'GET /api/onboarding': onboarded,
    ...extra,
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <MemoryRouter initialEntries={['/settings']}>
          <Routes>
            <Route path="/settings/:section?" element={<SettingsPage />} />
            <Route path="/welcome" element={<p>Welcome steps</p>} />
          </Routes>
        </MemoryRouter>
      </ThemeProvider>
    </QueryClientProvider>,
  )
  return calls
}

const open = (name: string) => userEvent.click(screen.getByRole('button', { name }))

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
})

describe('settings: Claude connection', () => {
  it('shows which access is used, that the CLI was found, and the subscription usage', async () => {
    setup()

    expect(await screen.findByText(/Found 2.1.312 \(Claude Code\)/)).toBeInTheDocument()
    expect(screen.queryByText(/Google|Gemini|API key/)).not.toBeInTheDocument()
    expect(await screen.findByRole('progressbar', { name: 'Session (5 hours)' })).toHaveAttribute('aria-valuenow', '7')
    expect(screen.getByRole('progressbar', { name: 'Week (7 days)' })).toHaveAttribute('aria-valuenow', '96')
    expect(screen.queryByText(/\$/)).not.toBeInTheDocument()
  })

  it('tests the connection and explains a failure with what to do about it', async () => {
    setup({
      'POST /api/claude/connection/test': {
        ok: false, provider: 'cli', problem: 'NOT_LOGGED_IN', message: 'Claude Code is installed but not signed in.',
        help: 'Open a terminal, type  claude  and sign in with your Claude account, then test again.', durationMs: 4000,
      },
    })

    await userEvent.click(await screen.findByRole('button', { name: 'Test the connection' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('installed but not signed in')
    expect(alert).toHaveTextContent('sign in with your Claude account')
  })

  it('changes the Claude Code model and sends it to the server for the next request', async () => {
    const calls = setup({ 'PUT /api/claude/connection/cli-model': connection({ cli: { found: true, path: '/bin/claude', version: '2.1', help: null, model: 'opus' } }) })

    await userEvent.selectOptions(await screen.findByLabelText('Claude model'), 'opus')

    await vi.waitFor(() => expect(calls.some((c) => c.url === '/api/claude/connection/cli-model')).toBe(true))
    expect(calls.find((c) => c.url === '/api/claude/connection/cli-model')!.body).toEqual({ model: 'opus' })

})
})

describe('settings: search, speed, logins and country', () => {
  it('makes career-page search an opt-in setting and leaves the national job board always on', async () => {
    const calls = setup({ 'PUT /api/settings/search': { careerPages: true } })
    await open('Search')

    expect(await screen.findByText(/national job board of your country is always searched/)).toBeInTheDocument()
    const box = screen.getByRole('checkbox', { name: /Also search employers' own career pages/ })
    expect(box).not.toBeChecked()
    await userEvent.click(box)

    await vi.waitFor(() => expect(calls.some((c) => c.method === 'PUT' && c.url === '/api/settings/search')).toBe(true))
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ careerPages: true })
  })

  it('shows Sweden as the only country and the others as coming soon', async () => {
    setup()
    await open('Country')

    expect(await screen.findByRole('radio', { name: /Sweden/ })).toHaveAttribute('aria-checked', 'true')
    const norway = screen.getByRole('radio', { name: /Norway/ })
    expect(norway).toBeDisabled()
    expect(within(norway).getByText('Coming soon')).toBeInTheDocument()
  })

  it('changes how many Claude calls run at once, and shows usage per task', async () => {
    const calls = setup({ 'PUT /api/settings/performance': { claudeConcurrency: 6, min: 1, max: 8 } })
    await open('Speed and usage')

    const slider = await screen.findByLabelText('Calls at the same time')
    expect(slider).toHaveValue('4')
    fireEvent.change(slider, { target: { value: '6' } })
    fireEvent.pointerUp(slider)

    await vi.waitFor(() => expect(calls.some((c) => c.method === 'PUT' && c.url === '/api/settings/performance')).toBe(true))
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ claudeConcurrency: 6 })
    expect(await screen.findByText('JobPilot has made 29 calls, 0 failed.')).toBeInTheDocument()
    expect(screen.getByText('123,456')).toBeInTheDocument()
  })

  it('lists saved logins by site and username, never a password, and deletes one', async () => {
    const calls = setup({
      'GET /api/logins': [{ host: 'jobs.example.com', username: 'cai@example.com', createdAt: '2026-10-20T10:00:00Z', lastUsedAt: '2026-10-25T10:00:00Z' }],
      'DELETE /api/logins/jobs.example.com': null,
    })
    await open('Saved logins')

    expect(await screen.findByText('jobs.example.com')).toBeInTheDocument()
    expect(screen.getByText('cai@example.com')).toBeInTheDocument()
    expect(screen.queryByText(/password:/i)).not.toBeInTheDocument()
    expect(screen.getByText(/You still press Log in yourself/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Delete the login for jobs.example.com' }))

    await vi.waitFor(() => expect(calls.some((c) => c.method === 'DELETE')).toBe(true))
  })

  it('has no Appearance or Setup section: the theme switch is in the top bar and setup is not repeated', async () => {
    setup()

    await screen.findByRole('button', { name: 'Country' })
    expect(screen.queryByRole('button', { name: 'Appearance' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Setup' })).not.toBeInTheDocument()
  })
})

describe('settings: writing rules', () => {
  it('saves an edited writing rule and can switch it off', async () => {
    const calls = setup({ 'PUT /api/settings/style-rules/1': rules })
    await open('Writing rules')

    const box = await screen.findByLabelText('Rule no_dashes')
    expect(screen.getAllByRole('button', { name: 'Save rule' })[0]).toBeDisabled()
    await userEvent.clear(box)
    await userEvent.type(box, 'No dashes at all.')
    await userEvent.click(screen.getByLabelText('Enable no_dashes'))
    await userEvent.click(screen.getAllByRole('button', { name: 'Save rule' })[0])

    await vi.waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true))
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ ruleText: 'No dashes at all.', enabled: false })
  })

  it('lists banned phrases by language and toggles and deletes them', async () => {
    const calls = setup({ 'PUT /api/settings/banned-phrases/1': null, 'DELETE /api/settings/banned-phrases/2': null })
    await open('Writing rules')

    await userEvent.click(await screen.findByLabelText('Enable phrase brinner för'))
    await userEvent.click(screen.getByLabelText('Delete phrase leverage'))

    await vi.waitFor(() => expect(calls.some((c) => c.method === 'DELETE')).toBe(true))
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ enabled: false })
    expect(screen.getByLabelText('Enable phrase leverage')).not.toBeChecked()
  })

  it('adds a phrase in the chosen language', async () => {
    const calls = setup({ 'POST /api/settings/banned-phrases': { id: 9, language: 'en', phrase: 'game changer', enabled: true } })
    await open('Writing rules')

    await userEvent.selectOptions(await screen.findByLabelText('Phrase language'), 'en')
    await userEvent.type(screen.getByLabelText('Phrase'), 'game changer')
    await userEvent.click(screen.getByRole('button', { name: 'Add phrase' }))

    await vi.waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ language: 'en', phrase: 'game changer' })
    expect(within(screen.getByText('Banned phrases').closest('section')!).getByLabelText('Phrase')).toHaveValue('')
  })
})
