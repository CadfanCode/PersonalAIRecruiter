import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SetupProvider } from '../../setup/SetupProvider'
import { mockApi } from '../../test/mockApi'
import { FlowBar } from '../shell/FlowBar'
import { ToastProvider } from '../Toasts'
import { useImportWatcher } from './useImportWatcher'

function Watcher() {
  useImportWatcher()
  return null
}

const running = { runId: 5, status: 'RUNNING', message: 'Reading your CV', files: ['cv.pdf'], preview: null }

function renderBar() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider><SetupProvider><MemoryRouter><Watcher /><FlowBar /></MemoryRouter></SetupProvider></ToastProvider>
    </QueryClientProvider>,
  )
}

afterEach(() => vi.unstubAllGlobals())

describe('a CV being read in the background', () => {
  const routes = (current: () => unknown) => ({
    'GET /api/cv/import/current': current,
    'GET /api/onboarding': { complete: true, nextStep: null },
    'GET /api/applications': [],
    'GET /api/apply/status': [],
  })

  it('shows in the top bar while it runs, linking to where it will appear', async () => {
    mockApi(routes(() => running))
    renderBar()

    expect(await screen.findByRole('link', { name: 'Reading your CV' })).toHaveAttribute('href', '/cv?tab=import')
  })

  it('says so when it is done, wherever the user is', async () => {
    let current: unknown = running
    mockApi(routes(() => current))
    renderBar()
    await screen.findByRole('link', { name: 'Reading your CV' })

    current = { ...running, status: 'DONE', message: 'Ready to review' }

    expect(await screen.findByText('Your CV has been read. Review it in the CV database.', {}, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Reading your CV' })).not.toBeInTheDocument()
  })

  it('shows nothing when no CV is being read', async () => {
    mockApi(routes(() => null))
    renderBar()

    await screen.findByRole('navigation', { name: 'Progress' })
    expect(screen.queryByRole('link', { name: 'Reading your CV' })).not.toBeInTheDocument()
  })
})
