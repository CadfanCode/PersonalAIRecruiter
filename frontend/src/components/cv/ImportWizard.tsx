import { useState } from 'react'
import { type ImportPreview, useImportCommit, useImportPreview } from '../../api/cv'
import { Button } from '../Form'
import { Panel } from '../Panel'

export function ImportWizard() {
  const [files, setFiles] = useState<File[]>([])
  const [preview, setPreview] = useState<ImportPreview | null>(null)
  const [included, setIncluded] = useState<Set<number>>(new Set())
  const analyse = useImportPreview()
  const commit = useImportCommit()

  const reset = () => {
    setFiles([])
    setPreview(null)
    analyse.reset()
  }

  if (commit.isSuccess && !preview) {
    return (
      <Panel title="Import">
        <p role="status" className="text-sm text-[var(--success)]">
          Imported {commit.data.entryIds.length} entries with {commit.data.facts} facts. Review them in the Entries tab.
        </p>
        <div className="mt-3">
          <Button variant="ghost" onClick={() => { commit.reset(); reset() }}>
            Import more
          </Button>
        </div>
      </Panel>
    )
  }

  if (!preview) {
    return (
      <Panel title="Import from CV or letters">
        <p className="mb-3 text-sm text-[var(--muted)]">
          Upload PDF, DOCX, TXT or MD files. The AI splits them into entries and facts. Nothing is saved until you confirm.
        </p>
        <input
          type="file"
          multiple
          aria-label="CV and letter files"
          accept=".pdf,.docx,.txt,.md"
          onChange={(e) => setFiles(Array.from(e.target.files ?? []))}
        />
        <div className="mt-3 flex items-center gap-3">
          <Button
            disabled={files.length === 0 || analyse.isPending}
            onClick={() =>
              analyse.mutate(files, {
                onSuccess: (p) => {
                  setPreview(p)
                  setIncluded(new Set(p.entries.map((e, i) => (e.duplicateOfEntryId === null ? i : -1)).filter((i) => i >= 0)))
                },
              })
            }
          >
            {analyse.isPending ? 'Reading...' : 'Analyse'}
          </Button>
          {analyse.isPending && <span className="text-sm text-[var(--muted)]">The AI is reading the files, this can take a minute.</span>}
        </div>
        {analyse.isError && (
          <p role="alert" className="mt-3 text-sm text-[var(--danger)]">
            {analyse.error.message}
          </p>
        )}
      </Panel>
    )
  }

  const toggle = (i: number) => {
    const next = new Set(included)
    if (next.has(i)) next.delete(i)
    else next.add(i)
    setIncluded(next)
  }

  return (
    <Panel title="Review before importing">
      <p className="mb-3 text-sm text-[var(--muted)]">
        {preview.entries.length} entries found in {preview.files.join(', ')}. Entries that already exist are unchecked.
        You can edit everything after importing.
      </p>
      <ul className="flex flex-col gap-3">
        {preview.entries.map(({ entry, duplicateOfEntryId }, i) => (
          <li key={i} className="rounded-md border border-[var(--line)] p-3">
            <label className="flex items-start gap-2">
              <input type="checkbox" checked={included.has(i)} onChange={() => toggle(i)} aria-label={`Import ${entry.title}`} />
              <span>
                <span className="font-medium">{entry.title}</span>
                <span className="text-sm text-[var(--muted)]">
                  {' '}
                  {[entry.organisation, entry.startYm && `${entry.startYm} – ${entry.ongoing ? 'present' : (entry.endYm ?? '?')}`]
                    .filter(Boolean)
                    .join(', ')}
                </span>
                {duplicateOfEntryId !== null && (
                  <span className="ml-2 rounded bg-[var(--warn-soft)] px-1.5 py-0.5 text-xs text-[var(--warn)]">already in database</span>
                )}
              </span>
            </label>
            <ul className="ml-6 mt-2 list-disc text-sm text-[var(--muted)]">
              {entry.facts.map((f, j) => (
                <li key={j}>{f.textEn ?? f.textSv}</li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
      {commit.isError && (
        <p role="alert" className="mt-3 text-sm text-[var(--danger)]">
          {commit.error.message}
        </p>
      )}
      <div className="mt-4 flex gap-2">
        <Button
          disabled={included.size === 0 || commit.isPending}
          onClick={() =>
            commit.mutate(
              { previewId: preview.previewId, entries: preview.entries.filter((_, i) => included.has(i)).map((e) => e.entry), skills: preview.skills, profile: preview.profile },
              { onSuccess: () => setPreview(null) },
            )
          }
        >
          Import {included.size} {included.size === 1 ? 'entry' : 'entries'}
        </Button>
        <Button variant="ghost" onClick={reset}>
          Cancel
        </Button>
      </div>
    </Panel>
  )
}
