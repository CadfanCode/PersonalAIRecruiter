import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { application, tab } from '../../test/fixtures2'
import { mockApi } from '../../test/mockApi'
import { ToastProvider } from '../Toasts'
import { useReadyNotifications } from './notifications'

function Watcher() {
  useReadyNotifications()
  return null
}

const notes: { title: string; body: string }[] = []

function renderWatcher() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const view = render(
    <QueryClientProvider client={client}>
      <ToastProvider><Watcher /></ToastProvider>
    </QueryClientProvider>,
  )
  return { client, view }
}

beforeEach(() => {
  notes.length = 0
  class FakeNotification {
    static permission = 'granted'
    constructor(title: string, options: { body: string }) {
      notes.push({ title, body: options.body })
    }
  }
  vi.stubGlobal('Notification', FakeNotification)
})
afterEach(() => vi.unstubAllGlobals())

describe('ready for submission notifications', () => {
  it('stays quiet about what was already ready, then tells the user in the app and with a system notification when one becomes ready', async () => {
    let phase: 'FILLING' | 'READY_FOR_SUBMISSION' = 'FILLING'
    mockApi({
      'GET /api/applications': [application({ title: 'Nurse at Akademiska' })],
      'GET /api/apply/status': () => [tab({ phase, status: phase === 'FILLING' ? 'FILLING' : 'READY_FOR_USER' })],
    })
    const { client } = renderWatcher()
    await vi.waitFor(() => expect(client.getQueryData(['apply', 'status'])).toBeDefined())
    expect(notes).toEqual([])

    phase = 'READY_FOR_SUBMISSION'
    await client.invalidateQueries({ queryKey: ['apply', 'status'] })

    expect(await screen.findByText('Nurse at Akademiska is ready for you to submit')).toBeInTheDocument()
    expect(notes).toEqual([{ title: 'JobPilot', body: 'Nurse at Akademiska is ready for you to submit' }])

    // another poll with the same state does not repeat it
    await client.invalidateQueries({ queryKey: ['apply', 'status'] })
    expect(notes).toHaveLength(1)
  })

  it('does not announce an application that was already ready when the app opened', async () => {
    mockApi({
      'GET /api/applications': [application()],
      'GET /api/apply/status': [tab({ phase: 'READY_FOR_SUBMISSION', status: 'READY_FOR_USER' })],
    })
    const { client } = renderWatcher()
    await vi.waitFor(() => expect(client.getQueryData(['apply', 'status'])).toBeDefined())

    expect(notes).toEqual([])
    expect(screen.queryByText(/ready for you to submit/)).not.toBeInTheDocument()
  })

  it('only shows the in-app message when system notifications were not allowed', async () => {
    vi.stubGlobal('Notification', class { static permission = 'denied' })
    let phase: 'FILLING' | 'READY_FOR_SUBMISSION' = 'FILLING'
    mockApi({
      'GET /api/applications': [application({ title: 'Nurse' })],
      'GET /api/apply/status': () => [tab({ phase })],
    })
    const { client } = renderWatcher()
    await vi.waitFor(() => expect(client.getQueryData(['apply', 'status'])).toBeDefined())

    phase = 'READY_FOR_SUBMISSION'
    await client.invalidateQueries({ queryKey: ['apply', 'status'] })

    expect(await screen.findByText('Nurse is ready for you to submit')).toBeInTheDocument()
    expect(notes).toEqual([])
  })
})
