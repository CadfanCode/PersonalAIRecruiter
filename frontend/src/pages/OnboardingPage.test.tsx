import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { countryView, onboarded } from '../test/fixtures2'
import { SetupProvider } from '../setup/SetupProvider'
import { mockApi } from '../test/mockApi'
import { OnboardingPage } from './OnboardingPage'

const fresh = {
  cv: { present: false, entries: 0, facts: 0, skills: 0 }, claude: { chosen: false, provider: 'cli', works: false },
  country: { chosen: false, code: 'SE' }, mode: { chosen: false, mode: 'PROMPT' }, complete: false, nextStep: 'CV',
}

const connection = (over = {}) => ({
  selected: 'cli', chosen: false, cli: { found: true, path: '/bin/claude', version: '2.1.312 (Claude Code)', help: null, model: '', stageModels: [{ stage: 'SEARCH', label: 'Search', model: '' }, { stage: 'RANKING', label: 'Scoring', model: '' }, { stage: 'CV_READING', label: 'Reading your CV', model: '' }, { stage: 'DOCUMENTS', label: 'CV and cover letter', model: '' }, { stage: 'AUTOFILL', label: 'Auto-fill', model: '' }] },
  secureStore: { available: true, name: 'Secret Service' }, ...over,
})

const ok = { ok: true, provider: 'cli', problem: null, message: 'Connected.', help: null, durationMs: 800 }

const preview = {
  previewId: 'p1', files: ['cv.pdf'],
  entries: [
    { entry: { kind: 'WORK', title: 'Sjuksköterska', titleEn: 'Nurse', organisation: 'Akademiska', location: null, startYm: '2020-01', endYm: null, ongoing: true, tags: [], facts: [{ textEn: 'Triage.', textSv: 'Triage.', tags: [] }] }, matchEntryId: null, matchTitle: null, newFacts: 0 },
    { entry: { kind: 'EDUCATION', title: 'Sjuksköterskeexamen', titleEn: null, organisation: 'Uppsala universitet', location: null, startYm: '2016', endYm: '2019', ongoing: false, tags: [], facts: [] }, matchEntryId: null, matchTitle: null, newFacts: 0 },
    { entry: { kind: 'WORK', title: 'Old job', titleEn: null, organisation: 'X', location: null, startYm: null, endYm: null, ongoing: false, tags: [], facts: [] }, matchEntryId: 7, matchTitle: 'Old job, X', newFacts: 0 },
  ],
  skills: [{ name: 'Triage', category: 'clinical' }],
  profile: { fullName: 'Alex Lindgren', headline: 'Nurse', email: 'alex@example.com', phone: null, city: 'Uppsala', website: null, linkedin: null, github: null },
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <SetupProvider>
        <MemoryRouter initialEntries={['/welcome']}>
          <Routes>
            <Route path="/welcome" element={<OnboardingPage />} />
            <Route path="/" element={<p>The app</p>} />
          </Routes>
        </MemoryRouter>
      </SetupProvider>
    </QueryClientProvider>,
  )
}

/** The server's reading of the uploaded files: started by the upload, finished by the time it is asked again. */
const job = { runId: 5, status: 'DONE', message: 'Ready to review', files: ['cv.pdf'], preview }
const reading = () => {
  let current: unknown = null
  return {
    'GET /api/cv/import/current': () => current,
    'POST /api/cv/import': () => { current = job; return job },
  }
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
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()

    await userEvent.click(screen.getByRole('button', { name: 'Test the connection' }))

    expect(await screen.findByText(/Connected. Claude answered./)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled()
    expect(calls.find((c) => c.url === '/api/claude/connection/test')!.body).toBeUndefined()
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
})

describe('onboarding: the whole way through', () => {
  it('reads the held CV once Claude is connected, shows what was found, then country and search mode, and finishes', async () => {
    let state: Record<string, unknown> = fresh
    const calls = mockApi({
      'GET /api/onboarding': () => state,
      'GET /api/claude/connection': connection(),
      'PUT /api/claude/connection/provider': connection({ chosen: true }),
      'POST /api/claude/connection/test': ok,
      ...reading(),
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
    expect(screen.getByText(/2 new entries, 1 facts and 1 skills/)).toBeInTheDocument()
    expect(screen.getByText(/1 entry was already in your database: nothing new to add to them/)).toBeInTheDocument()
    expect(calls.filter((c) => c.url === '/api/cv/import')).toHaveLength(1)

    // each category with entries opens to exactly its entries, by mouse and keyboard; empty ones are not buttons
    const work = screen.getByRole('button', { name: /Work/ })
    expect(work).toHaveAttribute('aria-expanded', 'false')
    await userEvent.click(work)
    expect(work).toHaveAttribute('aria-expanded', 'true')
    const workList = document.getElementById('found-WORK')!
    expect(within(workList).getByText('Sjuksköterska')).toBeInTheDocument()
    expect(within(workList).getByText('Triage.')).toBeInTheDocument()
    expect(screen.queryByText('Old job')).not.toBeInTheDocument() // the duplicate is not counted
    const edu = screen.getByRole('button', { name: /Education/ })
    edu.focus()
    await userEvent.keyboard('{Enter}')
    expect(edu).toHaveAttribute('aria-expanded', 'true')
    expect(within(document.getElementById('found-EDUCATION')!).getByText('Sjuksköterskeexamen')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Projects/ })).not.toBeInTheDocument()
    expect(screen.getByText('Projects')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Looks right, continue/ }))
    const commit = calls.find((c) => c.url === '/api/cv/import/p1/commit')!.body as { entries: unknown[]; profile: { fullName: string } }
    expect(commit.entries).toHaveLength(3) // the match is sent too, to be merged into the entry it matches
    expect((commit.entries as { mergeIntoEntryId: number | null }[]).map((e) => e.mergeIntoEntryId)).toEqual([null, null, 7])
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
    await userEvent.click(screen.getByRole('radio', { name: /Discovery Mode/ }))
    await userEvent.click(await screen.findByRole('button', { name: 'Finish setup' }))

    expect(await screen.findByText('The app')).toBeInTheDocument()
    expect(calls.find((c) => c.url === '/api/onboarding/mode')!.body).toEqual({ mode: 'DISCOVER' })
  })

  it('shows what went wrong when the CV cannot be read, and lets the user try again', async () => {
    let tries = 0
    let current: unknown = null
    mockApi({
      'GET /api/onboarding': fresh,
      'GET /api/claude/connection': connection(),
      'PUT /api/claude/connection/provider': connection({ chosen: true }),
      'POST /api/claude/connection/test': ok,
      'GET /api/cv/import/current': () => current,
      'POST /api/cv/import': () => {
        tries++
        if (tries === 1) return new Response(JSON.stringify({ error: 'cv.pdf contains no extractable text (scanned PDF?)' }), { status: 400 })
        current = job
        return job
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
