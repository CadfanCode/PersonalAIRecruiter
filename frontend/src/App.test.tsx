import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, vi } from 'vitest'
import { App } from './App'

const usage = {
  totals: { calls: 3, failures: 1, inputTokens: 1, outputTokens: 1, cacheReadTokens: 0, cacheCreationTokens: 0, durationMs: 1 },
  byTask: [],
}

const limits = {
  limits: {
    session: { utilization: 0.07, resetsAt: '2026-10-27T14:30:00Z', status: 'allowed' },
    week: { utilization: 0.96, resetsAt: '2026-10-30T18:00:00Z', status: 'allowed_warning' },
    checkedAt: '2026-10-27T12:00:00Z',
  },
  error: null,
}

function mockApi(health: unknown) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      const body = url.includes('/health') ? health : url.includes('/usage') ? usage : url.includes('/limits') ? limits : []
      return new Response(JSON.stringify(body), { status: 200 })
    }),
  )
}

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

beforeEach(() => mockApi({ ok: true, version: '2.1.310 (Claude Code)', durationMs: 4200, error: null }))
afterEach(() => vi.unstubAllGlobals())

describe('app shell', () => {
  it('shows the main navigation and navigates between pages', async () => {
    renderApp()
    const nav = screen.getByRole('navigation', { name: 'Main' })
    expect(nav).toHaveTextContent('CV database')
    expect(within(nav).getByRole('link', { name: /Finalize Applications/ })).toHaveAttribute('href', '/finalize')
    expect(within(nav).getByRole('link', { name: 'Activity Report' })).toHaveAttribute('href', '/aktivitet')
    expect(within(nav).queryByText(/Apply monitor/)).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('link', { name: 'Settings' }))
    expect(screen.getByRole('heading', { name: 'Settings' })).toBeInTheDocument()
  })

  it('renders the dashboard with example chips', () => {
    renderApp()
    expect(screen.getByRole('heading', { level: 1, name: 'New run' })).toBeInTheDocument()
    expect(screen.getByText('Accountant, Malmö, part time')).toBeInTheDocument()
  })
})

describe('Claude health card', () => {
  it('shows usage totals without calling the model', async () => {
    renderApp()
    expect(await screen.findByText('96% used')).toBeInTheDocument()
    expect(screen.getByText('7% used')).toBeInTheDocument()
    expect(screen.queryByText(/\$/)).not.toBeInTheDocument()
    const calls = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.map((c) => c[0])
    expect(calls).not.toContain('/api/claude/health')
  })

  it('reports ok after the user runs the check', async () => {
    renderApp()
    await userEvent.click(screen.getByRole('button', { name: 'Check Claude' }))
    expect(await screen.findByRole('status')).toHaveTextContent('ok: 2.1.310 (Claude Code)')
  })

  it('shows the error text when Claude is not working', async () => {
    mockApi({ ok: false, version: null, durationMs: 10, error: "Cannot start 'claude'" })
    renderApp()
    await userEvent.click(screen.getByRole('button', { name: 'Check Claude' }))
    expect(await screen.findByRole('status')).toHaveTextContent("Not working: Cannot start 'claude'")
  })

  it('tells the user when the API is unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))
    renderApp()
    await userEvent.click(screen.getByRole('button', { name: 'Check Claude' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the JobPilot API')
  })
})
