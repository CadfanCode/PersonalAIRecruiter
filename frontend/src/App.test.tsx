import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'
import { countryView, onboarded } from './test/fixtures2'
import { mockApi } from './test/mockApi'

const routes = (onboarding: unknown = onboarded) => ({
  'GET /api/onboarding': onboarding,
  'GET /api/country': countryView,
  'GET /api/runs': [],
  'GET /api/applications': [],
  'GET /api/apply/status': [],
  'GET /api/aktivitet/reminder': { active: false, day: 20, daysLeft: 0, forMonth: '2026-09', message: '' },
  'GET /api/claude/connection': { selected: 'cli', chosen: true, cli: { found: true, path: '/bin/claude', version: '2.1', help: null, model: '' }, secureStore: { available: true, name: 'test store' } },
  'GET /api/claude/limits': { limits: null, error: null },
})

function renderApp(path = '/') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.unstubAllGlobals())

const incomplete = { ...onboarded, complete: false, nextStep: 'CV', cv: { present: false, entries: 0, facts: 0, skills: 0 }, claude: { chosen: false, provider: 'cli', works: false } }
const openApplication = { jobId: 1, title: 'x', submittedAt: null, cv: { id: 1, version: 1, approved: true }, letter: { id: 2, version: 1, approved: true }, readyToApply: true, blockers: [], generation: null, searchMode: 'TARGETED' }

