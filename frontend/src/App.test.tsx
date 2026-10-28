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
  'GET /api/claude/connection': { selected: 'cli', chosen: true, cli: { found: true, path: '/bin/claude', version: '2.1', help: null, model: '' }, gemini: { configured: false, keyHint: null, model: 'gemini-flash-latest' }, secureStore: { available: true, name: 'test store' } },
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

describe('app shell', () => {
  it('shows the five stages across the top with the current one marked, and the menu with the places you maintain', async () => {
    mockApi(routes())
    renderApp()

    const flow = await screen.findByRole('navigation', { name: 'Pipeline' })
    const names = within(flow).getAllByRole('listitem').map((li) => li.textContent)
    expect(names).toEqual(['Mode', 'Search and ranking', 'Documents', 'Apply', 'Submit'])
    expect(within(flow).getByText('Mode').closest('[aria-current]')).toHaveAttribute('aria-current', 'step')
    // nothing exists beyond the first stage yet, so the later stages are faded and not links
    expect(within(flow).getByText('Documents').closest('[aria-disabled]')).toHaveAttribute('aria-disabled', 'true')
    expect(within(flow).queryByRole('link', { name: 'Documents' })).not.toBeInTheDocument()

    const more = screen.getByRole('navigation', { name: 'More' })
    expect(within(more).getByRole('link', { name: 'CV database' })).toHaveAttribute('href', '/cv')
    expect(within(more).getByRole('link', { name: 'Submitted applications' })).toHaveAttribute('href', '/submitted')
    expect(within(more).getByRole('link', { name: 'Extra features' })).toHaveAttribute('href', '/extras')
    expect(within(more).getByRole('link', { name: 'Settings' })).toHaveAttribute('href', '/settings')
  })

  it('opens a stage once there is something in it, and navigates with the menu', async () => {
    mockApi({ ...routes(), 'GET /api/applications': [{ jobId: 1, title: 'x', submittedAt: null, cv: { id: 1, version: 1, approved: true }, letter: { id: 2, version: 1, approved: true }, readyToApply: true, blockers: [], generation: null, searchMode: 'TARGETED' }] })
    renderApp()

    const flow = await screen.findByRole('navigation', { name: 'Pipeline' })
    expect(await within(flow).findByRole('link', { name: 'Documents' })).toHaveAttribute('href', '/documents')
    expect(within(flow).getByRole('link', { name: 'Apply' })).toHaveAttribute('href', '/apply')
    await userEvent.click(within(screen.getByRole('navigation', { name: 'More' })).getByRole('link', { name: 'Settings' }))
    expect(await screen.findByRole('heading', { name: 'Settings' })).toBeInTheDocument()
  })

  it('sends a first launch to the welcome steps and never shows the app before they are done', async () => {
    mockApi(routes({ ...onboarded, complete: false, nextStep: 'CV', cv: { present: false, entries: 0, facts: 0, skills: 0 }, claude: { chosen: false, provider: 'cli', works: false } }))
    renderApp('/documents')

    expect(await screen.findByRole('heading', { name: 'Start with your CV' })).toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: 'Pipeline' })).not.toBeInTheDocument()
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
