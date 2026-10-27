import { useState } from 'react'
import { useEntries } from '../../api/cv'
import { type JobDocuments, useSaveSelection } from '../../api/documents'
import { Button } from '../Form'
import { Panel } from '../Panel'

type Mode = 'auto' | 'pin' | 'exclude'

/** Per-job pin and exclude of CV entries (SPEC 8.1). Applied the next time the documents are generated. */
export function SelectionPanel({ jobId, docs }: { jobId: number; docs: JobDocuments }) {
  const entries = useEntries()
  const save = useSaveSelection(jobId)
  const initial = (id: number): Mode => (docs.pinned.includes(id) ? 'pin' : docs.excluded.includes(id) ? 'exclude' : 'auto')
  const [modes, setModes] = useState<Record<number, Mode>>({})
  const mode = (id: number) => modes[id] ?? initial(id)
  const reason = (id: number) => docs.selection?.entries.find((e) => e.entryId === id)

  const submit = () => {
    const all = entries.data ?? []
    save.mutate({
      pinned: all.filter((e) => mode(e.id) === 'pin').map((e) => e.id),
      excluded: all.filter((e) => mode(e.id) === 'exclude').map((e) => e.id),
    })
  }

  return (
    <Panel title="CV entries for this job">
      <p className="mb-3 text-xs text-[var(--muted)]">
        Pin an entry to always include it, exclude it to never use it. Regenerate to apply.
      </p>
      <ul className="flex flex-col gap-1.5">
        {(entries.data ?? []).filter((e) => !e.hidden).map((e) => {
          const sel = reason(e.id)
          return (
            <li key={e.id} className="flex items-center gap-3 text-sm">
              <select
                aria-label={`Selection of ${e.title}`}
                value={mode(e.id)}
                onChange={(ev) => setModes({ ...modes, [e.id]: ev.target.value as Mode })}
                className="rounded-md border border-[var(--line)] bg-[var(--surface)] px-2 py-1 text-xs"
              >
                <option value="auto">Auto</option>
                <option value="pin">Pin</option>
                <option value="exclude">Exclude</option>
              </select>
              <span className={mode(e.id) === 'exclude' ? 'text-[var(--muted)] line-through' : ''}>{e.title}</span>
              {sel && <span className="text-xs text-[var(--muted)]" title={sel.reason}>used in {sel.use}</span>}
            </li>
          )
        })}
      </ul>
      <div className="mt-3 flex items-center gap-3">
        <Button variant="ghost" onClick={submit} disabled={save.isPending}>Save selection</Button>
        {save.isSuccess && <span role="status" className="text-sm text-emerald-600">Saved. Regenerate to apply.</span>}
        {save.isError && <span role="alert" className="text-sm text-red-600">{save.error.message}</span>}
      </div>
    </Panel>
  )
}
