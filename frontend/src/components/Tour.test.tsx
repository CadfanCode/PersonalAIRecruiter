import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { HelpPage } from '../pages/HelpPage'
import { closeTour } from '../lib/tourStore'
import { onboarded } from '../test/fixtures2'
import { mockApi } from '../test/mockApi'
import { Tour } from './Tour'

function renderTour(complete = true) {
  mockApi({ 'GET /api/onboarding': { ...onboarded, complete } })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter><HelpPage /><Tour /></MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => { localStorage.clear(); closeTour(); localStorage.clear() })
afterEach(() => { vi.unstubAllGlobals(); localStorage.clear() })

describe('the walk-through', () => {
  it('is offered once, when setup is done, and walks through the stages', async () => {
    renderTour()

    const dialog = await screen.findByRole('dialog', { name: 'Welcome to JobPilot' })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(screen.getByRole('button', { name: 'Next' })).toHaveFocus()
    await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByRole('heading', { name: 'Find jobs' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Back' }))
    expect(screen.getByRole('heading', { name: 'Welcome to JobPilot' })).toBeInTheDocument()
  })

  it('is not shown while setup is still open', async () => {
    renderTour(false)

    await screen.findByRole('heading', { name: 'Help' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('closes with Escape, gives focus back, and is not offered again by itself', async () => {
    const { unmount } = renderTour()
    await screen.findByRole('dialog')

    await userEvent.keyboard('{Escape}')

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(localStorage.getItem('jobpilot.tourSeen')).toBe('1')
    unmount()
    renderTour()
    await screen.findByRole('heading', { name: 'Help' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('can be started again from Help and finished with Start on the last step', async () => {
    renderTour()
    await userEvent.keyboard('{Escape}')

    await userEvent.click(screen.getByRole('button', { name: 'Start the tour' }))
    for (let i = 0; i < 5; i++) await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByRole('heading', { name: 'Your tools' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Start' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('says what JobPilot never does', async () => {
    renderTour()
    await userEvent.keyboard('{Escape}')

    expect(screen.getByText(/Press a submit or send button/)).toBeInTheDocument()
    expect(screen.getByText(/Tick consent, GDPR/)).toBeInTheDocument()
  })
})
