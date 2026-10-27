import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
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
const usage = { totals: { calls: 29, failures: 0, costUsd: 1.82 }, byTask: [{ task: 'scoreJob', calls: 17, costUsd: 0.6 }] }

function setup(extra: Record<string, unknown> = {}) {
  const calls = mockApi({
    'GET /api/claude/usage': usage,
    'GET /api/settings/style-rules': rules,
    'GET /api/settings/banned-phrases': phrases,
    ...extra,
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><SettingsPage /></QueryClientProvider>)
  return calls
}

afterEach(() => vi.unstubAllGlobals())

describe('settings page', () => {
  it('shows usage per task and explains where the CLI model is set', async () => {
    setup()

    expect(await screen.findByText('29 calls, 0 failed, $1.82 in total')).toBeInTheDocument()
    expect(screen.getByText('scoreJob')).toBeInTheDocument()
    expect(screen.getByText(/JOBPILOT_CLAUDE_MODEL/)).toBeInTheDocument()
  })

  it('saves an edited writing rule and can switch it off', async () => {
    const calls = setup({ 'PUT /api/settings/style-rules/1': rules })

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

    await userEvent.click(await screen.findByLabelText('Enable phrase brinner för'))
    await userEvent.click(screen.getByLabelText('Delete phrase leverage'))

    await vi.waitFor(() => expect(calls.some((c) => c.method === 'DELETE')).toBe(true))
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ enabled: false })
    expect(screen.getByLabelText('Enable phrase leverage')).not.toBeChecked()
  })

  it('adds a phrase in the chosen language', async () => {
    const calls = setup({ 'POST /api/settings/banned-phrases': { id: 9, language: 'en', phrase: 'game changer', enabled: true } })

    await userEvent.selectOptions(await screen.findByLabelText('Phrase language'), 'en')
    await userEvent.type(screen.getByLabelText('Phrase'), 'game changer')
    await userEvent.click(screen.getByRole('button', { name: 'Add phrase' }))

    await vi.waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ language: 'en', phrase: 'game changer' })
    expect(within(screen.getByText('Banned phrases').closest('section')!).getByLabelText('Phrase')).toHaveValue('')
  })
})
