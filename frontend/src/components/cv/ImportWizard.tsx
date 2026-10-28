import { useState } from 'react'
import { type CommitEntry, type ImportPreview, useDiscardImport, useImportCommit, useImportJob, useStartImport } from '../../api/cv'
import { useEasedProgress } from '../../lib/useEasedProgress'
import { useDraft } from '../../lib/useDraft'
import { Button } from '../Form'
import { Panel } from '../Panel'
import { Processing } from '../ui/Processing'
import { FileDrop } from './FileDrop'

/** What the user decided for one found entry: add it to the stored entry it matches, or make it a new entry. */
type Choice = { include: boolean; merge: boolean }

const defaults = (p: ImportPreview): Choice[] =>
  // A match with nothing new to add is left out by default; everything else is included, matches merged into what is stored.
  p.entries.map((e) => ({ include: e.matchEntryId === null || e.newFacts > 0, merge: e.matchEntryId !== null }))

/**
 * Importing more CVs or letters. The reading runs on the server, so leaving the page does not stop it: it is picked up here again
 * (and announced in the top bar when it finishes). Entries the database already has are not duplicated: new facts are added to them.
 */
export function ImportWizard() {
  const [files, setFiles] = useDraft<File[]>('cv.import.files', [])
  const start = useStartImport()
  const job = useImportJob()
  const discard = useDiscardImport()
  const commit = useImportCommit()
  const pct = useEasedProgress(start.isPending || job.data?.status === 'RUNNING')
  const [choices, setChoices] = useState<Choice[] | null>(null)
  const [result, setResult] = useState<{ created: number; enriched: number; facts: number } | null>(null)

  if (result) {
    return (
      <Panel title="Import">
        <p role="status" className="text-sm text-[var(--success)]">
          {result.created} new {result.created === 1 ? 'entry' : 'entries'}, {result.enriched} {result.enriched === 1 ? 'entry' : 'entries'} enriched,
          {' '}{result.facts} {result.facts === 1 ? 'fact' : 'facts'} added. Review them in the Entries tab.
        </p>
        <div className="mt-3"><Button variant="ghost" onClick={() => setResult(null)}>Import more</Button></div>
      </Panel>
    )
  }

  const current = job.data
  if (start.isPending || current?.status === 'RUNNING') {
    return (
      <Panel>
        <Processing title="Reading your CV" percent={pct} etaSeconds={null} fallback="This takes a minute or two. You can leave this page: it carries on, and you will be told when it is ready." message={current?.files.join(', ') ?? ''} />
      </Panel>
    )
  }

  if (current?.status === 'FAILED' || start.isError) {
    return (
      <Panel title="Import">
        <p role="alert" className="text-sm text-[var(--danger)]">{start.isError ? start.error.message : current?.message}</p>
        <div className="mt-3"><Button variant="ghost" onClick={() => { start.reset(); if (current) discard.mutate(current.runId) }}>Try again</Button></div>
      </Panel>
    )
  }

  const preview = current?.status === 'DONE' ? current.preview : null
  if (!preview) {
    return (
      <Panel title="Import from CV or letters">
        <p className="mb-4 text-sm text-[var(--muted)]">What is new is added; what you already have is enriched, never duplicated.</p>
        <FileDrop files={files} onFiles={setFiles} label="CV and letter files" title="Drop a CV or letter here" />
        <div className="mt-4">
          <Button disabled={files.length === 0} onClick={() => start.mutate(files, { onSuccess: () => setFiles([]) })}>Read {files.length > 1 ? 'these files' : 'this file'}</Button>
        </div>
      </Panel>
    )
  }

  const picks = choices ?? defaults(preview)
  const set = (i: number, patch: Partial<Choice>) => setChoices(picks.map((c, j) => (j === i ? { ...c, ...patch } : c)))
  const chosen = preview.entries.flatMap((e, i): CommitEntry[] =>
    picks[i].include ? [{ entry: e.entry, mergeIntoEntryId: picks[i].merge ? e.matchEntryId : null }] : [])

  return (
    <Panel title="Review before importing">
      <p className="mb-4 text-sm text-[var(--muted)]">
        {preview.entries.length} {preview.entries.length === 1 ? 'entry' : 'entries'} found in {preview.files.join(', ')}. Matches are added to the entry you already have.
      </p>
      <ul className="flex flex-col gap-3">
        {preview.entries.map(({ entry, matchEntryId, matchTitle, newFacts }, i) => (
          <li key={i} className="rounded-lg border border-[var(--line)] p-3">
            <label className="flex items-start gap-2">
              <input type="checkbox" checked={picks[i].include} onChange={(e) => set(i, { include: e.target.checked })} aria-label={`Import ${entry.title}`} />
              <span>
                <span className="font-medium">{entry.title}</span>
                <span className="text-sm text-[var(--muted)]">
                  {' '}{[entry.organisation, entry.startYm && `${entry.startYm} to ${entry.ongoing ? 'now' : (entry.endYm ?? '?')}`].filter(Boolean).join(', ')}
                </span>
              </span>
            </label>
            {matchEntryId !== null && (
              <p className="ml-6 mt-1 flex flex-wrap items-center gap-x-3 text-xs">
                <span className="rounded-md bg-[var(--accent-soft)] px-1.5 py-0.5 font-medium text-[var(--accent)]">
                  {picks[i].merge ? (newFacts > 0 ? `Adds ${newFacts} ${newFacts === 1 ? 'fact' : 'facts'} to ${matchTitle}` : `Already in ${matchTitle}`) : 'Will be a new entry'}
                </span>
                <button type="button" className="text-[var(--muted)] underline underline-offset-2 hover:text-[var(--ink)]" onClick={() => set(i, { merge: !picks[i].merge })}>
                  {picks[i].merge ? 'Add as a new entry instead' : 'Add to the existing entry'}
                </button>
              </p>
            )}
            <ul className="ml-6 mt-2 list-disc text-sm text-[var(--muted)]">
              {entry.facts.map((f, j) => <li key={j}>{f.textEn ?? f.textSv}</li>)}
            </ul>
          </li>
        ))}
      </ul>
      {commit.isError && <p role="alert" className="mt-3 text-sm text-[var(--danger)]">{commit.error.message}</p>}
      <div className="mt-4 flex gap-2">
        <Button disabled={chosen.length === 0 || commit.isPending} onClick={() =>
          commit.mutate({ previewId: preview.previewId, entries: chosen, skills: preview.skills, profile: preview.profile }, { onSuccess: (r) => { setChoices(null); setResult(r) } })}>
          Import {chosen.length} {chosen.length === 1 ? 'entry' : 'entries'}
        </Button>
        <Button variant="ghost" onClick={() => { setChoices(null); discard.mutate(current!.runId) }}>Discard</Button>
      </div>
    </Panel>
  )
}
