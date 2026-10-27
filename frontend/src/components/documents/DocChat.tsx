import { useState } from 'react'
import { type ChatTurn, type CvContent, type DocView, type LetterContent, useDocumentChat, useSaveDocument } from '../../api/documents'
import { Button, TextInput } from '../Form'

const SUGGESTIONS: Record<DocView['kind'], string[]> = {
  CV: ['Make the profile shorter', 'Put the most relevant experience first', 'Use plainer wording'],
  LETTER: ['Make it shorter', 'Make the tone warmer', 'Make the opening more specific'],
}

/**
 * "Ask Claude": fine adjustments in the user's own words. The reply and the refreshed document appear here and in the preview;
 * Claude only uses facts from the CV database, and a change removes approval. The conversation is kept by the page, because the
 * editor starts over (new version) after every change.
 */
export function DocChat({ doc, jobId, draft, dirty, turns, onTurns }: {
  doc: DocView
  jobId: number
  draft: CvContent | LetterContent
  dirty: boolean
  turns: ChatTurn[]
  onTurns: (turns: ChatTurn[]) => void
}) {
  const [text, setText] = useState('')
  const chat = useDocumentChat(jobId)
  const save = useSaveDocument(jobId)
  const lastIndex = turns.length - 1

  const send = (message: string) => {
    const asked = message.trim()
    if (!asked || chat.isPending) return
    const withQuestion = [...turns, { role: 'user' as const, text: asked }]
    onTurns(withQuestion)
    setText('')
    chat.mutate(
      { id: doc.id, message: asked, content: draft, history: turns },
      {
        onSuccess: (r) =>
          onTurns([...withQuestion, { role: 'assistant', text: r.reply, ...(r.changed ? { previous: draft, approvalRevoked: r.approvalRevoked } : {}) }]),
        onError: (e) => onTurns([...withQuestion, { role: 'assistant', text: e.message, error: true }]),
      },
    )
  }

  const undo = (turn: ChatTurn, index: number) => {
    if (!turn.previous) return
    save.mutate({ id: doc.id, content: turn.previous }, {
      onSuccess: () => onTurns(turns.map((t, i) => (i === index ? { ...t, previous: undefined } : t)).concat({ role: 'assistant', text: 'Undone: the previous version is back.' })),
    })
  }

  return (
    <section aria-label="Ask Claude" className="mb-5 rounded-lg border border-[var(--line)] bg-[var(--panel)] p-4">
      <h3 className="mb-1 text-sm font-semibold">Ask Claude to adjust this {doc.kind === 'CV' ? 'CV' : 'letter'}</h3>
      <p className="mb-3 text-xs text-[var(--muted)]">
        Claude only uses facts from your CV database. {dirty ? 'Your unsaved edits are included. ' : ''}A change saves a new version and removes approval.
      </p>
      {turns.length > 0 && (
        <ol role="log" aria-live="polite" aria-label="Conversation" className="mb-3 flex max-h-56 flex-col gap-2 overflow-y-auto text-sm">
          {turns.map((t, i) => (
            <li key={i} className={t.role === 'user' ? 'self-end rounded-lg bg-navy px-3 py-1.5 text-white' : `self-start rounded-lg px-3 py-1.5 ${t.error ? 'bg-red-50 text-red-700 dark:bg-red-950' : 'bg-[var(--surface)]'}`}>
              <span className="block">{t.error ? <span role="alert">{t.text}</span> : t.text}</span>
              {t.approvalRevoked && <span className="mt-1 block text-xs text-amber-700 dark:text-amber-400">Approval was removed: review and approve again so it can be uploaded.</span>}
              {t.previous && i === lastIndex && (
                <button type="button" onClick={() => undo(t, i)} disabled={save.isPending} className="mt-1 text-xs underline">Undo this change</button>
              )}
            </li>
          ))}
          {chat.isPending && <li className="self-start px-3 py-1.5 text-[var(--muted)]">Claude is working on it...</li>}
        </ol>
      )}
      <div className="flex items-center gap-2">
        <TextInput aria-label="Your request to Claude" placeholder="For example: make the second paragraph shorter" value={text} disabled={chat.isPending}
          onChange={(e) => setText(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && send(text)} />
        <Button onClick={() => send(text)} disabled={!text.trim() || chat.isPending}>Send</Button>
      </div>
      {turns.length === 0 && (
        <div className="mt-2 flex flex-wrap gap-2">
          {SUGGESTIONS[doc.kind].map((s) => (
            <button key={s} type="button" onClick={() => send(s)}
              className="rounded-full border border-[var(--line)] px-3 py-1 text-xs text-[var(--muted)] hover:border-[var(--accent)] hover:text-[var(--ink)]">{s}</button>
          ))}
        </div>
      )}
      {save.isError && <p role="alert" className="mt-2 text-sm text-red-600">{save.error.message}</p>}
    </section>
  )
}
