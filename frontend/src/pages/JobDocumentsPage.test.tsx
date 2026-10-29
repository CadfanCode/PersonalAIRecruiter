import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cvDoc, job, jobDocs, report, run } from '../test/fixtures'
import { mockApi } from '../test/mockApi'
import { DocumentsIndexPage } from './DocumentsIndexPage'
import { JobDocumentsPage } from './JobDocumentsPage'

function renderPage(path = '/documents/job/1') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/documents" element={<DocumentsIndexPage />} />
          <Route path="/documents/job/:jobId" element={<JobDocumentsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const jobDetail = { row: job({ title: 'Junior Java-utvecklare', employer: 'Comstream AB' }), description: 'x' }

const entries = [
  { id: 1, kind: 'INTERNSHIP', title: 'Systemutvecklare (praktik)', hidden: false, tags: [], facts: [] },
  { id: 2, kind: 'WORK', title: 'Laborationslärare', hidden: false, tags: [], facts: [] },
]

const base = () => ({
  'GET /api/jobs/1/documents': jobDocs(),
  'GET /api/jobs/1': jobDetail,
  'GET /api/cv/entries': entries,
})

afterEach(() => vi.unstubAllGlobals())

describe('documents page', () => {
  it('shows the CV in an editor next to the PDF preview, with the source facts on hover', async () => {
    mockApi(base())
    renderPage()

    expect(await screen.findByDisplayValue('Javautvecklare | Spring Boot')).toBeInTheDocument()
    expect(screen.getByText('Comstream AB')).toBeInTheDocument()
    expect(screen.getByDisplayValue('Byggde mikrotjänster i Java och Kotlin.')).toBeInTheDocument()
    const chip = screen.getByLabelText('Source fact 10: Byggde mikrotjänster i Java och Kotlin.')
    expect(chip).toHaveAttribute('title', 'Software Developer (intern): Byggde mikrotjänster i Java och Kotlin.')
    expect(screen.getByTitle('CV preview')).toHaveAttribute('src', '/api/documents/100/pdf?v=1#navpanes=0&view=FitH')
  })

  it('highlights the text each violation is about and lists them in the report', async () => {
    const bad = jobDocs({
      cv: cvDoc({
        report: report(
          { code: 'dash', severity: 'FAIL', where: 'sections[1].items[0].bullets[0]', text: '– (U+2013)', message: 'Dashes are not allowed' },
          { code: 'banned_phrase', severity: 'WARN', where: 'profileParagraphs[0]', text: 'brinner för', message: 'Typical AI phrasing: "brinner för"' },
        ),
      }),
    })
    mockApi({ ...base(), 'GET /api/jobs/1/documents': bad })
    renderPage()

    const bullet = await screen.findByLabelText('Bullet 1 of Systemutvecklare (praktik)')
    expect(bullet).toHaveClass('ring-[var(--danger)]')
    expect(screen.getByLabelText('Profile paragraph 1')).toHaveClass('ring-[var(--warn)]')
    expect(screen.getAllByText(/Must fix: Dashes are not allowed/)).toHaveLength(1)
    expect(screen.getByText('1 must be fixed before approval')).toBeInTheDocument()
    expect(screen.getByText('2 of 2 pages used')).toBeInTheDocument()
  })

  it('does not ask for a run when there is none to follow', async () => {
    const calls = mockApi(base())
    renderPage()

    await screen.findByDisplayValue('Javautvecklare | Spring Boot')

    expect(calls.map((c) => c.url).filter((u) => u.startsWith('/api/runs'))).toEqual([])
  })

  it('saves edited text as the whole content and then re-renders the preview', async () => {
    const calls = mockApi({ ...base(), 'PUT /api/documents/100': cvDoc({ version: 2 }) })
    renderPage()

    const bullet = await screen.findByLabelText('Bullet 1 of Systemutvecklare (praktik)')
    expect(screen.getByRole('button', { name: 'Save and re-render' })).toBeDisabled()
    await userEvent.clear(bullet)
    await userEvent.type(bullet, 'Byggde och driftsatte mikrotjänster.')
    await userEvent.click(screen.getByRole('button', { name: 'Save and re-render' }))

    await vi.waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true))
    const put = calls.find((c) => c.method === 'PUT')!
    expect(put.url).toBe('/api/documents/100')
    const sent = put.body as { sections: { items: { bullets: { text: string; factIds: number[] }[] }[] }[]; headline: string }
    expect(sent.sections[1].items[0].bullets[0]).toEqual({ text: 'Byggde och driftsatte mikrotjänster.', factIds: [10] })
    expect(sent.headline).toBe('Javautvecklare | Spring Boot')
  })

  it('removes a bullet and edits the other fields', async () => {
    const calls = mockApi({ ...base(), 'PUT /api/documents/100': cvDoc({ version: 2 }) })
    renderPage()

    await screen.findByLabelText('Bullet 2 of Systemutvecklare (praktik)')
    const remove = screen.getAllByRole('button', { name: 'Remove bullet' })
    await userEvent.click(remove[1])
    await userEvent.type(screen.getByLabelText('Dates'), '-2027')
    await userEvent.click(screen.getByRole('button', { name: 'Save and re-render' }))

    await vi.waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true))
    const sent = calls.find((c) => c.method === 'PUT')!.body as { sections: { items: { dateLabel: string; bullets: unknown[] }[] }[] }
    expect(sent.sections[1].items[0].bullets).toHaveLength(1)
    expect(sent.sections[1].items[0].dateLabel).toBe('2026-2027')
  })

  it('approves a clean document', async () => {
    const calls = mockApi({ ...base(), 'POST /api/documents/100/approve': { violations: [], pages: 2, maxPages: 2 } })
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Approve CV' }))

    await vi.waitFor(() => expect(calls.some((c) => c.url === '/api/documents/100/approve')).toBe(true))
  })

  it('does not approve while there are unsaved edits', async () => {
    mockApi(base())
    renderPage()

    await userEvent.type(await screen.findByLabelText('Profile paragraph 1'), ' Mer text.')

    expect(screen.getByRole('button', { name: 'Approve CV' })).toBeDisabled()
  })

  it('shows the report when approval is refused', async () => {
    const refusal = report({ code: 'unknown_fact', severity: 'FAIL', where: 'sections[1].items[0].bullets[1]', text: '999', message: 'Fact 999 does not exist' })
    mockApi({
      ...base(),
      'POST /api/documents/100/approve': () =>
        new Response(JSON.stringify({ error: 'The document still has problems', report: refusal }), { status: 409 }),
    })
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Approve CV' }))

    expect(await screen.findByText('Not approved: fix the problems marked in red first.')).toBeInTheDocument()
    expect(screen.getByLabelText('Bullet 2 of Systemutvecklare (praktik)')).toHaveClass('ring-[var(--danger)]')
    // once inline under the bullet, once in the report
    expect(screen.getAllByText(/Fact 999 does not exist/)).toHaveLength(2)
  })

  it('edits the cover letter on its own tab', async () => {
    const calls = mockApi({ ...base(), 'PUT /api/documents/101': jobDocs().letter })
    renderPage()

    await userEvent.click(await screen.findByRole('tab', { name: /Cover letter/ }))
    expect(screen.getByTitle('Cover letter preview')).toHaveAttribute('src', '/api/documents/101/pdf?v=1#navpanes=0&view=FitH')
    const p = screen.getByLabelText('Paragraph 2')
    await userEvent.clear(p)
    await userEvent.type(p, 'Ett konkret exempel.')
    await userEvent.click(screen.getByRole('button', { name: 'Save and re-render' }))

    await vi.waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true))
    const sent = calls.find((c) => c.method === 'PUT')!.body as { paragraphs: string[]; subject: string }
    expect(sent.paragraphs).toEqual(['Jag är systemutvecklare.', 'Ett konkret exempel.'])
    expect(sent.subject).toBe('Ansökan: Junior Java-utvecklare')
  })

  it('lets the user pin and exclude CV entries for this job', async () => {
    const calls = mockApi({ ...base(), 'PATCH /api/jobs/1/selection': null })
    renderPage()

    await userEvent.selectOptions(await screen.findByLabelText('Selection of Systemutvecklare (praktik)'), 'pin')
    await userEvent.selectOptions(screen.getByLabelText('Selection of Laborationslärare'), 'exclude')
    await userEvent.click(screen.getByRole('button', { name: 'Save selection' }))

    await vi.waitFor(() => expect(calls.some((c) => c.method === 'PATCH')).toBe(true))
    expect(calls.find((c) => c.method === 'PATCH')!.body).toEqual({ pinned: [1], excluded: [2] })
    expect(screen.getByText('used in both')).toHaveAttribute('title', 'Strong Java match')
  })

  it('shows existing pins and exclusions', async () => {
    mockApi({ ...base(), 'GET /api/jobs/1/documents': jobDocs({ pinned: [2], excluded: [1] }) })
    renderPage()

    expect(await screen.findByLabelText('Selection of Laborationslärare')).toHaveValue('pin')
    expect(screen.getByLabelText('Selection of Systemutvecklare (praktik)')).toHaveValue('exclude')
  })

  it('follows a generation run and then shows the new documents', async () => {
    let polls = 0
    let docFetches = 0
    mockApi({
      'GET /api/jobs/1': jobDetail,
      'GET /api/cv/entries': entries,
      'GET /api/runs/9': () => {
        polls++
        return polls < 2
          ? { run: run({ id: 9, kind: 'DOCUMENTS', jobId: 1, status: 'RUNNING', step: 'CV', progress: 30, message: 'Writing the CV' }), profile: null }
          : { run: run({ id: 9, kind: 'DOCUMENTS', jobId: 1, message: 'Documents ready for review' }), profile: null }
      },
      'GET /api/jobs/1/documents': () => (++docFetches < 2 ? { cv: null, letter: null, selection: null, pinned: [], excluded: [] } : jobDocs()),
    })
    renderPage('/documents/job/1?run=9')

    expect(await screen.findByRole('progressbar', { name: 'Generation progress' })).toHaveAttribute('aria-valuenow', '30')
    expect(screen.getByText('Writing the CV')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Generate documents' })).not.toBeInTheDocument()
    expect(await screen.findByDisplayValue('Javautvecklare | Spring Boot', undefined, { timeout: 8000 })).toBeInTheDocument()
    expect(screen.getByText('Documents ready for review')).toBeInTheDocument()
  }, 12000)

  it('regenerates both documents and follows the new run', async () => {
    const calls = mockApi({
      ...base(),
      'POST /api/jobs/1/documents': { runId: 12 },
      'GET /api/runs/12': { run: run({ id: 12, kind: 'DOCUMENTS', jobId: 1, status: 'RUNNING', step: 'SELECT', progress: 10, message: 'Choosing the facts' }), profile: null },
    })
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Regenerate both' }))

    expect(await screen.findByText('Choosing the facts')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({})
  })

  it('asks before regenerating documents that were edited, and keeps them when declined', async () => {
    const calls = mockApi({
      ...base(),
      'GET /api/jobs/1/documents': jobDocs({ cv: cvDoc({ version: 2 }) }),
      'POST /api/jobs/1/documents': { runId: 12 },
      'GET /api/runs/12': { run: run({ id: 12, kind: 'DOCUMENTS', jobId: 1, status: 'RUNNING', step: 'SELECT', progress: 10, message: 'Choosing the facts' }), profile: null },
    })
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Regenerate both' }))
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('replaces both documents'))
    expect(calls.some((c) => c.method === 'POST')).toBe(false)

    confirm.mockReturnValue(true)
    await userEvent.click(screen.getByRole('button', { name: 'Regenerate both' }))
    expect(await screen.findByText('Choosing the facts')).toBeInTheDocument()
    confirm.mockRestore()
  })

  it('also asks when a document was edited (version above 1)', async () => {
    mockApi({ ...base(), 'GET /api/jobs/1/documents': jobDocs({ letter: jobDocs().letter && { ...jobDocs().letter!, version: 3 } }) })
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Regenerate both' }))

    expect(confirm).toHaveBeenCalled()
    confirm.mockRestore()
  })

  it('offers to generate when a job has no documents yet', async () => {
    const calls = mockApi({
      ...base(),
      'GET /api/jobs/1/documents': { cv: null, letter: null, selection: null, pinned: [], excluded: [] },
      'POST /api/jobs/1/documents': { runId: 5 },
      'GET /api/runs/5': { run: run({ id: 5, kind: 'DOCUMENTS', status: 'QUEUED', step: 'SELECT', progress: 0, message: 'Queued' }), profile: null },
    })
    renderPage('/documents/job/1?from=7')

    await userEvent.click(await screen.findByRole('button', { name: 'Generate documents' }))

    expect(await screen.findByText('Queued')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ runId: 7 })
  })

  it('lists documents on the overview page with their approvals', async () => {
    mockApi({
      'GET /api/documents': [{ jobId: 1, title: 'Junior Java-utvecklare', employer: 'Comstream AB', language: 'sv', cv: true, letter: true,
        cvApproved: true, letterApproved: false, updatedAt: '2026-10-27T08:00:00Z' }],
      'GET /api/applications': [],
    })
    renderPage('/documents')

    const link = await screen.findByRole('link', { name: /Junior Java-utvecklare/ })
    expect(link).toHaveAttribute('href', '/documents/job/1')
    expect(within(link).getByRole('img', { name: 'CV: approved' })).toBeInTheDocument()
    expect(within(link).getByRole('img', { name: 'Cover letter: not approved' })).toBeInTheDocument()
  })

  describe('ask Claude', () => {
    it('sends the request with what is on screen, shows the reply and the refreshed version, and can undo it', async () => {
      let version = 1
      const revised = cvDoc({ version: 2, content: { ...cvDoc().content, headline: 'Backendutvecklare | Java' } })
      const calls = mockApi({
        ...base(),
        'GET /api/jobs/1/documents': () => (version === 1 ? jobDocs() : jobDocs({ cv: revised })),
        'POST /api/documents/100/chat': () => {
          version = 2
          return { reply: 'Bytte rubrik till Backendutvecklare.', changed: true, approvalRevoked: true, document: revised }
        },
        'PUT /api/documents/100': () => {
          version = 3
          return cvDoc({ version: 3 })
        },
      })
      renderPage()

      await userEvent.click(await screen.findByRole('button', { name: 'Put the most relevant experience first' }))

      expect(await screen.findByText('Bytte rubrik till Backendutvecklare.')).toBeInTheDocument()
      expect(screen.getByText(/Approval was removed/)).toBeInTheDocument()
      expect(await screen.findByDisplayValue('Backendutvecklare | Java')).toBeInTheDocument() // the editor shows the new version
      expect(screen.getByTitle('CV preview')).toHaveAttribute('src', '/api/documents/100/pdf?v=2#navpanes=0&view=FitH')
      const sent = calls.find((c) => c.url === '/api/documents/100/chat')!.body as { message: string; content: { headline: string }; history: unknown[] }
      expect(sent.message).toBe('Put the most relevant experience first')
      expect(sent.content.headline).toBe('Javautvecklare | Spring Boot')
      expect(sent.history).toEqual([])

      await userEvent.click(screen.getByRole('button', { name: 'Undo this change' }))
      await vi.waitFor(() => expect(calls.some((c) => c.method === 'PUT' && c.url === '/api/documents/100')).toBe(true))
      expect((calls.find((c) => c.method === 'PUT')!.body as { headline: string }).headline).toBe('Javautvecklare | Spring Boot')
      expect(await screen.findByText('Undone: the previous version is back.')).toBeInTheDocument()
    })

    it('includes unsaved edits, sends the conversation so far, and offers no undo when nothing changed', async () => {
      const calls = mockApi({
        ...base(),
        'POST /api/documents/100/chat': () => ({ reply: 'There is no Rust in your CV database.', changed: false, approvalRevoked: false, document: cvDoc() }),
      })
      renderPage()

      const headline = await screen.findByDisplayValue('Javautvecklare | Spring Boot')
      await userEvent.type(headline, ' och Kotlin')
      expect(screen.getByText(/Your unsaved edits are included/)).toBeInTheDocument()
      await userEvent.type(screen.getByLabelText('Your request to the AI'), 'Add that I know Rust{Enter}')
      expect(await screen.findByText('There is no Rust in your CV database.')).toBeInTheDocument()
      await userEvent.type(screen.getByLabelText('Your request to the AI'), 'Ok, then shorter{Enter}')

      await vi.waitFor(() => expect(calls.filter((c) => c.url === '/api/documents/100/chat')).toHaveLength(2))
      const first = calls.find((c) => c.url === '/api/documents/100/chat')!.body as { content: { headline: string } }
      expect(first.content.headline).toBe('Javautvecklare | Spring Boot och Kotlin')
      const second = calls.filter((c) => c.url === '/api/documents/100/chat')[1].body as { history: { role: string; text: string }[] }
      expect(second.history).toEqual([
        { role: 'user', text: 'Add that I know Rust' },
        { role: 'assistant', text: 'There is no Rust in your CV database.' },
      ])
      expect(screen.queryByRole('button', { name: 'Undo this change' })).not.toBeInTheDocument()
    })

    it('shows the server message in the conversation when Claude cannot do it', async () => {
      mockApi({
        ...base(),
        'POST /api/documents/100/chat': new Response(JSON.stringify({ error: 'Something went wrong: Claude answered in the wrong shape, so nothing was changed.' }), { status: 500 }),
      })
      renderPage()

      await userEvent.type(await screen.findByLabelText('Your request to the AI'), 'Change something{Enter}')

      expect(await screen.findByRole('alert')).toHaveTextContent('nothing was changed')
    })

    it('keeps a separate conversation for the cover letter', async () => {
      mockApi({ ...base(), 'POST /api/documents/100/chat': { reply: 'Done.', changed: false, approvalRevoked: false, document: cvDoc() } })
      renderPage()

      await userEvent.type(await screen.findByLabelText('Your request to the AI'), 'hello{Enter}')
      expect(await screen.findByText('Done.')).toBeInTheDocument()
      await userEvent.click(screen.getByRole('tab', { name: /Cover letter/ }))

      expect(screen.queryByText('Done.')).not.toBeInTheDocument()
      expect(screen.getByRole('heading', { name: 'Ask the AI to adjust this letter' })).toBeInTheDocument()
    })
  })

  it('locks every edit control while approved, and Unapprove unlocks them again exactly as before', async () => {
    let approved = true
    const calls = mockApi({
      ...base(),
      'GET /api/jobs/1/documents': () => jobDocs({ cv: cvDoc({ approved }) }),
      'POST /api/documents/100/unapprove': () => {
        approved = false
        return { document: cvDoc({ approved: false }), notice: 'An application for this job is open in the browser. Approve it again.' }
      },
    })
    renderPage()

    expect(await screen.findByText(/Approved and locked/)).toBeInTheDocument()
    const lockedField = document.querySelector('fieldset[disabled]')
    expect(lockedField).not.toBeNull()
    expect(within(lockedField as HTMLElement).getByRole('button', { name: 'Send' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Save and re-render' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Regenerate both' })).toBeDisabled()
    expect(screen.queryByRole('button', { name: /^Approve CV/ })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Unapprove' }))

    expect(await screen.findByRole('button', { name: 'Approve CV' })).toBeEnabled()
    expect(calls.some((c) => c.url === '/api/documents/100/unapprove' && c.method === 'POST')).toBe(true)
    expect(document.querySelector('fieldset[disabled]')).toBeNull()
    expect(screen.getByRole('alert')).toHaveTextContent('open in the browser')
    expect(screen.getByRole('button', { name: 'Regenerate both' })).toBeEnabled()
  })
})