describe('app shell', () => {
  it('shows the five stages across the top with the current one marked, every one of them a link, and the menu with the places you maintain', async () => {
    mockApi(routes())
    renderApp()

    const flow = await screen.findByRole('list', { name: 'Pipeline' })
    expect(within(flow).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['1Mode', '2Search and ranking', '3Documents', '4Apply', '5Submit'])
    expect(within(flow).getByRole('link', { name: /Mode/ })).toHaveAttribute('aria-current', 'step')
    // nothing exists beyond the first stage yet: those stages still open, onto a page that says what to complete first
    expect(within(flow).getByRole('link', { name: /Documents/ })).toHaveAttribute('href', '/not-started/documents')
    expect(within(flow).getByRole('link', { name: /Submit/ })).toHaveAttribute('href', '/not-started/submit')

    const more = screen.getByRole('navigation', { name: 'More' })
    expect(within(more).getAllByRole('link').map((l) => [l.textContent, l.getAttribute('href')])).toEqual([
      ['CV database', '/cv'], ['Country', '/settings/country'], ['Claude', '/settings/connection'],
      ['Submitted applications', '/submitted'], ['Extra features', '/extras'], ['Settings', '/settings'],
    ])
  })

  it('has the light and dark switch in the top bar', async () => {
    mockApi(routes())
    renderApp()

    expect(await screen.findByRole('switch', { name: 'Dark mode' })).toHaveAttribute('aria-checked', 'false')
  })

  it('opens a stage once there is something in it, and navigates with the menu', async () => {
    mockApi({ ...routes(), 'GET /api/applications': [openApplication] })
    renderApp()

    const flow = await screen.findByRole('list', { name: 'Pipeline' })
    await vi.waitFor(() => expect(within(flow).getByRole('link', { name: /Documents/ })).toHaveAttribute('href', '/documents'))
    expect(within(flow).getByRole('link', { name: /Apply/ })).toHaveAttribute('href', '/apply')
    await userEvent.click(within(screen.getByRole('navigation', { name: 'More' })).getByRole('link', { name: 'Settings' }))
    expect(await screen.findByRole('heading', { name: 'Settings' })).toBeInTheDocument()
  })

  it('says a stage has not been started and what to complete first, instead of showing an empty page', async () => {
    mockApi(routes())
    renderApp()

    await userEvent.click(await screen.findByRole('link', { name: /Documents/ }))

    expect(await screen.findByRole('heading', { name: 'Documents has not been started yet' })).toBeInTheDocument()
    expect(screen.getByText(/Please complete/)).toHaveTextContent('Please complete Mode first.')
    expect(screen.getByRole('link', { name: /Documents/ })).toHaveAttribute('aria-current', 'step')
  })

  it('keeps what was typed into the search form when the menu is used and the person comes back', async () => {
    mockApi(routes())
    renderApp()

    await userEvent.type(await screen.findByLabelText('Starting prompt'), 'Nurse in Uppsala')
    await userEvent.click(within(screen.getByRole('navigation', { name: 'More' })).getByRole('link', { name: 'Settings' }))
    expect(await screen.findByRole('heading', { name: 'Settings' })).toBeInTheDocument()
    await userEvent.click(within(screen.getByRole('list', { name: 'Pipeline' })).getByRole('link', { name: /Mode/ }))

    expect(await screen.findByLabelText('Starting prompt')).toHaveValue('Nurse in Uppsala')
  })

  it('goes straight to a stage that has become available since its "not started" page was opened', async () => {
    mockApi({ ...routes(), 'GET /api/applications': [openApplication] })
    renderApp('/not-started/documents')

    expect(await screen.findByRole('heading', { level: 1, name: 'Your documents' })).toBeInTheDocument()
    expect(screen.queryByText(/has not been started yet/)).not.toBeInTheDocument()
  })

  it('sends a first launch to the setup steps, which are the progress bar, and keeps the bottom menu and the theme switch', async () => {
    mockApi(routes(incomplete))
    renderApp('/documents')

    expect(await screen.findByRole('heading', { name: 'Start with your CV' })).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Pipeline' })).not.toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: 'Setup progress' })).getAllByRole('listitem')).toHaveLength(4)
    expect(screen.getByRole('navigation', { name: 'More' })).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Dark mode' })).toBeInTheDocument()
  })

  it('opens the menu pages in the middle of setup without losing the chosen CV files or the step', async () => {
    mockApi(routes(incomplete))
    renderApp('/welcome')

    await userEvent.upload(await screen.findByLabelText('CV files'), new File(['%PDF'], 'cv.pdf', { type: 'application/pdf' }))
    await userEvent.click(within(screen.getByRole('navigation', { name: 'More' })).getByRole('link', { name: 'Settings' }))
    expect(await screen.findByRole('heading', { name: 'Settings' })).toBeInTheDocument()
    await userEvent.click(within(screen.getByRole('list', { name: 'Setup progress' })).getByRole('button', { name: /Your CV/ }))

    expect(await screen.findByText('cv.pdf')).toBeInTheDocument()
  })

  it('does not let setup steps be skipped: a later step says what to complete first', async () => {
    mockApi(routes(incomplete))
    renderApp('/welcome')

    await userEvent.click(await within(await screen.findByRole('list', { name: 'Setup progress' })).findByRole('button', { name: /Country/ }))

    expect(await screen.findByRole('heading', { name: 'Country has not been started yet' })).toBeInTheDocument()
    expect(screen.getByText(/Please complete/)).toHaveTextContent('Please complete Your CV first.')
  })

  it('never shows the setup steps again once setup is done: the address leads to Mode', async () => {
    mockApi(routes())
    renderApp('/welcome')

    expect(await screen.findByRole('heading', { level: 1, name: 'Find your next role' })).toBeInTheDocument()
  })

  it('renders Mode with both modes and examples from several professions', async () => {
    mockApi(routes())
    renderApp()

    expect(await screen.findByRole('heading', { level: 1, name: 'Find your next role' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /Discovery Mode/ })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /Prompt Mode/ })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByText('Nurse positions in Stockholm, last 7 days')).toBeInTheDocument()
    expect(screen.getByText('Accountant in Malmö, part time')).toBeInTheDocument()
    expect(screen.queryByLabelText(/Max age/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Review the search profile first/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Also search career pages/)).not.toBeInTheDocument()
  })
})
