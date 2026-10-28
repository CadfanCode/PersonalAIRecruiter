import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { countryView, onboarded } from '../test/fixtures2'
import { mockApi } from '../test/mockApi'
import { OnboardingPage } from './OnboardingPage'

const fresh = {
  cv: { present: false, entries: 0, facts: 0, skills: 0 }, claude: { chosen: false, provider: 'cli', works: false },
  country: { chosen: false, code: 'SE' }, mode: { chosen: false, mode: 'PROMPT' }, complete: false, nextStep: 'CV',
}

const connection = (over = {}) => ({
  selected: 'cli', chosen: false, cli: { found: true, path: '/bin/claude', version: '2.1.312 (Claude Code)', help: null },
  api: { configured: false, keyHint: null, model: 'claude-sonnet-5-5' }, secureStore: { available: true, name: 'Secret Service' }, ...over,
})

const ok = { ok: true, provider: 'cli', problem: null, message: 'Connected.', help: null, durationMs: 800 }

const preview = {
  previewId: 'p1', files: ['cv.pdf'],
  entries: [
    { entry: { kind: 'WORK', title: 'Sjuksköterska', titleEn: 'Nurse', organisation: 'Akademiska', location: null, startYm: '2020-01', endYm: null, ongoing: true, tags: [], facts: [{ textEn: 'Triage.', textSv: 'Triage.', tags: [] }] }, duplicateOfEntryId: null },
    { entry: { kind: 'EDUCATION', title: 'Sjuksköterskeexamen', titleEn: null, organisation: 'Uppsala universitet', location: null, startYm: '2016', endYm: '2019', ongoing: false, tags: [], facts: [] }, duplicateOfEntryId: null },
    { entry: { kind: 'WORK', title: 'Old job', titleEn: null, organisation: 'X', location: null, startYm: null, endYm: null, ongoing: false, tags: [], facts: [] }, duplicateOfEntryId: 7 },
  ],
  skills: [{ name: 'Triage', category: 'clinical' }],
  profile: { fullName: 'Alex Lindgren', headline: 'Nurse', email: 'alex@example.com', phone: null, city: 'Uppsala', website: null, linkedin: null, github: null },
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/welcome']}>
        <Routes>
          <Route path="/welcome" element={<OnboardingPage />} />
          <Route path="/" element={<p>The app</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const file = (name = 'cv.pdf') => new File(['%PDF'], name, { type: 'application/pdf' })

afterEach(() => vi.unstubAllGlobals())

describe('onboarding: your CV', () => {
  it('asks for a CV first, explains what happens to it, and cannot go on without one', async () => {
    mockApi({ 'GET /api/onboarding': fresh })
    renderPage()

    expect(await screen.findByRole('heading', { name: 'Start with your CV' })).toBeInTheDocument()
    expect(screen.getByText(/break your CV down into a personal database/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()
    expect(within(screen.getByRole('list', { name: 'Setup progress' })).getAllByRole('listitem')).toHaveLength(4)
    expect(screen.getByRole('listitem', { current: 'step' })).toHaveTextContent('Your CV')
  })

  it('takes files by browsing or dropping, rejects unsupported ones, and lets them be removed', async () => {
    mockApi({ 'GET /api/onboarding': fresh })
    renderPage()

    await userEvent.upload(await screen.findByLabelText('CV files'), file('cv.pdf'))
    expect(within(screen.getByRole('list', { name: 'Chosen files' })).getByText('cv.pdf')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled()

    const zone = screen.getByText('Drop your CV here').closest('div') as HTMLElement
    fireEvent.drop(zone, { dataTransfer: { files: [file('letter.docx'), new File(['x'], 'photo.png', { type: 'image/png' })] } })
    expect(await screen.findByText('letter.docx')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('photo.png is not supported')

    await userEvent.click(screen.getByRole('button', { name: 'Remove cv.pdf' }))
    expect(screen.queryByText('cv.pdf')).not.toBeInTheDocument()
  })

  it('lets a returning user go on with the CV database they already have', async () => {
    mockApi({ 'GET /api/onboarding': { ...onboarded, complete: false, nextStep: null }, 'GET /api/claude/connection': connection() })
    renderPage()

    expect(await screen.findByText(/already has 4 entries/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled()
  })
})

describe('onboarding: connect Claude', () => {
  async function toConnect(extra: Record<string, unknown> = {}) {
    const calls = mockApi({ 'GET /api/onboarding': fresh, 'GET /api/claude/connection': connection(), ...extra })
    renderPage()
    await userEvent.upload(await screen.findByLabelText('CV files'), file())
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByRole('heading', { name: 'Connect Claude' })
    return calls
  }

  it('finds the Claude Code program, reassures about where credentials are kept, and only continues after a real test passes', async () => {
    const calls = await toConnect({
      'PUT /api/claude/connection/provider': connection({ chosen: true }),
      'POST /api/claude/connection/test': ok,
    })

    expect(await screen.findByText('Found')).toBeInTheDocument()
    expect(screen.getByText(/2.1.312/)).toBeInTheDocument()
    expect(screen.getByText(/secure store: Secret Service, never into a plain file/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()

    await userEvent.click(screen.getByRole('button', { name: 'Test the connection' }))

    expect(await screen.findByText(/Connected. Claude answered./)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled()
    expect(calls.find((c) => c.url === '/api/claude/connection/test')!.body).toEqual({ provider: 'cli' })
  })

  it('explains how to install Claude Code when it is missing, and offers to check again', async () => {
    await toConnect({
      'GET /api/claude/connection': connection({ cli: { found: false, path: 'claude', version: null, help: 'Install Claude Code: open a terminal and run  curl -fsSL https://claude.ai/install.sh | bash  then type  claude  and sign in.' } }),
    })

    expect(await screen.findByText('Not found')).toBeInTheDocument()
    expect(screen.getByText(/curl -fsSL https:\/\/claude.ai\/install.sh/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Check again' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Test the connection' })).toBeDisabled()
  })

  it('shows what is wrong and what to do when the test fails, and does not let the user pass', async () => {
    await toConnect({
      'PUT /api/claude/connection/provider': connection({ chosen: true }),
      'POST /api/claude/connection/test': { ok: false, provider: 'cli', problem: 'NOT_LOGGED_IN', message: 'Claude Code is installed but not signed in.', help: 'Open a terminal, type  claude  and sign in with your Claude account, then test again.', durationMs: 3000 },
    })

    await userEvent.click(await screen.findByRole('button', { name: 'Test the connection' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('installed but not signed in')
    expect(alert).toHaveTextContent('sign in with your Claude account')
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()
  })

  it('saves an API key, selects it and tests it, and never shows the key again', async () => {
    const key = 'sk-ant-api03-abcdefghijklmnopqrstuvwxyz-0123'
    const calls = await toConnect({
      'PUT /api/claude/connection/api-key': connection({ api: { configured: true, keyHint: '…0123', model: 'claude-sonnet-5-5' } }),
      'PUT /api/claude/connection/provider': connection({ selected: 'api', chosen: true }),
      'POST /api/claude/connection/test': { ...ok, provider: 'api' },
    })

    await userEvent.click(screen.getByRole('radio', { name: /Anthropic API key/ }))
    const input = screen.getByLabelText('Anthropic API key')
    expect(input).toHaveAttribute('type', 'password')
    await userEvent.type(input, key)
    await userEvent.click(screen.getByRole('button', { name: 'Save and test' }))

    expect(await screen.findByText(/Connected. Claude answered./)).toBeInTheDocument()
    expect(calls.map((c) => `${c.method} ${c.url}`).filter((u) => u.includes('connection/')))
      .toEqual(['PUT /api/claude/connection/api-key', 'PUT /api/claude/connection/provider', 'POST /api/claude/connection/test'])
    expect(calls.find((c) => c.url === '/api/claude/connection/api-key')!.body).toEqual({ key })
    expect(screen.queryByDisplayValue(key)).not.toBeInTheDocument()
    expect(document.body.textContent).not.toContain(key)
  })

  it('does not offer the API key when the computer has no secure place to keep it', async () => {
    await toConnect({ 'GET /api/claude/connection': connection({ secureStore: { available: false, name: 'none available' } }) })

    await userEvent.click(screen.getByRole('radio', { name: /Anthropic API key/ }))

    expect(screen.getByRole('alert')).toHaveTextContent('no secure credential store')
    expect(screen.queryByLabelText('Anthropic API key')).not.toBeInTheDocument()
  })
})

describe('onboarding: the whole way through', () => {
  it('reads the held CV once Claude is connected, shows what was found, then country and search mode, and finishes', async () => {
    let state: Record<string, unknown> = fresh
    const calls = mockApi({
      'GET /api/onboarding': () => state,
      'GET /api/claude/connection': connection(),
      'PUT /api/claude/connection/provider': connection({ chosen: true }),
      'POST /api/claude/connection/test': ok,
      'POST /api/cv/import': preview,
      'POST /api/cv/import/p1/commit': () => {
        state = { ...fresh, cv: { present: true, entries: 2, facts: 1, skills: 1 }, nextStep: 'CLAUDE' }
        return { entryIds: [1, 2], facts: 1, skills: 1, profileFilled: true }
      },
      'GET /api/country': { ...countryView, chosen: false },
      'PUT /api/country': () => {
        state = { ...state, country: { chosen: true, code: 'SE' } }
        return countryView
      },
      'PUT /api/onboarding/mode': () => {
        state = { ...state, mode: { chosen: true, mode: 'DISCOVER' } }
        return state
      },
      'POST /api/onboarding/complete': () => ({ ...onboarded }),
    })
    renderPage()

    await userEvent.upload(await screen.findByLabelText('CV files'), file())
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Test the connection' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Continue' }))

    // step 1's files are read only now, with the progress view in between
    expect(await screen.findByRole('heading', { name: 'Here is what we found, Alex' })).toBeInTheDocument()
    expect(screen.getByText(/2 entries with 1 facts and 1 skills/)).toBeInTheDocument()
    expect(screen.getByText(/1 entries were already in your database/)).toBeInTheDocument()
    expect(calls.filter((c) => c.url === '/api/cv/import')).toHaveLength(1)
    await userEvent.click(screen.getByRole('button', { name: /Looks right, continue/ }))
    const commit = calls.find((c) => c.url === '/api/cv/import/p1/commit')!.body as { entries: unknown[]; profile: { fullName: string } }
    expect(commit.entries).toHaveLength(2) // the duplicate is left out
    expect(commit.profile.fullName).toBe('Alex Lindgren')

    // country: only Sweden can be chosen
    expect(await screen.findByRole('heading', { name: 'Where do you want to work?' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /Norway/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()
    await userEvent.click(screen.getByRole('radio', { name: /Sweden/ }))
    await userEvent.click(await screen.findByRole('button', { name: 'Continue' }))

    // search mode: two cards, then finish
    expect(await screen.findByRole('heading', { name: 'How do you want to search?' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Finish setup' })).toBeDisabled()
    await userEvent.click(screen.getByRole('radio', { name: /Discovery mode/ }))
    await userEvent.click(await screen.findByRole('button', { name: 'Finish setup' }))

    expect(await screen.findByText('The app')).toBeInTheDocument()
    expect(calls.find((c) => c.url === '/api/onboarding/mode')!.body).toEqual({ mode: 'DISCOVER' })
  })

  it('shows what went wrong when the CV cannot be read, and lets the user try again', async () => {
    let tries = 0
    mockApi({
      'GET /api/onboarding': fresh,
      'GET /api/claude/connection': connection(),
      'PUT /api/claude/connection/provider': connection({ chosen: true }),
      'POST /api/claude/connection/test': ok,
      'POST /api/cv/import': () => {
        tries++
        return tries === 1 ? new Response(JSON.stringify({ error: 'cv.pdf contains no extractable text (scanned PDF?)' }), { status: 400 }) : preview
      },
    })
    renderPage()

    await userEvent.upload(await screen.findByLabelText('CV files'), file())
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Test the connection' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Continue' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('scanned PDF')
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('heading', { name: 'Here is what we found, Alex' })).toBeInTheDocument()
  })
})
